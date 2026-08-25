package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * FVDIntegrator.java  (v3: TIME-domain, RK4)
 *
 * Force-Vector-Design style coaster centerline integrator, driven by
 * functions of TIME rather than arc length (matching FVD++'s model).
 *
 * You define the ride as keyframes along time t (seconds):
 *   Keyframe(t, Gv, Gl, rollRateDeg)
 * where:
 *   Gv          = target vertical G-force (1.0 = normal gravity)
 *   Gl          = target lateral G-force
 *   rollRateDeg = banking rate in degrees PER SECOND
 *
 * Physics: at speed v, an angular velocity omega (rad/s) of the heading
 * produces centripetal acceleration a = v * omega. So the turn rates
 * needed to produce the requested forces are:
 *   omegaU = g*(Gv - U.y) / v     (pitching toward the car's up axis)
 *   omegaR = g*(Gl - R.y) / v     (yawing toward the car's right axis)
 * and the roll rate rotates the frame about T directly.
 *
 * Integration (Phase 0 upgrade, see DECISIONS.md D44): classic RK4 on the
 * state (pos, T, U) with T' = Omega x T, U' = Omega x U, pos' = T*v; the
 * frame is re-orthonormalized after every step and R = U x T is derived.
 * Speed is ALGEBRAIC in the frame sense — energy conservation vs the
 * start height, v = sqrt(v0^2 + 2g(y0 - y) - loss), evaluated wherever a
 * stage needs it — with `loss` the FRICTION deficit that joined the RK4
 * state in Phase 3 (v17.0, D133: rolling mu*g + air k*v^2 per meter,
 * exactly 0.0 while the document's coefficients are 0, keeping every
 * pre-friction result bitwise). Each stage re-normalizes its local frame
 * copy so the physics never sees the slightly non-unit vectors RK4
 * stages produce.
 *
 * (The old integrator applied one exact Rodrigues rotation per step from
 * the step-START Omega — first-order in the rate of change of Omega.
 * RK4 shrank the analytic-circle closure error from centimeters to
 * micrometers at the same dt; see docs/TESTING.md #1.)
 */
public class FVDIntegrator {

    public static final double G0 = 9.80665; // m/s^2

    /** PHASE 3 FRICTION (v17.0, D133): the document's two loss
     *  coefficients — ROLLING (dimensionless; deceleration roll·g,
     *  the SECTIONS-DESIGN §7 "first cut" without normal-force
     *  scaling) and AIR drag (1/m; deceleration air·v²). Losses
     *  ride the RK4 state as a specific-energy deficit (v² units):
     *  v² = v0² + 2g(y0−y) − loss. Both coefficients 0 keeps the
     *  deficit exactly 0.0 — every historic path stays bitwise. */
    public record Friction(double roll, double air) {
        public static final Friction NONE = new Friction(0, 0);

        public boolean off() { return roll == 0 && air == 0; }

        /** d(loss)/ds at speed v (v² units per meter of travel). */
        double dLossDs(double v) {
            return 2 * (roll * G0 + air * v * v);
        }
    }

    public record Keyframe(double t, double Gv, double Gl, double rollRateDeg) {}

    /** t = ride time (s); s = accumulated track length (m). */
    public record Sample(double t, double s, Vec3 pos, Vec3 T, Vec3 U, Vec3 R,
                         double v, double Gv, double Gl) {}

    public static class Options {
        public double dt = 0.01;      // integration step, seconds
        public double v0 = 5;         // starting speed m/s
        public Vec3 pos0 = Vec3.ZERO;
        public Vec3 startHeading = new Vec3(1, 0, 0);
        public Vec3 startUp = new Vec3(0, 1, 0);   // entry-pose seeding (Phase 1)
        /** Phase 3 (v17.0, D133): the document's friction model +
         *  design direction. NONE = the historic loss-free profile,
         *  bitwise (the old crude per-second bleed factor this
         *  replaces was hardwired 0 and inert). */
        public Friction fric = Friction.NONE;
        /** GLOBAL roll (v16.1, D107): the roll lane integrates to a
         *  BANK TARGET vs the horizon, enforced by an algebraic
         *  post-step projection (the s/c GLOBAL pattern) — the roll
         *  term leaves Ω. false = the historic local roll, bitwise. */
        public boolean rollGlobal = false;
        /** PER-METER rates (v16.6, D115): the roll lane's values are
         *  °/m — the consumed rate is value × v at every stage, and
         *  the global-roll bank target integrates the lane over ARC
         *  instead of time. A per-meter roll profile twists the same
         *  amount of bank into the same meters of track whatever the
         *  speed. false = the historic °/s, bitwise. */
        public boolean rollPerMeter = false;
        /** ABSOLUTE roll (v16.7, D118): the roll lane holds the BANK
         *  ANGLE (deg) vs the horizon - the global-roll projection
         *  targets the lane VALUE directly (no integration), and the
         *  within-step feed-forward uses the lane's SLOPE. Requires
         *  rollGlobal. */
        public boolean rollAbsolute = false;
        /** SPEED CONTROLS (Phase 3, v18.0 D160): lift/brake/launch
         *  intervals keyed by the authoring axis (t here, s in the
         *  distance path) — step drives + post-step snaps on the loss
         *  deficit. Null = none = every historic path bitwise. */
        public SpeedControl.Ctl ctl = null;
        /** INFLUENCE delta rates (v19.7, D236 — the ONE-PASS hybrid
         *  influence model): other-mode delta keyframes on the SAME
         *  axis as the lanes (Gv slot = Δpitch, Gl slot = Δyaw,
         *  °/s — or °/m under rollPerMeter), added to Ω per stage
         *  at the CURRENT state and to the felt RECORD, so the
         *  resulting graphs == the solve + delta exactly and a
         *  zero-delta stretch IS the plain force solve. null =
         *  bitwise. dInflYpGlobal reads the deltas as heading/
         *  elevation rates (the influence axis flag). */
        public java.util.List<Keyframe> dInfl = null;
        public boolean dInflYpGlobal = false;
    }

    /** Signed bank of U vs the level up at T (rad; roll-lane sign),
     *  or NaN on near-vertical track (no defined bank — the caller
     *  holds off, the s/c rule). */
    static double bankOfTU(Vec3 T, Vec3 U) {
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < 1e-6) return Double.NaN;
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.atan2(ul.cross(U).dot(T), U.dot(ul));
    }

    /** REPORTED-left heading of T (rad — the D185/D186 sign the yaw
     *  lanes, yaw spinners and worldRates all speak: a left turn
     *  grows it), or NaN on near-vertical track. v19.8, D237 — the
     *  absolute yaw/pitch feature's bankOfTU twin. */
    static double headingOfT(Vec3 T) {
        double horiz2 = T.x() * T.x() + T.z() * T.z();
        if (horiz2 < 1e-6) return Double.NaN;
        return Math.atan2(T.z(), T.x());
    }

    /** Elevation of T (rad, + = climbing). Always defined; its
     *  correction AXIS vanishes on vertical track — callers hold
     *  off with the heading. */
    static double elevationOfT(Vec3 T) {
        return Math.asin(Math.max(-1, Math.min(1, T.y())));
    }

    /** The GLOBAL-roll projection: rotate U about T so the bank vs
     *  the horizon equals the target — a pure T-roll never bends the
     *  spine (position/T untouched). Sub-1e-12 corrections skipped;
     *  vertical track held (NaN bank). */
    static Vec3 projectBank(Vec3 T, Vec3 U, double targetBank) {
        double bank = bankOfTU(T, U);
        if (Double.isNaN(bank)) return U;
        double d = targetBank - bank;
        if (Math.abs(d) < 1e-12) return U;
        return U.rotate(T, d);
    }

    /** RK4 state: position + two frame vectors (R is derived) + the
     *  accumulated friction deficit (v17.0 — exactly 0.0 while the
     *  coefficients are 0, keeping every value bitwise-historic). */
    private record State(Vec3 pos, Vec3 T, Vec3 U, double loss) {}

    private record Deriv(Vec3 dPos, Vec3 dT, Vec3 dU, double dLoss) {}

    /** Keyframe lerp at t. Binary search — RK4 calls this 4x per step.
     *  Package-visible: GeoIntegrator reuses it (same bake grid). */
    static Keyframe interpAt(List<Keyframe> kfs, double t) {
        Keyframe first = kfs.get(0);
        if (t <= first.t()) return first;
        Keyframe last = kfs.get(kfs.size() - 1);
        if (t >= last.t()) return last;

        int lo = 0, hi = kfs.size() - 1;      // kfs[lo].t <= t < kfs[hi].t
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (kfs.get(mid).t() <= t) lo = mid; else hi = mid;
        }
        Keyframe a = kfs.get(lo);
        Keyframe b = kfs.get(hi);
        double u = (t - a.t()) / (b.t() - a.t());

        return new Keyframe(
            t,
            a.Gv() + (b.Gv() - a.Gv()) * u,
            a.Gl() + (b.Gl() - a.Gl()) * u,
            a.rollRateDeg() + (b.rollRateDeg() - a.rollRateDeg()) * u
        );
    }

    /** Speed from energy conservation vs the start height (algebraic).
     *  Package-visible: GeoIntegrator shares the design-speed profile. */
    static double speedAt(Vec3 pos, double v0, double y0) {
        return Math.sqrt(Math.max(v0 * v0 + 2 * G0 * (y0 - pos.y()), 0));
    }

    /** Energy speed minus an accumulated friction deficit (v17.0,
     *  D133) — loss in v² units, carried in the RK4 state. loss 0.0
     *  is bitwise the plain expression (x − 0.0 == x). */
    static double speedAt(Vec3 pos, double v0, double y0, double loss) {
        return Math.sqrt(Math.max(
            v0 * v0 + 2 * G0 * (y0 - pos.y()) - loss, 0));
    }

    /** State derivative. The stage frame is re-orthonormalized locally so
     *  omega and the advance direction come from unit vectors even when
     *  RK4 hands us a mid-stage (slightly denormalized) state. */
    private static Deriv deriv(State st, double t, List<Keyframe> kfs,
                               double v0, double y0, boolean rollGlobal,
                               boolean rollPM, boolean rollAbs,
                               Friction fric) {
        return deriv(st, t, kfs, v0, y0, rollGlobal, rollPM, rollAbs,
            fric, Double.NaN, null, false);
    }

    /** drive = a speed control's d(loss)/ds for this step (replaces
     *  friction — the drive supplies/absorbs whatever the felt-G
     *  contract needs); NaN = natural. dInfl = influence GEO-RATE
     *  deltas (v19.7, D236 — the one-pass hybrid), added to Ω at
     *  the current state; null = bitwise. */
    private static Deriv deriv(State st, double t, List<Keyframe> kfs,
                               double v0, double y0, boolean rollGlobal,
                               boolean rollPM, boolean rollAbs,
                               Friction fric, double drive,
                               List<Keyframe> dInfl, boolean dYpG) {
        Vec3 T = st.T().normalize();
        Vec3 U = st.U().sub(T.scale(st.U().dot(T))).normalize();
        Vec3 R = U.cross(T); // frame convention: T x R = U, R x U = T, U x T = R

        Keyframe kf = interpAt(kfs, t);
        double v = speedAt(st.pos(), v0, y0, st.loss());
        double vSafe = Math.max(v, 0.5);

        double omegaU = (G0 * (kf.Gv() - U.y())) / vSafe;
        // LATERAL SIGN (v18.13, D184): +Gl = felt RIGHT — the lane
        // negates into the historic left-positive internal relation
        double omegaR = -(G0 * (kf.Gl() + R.y())) / vSafe;
        Vec3 omega = U.scale(omegaR).add(R.scale(-omegaU));
        if (dInfl != null) {
            // influence deltas join Ω BEFORE the roll term so the
            // global-roll feed-forward sees the whole turn (D236)
            Keyframe dk = interpAt(dInfl, t);
            omega = omega.add(GeoIntegrator.inflOmega(dk.Gv(), dk.Gl(),
                T, U, R, dYpG, rollPM ? v : 1));
        }
        omega = omega.add(T.scale(rollAbs && rollGlobal
            ? rollTermPhi(Math.toRadians(rollSlopeAt(kfs, t)), T, omega)
            : rollTerm(kf, T, omega, rollGlobal, rollPM ? v : 1)));

        // d(loss)/dt = d(loss)/ds · v (the advance rate); exactly 0.0
        // with zero coefficients — the bitwise-off contract. A DRIVEN
        // step (speed control, v18.0) replaces friction outright.
        return new Deriv(T.scale(v), omega.cross(T), omega.cross(U),
            (Double.isNaN(drive) ? fric.dLossDs(v) : drive) * v);
    }

    /** The Ω roll term (rad/s). LOCAL: the lane verbatim (the
     *  historic expression, bitwise). GLOBAL (v16.4, D110 — the
     *  SUB-STEP feed-forward): the BODY roll rate implied by the
     *  lane's bank rate, p = φ' + ψ'·sinθ, with ψ' read off the U/R
     *  part of Ω (a T-roll never moves T — no circularity). The
     *  frame then twists WITHIN steps like a local-roll original and
     *  the post-step projection only corrects O(dt²) — without this
     *  the felt solver read stale banks mid-step, the 0.2% shape
     *  drift on roll-only bakes (the 42c floor). Vertical: 0 (the
     *  projection holds off there anyway). */
    static double rollTerm(Keyframe kf, Vec3 T, Vec3 omegaUR,
                           boolean rollGlobal) {
        return rollTerm(kf, T, omegaUR, rollGlobal, 1);
    }

    /** rateScale = v for PER-METER roll lanes (v16.6, D115): the lane
     *  value is °/m and the consumed rate is value × v; 1 = the
     *  historic °/s, bitwise. */
    static double rollTerm(Keyframe kf, Vec3 T, Vec3 omegaUR,
                           boolean rollGlobal, double rateScale) {
        double rate = Math.toRadians(kf.rollRateDeg()) * rateScale;
        if (!rollGlobal) return rate;
        return rollTermPhi(rate, T, omegaUR);
    }

    /** The global-roll feed-forward from an explicit bank rate
     *  (rad/s) - ABSOLUTE roll lanes derive it from the lane's SLOPE
     *  instead of its value (v16.7, D118). */
    static double rollTermPhi(double phiRate, Vec3 T, Vec3 omegaUR) {
        double horiz2 = T.x() * T.x() + T.z() * T.z();
        if (horiz2 <= 1e-6) return 0;
        Vec3 tp = omegaUR.cross(T);
        double psp = (T.z() * tp.x() - T.x() * tp.z()) / horiz2;
        return phiRate + psp * T.y();   // sinθ = T.y
    }

    /** C1 read of the ABSOLUTE roll lane (v16.9, D121): the baked
     *  keyframes are LINEAR per 0.05 cell, so consuming them
     *  directly as the bank target made the roll RATE a per-cell
     *  STAIRCASE — constant slope, then a jump at every knot (the
     *  user's "jitter"/stair-stepped resulting graph, worst at high
     *  rates; rate lanes never see this: their piecewise-linear
     *  VALUE is already continuous). Catmull-Rom through the knots:
     *  bank C1, rate continuous. Returns {bank°, slope °/axis-unit};
     *  clamps flat outside the grid. */
    static double[] bankHermite(List<Keyframe> kfs, double x) {
        return angleHermite(kfs, x, 2);
    }

    /** The bankHermite body slot-generalized (v19.8, D237 — the
     *  absolute yaw/pitch lanes read their angles through the SAME
     *  C1 machinery): slot 0 = the Gv keyframe slot (elevation °),
     *  1 = the Gl slot (heading °, reported-left), 2 = the roll
     *  slot (bank °). Identical arithmetic to the historic
     *  bankHermite — bitwise for slot 2. */
    static double[] angleHermite(List<Keyframe> kfs, double x, int slot) {
        int n = kfs.size();
        if (n < 2) {
            return new double[]{ n == 0 ? 0 : slotVal(kfs.get(0), slot), 0 };
        }
        if (x <= kfs.get(0).t()) {
            return new double[]{ slotVal(kfs.get(0), slot), 0 };
        }
        if (x >= kfs.get(n - 1).t()) {
            return new double[]{ slotVal(kfs.get(n - 1), slot), 0 };
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (kfs.get(mid).t() <= x) lo = mid; else hi = mid;
        }
        double t0 = kfs.get(lo).t(), t1 = kfs.get(hi).t();
        double h = t1 - t0;
        double v0 = slotVal(kfs.get(lo), slot);
        double v1 = slotVal(kfs.get(hi), slot);
        if (h < 1e-12) return new double[]{ v0, 0 };
        double m0 = lo == 0 ? (v1 - v0) / h
            : (v1 - slotVal(kfs.get(lo - 1), slot))
              / (t1 - kfs.get(lo - 1).t());
        double m1 = hi == n - 1 ? (v1 - v0) / h
            : (slotVal(kfs.get(hi + 1), slot) - v0)
              / (kfs.get(hi + 1).t() - t0);
        double u = (x - t0) / h, u2 = u * u, u3 = u2 * u;
        double val = (2 * u3 - 3 * u2 + 1) * v0
            + (u3 - 2 * u2 + u) * h * m0
            + (-2 * u3 + 3 * u2) * v1
            + (u3 - u2) * h * m1;
        double slope = ((6 * u2 - 6 * u) * v0
            + (3 * u2 - 4 * u + 1) * h * m0
            + (-6 * u2 + 6 * u) * v1
            + (3 * u2 - 2 * u) * h * m1) / h;
        return new double[]{ val, slope };
    }

    private static double slotVal(Keyframe k, int slot) {
        return slot == 0 ? k.Gv() : slot == 1 ? k.Gl() : k.rollRateDeg();
    }

    /** The absolute lane's bank target at x (°, C1). */
    static double bankAt(List<Keyframe> kfs, double x) {
        return bankHermite(kfs, x)[0];
    }

    /** The absolute lane's bank slope at x (° per keyframe-axis
     *  unit, continuous — the feed-forward's input). */
    static double rollSlopeAt(List<Keyframe> kfs, double x) {
        return bankHermite(kfs, x)[1];
    }

    private static State advance(State y, Deriv k, double h) {
        return new State(
            y.pos().add(k.dPos().scale(h)),
            y.T().add(k.dT().scale(h)),
            y.U().add(k.dU().scale(h)),
            y.loss() + k.dLoss() * h);
    }

    public static List<Sample> integrateTrack(List<Keyframe> keyframes, Options opts) {
        double dt = opts.dt;
        double totalTime = keyframes.get(keyframes.size() - 1).t();

        Vec3 pos = opts.pos0;
        double v = opts.v0;
        double vRec = v;   // the RECORDED speed — honest since v18.6
                           // (D175): no 0.1 park on the graphs; the
                           // loop's v keeps the floor for decisions
        Vec3 T = opts.startHeading.normalize();
        // orthonormalize the entry up against T (float-exact no-op for
        // the default (0,1,0) against (1,0,0): dot is 0, sqrt(1) is 1)
        Vec3 U = opts.startUp.sub(T.scale(opts.startUp.dot(T))).normalize();
        Vec3 R = U.cross(T);

        double y0 = pos.y();
        double s = 0;
        List<Sample> samples = new ArrayList<>();

        // GLOBAL roll (v16.1, D107): the bank target = the entry bank
        // plus the roll lane's exact integral (steps land on the
        // 0.05 s keyframe grid, so per-step trapezoids are exact);
        // enforced by projection after every step. A vertical entry
        // binds the reference lazily at the first defined bank.
        boolean rG = opts.rollGlobal;
        double bank0 = rG ? bankOfTU(T, U) : 0;
        double cumRoll = 0;
        double loss = 0;   // friction deficit (v² units), rides RK4

        double t = 0;
        while (true) {
            Keyframe kf = interpAt(keyframes, t);
            double sgv = kf.Gv(), sgl = kf.Gl();
            if (opts.dInfl != null) {
                // the RECORD carries the influence deltas' felt
                // contribution exactly (v19.7, D236 — resulting ==
                // the solve's targets + Δω·v/g, by linearity)
                Keyframe dk = interpAt(opts.dInfl, t);
                Vec3 dOm = GeoIntegrator.inflOmega(dk.Gv(), dk.Gl(),
                    T, U, R, opts.dInflYpGlobal,
                    opts.rollPerMeter ? vRec : 1);
                sgv += -dOm.dot(R) * vRec / G0;
                sgl += -dOm.dot(U) * vRec / G0;
            }
            samples.add(new Sample(t, s, pos, T, U, R, vRec,
                sgv, sgl));

            // END LANDING (v17.10, D147): the closing step is sized to
            // land the last sample EXACTLY on the authored end. The
            // historic `while (t <= totalTime)` refused the final step
            // whenever the accumulated float t overshot a round end
            // (300 x 0.01 = 3.0000000000000027), so every time-section
            // seam absorbed up to one dt of authored profile — the
            // D130 split-vs-unsplit gap. Interior steps stay h == dt,
            // bitwise; the closing step is a single stretched/shrunk
            // step in (0, 1.5*dt] (the D84 no-runt rule: never a full
            // step plus a sliver — near-duplicate samples poison rate
            // differencing).
            double rem = totalTime - t;
            if (rem <= dt * 1e-9) break;
            double h = (rem <= dt * 1.5) ? rem : dt;

            // speed control (v18.0): per-step drive decision at the
            // step-start axis position; NaN = natural
            double drv = opts.ctl == null ? Double.NaN
                : opts.ctl.drive(t, v);
            State y = new State(pos, T, U, loss);
            Deriv k1 = deriv(y, t, keyframes, opts.v0, y0, rG, opts.rollPerMeter, opts.rollAbsolute, opts.fric, drv, opts.dInfl, opts.dInflYpGlobal);
            Deriv k2 = deriv(advance(y, k1, h / 2), t + h / 2, keyframes, opts.v0, y0, rG, opts.rollPerMeter, opts.rollAbsolute, opts.fric, drv, opts.dInfl, opts.dInflYpGlobal);
            Deriv k3 = deriv(advance(y, k2, h / 2), t + h / 2, keyframes, opts.v0, y0, rG, opts.rollPerMeter, opts.rollAbsolute, opts.fric, drv, opts.dInfl, opts.dInflYpGlobal);
            Deriv k4 = deriv(advance(y, k3, h), t + h, keyframes, opts.v0, y0, rG, opts.rollPerMeter, opts.rollAbsolute, opts.fric, drv, opts.dInfl, opts.dInflYpGlobal);

            Vec3 newPos = pos.add(
                k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                    .scale(h / 6));
            T = T.add(
                k1.dT().add(k2.dT().scale(2)).add(k3.dT().scale(2)).add(k4.dT())
                    .scale(h / 6)).normalize();
            U = U.add(
                k1.dU().add(k2.dU().scale(2)).add(k3.dU().scale(2)).add(k4.dU())
                    .scale(h / 6));
            U = U.sub(T.scale(U.dot(T))).normalize();   // re-orthonormalize
            loss += (k1.dLoss() + 2 * k2.dLoss() + 2 * k3.dLoss()
                + k4.dLoss()) * h / 6;
            if (rG && opts.rollAbsolute) {
                // ABSOLUTE roll (D118): the target IS the lane value
                // at the just-reached time - no integral, no entry-
                // bank reference (C1 read since v16.9 D121: the raw
                // polyline made the rate a per-cell staircase)
                U = projectBank(T, U, Math.toRadians(
                    bankAt(keyframes, t + h)));
            } else if (rG) {
                if (opts.rollPerMeter) {
                    // bank target integrates the lane over ARC (D115):
                    // trapezoid of rate·v over the step
                    double v1 = speedAt(newPos, opts.v0, y0, loss);
                    cumRoll += Math.toRadians(
                        kf.rollRateDeg() * v
                        + interpAt(keyframes, t + h).rollRateDeg() * v1)
                        / 2 * h;
                } else {
                    cumRoll += Math.toRadians(kf.rollRateDeg()
                        + interpAt(keyframes, t + h).rollRateDeg()) / 2 * h;
                }
                if (Double.isNaN(bank0)) {
                    double b = bankOfTU(T, U);
                    if (!Double.isNaN(b)) bank0 = b - cumRoll;
                }
                if (!Double.isNaN(bank0)) {
                    U = projectBank(T, U, bank0 + cumRoll);
                }
            }
            R = U.cross(T);

            s += newPos.sub(pos).length();
            pos = newPos;

            // speed-control snap (v18.0): the step that violates a
            // constant-speed flag or crosses an accel target lands ON
            // the target — a pure loss write, keyed on the step-start
            // axis position like the drive
            if (opts.ctl != null) {
                double vA = speedAt(pos, opts.v0, y0, loss);
                double dl = opts.ctl.snap(t, v, vA, !Double.isNaN(drv));
                if (dl != 0) loss += dl;
            }

            // recorded speed: energy minus the accumulated friction
            // deficit (v17.0 — with zero coefficients loss is exactly
            // 0.0 and this equals the historic expression bitwise).
            // HONEST record since v18.6 (D175 — the user's "speed
            // becomes piecewise near zero": the max(0.1,·) parked
            // every slow dip at exactly 0.1 on the graphs); the
            // loop-internal v keeps the floor for the drive/trapezoid
            // decisions — physics bitwise.
            vRec = speedAt(pos, opts.v0, y0, loss);
            v = Math.max(0.1, vRec);

            t = (h == dt) ? t + dt : totalTime;
        }

        return samples;
    }

    // ---------- DISTANCE domain (v16.0, D103) ----------
    // Same physics, other parameterization: the keyframes' t-slots hold
    // ARC LENGTH (meters — the lanes are baked on an s-grid) and targets
    // are looked up by the arc actually traveled. s joins the RK4 state
    // (s' = v, the ScIntegrator pattern); integration still runs in TIME
    // at dt so samples keep their cadence, and the FINAL step is
    // Newton-sized to land s exactly on the lane end (the exit pose is a
    // section contract). The advance speed is floored at 0.5 m/s like
    // ScIntegrator's so a stalling section still terminates (creep) —
    // the same floor the time path already applies to ω via vSafe.

    private record DState(Vec3 pos, Vec3 T, Vec3 U, double s, double loss) {}

    private record DDeriv(Vec3 dPos, Vec3 dT, Vec3 dU, double dS, double dLoss) {}

    private static DDeriv derivDist(DState st, List<Keyframe> kfs,
                                    double v0, double y0, boolean rollGlobal,
                                    boolean rollPM, boolean rollAbs,
                                    Friction fric) {
        return derivDist(st, kfs, v0, y0, rollGlobal, rollPM, rollAbs,
            fric, Double.NaN, null, false);
    }

    private static DDeriv derivDist(DState st, List<Keyframe> kfs,
                                    double v0, double y0, boolean rollGlobal,
                                    boolean rollPM, boolean rollAbs,
                                    Friction fric, double drive,
                                    List<Keyframe> dInfl, boolean dYpG) {
        Vec3 T = st.T().normalize();
        Vec3 U = st.U().sub(T.scale(st.U().dot(T))).normalize();
        Vec3 R = U.cross(T);

        Keyframe kf = interpAt(kfs, st.s());   // lookup by ARC length
        double v = Math.max(0.5, speedAt(st.pos(), v0, y0, st.loss()));

        double omegaU = (G0 * (kf.Gv() - U.y())) / v;
        double omegaR = -(G0 * (kf.Gl() + R.y())) / v;   // +Gl = right (D184)
        Vec3 omega = U.scale(omegaR).add(R.scale(-omegaU));
        if (dInfl != null) {   // influence deltas join Omega (D236)
            Keyframe dk = interpAt(dInfl, st.s());
            omega = omega.add(GeoIntegrator.inflOmega(dk.Gv(), dk.Gl(),
                T, U, R, dYpG, rollPM ? v : 1));
        }
        omega = omega.add(T.scale(rollAbs && rollGlobal
            // slope per METER on an arc axis: phi' = slope x v
            ? rollTermPhi(Math.toRadians(rollSlopeAt(kfs, st.s())) * v,
                T, omega)
            : rollTerm(kf, T, omega, rollGlobal, rollPM ? v : 1)));

        return new DDeriv(T.scale(v), omega.cross(T), omega.cross(U), v,
            (Double.isNaN(drive) ? fric.dLossDs(v) : drive) * v);
    }

    private static DState advanceDist(DState y, DDeriv k, double h) {
        return new DState(
            y.pos().add(k.dPos().scale(h)),
            y.T().add(k.dT().scale(h)),
            y.U().add(k.dU().scale(h)),
            y.s() + k.dS() * h,
            y.loss() + k.dLoss() * h);
    }

    private static DState rk4Dist(DState y, List<Keyframe> kfs,
                                  double v0, double y0, double h,
                                  boolean rollGlobal, boolean rollPM,
                                  boolean rollAbs, Friction fric) {
        return rk4Dist(y, kfs, v0, y0, h, rollGlobal, rollPM, rollAbs,
            fric, Double.NaN);
    }

    private static DState rk4Dist(DState y, List<Keyframe> kfs,
                                  double v0, double y0, double h,
                                  boolean rollGlobal, boolean rollPM,
                                  boolean rollAbs, Friction fric,
                                  double drive) {
        return rk4Dist(y, kfs, v0, y0, h, rollGlobal, rollPM, rollAbs,
            fric, drive, null, false);
    }

    private static DState rk4Dist(DState y, List<Keyframe> kfs,
                                  double v0, double y0, double h,
                                  boolean rollGlobal, boolean rollPM,
                                  boolean rollAbs, Friction fric,
                                  double drive,
                                  List<Keyframe> dInfl, boolean dYpG) {
        DDeriv k1 = derivDist(y, kfs, v0, y0, rollGlobal, rollPM, rollAbs, fric, drive, dInfl, dYpG);
        DDeriv k2 = derivDist(advanceDist(y, k1, h / 2), kfs, v0, y0, rollGlobal, rollPM, rollAbs, fric, drive, dInfl, dYpG);
        DDeriv k3 = derivDist(advanceDist(y, k2, h / 2), kfs, v0, y0, rollGlobal, rollPM, rollAbs, fric, drive, dInfl, dYpG);
        DDeriv k4 = derivDist(advanceDist(y, k3, h), kfs, v0, y0, rollGlobal, rollPM, rollAbs, fric, drive, dInfl, dYpG);
        Vec3 pos = y.pos().add(
            k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                .scale(h / 6));
        Vec3 T = y.T().add(
            k1.dT().add(k2.dT().scale(2)).add(k3.dT().scale(2)).add(k4.dT())
                .scale(h / 6)).normalize();
        Vec3 U = y.U().add(
            k1.dU().add(k2.dU().scale(2)).add(k3.dU().scale(2)).add(k4.dU())
                .scale(h / 6));
        U = U.sub(T.scale(U.dot(T))).normalize();
        double s = y.s() + (k1.dS() + 2 * k2.dS() + 2 * k3.dS() + k4.dS()) * h / 6;
        double loss = y.loss() + (k1.dLoss() + 2 * k2.dLoss()
            + 2 * k3.dLoss() + k4.dLoss()) * h / 6;
        return new DState(pos, T, U, s, loss);
    }

    private static void addSampleDist(List<Sample> samples, DState st, double t,
                                      List<Keyframe> kfs, double v0, double y0) {
        addSampleDist(samples, st, t, kfs, v0, y0, null, false, false);
    }

    private static void addSampleDist(List<Sample> samples, DState st, double t,
                                      List<Keyframe> kfs, double v0, double y0,
                                      List<Keyframe> dInfl, boolean dYpG,
                                      boolean rollPM) {
        Vec3 T = st.T();
        Vec3 U = st.U();
        Keyframe kf = interpAt(kfs, st.s());
        double v = speedAt(st.pos(), v0, y0, st.loss());
        double sgv = kf.Gv(), sgl = kf.Gl();
        if (dInfl != null) {   // resulting == solve + delta (D236)
            Keyframe dk = interpAt(dInfl, st.s());
            Vec3 R = U.cross(T);
            Vec3 dOm = GeoIntegrator.inflOmega(dk.Gv(), dk.Gl(),
                T, U, R, dYpG, rollPM ? v : 1);
            sgv += -dOm.dot(R) * v / G0;
            sgl += -dOm.dot(U) * v / G0;
        }
        samples.add(new Sample(t, st.s(), st.pos(), T, U, U.cross(T),
            v, sgv, sgl));   // honest record (v18.6, D175)
    }

    /** DISTANCE-domain run: keyframes over arc length (meters in the
     *  t slots), integrated until s lands exactly on the last keyframe's
     *  arc. Samples stay on the dt grid plus one Newton-sized landing
     *  step (≤ ~1.25·dt, the D84 rule). */
    public static List<Sample> integrateTrackDist(List<Keyframe> keyframes,
                                                  Options opts) {
        double dt = opts.dt;
        double totalLen = Math.max(keyframes.get(keyframes.size() - 1).t(), 1e-6);

        Vec3 T = opts.startHeading.normalize();
        Vec3 U = opts.startUp.sub(T.scale(opts.startUp.dot(T))).normalize();
        DState st = new DState(opts.pos0, T, U, 0, 0);
        double y0 = opts.pos0.y();
        double t = 0;
        List<Sample> samples = new ArrayList<>();
        int guard = 0;
        boolean rG = opts.rollGlobal;
        double bank0 = rG ? bankOfTU(T, U) : 0;
        double cumRoll = 0;

        while (st.s() < totalLen - 1e-9 && guard++ < 500_000) {
            addSampleDist(samples, st, t, keyframes, opts.v0, y0,
                opts.dInfl, opts.dInflYpGlobal, opts.rollPerMeter);
            double v = Math.max(0.5, speedAt(st.pos(), opts.v0, y0, st.loss()));
            double sPrev = st.s();
            // speed control (v18.0): drive decided at the step-start
            // ARC position; snap keyed on the same position below
            double drv = opts.ctl == null ? Double.NaN
                : opts.ctl.drive(sPrev, v);
            double hUsed;
            if (st.s() + v * dt < totalLen - 1e-12) {
                st = rk4Dist(st, keyframes, opts.v0, y0, dt, rG,
                    opts.rollPerMeter, opts.rollAbsolute, opts.fric, drv,
                    opts.dInfl, opts.dInflYpGlobal);
                t += dt;
                hUsed = dt;
            } else {
                // land the last step EXACTLY on the lane end (v barely
                // changes inside one step — two refinements reach noise)
                double h = (totalLen - st.s()) / v;
                DState end = st;
                for (int it = 0; it < 3; it++) {
                    end = rk4Dist(st, keyframes, opts.v0, y0, h, rG,
                        opts.rollPerMeter, opts.rollAbsolute, opts.fric,
                        drv, opts.dInfl, opts.dInflYpGlobal);
                    double vEnd = Math.max(0.5,
                        speedAt(end.pos(), opts.v0, y0, end.loss()));
                    h += (totalLen - end.s()) / vEnd;
                }
                st = new DState(end.pos(), end.T(), end.U(), totalLen,
                    end.loss());
                t += h;
                hUsed = h;
            }
            if (opts.ctl != null) {
                double vA = speedAt(st.pos(), opts.v0, y0, st.loss());
                double dl = opts.ctl.snap(sPrev, v, vA,
                    !Double.isNaN(drv));
                if (dl != 0) {
                    st = new DState(st.pos(), st.T(), st.U(), st.s(),
                        st.loss() + dl);
                }
            }
            if (rG && opts.rollAbsolute) {
                // ABSOLUTE roll (D118): target = the lane value at
                // the just-reached arc (C1 read, v16.9 D121)
                Vec3 pu = projectBank(st.T(), st.U(), Math.toRadians(
                    bankAt(keyframes, st.s())));
                if (pu != st.U()) {
                    st = new DState(st.pos(), st.T(), pu, st.s(), st.loss());
                }
            } else if (rG) {
                // per-meter roll: the bank target is the lane's ARC
                // integral — exact here, ds is the step's own advance
                cumRoll += opts.rollPerMeter
                    ? Math.toRadians(
                        interpAt(keyframes, sPrev).rollRateDeg()
                        + interpAt(keyframes, st.s()).rollRateDeg())
                        / 2 * (st.s() - sPrev)
                    : Math.toRadians(
                        interpAt(keyframes, sPrev).rollRateDeg()
                        + interpAt(keyframes, st.s()).rollRateDeg())
                        / 2 * hUsed;
                if (Double.isNaN(bank0)) {
                    double b = bankOfTU(st.T(), st.U());
                    if (!Double.isNaN(b)) bank0 = b - cumRoll;
                }
                if (!Double.isNaN(bank0)) {
                    Vec3 pu = projectBank(st.T(), st.U(), bank0 + cumRoll);
                    if (pu != st.U()) {
                        st = new DState(st.pos(), st.T(), pu, st.s(),
                            st.loss());
                    }
                }
            }
        }
        addSampleDist(samples, st, t, keyframes, opts.v0, y0,
            opts.dInfl, opts.dInflYpGlobal, opts.rollPerMeter);
        return samples;
    }

    /**
     * RESULTING body rotation rates from integrated samples, for the
     * read-only "rates" graph overlay (Phase 0 item 3). Display sign
     * conventions match the editor lanes: pull-up = +pitch, right turn =
     * +yaw, and +roll matches the roll-rate lane. Central differences on
     * the frame vectors; with T' = Omega x T and U' = Omega x U the body
     * components are pitch = T'.U, yaw = T'.R, roll = -U'.R.
     *
     * @return one row per sample: { t, pitchDeg, yawDeg, rollDeg } (deg/s)
     */
    public static double[][] bodyRates(List<Sample> samples) {
        int n = samples.size();
        double[][] out = new double[n][4];
        for (int i = 0; i < n; i++) {
            Sample c = samples.get(i);
            Sample a = samples.get(Math.max(0, i - 1));
            Sample b = samples.get(Math.min(n - 1, i + 1));
            double dt = b.t() - a.t();
            out[i][0] = c.t();
            if (dt < 1e-9) continue;   // degenerate: leave zeros
            Vec3 dT = b.T().sub(a.T()).scale(1 / dt);
            Vec3 dU = b.U().sub(a.U()).scale(1 / dt);
            out[i][1] = Math.toDegrees(dT.dot(c.U()));
            // YAW SIGN (v18.14, D185): +yaw = a LEFT turn — the
            // reported rate negates the internal U-rotation sense,
            // pairing with D184's +Gl = body-right
            out[i][2] = Math.toDegrees(-dT.dot(c.R()));
            out[i][3] = Math.toDegrees(-dU.dot(c.R()));
        }
        return out;
    }
}
