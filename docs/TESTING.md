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

1. **Circle accuracy (integrator ground truth).** Flat unbanked turn,
   v0=20 m/s, Gl=1.5 ⇒ analytic radius v²/(g·Gl)=27.192 m, center =
   pos0 + R0·r. Assert: worst |distance-from-center − r| over the whole
   path < 1e-6 m and the path stays in the y=0 plane. RK4 (v8.0)
   measures < 1e-9 m; the old Euler stepper measured 10.0 cm. Do NOT use
   "closure error at the last sample" as the metric — the last sample
   lands up to dt before exact closure time, which alone contributes
   ~6 cm and made Euler look 4× better than it was (see D44).

2. **Default layout invariants (bake + integrate pipeline).** Build the
   shipped three-lane default (node tables live in `FvdDesigner`; consider
   extracting to a shared `DefaultLayout` class so tests and app share one
   source of truth). Assert: baked keyframes strictly increasing in t, all
   finite; end |pitch| < 3°; end U.y > 0.99. RK4 baseline (v8.0,
   start (0,21,0), v0 26): 271 keyframes, 1347 samples, 13.46 s,
   406.878 m, end pitch 0.2284°, U.y 0.999971, top speed 32.10 m/s,
   rail y 2.95–24.78. (Euler-era historical: 406.809 m, pitch 0.2300°.)

3. **Baker fuzz.** 200 seeded-random layouts (4–13 nodes, random values in
   ~2× the UI ranges), then scramble ALL handles with out-of-range values
   (dt exceeding segments, wrong signs, huge dv). Assert: baked output
   strictly monotonic in t, all values finite, zero exceptions. (Historical:
   0 violations.)

4. **Save round-trip.** DONE PROPERLY as of v9.0: save/load lives in
   `DesignIO` (D50), so tests exercise the real production code — the
   old duplicated-logic weakness is gone. The round-trip is asserted
   CANONICALLY in #11: save → load → save must be byte-identical, which
   subsumes field-by-field comparison at format precision.

5. **Banked-turn physics identity (recommended addition).** Bisect Gv for
   "turn ends level" at a 40° bank through the full pipeline and assert the
   result is within 2% of 1/cos(40°) ≈ 1.305. This single test cross-checks
   the ω equations, roll accumulation, and frame conventions.

6. **Handle squish invariance (added with fractional handles, v6).** Two-node
   segment with non-default handles (fracs 0.42/0.27, dv 0.35/−0.15), end
   node at t=2 vs t=1: assert `eval_wide(2u) ≈ eval_narrow(u)` for u in
   (0,1). Historical: max error 0.002 (bake-polyline sampling noise); assert
   < 0.005. Also re-assert test 2's invariants — after the v6 refactor they
   reproduced exactly (271 keyframes, 13.46 s, 406.8 m, pitch 0.23°,
   U.y 1.000).

7. **Split identity (v6.5).** `BezierBaker.splitSegment` on assorted
   segments (flat, angled, near-folded) at assorted split times: evaluating
   the 2-node original vs the 3-node split lane must agree everywhere.
   Historical: max diff 0.005 (bake-polyline sampling noise); assert < 0.01.

