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
 * Speed is ALGEBRAIC, not part of the ODE state: energy conservation vs
 * the start height, v = sqrt(v0^2 + 2g(y0 - y)), evaluated wherever a
 * stage needs it. Each stage re-normalizes its local frame copy so the
 * physics never sees the slightly non-unit vectors RK4 stages produce.
 *
 * (The old integrator applied one exact Rodrigues rotation per step from
 * the step-START Omega — first-order in the rate of change of Omega.
 * RK4 shrank the analytic-circle closure error from centimeters to
 * micrometers at the same dt; see docs/TESTING.md #1.)
 */
public class FVDIntegrator {

    public static final double G0 = 9.80665; // m/s^2

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
        public double friction = 0;   // crude per-second speed bleed factor
    }

    /** RK4 state: position + two frame vectors (R is derived). */
    private record State(Vec3 pos, Vec3 T, Vec3 U) {}

    private record Deriv(Vec3 dPos, Vec3 dT, Vec3 dU) {}

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

    /** State derivative. The stage frame is re-orthonormalized locally so
     *  omega and the advance direction come from unit vectors even when
     *  RK4 hands us a mid-stage (slightly denormalized) state. */
    private static Deriv deriv(State st, double t, List<Keyframe> kfs,
                               double v0, double y0) {
        Vec3 T = st.T().normalize();
        Vec3 U = st.U().sub(T.scale(st.U().dot(T))).normalize();
        Vec3 R = U.cross(T); // frame convention: T x R = U, R x U = T, U x T = R

        Keyframe kf = interpAt(kfs, t);
        double v = speedAt(st.pos(), v0, y0);
        double vSafe = Math.max(v, 0.5);

        double omegaU = (G0 * (kf.Gv() - U.y())) / vSafe;
        double omegaR = (G0 * (kf.Gl() - R.y())) / vSafe;
        Vec3 omega = U.scale(omegaR).add(R.scale(-omegaU))
                      .add(T.scale(Math.toRadians(kf.rollRateDeg())));

        return new Deriv(T.scale(v), omega.cross(T), omega.cross(U));
    }

    private static State advance(State y, Deriv k, double h) {
        return new State(
            y.pos().add(k.dPos().scale(h)),
            y.T().add(k.dT().scale(h)),
            y.U().add(k.dU().scale(h)));
    }

    public static List<Sample> integrateTrack(List<Keyframe> keyframes, Options opts) {
        double dt = opts.dt;
        double totalTime = keyframes.get(keyframes.size() - 1).t();

        Vec3 pos = opts.pos0;
        double v = opts.v0;
        Vec3 T = opts.startHeading.normalize();
        // orthonormalize the entry up against T (float-exact no-op for
        // the default (0,1,0) against (1,0,0): dot is 0, sqrt(1) is 1)
        Vec3 U = opts.startUp.sub(T.scale(opts.startUp.dot(T))).normalize();
        Vec3 R = U.cross(T);

        double y0 = pos.y();
        double s = 0;
        List<Sample> samples = new ArrayList<>();

        double t = 0;
        while (t <= totalTime) {
            Keyframe kf = interpAt(keyframes, t);
            samples.add(new Sample(t, s, pos, T, U, R, v, kf.Gv(), kf.Gl()));

            State y = new State(pos, T, U);
            Deriv k1 = deriv(y, t, keyframes, opts.v0, y0);
            Deriv k2 = deriv(advance(y, k1, dt / 2), t + dt / 2, keyframes, opts.v0, y0);
            Deriv k3 = deriv(advance(y, k2, dt / 2), t + dt / 2, keyframes, opts.v0, y0);
            Deriv k4 = deriv(advance(y, k3, dt), t + dt, keyframes, opts.v0, y0);

            Vec3 newPos = pos.add(
                k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                    .scale(dt / 6));
            T = T.add(
                k1.dT().add(k2.dT().scale(2)).add(k3.dT().scale(2)).add(k4.dT())
                    .scale(dt / 6)).normalize();
            U = U.add(
                k1.dU().add(k2.dU().scale(2)).add(k3.dU().scale(2)).add(k4.dU())
                    .scale(dt / 6));
            U = U.sub(T.scale(U.dot(T))).normalize();   // re-orthonormalize
            R = U.cross(T);

            s += newPos.sub(pos).length();
            pos = newPos;

            // recorded speed: energy conservation, then the (inert while
            // friction = 0) one-step bleed the old integrator applied
            double vFromHeight = speedAt(pos, opts.v0, y0);
            v = Math.max(0.1, vFromHeight - opts.friction * vFromHeight * dt);

            t += dt;
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

    private record DState(Vec3 pos, Vec3 T, Vec3 U, double s) {}

    private record DDeriv(Vec3 dPos, Vec3 dT, Vec3 dU, double dS) {}

    private static DDeriv derivDist(DState st, List<Keyframe> kfs,
                                    double v0, double y0) {
        Vec3 T = st.T().normalize();
        Vec3 U = st.U().sub(T.scale(st.U().dot(T))).normalize();
        Vec3 R = U.cross(T);

        Keyframe kf = interpAt(kfs, st.s());   // lookup by ARC length
        double v = Math.max(0.5, speedAt(st.pos(), v0, y0));

        double omegaU = (G0 * (kf.Gv() - U.y())) / v;
        double omegaR = (G0 * (kf.Gl() - R.y())) / v;
        Vec3 omega = U.scale(omegaR).add(R.scale(-omegaU))
                      .add(T.scale(Math.toRadians(kf.rollRateDeg())));

        return new DDeriv(T.scale(v), omega.cross(T), omega.cross(U), v);
    }

    private static DState advanceDist(DState y, DDeriv k, double h) {
        return new DState(
            y.pos().add(k.dPos().scale(h)),
            y.T().add(k.dT().scale(h)),
            y.U().add(k.dU().scale(h)),
            y.s() + k.dS() * h);
    }

    private static DState rk4Dist(DState y, List<Keyframe> kfs,
                                  double v0, double y0, double h) {
        DDeriv k1 = derivDist(y, kfs, v0, y0);
        DDeriv k2 = derivDist(advanceDist(y, k1, h / 2), kfs, v0, y0);
        DDeriv k3 = derivDist(advanceDist(y, k2, h / 2), kfs, v0, y0);
        DDeriv k4 = derivDist(advanceDist(y, k3, h), kfs, v0, y0);
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
        return new DState(pos, T, U, s);
    }

    private static void addSampleDist(List<Sample> samples, DState st, double t,
                                      List<Keyframe> kfs, double v0, double y0) {
        Vec3 T = st.T();
        Vec3 U = st.U();
        Keyframe kf = interpAt(kfs, st.s());
        double v = speedAt(st.pos(), v0, y0);
        samples.add(new Sample(t, st.s(), st.pos(), T, U, U.cross(T),
            Math.max(0.1, v), kf.Gv(), kf.Gl()));
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
        DState st = new DState(opts.pos0, T, U, 0);
        double y0 = opts.pos0.y();
        double t = 0;
        List<Sample> samples = new ArrayList<>();
        int guard = 0;

        while (st.s() < totalLen - 1e-9 && guard++ < 500_000) {
            addSampleDist(samples, st, t, keyframes, opts.v0, y0);
            double v = Math.max(0.5, speedAt(st.pos(), opts.v0, y0));
            if (st.s() + v * dt < totalLen - 1e-12) {
                st = rk4Dist(st, keyframes, opts.v0, y0, dt);
                t += dt;
            } else {
                // land the last step EXACTLY on the lane end (v barely
                // changes inside one step — two refinements reach noise)
                double h = (totalLen - st.s()) / v;
                DState end = st;
                for (int it = 0; it < 3; it++) {
                    end = rk4Dist(st, keyframes, opts.v0, y0, h);
                    double vEnd = Math.max(0.5, speedAt(end.pos(), opts.v0, y0));
                    h += (totalLen - end.s()) / vEnd;
                }
                st = new DState(end.pos(), end.T(), end.U(), totalLen);
                t += h;
            }
        }
        addSampleDist(samples, st, t, keyframes, opts.v0, y0);
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
            out[i][2] = Math.toDegrees(dT.dot(c.R()));
            out[i][3] = Math.toDegrees(-dU.dot(c.R()));
        }
        return out;
    }
}
