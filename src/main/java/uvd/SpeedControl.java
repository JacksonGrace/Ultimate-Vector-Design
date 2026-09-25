package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * SpeedControl.java (Phase 3, v18.0) — one speed-controlled interval of
 * a section: a LIFT/TRANSPORT (constant speed), a LAUNCH/BRAKE
 * (constant felt longitudinal G toward a target speed), or either with
 * the constant replaced by an editable Bezier graph ("complex").
 * Pure Java, no JavaFX.
 *
 * MODEL: the control lives on the section's own authoring axis —
 * seconds for TIME lane sections, meters of arc for DISTANCE lanes and
 * straight/curve sections — as an interval [x0, x0+len], clamped
 * inside the section (a control never extends its section). The
 * complex lane's node x runs CONTROL-LOCAL [0..len] so dragging the
 * control carries its graph.
 *
 * PHYSICS (the D160 contract): a control edits the LOSS DEFICIT that
 * already rides every integrator's RK4 state (v17.0, D133), so speed
 * stays v = sqrt(v0² + 2g(y0−y) − loss) everywhere and zero controls
 * stay bitwise:
 *
 *  - CONSTANT SPEED: post-step, whenever the flags are violated
 *    (maxLimit and v above target / minLimit and v below), the loss is
 *    SNAPPED so v == target exactly — the user's "instantly snap".
 *    With one flag off the other side runs at natural speed.
 *  - CONSTANT ACCEL: while v is off-target on a flagged side, the
 *    step's loss rate becomes d(loss)/ds = ∓2·g·A — algebra: felt
 *    longitudinal G is (dv/dt + g·T.y)/g, so enforcing felt == A gives
 *    d(v²)/ds = 2g(A − T.y), i.e. loss' = −2gA (friction is absorbed
 *    by the drive: the rider feels exactly A). A step that CROSSES the
 *    target snaps onto it; at the target the control holds it exactly
 *    (the limit of the ramp) until the flags release it.
 *  - COMPLEX: the graph replaces the constant — target speed for
 *    SPEED controls (green), accel magnitude for ACCEL controls
 *    (blue, negative values coast); ACCEL keeps its constant target
 *    speed. Values read EXACTLY off the cubic (BezierBaker.exactAt).
 *
 * Both the DESIGN pass (each integrator, via Ctl) and the REAL pass
 * (TrackCompiler.passD, via per-sample resolved rows) apply the same
 * rules, so a control acts on both speeds equally — which is also how
 * a speed control can close a design/real conflict (both sides exit
 * at the same clamped speed).
 */
public class SpeedControl {

    public static final int SPEED = 0, ACCEL = 1;

    /** Comparison band for "at the target" (m/s). */
    static final double EPS = 1e-9;

    public int type = SPEED;
    public boolean complex = false;
    /** Start + extent on the section's native axis (s or m). */
    public double x0 = 0, len = 1;
    /** Target speed (m/s) — the value box for SPEED controls, and the
     *  approach target for ACCEL controls (complex included). */
    public double speed = 15;
    /** Felt longitudinal G magnitude for ACCEL controls (g) — the
     *  ACCELERATING side (below-target). */
    public double accel = 0.5;
    /** The DECELERATING side's magnitude (g), v18.45 (D217): tied
     *  (the default) means the evaluator reads accel for both sides
     *  — bitwise the pre-split behavior; untied lets a section brake
     *  at a different felt G than it launches. */
    public double decel = 0.5;
    /** Tied = one magnitude (and one complex graph) serves both
     *  directions — the historic behavior and every old file. */
    public boolean tied = true;
    /** How the target acts (at least one is always on — the UI
     *  re-checks the other when both would go off): maxLimit = the
     *  target is a ceiling (brake side), minLimit = a floor (lift/
     *  launch side). Both = hold/approach the target from either
     *  side. */
    public boolean minLimit = true, maxLimit = true;
    /** The complex graph — node x CONTROL-LOCAL in [0..len]; values
     *  m/s (SPEED) or g (ACCEL). Always ≥ 2 nodes. For an UNTIED
     *  complex ACCEL control this is the ACCELERATION graph. */
    public List<BezierBaker.Node> lane = new ArrayList<>();
    /** The DECELERATION graph of an untied complex ACCEL control
     *  (v18.45, D217); empty = falls back to the decel constant.
     *  Kept (and saved) across re-ties for session fidelity — the
     *  evaluator simply ignores it while tied. */
    public List<BezierBaker.Node> decelLane = new ArrayList<>();
    /** Which graph the strip DISPLAYS/EDITS for an untied complex
     *  ACCEL control (session state — rides undo via copy(), never
     *  saved; accel first by default, D217 user spec). */
    public boolean showDecel = false;

