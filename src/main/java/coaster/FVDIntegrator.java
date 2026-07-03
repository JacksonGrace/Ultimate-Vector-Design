package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * FVDIntegrator.java  (v2: TIME-domain)
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
 * Each step: rotate the frame by Omega*dt, advance pos by T*v*dt, update
 * v from energy conservation (minus a simple friction bleed).
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
        public double friction = 0;   // crude per-second speed bleed factor
    }

    private static Keyframe interpAt(List<Keyframe> kfs, double t) {
        Keyframe first = kfs.get(0);
        if (t <= first.t()) return first;
        Keyframe last = kfs.get(kfs.size() - 1);
        if (t >= last.t()) return last;

        int i = 0;
        while (kfs.get(i + 1).t() < t) i++;
        Keyframe a = kfs.get(i);
        Keyframe b = kfs.get(i + 1);
        double u = (t - a.t()) / (b.t() - a.t());

        return new Keyframe(
            t,
            a.Gv() + (b.Gv() - a.Gv()) * u,
            a.Gl() + (b.Gl() - a.Gl()) * u,
            a.rollRateDeg() + (b.rollRateDeg() - a.rollRateDeg()) * u
        );
    }

    public static List<Sample> integrateTrack(List<Keyframe> keyframes, Options opts) {
        double dt = opts.dt;
        double totalTime = keyframes.get(keyframes.size() - 1).t();

        Vec3 pos = opts.pos0;
        double v = opts.v0;
        Vec3 T = opts.startHeading.normalize();
        Vec3 U = new Vec3(0, 1, 0);
        Vec3 R = U.cross(T); // frame convention: T x R = U, R x U = T, U x T = R

        double y0 = pos.y();
        double s = 0;
        List<Sample> samples = new ArrayList<>();

        double t = 0;
        while (t <= totalTime) {
            Keyframe kf = interpAt(keyframes, t);
            double rollRate = Math.toRadians(kf.rollRateDeg()); // rad/s

            samples.add(new Sample(t, s, pos, T, U, R, v, kf.Gv(), kf.Gl()));

            double vSafe = Math.max(v, 0.5);

            // angular velocities (rad/s) needed for the requested forces
            double omegaU = (G0 * (kf.Gv() - U.y())) / vSafe;
            double omegaR = (G0 * (kf.Gl() - R.y())) / vSafe;

            Vec3 omega = U.scale(omegaR).add(R.scale(-omegaU)).add(T.scale(rollRate));
            double omegaMag = omega.length();

            if (omegaMag > 1e-9) {
                Vec3 axis = omega.scale(1.0 / omegaMag);
                double angle = omegaMag * dt;
                T = T.rotate(axis, angle).normalize();
                U = U.rotate(axis, angle).normalize();
                R = R.rotate(axis, angle).normalize();
            }

            double step = v * dt;
            pos = pos.add(T.scale(step));
            s += step;

            // energy conservation relative to start height, then friction bleed
            double vFromHeight = Math.sqrt(Math.max(opts.v0 * opts.v0 + 2 * G0 * (y0 - pos.y()), 0));
            v = Math.max(0.1, vFromHeight - opts.friction * vFromHeight * dt);

            t += dt;
        }

        return samples;
    }
}
