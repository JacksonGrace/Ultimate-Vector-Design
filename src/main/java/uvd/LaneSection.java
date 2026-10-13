package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * Common base of the LANE-DRIVEN section modes (Phase 2, SECTIONS-DESIGN
 * §3): three Bezier lanes of control values over section-local ride
 * time, integrated into geometry at the section's DESIGN speed by
 * TrackCompiler.passG. What the lanes MEAN is the subclass's business
 * (force targets vs body rates); everything the graph editor touches —
 * the lane lists, vDesign, reversed — lives here so it can edit any
 * lane section through one type. Pure Java; public mutable fields in
 * the project's Node style (the editor edits lanes in place).
 *
 * Phase-2 modes that are NOT three-lane (bridge, straight/curve,
 * external) implement Section directly; the editor grows a real
 * per-mode surface when they land.
 */
public sealed abstract class LaneSection implements Section
        permits ForceSection, GeoSection {

    /** Lanes 0/1/2 — subclass defines the semantics. The list OBJECTS
     *  are long-lived: the editor holds references. */
    public final List<List<BezierBaker.Node>> lanes;

    /** PRISTINE-LANE END FOLLOW (v19.19, D248 — user spec): per-lane
     *  "not edited since creation" flags. A hold lane is created with
     *  the flag SET (continuation / holdSection / the '+' and Insert
     *  holds / the start wizard / split halves inherit); the editor
     *  clears a lane's flag at its first edit ({@link #touchLane}) and,
     *  after any gesture that moves a lane's last node, moves every
     *  still-following lane's last node onto the new section end
     *  ({@link #syncFollowingEnds}) — so dragging one lane's end drags
     *  the untouched lanes with it, growing AND shrinking the section;
     *  the redraw-time belt ({@link #clearStaleFollows}) only CLEARS a
     *  follower that stopped being hold-shaped, never moves one.
     *  Persisted as the `lane` line's 3rd field (missing = derived by
     *  {@link #pristineHold} AT the section end at load,
     *  DesignIO.finishCoaster — and only while the defining lanes end
     *  there too, the sync's fixed point: a hold short of the end or a
     *  sole longest hold stays off, so an old file opens bitwise); rides
     *  DocState.SecState and Coaster.copySection. Delta lanes carry no
     *  flag. Feeds no geometry — the follow WRITE is an editor action,
     *  so compile/rebuild stay idempotent (D197-D200). */
    public final boolean[] endFollows = new boolean[3];

    public double vDesign;
    public boolean reversed = false;
    public boolean vPinned = false;   // v14.5 D97: reconcile-exempt
    /** v19.27 D256: the reconcile lands vDesign on the REAL seam speed
     *  (see Section.vRealAnchored). */
    public boolean vRealAnchored = false;
    public String name = "";

    /** Authoring DOMAIN (v16.0, D103): TIME = lane x is seconds (the
     *  classic model), DISTANCE = lane x is METERS of track. The lane
     *  VALUES keep their units either way (G / °/s) — the domain only
     *  re-parameterizes the x-axis, which is what lets the exact node
     *  map convert between them without touching values. */
    public Section.Domain domain = Section.Domain.TIME;

    /** Cross-domain view flag (v16.0) — see Section.viewOtherDomain. */
    public boolean viewOtherDomain = false;

    /** CROSS-RATE view flag (v16.7, D119): the strip DISPLAYS its
     *  rate lanes in the other units (°/s ↔ °/m, warped through the
     *  compiled speed profile) while the native lanes stay put —
     *  editing writes back through the warp. Session state like the
     *  domain view (never saved; auto-drops on leaving the section).
     */
    public boolean viewOtherRate = false;

    public boolean viewOtherRate() { return viewOtherRate; }

    /** GLOBAL-AXIS authoring (v16.1, D107 — user spec 2026-07-12).
     *  rollGlobal: the roll lane's rate integrates to a BANK TARGET vs
     *  the HORIZON, enforced by the s/c GLOBAL projection after every
     *  step — rate 0 HOLDS the bank through pitched turns (an upward
     *  turn spirals level instead of tilting into an inclined loop);
     *  the roll term leaves Ω. yawPitchGlobal (geo sections; the two
     *  toggle together per user): lanes 0/1 rotate about the WORLD
     *  vertical / the heading's horizontal right — heading and
     *  elevation rates — instead of the body frame. Defaults false =
     *  the historic local behavior, bitwise. */
    public boolean rollGlobal = false;
    public boolean yawPitchGlobal = false;

    /** Any global-axis flag set? Such lanes are NOT body rates: the
     *  editor's analytic matching and authored-strip drawing fall
     *  back to the compiled curves. */
    public boolean globalAxes() { return rollGlobal || yawPitchGlobal; }

    /** Does lane `lane`'s AUTHORED value deviate from the compiled
     *  quantity under a RIGID orientation tilt of its piece (v19.19,
     *  D248 — the true-stroke split reference, per lane)? A tilt
     *  (TrackCompiler.tiltLine: a free piece's design→true conflict
     *  or a follower's held-seam tilt) re-projects the recorded FORCE
     *  felt (Gv/Gl — gravity's components move) and changes every
     *  WORLD-axis quantity (bank-vs-horizon rate — including a
     *  yaw-pitch-global geo section's "local" roll lane, which IS a
     *  bank rate, D177 —, heading/elevation rates, absolute bank /
     *  elevation / heading angles); GEO LOCAL body rates (pitch/yaw/
     *  roll rates) are rotation-invariant and stay equal to the
     *  authored lane. A FORCE section deviates on ALL THREE lanes
     *  (D248 brief decision: its local roll RATE lane is rotation-
     *  invariant too, but the whole force section keeps the historic
     *  D100 authored reference — zero behavior change on the
     *  true-tier path). The editor takes the AUTHORED lane as the
     *  true stroke's reference exactly on the deviating lanes of an
     *  orientation-conflicted piece; the others keep the compiled
     *  (derived) reference. */
    public static boolean tiltDeviates(LaneSection ls, int lane) {
        boolean geo = ls instanceof GeoSection;
        if (!geo) return true;
        if (lane == 2) return ls.rollGlobal || ls.yawPitchGlobal;
        return ls.yawPitchGlobal;
    }

    /** Any lane of `ls` deviates under a rigid tilt (D248 — the
     *  inactive-strip rule: such a strip on a conflicted piece draws
     *  DERIVED, the truth, instead of authored lanes the ride doesn't
     *  follow; a local-rate geo strip keeps its authored lanes). */
    public static boolean tiltDeviatesAny(LaneSection ls) {
        return tiltDeviates(ls, 0) || tiltDeviates(ls, 1)
            || tiltDeviates(ls, 2);
    }

    /** PER-METER rate units (v16.6, D115 — user spec): the RATE
     *  lanes' values are °/m instead of °/s — geo pitch/yaw/roll, and
     *  a force section's roll lane (Gv/Gl are G targets; they carry
     *  no time denominator to swap, so the flag doesn't touch them).
     *  The integrators consume value × v at every stage, so a
     *  per-meter profile puts the same angles into the same meters
     *  of track whatever the speed. Orthogonal to the DOMAIN (which
     *  re-parameterizes the x-axis): domain says where a value
     *  sits, rate units say what the value means. false = the
     *  historic °/s, bitwise. */
    public boolean ratePerMeter = false;

    /** ABSOLUTE ROLL (v16.7, D118 — user spec): the roll lane
     *  authors the BANK ANGLE vs the horizon (°) directly instead of
     *  a rate — a lane value of 50 means the track banks 50° there,
     *  0 means level, whatever came before. GLOBAL roll only (the
     *  angle IS the projection's target; local roll has no absolute
     *  meaning) — the UI auto-reverts to rate when roll goes local.
     *  The rate-units flag doesn't apply to an absolute lane
     *  (degrees are degrees); pitch/yaw keep theirs. Influence
     *  refuses absolute-roll sections for now. */
    public boolean rollAbsolute = false;

    /** ABSOLUTE yaw/pitch (v19.8, D237 — the rollAbsolute twin for
     *  lanes 0/1, GEO sections with GLOBAL yaw/pitch only): lane 0
     *  holds the ELEVATION angle (°), lane 1 the HEADING (°,
     *  reported-left, unwrapped past ±180) — the integration
     *  projects onto the lane values with slope feed-forward.
     *  Section-line field 10; missing = rate. */
    public boolean ypAbsolute = false;

    /** INFLUENCE axis semantics (v16.5, D112 — user spec): how the
     *  two-pass integrates base + delta AS the other mode. A force
     *  section's geo influence picks roll and yaw/pitch axes; a geo
     *  section's force influence picks the roll axis (the force
     *  integrator's only choice). Independent of the section's OWN
     *  flags — the base rows convert between representations
     *  analytically (worldRates + the transport term), so any
     *  combination of section × influence orientations compiles.
     *  Defaults mirror the section's roll semantics on entry
     *  (Coaster.enterInfluence); missing file fields = local =
     *  the historic behavior. */
    public boolean inflRollGlobal = false;
    public boolean inflYawPitchGlobal = false;

    /** SPEED CONTROLS (Phase 3, v18.0 D160) on the section's native
     *  axis + the plain speed-line diamonds. The list objects are
     *  long-lived (editor holds references). */
    public final List<SpeedControl> speedCtls = new ArrayList<>();
    public final List<Double> speedMarks = new ArrayList<>();

    @Override public List<SpeedControl> speedControls() { return speedCtls; }

    @Override public List<Double> speedMarks() { return speedMarks; }

    /** Lane sections take controls unless legacy-reversed.
     *  INFLUENCED sections take them too since v19.6 (D235 — user
     *  spec lifting the D160 exclusion): the two-pass drives its
     *  ref, operating profile and pass 2 with the same Ctl, so a
     *  lift/brake shapes the influenced ride exactly like a plain
     *  one. */
    @Override public boolean speedControllable() {
        return !reversed;
    }

    // ---------- INFLUENCE (v15.0, D98; SECTIONS-DESIGN §5.4) ----------
    // Sparse DELTA lanes in the OTHER mode's units (force section →
    // pitch/yaw/roll °/s, geo section → Gv/Gl g; lane 2 is the shared
    // roll rate either way) that ADD to the section's RESULTING graphs:
    // passG integrates base+delta as the other mode, so
    // resulting == base + delta by construction. Null = uninfluenced.
    public List<List<BezierBaker.Node>> deltaLanes;

    /** The AUTHORED design speed, frozen when influence is entered —
     *  the ref (= the section's old shape) always regenerates from
     *  (lanes, vAuthored). While influenced, the vDesign FIELD is the
     *  OPERATING speed (§5.4's vApply) that every existing consumer
     *  ('+' freeze, B re-freeze, bridge vB, run rows) already reads
     *  and writes; DesignIO maps the pair onto the spec'd file slots
     *  (section line = authored, `infl` line = operating). NaN =
     *  uninfluenced. */
    public double vAuthored = Double.NaN;

    /** UI: the strip edits the DELTA lanes (true) vs the design lanes.
     *  Session state — snapshotted in DocState, never saved to file. */
    public boolean editInfluence = false;

    // (the v19.6/D235 split-anchor fields inflRefTilt/inflVStarK are
    // GONE, v19.7 D236 — the one-pass hybrid influence model needs
    // no ref frame: splits are exact by the Markov property alone.
    // The loader ignores the short-lived `infl` fields 4-8.)

    /** Last compile's BASE curves — rows {t*, lane0, lane1, lane2} in
     *  the delta lanes' units on the section-local operating clock
     *  (TrackCompiler two-pass, transient like a bridge's solve). The
     *  editor's guides/offsets read these; never persisted. */
    public double[][] baseCurves;

    public boolean influenced() {
        return deltaLanes != null && !Double.isNaN(vAuthored);
    }

    /** Linear read of a compiled base-curve column at section-local
     *  time t (rows {t*, L0, L1, L2}, clamped ends). Static so the
     *  compiler can evaluate rows it hasn't stored yet; the editor
     *  calls it through {@link #baseValAt}. */
    public static double baseAt(double[][] rows, int lane, double t) {
        if (rows == null || rows.length == 0) return 0;
        int c = lane + 1;
        if (t <= rows[0][0]) return rows[0][c];
        int hi = rows.length - 1;
        if (t >= rows[hi][0]) return rows[hi][c];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (rows[mid][0] <= t) lo = mid; else hi = mid;
        }
        double u = (t - rows[lo][0])
            / Math.max(1e-12, rows[hi][0] - rows[lo][0]);
        return rows[lo][c] + u * (rows[hi][c] - rows[lo][c]);
    }

    /** This section's base value on delta lane `lane` at local time t
     *  (0 before the first compile — a fresh document's guides start
     *  flat and correct themselves on the next rebuild). */
    public double baseValAt(int lane, double t) {
        return baseAt(baseCurves, lane, t);
    }

    /** Base slope (per second) at local time t — centered difference
     *  over the stored rows at ±2 grid steps, one-sided at the ends. */
    public double baseSlopeAt(int lane, double t) {
        if (baseCurves == null || baseCurves.length < 2) return 0;
        double end = baseCurves[baseCurves.length - 1][0];
        double h = Math.max(1e-4, 2 * (end / Math.max(1, baseCurves.length - 1)));
        double t0 = Math.max(0, t - h), t1 = Math.min(end, t + h);
        if (t1 - t0 < 1e-9) return 0;
        return (baseAt(baseCurves, lane, t1) - baseAt(baseCurves, lane, t0))
            / (t1 - t0);
    }

    /** Fresh flat-zero delta lanes (v15.4, D102 — user spec): the two
     *  EDITABLE lanes start with a node at 0 AND at the section end
     *  (dur = the compiled duration), flat default handles — "no
     *  change is a horizontal line" you can grab at either end. Lane 2
     *  (roll) is a lone inert (0,0) node: roll influence was removed
     *  from the UI (only Gv/Gl and pitch/yaw deltas exist), the model
     *  keeps three lanes so the machinery and old files stay whole. */
    public static List<List<BezierBaker.Node>> zeroDeltaLanes(double dur) {
        List<List<BezierBaker.Node>> l = new ArrayList<>(3);
        double end = Math.max(0.1, dur);
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> lane = new ArrayList<>(2);
            lane.add(new BezierBaker.Node(0, 0));
            if (i < 2) {
                lane.add(new BezierBaker.Node(end, 0));
                BezierBaker.applyDefaultHandles(lane);
            }
            l.add(lane);
        }
        return l;
    }

    /** True when every delta lane is identically zero (the do-no-harm
     *  fast path: with vDesign == vAuthored the compiler takes the
     *  plain single-pass route, bitwise). Checks node values AND
     *  handle offsets — any nonzero dv bends the curve off zero. */
    public boolean deltasAllZero() {
        if (deltaLanes == null) return true;
        for (List<BezierBaker.Node> lane : deltaLanes) {
            for (BezierBaker.Node n : lane) {
                if (n.val != 0 || n.outDv != 0 || n.inDv != 0) return false;
            }
        }
        return true;
    }

    protected LaneSection(List<List<BezierBaker.Node>> lanes, double vDesign) {
        this.lanes = lanes;
        this.vDesign = vDesign;
    }

    /** Fresh three empty lanes (loader scaffolding). */
    protected static List<List<BezierBaker.Node>> emptyLanes() {
        List<List<BezierBaker.Node>> l = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) l.add(new ArrayList<>());
        return l;
    }

    /** Two-node 1 s lanes holding the given per-lane values (flat
     *  default handles) — the '+' menu's hold/straight continuations. */
    protected static List<List<BezierBaker.Node>> holdLanes(double v0, double v1, double v2) {
        return holdLanes(v0, v1, v2, 1);
    }

    /** Hold lanes of an explicit x-extent (v16.0): 1 s for TIME
     *  sections, 10 m for DISTANCE continuations. */
    protected static List<List<BezierBaker.Node>> holdLanes(double v0, double v1,
                                                            double v2, double len) {
        double[] vals = { v0, v1, v2 };
        List<List<BezierBaker.Node>> lanes = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> lane = new ArrayList<>(2);
            lane.add(new BezierBaker.Node(0, vals[i]));
            lane.add(new BezierBaker.Node(len, vals[i]));
            BezierBaker.applyDefaultHandles(lane);
            lanes.add(lane);
        }
        return lanes;
    }

    /** Set every lane's END-FOLLOW flag (D248) — the hold creation
     *  sites call it with true; returns this for chaining. */
    public LaneSection endFollows(boolean on) {
        for (int i = 0; i < 3; i++) endFollows[i] = on;
        return this;
    }

    /** The PRISTINE-HOLD predicate (D248): exactly two nodes at the
     *  same value with flat DEFAULT_FRAC facing handles and no lock —
     *  the shape LaneSection.holdLanes creates. Used to DERIVE the
     *  flag for files saved before the field existed and as the
     *  redraw-time belt {@link #clearStaleFollows} (a following lane
     *  that is no longer hold-shaped was edited through a sink that
     *  missed the flag — it is cleared, never moved). The two
     *  DEAD outer handles are ignored (they shape nothing). Values and
     *  dv compare EXACTLY (a de Casteljau cut of a flat hold keeps
     *  them exact); the fractions within 1e-9 — the cut's bisected
     *  root leaves them 1/3 ± ulps, and a split half must stay
     *  pristine (a typed 0.333 is 3e-4 off: an edit). */
    public static boolean pristineHold(List<BezierBaker.Node> lane) {
        if (lane == null || lane.size() != 2) return false;
        BezierBaker.Node a = lane.get(0), b = lane.get(1);
        return a.val == b.val
            && Math.abs(a.outFrac - BezierBaker.DEFAULT_FRAC) <= FRAC_EPS
            && a.outDv == 0
            && Math.abs(b.inFrac - BezierBaker.DEFAULT_FRAC) <= FRAC_EPS
            && b.inDv == 0
            && !a.lockedOut();
    }

    /** pristineHold's fraction tolerance (see there). */
    private static final double FRAC_EPS = 1e-9;

    /** The editor's ONE clearing choke (D248): the lane about to be
     *  written stops following. Resolved by IDENTITY against the
     *  section's own lanes, so a delta lane, a speed-control graph or
     *  any foreign list is a no-op. Returns whether a flag cleared. */
    public static boolean touchLane(LaneSection ls, List<BezierBaker.Node> lane) {
        if (ls == null || lane == null) return false;
        for (int i = 0; i < 3; i++) {
            if (ls.lanes.get(i) == lane) {
                boolean was = ls.endFollows[i];
                ls.endFollows[i] = false;
                return was;
            }
        }
        return false;
    }

    /** The redraw-time BELT (D248 review fix — CLEAR only, never
     *  move): every flagged lane that is no longer {@link #pristineHold}
     *  loses its flag (it was edited through a sink that missed
     *  touchLane); also {@link #syncFollowingEnds}' first pass. A
     *  redraw runs this alone — a node move there would leave the
     *  graph ahead of the compiled track until the next rebuild, which
     *  would then not be a no-op (contract b); the sinks that can move
     *  a last node run the full sync BEFORE their own rebuild. Returns
     *  the number of flags cleared. */
    public static int clearStaleFollows(LaneSection ls) {
        if (ls == null) return 0;
        int cleared = 0;
        for (int i = 0; i < 3; i++) {
            if (ls.endFollows[i] && !pristineHold(ls.lanes.get(i))) {
                ls.endFollows[i] = false;
                cleared++;
            }
        }
        return cleared;
    }

    /** The FOLLOW WRITE (D248): belt first ({@link #clearStaleFollows}
     *  — a flagged lane that is not {@link #pristineHold} is cleared
     *  and left alone) — then
     *  E = the latest last-node x over the DEFINING lanes (flag off,
     *  ≥ 2 nodes) and every still-following lane's last node moves to
     *  max(E, node0 + minGap) (its own axis units: seconds or meters;
     *  minGap = the domain's minimum transition). Values, handles and
     *  locks are untouched (a pristine lane's dv are zero, so no slope
     *  rescale is needed). No defining lane — a fresh, wholly untouched
     *  section — moves nothing; 1-node lanes neither define nor
     *  follow. Returns the number of lanes moved. Pure model so the
     *  gate drives the real thing; the editor decides WHEN (after the
     *  gesture sinks, BEFORE their redraw + rebuild — never during an
     *  undo restore; the top of every redraw runs only the belt, D248
     *  review fix). The loader's derivation (DesignIO.finishCoaster)
     *  produces a fixed point of this write. */
    public static int syncFollowingEnds(LaneSection ls, double minGap) {
        if (ls == null) return 0;
        clearStaleFollows(ls);
        double end = Double.NaN;
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> lane = ls.lanes.get(i);
            if (ls.endFollows[i] || lane.size() < 2) continue;
            double t = lane.get(lane.size() - 1).t;
            end = Double.isNaN(end) ? t : Math.max(end, t);
        }
        if (Double.isNaN(end)) return 0;
        int moved = 0;
        for (int i = 0; i < 3; i++) {
            if (!ls.endFollows[i]) continue;
            List<BezierBaker.Node> lane = ls.lanes.get(i);
            BezierBaker.Node last = lane.get(lane.size() - 1);
            double target = Math.max(end, lane.get(0).t + minGap);
            if (Math.abs(last.t - target) <= 1e-12) continue;
            last.t = target;
            moved++;
        }
        return moved;
    }

    @Override public double vDesign() { return vDesign; }

    @Override public void vDesign(double v) {
        vDesign = v;
        // v15.4 (D102): while influenced the authored speed MIRRORS
        // the operating speed through every consumer — the section
        // behaves exactly like a normal one. Under the v19.7 hybrid
        // (D236) vAuthored is inert compile-side (file compat only);
        // the mirror keeps old readers pairing lanes with the speed
        // they run at.
        if (influenced()) vAuthored = v;
    }

    @Override public boolean vPinned() { return vPinned; }

    @Override public void vPinned(boolean p) { vPinned = p; }

    @Override public boolean vRealAnchored() { return vRealAnchored; }

    @Override public void vRealAnchored(boolean a) { vRealAnchored = a; }

    @Override public Domain domain() { return domain; }

    @Override public void domain(Domain d) {
        domain = d == null ? Domain.TIME : d;
    }

    @Override public boolean viewOtherDomain() { return viewOtherDomain; }

    @Override public void viewOtherDomain(boolean v) { viewOtherDomain = v; }

    @Override public boolean reversed() { return reversed; }

    @Override public void reversed(boolean r) { reversed = r; }
}