    public SpeedControl() {}

    /** Stretch BOTH complex graphs by f about x0 (v18.45, D217 — the
     *  fraction-semantics rescale; node x is control-local so a pure
     *  scale IS the stretch, handles keep their slopes via the D105
     *  dv rescale). Shared by the editor's clamp, the end-diamond
     *  resize, the popup Length spinner and — since v19.19, D248 — the
     *  START diamond's resize-from-the-left (Gestures.resizeStart);
     *  lives here so the headless gates reach it. */
    public void rescaleLanes(double f) {
        if (!lane.isEmpty()) {
            double[] gaps = BezierBaker.segGaps(lane);
            for (BezierBaker.Node nd : lane) nd.t *= f;
            BezierBaker.rescaleDvToGaps(lane, gaps);
        }
        if (!decelLane.isEmpty()) {
            double[] gaps = BezierBaker.segGaps(decelLane);
            for (BezierBaker.Node nd : decelLane) nd.t *= f;
            BezierBaker.rescaleDvToGaps(decelLane, gaps);
        }
    }

    public SpeedControl(double x0, double len) {
        this.x0 = x0;
        this.len = len;
    }

    /** Two flat nodes holding `val` — a fresh complex graph. */
    public void freshLane(double val) {
        lane.clear();
        lane.add(new BezierBaker.Node(0, val));
        lane.add(new BezierBaker.Node(Math.max(0.01, len), val));
        BezierBaker.applyDefaultHandles(lane);
    }

    /** freshLane's decel twin (v18.45, D217). */
    public void freshDecelLane(double val) {
        decelLane.clear();
        decelLane.add(new BezierBaker.Node(0, val));
        decelLane.add(new BezierBaker.Node(Math.max(0.01, len), val));
        BezierBaker.applyDefaultHandles(decelLane);
    }

    /** The lane the strip DISPLAYS/EDITS (v18.45, D217): the decel
     *  graph only for an untied complex ACCEL control toggled onto
     *  its deceleration view. */
    public List<BezierBaker.Node> editLane() {
        return type == ACCEL && complex && !tied && showDecel
            ? decelLane : lane;
    }

    public SpeedControl copy() {
        SpeedControl c = new SpeedControl(x0, len);
        c.type = type;
        c.complex = complex;
        c.speed = speed;
        c.accel = accel;
        c.decel = decel;
        c.tied = tied;
        c.showDecel = showDecel;
        c.minLimit = minLimit;
        c.maxLimit = maxLimit;
        for (BezierBaker.Node n : lane) c.lane.add(n.copy());
        for (BezierBaker.Node n : decelLane) c.decelLane.add(n.copy());
        return c;
    }

    public static List<SpeedControl> copyList(List<SpeedControl> src) {
        List<SpeedControl> out = new ArrayList<>(src.size());
        for (SpeedControl c : src) out.add(c.copy());
        return out;
    }

    /** Target speed at control-local x (m/s, floored — a zero target
     *  would divide the force solver). */
    public double targetAt(double lx) {
        double v = complex && type == SPEED && !lane.isEmpty()
            ? BezierBaker.exactAt(lane, lx) : speed;
        return Math.max(0.5, v);
    }

    /** Accel magnitude at control-local x (g; complex ACCEL graphs may
     *  dip negative = coast) — the ACCELERATING side. */
    public double accelAt(double lx) {
        return complex && type == ACCEL && !lane.isEmpty()
            ? BezierBaker.exactAt(lane, lx) : accel;
    }

    /** The DECELERATING side's magnitude at control-local x (v18.45,
     *  D217): tied reads the accel side — bitwise the old behavior;
     *  untied reads the decel graph/constant. */
    public double decelAt(double lx) {
        if (tied) return accelAt(lx);
        return complex && type == ACCEL && !decelLane.isEmpty()
            ? BezierBaker.exactAt(decelLane, lx) : decel;
    }

