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
    BridgeBump gained the onset pin).** The march blend's upgrade
    from cubic to QUINTIC Hermite (zero end second-derivative)
    softens how hard the rate graphs bend right after a seam: onset
    ratio (peak |rate slope| in the outer 8% of the span over the
    overall peak) 0.11 on the turn→straight fixture vs ~0.4 with
    the cubic, gated < 0.3; overshoot stays x1.00 and EVERY prior
    bridge gate passes unchanged (constant/zero seam states reduce
    the quintic to the same constant blend — floor collapse, mode
    ping-pong purity 56.000 → 56.000 m, zero length-round-trip
    drift). The three new Options settings (tangent sweep ×, length
    tolerance %, slope weight) enter the core as PARAMETERS with the
    old signatures kept as default-overloads, so all probes/gates
    run the same defaults byte-stably; the settings shape optimize
    ACTIONS only and the results are stored in the .fvd (files
    compile identically anywhere). Passed 2026-07-08.

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

## Debugging heuristics learned the hard way

- Wild 3D output after math edits → check the frame-rotation sign (`−ωU·R`)
  and the Y-flip discipline before anything else.
- "Track dives underground / loops unexpectedly" is usually a LAYOUT problem
  (curvature ∝ 1/v² runaway), not an integrator bug — reproduce headlessly
  and print pitch over time before touching physics code.
- Parameter scans that respond non-monotonically mean the track is passing
  through vertical; shrink the phase durations and re-bisect.
- If numbers parse/format weirdly for a European user: a missing `Locale.US`.
