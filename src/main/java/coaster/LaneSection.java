package coaster;

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

    public double vDesign;
    public boolean reversed = false;
    public boolean vPinned = false;   // v14.5 D97: reconcile-exempt
    public String name = "";

    /** Authoring DOMAIN (v16.0, D103): TIME = lane x is seconds (the
     *  classic model), DISTANCE = lane x is METERS of track. The lane
     *  VALUES keep their units either way (G / °/s) — the domain only
     *  re-parameterizes the x-axis, which is what lets the exact node
     *  map convert between them without touching values. */
    public Section.Domain domain = Section.Domain.TIME;

    /** Cross-domain view flag (v16.0) — see Section.viewOtherDomain. */
    public boolean viewOtherDomain = false;

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
     *  back to the compiled curves, and influence refuses. */
    public boolean globalAxes() { return rollGlobal || yawPitchGlobal; }

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

    @Override public double vDesign() { return vDesign; }

    @Override public void vDesign(double v) {
        vDesign = v;
        // v15.4 (D102): while influenced the authored speed MIRRORS
        // the operating speed through every consumer (spinner, B
        // re-freeze, the D95 reconcile, resolve ops) — the section
        // then behaves exactly like a normal one (ref re-derives at
        // the new speed, deltas ride on top). The pair only diverges
        // in hand-edited files, which the compiler still honors.
        if (influenced()) vAuthored = v;
    }

    @Override public boolean vPinned() { return vPinned; }

    @Override public void vPinned(boolean p) { vPinned = p; }

    @Override public Domain domain() { return domain; }

    @Override public void domain(Domain d) {
        domain = d == null ? Domain.TIME : d;
    }

    @Override public boolean viewOtherDomain() { return viewOtherDomain; }

    @Override public void viewOtherDomain(boolean v) { viewOtherDomain = v; }

    @Override public boolean reversed() { return reversed; }

    @Override public void reversed(boolean r) { reversed = r; }
}