    // ----------------------------------------------------------------
    // shared step logic (the integrators' Ctl and passD's resolved
    // rows both route through these, so design and real agree exactly)
    // ----------------------------------------------------------------

    /** d(loss)/ds for a DRIVEN step (∓2gA toward the target), or NaN =
     *  natural (friction applies). Split magnitudes since v18.45
     *  (D217): aAcc drives the below-target (launch/lift) side, aDec
     *  the above-target (brake) side — tied controls pass the same
     *  value for both, reproducing the one-magnitude arithmetic
     *  bit-for-bit. A non-positive magnitude leaves ITS side
     *  natural, per-side (was one gate for both). */
    static double driveCore(int type, double vt, double aAcc, double aDec,
                            boolean min, boolean max, double v) {
        if (type != ACCEL) return Double.NaN;
        if (v < vt - EPS && min) {
            return aAcc <= 0 ? Double.NaN : -2 * FVDIntegrator.G0 * aAcc;
        }
        if (v > vt + EPS && max) {
            return aDec <= 0 ? Double.NaN : 2 * FVDIntegrator.G0 * aDec;
        }
        return Double.NaN;
    }

    /** Post-step loss ADJUSTMENT (add to the deficit) that snaps v
     *  onto the target, or 0. vB/vA = speed before/after the step;
     *  drove = the step ran a drive. */
    static double snapCore(int type, double vt, boolean min, boolean max,
                           double vB, double vA, boolean drove) {
        if (type == SPEED) {
            if ((max && vA > vt) || (min && vA < vt)) {
                return vA * vA - vt * vt;
            }
            return 0;
        }
        // ACCEL: a driven step that crossed the target lands ON it; an
        // at-target step whose natural drift violates a flag holds it
        // (the ramp's limit behavior)
        boolean crossed = (vB - vt) * (vA - vt) <= 0;
        if (crossed && (drove || (max && vA > vt) || (min && vA < vt))) {
            return vA * vA - vt * vt;
        }
        return 0;
    }

    // ----------------------------------------------------------------
    // structural ops — splits/merges/converts carry controls (v18.0)
    // ----------------------------------------------------------------

    /** Exact split of a complex lane at local x: left keeps [0..x],
     *  right is [x..end] re-anchored to 0. De Casteljau inside a
     *  segment (curve exact); a lane ending before x holds. */
    static List<BezierBaker.Node>[] splitLane(List<BezierBaker.Node> lane,
                                              double x) {
        List<BezierBaker.Node> L = new ArrayList<>();
        List<BezierBaker.Node> R = new ArrayList<>();
        for (BezierBaker.Node n : lane) {
            if (n.t < x - 1e-9) L.add(n.copy());
            else if (n.t > x + 1e-9) R.add(n.copy());
            else {   // ON the cut: both sides own it
                L.add(n.copy());
                R.add(n.copy());
            }
        }
        boolean onNode = !L.isEmpty() && Math.abs(L.get(L.size() - 1).t - x) <= 1e-9;
        if (!onNode) {
            if (L.isEmpty()) {
                // cut before the lane: right = whole, left = held start
                double v = lane.isEmpty() ? 0 : lane.get(0).val;
                L.add(new BezierBaker.Node(x, v));
            } else if (R.isEmpty()) {
                // cut past the lane end: left = whole + held end node
                double v = L.get(L.size() - 1).val;
                L.add(new BezierBaker.Node(x, v));
                R.add(new BezierBaker.Node(x, v));
            } else {
                // inside the segment L.last -> R.first: exact split
                BezierBaker.Node a = L.get(L.size() - 1);
                BezierBaker.Node b = R.get(0);
                BezierBaker.Node mid = BezierBaker.splitSegment(a, b, x);
                L.add(mid.copy());
                R.add(0, mid.copy());
            }
        }
        for (BezierBaker.Node n : R) n.t -= x;
        @SuppressWarnings("unchecked")
        List<BezierBaker.Node>[] out = new List[]{ L, R };
        return out;
    }