8. **NL2 CSV export (v7.8).** `Nl2CsvExporter` validated headlessly under
   `Locale.GERMANY` (comma-decimal trap — output must stay `Locale.US`):
   (a) straight 5 s line ⇒ 131 points over 130 m, spacing uniform to 1e-6,
   front=+X, up=+Y, left=−Z (the Z-mirror); (b) every row orthonormal and
   `up = front × left` (NL2's right-handed identity) to 5e-6 — the
   tolerance is %.6f text round-trip noise, do NOT tighten it; (c) +Gl
   integrates as a rider-right turn AND exports clockwise-from-above
   (`f_k × f_k+1` has y<0), +roll tips exported `up` toward `left` — the
   chirality pair that catches a wrong/missing mirror; (d) a violent
   hills+roll layout (67 m/s, 198 m drop): heartline export length matches
   the integrator's `s`, spine export is resampled by the OFFSET curve's
   own arc length (337.4 vs 343.2 m — they must differ), and every spine
   point sits on the offset POLYLINE within 1e-5 (measure distance to
   SEGMENTS, not to sample points — dense samples are ~0.7 m apart at that
   speed and nearest-point distance ~0.33 m is expected, not a bug);
   (e) count = round(total/segLen)+1 at 0.1 m and 10 m requests. All
   passed 2026-07-05 (scratch main; convert to JUnit with the rest);
   re-passed unchanged on the RK4 integrator (path lengths shifted
   ~1 m on the violent test layout, as expected).

9. **Resulting body rates (v8.0).** `FVDIntegrator.bodyRates`: straight
   line ⇒ all rates 0 (measured 0.0); flat circle (v0=20, Gl=1.5) ⇒ yaw
   = deg(g·Gl/v) = 42.141 °/s within 4e-4, pitch 0; constant 50 °/s roll
   ⇒ extracted roll within 1e-3; default layout ⇒ extracted roll tracks
   the baked roll lane within 0.09 °/s (skip ±5 edge samples — central
   differences degrade to one-sided there).

10. ~~Smoothing tool~~ removed in v8.1 (D47) — validation retired with it.

11. **Phase-1 gates (v9.0, D50 — scratch Phase1Gate; these BLOCK any
    further Phase-1/2 work if they break).**
    (a) BIT-FOR-BIT: TrackCompiler.compile on the default document (one
    piece at (0,21,0) yaw 0, one force section, vDesign = v0 = 26) must
    equal the old direct `integrateTrack(bake(lanes), opts)` on RAW
    DOUBLE BITS for every field of every sample (t, s, pos, T, U, R, v,
    Gv, Gl — all 1347). Any new arithmetic on that path (an extra
    normalize, a quaternion round-trip, reordered adds) fails this.
    (b) Pass D pass-through: `trueV[i]` bitwise equals `Sample.v` while
    single-section (same formula, same evaluation order — deliberate).
    (c) FVDD3 canonical round-trip: save → load → save byte-identical,
    on a document with yaw, broken node, straight-lock, speed region and
    non-default cosmetics.
    (d) FVDD2 fixture loads as 1 piece/1 section (start → placement,
    v0 → v0 + vDesign, 7- and 8-field n lines, cosmetics); missing
    start/v0 default to (0,21,0)/26. FVDD1 fixture: absolute-second
    handles convert to fractions (0.5 s over a 2 s segment → 0.25).
    (e) Piece independence (§0 connect invariance): in a two-piece
    document, editing piece A's lanes leaves piece B's WORLD geometry
    bitwise unchanged (t/s rebase aside), and the edit demonstrably
    changed A. (f) Changing global v0 leaves ALL geometry bitwise
    unchanged — shape depends on vDesign only. (g) Quat exactness:
    IDENTITY.rotate returns its argument bitwise; aboutY(0) == IDENTITY;
    fromFrame→T()/U() round-trips < 1e-12. (h) Sections (v9.5, D55):
    Coaster.continuation holds the previous section's final values
    (2 nodes, 1 s, given vDesign); a two-section piece compiles with
    strictly increasing t, no position jump and continuous frames at
    the boundary; editing section B leaves section A BITWISE unchanged;
    the canonical FVDD3 round-trip runs on a two-section document.
    All passed 2026-07-05/06.

12. **Phase-2 geo gates (v10.0, D58 — scratch Phase2Gate; 28 checks,
    supersedes nothing: #11's bit-for-bit and pass-D checks are
    RE-RUN inside it and must stay green).**
    (a) Constant-rate analytics: 30 °/s pitch / 45 °/s yaw / 50 °/s
    roll geo sections produce EXACTLY rate × t_last of angle change —
    assert against the LAST SAMPLE's t, not the nominal duration: the
    integrator loop (force and geo alike) records its final sample at
    the largest accumulated t ≤ duration, so a 2 s section's last
    sample can land at 1.99 s (float accumulation; pre-existing
    cadence, do not "fix" the test by padding durations).
    (b) Pure pitch stays in the x-y plane; pure roll leaves the path
    straight; level yaw holds height and up; a dive at vDesign 10
    changes pitch by exactly rate × t (rate integration is
    speed-independent even though the SHAPE is not).
    (c) Chirality: geo +yaw turns the same direction as force +Gl.
    (d) EQUIVALENCE CANARY (the strong one): feed the default layout's
    resulting bodyRates back in as geo keyframes at the same
    entry/vDesign — end position within 2 mm over 407 m, end frame
    within 0.001°. A sign error anywhere in Ω/quaternion/frame
    conventions diverges wildly here.
    (e) Mixed force→geo→force chain: t strictly increasing, no
    boundary jump, force prefix BITWISE equal to the solo compile;
    editing the geo section or changing v0 moves nothing upstream
    (§0); FVDD3 round-trip byte-identical with the geo section aboard,
    modes/vDesign/flags surviving.
    (f) Felt-G recording: straight level geo = exactly 1 g / 0;
    pull-up entry Gv = ω·v/g + 1. Coaster.holdSection and
    GeoSection.straight hold their values.
    All passed 2026-07-06.

13. **Reversal gates (v10.9, D67 — in the same scratch Phase2Gate).**
    Forward vs reversed compile of the default layout at the same
    entry: same count; PAIRWISE DISTANCES identical to 1e-9 (the §9
    "same point set" gate, robust to the rigid placement); U.y, v and
    Gv BITWISE equal per mirrored sample and Gl bitwise NEGATED (the
    canonical run at the ENTRY HEIGHT makes this exact — a canonical
    run at y=0 was 1 ulp off, which is why the entry-height rule
    exists); t/s ascend in traversal order; the reversed first sample
    sits exactly on the entry pose with exact horizontal heading;
    force + reversed-force chain compiles continuous and monotonic;
    a reversed geo section with +50 °/s roll reads +50 in passA —
    roll rate is traversal-INVARIANT (T and the bank rate both flip
    and cancel; felt Gl flips instead). All passed 2026-07-06.

14. **Straight/curve gates (v11.0, D68 — same scratch Phase2Gate).**
    (a) Analytic elements: a 50 m straight lands EXACTLY (0 error, s
    bitwise == total length — the Newton landing); an R20 90° dir-0
    curve is an exact circle (max radial error < 1e-7, endpoint and
    −z heading to 1e-6°); dir 90 pulls UP in the vertical plane with
    exact pitch; negative angle mirrors the turn. Felt G: straight
    level = 1 g/0; flat turn Gl = v²/Rg; pull-up entry Gv = v²/Rg + 1
    (all 1e-9).
    (b) SHARP straight→arc→straight chain ends on the ANALYTIC
    heading to 1e-6° — this rides on the integrator sub-stepping at
    profile breakpoints with side-capped lookups (stepping OVER a
    curvature step cost ~0.02–0.13° per crossing before; the k4 stage
    read the next element's κ).
    (c) Smoothing: the same chain with 6 m blends ends on the SAME
    analytic heading (symmetric sine blend integrates to the mean
    curvature — turn angle preserved exactly for straight↔arc), arc
    length bitwise unchanged, path visibly eased; smoothIn 0 is
    BITWISE identical to sharp; blends overflowing a short middle
    element clamp proportionally and still keep the turn.
    (d) Reversal: same point set (pairwise distances 1e-9), entry
    pose landed, t/s ascend.
    (e) Chain + §0: force→s/c compile continuous; force prefix
    bitwise == solo; mirroring the s/c turn moves ONLY the s/c
    stretch. Spans (new compile output): one per section, contiguous
    index ranges, exact t/s chaining, s/c span length == authored.
    (f) FVDD3 round-trip byte-identical with force+geo+s/c aboard;
    kinds/radius/angle/direction/smoothing/reversed survive; loaded
    document compiles.
    (g) Roll (v11.1, D69): RATE 40 °/s on a straight = exact twist
    (rate × last-sample t) with the spine unbent (endpoint 1e-9);
    GLOBAL 25° holds the bank vs the horizon to 1e-9 and the
    projection never moves the position; positive bank twists the
    same way as +roll rate; the USER SCENARIO — 20° dip then a 90°
    flat turn — stays LEVEL under GLOBAL 0 (R.y ~1e-17) while the
    same chain under RATE 0 drifts to R.y 0.342 (the v11.0 cone
    behavior, kept for compatibility); roll mode/value round-trip on
    elem lines; v11.0-format elem lines (no roll fields) load as
    RATE 0 so old saves keep their geometry. All passed 2026-07-06.

15. **Piece gates (v12.0 D70, revised v12.1 D71 — same scratch
    Phase2Gate).**
    Split at an EXACTLY LEVEL boundary: the two-piece compile is
    BITWISE the original ride plus one duplicated boundary sample (a
    new piece's first sample is kept, not dropped), and that sample
    sits exactly ON the boundary. Split at a PITCHED boundary: ALSO
    bitwise (v12.1 — the span's exact exit pose lands in the new
    piece's baseOrient; the v12.0 yaw-only re-lay is gone per user
    rule "splitting must not change orientation at all"). Attach
    after a split: bitwise the original, count included. Moving
    piece 2 (x/z/yaw): piece 1 bitwise untouched (§0) and piece 2
    transforms RIGIDLY (pairwise distances 1e-9) onto its new origin.
    FVDD3 round-trip byte-identical with two placed pieces (piece
    lines carry the base-orientation quat at %.10f — under the
    loader's re-normalize threshold); placements survive.
    PIECE REVERSAL (v12.1): on a level straight+turn chain,
    reversePiece flips the section list to train order with toggled
    flags, starts exactly at the old exit (1e-12), ends at the old
    entry (1e-9 — the track STAYS PUT), preserves arc length;
    reversing again restores the model EXACTLY (same section objects,
    original order, flags cleared) and the geometry to 4e-14 m.
    All passed 2026-07-07.

16. **Bridge gates (v13.0 D72, revised for v13.1 D73 — same scratch
    Phase2Gate).**
    Between two arbitrarily posed endpoints (yawed + banked): both
    endpoint positions land to 1e-12, tangent DIRECTIONS match both
    seams to 1e-6°, roll (U) matches both ends (~1e-6°, the eased
    twist); t/s monotonic, all finite. Collinear matched endpoints
    with zero seam curvature degenerate to an EXACT straight line
    (y/z to 1e-12, s to 1e-9, felt 1 g/0). Two-piece chain with a
    fresh bridge: whole compile continuous (the gap is closed) and
    the bridge exit sits EXACTLY on the next piece's entry (1e-12).
    LIVE-FOLLOW (v13.1, replaced the v13.0 freeze): moving/yawing the
    target piece re-joins on the NEXT COMPILE to 1e-12 with the chain
    still continuous; deleting the target ORPHANS the bridge — its
    samples stay bitwise frozen at the last capture. G²/G³ (v13.1,
    septic v13.2):
    one-sided |ΔT|/Δs probes on both sides of each seam agree
    (arc 0.0501 vs bridge 0.0502 entering; 0.0400 vs 0.0400 leaving —
    the G¹ bridge read ~0 on its side). OPTIMIZE PLACEMENT: aligning
    the target dead ahead matches headings to 1e-9 and the bridge
    straightens to a 1.4e-14 m max chord deviation. movePiece reorders
    an arbitrary bridge target to follow the bridging piece. FVDD3
    round-trip byte-identical with a bridge aboard (bpose/bacc at
    %.10f).
    SEAM SMOOTHNESS (v13.2, D74): on a rolling straight bridged to a
    dead-ahead lower/slower piece — a real v mismatch (energy would
    land ~7 m/s off the target's design speed): the far seam has NO
    speed step (20.000 vs 20.000); the roll rate flows through
    (~15 °/s both sides — the old twist snapped it to 0);
    pitch/yaw/roll rates step ≤ 3.5 °/s per sample into the bridge
    and ≤ 8 °/s across the exit window measured clear of the
    duplicate boundary sample (bodyRates' zero-dt rows there are a
    measurement artifact, probed) — v13.1-era snaps measured 15-44+.
    The `bdyn` line round-trips byte-identical. NOTE the honest
    limit, probed and documented in D74: cramped/conflicting bridges
    (5 m dive over a 20 m gap; entry curving away from the target
    heading) legitimately need violent-but-smooth transients — that
    is the geometry, not the seams. All passed 2026-07-07.

17. **v13.3 probes (scratch SeamProbe + BridgeSmooth; findings in
    D75).** SeamProbe: a geo section (pitch ending 12 °/s) into a
    force hold with a DELIBERATELY mismatched vDesign (26 vs 20),
    replicating the editor's B-matching on alternating sides. OLD
    felt-G-copy matching multiplies the seam pitch rate by the
    design-speed ratio every round: 12 → 42 °/s over 8 rounds, never
    converging (the user's Shift+B ping-pong). NEW rate-consistent
    matching settles at 12.00 °/s with < 0.01 °/s residual per press
    while the roll lane's authored copy stays fixed. BridgeSmooth:
    four bridge scenarios (drop-ahead, lateral S, 60° turnaround,
    short level gap) × tangent lengths 0.5-1.3 × gap — at 0.5·gap the
    end rates run 12-55× the interior (the "spiky ends, flat middle"
    report); at 1.0·gap the ratio is ~0.5-1.7 with max |dRate/dt|
    down 3-7×; above ~1.15·gap the interior bulge takes over. Basis
    for the 1.0·gap default. Both passed 2026-07-07.

18. **v13.4 probes (scratch SeamProbe2 + BridgeProbe2; findings in
    D76).** SeamProbe2: a geo section ending BANKED (40°) with sloped
    pitch/yaw at the seam into a force hold, alternating full
    three-lane Shift+B replication. v13.3 sampled matching: the
    values AND the seam frame creep every round (yaw 8.0 → 8.9,
    R.y 0.6157 → 0.6028 over 10 rounds; same with equal design
    speeds) — the linear extrapolation bias. v13.4 analytic matching:
    bit-stable from round 1, both speed cases. BridgeProbe2:
    (a) bridge into a REVERSED-then-EDITED piece — nominal entry
    pitch −44° vs actual head −11°, seam tangent/up still land at
    0.0000°; (b) deleting the bridge keeps two pieces, placements
    bit-identical, a real 45 m gap, all samples finite; (c) bisector
    placement on a −22° dive into a level head: max |dRate/dt| 1106
    → 529 °/s² vs dead-ahead; (d) captured roll accelerations pull
    the bridge's entry roll-rate slope from −90 toward the chain's
    +29 °/s² (zeroed accs = the old flat shelf). All passed
    2026-07-07.

19. **v13.4 continuation placement (scratch SplitTurnProbe; D77).**
    The user's acceptance test, headless: split a CONSTANT turn
    between two sections, bridge the pieces back, optimize at
    L = 15/30/45 m (34°–103° sweeps). Flat s/c circle (rails level)
    AND a banked LEVEL force turn (entry frame banked 30°,
    Gv = 1/cos 30°): the bridge continues THE SAME circle — rate
    deviation ≤ 0.11 °/s (banked case ≤ 0.01), radial sag ≤ 7 mm,
    height flat, compiled length within 5 % of the request; a
    straight chain reduces to dead-ahead to 1e-12 m. Notable dead
    ends hit on the way (kept for honesty): an s/c GLOBAL-bank arc
    is NOT a body-constant fixture (bank is held vs the horizon —
    the "turn" is a tilted circle that climbs), and a geo coning
    turn isn't per-meter constant either (v changes with height, so
    κ = ω/v drifts) — the march models body-state-per-meter, which
    is exactly what level force/geo constant turns are. Also passed
    2026-07-07.

20. **v13.5 probes (scratch SeamJolt + UnfreezeProbe; D78).**
    SeamJolt: geo|force seams with DRIFTED design speeds (26 vs 20),
    banked + rolling, both orders — without the re-freeze the felt
    Gv/Gl display steps 0.06–0.10 g exactly at the boundary with the
    rates already continuous (the user's jolt); with B-matching's
    vDesign re-freeze the step collapses to 0.002–0.004 g and rates
    stay continuous. UnfreezeProbe: a violent whipsaw bridge
    (27.5 m) unfreezes to a 14-node/lane geo section within 21 cm /
    0.6° / 0.07 g; a tame continuation bridge (split turn) lands
    1.5 mm / 0.46° — the residuals are the dt-grid cadence quantum
    plus ~one integration step of rate on the exit pose. All passed
    2026-07-07.

21. **TRUE-speed pass gates (§5 round A, v13.6 D79 — canonical, in
    Phase2Gate.trueSpeed).** (a) No-drift identity: a '+'-style
    chain frozen at the exact design-end speed keeps trueV == design
    v to 3.6e-15 m/s; the single-force bit-for-bit gate additionally
    pins trueV == Sample.v BITWISE. (b) Both seam truth directions:
    a felt-matched force→force seam whose downstream vDesign is
    drifted −6 m/s reads 0.0000 g in the design felt while the TRUE
    felt steps 4.7 g (the hidden kink D78 chased); a stale-vDesign
    s/c→s/c seam continuing the same R20 arc steps 1.31 g in design
    felt while the TRUE felt stays smooth to 0.018 g (the false
    alarm suppressed); inside the staled section |true − design|
    reaches 1.30 g (the companion-stroke trigger). (c) A DETACHED
    piece is its own run: its trueV starts at its OWN first
    section's vDesign (24.000, not the 17.4 that ride-start energy
    would give). (d) A bridged run is ONE energy body: max
    trueV step between adjacent samples 0.018 m/s across both bridge
    seams, while inside the bridge trueV honestly diverges 6.8 m/s
    from the design speed blend and the target piece keeps run
    energy (24.8 m/s, not its stale 18). All passed 2026-07-08.

22. **Geo exit-pose contract (v13.6 D80 — canonical gate
    Phase2Gate.geoExitPose + scratch SeamOrient).** The user's
    "instant roll jump at geo→force seams": GeoIntegrator handed out
    its POST-LOOP quaternion — one RK4 step past the last recorded
    sample — as the exit orientation, rotating everything downstream
    by end-rate × dt (0.4387° on a 15/10/40 °/s fixture = |Ω|·dt
    exactly; the seam sample-gap twisted 0.80° of roll vs the
    interior's 0.40°/sample; zero end rates ⇒ ~0.0002°, the
    intermittency the user described). Post-fix: exit == the last
    sample's frame to 0.0°, seam rotation == the interior cadence
    (0.4000° roll / 0.4392° total), zero-rate and force-section
    controls clean. Gate: constant 15/10/40 geo section,
    angle(qEnd, fromFrame(last sample)) < 1e-4°. Passed 2026-07-08.

23. **CONVERT gates (v13.7 D81 — canonical, Phase2Gate.convertGates;
    UnfreezeProbe re-pointed at Coaster.convertToLanes).** Shape
    preservation, compile-before vs compile-after, measured at
    matched arc lengths (interior probes at 10%..90% + the end
    position): geo→force (constant 15/10/40 °/s, 2 s) 6.7 mm
    interior / 4.4 mm end; s/c→geo and s/c→force (straight + R20
    60° smoothed + straight) 16.7 mm / 12.2 cm; bridge→force
    9.8 cm interior (the document end is pinned by the next piece's
    own placement — the seam-residual story is #20's); force→geo
    across the WHOLE 407 m default layout 0.94 m interior / 1.30 m
    end = 0.23%/0.32%, the 0.1 s node carpet rounding the layout's
    ±50 °/s roll spikes and 0.2 s Gv steps (better relative error
    than #20's accepted 0.8%; "delete nodes to taste" applies).
    vDesign carries over; converted sections are the requested
    mode. All passed 2026-07-08.

24. **Bridge double-bump + determinism + optimal length (v13.8 D82 —
    scratch BridgeBump; SplitTurnProbe re-pointed at
    Coaster.optimizeBridge).** BridgeBump: a turn→straight optimize
    (R25 flat turn ending mid-arc, straight target, P+Y at 30 m)
    peaks INSIDE at x1.00 of the seam's own rate (pre-fix x2.20 —
    stale chord lens + linear-blend march); a roll 35→0 °/s blend
    peaks x1.12 at a fixed 35 m (the residual-bank twist demand) and
    x1.00 at the knee's optimal 56 m; OPTIMAL LENGTH is a pure
    function of the seam states (absolute candidate ladder — the
    chord-anchored first attempt wandered under mode ping-pong):
    P+Y → pos-only → P+Y returns 56.000 → 56.000 m exactly, a
    smooth split MOVED 51 m away and yawed 137° collapses to the
    1 m floor, and a SLOPED-seam geo split (all lanes ramping
    through the cut, matched values and slopes) moved anywhere
    collapses too — the floor IS the zero-distance attach cue;
    30→90→30 length round trips return lens AND shape
    to exactly 0.000 drift (pre-fix: 3.8 m lens, 4.3 cm shape per
    trip, compounding). SplitTurnProbe THROUGH the new optimize:
    better than the D77 baseline — flat s/c circle re-joins at
    0.06-0.12 °/s ripple with 0-8 mm radial (L = 15/30/45), the
    banked level force turn at 0.00-0.01 °/s with 0-1 mm; straight
    seams still dead-ahead to 1e-12. Dead ends kept for honesty:
    peak-rate lens cost corner-cut the circle (10-30 cm sag);
    arc-fraction deviation drowned in tangential offset; virtual
    candidates needed the B-side seam vectors YAW-ROTATED and the
    march's d-scaled jerk slopes needed a 2%-of-κ deadband (fit
    noise moved placements 10 cm). All passed 2026-07-08.

25. **Quintic march onset + optimizer settings (v13.9 D83 — scratch
    BridgeBump gained the onset pin). BOTH REVERTED in v13.10
    (D84).** The quintic blend (onset ratio 0.11 vs cubic ~0.4) was
    rolled back to CUBIC on user feel — graphs may bend right off
    the seam, and the quintic's mid-blend bulge read as shape
    weirdness; BridgeBump's onset assertion is retired to an
    informational print (cubic ≈ 0.33). The settings were withdrawn
    from the UI entirely (Options rows v13.9 → per-bridge rows
    v13.10-dev → gone); the tuned defaults are internal constants
    and every determinism/floor/ping-pong gate value held through
    the revert (knee 56 m, x1.00 overshoot). Kept here as the
    record of a probed-and-rejected direction.

26. **v13.10 bridge round (scratch SeamPause + BridgeBump/Phase2Gate
    additions; D84).** SeamPause: at a bridge→next-piece seam the
    sample stream used to hold a FULL-dt interval covering only the
    landing centimeters (the last bridge sample stamped ~dt late —
    the flattened graph tail) followed by an exact zero-advance,
    zero-dt DUPLICATE (the next piece's first sample — the POV
    "brief stop"); post-fix min |dPos| 0.066 m and min dt 0.0028 s
    at the seam, no interval under 0.25·dt anywhere (the landing
    merges into one ≤ 1.25·dt stride — full-step+runt pairs divided
    quaternion noise by tiny dt in the optimizer costs and briefly
    broke the sloped-seam floor collapse to 28 m; the BridgeBump
    gate caught it). Phase2Gate's seam assertion moved to poses
    (exit == next entry to 1e-12) + a no-duplicate step check.
    OPT_POS yaw hump: a 55°-yawed straight target at 30 m peaks
    68 °/s vs the 48 ideal constant turn and 30→33 m moves the peak
    9% (was: the septic absorbed the whole heading error — wild
    shapes, high length sensitivity). Run-clamped Pass A: a
    detached piece yawed 120° leaves the run-end rates at 0.1 °/s
    (differencing across the gap gave |Δ120°|/0.01 s ≈ 12000).
    All passed 2026-07-08.

27. **v13.11 gates (Phase2Gate.partialConvert +
    deleteKeepsHandles; D85).** PARTIAL bridge convert on the
    two-piece s/c fixture: LEFT at 40% → [sc, converted geo,
    bridge] with the whole compiled track within 0.138 m interior /
    0.000 m end of the original and the remaining bridge landing on
    the target to 1e-9; RIGHT at 55% → the piece splits at the cut
    pose, [sc, bridge] + a new force-lane piece whose samples end at
    the old target's entry within 0.35 m (fit residual), whole
    track 0.148 m / 0.000 m. DELETE keeps handle geometry: on a
    slope-fitted lane, removing an interior node preserves the
    survivor's departure slope EXACTLY (3.500 → 3.500) and its
    handle's absolute time run (0.0333 s — a plain remove doubled
    it, halving fitted slopes: the user's "handle snaps
    horizontal"). All passed 2026-07-08. (v13.12 revised the
    SEMANTICS on user rule — fractions kept, dv rescaled — and the
    gate now pins slope preserved + fraction bit-equal.)

28. **v13.12 march-spine bridges (Phase2Gate + BridgeBump re-runs;
    D86).** With optimized placements, the bridge geometry IS the
    continuation march: the OPT_POS 55°-yawed fixture rides ONE
    symmetric rate hump (17→69→1 °/s over 30 m) instead of the
    septic's end-heavy shape; turn→straight overshoot x1.00 with
    the onset ratio ~0.3 = the cubic blend's own smoothstep
    shoulder (printout only — the user explicitly accepts graphs
    bending off the seam); mode ping-pong P+Y → pos-only → P+Y
    returns 60.000 → 60.000 m; smooth/sloped splits moved anywhere
    still collapse to the 1 m floor; 30→90→30 hysteresis stays
    0.000; all septic-era gates (G² seams, orphan freeze, collinear
    line, SplitTurnProbe's D77 baselines, BridgeSmooth) pass
    UNCHANGED — un-optimized bridges (lastOptDist 0, old files)
    keep the septic path byte-stable. PARTIAL-CONVERT length pin
    (user bug: upstream edits re-lengthed the remainder, "optimize
    length suddenly becoming checked"): both surviving bridges come
    out optLen-OFF with lastOptDist = their share of the cut —
    gated LEFT 17.7 ≈ 17.9 m share, RIGHT 16.4 m vs a planted stale
    999. All passed 2026-07-09.

29. **v13.13 gates (Phase2Gate.orientAlign + partialConvert
    updates; D87).** Piece orientation deltas are decoupled Euler
    controls: pitch 10° raises the entry pitch only (heading bit-
    exact), roll 25° banks only (T untouched), zeros revert to
    baseOrient exactly. Optimize position and ORIENTATION: a target
    pitched −20° and banked 25° with pitch+roll UNLOCKED meets a
    level continuation at 0.00° pitch / −0.00° bank; with the
    default locks the same fixture keeps the user's −20° and the
    bridge lands on it to 1e-9 (the march's pitch hump). RIGHT
    partial-convert now keeps the track connected: 2 pieces, the
    lanes prepend to the target piece, samples joined across the
    lanes→old-track seam, whole compile within 0.15 m. Fixture
    lesson: an s/c GLOBAL-0 straight actively unbanks itself (real
    ~93 °/s head roll), so orientation-holding fixtures use
    zero-rate GEO sections. All passed 2026-07-09.

30. **v13.14 gates (Phase2Gate.partialConvertMarch; D88).** Partial
    convert honors the march claim: on an OPTIMIZED bridge with a
    pitch hump (pitched+turning geo entry, 30 m POS_YAW march —
    pre-fix, its fresh sub-march missed the unmoved cut by
    0.09/0.17 m > the 2 cm/0.3° landing gate and the graph silently
    fell to the septic; a placement-mode ping-pong re-placed it by
    hand), converting RIGHT and LEFT at 50% now plants SIG_STALE:
    the auto pass fires exactly ONCE on the first compile, one
    recompile settles, the re-place nudge is bounded (0.093/0.168 m
    — the former miss), and the placement sits ON the fresh march
    (a same-mode re-optimize moves 0.00000 m = marchSpine's landing
    condition, so the march renders — pre-fix this moved by the
    miss). Whole track holds ≤ 0.34 m (fit residual + nudge). A
    never-optimized (septic) bridge's convert stays record-only —
    the auto pass does NOT act and the cut piece does not move
    (v13.13 byte-behavior). The LEFT remainder keeps customized
    lockYaw/lockPitch/lockRoll (they silently reset before).
    autoReoptimizeBridges itself moved FvdDesigner → Coaster so the
    gate drives the REAL pass. Diagnostic numbers (scratch
    ScratchPartialConvert): post-convert graph delta vs the pre-cut
    sub-arc drops from 2–4.3 °/s (septic) to the ~3 °/s hump-replan
    residual on hump fixtures and 0 on hump-free ones; mode
    ping-pong now moves ≤ 6 mm and reproduces the same graph per
    mode. Full Phase2Gate + BridgeBump/SplitTurnProbe/SeamPause/
    SeamJolt/UnfreezeProbe/SeamOrient/BridgeProbe2 re-run clean.
    All passed 2026-07-09.

31. **v14.0 convert expansion (Phase2Gate.convertExpansion; D89/
    D90).** TARGET DENSITY: converting the mixed force fixture at
    0.5 s spacing yields exactly round(dur/0.5)+1 = 7 nodes,
    endpoints pinned bit-exact on the section ends, gaps even to
    1e-6 (0.5000 s realized); a 99 s target collapses to the two
    endpoints. ADAPTIVE: on a roll-free fixture the flat yaw and
    roll lanes keep exactly 2 nodes; the rolling fixture at 1 %
    tolerance holds the whole compiled shape to 0.21 m interior /
    0.10 m end with 8/6/10 nodes (uniform 0.1 s: 31 per lane).
    EXACT CUTS: a 37 % right bridge-cut pins lastOptDist to
    0.37 × total within 1e-6 (the old sample snap was off by up to
    half a sample). LANE PARTIAL: right-convert at 40 % → [force,
    geo], kept side within 2.8 mm (bake-grid noise), whole track
    0.074 m, converted side's vDesign = the cut speed to 0.03 m/s;
    left-convert cut exactly ON a node partitions the lanes (kept
    right lane = 2 nodes starting AT the node, no insert). S/C
    PARTIAL: [straight 12, curve R20 90° smooth 4] cut at 50 % →
    kept [straight, 27.8° arc] ends at exactly half the arc
    (1e-6), kept side within 0.4 mm; a 13 m request inside the
    blend window [9.9..16] clamps to 16.00 m (scCutClamp). WHOLE
    BRIDGE: converts in place and ATTACHES the next piece — one
    piece, samples joined end to end, shape holds 0.20 m. All
    passed 2026-07-09 (205 checks total).

32. **[REVERTED, D95] v14.1 banked-seam bridges (D92).**
    Fixture = the user's blowup: entry rolls to +30° bank then
    holds (seam roll rate ~0, pitched +24°, curving), target piece
    banked −30° via its roll delta AND curving (12 °/s pitch).
    ROLL HUMP: optimized POS_YAW at 30 m, the march now ARRIVES in
    the target's frame — seam U mismatch 0.01° (pre-fix ~60°),
    far-seam felt steps ΔGv 0.000 / ΔGl 0.001 g (pre-fix 0.50 g),
    no phantom lateral (|Gl|max 0.50 g = pure gravity; pre-fix
    1.00), far-seam curvature direction step 11.6° = the pitch
    hump's own accepted shoulder (pre-fix 76° of frame error), and
    the placement is DETERMINISTIC — a same-mode re-optimize moves
    0.00000 m (a yaw-variant U-projection twist crept 0.17 m/run;
    a one-shot hump left 16° — the shipped version measures bank
    about each frame's own tangent and iterates ≤ 4 marches).
    RATE BUDGET: the pure knee documents the net-roll 1/d runaway
    (128 m for a ~40 m gap — the user's "well over 100 m"); the
    neighbor budget (101 °/s-cost from the entrance's own 60 °/s
    roll) knees at 36 m. All legacy probes (SplitTurnProbe's
    circle/dead-ahead baselines, BridgeBump's 60.000 m pure-knee
    determinism, BridgeSmooth/SeamPause/SeamJolt/UnfreezeProbe/
    SeamOrient/BridgeProbe2) pass UNCHANGED — the 0.2° twist
    deadband keeps level/planar marches on the old code path
    bit-identically. All passed 2026-07-09 (214 checks total;
    diagnostic scratch: BridgeBank).

33. **[REVERTED, D95 — findings kept for the record] v14.2 march
    stability (scratch BridgeSweep; D93).** The sweep: 14 seam families × lengths
    {8,16,32,64,128,knee} with wildness metrics. Pre-fix worst
    cases: ramped-pitch seams hit 28× seam rates at 128 m; the
    32 m fold window barrel-rolled 360° of bank with 1.9 g phantom
    lateral ON A PLANAR FIXTURE and re-optimize crept 8.7 m; knees
    picked 56-128 m lengths whose apply rendered the septic, not
    the march they scored. Post-fix (slope caps + fold-signature
    rejection + landable-knee probing): every family's knee LANDS
    (ramped-seam knee 14 m, arc == request), the fold window stays
    planar (|Gl|max 0.000) rendering septic, re-optimize moves
    0.0000000 m everywhere (redo column all-zero across the
    sweep), and matched/turn/roll/speed families are byte-stable.
    Legacy probes (BridgeBump/SplitTurnProbe/BridgeSmooth et al)
    pass unchanged. All passed 2026-07-09 (217 checks).

63. [REVERTED, D95 — except (c), the Length spinner, which stays] v14.2 bridge feel round: (a) ramp a lane's rate INTO a seam
    (sloped graph at the cut), bridge across, optimize orientation:
    no corkscrew at any Length value; sweeping the Length spinner
    through 20-120 m changes the shape smoothly with no wild
    window. (b) Optimize length on such seams picks tens of meters
    and the graph shown IS the optimized shape (no silent septic
    swap). (c) The Length spinner arrows now step cleanly by 1 m in
    both directions from any state — no snap-back, no stuck values
    (it reads the REQUESTED length; a hand-moved target that breaks
    the landing no longer feeds the septic's arc back into the
    box). (d) Steep pull-up seams (60°+ pitch) bridged short: the
    bridge stays in its vertical plane — banking stays put.

34. **[REVERTED, D95] v14.3 zero lateral G + budget (D94).** Budget
    de-inflation: the banked fixture's floor drops 101 → 61 °/s
    (slope term removed — node-corner spikes inflated it and knees
    collapsed to 2-4 m on real tracks), knee 36 → 56 m; ramped-seam
    knee stays 14 m and landable. ZERO LATERAL G: an unbanked
    flat-turn bridge (mid-span |Gl| 0.93 g) re-banks to 0.000 g
    with the PATH bit-unchanged (mid-span position delta 0.00e+00)
    and the entry seam's bank held (U step 0.09°). All probes
    unchanged. All passed 2026-07-10 (220 checks).

64. [REVERTED, D95] v14.3 feel round: (a) Optimize length on ordinary bridges no
    longer collapses to a few meters — lengths come out at rideable
    scales again. (b) "Zero lateral G" check (bridge panel, next to
    Optimize length): a turning bridge banks itself so the Gl graph
    reads ~0 through the interior while easing to the seams' banks
    at the ends; through an airtime hump the track rolls smoothly
    (zero-G roll); the 3D path itself does not move when toggling;
    save/load keeps the check; one Ctrl+Z undoes it. (c) The
    cut-out S-hill: optimize placement+orientation, check Zero
    lateral G — compare to the original middle.

35. **v14.1-final same-piece speed reconcile (Phase2Gate.
    speedReconcile; D95).** A [curvy force, geo] chain whose hill
    shifts the seam speed 4.09 m/s off the geo section's frozen
    design speed: the rebuildTrack reconcile loop re-freezes it to
    0.0000 m/s of drift and Pass D's true speed equals the design
    profile across the whole piece (max 0.0000 m/s) — the
    design/true split cannot open inside a plain chain. Bridged
    pieces keep their drift (piece-first sections excluded). Also
    re-ran the FULL suite after reverting D92-D94: 209 checks + all
    7 probes pass — the bridge code is byte-equivalent to v13.14
    (43baeeb) apart from requestedBridgeGap. All passed 2026-07-10.

65. v14.1-final feel round: (a) chain force -> geo -> s/c in ONE
    piece, then edit the first section (raise a hill): NO thin true
    stroke appears anywhere in the piece, downstream badges never
    wear "≠v", and the downstream geometry re-derives from its
    graphs at the new speed. (b) A BRIDGED second piece still shows
    the honest split when the entry speed drifts (that feature is
    per-piece now). (c) Bridges behave exactly as at v13.14 —
    including the known banked-seam weirdness D95 records for the
    next attempt; the Length spinner still steps cleanly by 1 m.

66. v14.4 convert feel round: (a) the density window opens ABOVE
    the plot, drags anywhere by its body, and while it's up the
    graph takes no edits (keys included — try Delete/A/Ctrl+Z:
    nothing; Space/T view keys still work); Alt-Tab to another app:
    the window vanishes with the app and is back, same spot and
    still open, on return. (b) Split line: clicking a node's column
    below/above the node does NOT snap — only clicking the node
    body itself does, and only on the SELECTED lane; dragging the
    line magnetizes within ~5 px of the selected lane's nodes only.
    (c) Bridge panel padlocks read clearly green/red, not grayed.

36. **v14.5 split resolution + split-preserving bridge converts
    (Phase2Gate.splitResolution; D97).** A dropping piece bridged
    to a slow-design target (16.8 m/s of real drift at its head):
    "make real" closes the split to 0.000 m/s keeping the shape
    (interior 0.191 m); "re-freeze" closes it to 0.000 keeping the
    IDENTICAL lane objects. Bridge converts PRESERVE the split
    (user rule): after RIGHT-partial AND whole converts the
    downstream section keeps vDesign 15.0 bitwise, is vPinned, and
    its 16.8 m/s split survives the full rebuild loop (reconcile +
    auto passes); the pin survives save/load. Mode-switch kink
    (scratch BridgeModeSwitch): the far-seam frame step in
    optimize-orientation drops 0.063° -> 0.000° (rate spike 2.41 ->
    1.02 °/s = interior level), POS unchanged at 0.000°, ping-pong
    deterministic. All passed 2026-07-10 (216 checks + 7 probes).

67. v14.5 feel round: (a) convert a bridge (whole or part) whose
    next piece rides at a different true speed: EVERYTHING you saw
    before conversion stays — the downstream design graphs, its
    design speed, and the thin true strokes marking the split
    (drag an upstream node afterwards: the split still doesn't
    close — the seam is pinned). (b) A drifted section's badge menu
    grows "Resolve speed conflict ▸": anywhere DOWNSTREAM of an
    unconverted bridge it only explains ("convert the bridge
    section(s) earlier on this track first"); once no bridge sits
    upstream, "Make graphs real (keep shape)…"
    opens the density preview and the true strokes vanish on Apply
    with the 3D track staying put, while "Re-freeze design speed"
    keeps the graphs, visibly reshapes, and leaves an honest kink
    at the seam (Shift+B smooths it).
    (c) Optimize mode -> set a length -> "No optimization" -> back:
    untouched round-trips restore Optimize length exactly as left;
    after moving the piece manually, coming back keeps the manual
    bridge's length with Optimize length unchecked, and the grayed
    Length box live-updates while dragging x/y/z. (d) The
    POS/POS_YAW ping-pong no longer flips an end-of-graph jump in
    and out.

37. **v15.0 INFLUENCE mode (Phase2Gate.influence; D98, §5 round
    C).** 24 checks: (a) DO-NO-HARM — entering influence on an
    undrifted section (zero deltas) compiles BITWISE, the fast path
    still exports base curves, and discard restores bitwise — also
    after sculpting a real delta first; (b) delta-0 closure at a
    DRIFTED operating speed (26 → 29 m/s over the 407 m default
    layout): shape holds to 0.27 m interior / 0.10 m end
    (convert-class residual — the re-time across a speed change IS
    the become-real mechanism) and the clock re-times shorter
    (ratio 0.919); (c) ADDITIVITY, force section: a 15 °/s yaw
    delta hump lands on the resulting yaw rate as base + delta to
    0.006 °/s while genuinely bending the track 77 m; discard
    restores bitwise; (d) ADDITIVITY, geo section: a 0.4 g ΔGv hump
    lands on the felt Gv to 1e-4 g; (e) the USER'S EXAMPLE: a
    no-lateral force section + a held 10 °/s yaw delta produces
    felt/true Gl 0.463 g (= ω·v/g analytically) with the authored
    Gl lane untouched at 0; (f) upstream translation leaves the
    influenced local shape to 0.0 (float-exact); (g) the D95
    reconcile LEAVES an influenced continuation's operating speed
    (the split is deliberate); (h) IO — influenced saves carry
    infl/dlane/dn, the section line keeps the AUTHORED speed, the
    canonical round-trip is byte-identical, and uninfluenced saves
    carry no influence lines; (i) piece reversal with influence
    compiles and double-reversal restores to 5.6e-14 m. All passed
    2026-07-11 (241 checks + 7 probes).

68. v15.0 influence feel round (untested by the user yet):
    (a) badge menu > "Influence resulting geo graphs" on a force
    section: the strip flips to geo colors, lane buttons read
    Δ Pitch/Δ Yaw/Δ Roll, badge grows "fx", and the track does NOT
    move; the dotted resulting curves sit behind a flat zero delta
    line. Drag the delta up — the 3D track bends live and the
    dotted resulting follows exactly (hover it: values; the
    Resulting ▾ picks read TRUE values, right axis says "True").
    (b) Badge > "Resulting view": one curve per lane — the nodes
    now sit ON the resulting curve; place a node where the yaw rate
    should BE and the readout shows "value (Δ delta)"; the faint
    dashed base guide and the tint between show what the influence
    adds; Ctrl-snap lands the RESULTING value on grid steps.
    (c) Uncheck "Edit influence": the design lanes come back
    (force colors) — author zero laterals, and with a yaw delta
    live a THIN true stroke shows the laterals beside the flat
    design lane; hover reads " (true …)".
    (d) B on a delta edge node at a section boundary: the RESULTING
    curve meets the neighbor (repeat = no creep).
    (e) "Bake influence into design graphs…": density preview
    opens against the compiled (influenced) curves; Apply folds the
    influence into ordinary lanes (badge loses fx); one Ctrl+Z
    returns the influenced state. "Discard influence" snaps the
    track back exactly.
    (f) Delta nodes refuse to drag past the section end (hard
    wall); A appends only up to it; the hold-insert explains
    itself in the stats bar.
    (g) Undo/redo walk the whole flow (enter → sculpt → view
    toggles → discard) cleanly; save/load round-trips the deltas
    and comes back on the DESIGN surface.
    (h) Reverse the piece: the influenced shape survives (badge ◀
    + fx), influence editing is locked until reversed back.

38. **v15.1-v15.3 conversion round (Phase2Gate.convRound;
    D99-D101).** (a) ANALYTIC EXTRACTION: a max-accuracy adaptive
    force→geo convert of the default layout converges — interior
    rate residual 0.077 °/s (the old ±2-sample differencing floored
    at ~1.1 however dense; ConvProbe also showed zero time-shift,
    ruling out the re-time). (b) CONFLICT-PRESERVING CONVERTS: a
    pinned 15 m/s section with 1.94 m/s of drift converts to geo
    keeping vDesign 15 bitwise, the pin, the identical drift, and
    its shape to the fit residual through the full rebuild loop;
    partial converts pin BOTH halves. (c) RIGID TILT: force AND geo
    pieces at pitch 10° roll −7° match the exact rigid rotation to
    0.0 m (probed 190 m / 21 m off before), zeroing the deltas
    restores BITWISE, and the felt re-projection preserves the κ
    identity to 2e-16 g. (d) TRANSITION TO REAL: a section
    inheriting a pinned neighbor's 3.55 m/s split closes to
    0.000 m/s, SURVIVES the reconcile loop (the real-anchor rule),
    the neighbor keeps its own 1.94 m/s conflict, and the start Gv
    lands on the neighbor's true end felt. All passed 2026-07-11
    (261 checks + probes).

69. v15.1-v15.3 conversion feel round (untested by the user yet):
    (a) convert any section at high density/accuracy — the fitted
    graphs now sit ON the dashed originals (no "slightly different
    spot"); the roll spikes still round a hair (roll is measured,
    not analytic). (b) Convert a speed-conflicted section
    whole/partial: the conflict badge (≠v), the design speed and
    the shape all stay; the thin true strokes don't move. (c) Pitch
    or roll a piece (Placement ▾ rows — unlock the padlock first;
    or the bridge panel): the 3D track rotates RIGIDLY about the
    piece start; the force strips grow true strokes showing the new
    felt reality; zeroing reverts exactly. Splitting/reversing a
    TILTED piece bakes the tilt in (documented corner — the tail
    re-solves). (d) '+' after a conflicted section forks: continue
    = the old behavior; transition to real = the new section starts
    seamlessly off the TRUE curves (watch the thin strokes stop at
    the seam). (e) Resolve submenu: on a conflicted section
    directly after another conflicted one, only "Transition to
    real" is live (the others say resolve-the-previous-first);
    directly after a bridge everything explains itself; chains
    resolve cleanly left→right, each section its own choice.
    (f) B/Shift+B on the FIRST node of a transitioned section keeps
    matching the real curves (re-press = no creep); B on the LAST
    node of the conflicted section before it refuses with the
    stats-bar hint. (g) Bridge-panel padlocks and Placement-row
    padlocks on the bridged-in piece are the same locks — flip one,
    reopen the other menu, it followed.

39. **v15.4 influence/resolve adjustments (Phase2Gate influence
    (a/b/g/g2) + convRound (d) REWRITTEN; D102).** (a) entering
    influence NEVER touches speeds (vDesign bitwise, pair
    mirrored); (b) the divergent-pair machinery still engages via
    direct field writes (hand-edited files); (g) an influenced
    continuation reconciles like a normal section (18.35 →
    25.87 m/s under upstream drift, pair locked); (g2) THE USER'S
    BUG: influencing a pinned conflicted section leaves its speed
    (15.0 bitwise) and the NEXT section's conflict (drift 3.55 →
    3.55 m/s, vDesign kept); (d) RE-FREEZE directly right of a
    conflicted section closes the downstream split 3.55 →
    0.000 m/s and survives the reconcile (the refreeze-then-Shift+B
    flow). All passed 2026-07-11 (253 checks).

70. v15.4 feel round (untested by the user yet): (a) the POV
    convert-cancel bug: park POV BEYOND a bridge, convert the whole
    bridge — it now applies (and any refused convert says why in
    the stats bar). (b) Influence a speed-conflicted section: its
    ≠v badge, its design speed and the NEXT section's conflict all
    stay put; the transition into the section stays perfect.
    (c) Influence lanes are Δ Vert G/Δ Lat G or Δ Pitch/Δ Yaw only
    — the roll button grays, the roll strip shows the dotted
    resulting roll; a fresh influence has nodes at BOTH ends of the
    flat zero line. (d) Resulting view: drag a handle — its angle
    reads exactly as in Influence view (the assembly rides the
    base). (e) Resolve submenu: two ops; "Re-freeze" works right
    after another conflicted section, then Shift+B on the first
    node lines the seam up with the real ride (re-press = no
    creep); "Make graphs real" still waits for the previous
    section. (f) '+' after a conflicted end still forks
    Continue/Transition to real — unchanged, per your note.
    (g) B or Shift+B on a delta lane's first/last node zeroes it
    with a flat handle (no influence) — repeat presses change
    nothing, and neither seam speed moves.

40. **v16.0 TIME/DISTANCE domain gates (scratch DomainGate; D103).**
    14 checks, all passed 2026-07-12: (a) DO-NO-HARM — the all-TIME
    default layout keeps its RK4 baseline exactly (1347 samples,
    406.878 m, end pitch 0.2284°). (b) DISTANCE CIRCLE — a flat
    unbanked turn authored over METERS (Gv=1, Gl=1.5, v0=20, one
    full circumference) closes to 2.57e-12 m radial deviation, stays
    in plane to 0, and the last sample's arc equals the authored
    length BITWISE (the Newton landing). (c) geo distance straight:
    zero rates over 50 m = dead ahead, s ends exactly 50. (d) the
    default layout switched time→distance re-compiles within 0.83 m
    over 407 m — 0.20%, BELOW the convert engine's 0.3% fit residual
    on the same layout (the interior composition residual is theory:
    see D103; the deviation grows lever-arm-linearly, ~0.1° of
    accumulated heading). (e) switching BACK recovers every node to
    1.4e-2 s (the shape residual ÷ v — dv offsets bitwise) and the
    track to 0.71 m. (f) the switch leaves vDesign + vPinned
    untouched (conflicts persist). (g) a REVERSED section switches
    within 1.55 m (the flipped map path). (h) a bent GEO section
    (pitch/yaw/roll ramps) switches within 1.28 m. (n) EXACTNESS
    CANARY: a constant-rate section switches to 2.4e-5 m — the
    composition is exact there, so any regression is an
    implementation bug, not the theory floor. (i) influenced
    sections refuse the switch. (j) FVDD3 with a distance section:
    save→load→save byte-identical, domain honored on load. (k) the
    '+' continuation after a distance section is a 10 m DISTANCE
    hold. (l) partial convert (right→geo) on distance lanes: the
    kept half stays DISTANCE, end deviation 0.13 m. (m)
    Coaster.sectionMap rows anchored at (0,0) and strictly monotonic
    in both columns.

71. v16.0 domain feel round (untested by the user yet): (a) badge
    menu > Domain: distance (m) on a force section — the x-axis
    relabels "Distance (m)", the strip's nodes slide to their arc
    positions (fast track stretches, slow track bunches), the
    3D track doesn't move, and every OTHER strip warps onto the
    meters axis; the badge wears ·m. Switch back — the original
    node times return. (b) with a distance section active, drag a
    node with Ctrl: it snaps whole meters (the Options
    element-length step); arrows nudge by the same; A appends 10 m;
    the ⊕ hold after it is 10 m and stays a distance section.
    (c) "View in meters" on a TIME section: the axis flips, the
    curve warps, nodes stay put in the model (uncheck = everything
    back); drag a node mid-warp — the curve doesn't slide under the
    cursor while the track rebuilds, Ctrl snaps in METERS, and the
    readout says Δs=…m. (d) a speed-conflicted section switched to
    distance keeps its ≠v badge, both speeds, and its true strokes.
    (e) B/Shift+B across a time|distance seam: values AND slopes
    join (the felt/rate curves flow through), re-press = no creep.
    (f) an s/c section now lays out linearly in meters when active
    (elements at true lengths, drag readouts unchanged); its "View
    in seconds" shows the old time-warped layout. (g) POV marker,
    hover readouts and Ctrl+click-insert all land on the right spot
    on a meters axis (slopes read /m). (h) influence entry is
    grayed on a distance section (and the domain radios gray on an
    influenced one) with the reason in the item text.
    [PARTLY SUPERSEDED same day, v16.1: domain switching is a REFIT
    with a density preview now (D104), influence works on distance
    sections (D107), and (a)'s "nodes slide" no longer applies — see
    #41/#72.]

41. **v16.1 round gates (scratch DomainGate rewritten; D104-D107).**
    14 checks, all passed 2026-07-12: (40a/40b unchanged — the
    all-TIME baseline and the 2.6e-12 m distance circle ride the same
    build). (40d REWORKED) domain conversion is a REFIT that
    CONVERGES: the 407 m layout round-trips time→distance→time within
    3.47 m at coarse density (2 m / 0.1 s) and 0.24 m at fine (0.4 m /
    0.02 s). (40f) convertDomain carries vDesign + vPinned; (40g)
    reversed refuses; (40i) an influenced section converts with its
    influence CAPTURED (output plain distance lanes); (40j) FVDD3
    byte-identical round trip incl. the v16.1 axis flags (fields 6-7);
    (40k) '+' continuation = 10 m DISTANCE hold carrying the axis
    flags; (40l) partial convert on distance lanes, end dev 0.06 m.
    (41a) SLOPE-PRESERVING MOVES (D105): segGaps/rescaleDvToGaps keep
    both facing handle slopes exact through a node re-time. (41b)
    TILTED REFREEZE (D106, the user's bug): a bridged piece pitched 8°
    refreezes from 4.64 m/s max interior drift to 0.017 m/s, felt
    split 0, pitchDeg baked to 0, entry pose preserved to 1e-9. (41c)
    GLOBAL AXES (D107): pitched-entry geo with global yaw 15°/s +
    global roll 0 = the upward SPIRAL — elevation and bank held to
    <0.03°, heading turns exactly 120°; the same lanes under LOCAL
    axes drift elevation 22° (the inclined-loop contrast). (41d)
    global roll bank == the lane integral on level track (80.00°).
    (41e) DISTANCE INFLUENCE: entering is bitwise (zero deltas), and a
    6°/s yaw delta lands resulting == base + delta at mid-arc.

72. v16.1 feel round (untested by the user yet): (a) drag a node with
    BANKED handles left/right: the handles stay in line (slopes hold;
    arrow nudges and the dialog too). (b) zoom into part of the graph,
    open the badge menu and flip Domain or the meters view, or click a
    strip of the other domain: the window stays put, its ends lined up
    across the units. (c) Domain radios now open the NODE DENSITY
    window (meters spacing for →distance): crank the density and the
    refit hugs the dashed originals; Cancel restores exactly. (d) the
    old exact-shape role: "View in meters/seconds" — unchanged, and it
    works on INFLUENCED sections now too. (e) pitch a bridged-in piece
    (bridge panel or Placement rows), watch ≠v appear (even when only
    the interior splits — the info line says "true splits inside"),
    then Re-freeze design speed: pitch/roll read 0 after (baked into
    the natural orientation), the thin true strokes VANISH completely,
    and the graphs kept their shapes. Tilting the ride's FIRST piece
    now also shows the conflict. (f) badge menu > Roll: global on a
    force/geo section — a pitched turn with roll rate 0 spirals with
    the rails level to the horizon (the same section on local rolls
    with the track); geo sections also get Yaw/pitch: global =
    heading/elevation rates. B on a global-axis section politely
    refuses (stats bar). (g) influence a DISTANCE section: deltas
    author over meters, the wall is the section length, A appends
    10 m, and the meters/seconds view swap keeps working while
    influencing.

42. **v16.2 gates (DomainGate extension; D108).** 5 checks, all
    passed 2026-07-12: (a) bake local→global on a bent geo section
    keeps the shape to 6 mm over 106 m (flags set); (b) baking back
    global→local round-trips within 6 mm; (c) a force section's roll
    bake local→global holds 0.4 m; (d) make-real RIGHT OF a pinned
    conflict resolves only its own section (A 11.0→11.0 m/s drift
    kept, B→0.000, C stays pinned and split); (e) a vertical-loop
    bake stays finite (rates cap at 3600 °/s), compiles, no crash.

73. v16.2 feel round (untested by the user yet): (a) with the node
    density window open, ride/fly W/S still move the camera. (b) the
    badge menu now reads "Convert to … domain…" (one action) and
    "View in … domain"; leave a viewed section and come back — it
    shows its true domain again. (c) unselected lanes' thin true
    strokes are faint now; the active lane's stays bold. (d) ⊕ >
    "Add force/geometric section…": one dialog — type, domain (1 s /
    10 m), axes, the conflict fork (only after a drifted end), and
    the design-speed padlock: locked tracks your fork choice;
    unlock, type a slower speed, Add — the section arrives with ≠v
    (deliberate, survives edits), and B/Shift+B on its FIRST node
    lines the REAL curves up with the previous section (re-press =
    no creep). Try every combo: force/geo × time/distance ×
    local/global. (e) "Bake roll to global (keep shape)…" (and
    yaw/pitch on geo): the density window opens, the track stays
    put, and the section now authors in the other axis semantics;
    bake back — still the same track. KNOWN LIMITS (by design, say
    if they chafe): B refuses ON a global-axis section (match from
    the local side); a global|global seam has no B side yet (bake
    one side local first); vertical stretches bake imprecisely
    (capped, documented). (f) "Make graphs real" now works right
    after another conflicted section — it resolves only itself; the
    neighbors keep their ≠v.
    [(e)'s global-B limits are GONE in v16.3 — see #74.]

43. **v16.3 gates (DomainGate re-run; D109).** The analytic
    world-rate rework rides the existing checks: 42a geo bake
    tightens 6→4 mm; 42c's probe pinned the φ' transport SIGN
    (+121 m wrong-sign shape error → 0.408 m, now the documented
    step-boundary projection lag, identical to the old differencing
    floor); 42e still finite through a vertical loop (world rates
    hold off past ~81° elevation instead of integrating a capped
    spike into the bank target). All 19 + Phase2Gate's 253 green
    2026-07-12 (late).

74. v16.3 feel round (untested by the user yet): (a) re-test the
    banked angled curve bake — geo bakes should sit within
    millimeters now; a force section's roll-only bake keeps a small
    (~0.2%) offset from the projection's step cadence — say if that
    still reads as "a different spot". (b) add a custom-speed
    section (unlocked padlock) after a clean section: its REAL
    curves line up immediately (same height); B/Shift+B then only
    re-aim handles. Re-run your geo→force custom-speed test — the
    two-sided detection fix should close what you saw. (c) Shift+B
    from the UNCONFLICTED side of a conflicted seam now matches the
    neighbor's REAL curves (re-press converges); the conflicted
    side keeps working as before. (d) B works ON global-axis
    sections now (world-rate targets) — try a global|global seam
    both directions; only a vertical seam refuses, and every
    refusal anywhere now explains itself on the stats bar. (e) the
    Add dialog's domain radios read just "Time"/"Distance".

44. **v16.4 gates (DomainGate re-run; D110).** The sub-step roll
    feed-forward rides the existing checks: 42c (force roll-only
    bake) falls 0.408 → 0.008 m and its bound tightens to 0.05; the
    geo bake stays 4 mm; local paths bitwise (40a + Phase2Gate's 253
    green). All passed 2026-07-12 (night).

75. v16.4 feel round (untested by the user yet): (a) re-test the
    local↔global roll bake on your banked curve — the converging
    offset should be gone (both directions, ~millimeters now).
    (b) badge menu > "Orientation settings…": one window — flip the
    radios and watch BOTH buttons relabel live ("Switch to …" /
    "Bake shape to …"); Switch reshapes, Bake keeps the track; Close
    changes nothing. On an influenced section only Bake is offered
    (it captures); on a reversed one only Switch. (c) clicking an
    INACTIVE section's badge now just activates it — click again
    for the menu. (d) in the Add dialog, unlock the speed, type a
    value, then flip Continue/Transition: the padlock re-locks and
    shows the new fork's value.

45. **v16.5 gates (DomainGate extension; D111-D112).** All passed
    2026-07-14: AREA-MATCHED FITS (D111) ride the whole suite — 40d
    re-baselined ("area-matched refit: round trips at cm scale",
    coarse 3.47 → 0.019 m, fine 0.24 → 0.27; residuals are no longer
    density-monotone since the matcher, not spacing, sets the floor;
    the old 0.02 s "fine" leg now takes the documented sub-grid
    skip), 40l end dev 0.065 m, 42a-c bake residuals hold 4-7 mm,
    and the probe matrix (scratch ConvEndProbe) reads: uniform 0.1 s
    force→geo 0.617→0.019 m, force→force 0.824→0.017; uniform 0.05 s
    0.0095→0.0025 / 0.017→0.0045; DISTANCE targets 1.6-7.6 mm;
    geo-source fits 0.2-1.0 mm; adaptive force-source rows keep a
    ~0.1-0.2 m through-the-solution floor (documented, D111 — fine
    uniform is the max-fidelity setting there). INFLUENCE ORIENTATION
    (D112): (45a) influence entry + discard on a GLOBAL-ROLL force
    section are bitwise, defaults mirror the roll choice; (45b) the
    zero-delta two-pass at a hand-split speed pair keeps the
    global-roll shape to 0.078 m / 83 m (the D110 projection-cadence
    class; the UI never splits the pair); (45c) a +10°/s heading
    delta on a 48° climb turns EXACTLY 60.00° under global influence
    axes vs 39.75° local — the cos(elevation) contrast; (45d) FVDD3
    round trip byte-identical with the `infl` flags. DomainGate 24
    checks + Phase2Gate 253 green. (Also learned: geo fixtures with
    sustained yaw+bank climb until they STALL and creep — the old
    bent-geo probe fixture's convert numbers were stall artifacts;
    fixtures must dive first.)

76. v16.5 feel round (untested by the user yet): (a) CONVERT
    ACCURACY — re-run whatever convert bothered you: at normal
    densities the converted track should land ON the original's
    endpoint now (mm-cm, not "very close"); crank density and watch
    it converge. On violent layouts EVEN SPACING now lands closer
    than Optimal placement — say if that reads wrong. The nudged
    nodes sit within ~1e-3 of the original curves (invisible).
    (b) INFLUENCE ORIENTATION — influence a section, keep "Edit
    influence" on, open the badge menu: the entry reads "Influence
    orientation settings…"; its radios set what the DELTAS integrate
    about (force section: roll + yaw/pitch; geo section: roll).
    Try a yaw delta on a climbing section under global vs local
    yaw/pitch — global turns the compass exactly, local turns less
    the steeper you climb. Fresh influence mirrors the section's
    roll choice. (c) GLOBAL-AXIS sections now accept influence, and
    "Orientation settings…" on an influenced section's DESIGN
    surface allows Switch too. (d) ⊕ after a conflicted end: the
    straight/curve entry is ONE item — hover/click reveals Continue/
    Transition. (e) undo/save-load: influence axis choices survive
    both.

46. **v16.6 gates (DomainGate extension; D113-D116).** All passed
    2026-07-14: (45e) MID-CHAIN convert keeps the section's entry
    step (D113 — the dropped-boundary-sample bug): the overbanked
    mid-chain repro falls 0.20 m → 2.6 mm (geo), 7.2 mm (force),
    3.3 mm (adaptive), arc loss gone. (46a) per-meter constant pitch:
    elevation == rate × arc to 0.02° over 106 m whatever the speed
    profile. (46b) DISTANCE + per-meter geo shape is design-speed
    INVARIANT (0.08 mm between 18 and 30 m/s — the s/c property for
    lanes). (46c) force per-meter GLOBAL roll: bank == rate × arc
    (the arc-integral bank target; a LOCAL-roll first draft of the
    gate mis-measured — bank-vs-horizon drifts from the body-roll
    integral by transport once a banked Gv=1 track pitches). (46d)
    convert to per-meter keeps the shape (9.7 mm) + FVDD3
    byte-identical round trip with section field 8. (46e)
    ALL-SETTINGS convert in one call — force/time/local/°s →
    geo/DISTANCE/global-roll+yp/per-meter — dev 0.0002 m (a first
    fixture cresting at T.y ±0.95 read 35-58 m: the DOCUMENTED
    near-vertical global-axis hold-off, not a code path — keep gate
    fixtures off vertical). DomainGate 30 checks + Phase2Gate 253
    green.

77. v16.6 feel round (untested by the user yet): (a) YOUR OVERBANK —
    re-run the ~150 m overbanked-turn convert mid-layout: the end
    point should land ON the original now (mm; it was the section's
    first integration step going missing on every mid-chain convert,
    not the fit). (b) badge menu > "Convert…": ONE dialog — Whole or
    Part, Type, Domain, Roll axis, Yaw/pitch axis (grays for force),
    Rate units, each row with ↺ back to the section's current
    setting. Any combination to any other; Part opens the split line
    carrying your choices; the old per-item entries and the separate
    domain-convert action are gone. Global-axis targets still blur
    over near-vertical track (documented cap). (c) RATE UNITS — add
    or convert a section to °/m: same graphs-over-x editing, values
    now mean degrees per METER of track (a distance-domain °/m geo
    section's shape ignores design speed entirely, like s/c). Force
    Gv/Gl stay G targets — a 2 g target is 2 g at any speed, there is
    no "g per meter" to author, so Rate units on a force section
    governs its roll lane. (d) B/Shift+B across a local|global ROLL
    seam — both directions, re-press = no creep; global|global roll
    seams are exact now too; °/s|°/m seams convert value + slope.
    (e) the Orientation dialog lost its gray explainer text. (f) a
    speed-conflicted s/c section's true strokes now dim like
    everyone else's — all three at a middle weight (between a lane
    strip's selected and unselected strokes); say if the level reads
    wrong.

47. **v16.7 gates (DomainGate extension; D117-D119).** All passed
    2026-07-14 (late): (47a) PER-METER INFLUENCE — entry bitwise on a
    °/m section; a 0.5 °/m heading delta turns exactly rate × arc
    (60.00°). (47b) ABSOLUTE ROLL — a ramp-to-50°-hold lane reads
    bank 50.000° at mid and end (the lane value IS the bank). (47c)
    converting a rate section TO absolute roll holds the shape to
    1.6 mm, forces rollGlobal, and round-trips FVDD3 byte-identical
    (section field 9). DomainGate 33 checks + Phase2Gate 253 green.
    (Ops lesson: the incremental compiler reported stale success
    while GraphEditor sat broken — headless gates never load JavaFX
    classes, so editor compile breaks hide; force-delete
    target/classes/coaster when in doubt.)

48. **v17.0 gates (Phase3Gate, new scratch file; D133 friction +
    D134 reverse-as-convert).** All passed 2026-07-17: (48a)
    rolling closed form on flat track, v² = v0² − 2μg·s per sample
    to 1.7e-13; (48b) air-drag closed form v = v0·e^(−ks) to
    2.1e-14; (48c) Pass D reproduces the design profile on an
    undrifted hilly section with both coefficients on (1.1e-6 m/s);
    (48d) a two-section chain reconciles design == true under
    friction (8e-7 m/s); (48e) the s/c SPINE is friction-invariant
    — RATE-0 roll 3.1e-7 m exit dev; GLOBAL-0 bank 8.2e-3 m (the
    projection-cadence O(dt²) noise, accepted bound 5 cm) — while
    the design end speed drops 5-7 m/s; (48f) REVERSE PIECE = a
    convert (D134): a bridge-carrying piece refuses; a force+geo
    piece comes back as the same structure mirrored, ALL-forward,
    entry speed = the recorded Pass-D end speed, NO speed conflict
    (split ≤ 1e-6 m/s), shape at matched arc 0.87 m over 204 m
    with friction on at the 0.02 s carpet (the per-seam
    fit-residual reorientation chained converts always had); a
    STALLING reversal (heavy friction — the energy for the climb
    home was already spent once) stays finite and conflict-free at
    the creep floor; (48g) a custom (unlocked) entry speed is
    honored and the shape is independent of it; (48h) an s/c piece
    reverses into forward FORCE lanes, spine held to 4 cm; (48i) a
    LEGACY reversed-flag piece reverses to all-forward exactly at
    a level seam (0.000 m; a KINKED legacy seam cannot survive any
    chained re-author — documented, not gated), and double
    reversal lands back on the original shape (0.86 m, a fit of a
    fit); (48j) the FVDD3 friction line round-trips
    byte-identically, the legacy reversed flag still round-trips,
    friction-less legacy text loads with zero friction; (48k)
    whole force→geo convert under friction lands the end to 2 mm
    over 329 m (the fitLanes friction-aware re-time). Phase2Gate
    265 (incl. the reworked pieceReversal + influence-reversal
    checks) + DomainGate green = the friction-OFF bitwise
    contract. (Fixture lessons: friction is paid BOTH ways, so a
    reversal fixture must have the energy to climb home — heavy
    coefficients on a long piece stall by real physics; and
    compare refit geometry at matched ARC, not sample index — the
    fit re-times the samples.)

49. **v17.1 gates (Phase3Gate extension; D135 — strict seams).** All
    passed 2026-07-18: (49a) make-seam-strict splits + marks the
    follower, creation moves nothing; an upstream Gv edit leaves
    the downstream LOCAL shape frozen to 4.3e-14 m while position
    and REAL speed stay continuous at the seam, the design speed
    holds (honest 4.4 m/s split opens), unlocked yaw follows the
    incoming heading and locked pitch holds the original; (49b)
    all-unlocked aligns fully; relocking all three axes returns
    the ORIGINAL orientation to 0.0 (the stored placement never
    mutates); (49c) un-strict merges back to one piece with the
    conflict PINNED, the design graphs bit-exact, and the track
    reoriented continuous; (49d) a strict bridge-convert keeps the
    follower with the bridge's locks; strict vs classic agree to
    the fit residual at creation, and convert+strict+UN-STRICT ==
    the classic convert BIT-IDENTICALLY (0.00 m — the user's
    stated identity); (49e) piece fields 11-14 round-trip
    byte-identically, and connect-directly (= strict, all axes
    unlocked) moves nothing on coincident pieces (5e-15 m); (49f)
    the ATTACH CONTRACT (user report 2026-07-18 — the pre-v17.1
    attach dissolved the piece into the chain and the reconcile
    silently re-froze its design speed to the seam's, re-deriving
    the shape): attaching a foreign piece (vDesign 20, tilted,
    parked elsewhere) KEEPS its design speed (an 11.9 m/s ≠v split
    opens against the ~28 m/s arriving), keeps its design
    orientation (the joint reorients fully continuous — the
    pitch/roll difference becomes the hidden design/real felt
    split, exactly like editing piece pitch/roll), and the shape
    stays RIGID (local delta = one-sample alignment noise only);
    (49g) RESOLVING a follower's conflict (D136 — the user's
    "launches off into the distance"): the strict bake precedes the
    ops — make-real keeps the on-screen shape to the fit residual
    (end dev 1.4 m on the violent 40°-dive + 60°/s-roll fixture,
    arc preserved; ~190 m off before — 0.25 m since D137's clean
    entry row), and strict refreeze equals
    the UN-STRICTED twin's refreeze to 0.22 m over a ~300 m
    reshape (the reshape happens from the REAL frame either way).

50. **v17.2 gates (Phase3Gate extension; D137 entry-row synthesis +
    D139 strict-seam smoothing).** All passed 2026-07-18: (50a/b)
    converting a section whose targets JUMP at its entry seam
    (fixture: Gv 1→2.2 + a 40 °/s roll-rate step) anchors node 0
    on the SECTION'S OWN value — Gv 2.200 exactly, roll ~0 — with
    ZERO ripple downstream, at 0.1 s AND 0.02 s spacing (pre-D137:
    node 0 landed BETWEEN 1.0 and 2.2 with 20 oscillation
    sign-flips at 0.1 s, and exactly ON the wrong value at fine
    density); (50c/d) make-real on a strict-seam follower clears
    the ≠v drift (11.9/20.0 → 0.008/0.040 m/s, DRIFT_EPS 0.2) and
    lands node 0 on the true felt at the entry (±0.004 g) — locked
    AND all-unlocked seams (the unlocked fixture's pre-fix node 0
    read −0.409 against a true −0.706: the visible "conflict still
    there"); (50e) smoothing is WINDOW-LOCAL — upstream of the
    window bitwise, beyond it frames BIT-EXACT and positions offset
    by one constant vector (variation 6e-14 m), the follower's
    later sections carried identically — and since v17.3 (D140)
    the POSITION CLOSURE bends + stretches the window so that
    vector all but vanishes: |dp| 0.011 → 3e-6 m on the moderate
    fixture (the violent 147°-kink probe: 0.689 → 0.000 m);
    (50f) a 30 m window
    collapses a ~9° seam kink: 9.09 → 0.23 °/sample frame step,
    true rate spike 589 → 23 °/s, true-felt steps 0.062 g/sample
    (smooth κ·v² excursion — narrower windows buy sharper g, real
    physics); (50g) piece field 18 round-trips byte-identically,
    17-field v17.1 lines load as smoothing 0; (50h) make-real on a
    SMOOTHED follower still clears the drift and moves the ride end
    only 0.16 m — fit ops read the UNSMOOTHED compile
    (Coaster.compileForFit; fitting the blended samples from the
    contract frame swung the tail 122 m before the fix).

78. v16.7 feel round (untested by the user yet): (a) °/m sections:
    both vertical axes now say the units (left title + the right
    Resulting axis on °/m force sections), and °/m lanes get their
    own default scales — roll ±15 °/m, geo pitch/yaw ±10 °/m. (b)
    every ↺ in the program grays while its row sits at the default/
    original; a changed radio selection tints orange. (c) influence
    a °/m section — the delta lanes author °/m. (d) "View rates in
    °/m|°/s" (badge menu): the strip re-displays in the other units,
    warped by speed; edit straight through it; it drops when you
    leave the section. (e) ABSOLUTE ROLL: add or convert a section
    with Roll behavior = Absolute bank (needs Global roll — watch
    the interlock gray/flip the radio); the roll graph height IS the
    bank in degrees (50 = 50° bank, 0 = level); B/Shift+B match the
    seam's bank exactly, slopes best-effort (your caveat); switching
    roll to Local reverts the lane to Rate. (f) Orientation dialog
    buttons wear their explanations as gray captions beneath. (g)
    1/2/3 switch lanes while the density preview is open.
79. v16.8 feedback fixes (D120): (a) B on an ABSOLUTE roll lane's
    edge node against a rate neighbor now takes the SEAM's bank —
    set the abs lane to any wrong value, press B, it lands on what
    the neighbor delivers (it used to hand the lane its own value
    back, with a huge garbage slope). (b) THE CUTBACK CASE: bank a
    global-rate section past 180° (e.g. rate ramping to +250° of
    total bank) into an absolute section whose lane is authored in
    the same unwrapped range — B matches at +250-ish, NOT the
    mirrored −110 (the fix unwraps to the representative nearest
    your node's current value; a node near 0 still gets the
    principal value — same physical bank). Verify both directions:
    B from the rate side toward the abs neighbor reads the compiled
    curves (right units), never the bank lane as a rate. (c)
    CROSS-RATE VIEW: nodes and curves sit together at NATIVE scale
    now; the right edge grows an axis labeling the same scale in
    the other units (at the section's design speed) and curve hover
    appends the exact converted value at the cursor; the axis
    replaces the Resulting overlays' right axis while the view is
    on. (d) A changed radio's orange sits on the radio DOT, not the
    label letters.
80. v16.9 feedback fixes (D121): (a) ABSOLUTE ROLL smoothness — a
    fast bank ramp (say 0 → 200° over 4 s) rides SMOOTHLY now: no
    jitter on the track, and the resulting roll-rate graph is a
    continuous curve instead of stair steps (the graph should look
    exactly as smooth as an equivalent roll-RATE section's). Gate
    47d locks it (max consecutive rate jump 3.56 → 0.88 °/s; bank
    tracks the authored curve to 0.007°). (b) CROSS-RATE VIEW, as
    you actually specced: "View rates in °/m|°/s" now EDITS in the
    other units — the curve, nodes and handles all warp together
    and line up, the left axis + default scales speak the viewed
    units (roll ±15 °/m, pitch/yaw ±10 °/m), the RIGHT edge shows
    the native units at design speed, hover appends the exact
    native value, and drags/Ctrl-snaps work in what you see. (c)
    The changed-radio orange dot is now geometrically identical to
    the black dots (only the mark color tints).

81. v16.10 feedback fixes (D122): (a) YOUR HILL REPRO — abs-roll
    force section (Time, Global, Absolute bank), then '+' a geo
    section (Time, Global roll, Local yaw/pitch, °/m, Absolute
    bank): Shift+B on its first node now sets the PITCH node to the
    hill's continuing curvature in °/m (and yaw likewise) instead
    of zeroing it flat; the transition rides smooth. Same fix
    applies wherever a °/m lane matches across a seam the analytic
    paths can't serve — against reversed, influenced, s/c, bridge
    and global-axis neighbors, and real-split (≠v) seams: targets
    were all ~v× too small before. Re-pressing B should change
    nothing once matched. (b) Ctrl-snap on any lane DISPLAYED in
    °/m steps by 1 °/m now (Options row "Snap: per-meter rate",
    reset ↺, arrows walk 1-2-5); °/s lanes and absolute BANK lanes
    keep the 5-step. The °/m|°/s view follows the displayed units.
    (c) In the Convert… and Add-section dialogs, hover a grayed
    choice — Absolute bank under Local roll, Yaw/pitch under Force,
    Part… on an influenced section, the padlocked speed field — and
    a tooltip says how to un-gray it. Enabled controls show no
    tooltip. (d) FLY-MODE DRIFT: right-click into fly mode and
    sweep the mouse side to side for a few seconds — the horizon
    stays put now (it used to pitch steadily upward); vertical look
    still works normally. (e) B/Shift+B on the LAST node of a
    section whose NEXT section is absolute-bank refuses with a
    stats-bar hint pointing at the other side of the boundary;
    B/Shift+B on the abs section's own FIRST node remains the way
    to smooth that seam.

82. v16.11 viewport round (D123): (a) MIDDLE-drag now PANS the
    orbit view (grab-style — the world follows the mouse; works in
    the T/Shift+T ortho views too and stays ortho); left-drag still
    orbits, shift+left-click still snaps the pivot to the track.
    (b) The ACTIVE section's cross ties are WHITE — click strips in
    the graph and watch the white band move along the 3D track;
    piece/section switches re-tint without a visible hitch.
    (c) Click a node MARKER sphere: that node selects in the graph
    (white marker + selected node), and if you were zoomed
    elsewhere the graph window re-centers onto it. Clicking track
    between markers still just activates the piece + section.
    (d) GIZMOS: the active piece's entry shows 3 colored arrows +
    a green yaw ring (pitch/roll rings appear when you unlock the
    Placement padlocks). Drag arrows/rings — the track follows
    live, one Ctrl+Z per gesture, spinners re-sync on release.
    Verify NO gizmo on a piece that an optimize-mode bridge places,
    and no pitch/roll rings while locked. (e) BRIDGE gizmo: with
    the bridge section active, manual mode = arrows + rings at the
    NEXT piece's entry; optimize modes = one ORANGE arrow along the
    end tangent that drags the LENGTH (gone when Optimize length is
    checked). (f) The "Axes: global/local" button (top-right of the
    3D view, only while a gizmo shows) re-aims the translation
    arrows along the piece's right/up/forward — toggling moves
    NOTHING. (g) Feel-check ring directions: each ring should
    follow the mouse (grab a ring and the track turns WITH the
    drag) — report any ring that runs backwards (one documented
    sign, D123). Gizmos hide in POV/fly and re-appear after.

83. v16.12 viewport feedback fixes (D124): (a) MIDDLE-drag ORBITS
    now (left-drag does nothing — left is selection only);
    shift+middle-click snaps the pivot to the track; DOUBLE-click
    the track inserts a node and the graph scrolls to it
    (Ctrl+click no longer inserts). (b) GIZMO REVEAL: the bigger
    sphere at the piece's start toggles the gizmos; with a
    trailing bridge BOTH gizmos appear (entry + bridge end),
    whatever section is active — and there's a reveal sphere at
    the bridge end too. Switching pieces hides them. (c) Colors:
    arrows X red / Y blue / Z green; rings yaw yellow, pitch teal,
    roll orange-red (the graph palette). (d) RING FEEL: drag along
    a straight line — rotation follows the mouse's distance from
    the press (~57° per ring-radius); verify the optimize-position
    bridge's yaw ring no longer runs away (the old feedback spiral).
    (e) CTRL snaps gizmo translation/length by the element step
    (1 m) and rings to whole degrees; the bridge yaw spinner arrows
    step 1°. (f) Gizmo appearance follows the drag in REAL TIME
    (arrows/rings re-aim as the piece turns), parts are thicker,
    the scale stays constant anywhere on screen (no edge blow-up),
    and gizmos stay visible in fly mode. (g) UNDO: unlock a
    Placement padlock, drag the pitch/roll ring, Ctrl+Z — the
    padlock STAYS unlocked (it relocks only when undoing past the
    point it was locked). (h) Active-section ties are brighter
    white now (self-lit).

84. v16.13 (D125): (a) gizmo arrows are X GREEN / Y blue / Z RED
    now; clicking EMPTY space hides revealed gizmos (the piece
    stays selected). (b) Two pieces parked close together: the
    HEARTLINE no longer joins them (one strand per piece). (c) On
    a piece placed by an optimize-mode bridge, open Placement ▾ —
    x/y/z (and yaw when locked/optimized) are grayed with a hover
    tip pointing at the bridge; "No optimization" un-grays x/y/z.
    (d) The new VIEW menu (between Edit and Coaster) holds gizmo
    axes, heartline, node markers, comb toggles, comb colors, both
    FOVs and mouse sensitivity — sliders/checks keep the menu open,
    and Edit > Options now has only snaps + POV marker. (e) COMBS:
    toggle each on — vertical G teeth point outward and wear the
    rail gradient's colors; lateral teeth flip sides with the
    force and wear their own stops (edit both sets under View >
    G-force & comb colors…; save/load a .fvd and the lateral stops
    survive); speed teeth grow with speed. Hover a tooth's tip —
    a readout follows the cursor with the value ("2.31 g vert",
    "-0.45 g lat", "26.0 m/s"); it hides off the combs. (f) Old
    .fvd files load with default lateral stops and byte-identical
    round-trips still pass (gated).

85. v16.14 comb/gradient round (D126): (a) TIE + COMB SPACING is
    dead even now — look along any stretch (especially fast track):
    every gap identical, no occasional wide ones. (b) Combs: a
    three-way View-menu choice (No / G-Force / Speed) — G-Force
    shows vertical AND lateral together; teeth every 0.5 m, growing
    from the HEARTLINE; lateral teeth point rider-LEFT on a
    right-hand force; "Comb size (%)" scales them (applies when you
    release the slider). Combs hide in POV and never block clicks —
    double-click through them still inserts a node. (c) HOVER a
    tooth (anywhere on it): its value appears at the BOTTOM-LEFT of
    the viewport. (d) Gradient: five stops now — purple −1.5, blue
    −0.7, green 1, yellow 3, red 5 — driving rails + vertical comb;
    hang time shades blue→purple. Lateral colors mirror about zero
    (green ±0.5, yellow ±1, red ±1.5). Every color row has a ↺
    that grays at the default. (e) Rail color lives in the View
    menu: gradient|solid radios, the solid picker (with ↺) appears
    only when solid — and after using "Custom Color"'s Save/Use the
    app window stays in front (it used to drop behind another
    window). WATCH: if picking "Custom Color" from the View menu's
    rail row crashes, report it (the pre-v7.5 menu-owner crash —
    documented fallback ready). (f) Edit menu says "Snapping…"
    (snap steps + references only); the POV graph marker is always
    on. (g) Old files: 4-stop saves load with purple prepended;
    re-saving writes 5 stops.

86. v16.15 (D127): (a) double-click a mid-lane node — grey
    parentheses beside Transition and Value update LIVE as you
    type: the absolute axis position and the Δ from the previous
    node. (b) Hover a node: grey ring + grey readout
    (Δt + value); hold SHIFT while hovering: absolute position +
    Δvalue. The readout no longer vanishes over node bodies; click
    still selects (white ring) as before. (c) POV: capture with
    right-click, press A — the view flips backward while W/S keep
    moving the ride the same way along the track (W now reads as
    backing up); A again or D returns forward; the View-menu
    "Reverse POV view direction" check mirrors the state both
    ways. (d) Comb teeth are twice as wide along the rails.
    (e) G-Force Colors (renamed, letters tinted by your own
    gradient hottest→coldest — re-tints after you edit stops):
    vertical stops now purple −1.5 / blue −1 / CYAN −0.5 / green 1
    / yellow 3 / red 5; older files load with the cyan default
    inserted. (f) Custom Color window: clicking Save/Use returns
    focus DIRECTLY to the app — no flash through another window
    (root fix; the v16.14 behavior pulled focus back late).

87. v16.16 (D128): (a) THE FLASH: open G-Force Colors, click a
    color swatch — the app's OWN picker opens (RGB sliders + hex,
    live preview on the track); OK/Cancel/X return focus straight
    to the dialog with NO flash through another window, every
    time. The View menu's rail swatch behaves identically. (b) All
    four View sliders (FOVs, sensitivity, comb size) wear ↺ resets,
    grayed at default. (c) Reverse POV (A while captured): a
    "Backwards POV (A)" tag sits at the bottom-left while reversed,
    gone when forward. (d) SHIFT while dragging a node: the readout
    swaps to absolute position + Δvalue (same as shift+hover).
    (e) CTRL while hovering a curve: the snapped insert dot's
    readout leads with the global axis position. (f) The color
    dialog offers a QUICK PALETTE (30 hues/shades + grays) and a
    "Recent" row of your last ten picked colors — pick one, OK,
    reopen any swatch: it leads the Recent row; clicking swatches
    live-applies exactly like sliding.

88. v16.17 (D129): the color dialog's RGB sliders are gone — a
    HUE/SATURATION plane (hue left-to-right, saturated at the top)
    with a vertical BRIGHTNESS slider beside it, and typed RGB
    fields. Check: dragging on the plane moves the double-ring
    marker and live-updates the track/gradient; the brightness
    slider darkens the preview (the plane itself stays readable);
    typing RGB (e.g. 255 0 0) or hex jumps the marker to match;
    picking a GRAY then raising saturation keeps the hue you were
    on (marker doesn't snap left); palette/recent swatches, OK,
    Cancel and the no-flash close all behave as in #87.

89. v16.18 (D130): (a) right-click any node — the OPTIONS menu
    (edit, smooth/match, level(-lock), straighten/straight-lock,
    splits, delete) with your CURRENT shortcut bindings in the
    labels; items gray where they can't apply (pair ops need 2+,
    splits need an interior node on a normal force/geo section).
    (b) "Split section here" on a mid-lane node: the strip gains a
    boundary at the node, both halves edit natively, the ride
    LOOKS identical (the new seam behaves exactly like one you'd
    authored — the gate pins split == authored-as-two at 0.0 m),
    downstream design speed = the speed at the cut (no ≠v badge).
    (c) "Split track piece here": same cut plus a new piece — the
    Placement dropdown/piece selector show it; moving the new
    piece moves only itself. On a section's FIRST node it does the
    classic boundary split. (d) Ctrl+Z restores either split in
    ONE step. (e) Reversed and influenced sections refuse with a
    stats-bar reason.

90. v16.19 (D131): (a) color picker — the plane darkens with the
    brightness slider; the slider's track shows black->your-color
    and its thumb centre is the picked color, all live. (b) Split
    a section (right-click menu), then right-click the seam's edge
    node -> "Connect with next/previous section": the sections
    rejoin into ONE and the ride is EXACTLY the original (gated
    0.0 m). (c) Connect two sections whose seam you left
    UNMATCHED: the joined node sits at the average value with
    averaged (smooth) handles — the curves bend to meet it.
    (d) The connect entries gray against unlike neighbors
    (different type/domain/axis flags) and on reversed/influenced
    sections.

91. v16.20 (D132): (a) THE SLOPE FIX — make two sections whose
    seam has a real SLOPE, Shift+B it, Connect: the joined curve
    keeps that slope exactly (it used to HALVE — a sign-blind
    guard read every in-slope as 0). Let a lane HOLD across the
    seam too: matched holds join bit-identically (dead handle data
    used to bend them), and an UNMATCHED hold joins at the average
    of the held value and the neighbor's node, easing in smoothly. (b) Right-click nodes of every flavor: interior,
    edge, multi-select, on locked runs — the menu only ever lists
    ops that work there (locked segments offer "unlock" wording;
    Delete is absent on end nodes and shows no shortcut tag).
    (c) The badge menu's "Connect with previous section" grays
    against unlike neighbors and works on matching ones.

92. v17.0 (D133 friction + D134 reverse-as-convert): (a) Coaster
    menu grows "Friction (rolling μ)" and "Air drag (per km)" rows
    (tooltips carry the model). Set μ ≈ 0.03 on the default
    layout: the whole ride slows toward the end (stats-bar top
    speed, POV pace, speed comb) and the graphs re-derive —
    same-piece chains stay REAL automatically (no ≠v inside a
    plain chain). Ctrl+Z steps the coefficient back; Save/Load
    round-trips it; New zeroes it. (b) REVERSE PIECE (Placement ▾
    > "Reverse piece (rebuild backward)…"): the density popup
    opens with a padlocked ENTRY SPEED row on top reading the
    speed the ride reaches the piece's end with. The 3D track
    flips travel direction in place (same shape to the fit
    residual — crank the density and watch it tighten), the strips
    re-author as ordinary FORWARD sections (same types at the same
    boundaries; a straight/curve strip comes back as force lanes),
    and NO ≠v badges appear anywhere — the reversed piece is
    conflict-free by construction, friction included. Apply = one
    Ctrl+Z step; Cancel restores exactly. (c) Unlock the padlock
    and type a bigger entry speed: the whole flow re-runs live and
    the shape stays put (only the graphs/speeds change). With
    HEAVY friction on a long piece, reverse it at the locked
    speed: the stats bar warns "the reversed ride STALLS partway —
    friction is paid both ways"; give it more speed and the warn
    clears. (d) A piece ending in a bridge refuses with the hint.
    An influenced piece reverses with its influence FOLDED IN
    (plain sections out). Old files' reversed-flag sections (◀
    badges) still compile exactly as before; reversing such a
    piece re-authors it all-forward. (e) With friction 0
    everything else behaves exactly as before (bitwise — the whole
    old gate suite pins it).

93. v17.1 (D135) — STRICT SEAMS: (a) On a two-section piece, click
    the boundary LINE between the strips → "Make seam strict…" —
    the line turns DOUBLE and the settings pop up (three padlocks;
    default yaw unlocked, pitch/roll locked). Edit the FIRST
    section violently: the downstream track keeps its exact shape
    in 3D, sliding/re-aiming to stay on the seam; its badge wears
    ≠v (design speed kept — thin true strokes split) while POV
    rides through the seam at continuous real speed. (b) Unlock
    all three locks in the seam settings: the downstream aligns
    fully (a smooth joint again, still shape-frozen). Relock them:
    it snaps back to its ORIGINAL orientation exactly. (c) Click
    the double line → "Un-strict seam": one piece again, track
    re-bent continuous, graphs untouched, the ≠v conflict KEPT
    (pinned). Ctrl+Z steps each of these back. (d) Convert a WHOLE
    bridge: the dialog's new "Make following seam strict" is
    checked — after Apply the next piece keeps its shape exactly
    behind a double line and the converted lanes edit freely
    without disturbing it. Un-strict that seam: you get exactly
    what the old convert used to produce. Uncheck the box instead:
    the classic stitch directly. (e) ⊕ > Attach floating piece and
    the zero-distance bridge prompt now connect with a strict seam
    (all unlocked — seamless) and open the settings. (f) A
    follower's Placement rows gray (hover says why) and its entry
    gizmo doesn't build; "Split track at seam" detaches it in
    place as an ordinary piece.

94. v17.1 feedback round (D136): (a) hover a boundary line — it
    brightens and the cursor becomes a hand; click it — the line
    stays lit while the menu is open. (b) The seam menu on a
    section boundary offers "Connect sections (merge into one)"
    (grayed against unlike sections); the badge menu no longer
    carries Split-piece or Connect-with-previous (seam + first-node
    menus own them). (c) The strict seam settings are a pop-out:
    per axis a DISCONTINUOUS button (red outline while on), a
    typed/arrow spinner, and a ↺ back to the creation value —
    toggling Discontinuous ON freezes the CURRENT angle (nothing
    jumps), editing the spinner re-aims the whole downstream
    rigidly, ↺ returns it, Apply closes. (d) Select a section
    after a strict seam: no ghost "node" floats off-track anymore
    (it was the follower's entry reveal marker at its stale stored
    position). (e) Make a seam strict, edit upstream, unlock
    everything, then resolve the follower's conflict: MAKE-REAL
    keeps the shape you see (re-timed at the real speed) and
    RE-FREEZE reshapes exactly like the un-stricted section would
    — no more launching into the distance. (f) The density
    preview reaches 0.001% accuracy and 0.01 s spacing (distance
    fits floor at 0.5 m).

95. v17.2 (D137-D139) — seam-jump fixes + strict-seam carets &
    smoothing: (a) CONVERT AT A JUMP: author two sections whose
    Gv (or roll rate) steps at the seam, then Convert… the second
    one — the refit's FIRST node sits exactly on the section's own
    starting value with no wobble after it, at ANY density (it
    used to land between the two sides' values and oscillate; the
    previous section no longer matters). (b) MAKE-REAL AFTER A
    STRICT SEAM: make a seam strict, edit upstream until ≠v shows,
    resolve with "Make graphs real…" — the badge's ≠v CLEARS on
    Apply and the design curve now starts ON the true stroke at
    the seam (no leftover split at the entry). (c) SEAM CARETS
    (semantics re-spec'd in D140 — see #96b): lane-colored
    chevrons ON the double line flag each lane whose rate graph
    would SPIKE at the seam. (d) SEAM SMOOTHING (control re-spec'd
    in D140 — the diamond, see #96a): set ~10-30 m on a kinked
    seam — the 3D junction rounds off, the true strokes ramp
    through a smooth transition instead of stepping, the carets
    disappear (the seam is smooth now), and NOTHING outside the
    blend window moves. Too-small windows on hard kinks show
    honest big-g excursions in the true strokes — widen the
    window. Snap shut (or Ctrl+Z) restores the sharp joint
    exactly; Save/Load round-trips it. (e) Resolve/convert ops on
    a SMOOTHED follower behave as if the seam were sharp (the
    blend re-applies on top of the result) — make-real keeps the
    on-screen shape, no swing. (f) With smoothing 0 everywhere,
    everything compiles exactly as v17.1 (bitwise — the old suite
    pins it).

96. v17.3 (D140) — the v17.2 feedback round: (a) THE SMOOTHING
    DIAMOND (shown while smoothing is ENABLED — see #97a): drag it
    sideways: the blend window grows and shrinks live with a
    "smoothing X m" readout, Ctrl snaps whole meters (the
    element-length step), dragging near the seam snaps shut
    ("smoothing off"), the shaded band shows the window, the
    diamond fills while active, one Ctrl+Z undoes a whole drag.
    The settings pop-out has NO smoothing row anymore. (b) CARET
    DIRECTIONS: build a seam with an instant pitch-up kink (e.g.
    locked pitch, then dive the upstream) — the Gv caret points UP
    (the felt graph would spike +∞ there); an instant roll step in
    the negative direction points the roll caret DOWN. Carets sit
    ON the line (top/bottom edges since v17.4 — #97c); enable
    Resulting overlays — matching carets appear in the overlay
    palette; smooth the seam — all carets vanish. (c) THE
    SETTINGS pop-out is WHITE like the menus, titled just "Strict
    seam", and DRAGS by its body; pressing Discontinuous now snaps
    the axis to its ORIGINAL creation angle (the ↺ value) instead
    of freezing the current one. (d) SEAM GIZMO: with a follower
    piece active, a reveal ball sits ON the seam point in 3D —
    click it: rings appear for the DISCONTINUOUS axes only (yaw
    yellow / pitch teal / roll orange), dragging one re-aims the
    downstream rigidly with the standard Ctrl 1° snap and one undo
    per gesture; toggle an axis continuous in the settings and its
    ring disappears live; DOUBLE-CLICK the ball — the settings
    pop-out opens (reveal state unchanged). (e) DRIFT CLOSURE:
    smooth a kinked seam on a LONG follower and watch its far end
    in 3D while dragging the diamond — it stays put now (the
    window bends/stretches to land back on the original exit; the
    end used to creep noticeably). (f) Convert density ranges are
    back to their pre-D136 spans (spacing 0.02–2 s / 0.2–20 m,
    accuracy 0.01–10%).

97. v17.4 (D141) — the v17.3 feedback round: (a) ENABLE/DISABLE:
    a hand-made strict seam starts UNSMOOTHED with no diamond; its
    seam menu says "Enable smoothing (10 m)" — click it: the
    diamond + band appear at 10 m and the item reads "Disable
    smoothing" (defaults were 1 m for one unshipped round — 10 m
    since D142). Convert a whole bridge (strict box checked) or
    connect two pieces: those seams arrive with smoothing ALREADY
    ON at 10 m. Dragging the diamond shut disables it again (the
    menu re-enables). (b) RED BAND: on a smoothed CLEAN seam
    (no ≠v at zero smoothing), drag the window very wide over a
    kinked junction until the follower's badge shows ≠v — the band
    turns RED, and the badge menu's "Resolve speed conflict"
    submenu is replaced by a grayed "Resolve speed conflict by
    decreasing seam smoothing size"; shrink the window until the
    ≠v clears — the band returns to white and the badge menu goes
    quiet. A seam whose conflict exists at ZERO smoothing keeps
    the normal resolve submenu and the white band. (c) CARET
    STACKS: up-spikes sit just under the seam line's TOP edge,
    down-spikes just above its BOTTOM edge — pan and zoom the
    graphs: the carets stay put (direction only, no magnitude).
    (d) EDGE GRABS: hover either EDGE of a smoothing band (seam or
    s/c) — the cursor turns ↔; drag it: the window resizes exactly
    like the diamond (Ctrl snaps, seam windows snap shut). Nodes,
    badges, seam lines, '+' and curve inserts all still win the
    click when they overlap an edge.

98. v17.5 (D142) — the v17.4 feedback round: (a) CARETS ALWAYS
    ON: at any sharp strict seam all three lane carets (and every
    enabled overlay's) are visible — the ones whose graphs barely
    jump are TRANSLUCENT rather than gone; kink an axis harder and
    its caret goes solid. (b) 10 m DEFAULTS: "Enable smoothing
    (10 m)" seeds ten meters; bridge converts and piece connects
    arrive at 10 m. (c) RELATIVE DIAMOND: on a seam that already
    has smoothing, grab the diamond and nudge right — the window
    GROWS from its current size (left shrinks); it no longer
    collapses to zero at the grab. Band edges still drag to the
    cursor absolutely. (d) DOUBLE-CLICK the diamond: the seam
    settings pop-out opens (no drag starts). (e) NODE DODGE: put
    a node at a strict seam near the diamond's height — the
    diamond hops down the '+'-button ladder to a clear spot and
    remains grabbable there.

51. **v17.6 gates (Phase3Gate extension; D143 — global-axis hold
    continuity).** All passed 2026-07-19: (51a-c) appending a geo
    section holding the v17.6 world-rate values after a curving
    force section crosses the seam within 0.37-0.44 °/s of TRUE
    rates for rollG-only, ypG-only AND both-global (the old zero
    holds stepped 19-25 °/s; ypG-only's local roll lane excluded —
    the documented D109 transport shift). Gate 49e re-pinned:
    connect-directly arrives with smoothing OFF (D143) and the
    coincident placement stays exact (5.3e-15 m).

52. **v17.10 gates (Phase3Gate extension; D147 end landing + D148
    bridge seams).** All passed 2026-07-21, Phase3Gate at 69:
    (52a) a time section's LAST sample lands exactly on the
    authored end (== 5.0, float-exact); (52b) split-at-node vs
    unsplit end gap 0.0093 m on the curved hill fixture — bake-
    grid phase only (0.245 m pre-fix on the ramp; MACHINE-precision
    1.2e-12 m since the v17.11 exact bake + 0.01 s time grid,
    D151); (52c) split on
    an ANGLED (linear) stretch is exact, 1.6e-5 m. (53a-b,
    REWRITTEN v17.11 after the D148 drafts were user-reverted,
    D150) roll-free march bridges re-pinned at the shipped
    parabola-hump seam levels; (53c) ROLLING seams with locked
    roll — the user's split-and-rebridge file's class — march
    renders at every length with the far-seam felt CONTINUOUS
    (worst 0.0015 g; pre-D150 ~0.5-1.2 g instant Gv/Gl jumps or a
    permanent septic fallback). The WHOLE old suite (Phase2Gate
    265 + DomainGate) re-ran green over the D147/D151 re-times
    (default-layout baselines re-pinned 406.931 m / 0.2131°).

53. **v17.14 gates (Phase3Gate 73; D155 crest bridges).** All
    passed 2026-07-24: (54a) the CREST fixture — entry pulling up
    at +3.6 g / +23° while the target's entry sits at −5° ALSO
    pulling up, the "Odd bridge behavior" seam pair — marches at
    EVERY length {15..80} with ZERO pole crossings; (54b) min U.y
    > 0.5 everywhere and target roll delta 0.0000° (pre-fix the
    branch-blind measures walked the residual twist into a 360°
    barrel roll — minUy −0.99 at every mid length); (54c)
    placement deterministic — yaw spread 0.00° across the whole
    sweep — with the far seam felt-continuous (0.000 g); (54d) the
    knee lands at 64 m on the ~46 m gap (was 88 m while looped
    branches scored as smooth). The manual-mode tangent rescale
    probed on his file 3: unmoved compiles bitwise (lenA
    bit-equal), a +10 z target drag scales lenA 11 → 11.73 at
    exactly the stored proportion, dragging back restores
    11.000000. Old suites bitwise green (crossing-free marches
    take the historic expressions verbatim).

54. **v17.15 gates (Phase3Gate 76; D156 banked crests).** All
    passed 2026-07-24: (54e) the SAME crest with the seam pieces
    rolled +19°/−22° (his file-4 pair) — marches at every length
    {15..64} with the end bank CLOSED (< 2°); pre-fix the coupled
    fixed point cliffed at ~45-51 m (clean at 44, unconverged
    march + ballooned septic at 46); (54f) min U.y > 0.2 across
    every rendered span and the far seam felt-continuous (0.000 g)
    — the twist-0 rescue draft once CONVERGED onto a mid-span-
    inverted corkscrew whose exact ends passed the angle-only
    render gate; (54g) at 80/100 m anything that renders as a
    march has its bank closed (the new 1° bank render gate — the
    rest falls to the sane 1.5×-chord septic). Probe sweeps on his
    file 4: scenario B (both banks) marches clean 20→90 m, A (one
    bank) through 80, knees 120/112 → 60; files 1-3 re-probed
    unchanged-or-better (file 1: march at 5..120, file 2: to
    100 m). Old suites green — the damping takes full steps on
    contracting sequences, so every previously-converging march
    (all of 53a-c and 54a-d) reproduces verbatim.

55. **v18.0 gates (Phase3Gate 87; D160 speed controls).** All
    passed 2026-07-25: (55a) a constant-speed control on flat
    track snaps to 15 m/s within one step, holds it EXACTLY and
    keeps the shed energy past the control; (55b) a 1 g launch's
    speed ramp matches the analytic dv/dt = g·A to 3e-11 m/s and
    lands exactly on its 40 m/s target; (55c) a control inside a
    deliberately conflicted (pinned) section closes the
    design/true split to exactly 0.0 downstream; (55d) a max-only
    brake under friction lands ON its 5 m/s target at the
    crossing, then friction keeps shedding below it (a ceiling,
    not a motor) with design == true; (55e) an inert control (past
    the lane end) leaves every sample BITWISE — the zero-controls
    contract the whole old suite also pins; (55f) the node split
    partitions controls exactly (a straddler clips in two) and
    merge re-covers the interval + marks; (55g) domain conversion
    carries a control at its ARC position through the section map
    (ends within fit residual); (55h) geo and s/c sections clamp
    identically (all three integrators); (55i) a min-flag lift
    RESCUES a stalling 60° geo climb in both the design and the
    true pass (floor held at 8 m/s exactly).
    Round 2 adds (55j) a roll-only convert (local -> global on a
    +-40 deg/s wave): lanes 0/1 BIT-EXACT to the original nodes and
    the ride end within 3 mm — the residual lives in the roll lane
    alone (D169).

99. v17.6 (D143) — the both-global fix + resulting settings:
    (a) BOTH-GLOBAL ADD: after any curving force section, ⊕ > Add
    geometric section with Global roll AND Global yaw/pitch — the
    new section's graphs now START on the incoming world rates
    (no flat zero, no sudden transition); same for either flag
    alone. If B/Shift+B ever still seems dead on such a seam, read
    the stats bar — the matcher always says why it refused.
    (b) RESULTING SETTINGS: Resulting ▾ > "Resulting graph
    settings…" — flip Rate units to °/m: the overlay curves and
    the right axis re-scale live and the dropdown's check labels
    re-title; flip Roll axis to Global over a force section and
    enable the new ROLL check: the resulting bank rate overlays in
    the roll color; set Roll behavior to Absolute bank: the curve
    becomes the bank angle itself ("bank … °" on hover). Yaw/pitch
    axis rows gray over a geo section (the resulting graphs are
    felt G there) with the hover explainer; the roll check grays
    over geo sections too. Settings persist across restarts.
    (c) DIALOG ORDER: the Convert dialog, the Add-section dialog
    and the Resulting settings all list Type, Domain, Rate units,
    Roll axis, Yaw/pitch axis, Roll behavior in that order.
    (d) CARETS vs SMOOTHING: smooth a kinked strict seam and drag
    the window wide — the carets stay exactly put (they read the
    junction's sharp contract states); disable smoothing — still
    identical.

100. v17.7 (D144) — the v17.6 feedback round: (a) GIZMOS ALWAYS
    ON: select any piece — its gizmos are just there (entry
    arrows/rings, bridge-end, seam rings), no reveal click;
    switching pieces moves them; View > "Show placement/seam
    gizmos" hides them all (persists; menu-local Ctrl+Z works).
    The entry sphere still selects node 0; double-clicking the
    seam ball still opens the seam settings. (b) CARET SIZES: at
    a strict seam, kink one axis hard and leave another nearly
    continuous — the hard one wears a full-size caret, the quiet
    one a smaller thinner one (both fully opaque). Drag a node
    onto a caret's spot — the caret hops just far enough inward
    to clear it and returns when the node leaves; up-carets hug
    the top edge, down-carets the bottom. (c) RESULTING ROLL
    EVERYWHERE: on a GEO section, enable the Resulting roll
    check — with matching roll settings it's grayed (it would
    duplicate the real roll graph); flip the resulting Roll axis
    to the other setting and the check un-grays and overlays the
    converted roll; switch to a section whose settings match —
    grayed again but still checked, and it comes back elsewhere.
    Absolute bank draws RED-ORANGE. (d) SCALES: a °/m lane's
    empty default scale reads ±5 °/m (all lanes); resulting
    overlays open at the same defaults as the lanes (−1..4 g
    felt, ±60 °/s, ±5 °/m, ±90° bank).

101. v17.8 (D145) — the v17.7 feedback round: (a) SEAM SETTINGS
    MEMORY: make a seam strict (settings auto-open = the FIRST
    open) — toggling Discontinuous snaps to the creation angle;
    Apply, re-aim the seam, reopen the settings and toggle an
    axis off/on — it now HOLDS the current angle (nothing jumps);
    ↺ still returns the creation angle. (b) APPLY/CANCEL: click
    off the settings window — it stays; change locks/angles then
    CANCEL — everything reverts to how the window found it (one
    Ctrl+Z step); Apply keeps. (c) TINTED CHECKS: in Resulting ▾,
    checked boxes fill with their curve's color (bank = the
    near-red); unchecking clears the fill; colors follow the
    active mode. (d) TRACK PIECE: the toolbar button reads
    "Track Piece ▾" (still "Bridge properties ▾" over a live
    bridge); its panel DRAGS by its dark background, spinners
    still edit live, click-off closes, menu-local Ctrl+Z works,
    and "Reverse piece…" is a button inside it.

102. v17.9 (D146) — the v17.8 feedback round: (a) BANK COLOR: an
    absolute-bank section's lane 2 draws in the same red-orange as
    the resulting bank overlay (nodes/fills/markers included).
    (b) CARETS: enable the resulting roll/bank overlay at a
    strict seam — no extra caret appears for it (the lane's own
    roll caret covers the spike); pitch/yaw overlay carets still
    show. (c) ROW ORDER: Convert, Add-section and Resulting
    settings all list Type, Domain, Rate units, Yaw/pitch axis,
    Roll axis, Roll behavior. (d) MOVABLE RESULTING: drag the
    Resulting dropdown by its dark background — it stays open
    while moved (hover-away no longer closes it after a drag);
    click elsewhere to close; reopening re-docks it at the
    button. Track Piece panel labels are white.

103. v17.10 (D147-D149) — end landing, bridge seams, View/Coaster
    round: (a) SPLIT IS EXACT: insert a node on an angled stretch,
    right-click > Split section here — the END of the track does
    not move (was up to ~25 cm); Ctrl+Z, repeat with Split track
    piece — same. (b) [SUPERSEDED by #104 — the v17.10 hump/gate
    drafts were REVERTED, D150.] (c) VIEW MENU: "Show Gizmos" is the TOP row
    with Local|Global dots inline (grayed while unchecked, same
    state as the viewport's Axes button); "Show heartline" (no
    "(neon)"); "Visualize track selection" OFF = full-color tubes
    on every piece + plain dark ties everywhere (no white active
    ties), ON = the old look. (d) COASTER MENU: a "Rail Color"
    section — Solid (square 22 px swatch appears while selected,
    NO reset arrow) | Normal Force; picking a color re-tints live,
    Load/New re-sync the swatch without dirtying. (e) All color
    swatches (rail + every stop in G-Force Colors…) are small
    squares. (f) BRIDGE PROPERTIES panel: every label/radio/check
    reads WHITE on the dark popup (spinner box text stays dark).
    (g) SMOOTHED SEAM TRUE STROKES: on a smoothed strict seam
    with NO speed conflict, the thin true stroke now draws inside
    the window on the LEFT (upstream) side too — the real blended
    curve vs the authored graph; it still vanishes outside the
    window.

104. v17.11 (D150-D152) — his round-2 report: (a) BRIDGE (his
    "Bridge length continuity bug.fvd"): load it, sweep the Length
    spinner anywhere (including 24.1 m and below) — the far seam's
    normal/lateral graphs stay CONTINUOUS at every length (they
    jumped ~1 g below 24.1 before; above 24.1 was the septic
    materializing, not a fix). The two v17.10 bridge drafts are
    reverted — old optimized bridges render exactly as before.
    (b) SPLIT: insert a node where all three graphs have slope AND
    curvature, split the section (or piece) — the end of the track
    does not move at all now (machine precision on 0.01-multiple
    cuts, µm otherwise; was ~2 cm per 100 m on curvature-heavy
    cuts). (c) POV STATS LINE: the bottom line reads POV X/Y/Z,
    Speed, Vertical G, Lateral G (3 decimals, TRUE values); leave
    POV view — it keeps updating as you edit; the purple graph
    line marks the position at all times. Coaster > Coaster
    Statistics holds the old five numbers; the shortcut hint is
    gone. (d) MENUS: "G-Force Colors…" appears under Coaster >
    Rail Color > Normal Force only while selected; View lost
    "Reverse POV view direction" (A/D still work captured);
    "Visualize Selection" and "Node Markers" renamed. (e) POV ON
    BRIDGE: park the POV on a bridge, open Bridge properties,
    shrink the length hard — the POV stays on the bridge and the
    panel stays open (it used to select the next piece and close).

105. v17.12 (D153) — his round-3 report: (a) SEAM SLOPES: on the
    bridge file, sweep the length — at BOTH bridge seams the
    G/rate graphs now continue on the neighbors' SLOPES as well as
    their values (the graphs used to leave the seams at a slightly
    wrong angle, worst on short bridges). (b) ATTACH DEFAULTS:
    attach a floating piece whose pitch/roll padlocks are locked
    (the default) — the seam settings open with Pitch and Roll
    DISCONTINUOUS (red) holding the piece's angles, Yaw continuous;
    unlock a padlock first and that axis arrives continuous
    instead. Answer YES to the zero-distance-bridge prompt — the
    seam's Discontinuous axes match the bridge's padlocks (default
    pitch+roll locked = discontinuous, yaw unlocked = continuous).
    (c) TRUE-FORCE RAILS: on a track with a ≠v section, the Normal
    Force gradient colors match the G-force comb (the real ride),
    not the design graphs; without conflicts nothing changes.

106. v17.13 (D154) — B across strict seams matches REAL graphs:
    make a strict seam, open a speed conflict across it (edit
    upstream so the follower wears ≠v), then B / Shift+B on the
    edge nodes from EITHER side: the thin TRUE strokes line up at
    the seam (value + slope, converging over a re-press or two) —
    the design curves deliberately keep their ≠v step. Also works
    unconflicted-vs-conflicted. With seam SMOOTHING enabled the
    targets read the blended samples — expect a couple of
    iterations, or match with smoothing off and re-enable. After
    matching, a smoothed conflicted seam's TRUE strokes run
    smoothly through the window; the DESIGN felt/rate step at the
    seam is the honest ≠v display and stays whatever the window
    size (D139).

107. v17.14 (D155) — crest bridges (his "Odd bridge behavior"
    files): (a) file 1 (OPT_POS_YAW, yaw-only unlocked): sweep the
    Length spinner 5..120 — the bridge stays UPRIGHT at every
    length (no corkscrew roll, no inverted loop, no S-whip), the
    target piece re-aims by the SAME ~8° yaw whatever the length,
    and the graphs blend seam to seam without a far-seam spike.
    Check "Optimize length": ~60 m (was 88 m and rolled). The
    crest still rises then dips past the target pitch a little —
    that S is forced by the seams (both ends curve UP), and
    shorter lengths flatten it. (b) file 2 (the force lane plunges
    4 g right at the cut): lengths through ~100 m march cleanly —
    the dive deepens smoothly with length instead of spiraling;
    120 m over the 34 m gap falls back to the septic honestly (the
    solution wants a near-vertical dive). (c) file 3 (manual
    mode): NUDGE the target piece — the septic's end spikes
    collapse as the tangents rescale to the gap, and further drags
    track proportionally (the saved stale shape only heals on the
    first move — loads never change geometry). (d) any live
    bridge's badge menu: top item "Bridge properties…" opens the
    same movable panel as the toolbar button (orphaned bridge:
    grayed "(no following piece)"). (e) valleys: behavior
    unchanged (crossing-free marches are bitwise-historic).

108. v17.15 (D156) — BANKED crest bridges (his file 4 / report
    #4): (a) load "Odd bridge behavior 4" (entry piece rolled
    +19°, target −22°, roll locked): sweep the Length spinner
    20..90 — the twisting crest stays the SAME shape family at
    every length (no sudden shoot-around past ~45 m: that cliff
    was the coupled pitch/twist iteration diverging), growing
    smoothly with length; "Optimize length" lands ~60, inside the
    clean zone. (b) zero the target's roll (one-sided bank): same
    sweep, clean through ~80. (c) extreme lengths (~2× the gap and
    beyond) may drop to the septic — it now sweeps with full-gap-
    family tangents (no ballooned loop), and NOTHING that renders
    as a march is ever mid-span inverted (the bank render gate).
    (d) unbanked files 1-3 and ordinary valleys: identical to
    #107 (converging marches are bit-reproduced through the new
    damping).

109. v17.16 (D157) — new combs + Help rewrite: (a) View menu: the
    comb radios now read No / G-force / Speed / Geometric / Time —
    the Geometric row carries inline Local·Global·Combined dots
    (grayed unless it's selected, like the gizmo dots). GEOMETRIC:
    teeth pointing OUTWARD from every curve — pitch/elevation
    orange, yaw/heading RED, the combined tooth red-orange, every
    hover ending in the radius, (r=∞) on zero components (v17.18) — (a
    pull-up's teeth point down/outside, a right turn's point
    left); Combined (default) = one tooth per spot whose hover
    reads "curv X °/m (r≈Y m)"; Local = pitch + yaw tooth pairs;
    Global = elevation + heading rates, absent on near-vertical
    track. (b) TIME comb: a tick every 0.1 s along the track in
    the speed comb's old light blue, WHOLE seconds white — hover
    reads "t = X s"; the SPEED comb is now GREEN. Both scale with
    Comb size, hide in POV, and never block clicks. (c) HELP: one
    read-through — every section should be current (no removed
    controls mentioned), organized by workflow, and short enough
    to scan; the new combs appear under 3D viewport > Combs.

110. v17.17 (D158) — the pre-speed-control quick round: (a) an
    influenced section's badge menu has NO view radios — the
    delta lanes always edit around zero with the resulting
    curves dotted behind (check a saved influenced section loads
    and edits normally). (b) CLICK a smoothed strict seam's ◇
    once: the seam settings open; DRAG it: the window resizes
    with the live readout and the settings never appear (wiggle
    under ~3 px still counts as a click). (c) enable the
    resulting ROLL overlay over a geo section (or while
    influence-editing a force section) and hover it: the readout
    says "roll … °/s" (or °/m / bank …° per the Resulting
    settings) — it used to read "Gl … g". (d) every bridge's exit
    boundary draws the strict-seam DOUBLE line, with no carets or
    diamond on it; ordinary section boundaries stay single.

111. v18.0 (D160-D164) — SPEED CONTROLS: (a) the "+V" box at the
    graph's bottom-left toggles the speed strip; the ▲ at its left
    edge expands the tall view (both persist across restarts).
    (b) Click the line = a diamond appears; double-click or
    right-click the diamond (or the line after it) = the stretch
    to the next diamond/section end becomes a CONTROL (green =
    constant speed) and the Speed Control options open — movable,
    live, with the end-speed reference. Close it, double/right-
    click the control again — it re-opens. (c) Constant Speed with
    both flags on a fast section: the resulting speed (green
    dotted, tall view) snaps flat at the target; uncheck Min —
    below-target stretches run natural; try unchecking BOTH —
    the other re-checks. (d) Constant Acceleration 1 g toward a
    high target = a straight speed ramp; toward a low target with
    Max only = a brake. (e) Complex control: Edit Speed expands
    the strip and the graph edits like a lane (insert on the
    curve, drag nodes/handles, Ctrl snaps, Delete removes a node);
    the resulting speed follows the graph. (f) Drag the START
    diamond = the control moves; the END diamond = it resizes
    (its graph stretching along); ripple "time"/"distance" shifts
    later diamonds; nothing ever leaves the section. (g) The 3D
    view wears a green/blue PLATE down the tie centers under every
    control; a control on a conflicted section pulls design AND
    true speed onto the same clamp — the ≠v badge downstream
    clears. (h) Speed a section down until the ride can't finish:
    track past the stall turns TRANSPARENT and the POV jumps over
    it (W into the gap lands on the next run's start, S backs onto
    the last solid sample); add a Min lift over the climb — solid
    again. (i) Friction rows read m/m and m⁻¹ (arrow steps 0.001 /
    0.00001); a NEW document starts at 0.03 / 1.0e-4; loading an
    OLD file shows ITS values (the load-friction bug is fixed).
    (j) Badge menu > "Set initial design speed…" types a speed
    directly (elsewhere = deliberate conflict, pinned; on the ride
    entry it writes the Start-speed spinner); its "Set Design
    Speed to Real" button == the renamed resolve op. The Add
    dialog's fork row now says "Initial design speed" with "Set to
    Real". (k) TIME comb: whole-second ticks stay put; fillers
    space evenly between them at roughly the other combs' density
    — fast track gets more, crawls get few. (l) Reverse piece on a
    piece with controls refuses with the hint; Influence entry
    grays with "(remove speed controls first)". Save/load a doc
    with plain + complex controls + a bare diamond: everything
    returns; Ctrl+Z steps through strip edits one gesture each.

112. v18.1 (D165-D168) — the speed-control feedback round:
    (a) Speed Control popup: End speed sits right under the speed
    row; typing letters/spaces into any of its boxes does nothing;
    Alt-Tab to another app — the panel vanishes; come back — it
    reappears in place. (b) Complex graph: with a node selected,
    Z then a drag moves only vertically (Z again frees it), X only
    horizontally, holding S slides along your dominant motion;
    H flattens its handles; F straightens the segment ahead;
    Shift+F keeps it straight through further edits, Shift+H
    levels it; Shift-hovering the curve reads the slope; the curve
    wears the translucent shadow to zero and nodes pre-light with
    a grey ring. (c) On a constant-speed complex control, press B
    on the FIRST node: its value and slope snap onto the incoming
    real speed (green dotted meets the graph smoothly); same on
    the LAST node against the outgoing side; Shift+B identical.
    (d) Shorten a section under a control (drag its last lane node
    left): the control squeezes back inside — never past the end;
    stack two controls at the end and click the pile: the FIRST
    one selects. Min length is 0.02 s / 0.2 m everywhere. (e) Open
    ANY window — node editor, Convert, Snapping, Shortcuts, the
    Speed Control panel — then right-click the viewport: fly
    works, WASD works, W/S ride in POV; letters never land in a
    numeric box (only digits and one period type anywhere).
    (f) TIME comb: seconds deep blue and taller, 10/20/30 s red,
    fillers light blue. (g) Ride W into a stalled (transparent)
    stretch: the camera stops exactly at the stall point like a
    track end; S backs off it. Ride at Ctrl ×0.25: buttery — no
    sample-stepping. (h) View > Mesh quality: at 400% a tight
    corkscrew's tubes read round; at 25% visibly faceted; default
    100% is already smoother in curves than before at about the
    same triangle budget (rings concentrate where the track
    turns).

113. v18.2 (D169-D171) — the second speed-control feedback round:
    (a) With the Speed Control panel (or ANY window) open:
    right-click the viewport, press Space — POV toggles; T /
    Shift+T ortho views work too. (b) Resulting ▾ roll check: make
    it redundant (matching settings over a geo section) — it grays
    AND unchecks; re-enable elsewhere manually. (c) Convert a
    force section changing ONLY Roll axis (or behavior): the Gv/Gl
    graphs don't move AT ALL (nodes identical), the preview opens
    ON the roll lane, and the fitted roll lane converges with
    density; the density slider changes nothing on lanes 0/1.
    (d) An absolute-bank section's third lane button reads red
    like its curve. (e) TIME comb: red ticks at 5, 10, 15 s….
    (f) Ride W into a stalled stretch: the POV jumps to the next
    piece — or to the ride start when it is the last/only piece —
    exactly like riding off the end; S from beyond backs onto the
    last solid sample. (g) Strip graphs: box-drag selects several
    nodes (Shift adds), the group drags together and Delete
    removes it; arrows nudge; Ctrl-hover shows the white snapped
    insert dot; S/Z/X draw their guide lines; drag a handle off
    the strip — dashed stub + hollow marker, click it and the
    scale expands to reach; the zero line IS the control line
    (speed above, negative accel below, "0" labeled on it).
    (h) Grab the strip's top edge: drag up/down resizes (persists
    across restarts), below ~40 px it collapses to the thin line,
    dragging the thin edge up re-expands. (i) Double-click node 0
    of any non-first section: the dialog carries "Initial design
    speed (m/s)" — changing it pins a deliberate conflict; node 0
    of the ride keeps "Start speed".
114. v18.3 (D172) — the strip IS the editor: select a complex
    control, tall view. (a) Every gesture matches the main plot
    EXACTLY (rings, hover/drag readouts + Shift twins, guides,
    ease-preset snap names, Ctrl-presnapped insert dot, box band,
    S/Z/X, H/F, Shift+F/Shift+H twin-line lock visuals — enforced
    on control lanes through any edit), at smaller nodes/lines.
    (b) Double-click a strip node: the TYPED dialog opens
    (transition/value/handles, numeric-only) and Apply edits the
    control's graph; no Start-speed/boundary rows there. (c) The
    Ripple time/value checks apply to strip node drags exactly
    like lane drags. (d) Drag a strip node against the control's
    ends: hard wall BOTH sides (no started-at-end extension), and
    the plot axes never auto-grow during strip drags. (e) The
    main plot never shows a phantom insert dot while the strip is
    hovered, and vice versa. (f) With a strip node selected,
    click the plot: the strip's node selection drops (and the
    reverse) — Delete always targets the surface last pressed.
    (g) The tall strip wears its own value gridlines + labels on
    the selected control's scale, zero ON the control line.
115. v18.4 (D173) — strip refinements: (a) Click-DRAG on a free
    stretch of the control line: translucent preview grows with
    the drag (Ctrl snaps, length readout), release creates a
    selected SPEED control exactly press→release, clamped against
    neighbors; a plain click still drops a diamond; Ctrl+Z removes
    the whole creation. (b) The green dotted TRUE-speed curve is
    GONE inside constant/complex SPEED controls (no overlap) but
    still there over accel controls and plain track; its hover
    follows. (c) Restart the app: the strip is always closed
    (tall/height remembered for when you reopen it). (d) Drag a
    strip node / hold a snapping shortcut: the MAIN graphs stay
    rock solid — no flashing (the redraw surface-leak fix).
    (e) A complex const-speed control shows a blue dotted
    longitudinal-G curve inside its span (edges trimmed — the
    boundary snap is instant); hover reads "long G = X g", Shift
    the slope; same blue as accel graphs. (f) Shift+F/Shift+H on
    strip segments draw mini twin-line (+ band for level) visuals
    like the main plot.
116. v18.5 (D174): (a) A PLAIN constant-speed control: the green
    dotted true-speed curve still crosses it; check Complex — the
    curve disappears inside; uncheck — it returns. (b) Author a
    complex speed graph over steep track: the blue long-G curve
    never leaves the strip vertically (the accel scale expands to
    fit it). (c) Time ripple ON: drag an interior graph node right
    — later nodes shift along but the control's LAST node stays
    put and nothing exits the control; with value ripple it still
    moves vertically. (d) Pull a control graph's last node left of
    the control end: the remainder draws DOTTED at the held value,
    and clicking the dotted stretch appends a node there.
117. v18.6 (D175): (a) The Coaster menu carries NO Start-speed row
    — double-click node 0 of the ride to edit it (still one undo
    step, title dirties, clamped 1..100). (b) Slow track to a
    crawl (heavy friction): the resulting speed curve descends
    SMOOTHLY through 0.1 toward 0 — no flat park at exactly
    0.1 m/s, no jitter where it grazes; stall transparency and
    the POV wrap behave as before (the ≤0.1 predicate reads the
    same ranges). (c) Drag a strip graph node past the strip's
    top edge: the value scale grows continuously under the cursor
    (accel graphs also grow downward past the line); release
    re-fits — exactly the plot's edge-growth feel. (d) Phase3Gate
    88 green (48f compares above the 0.1 creep floor — honest
    records expose the sqrt-amplified creep-zone residual its
    comment always documented).
118. v18.7 (D176): (a) Ride W into a stalled stretch: the POV
    slows to the crawl floor and then WRAPS (next piece / ride
    start) — it must never freeze short of the jump. (b) The
    Speed Control popup reads "Start: X m/s" and "End: X m/s"
    under the speed row, each with "(true Y)" when the design/
    true split exceeds 0.05 m/s. (c) Every translation gizmo
    arrow points BOTH ways and drags from either side; the
    bridge-length tangent arrow stays single. (d) In orbit/fly
    the node markers sit ON the heartline; enter POV — they drop
    to track level (and back on exit). (e) The bottom-right XYZ
    compass tracks every orbit/fly/POV/ortho camera move live —
    X green, Y blue (up), Z red; axes pointing away from the
    viewer draw dimmed.







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
1. Window fits screen incl. title bar (test target: 1920×1080). Floor is a
   transparent grid (1 m lines, heavier every 10 m) at ABSOLUTE y=0 — the
   default layout floats ~2 m above it (start y 21) and a track edited to
   dive below 0 goes under the grid; that's fine. The grid fades
   out with distance (minors gone past 100 m, everything ends at 150 m):
   zoomed way out there must be NO shimmering/moiré. Heart/Gauge labels in
   the Coaster menu are black (readable on the light popup).
2. Keys 1/2/3 switch lanes; inactive curves faint. Clicking a dimmed curve
   or node switches to that lane (and selects the node); clicks near the
   ACTIVE lane's nodes/handles never switch away.
3. Click node → ring + translucent handles. Drag knob: moves FREELY (angle
   + length). On a smooth node the partner mirrors proportionally on both
   axes: equal handles stay exact mirror images; unequal ones scale in
   proportion (drag one 30%→15% and a 20% partner lands at 10%; dv 1.0→1.5
   takes a −0.8 partner to −1.2) — always collinear. Shift-drag: knob rides
   its current line, length only, partner does NOT move. Ctrl-drag: length
   snaps between ease presets (readout shows e.g. "out 0.33 cubic" —
   fractions of the segment, no % anywhere).
   Drag node: free; Ctrl snaps the TRANSITION to 0.1 s steps (readout Δt
   lands on 0.30s/0.80s even when the previous node sits off-grid) and the
   value to the absolute grid (0.1 G; whole °/s in the roll lane); readout
   shows the transition ("Δt=0.80s  Δ+1.50 (2.50)") — Δs from the previous
   node, true value in parens. Moving a node closer to its neighbor
   squishes the segment shape, never distorts it.
4. B on an interior node: knobs circle↔square; re-smooth swings the RIGHT
   handle to match the LEFT.
5. Hover the active curve away from nodes: translucent white dot rides the
   line; click inserts a node there with NO visible change to the curve
   (tangent handles on slopes, level sine handles on flat runs); the new
   node comes up selected. Must also work on NEAR-VERTICAL sections (the
   hit test is nearest-point in 2D, not vertical distance). Holding Shift
   while hovering REPLACES the value readout with the slope (no prefix) —
   it swaps even if the mouse is stationary when Shift goes down. Dragging
   a node past the plot edges keeps its Δt/value readout visible, clamped
   inside the plot.
5b. Edit > Options: "Show heartline (neon)" draws a hairline glowing
   yellow strand along the heart path (between/above the rails per the
   Heart setting), unaffected by lighting; toggling off removes it; state
   survives restart. The two FOV sliders live in Options too (no View
   menu). Clicking "Help" in the menu bar opens the Help window
   immediately (no dropdown). Shift+click a track piece in orbit mode:
   the camera re-centers its orbit on that spot (within ~25 px of the
   rails); orientation/zoom unchanged. Shift+click on EMPTY SKY moves
   nothing — the drag that follows orbits exactly like a plain drag (no
   pivot teleports to off-screen/behind-camera track). Shift-hover slope
   readout changes CONTINUOUSLY as the mouse slides along a curved
   segment (no stair-stepping). Double-click ON a node opens the editor dialog:
   typed transition time/Δ and handle fractions apply on Apply/Enter,
   Cancel/Esc discards, garbage input keeps old values, ripple toggles are
   honored. Typing a HANDLE fraction slides the knob along its current
   angle (curve steepens/relaxes but the tangent direction at the node
   is unchanged — like an S-drag); on node 0 the dialog shows "Start
   speed (m/s)" instead of a transition time and applying it updates the
   Coaster-menu spinner and the stats top speed. The Delete
   key removes the selected node: neighbors
   stay put (positions AND handle settings) with the curve bridging the
   gap; deleting the last node shortens the ride; node 0 refuses
   (v10.7: right-click no longer deletes — see step 48).
6. A appends 1 s; ride time in stats bar grows by 1.0; the appended
   transition's handles sit at the SINE fraction (readout ~36%, not 33%).
   ONLY the active lane gets the new node — switch lanes: the other two
   still end at their old last node with a dotted hold to the new ride
   end (clicking the dotted line still inserts).
7. Space: POV cam starts at t=0 HANDS-OFF — W/S do nothing to the camera
   and the S slide key still works on the graph (drag a node with S held
   while the POV view shows). Right-click the view: mouse vanishes, W
   rides forward at believable speed, S reverses, Shift/Ctrl speed/slow,
   wraps at both ends; moving the mouse does NOT look around. Right-click
   releases (cursor back, W/S return to the graph). While captured, Space
   flips to fly (WASD, mouse look) and Space again back to POV, mouse
   staying hidden throughout. Uncaptured Space exits to orbit. Scroll
   bursts along the track captured or not.
8. Coaster menu > Heart spinner to −1.0: rails jump above the heartline
   (inverted); POV now hangs below track. Gauge visibly widens/narrows rail
   spacing. Menu stays open while spinning (CustomMenuItem hideOnClick off).
9. Ctrl+S (or File > Save) → .fvd file (FVDD3 as of v9.0 — old app
   versions can NOT read new saves; new versions read all three
   formats). As of v9.1 Save writes STRAIGHT to the open file once one
   exists (no chooser; title loses its *) — use Save As… to re-target;
   a never-saved "Untitled" still opens the chooser. Edit something;
   Ctrl+O the file back:
   design, heart, gauge, rail color, start point/speed all restored;
   Ctrl+Z after load returns the pre-load design. Loading a pre-v6
   FVDD1 file must reproduce its curve shapes (handles auto-convert
   from absolute seconds to fractions); loading a v7/v8 FVDD2 file must
   reproduce it exactly (start line → piece placement).
10. Undo spam: Ctrl+Z ×20 walks history without exceptions; one drag = one
    undo step; Ctrl+Shift+Z ×20 walks it all forward again (see step 36).
11. Coaster menu > "Rail colors…" opens the color dialog; picking a solid
    color switches to solid mode and repaints the rails. "Custom Color…"
    inside the picker must NOT crash (regression: it did when the picker was
    embedded in the menu). Switch back to gradient: G-force coloring
    returns. Change the four gradient stops — both a COLOR (e.g. 1 g to
    white) and a G VALUE (e.g. move stop 3 from 3 g to 2 g): rails recolor
    live; colors AND stop values survive a save/load round-trip; Ctrl+Z
    inside the dialog steps both kinds of edits back. With "Show
    heartline" on, entering POV hides the neon strand (no flicker across
    the lens) and exiting POV — or flipping to fly with Space — brings it
    back.
12. Edit > Shortcuts: rebind POV to another key (click button, press key) —
    new key rides, old key does nothing, ↺ appears; restart the app and the
    binding survives (~/.fvd-designer.properties); ↺ restores Space and
    disappears. Escape during capture cancels. Stats-bar hint tracks changes.
13. Edit > Options: FOV slider changes POV lens live (ride while dragging);
    value survives restart.
14. Fly mode: right-click the 3D view — cursor vanishes, view starts at the
    exact orbit pose; mouse looks around smoothly (no cursor escaping the
    window), WASD flies (A strafes, does NOT append track), Shift is fast,
    Ctrl slow, scroll lunges forward. Right-click exits: cursor back and the
    VIEW STAYS WHERE YOU FLEW (no snap-back) — even when the mouse is
    MOVING during the right-click (regression: stale orbit anchor jumped
    the view); orbit drags now circle the
    point you were looking at. Space during fly switches to captured POV.
    Roll lane Ctrl-snap defaults to 10 °/s steps (adjustable in Options).
16. Coaster menu > "Tie every (m)": ties appear between the rails, evenly
    spaced along the track (not bunched on slow sections), ends flush —
    nothing pokes past the rails; lowering the value visibly densifies
    them; value survives save/load round-trip.
17. Entering/exiting fly mode: NO field-of-view jump in either direction
    (fly & orbit share one FOV setting). The "Fly & orbit FOV" slider
    widens both live; "POV camera FOV" affects only the ride cam. The
    snap-step spinners' arrows walk the 1-2-5 series both ways
    (0.05 → 0.1 → 0.2 → 0.5 …); typed values are kept as-is. Every Options
    row has a ↺ that restores its default. "Mouse sensitivity" affects
    orbit dragging AND fly look. Scrolling in fly/POV/orbit/graph works
    IDENTICALLY with Shift held (Windows turns Shift+wheel into horizontal
    scroll — must not go dead). Hovering the curve shows the insert dot
    plus the value at that point.
18. Coaster menu > Start x/y/z: track translates; speeds/forces unchanged
    (energy is relative to start height); grid stays at y=0; values
    survive save/load. Start speed spinner: raising it visibly speeds the
    POV ride and raises the stats top speed; the same value appears in
    node 0's double-click dialog and edits made there show in the menu;
    survives save/load (v0 line); Ctrl+Z inside the open menu steps
    spinner changes back, AND after closing the menu plain Ctrl+Z steps
    them back too (v9.1: heart/gauge/start/speed are document undo).
19. Graph: scroll zooms the time axis around the cursor; ticks and the
    "Time (s)" / rotated lane-name axis labels stay correct; right-drag
    pans when zoomed; node editing works identically while zoomed; zooming
    fully out resumes auto-fit (appending track re-fits again). Grid lines
    sit on nice values (…, 0.5 s, 1 s, 2 s; 0.5 G; 10 °/s…) and re-density
    as you zoom; tick labels ride the same lines.
20. Zoom into a short steep transition: the hover dot appears close to the
    surrounding nodes (down to 0.02 s away) and clicking inserts — the dot
    never shows where a click would refuse.
21. Select a node, press Z: translucent vertical guide appears, drags only
    change value; Z again unlocks. X: horizontal guide, drags only change
    time. Selecting another node clears the lock. H with one node
    selected levels its handles (both dv 0); with 2+ selected every
    node jumps to the PRIMARY node's value with flat handles — the run
    becomes one horizontal line (v10.5: this was Shift+H; Shift+H now
    LEVEL-LOCKS, see step 46); segments to unselected
    neighbors still transition. Primary = last clicked, or the EARLIEST
    node of a box selection (check its ring). All keys rebindable in
    Shortcuts.
22. POV: scrolling in the 3D view lunges along the track (both directions,
    wraps); a translucent vertical line on the graph tracks the camera and
    moves with W/S/scroll; Edit > Options unchecks it away live.
23. Drag the divider between 3D view and graph: both resize live; the ▼
    button (bottom-right of the 3D view) collapses the whole bottom panel
    (stats + toolbar + graph) to full-screen the viewport, turns to ▲, and
    restores to the previous size when clicked again. With the window
    NON-maximized and resized smaller, the button and divider must remain
    visible and functional (regression: SubScene min-size pushed them
    off-screen).
24. Coaster menu open: change Heart a few times, Ctrl+Z steps them back,
    Ctrl+Shift+Z forward — all while the menu stays open. Close the menu:
    plain Ctrl+Z now steps the heart changes back TOO (v9.1 — path values
    are document undo; each spinner click = one step), then continues
    into curve edits. Tie spacing does NOT document-undo (cosmetic).
    Same menu-local behavior inside Edit > Options (FOV drag = one undo
    step; app settings never document-undo) and the rail-color dialog
    (colors dirty the file but don't document-undo).
25. Value axis: default ranges (Gv −1..4, Gl −1..1, roll ±150); drag a Gv
    node to 5 g — the axis expands to a rounded bound; delete it — the
    axis returns to default. Axis stays still while dragging.
26. Hold a node PAST any plot edge without moving the mouse: that axis
    grows continuously, faster the further past the edge (right edge =
    ride extends; top/bottom = value scale expands); pull back inside and
    growth stops instantly; view re-fits on release. No runaway.
    Move a lane's last node left of the ride end: dotted flat line appears
    to the ride end; hovering it shows the dot and clicking appends a node
    on it. After any click-insert, immediately click-drag the new node:
    it must MOVE (not grab one of its handles).
26b. Mid-drag combo guard: start dragging a node, hold Ctrl (snap engages),
    then press and hold S — the drag starts SLIDING (axis constraint) and
    NO save dialog appears; release the mouse with Ctrl+S still held —
    still no save dialog (key auto-repeat must not fire it); release
    everything, plain Ctrl+S saves normally. Same drill with Z instead of
    S: mid-drag Ctrl+Z does NOT undo (the axis lock simply doesn't engage
    while Ctrl is down; nothing else fires either).
27. Shift+click two more nodes (faint rings): dragging any moves all three
    (clamped against unselected neighbors); HOLDING S mid-drag constrains
    IMMEDIATELY to the dominant axis and SWITCHES axes if you pull further
    the other way (total dx vs dy); the node returns to its original
    row/column; Shift while dragging does nothing axis-wise. Guide colors:
    vertical guide = lane color (value changing), horizontal = light purple
    (time changing, v10.2); "Time (s)" label is the same purple. Z/X
    override S's choice
    (press again to return it). With exactly two ADJACENT nodes selected,
    F straightens the segment between them into a line (smooth neighbors
    stay C1). Ctrl-drag a handle far out: snaps at 0.1 steps up
    to 1.5× the segment (curve may fold; must stay finite).
    Arrows nudge the group; Delete removes them all. Lane buttons show
    their curve colors.
28. Ground: dense 1 m lines near the track fade over bands; past ~120 m
    only 10 m lines, past ~240 m a smooth solid translucent sheet; no
    shimmer zoomed out, no z-fighting stripes between lines and sheet.
29. Help > Controls & Features opens a non-modal scrollable reference
    (usable while editing) with bulleted sections and the node/handle
    diagram; spot-check that it mentions the newest features — it is
    REQUIRED to stay current with every change.
30. Drag one handle of a smooth node until the partner leaves the view:
    NO fake kink at the node — the partner draws as a dashed stub with a
    hollow marker at the plot edge; clicking the marker expands the value
    scale to show the whole handle (editable); clicking empty plot
    restores the scale. Pulling a knob past the plot edge grows the view
    like nodes do.
31. Select a node whose handles overlap it: pressing on the node body
    always drags the NODE, never a knob. Append with it selected inserts
    a 1 s flat hold (later track identical, 1 s later, in all lanes);
    without a selection Append still extends the ride end.
32. Options: two FOV sliders (track view vs fly — changing one leaves the
    other camera alone), fly sensitivity slider takes effect immediately,
    snap-step spinners change Ctrl-snap/arrow-nudge granularity live.
    Box select: drag a rectangle on empty plot — nodes inside get rings
    (Shift adds to the existing selection); a plain click still deselects.
    Zoom to a 0.2 s window: grid/ticks show 0.02/0.05 s steps with two
    decimals, no repeated labels. Fly/orbit/POV: the floor grid's dense
    region follows the camera (fly far from the track and look down).
33. File > Export NL2 CSV (or Ctrl+E): dialog offers rail center /
    heartline radios + segment length spinner (↺ resets 1.0 m); Export…
    prompts for a .csv and the stats bar reports points/spacing/length.
    Heartline is the default (fresh settings file). Both settings survive
    an app restart (they're per-user, not in the
    .fvd). Ctrl+Z inside the dialog undoes setting changes. In NoLimits 2:
    import the file as an external/CSV track — the layout must match the
    designer INCLUDING turn direction and banking side (if turns come out
    mirrored, the Z-negation in `Nl2CsvExporter` is wrong — see D40) and
    segment spacing should look even. Rail-center export should put NL2's
    rails where the designer draws them; heartline export sits one Heart
    setting above.
34. Straight lock: select two adjacent nodes, Shift+F — the segment snaps
    straight and draws as TWIN thin lines (single 2 px stroke gone). Drag
    either endpoint anywhere: the segment stays a perfect line. Drag one
    of the segment's facing knobs: it slides ALONG the line (length
    changes, line doesn't bend); an S-drag feels identical. Double-click
    an endpoint and type a handle value — still straight after Apply.
    Click-insert ON the locked segment: both halves stay locked (four
    thin lines); delete the inserted node: merged segment still locked.
    Select just ONE endpoint, Shift+F: unlocks (normal single stroke
    returns, handles stay where the lock left them). Lock survives a
    save/load round-trip (n-line 8th field) and Ctrl+Z restores the
    pre-lock handle shape. In the node dialog the handle rows read just
    "In handle"/"Out handle" (no "(fraction)").
35. Toolbar "Resulting" dropdown: check "Pitch rate" — a thin dashed
    TEAL curve appears (rates wear the geo palette since v10.2); check
    "Yaw rate" — dashed YELLOW joins it. Both stretch across the WHOLE
    ride (v10.8; a tiny deliberate gap at each section boundary — the
    derived data smears there) on their OWN shared scale, labeled
    down the plot's RIGHT edge (v10.3: ticks + rotated "Resulting rate
    (°/s)" title; the plot narrows slightly while shown; the roll
    lane's axis no longer expands for them). On the
    default layout the pitch-rate curve
    swings through the hills while yaw stays ~0 (it's a no-lateral
    layout). There is NO roll entry (the resulting roll rate is the
    roll lane itself). Unchecking both removes the right axis and
    restores the full plot width.
    Picks survive an app restart. Editing nodes updates the curves live.
    With a GEO section active the same two picks retitle to "Vertical
    G (g)" / "Lateral G (g)" and draw the felt forces in the FORCE
    palette (Gv blue / Gl green since v10.8 — swapped), the right axis
    retitling to "Resulting G (g)".
36. Redo: drag a node, Ctrl+Z — it snaps back; Ctrl+Shift+Z — the drag
    returns. Undo ×5 then redo ×5 walks forward through the same states.
    After an undo, make ANY new edit: Ctrl+Shift+Z now does nothing (the
    redo branch cleared). Works for deletes, inserts, appends, dialog
    edits and loads. Inside menus/dialogs Ctrl+Shift+Z still redoes the
    MENU-local edits only (unchanged).
37. Ortho views & orbit gestures: press T — the view snaps straight down
    with NO perspective (rails don't converge) and the grid runs SQUARE
    to the screen (yaw snapped to the nearest 90°); the picture is the
    same SIZE as before the snap. Select a node mid-turn (somewhere the
    track no longer heads east), Shift+T — the view centers on that
    node AND the track runs HORIZONTALLY across the screen there (lens
    tangent to the local heading, v9.3); approach from the other side
    of the track first and the view opens from THAT side (no 180°
    flip). The node's marker is white and larger than the rest. With
    nothing selected the side view is the world X–Y elevation and keeps
    the current center. While IN the side view, select a different node
    and press Shift+T again: the view re-centers and re-tangents WITHOUT
    exiting; the side view has no same-key exit — orbit away or T, T. RIGHT-drag in either view pans (world follows the
    mouse, view stays snapped, grid dense region follows); right-click
    does NOT enter fly while a snapped view is active. Scroll zooms in
    both (smooth, no sudden clamp). The same key returns to the exact
    perspective view you left; orbit-DRAGGING (primary or middle)
    instead keeps the direction you're facing and restores perspective
    without a size jump. MIDDLE-drag rotates the orbit exactly like
    primary-drag. Hold C and click the track: the pivot re-centers
    there (same as Shift+click); C+click on sky does nothing.
38. Track markers & insert: small spheres sit between the rails at each
    ACTIVE lane node (lane color; selected nodes white — click nodes in
    the graph and watch the spheres follow; switch lanes and they move
    to that lane's nodes). Options unchecks them away live. Ctrl+click
    ON the track: a node appears in the active lane at that point of
    the ride (graph shows it selected; one Ctrl+Z removes it);
    Ctrl+click on sky does nothing. Markers must NOT flicker or lag
    while dragging graph nodes (signature-guarded rebuild).
39. File lifecycle (v9.1): fresh launch shows "Untitled — FVD Coaster
    Designer"; drag any node → "Untitled*". File > New with changes:
    Save/Don't Save/Cancel dialog — Cancel keeps everything; Don't Save
    gives a flat 1 s track (Gv 1 g, straight line, all defaults, title
    "Untitled", empty undo — Ctrl+Z does nothing); Save on a never-saved
    doc opens the chooser first (cancelling THAT aborts the New). Save
    to a file: title shows "name.fvd"; edit → "name.fvd*"; Ctrl+S
    silently rewrites the same file and the * clears; Save As… asks for
    a new name. Load with unsaved changes shows the same gate. Undo of
    heart/gauge/start/speed changes also works after New (fresh
    history). Ctrl+N is rebindable ("New design").
40. Sections (v9.5): a dashed vertical line with a circled + sits just
    past the ride end. Click the + → menu; "Add force section (1 s
    hold)" appends a strip holding the current end values — the new
    section is ACTIVE (its curves normal), the original layout is now a
    faint strip left of the boundary line, and the ride/stats grow by
    1 s. Click the faint strip → editing returns to it (selection
    clears); click back. Node edits inside section 2 behave exactly as
    ever (drags, snapping, inserts, dialogs — all in its own stretch of
    the axis; a knob dragged across the boundary still belongs to its
    node). Ctrl+Z after adding removes the section (redo restores).
    Ctrl+click the 3D track INSIDE section 2's stretch: section 2
    activates and gets the node. Node markers follow the ACTIVE
    section. The node-0 dialog offers "Start speed" only in section 1.
    Save → load: both sections and the active-strip behavior survive
    (FVDD3). POV rides across the boundary seamlessly. With ONE section
    everything must look exactly as before (feature-neutral baseline).
    v9.6/9.7 polish: drag the last node up the '+' line — the ⊕ hops
    down the ladder (30%/50%/70%/bottom) to a clear spot, skipping
    heights where the OTHER lanes' zero-value end nodes sit; wherever
    the ⊕ ends up, a node dragged onto it still SELECTS on click (nodes
    outrank the button — the menu opens only from its exposed edge);
    inactive strips are clearly legible (alpha 0.20). Select section
    2's FIRST node, press B (or use "Match slope across boundary" in
    its dialog): its out-handle angles so the curve continues the
    previous section's end slope with no kink at the boundary — then
    drag it freely (no lock). Same from section 1's LAST node toward
    section 2. B on the ride's very first/last node does nothing;
    B on interior nodes still toggles smooth/broken.
41. Geo sections (v10.0, behavior updated v10.1): click the ⊕ → the
    menu offers "Add geo section (1 s hold)" (bridge/curve and
    attach-piece still disabled). Add one after the default layout:
    the track CONTINUES ITS END MOTION (the new section holds the
    resulting end pitch/yaw + the authored roll — the default layout
    ends level, so it runs near-straight), the lane buttons relabel
    AND recolor from Vert G (blue) / Lat G (green) / Roll (orange) —
    Gv/Gl colors swapped in v10.8 — to Pitch (teal) / Yaw (yellow) /
    Roll (orange), the
    y-axis label reads "Pitch rate (deg/s)" and the value axis shows
    ±60. Drag the pitch lane's end node up to ~40: the extension
    curls upward live; yaw bends it sideways; roll twists it (watch
    the ties). 3D node markers wear the geo colors too. Ctrl-drag in
    ANY geo lane snaps by the ROLL step (whole °/s, not 0.1 g). The
    1 g green reference line is gone in geo lanes; hover readouts and
    the node dialog say °/s. Add a FORCE section after the geo one:
    its lanes hold the felt end forces (stats bar Gv range doesn't
    jump). Undo removes sections one at a time and restores the right
    mode/labels/colors; redo brings them back. Save → load: modes
    survive ("section geo" line in the .fvd); the rail G-force
    gradient colors the geo stretch by the forces it actually
    produces (dive in a geo section = red at the pull-out even though
    no force lane authored it). POV rides the whole chain seamlessly.
42. Section UX round (v10.1): with force + geo sections in the ride —
    UNIFIED VIEW: editing the geo section, the force strip shows
    faint teal/yellow/orange RATE curves (its resulting rates — the
    default layout's pitch swings, yaw flat); click into the force
    section and the geo strip shows faint blue/green/orange FORCE
    curves (what its rates make the rider feel; Gv blue / Gl green
    since the v10.8 swap). Curves meet at the
    boundary with no unit clash; all strips share the active axes.
    Every lane curve (active, dimmed lanes, strips) has a translucent
    same-color fill down to its zero line; the dashed "Resulting"
    overlays have NO fill and draw ONLY across the section being
    edited. BOUNDARY RETURN: drag a section's first node somewhere
    wild, press B (or "Match boundary (value + slope)" in its
    dialog) — the node jumps back to the previous section's end value
    and takes its end slope; works at force↔geo boundaries too (from
    the resulting curves) and when the neighbor lane HOLDS at the
    boundary the matched slope is flat. BADGES: every strip wide
    enough shows a force/geo tag at its top-left (active = brighter);
    click it → info line + "Delete section" (greyed with one section
    only). Deleting a middle section re-chains the later ones from
    the new end; deleting the active one selects its predecessor
    (labels/colors follow); Ctrl+Z restores the section, redo deletes
    again. MENUS: with the ⊕ or badge menu open, click ANYWHERE on
    the graph — the menu closes and nothing else happens (no node
    grabbed, no insert). INSERT-DRAG: press on the curve and drag
    without releasing — the node inserts and follows the mouse in one
    motion; same with Ctrl held from the start (snapping live);
    Ctrl+Z after release removes the node entirely (one step).
43. Snap/boundary round (v10.2): make a lane end EARLY in a section
    (drag its last node left of the other lanes' end). Plain-drag it
    right: it STOPS DEAD at the section end — release, grab it again,
    and now it pulls past, extending the section (two deliberate
    gestures). Ctrl-drag it toward the end: within half a snap step of
    the end there is NO grid stop just before it — it snaps exactly ON
    the end (readout Δt lands on the full remaining gap); further left
    the normal grid points return. Options: check "Time snap: absolute
    ride time" — Ctrl-drags now land nodes on the axis-tick grid
    (whole 0.1 s positions even when the previous node sits off-grid);
    check "Value snap: Δ from previous node" — Ctrl-drags land values
    at 0.1 g offsets from the previous node's value instead of the
    absolute grid; both survive a restart; Ctrl+arrow matches the same
    references. B at a force↔geo boundary: ONE press lands the edge
    node on the neighbor's boundary value/slope (a second press does
    nothing new; matching the section END against a cross-mode NEXT
    section may take a couple of presses — its felt values move with
    our exit pose). No tiny angled connector at strip seams (derived
    curves and overlays stop ~0.025 s short of the boundary). Click
    directly ON a faint strip's curve: the section ALWAYS activates
    (previously stolen as a lane switch when the click lined up with a
    held value). Time is LIGHT PURPLE everywhere: "Time (s)" label,
    horizontal drag guide, POV marker line.
44. Dialog/overlay round (v10.3): Edit > Shortcuts now shows TWO
    columns with bold category titles — Graph editor (left), View /
    File / History (right); rebinding and ↺ still work in every
    column and the dialog fits a 1080p screen. Edit > Options: the
    snap references are two LOCAL/GLOBAL radio rows — "Horizontal
    snap (time)" defaults Local, "Vertical snap (value)" defaults
    Global; flip them and Ctrl-drags follow (see step 43); ↺ restores
    each default; choices survive a restart. "Resulting ▾": the menu
    is a dark popup that STAYS OPEN while clicking the checkboxes
    (toggle both in one visit) and closes ~¼ s after the mouse leaves
    both the popup and the button — hovering back inside cancels the
    close; hovering the button itself never closes it. With a curve
    enabled: value ticks run down the RIGHT edge of the plot with a
    rotated axis title; hover the dashed curve — a dot rides it with
    "pitch 23.4 °/s" (or "Gv 1.82 g" in geo sections) in the curve's
    color; the readout follows the mouse along the curve and never
    appears while the white insert dot is showing on the editable
    curve.
45. Boundary/lock round (v10.4; the C1 "no-kink" re-timing that
    shipped here was REVERTED in v10.5 — re-timing squishes ALL
    handles with their segments again, kinks included, per user
    decision). Shift+B on a section's first/last node:
    ALL THREE lanes' edge nodes jump to their boundary values/slopes
    at once; one Ctrl+Z restores all three (rebindable as "Match
    boundary: all lanes"). Straight-lock: lock a segment (Shift+F) and
    select an endpoint — the knobs FACING the locked segment are gone
    (nothing to drag; the line can't bend); outer knobs remain.
    Insert a node on the locked segment, drag it (two straight
    halves), then Shift+F to unlock: the node comes out BROKEN
    (square) since its sides have different slopes — but insert on a
    locked segment and unlock WITHOUT moving anything: it stays
    smooth (slopes match). "Resulting" popup: click the button and
    IMMEDIATELY click a checkbox — the popup must NOT vanish while
    the mouse is over it (it only closes ~¼ s after the cursor is
    truly outside both popup and button — flick the mouse in and out
    quickly to confirm re-entry cancels the close). Shift while
    hovering a resulting curve swaps its readout to the slope
    ("pitch −14.2 °/s/s"), toggling live like the lane hover.
46. Level lock (v10.5, toggles refined v10.6): select 3 adjacent nodes
    at different values,
    press H — they jump onto the MAIN node's value, one horizontal
    line (H with a single node still just flattens its handles).
    Undo, then press Shift+H instead: same leveling PLUS the run
    locks — twin thin lines with a TINTED BAND between them (the
    straight lock's look plus fill). Drag an interior locked node
    vertically: it snaps back onto the line (horizontal moves work);
    drag EITHER END node vertically: the whole line moves with it
    (v10.6 — both ends steer). Click-insert on the locked stretch: the
    new node lands ON the
    line and both halves stay locked; dragging it slides along the
    line only. Knobs facing the locked stretch are hidden. Shift+F
    with the two endpoints of a level segment selected: the band
    disappears, plain twin lines remain (now a straight lock — drag
    an endpoint vertically and the line ANGLES instead of staying
    level); Shift+H on it again: it re-levels and the band returns
    (direct swap both ways). Shift+H with ONE locked node selected
    unlocks (kinked joints come out broken). Locks survive save/load
    (9th n-field) and Ctrl+Z steps each lock/unlock back. RESULTING
    POPUP SEAM: open the popup and park the cursor exactly on the
    boundary line between the button and the popup — it must NOT
    close (the old 2 px gap read as "outside").
47. Pair-priority toggles + multi-F (v10.6): straight-lock a segment,
    then select ITS RIGHT NODE plus the next unlocked node and press
    Shift+F — the NEW segment locks and the existing lock is
    UNTOUCHED (previously this unlocked the neighbor instead). Same
    drill with Shift+H against a level-locked neighbor. Select THREE
    nodes and Shift+F: both segments between them lock straight (two
    independent lines hinging at the middle node — drag it and both
    stay straight); select all three again, Shift+F unlocks both.
    Select a locked pair plus one unlocked neighbor segment's node:
    Shift+F locks the unlocked one too (mixed = lock everything
    between). Selecting a level pair and its straight-locked neighbor
    pair, Shift+H: everything between the selected nodes goes LEVEL
    (the straight neighbor converts — it was between selected nodes,
    not merely neighboring). A single selected endpoint still
    unlocks its own segment(s), both lock types.
48. Right-click + snapped inserts (v10.7): right-click a NODE — its
    editor dialog opens (identical to double-click; the node gets
    selected; NOTHING is deleted). Right-click the CURVE between two
    nodes — a three-way menu appears: Unlocked / Straight /
    Horizontal, with the segment's current state checked; picking
    Straight shows the twin lines, Horizontal levels the right node
    onto the left and adds the tinted band, Unlocked clears either
    (kinked joints come out broken); each pick is one undo step and
    the menu dismisses on the next graph click like the other menus.
    Start a right-DRAG on the curve while zoomed in: it PANS (no menu
    on release after real movement); a clean right-click on empty
    plot still just pans/does nothing. Ctrl-hover the curve: the
    insert dot JUMPS between snapped times (Δt grid from the previous
    node by default; absolute grid when the horizontal snap is
    Global) while staying ON the curve vertically; pressing Ctrl with
    the mouse parked moves the dot without wiggling; clicking inserts
    exactly at the dot (drag-chain and undo unchanged); near a node
    where the snapped spot has no room the dot disappears and the
    click does nothing.
49. Reversal (v10.9 mechanics; PIECE-scoped UI since v12.1 — see
    step 53 for the current control; the per-section badge check is
    GONE). The underlying per-section behavior still holds and is
    what step 53 exercises through the piece operation: a reversed
    section runs its authored shape back-to-front; the active one
    still edits its lanes in the AUTHORED direction (drag a Gv node
    and the mirrored stretch updates live); as an INACTIVE strip it
    draws the derived train-direction curves (like a cross-mode
    strip); B on a neighbor's boundary node matches against its
    compiled curves. Pitched/banked seams show an honest kink
    (yaw-only alignment per section). The ◀ badge marks flagged
    sections; the flag stays the 5th token of the `section` line.
    Stats bar Gv range unchanged by reversal; felt LATERAL G flips
    sign on a reversed turning section.
50. Straight/curve sections (v11.0): '+' past the ride end → "Add
    straight/curve section" — a 10 m dead-straight continuation
    appears (3D: no kink at the seam whatever preceded it), the strip
    shows one slate band with a "— 10 m" chip, lane buttons GREY OUT,
    the y-axis reads force units and the felt-G curves draw faintly
    behind the band. Right-click the band → "Insert curve after" — a
    rose band ("R20 90°→", 2 m smoothing pre-set) and the 3D track
    turns right through 90°. Drag the separator between them: the
    straight resizes live (readout "length … m"; Ctrl steps by the
    Options element-length snap, default 1 m; cursor is ↔ over
    grabbable lines); drag the section END line: the curve re-angles
    at its radius (readout "arc … m (…°)"). Drag the ◇ diamond away
    from its boundary — the shaded blend zone widens and the 3D
    corner visibly eases (curvature ramps instead of jumping); drag
    it back onto the boundary — it snaps hollow (sharp). Double-click
    the curve band: dialog — set direction 90 → the turn becomes a
    pull-UP; angle −90 mirrors; entry smoothing types exactly;
    element 0's smoothing field is disabled (seam). Delete removes
    the selected element (last one refuses); A appends a straight.
    Click a lane-section strip then back — selection/deselection and
    the mode swap are clean both ways; B on the lane section's edge
    node matches against the s/c section's compiled felt G. Reverse
    the s/c section via its badge: elements draw in TRAIN order
    (chips mirrored) and drags still work. Undo steps every element
    edit/insert/delete/drag as one step each; save → load → identical
    elements (elem lines); NL2 export includes the s/c stretch. Add a
    force section AFTER the s/c one: it holds the felt end G
    seamlessly. POV rides the whole chain smoothly.
51. S/c drag-extend + roll (v11.1): drag the section-END separator
    RIGHT — the last element GROWS past the old end (v11.0 could only
    shorten; keep pulling and the view edge auto-grows). Same for
    internal separators. The toolbar button reads just "Append".
    Roll: build dip-then-turn (curve R30 −20° dir 90, straight,
    curve R20 90° dir 0) — with the default roll (Global bank 0) the
    rails stay LEVEL through the whole thing, visibly twisting in
    POV. Open the turn's dialog → Roll = Rate (°/s), value 0 — now
    the turn banks over as it comes off the slope (the old drift).
    Set Global bank 25 on a straight: the track twists in over ~2 m
    and holds 25° vs the horizon (chip shows ∠25°); Rate 40 on a
    straight twists continuously (chip ↻40°/s). Element 0 accepts
    roll too (only its SMOOTHING is seam-locked). Save/load keeps
    modes and values; a v11.0 save opens with its old geometry
    (loader defaults those elements to Rate 0).
52. Pieces (v12.0; split exactness v12.1): with a 3-section ride,
    click section 2's badge →
    "Split piece before this section". A "Piece 1/2 ▾" button appears
    on the toolbar; the graph now shows only piece 1's strips and the
    3D track is COMPLETELY UNCHANGED — split anywhere, mid-hill,
    banked, pitched: nothing moves (the boundary's full orientation
    lands in the new piece's base orientation, v12.1).
    The button lists both pieces (✓ marks active); picking piece 2
    jumps the graph to its stretch of the timeline (axis continues
    piece 1's time). Coaster menu now reads "Piece x/y/z" + "Piece
    yaw": with piece 2 active, change x/z/yaw — piece 2 slides/turns
    rigidly in 3D (yaw on TOP of its natural orientation; set it back
    to 0 and the original orientation returns exactly), piece 1 never
    moves, the spinners re-sync when you
    switch pieces. 3D Ctrl+click on either piece's track activates
    that piece + section (and inserts a node on lane sections, as
    ever). ⊕ at piece 1's end → "Attach floating piece" → Piece 2:
    the pieces merge back into one chain re-laid from piece 1's end
    (the moved placement is discarded), and the piece button hides.
    Undo steps split/attach/placement each as one step, restoring the
    active piece too. Save with two pieces → load: both pieces +
    placements + base orientations return. Deleting a piece's only
    section deletes the
    piece (badge > Delete); the POV rides pieces in order, jumping
    across the gap between them.
53. Reverse piece (v12.1): Coaster menu > "Reverse piece (flip travel
    direction)" on a level-ending ride: the 3D track DOES NOT MOVE
    but the POV now runs it the other way (the old exit is the new
    start; the stats ride time is unchanged). The strips reorder to
    train order and every section badge wears ◀; the section you were
    editing stays active at its new position. Sections still edit in
    their authored direction (step 49). Reverse again: everything —
    order, badges, geometry, placement spinners — returns exactly.
    On a ride ending pitched/banked, reversing re-joins interior
    seams with visible kinks where they aren't level (honest, like
    D67) — undo restores. Ctrl+Z steps each reversal; save → load
    keeps the reversed state (flags + piece base orientation).
54. Bridges (v13.0; live-follow + G² + any-piece + optimize v13.1):
    split a ride, move piece 2 well away (x/z/yaw), then on piece 1:
    ⊕ → "Bridge to piece" → pick piece 2 — a
    smooth spline spans the gap in 3D, the POV rides across without
    jumping, and a "bridge" badge appears on the new strip (its strip
    shows the felt-G curves; the graph timeline includes the bridge's
    seconds). Now MOVE piece 2 again (x/z/yaw spinners): the bridge
    FOLLOWS LIVE, staying glued to both ends while you spin the
    values. Edit piece 1's track upstream — same: the bridge re-joins
    every rebuild. If the seam sits on curved track (e.g. piece 1
    ends mid-turn), look closely at the join: the bridge CONTINUES
    the curve (G² — no visible straightening right at the seam; the
    v13.0 bridge went momentarily straight there). Badge → Bridge
    tangents…: larger entry tangent = leaves straighter and sweeps
    wider; smaller = turns off sooner (one undo step per apply).
    Badge → "Optimize placement of piece 2": piece 2 jumps dead ahead
    of piece 1's end at the same distance with headings aligned — the
    bridge straightens (level ends: dead straight); repeatable after
    further moves; one undo step. With THREE pieces, ⊕ → Bridge to
    piece 3 from piece 1: piece 3 reorders to follow piece 1 on the
    timeline (piece button renumbers) and the bridge connects them.
    Delete piece 2 while bridged: the bridge keeps its last shape and
    its badge turns "bridge !" ("no following piece"). ⊕ on piece 1
    says "Piece ends with a bridge — extend the NEXT piece"
    (add/attach disabled); Coaster > Reverse piece on piece 1 refuses
    with a stats-bar hint. Delete the bridge via its badge — the gap
    returns. Undo steps create/tangents/optimize/delete each as one
    step; save → load keeps the bridge (bpose/bacc lines); NL2
    export includes it.
55. Viewport (v13.1): with two DETACHED pieces there is NO stretched
    rail/heartline connector between them — each piece's track ends
    cleanly in the air (the old ghost tube between piece ends is
    gone). Plain-CLICK (no modifiers, no drag) any piece's track in
    orbit view: the graph switches to that piece + section (piece
    button + strips + placement spinners all follow); dragging still
    orbits, Shift/C+click still recenters, Ctrl+click still inserts a
    node. Clicking empty sky changes nothing.
56. v13.2 UX round: INACTIVE pieces render desaturated/darker in 3D
    (gradient and solid modes both); clicking another piece swaps the
    highlight instantly. The toolbar gains "Placement ▾" next to the
    piece selector — Piece x/y/z, Piece yaw and Reverse piece now
    live there (gone from the Coaster menu; Ctrl+Z inside the popup
    still does menu-local undo). With a bridge ACTIVE a "Gap (m)"
    spinner appears on the toolbar: each up/down click slides the
    NEXT piece dead-ahead to that distance and the bridge re-solves
    live (arrow bursts = one undo step; the value re-syncs if you
    move the piece by hand). SEAM SMOOTHNESS: bridge a rolling/curved
    chain end to a piece with a different design speed — watch the
    Resulting rate overlays flow across both bridge seams with no
    step and no kink (v13.1 snapped visibly); the felt-G curves and
    rail colors blend across too (the bridge's speed eases onto the
    next piece's design speed). A cramped bridge (big drop over a
    short gap) still shows a fast-but-smooth swing INSIDE the bridge
    — that's the geometry; raise the Gap or re-tangent to calm it.
    Bridged pieces read as ONE continuous track in 3D (flush seam);
    deleting the bridge separates them again; a fresh split shows its
    boundary via the saturation change (the tubes genuinely
    coincide).
57. v13.3 fix round: (a) zoom into part of the ride (not fully out),
    '+' → add a section — the view jumps to auto-fit with the new
    strip visible and ACTIVE; the first click on its curve inserts
    exactly under the cursor (no node at the strip's left edge, no
    ghost-only plot). Zoom in near the ride end, delete the last
    section — the window slides back inside the piece instead of
    going blank. (b) Build geo → force, edit the geo section's
    heights (so the design speeds drift), then alternate Shift+B on
    the two edge nodes: the values settle after ~one press per side
    and STAY (pre-v13.3 they grew every press); the rate overlays
    join at the seam. A felt-G step remaining in FORCE view means
    the seam design speeds differ — that's the honest display, not
    a match failure. (c) Split a piece mid-ride: tie spacing on the
    SECOND piece stays identical to the first (was: a tie on every
    sample for the first stretch). (d) Fresh bridges leave/arrive
    with the turning spread through the middle (rate curves rounded,
    not end-spiked); the tangents dialog still sharpens ends on
    demand. (e) Hover the ACTIVE bridge's end line — H_RESIZE
    cursor; drag left/right — the gap follows the cursor with a
    live "gap … m" readout, the NEXT piece slides dead-ahead, the
    Gap spinner tracks, Ctrl snaps whole meters, one Ctrl+Z undoes
    the whole drag, and the tangent sweep scales with the gap.
59. v13.4 bridge deep-dive: split the example layout's banked turn
    (badge > Split piece before this section, mid-turn), then ⊕ >
    Bridge to piece 2 and badge > "Optimize placement … (continue
    the turn)": the bridge continues the SAME turn — all three rate
    graphs stay flat through it, no straight-line-with-transitions.
    Type in the toolbar "Length (m)" box (was "Gap") or drag the
    bridge strip's end line: the second piece slides AROUND the
    turn's own circle, the bridge staying on it; absurd lengths just
    keep circling (undoable). Straight track still optimizes to
    dead-ahead. A bridge created right after a split (zero gap) then
    lengthened gets a sane full-length sweep, not blown-up tangents.
60. v13.5 round: (a) SEAM JOLT — make a geo|force seam, change the
    start speed or an upstream hill so the seam design speeds drift,
    Shift+B once per side: the felt-G curves now JOIN at the line
    (the jolt is gone) and the downstream section's design speed
    re-freezes to match (visible in its badge info). (b) Plain-click
    track selection now lands reliably in orbit AND hands-off POV,
    including on close-up track between sample dots; empty space
    between detached pieces still misses. (c) Hover a bridge or s/c
    strip's curves: value readout in the lane color; hold Shift for
    the slope — exactly like lane hover. (d) With a bridge active,
    the toolbar Placement ▾ shows the three placement modes:
    position+yaw (default) re-optimizes on every Length change;
    position-only obeys your typed next-piece yaw; no-optimization
    frees x/y/z + yaw and disables the Length box and end-line drag.
    Modes persist through save/load. (e) Badge > "Unfreeze (edit as
    geo section)": the bridge becomes ordinary editable geo lanes,
    the run splits there, the seam stays visually tight (tame
    bridges essentially exact); Ctrl+Z brings the live bridge back.
61. v14.0 convert expansion: (a) PARTIAL CONVERT EVERYWHERE — badge
    menu of a force/geo/s-c section > "Convert part to … section…":
    the split line appears; drag it (readout shows the exact %),
    click a side, press the chip. The unconverted side's curves and
    3D track do not move; the converted side re-fits. On an s/c
    strip the line refuses to sit inside transition blends (it
    jumps past them). (b) NODE SNAP — with the line up on a lane
    strip, click any node: the line lands exactly on it (drag near
    one: it magnetizes); the cut then starts/ends the halves at
    that node with no extra node inserted. Clicking elsewhere in
    the strip still flips the convert side. (c) DENSITY WINDOW —
    press any convert (whole or the chip): the popup appears; drag
    the log slider and watch the node carpet re-fit LIVE with the
    original curves dashed behind; type a value; check OPTIMAL
    PLACEMENT: flat lanes drop to two nodes and the slider becomes
    accuracy (now down to 0.01 %); Apply commits (ONE Ctrl+Z returns the original),
    Cancel restores exactly — including after slider excursions.
    While the popup is open, plot clicks do nothing (pan/zoom still
    work); switching pieces cancels. (d) WHOLE BRIDGE — badge >
    "Convert whole bridge to geo section": the bridge becomes
    lanes, the next piece's sections chain on after them (ONE
    piece, connected track, POV rides through). (e) EXACT % — cut a
    bridge at 50%: the surviving bridge's Length spinner reads
    exactly half the original arc. (f) PADLOCKS — the bridge
    panel's yaw/pitch/roll locks: red tint = locked, green =
    unlocked, flipping live. (g) UNDO PITCH/ROLL — set a next-piece
    pitch delta, make any edit, Ctrl+Z: the pitch delta survives
    (it used to zero).
62. [REVERTED, D95] v14.1 banked bridges: rebuild the S-hill blowup — bank one way,
    airtime hill rolling to the other bank, delete the middle,
    bridge the halves. (a) "Optimize position and orientation": no
    more looping/corkscrew — the bridge rolls smoothly between the
    banks, the felt-G and rate graphs flow through both seams, and
    the placed piece sits at a sane height. (b) Optimize length now
    lands at a rideable length (tens of meters, scaled to how fast
    the neighboring track itself rolls/turns) instead of 100+ m.
    (c) Hand-set lengths look clean across the range — no "obvious
    dramatically better placement". (d) Repeat-switching modes or
    re-ticking the Length spinner moves nothing (deterministic).
    (e) Level/planar bridges (split turns, straight gaps) behave
    exactly as before.
58. v13.4 round: (a) BRIDGED RUN — bridge piece 1 to piece 2: the
    graph now shows BOTH pieces' strips plus the bridge as one
    timeline; click any strip or badge on the far side — it
    activates without the zoom jumping; the '+' sits past the LAST
    piece's end and appends there even while editing upstream; in 3D
    the whole bridged run is full-color (only detached pieces
    desaturate). Delete the bridge — the graph splits back to the
    single piece's window and the other piece keeps its position
    exactly. (b) Shift+B at a BANKED geo↔force seam (roll ramped
    through the transition, sloped handles): alternate sides
    repeatedly — after the first press per side NOTHING changes
    anymore, in both mode orders. (c) Reverse a piece, EDIT its
    lanes afterwards, then bridge into it: the bridge meets the
    reversed entrance smoothly (no sharp elbow). "Optimize placement
    of piece N" on ends with different pitches now sets the chord
    between the slopes (no dip-and-recover bounce); repeat-clicking
    it is stable. (d) A bridge between rolling track: the roll-rate
    curve now LEAVES the seam on the neighbor's slope instead of a
    flat shelf (cramped bridges still swing fast inside — that's the
    demanded geometry). (e) The Gap box refuses letters entirely,
    and riding/flying with W/A/S/D never types into it. (f) In POV
    with the mouse visible (uncaptured), plain-click any track —
    that piece+section activates like in orbit view.
15. Ripple checks (filled square = on, no checkmark glyph): with only
    "time" on, dragging a middle Gv node right 1 s moves every later node
    of that lane right 1 s (later transition times unchanged, shapes
    preserved) but vertical drags move only the node; with only "value" on,
    the reverse. Other lanes unaffected. Both off: old clamped single-node
    behavior. Ctrl+Z undoes a whole ripple drag as one step.

119. v18.8 (D177-D178): (a) Load "B Shortcut not working
    7.28.26.fvd", select the geo section, Shift+B on its first node:
    the resulting/true ROLL graph no longer steps ~35 deg/s at the
    force->geo seam (residual ~1 deg/s), re-presses stable; the
    geo->force seam behaves as before. (b) Top-left of the viewport:
    a "Fly/Orbit >" label; click it, pick POV/Top/Side - the camera
    switches and the label follows every mode change (Space, T,
    Shift+T, fly included). (c) T / Shift+T are TRUE orthographic:
    parallel rails stay parallel across the screen (no vanishing
    point); zoom/pan/T-exit/orbit-away behave exactly as before;
    clicks, node-marker picks, gizmo drags and comb hovers all work
    in ortho. (d) The XYZ compass sits bottom-LEFT; hover a comb
    tooth (readout appears) or ride POV backward - the compass hops
    just above the label, never overlapping. (e) COLLAPSE the graph
    panel, enter POV: node markers sit at TRACK level (not floating
    at the heartline). (f) Hover readouts near the plot's right edge
    (lane value, resulting overlay, derived curve, speed strip) stay
    fully inside the plot, like the drag readout. (g) Shrink the
    window until the y-axis title nears a corner: the max/min scale
    numbers vanish instead of overlapping it. (h) The speed-control
    popup shows "Start: ... End: ..." on ONE row, Start first.
    (i) Hovering Resulting / the piece selector / Track Piece
    (or Bridge properties) shows NO tooltip.

120. v18.9 (D179-D180): (a) In his edited file, activate the geo
    section: the force strip's grayed teal/yellow/orange curves now
    JOIN the geo lanes at the seam (they drew body rates before —
    huge false step); hovers on them read the same units. (b) Drag a
    speed control's colored SEGMENT: the whole control moves under
    the cursor (Ctrl snaps, ripple honored); the end diamond still
    resizes; a plain click just selects. (c) Close and reopen the
    strip with +V: it arrives COLLAPSED even if it was tall before.
    (d) Hover the green true-speed curve where it hugs the strip's
    top edge: the "v = ..." readout stays fully inside the strip.
    (e) Enter a Side view: a faint horizontal GROUND LINE marks y=0;
    it tracks pan/zoom. (f) The second dropdown under the view label
    offers Front/Back/Right/Left/Tangent to Selection; each looks
    down the advertised world axis; Shift+T still re-tangents.
    (g) View menu: ONE "FOV" slider — it drives orbit, fly AND POV.
    (h) "Add View" in the view label's list: a second pane appears
    right, divider drags; the new pane orbits/zooms/pans and takes
    Top/Side (with sub-modes + ground line) independently; edits in
    the graph appear in it after every rebuild. "Remove View" drops
    it and the remaining panes reflow. POV/fly/clicking-to-edit
    stay primary-pane-only (deliberate scope).

121. v18.10 (D181): (a) Add View: the new pane is FULLY live —
    orbit, fly (right-click), POV (Space while hovering it), gizmo
    drags, node-marker clicks, comb hovers, its own compass/labels;
    Space toggles POV in whichever pane the mouse is over (or the
    captured one); entering fly in one pane releases a capture in
    another; both panes riding POV show the SAME position (shared).
    Remove View on ANY pane (including the original) removes THAT
    pane; the last pane refuses. The graph collapse arrow always
    sits in the rightmost pane. (b) Top/Side views draw a
    world-aligned GRID overlay (1-2-5 adaptive, majors heavier);
    in Tangent side view the vertical lines measure from the
    selected node's anchored spot and DON'T slide when the node
    moves; heights stay absolute. (c) Picking "Side" from the view
    list = Front; the Tangent view shows two buttons below the
    dropdown - refresh (re-tangent to current selection) and
    reverse (180 deg flip in place, grid unchanged) with
    "Refresh"/"Reverse View" tooltips. (d) The top-right
    "Axes: global/local" button is GONE (View menu dots remain).
    (e) Open any file: no Resulting overlays are checked.
    (f) A near-vertical stretch keeps its resulting world-rate
    graphs live until ~87 deg elevation (was ~81). (g) Expand the
    speed strip with a plain control selected: the control line
    stays put and the scale grows upward; select a complex control:
    the line moves up for below-line room as before.

122. v18.11 (D182): (a) Add View from a pane in any mode: the new
    pane arrives in Fly/Orbit looking the same way as its spawner.
    (b) Two panes riding POV: A in the captured one reverses ONLY
    that pane (its own "Backwards POV" tag; the other keeps its
    direction). (c) View menu reads "Show Heartline" / "Show Node
    Markers". (d) Ortho scroll zoom is twice as fast; zooming IN
    pulls the point under the cursor toward the cursor; zooming out
    recedes from the center. (e) The Top/Side grid is 1 m squares
    stepping x10 as you zoom out (majors every 10th line). (f) With
    a comb readout or the backwards tag visible bottom-left, the
    compass does NOT move - the text just draws over it. (g) Every
    reset arrow (View menu sliders, convert-dialog rows, seam
    settings, shortcut dialog) and the tangent refresh button show
    a ROUND-bottomed drawn circle-arrow, centered; the s/c chip's
    rate marker too. (h) Rapid-click the strip's expand/collapse
    arrow repeatedly: it only toggles - no diamonds or speed
    controls appear. (i) Hovering a VIEW: 1/2/3/4/5/6 jump to
    Front/Back/Right/Left/Top/Tangent (T and Shift+T do nothing
    now); hovering the GRAPH: 1/2/3 switch lanes as ever and 6
    re-tangents the last-hovered view; while captured (fly or
    right-clicked POV) the digits act on the captured pane.

123. v18.12 (D183): (a) Fly into a view, then press 1-5: the gizmos
    arrive correctly sized immediately (no click needed). (b) The
    "Backwards POV" tag shows "(A)" only while right-clicked in.
    (c) In Front view with Global axes: no Z arrow (Top: no Y;
    Right/Left: no X) - the white ball is fully visible; drag it to
    move the piece in the view plane (works in perspective too);
    Ctrl+drag with Global snaps onto whole grid coordinates, with
    Local it steps from the start position; double-click the ball =
    the Track Piece panel (bridge-end ball = bridge properties,
    seam ball = seam settings, as before). The ball grows/shrinks
    with the gizmo; with Show Gizmos off it keeps its old fixed
    size. (d) Reset icons are thinner; the SHORTCUTS dialog's
    binding resets wear them too; the tangent view's reverse button
    matches the refresh button's style. (e) Color swatches: the
    fill sits exactly inside the outline (no bleed below).
    (f) Rapid double-clicks on the strip's expand/collapse arrow
    only toggle - never a control (the double-click path is guarded
    now, not just the press).

124. v18.13 (D184): (a) LATERAL SIGN: a flat RIGHT turn's felt
    lateral reads NEGATIVE everywhere (graphs, hover, stats line,
    popup); authoring +Gl turns LEFT on screen; the lateral comb's
    teeth sit on the SAME side as before the flip; reversal still
    negates laterals; converts/influence/B-matching all line up
    under the new sign (suites re-baselined: chirality, s/c felt,
    influence e, DomainGate 40b). Old files with lateral content
    load MIRRORED - expected. (b) Single-click the control line =
    diamond; QUICK second click = the diamond is replaced by a
    control spanning its neighbors (or section ends), options open
    - same as right-clicking; an old diamond double-clicked keeps
    the historic promote. (c) Double-click ANY part of a gizmo
    (arrows, rings, ball): the entry gizmo opens the Track Piece
    rows even while a bridge section is active; the bridge gizmo
    always opens the bridge rows; the seam gizmo its pop-out; a
    drag never triggers it. (d) In Front/Top/etc. views only the
    IN-PLANE ring builds (yaw in Top; roll/pitch per heading in
    side views) - the white center is clear. (e) The entry ball is
    WHITE, replaces the node-0 marker (no z-fighting sphere pair),
    and scales with the gizmo. (f) Ctrl+dragging an arrow with
    Global axes lands the piece on whole grid coordinates in ALL
    axes; Local still steps from the start.

125. v18.14 (D185): (a) YAW SIGN: authoring a positive geo yaw rate
    turns LEFT on screen (same side as +Gl, D184's pairing
    restored); the resulting yaw and heading-rate graphs/hovers
    read positive through a left turn; a global-axes geo section's
    heading lane turns left for positive values too. (b) Geo->
    force convert of a left turn produces +Gl lanes; force->geo of
    a +Gl section produces +yaw lanes; both round-trip (the two
    negations cancel in the extraction - it reads exactly like the
    pre-D184 code but MEANS reported-left). (c) B/Shift+B across
    force<->geo seams still match value+slope and re-presses stay
    put (matcher entries + exits rewritten to the reported
    contract). (d) Seam spike carets: a yaw-kinked seam's caret
    points the way the flipped yaw graph spikes (the lane-1 caret
    was ALSO owed by D184's felt flip - one negation fixes both
    views). (e) Piece yaw PLACEMENT, strict-seam held angles,
    gizmo rings and the compass are ANGLES, not rates - their
    sense is deliberately unchanged. (f) Old files with geo yaw
    content change shape, and with roll present it is NOT a pure
    mirror (twoSecPiece's flipped twin genuinely stalls - the 48g
    analysis); direction fixtures re-baselined by authoring the
    mirrored value (Phase2 chirality, Phase3 yaw -12, DomainGate
    41c spiral -15), every invariance gate untouched and green.

126. v18.15 (D186): (a) YAW ANGLES: typing +30 into "Piece yaw",
    "Next piece yaw" or a strict seam's yaw row aims LEFT (the
    D185 rate pairing); loading any old file changes NO pose (the
    negation is display-only - stored values and the file keep the
    internal sense, so a pre-flip save shows its yaw numbers
    negated but sits identically); gizmo yaw rings drag exactly as
    before and the spinners re-sync negated after a ring drag; the
    seam row's live (continuous) readout, Discontinuous seed, and
    both resets stay self-consistent. The s/c arc angle keeps its
    own rider-right + direction-arrow spec (deliberate). (b) The
    white center ball holds the SAME proportion to its gizmo at
    every camera distance, perspective and ortho (was: historic
    world size far away - invisible); gizmos hidden = the old
    fixed-size marker. (c) In Top/Front/Back/Right/Left the
    overlay grid lines sit ON the 3D ground grid's lines (X, Z and
    height), and a Global Ctrl-snapped gizmo drag lands the piece
    exactly on overlay lines; the Tangent side view still anchors
    its horizontal grid at the selected node.

127. v18.16 (D187): (a) GIZMO REVEAL: only the white balls show at
    first; clicking one reveals ITS rig (entry/seam ball = the
    piece/seam gizmo, bridge-end ball = the bridge gizmo), clicking
    the track/background or another piece hides it, clicking the
    same ball again toggles it off. The revealed gizmo draws
    THROUGH the track and ground; the top-right Local/Global
    button appears only while revealed, and every program launch
    starts Global. The View menu has no gizmo row anymore. Ball
    drag/double-click/node-0 select unchanged; POV still hides
    everything. (b) VIEW LIST: the view label's list is flat -
    Fly/Orbit, POV, Top, Front, Back, Right, Left, Tangent to
    Selection (no Side sub-dropdown); the label wears the specific
    name (Tangent shows the rotate/reverse tools right below);
    digits 1-6 unchanged. (c) S/C ARC: a +90 flat arc turns LEFT
    (chip arrow agrees); dir 90 still pulls UP; negative mirrors;
    old files with flat arcs load mirrored. Phase2's s/c direction
    fixtures author -90 for right turns now; all suites green.

128. v18.17 (D188): (a) The gizmo looks EXACTLY like it always did
    (arrows pass through the ring correctly, no inside-out
    shading) while still drawing through track/ground - it renders
    in its own depth-buffered overlay SubScene; orbit/fly/ortho/
    zoom all keep the overlay glued to the main view (mirrored
    camera per frame). Clicks pass through empty overlay regions:
    track activation, node markers, click-off-to-hide all work
    while a rig is shown. (b) The un-revealed white ball is FIXED
    size at any distance; click reveals, drag moves the owner AND
    reveals mid-drag; the revealed rig's own center ball scales
    with the rig, sits correctly among the arrows (not hidden
    behind them), click hides, drag moves, double-click opens
    settings. (c) Toggling any pitch/roll padlock (Placement rows
    or bridge panel) adds/removes that ring INSTANTLY. (d)
    Clicking any 3D node marker selects THAT node in the graph
    (was: the previous one whenever the entry ball replaced node
    0's marker - the pick returned list position as node index).

129. v18.18 (D189): (a) FLY over a revealed gizmo: the cursor stays
    hidden and the look stays smooth even when the screen center
    crosses gizmo geometry (the overlay is mouse-transparent in
    fly/POV); exiting fly restores gizmo clicks. (b) While a rig
    is shown, its fixed track ball is HIDDEN (the scaling center
    ball is the white); the other handle ball (e.g. bridge end)
    stays visible; reveal-by-drag keeps the dragged ball until
    release. (c) With "Gizmos: Local", the rotation rings aim
    along the piece's own frame - roll/yaw/pitch rings tilt with
    the object, and dragging a ring rotates the WHOLE rig rigidly
    with it (NL2 look); Global keeps the world/Euler aiming. Ring
    writes are unchanged (one Euler quantity each, 1 degree Ctrl
    snap, one undo per drag). (d) The top-right button reads
    "Gizmos: Local" / "Gizmos: Global".

130. v18.19 (D190): (a) TRUE LOCAL ROTATION: with Gizmos: Local on
    a tilted piece, dragging ANY ring turns the track exactly
    about the drawn axis (watch the other rings/arrows ride along
    rigidly); with an axis locked the object follows as closely as
    the remaining deltas allow; Global drags are bit-identical to
    before. DecompProbe (scratchpad) gates the math: in-range
    inversion 1.3e-12 deg, ring loop <= 1.1e-5 deg at poles. (b)
    Pressing A with the cursor over any 3D view does NOT append
    (over the graph it still does). (c) With a node selected, a
    clean click on empty viewport space or bare track deselects it
    (marker clicks still select; gizmo clicks don't deselect). (d)
    Double-clicking a white ball or any rig part opens its
    settings panel NEXT TO the click; the toolbar "Track Piece"
    button and the seam line keep their old panel positions.

131. v18.20 (D191): (a) Drag a piece with any gizmo part and
    release: the graph strips (derived curves, true strokes, the
    speed-conflict overlays) repaint IMMEDIATELY - no hover
    needed. (b) Every numeric box in the app accepts typed input:
    the Track Piece x/y/z/yaw rows, Coaster-menu heart/gauge/tie/
    friction rows, start speed - and on EVERY spinner, typing a
    value then clicking elsewhere COMMITS it (was: silently
    reverted unless you pressed ENTER); garbage text falls back
    to the old value. (c) No spinner clips its digits (the Piece
    yaw "3" screenshot - narrow boxes widened to 82 px). (d) The
    reverse button reads "Reverse piece...".

132. v18.21 (D192): clear ANY spinner box completely, then click
    off OR press Enter: the value commits to ZERO (clamped into
    range - gauge lands at 0.3, bridge length at 1) and the box
    shows it; typing garbage like a lone "-" restores the old
    value. The low-speed stairstep report is NOT reproducible
    headlessly (SpeedStairProbe: all speed arrays smooth to the
    honest 0.0 stall park) - regression-check speed curves around
    a stall against that probe before touching speed paths.

133. v18.22 (D193): load a file whose piece is TILTED (pitch
    delta) so design and true speeds split hard, and ride POV
    toward the true stall: the Speed readout in the stats line
    glides continuously (no 1.99 -> 1.67 -> 1.26 steps) and the
    ride slow-down FEELS continuous - both interpolate at the
    exact ride time now instead of reading the nearest design-
    spaced sample. The stall wrap, the 0.1 crawl floor, the
    graphs and all recorded arrays are bitwise-unchanged
    (StairFileProbe is the reference for the arrays).

134. v18.23 (D194): ride POV into a stall (the Speed Stairstepping
    file): the moment the Speed readout reaches 0.000 the camera
    wraps - to the ride start (or past the stalled stretch) with
    NO frozen crawl first; entering POV parked exactly on a stall
    start wraps the same way. Riding BACKWARD still parks at the
    stall's first sample and rides out freely.

135. v18.24 (D196): make a lane hold early (e.g. a geo section
    whose yaw lane's last node sits mid-section), split the
    section past that node (right-click a later node of another
    lane > Split section here), save, reload: the file OPENS
    (pre-fix: "Load failed: each lane needs at least 2 nodes" -
    the split's right half holds ONE node in the short lane,
    which the editor and saver always accepted). Re-saving
    changes nothing (byte-identical). Headless: LoadGate196.

136. v18.25 (D197): DETERMINISM on a long chained layout (the
    FULL TEST COASTER class: 15+ sections, friction, speed
    controls, time-domain tail). Open it, note the shape; click
    every section, spam a piece X/Y/Z/yaw spinner up and back
    down, re-select pieces: the track must NEVER change shape
    from interaction alone (pre-fix each rebuild advanced a
    half-finished speed-reconcile cascade 3 passes and moved
    the track tens of meters). Save under a new name, reopen:
    identical shape (pre-fix ~0.5 m of %.4f quantization
    drift). A file saved by an OLD build settles ONCE on first
    open (the honest fixed point), then never again. Headless:
    DetermGate197 (6 checks).

137. v18.26 (D198): BIT-EXACT reload, stall knot included. Make a
    layout stall mid-ride (ghost track + a tiny high-curvature
    knot where the design speed hits the 0.1 floor), save, quit,
    reopen: the ENTIRE track - knot scribble included - must be
    pixel-identical, and clicking around must not move it
    (pre-fix: the loader clamped the knot section's saved 0.1
    design speed to 1.0 and the 10-decimal node rounding flipped
    a grazing-stall knife edge - the reload diverged exactly at
    the knot). Piece X/Z moves and pitch/roll tilts stay exactly
    rigid; Y/yaw moves on a STALLED layout still re-roll the
    knot deterministically (documented, canonical-frame fix
    pending user verdict). Headless: DetermGate198 (4 checks,
    incl. a pinned live-knot bitwise round-trip).

138. v18.28 (D200): the HAND-REVERT test — note an early node's
    exact value (double-click dialog), type something else, click
    away, then retype the ORIGINAL value exactly: the whole track
    (Resulting curves included) must return pixel-identical, and
    a save right after must byte-match a save from before the
    excursion. Drags/nudge-glides must FEEL unchanged (the exact
    settle runs on release, not per event) — a brief pause on
    releasing a big early-chain edit on a long layout is the
    settle and is expected; spinner arrow-HOLDS on friction/start
    speed may lag on very long docs (accepted, D200). Headless:
    DetermGate200 (7 checks: bitwise hand-revert on the plain
    file AND through a pinned chaotic knot, settled idempotence)
    + Nl2Probe (20 checks: NL2 CSV export verified on the full
    layout with friction — spacing, frames, handedness, D40
    Z-negation both ends).

139. v18.29 (D201): File > Export... — the dialog opens with
    Format radios (Spline (CSV) | NL2 Element), Track points
    radios, spacing; NO explanation paragraph; picks persist
    across runs. Export an .nl2elem and INSERT it in the NL2
    editor (right-click a track vertex > insert element): the
    element must arrive heading away from the insert point,
    right-side-up, banked the same way as in the designer, and
    at the designer's scale (the in-NL2 insert is the one step
    headless checks can't cover). Spline (CSV) must behave
    exactly as before. Headless: Nl2ElemProbe (21 checks:
    DOM-parsed FVD++ structure, vertices == the verified CSV
    under the rigid anchor transform, heights/chords/strict
    flags, roll coords k/nSeg, up/right frames, the up=+Y
    right=+X handedness witness at the flat start).

140. v18.30 (D202): export JITTER — export the full test coaster
    as .nl2elem at 0.2 m and ride/inspect it in NL2 around the
    fast banked stretch (~935 m): the high-frequency shimmy is
    GONE (was ~0.14 g of 18 Hz curvature ripple from the old
    lerp resampling's chord-sag aliasing). Any remaining
    roughness there is authored content (e.g. the abs-bank
    section's 0.3676 s node carries a real bank-slope kink at
    ~367 m — visible in the designer's combs too). Headless:
    Nl2ElemProbe's jitter bound (rms < 0.0035, @935 < 0.002 —
    lerp measured 0.0053/0.0051) + both export probes on the
    shared resampler (42 checks).

141. v18.31 (D203): the smoothed CONFLICTED strict seam — on the
    full test coaster (or any ≠v strict seam with smoothing on),
    watch the TRUE curves (thin strokes / Resulting overlays /
    combs) through the seam: NO instantaneous jump (pre-fix the
    true felt stepped ~2 g at any smoothing length — the blend
    extrapolated per-INDEX across design-spaced samples), and
    WIDENING the smoothing diamond now genuinely smooths further
    (10 m -> 0.31 g residual, 30 m -> 0.075 g on the test file).
    The DESIGN curves still step at the seam (the honest ≠v
    display, unchanged) and smoothing OFF keeps the hard kink.
    Headless: SeamJumpProbe (4 checks).

138. v18.27 (D199): CANONICAL-FRAME placement rigidity. On a
    layout with a live stall knot (pin its floor design speed or
    author one): move the piece in X, Y, Z and yaw - the track
    must move/rotate as a PERFECTLY rigid body every time (no
    knot re-roll, no downstream swing, no ghost-track change,
    design speeds untouched), and moving it back restores the
    original exactly. Split a piece at a section boundary: the
    track must not change at all (D71), including near the knot,
    and the split file must reopen identically. Expected limits:
    a piece behind a BRIDGE still re-solves (live-follow), a
    held-angle strict follower still re-aims under upstream yaw,
    and the seam smoothing window re-blends with it. Headless:
    DetermGate199 (10 checks); Phase2Gate's two pass-through
    contracts re-baselined to compile == placed-direct-at-origin.

142. v18.32-v18.37 (D204-D209): the shakedown feedback round.
    (a) File > New: piece at 5 m, start speed 5 m/s (startup demo
    keeps 21/26); (b) any Convert/preview window opens with
    "Optimal placement" checked, every time; (c) strict-seam
    settings rows wear a 🔒/🔓 padlock (red outline while locked)
    instead of "Discontinuous" — behavior identical; (d) new
    project → Convert to geo (distance, °/m, both axes global) →
    drag the entry ball/arrows: the TRACK follows (pre-fix only
    the gizmo moved — stale piece object after the preview's
    applyDocState; also try: undo a node edit, then drag the
    ball); (e) switch lanes by clicking a dimmed node, then
    immediately click-DRAG that node: no node dialog on release
    (a stationary double-click still opens it); (f) at every
    section seam the Resulting overlays / derived strips / true
    strokes now run right up to the boundary and break ON it —
    no blank band; hover slopes near seams stay sane; a sharp
    strict seam shows NO rate spike (the carets are the jump's
    witness); (g) collapse the graph panel on a big zoomed-out
    layout: interaction lag from the plot disappears (expand
    repaints correctly); zoomed-out drawing is also decimated
    per pixel column — verify curves look identical at every
    zoom; (h) influence-edit a section with a neighbor and press
    B / Shift+B on a delta EDGE node: the dotted RESULTING curves
    line up with the neighbor (value + slope; re-press converges;
    with no neighbor on that side it still zeroes). Headless:
    GizmoProbe (32 convert combos × rigid moves), InflMatchProbe
    (3 checks), Phase3Gate 50f RE-BASELINED (span-clean rates
    show no smeared seam spike — the old > 300 °/s expectation
    WAS the artifact), all suites + DetermGates 197-200 green.

143. v18.38-v18.45 (D210-D217): shakedown round 2.
    (a) File > New: 5 s starter section, camera framing it the same
    way every time; save a file, orbit somewhere else, reopen — the
    view returns to where it was saved (old files: view stays put);
    (b) ride POV: the stats line shows Pitch/Yaw/Roll angles +
    °/s rates (yaw left-positive; vertical track shows —);
    (c) blank ANY spinner (piece x/y/z, seam angles, speed-control
    boxes, friction) then click anywhere — same panel, main window,
    or another app: it commits 0 (clamped; the snap-step spinner
    restores its value instead); (d) convert a rolling section to
    absolute roll WITH °/m rate units: the banking survives (was
    flattened to ~angle/v); (e) geo section + Resulting roll
    overlay: the G axis stays honest (the roll curve fits its own
    scale, title says so); (f) drag a complex control's LAST node:
    the control end follows, earlier nodes stay put (the end
    diamond still stretches); the drag-create length readout is
    readable on the collapsed strip; (g) hand-damage a .fvd line
    (or truncate the tail): the file OPENS with a warning count and
    marks dirty; a header-only file refuses cleanly; (h) ACCEL
    control: untick Tied — set decel ≠ accel and watch the brake
    ramp change while the launch side doesn't; check Complex: two
    graphs, the Edit buttons switch which one shows (dimmed twin
    behind), untying seeds decel = accel. Headless: R2Probe (14),
    probeAbsRoll sweep (28 combos at fit residual), full battery +
    DetermGates green. GATE-INFRA: DetermGate198/199/200 build
    their own knot now (KnotFixture — the on-disk fixture was
    re-saved knotless 2026-08-04); LoadGate196 check (c)
    re-baselined to the D216 heal.

144. v18.46-v18.47 (D218-D219): round-3 polish. (a) Add View on a
    big layout: the new pane opens centered on the WHOLE coaster
    with the floor grid visible immediately (pre-fix: spawner's
    pose, grid parked at mid-track — often no floor until the
    first move); also load a file whose saved camera is far from
    the track start — the grid must be under the camera right
    away. (b) ACCEL control popup: Acceleration · 🔗 · Deceleration
    on one line, always visible; linked (default, lit chain) =
    editing either spinner changes both; unlink (dimmed chain) =
    independent; hover the chain = "Link Acceleration and
    Deceleration". Complex: the two ▸ Edit buttons sit in the same
    layout — linked, both open the one shared graph (both wear ▸);
    unlinked, each picks its own. Re-linking snaps decel onto
    accel. Headless: full battery green (tied stays the bitwise
    one-magnitude path).

145. v18.48 (D220): round-4 polish. (a) startup, File > New and
    Add View all frame the coaster the same way, noticeably closer
    than before (0.8× fit); (b) combs are 1.5× denser and read
    ~1.4× bigger at the slider's 100% (a stored 140% renders
    bigger still — ↺ returns to the new default look); EVERY comb
    hover readout and the POV stats angles/rates read THREE
    decimals, and the radius wears a plain "r=" (it is the exact
    reciprocal of the °/m value); (c) the 🔗 chain
    is a small bright-yellow-when-linked button with a black glyph
    and no outline; while linked the Deceleration spinner/button
    is grayed but the spinner visibly mirrors Acceleration edits;
    (d) new file, tall speed strip: the green dotted true-speed
    curve is fully bright even where it runs along a gridline
    (the strip layered it UNDER the axis grid before — root cause
    of the "dim around 5 m/s" report).

146. v19.0 (D221+D222): "LOCK TRACK SHAPE" — the two-tier
    design/true orientation. (a) Track Piece panel: "Lock Track
    Shape" CHECKED by default (tooltip "Design orientation does
    not change") — unlock the pitch padlock and spin pitch: the
    track TILTS RIGIDLY, "≠v" appears, a grey "Design
    orientation: pitch …° roll …°" line shows under the spinners,
    and the button pair "Design → Real Orientation" / "Real →
    Design Orientation" appears on one line. Real→Design
    un-rotates the piece (and its carried chain) back — shape
    kept, conflict gone; Design→Real keeps the orientation and
    RE-SOLVES the shape at the real frame. (b) UNCHECK the box:
    nothing moves. Spin pitch now: the shape re-solves and BOTH
    orientations move together (the readout stays at a constant
    offset if a conflict predates the uncheck — the D222
    invariance: the same edits after unlocking produce the same
    shaping whether or not a conflict rides on top). Re-check:
    nothing moves, edits tilt rigidly again. The toggle is one
    undo step and never reshapes by itself. (c) RIGID carry:
    chain pieces with a manual bridge and/or strict seam; with
    the first piece's box CHECKED, tilt/move/yaw it — bridge,
    seam and every following piece ride along as one rigid body
    (each shows its own conflict); UNCHECKED = natural updates
    (bridge re-solves, followers re-aim, historic). (d) Strict
    seam + bridge panels carry NO lock-shape controls (D222 —
    their orientation rows work exactly as always); a follower's
    Track Piece panel hides them too. (e) Old files load checked
    + no split = exactly the old behavior; save→reload keeps the
    tiers bit-exactly (`porient locked pd rd`). (f) FLY-KEYS
    BUG FIX: open the strict-seam settings, click a spinner,
    right-click a 3D view → fly mode AND W/A/S/D movement work
    (the popup was the one editor window missing the D166 key
    forwarder). Headless: OrientGate (23 checks) + the full
    DetermGate/Phase2/Phase3/Domain/Load suite.

147. v19.1 (D223-D226): batch 15. (a) Hover a section BADGE:
    it brightens + hand cursor; click behavior unchanged. (b)
    File > Export on a multi-piece doc: a "Track piece" dropdown
    (Whole track / Piece k/n); export a piece → the file holds
    only that piece's spline, from its entry. (c) ABSOLUTE
    boxes: split a piece at a pitched/banked point — the new
    piece's Track Piece boxes read the REAL heading/elevation/
    bank of the boundary (yaw + = left) and NOTHING moves;
    split a TILTED (locked-conflict) piece — still nothing
    moves, the new piece shows its own conflict + design readout;
    typing into a split piece's pitch box tilts it about the
    same physical axis it always did. (d) VERTICAL PASS-THROUGH:
    reveal a piece gizmo (Global), drag the pitch ring past
    straight up — the track keeps rotating smoothly, and the
    boxes flip (heading +180, elevation mirrored, bank +180) the
    way NoLimits shows it; Local rings near vertical no longer
    wobble. Pitch spinners now allow ±90. (e) LIVE node editor:
    double-click a node — no Apply; type / ▲▼ / wheel (Shift
    ×10, Ctrl ×0.1) any box and the track updates at once; blank
    a box → nothing changes until it parses; Ctrl+Z (main
    window) undoes each edit, a typing burst as one; Ctrl+Z with
    the dialog open then editing again still works (index-
    resolved). (f) START SCREEN: File > New → the graph area
    shows two tiles, the 3D view shows NO track; pick one → the
    track appears and the form shows; change any radio/spinner →
    live; "Start designing" → normal editor, title clean, empty
    history; "Skip" → the classic 5 s starter. ⊕ > "Add floating
    section…" → same screen minus friction, a new detached piece
    5 m past the active piece's end; "Add piece" keeps it, Ctrl+Z
    removes it in one step; Cancel un-creates it. Headless:
    OrientGate2 (18) + OrientGate (23) + the recovered battery
    (all in repo `gates/`). Dev hook: `java ... -Dfvd.devshot=DIR
    coaster.FvdDesigner` writes start-pick/start-lane/start-sc-
    floating PNGs.

148. v19.2 (D227-D231): batch 16. (a) UNDO: open Track Piece ▾,
    tilt/lock/unlock/resolve, press Ctrl+Z / Ctrl+Shift+Z WITH the
    panel open — each step reverts, the spinners and the Lock
    check follow; close it, Ctrl+Z again — every panel edit
    reverts one by one, none skipped; same in the node editor
    (boxes re-read after each undo), the strict-seam pop-out
    (rebuilds in place), the speed-control popup, the Coaster
    menu (heart/gauge/friction). Cosmetic menus (View, Snapping,
    colors, Export) keep their local Ctrl+Z. (b) Node editor:
    handle rows show LENGTH + SLOPE (one slope box on a smooth
    node, one per handle when broken); typing a slope re-aims the
    handle keeping its length. (c) Piece labels everywhere read
    "Piece n — 123.4 m"; the ground shows a thick green X and red
    Z axis through the origin; ⊕ menu order Attach → Add floating.
    (d) MULTI-COASTER: Coaster ▸ New coaster… builds a second one
    (start screen); the list at the menu top switches (title
    shows [name]); the inactive coaster renders in its solid
    color; Rename/Delete work and undo; delete all → the graph
    shows "No coaster in this file", nothing throws, Ctrl+Z brings
    one back; Save = FVDD4 (or FVDD3 with a name line for one),
    reload identical; Export gets a Coaster dropdown. (e) START
    SCREEN: File > New → empty doc + wizard: Placement | Coaster
    (name, speed, friction) | Section Type tiles; "Start
    designing" without a type → red "Select the Section Type
    First" + flashing tiles; pick a type → its options appear
    under the tiles, the track appears; every change is a Ctrl+Z
    step; Ctrl+Z right after ⊕ > Add floating section… removes
    the new piece and closes the panel; select another piece
    while the wizard is up → normal graphs; re-select → panel
    back as left; the graph pane can't be dragged below the
    panel's height while it shows. Headless: MultiCoasterGate
    (15) + the whole battery.

149. v19.3 (D232): batch 17. (a) START SCREEN: tiles read
    "Force/Geometric" (first, pre-selected — the track shows
    immediately) and "Straight / Curve", no grey descriptions;
    the lane options sit in two columns; "Start designing" always
    works. (b) COASTER MENU: selector dropdown at top, then
    Coaster Options… (rename + friction + statistics), New
    Coaster…, Delete Coaster (asks "Are you sure you want to
    delete "NAME"?"), then Rail Settings… (rail color + G-Force
    Colors… + heartline + gauge + tie spacing); everything
    left-aligned; Ctrl+Z inside both dialogs is the global one.
    (c) SEAMS: the strict-seam settings are DARK with light text;
    clicking the smoothing ◇ opens the full seam MENU;
    "Un-strict seam" has no parenthetical. (d) Ctrl-hover over a
    curve leads with Δt from the previous node. (e) The green/red
    ground axes no longer flash far away (camera-faded segments).
    (f) S/C ENTRY EASE: edit element 0 → "Entry roll ease (m)" —
    a global-bank first element's bank arrives over that length
    (0 = the classic 2 m; 0.5 m comes in visibly earlier; 5 m
    later). (g) MAKE SMOOTHING REAL: on a smoothed strict seam,
    seam menu > "Make smoothing real…" → density preview → Apply:
    the blend window becomes an authored section of the upstream
    type, a fresh strict seam sits at the window's end, the track
    holds (mm-class), Ctrl+Z restores exactly; refuses with a
    stats-bar reason on s/c edges, speed controls, stalls in the
    window. "Enable smoothing" grays on an s/c edge. Headless:
    SmoothRealGate (11) + the whole battery.

150. v19.4 (D233): batch 18. (a) Seam padlocks: the red outline
    hugs the button exactly (the shared outlineStyle pattern —
    use it for every future outlined button). (b) Coaster menu:
    the "Coaster: NAME" submenu lists and switches coasters (the
    ChoiceBox never opened — replaced). (c) Wizard: "New Coaster"
    / "New Track Piece" titles; the options grid's radio dots
    line up per column. (d) Hover a resulting/derived curve
    inside a smoothing window at high zoom: the dot and value
    follow the curve smoothly (interpolated, no more sample
    stairs). (e) s/c ROLL smoothness: a global-bank element's
    ease and a RATE-roll boundary blend leave/land with zero
    roll-rate slope (quintic — no jolt at either end; pitch/yaw
    blends unchanged). (f) ENTRY EASE: element 0's ease is the
    typed value exactly — 0 = instant bank snap; new sections
    default 2 m; the s/c strip has an entry DIAMOND + band (drag
    = absolute length, Ctrl snaps, < 0.2 snaps to 0, capped at
    element 0). (g) Make smoothing real: a speed control OUTSIDE
    the window no longer refuses (it carries onto the kept
    half); INSIDE still refuses with a reason. Headless:
    SmoothRealGate (12) + the whole battery.

151. v19.5 (D234): batch 19. (a) COASTER MENU: pressing "Coaster"
    opens the popup (the real bug: the menu was EMPTY until
    onShown, and an empty Menu never opens — populate at
    construction + onShowing rebuild); the "Coaster: [ChoiceBox]"
    row is back — its dropdown opens inside the menu popup,
    picking a coaster switches and closes the menu
    (Robot-verified end to end via -Dfvd.devmenu=1; the D233
    "ChoiceBox never opens in a menu" lesson was false).
    (b) INFLUENCE SPLITS: right-click a design-surface node of an
    influenced section → "Split section here" / "Split track
    piece here" both work; both halves stay influenced (deltas
    cut with the lanes, flags carried). Deltas zero before the
    cut = the track stays put (normal seam class); deltas BEFORE
    the cut = the tail re-solves proportionally (accepted corner
    — ~1.8 m per 1 °/s-nudge prefix on the gate's hill; Ctrl+Z
    exact). The influence surface still redirects to the design
    lanes. (c) MAKE SMOOTHING REAL + influence: an influenced
    seam section with deltas ZERO across the window converts (the
    kept halves stay influenced); non-zero deltas inside the
    window refuse with a stats-bar reason. (d) S/C ENTRY EASE
    BAND EDGE: the shaded band's far edge drags the ease exactly
    like the diamond (H_RESIZE cursor on hover). (e) RESOLVE
    RENAMES: the conflict submenu reads "Convert Real to Design
    (keep track shape)…" / "Set Design to Real (keep graphs,
    reshape)"; the initial-speed dialog's button says "Set Design
    to Real". Headless: InflSplitGate (13, NEW) + SmoothRealGate
    (15) + the whole battery.

## Debugging heuristics learned the hard way

- Wild 3D output after math edits → check the frame-rotation sign (`−ωU·R`)
  and the Y-flip discipline before anything else.
- "Track dives underground / loops unexpectedly" is usually a LAYOUT problem
  (curvature ∝ 1/v² runaway), not an integrator bug — reproduce headlessly
  and print pitch over time before touching physics code.
- Parameter scans that respond non-monotonically mean the track is passing
  through vertical; shrink the phase durations and re-bisect.
- If numbers parse/format weirdly for a European user: a missing `Locale.US`.