    /** The sub-controls of src lying inside [lo, hi], re-anchored so
     *  lo becomes 0 (straddling controls clip exactly, complex graphs
     *  included). Deep copies throughout. */
    public static List<SpeedControl> portion(List<SpeedControl> src,
                                             double lo, double hi) {
        List<SpeedControl> out = new ArrayList<>();
        for (SpeedControl c : src) {
            double a = Math.max(c.x0, lo);
            double b = Math.min(c.x0 + c.len, hi);
            if (b - a < 1e-6) continue;
            SpeedControl cp = c.copy();
            if (cp.complex && !cp.lane.isEmpty()) {
                if (a > c.x0 + 1e-9) {
                    cp.lane = splitLane(cp.lane, a - c.x0)[1];
                }
                if (b < c.x0 + c.len - 1e-9) {
                    cp.lane = splitLane(cp.lane, b - a)[0];
                }
            }
            // the decel graph clips identically (v18.45, D217)
            if (cp.complex && !cp.decelLane.isEmpty()) {
                if (a > c.x0 + 1e-9) {
                    cp.decelLane = splitLane(cp.decelLane, a - c.x0)[1];
                }
                if (b < c.x0 + c.len - 1e-9) {
                    cp.decelLane = splitLane(cp.decelLane, b - a)[0];
                }
            }
            cp.x0 = a - lo;
            cp.len = b - a;
            out.add(cp);
        }
        return out;
    }

    /** Marks inside [lo, hi), re-anchored to lo. */
    public static List<Double> marksPortion(List<Double> src,
                                            double lo, double hi) {
        List<Double> out = new ArrayList<>();
        for (double m : src) {
            if (m >= lo - 1e-9 && m < hi - 1e-9) out.add(m - lo);
        }
        return out;
    }

    // ----------------------------------------------------------------
    // Ctl — the evaluator handed to the integrators (design pass).
    // Null = no controls = every historic path bitwise.
    // ----------------------------------------------------------------

    public static final class Ctl {
        final List<SpeedControl> list;

        private Ctl(List<SpeedControl> list) { this.list = list; }

        /** Null when there is nothing to do (the bitwise-off gate). */
        public static Ctl of(List<SpeedControl> ctls) {
            return ctls == null || ctls.isEmpty() ? null : new Ctl(ctls);
        }

        /** The control owning axis position x, or null. Controls are
         *  disjoint by construction; half-open [x0, x0+len). */
        SpeedControl at(double x) {
            for (SpeedControl c : list) {
                if (x >= c.x0 && x < c.x0 + c.len) return c;
            }
            return null;
        }

        /** Step drive: d(loss)/ds for the step starting at x with
         *  speed v, or NaN = natural. */
        public double drive(double x, double v) {
            SpeedControl c = at(x);
            if (c == null) return Double.NaN;
            return driveCore(c.type, Math.max(0.5, c.speed),
                c.accelAt(x - c.x0), c.decelAt(x - c.x0),
                c.minLimit, c.maxLimit, v);
        }

        /** Post-step snap: loss adjustment for the step that STARTED
         *  at x (0 = none). */
        public double snap(double x, double vB, double vA, boolean drove) {
            SpeedControl c = at(x);
            if (c == null) return 0;
            double vt = c.type == SPEED
                ? c.targetAt(x - c.x0) : Math.max(0.5, c.speed);
            return snapCore(c.type, vt, c.minLimit, c.maxLimit,
                vB, vA, drove);
        }
    }

    // ----------------------------------------------------------------
    // per-sample resolved rows for TrackCompiler.passD (the REAL march
    // walks global samples; compile resolves each in-control sample to
    // {type, vt, A, min, max} so complex graphs are already evaluated)
    // ----------------------------------------------------------------

    /** Resolved row {type, targetSpeed, accel, decel, min, max} for a
     *  sample at control-local x, in the given control (decel slot
     *  since v18.45, D217 — complex decel graphs pre-resolve for
     *  passD exactly like accel always did). */
    static double[] rowFor(SpeedControl c, double lx) {
        return new double[]{ c.type,
            c.type == SPEED ? c.targetAt(lx) : Math.max(0.5, c.speed),
            c.accelAt(lx), c.decelAt(lx),
            c.minLimit ? 1 : 0, c.maxLimit ? 1 : 0 };
    }

    static double driveRow(double[] row, double v) {
        if (row == null) return Double.NaN;
        return driveCore((int) row[0], row[1], row[2], row[3],
            row[4] != 0, row[5] != 0, v);
    }

    static double snapRow(double[] row, double vB, double vA,
                          boolean drove) {
        if (row == null) return 0;
        return snapCore((int) row[0], row[1], row[4] != 0, row[5] != 0,
            vB, vA, drove);
    }
}
