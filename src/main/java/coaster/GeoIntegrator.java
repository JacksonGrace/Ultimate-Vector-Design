package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoIntegrator.java (Phase 2, SECTIONS-DESIGN §3) — geometry for GEO
 * sections: the designer specifies BODY RATES directly, so there is no
 * force equation to invert. The frame simply rotates at the authored
 * rates while position advances at the design-speed profile.
 *
 * KEYFRAME SLOTS: geo lanes ride the same BezierBaker.bake output as
 * force lanes, so the FVDIntegrator.Keyframe fields are REUSED with geo
 * meanings — Gv slot = pitch rate, Gl slot = yaw rate, rollRateDeg =
 * roll rate (all deg/s). This file is the only place that mapping
 * exists; everything downstream sees ordinary Samples.
 *
 * Physics: angular velocity from the body rates and the current frame,
 *   Omega = yaw·U − pitch·R + roll·T          (rad/s; same axis
 * conventions as FVDIntegrator, where pitch = T'·U, yaw = T'·R,
 * roll = −U'·R, so +pitch pulls up, +yaw turns rider-right, +roll
 * matches the roll lane). Orientation is integrated NATIVELY AS A
 * QUATERNION (the SECTIONS-DESIGN §2 amendment: geo sections get the
 * quaternion path; the force integrator keeps its gated T/U vectors):
 *   q' = ½ · (0, Omega) ⊗ q,     pos' = T·v
 * with classic RK4 on (pos, q), q normalized after each step and inside
 * each stage. Speed is ALGEBRAIC like the force integrator: energy vs
 * the section's entry height at v0 = vDesign — so a geo section's SHAPE
 * depends only on (entry pose, lanes, vDesign), preserving the §0
 * freeze. In TIME domain speed still shapes geometry (more seconds =
 * more meters); the future DISTANCE domain (deg/m) removes that too.
 *
 * Recorded Gv/Gl are the FELT forces at the design speed profile —
 * inverting the force integrator's equations (Gv = ω_pitch·v/g + U.y,
 * Gl = ω_yaw·v/g + R.y) — so rail coloring, stats and continuation
 * holds stay honest for geo track.
 */
public final class GeoIntegrator {

    private GeoIntegrator() {}

    /** Samples plus the section's exact exit orientation (the natively
     *  integrated quaternion — not re-derived from the frame). */
    public record Result(List<FVDIntegrator.Sample> samples, Quat qEnd) {}

    /** RK4 state: position + orientation quaternion. */
    private record State(Vec3 pos, Quat q) {}

    private record Deriv(Vec3 dPos, Quat dQ) {}

    private static Deriv deriv(State st, double t, List<FVDIntegrator.Keyframe> kfs,
                               double vDesign, double y0) {
        Quat q = st.q().normalize();   // stages hand us slightly non-unit q
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T); // frame convention: T x R = U, R x U = T, U x T = R

        FVDIntegrator.Keyframe kf = FVDIntegrator.interpAt(kfs, t);
        double v = FVDIntegrator.speedAt(st.pos(), vDesign, y0);

        Vec3 omega = U.scale(Math.toRadians(kf.Gl()))           // yaw slot
            .add(R.scale(-Math.toRadians(kf.Gv())))             // pitch slot
            .add(T.scale(Math.toRadians(kf.rollRateDeg())));    // roll
        Quat dQ = new Quat(0, omega.x(), omega.y(), omega.z()).mul(q).scale(0.5);
        return new Deriv(T.scale(v), dQ);
    }

    private static State advance(State y, Deriv k, double h) {
        return new State(y.pos().add(k.dPos().scale(h)), y.q().add(k.dQ().scale(h)));
    }

    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0) {
        double totalTime = keyframes.get(keyframes.size() - 1).t();

        Vec3 pos = pos0;
        Quat q = q0.normalize();
        double y0 = pos.y();
        double s = 0;
        List<FVDIntegrator.Sample> samples = new ArrayList<>();

        double t = 0;
        while (t <= totalTime) {   // same sampling cadence as the force path
            Vec3 T = q.T();
            Vec3 U = q.U();
            Vec3 R = U.cross(T);
            FVDIntegrator.Keyframe kf = FVDIntegrator.interpAt(keyframes, t);
            double v = FVDIntegrator.speedAt(pos, vDesign, y0);
            // felt forces at design speed (see class comment); v not vSafe:
            // this is a readout, not a division
            double gv = Math.toRadians(kf.Gv()) * v / FVDIntegrator.G0 + U.y();
            double gl = Math.toRadians(kf.Gl()) * v / FVDIntegrator.G0 + R.y();
            samples.add(new FVDIntegrator.Sample(t, s, pos, T, U, R,
                Math.max(0.1, v), gv, gl));

            State y = new State(pos, q);
            Deriv k1 = deriv(y, t, keyframes, vDesign, y0);
            Deriv k2 = deriv(advance(y, k1, dt / 2), t + dt / 2, keyframes, vDesign, y0);
            Deriv k3 = deriv(advance(y, k2, dt / 2), t + dt / 2, keyframes, vDesign, y0);
            Deriv k4 = deriv(advance(y, k3, dt), t + dt, keyframes, vDesign, y0);

            Vec3 newPos = pos.add(
                k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                    .scale(dt / 6));
            q = q.add(
                k1.dQ().add(k2.dQ().scale(2)).add(k3.dQ().scale(2)).add(k4.dQ())
                    .scale(dt / 6)).normalize();

            s += newPos.sub(pos).length();
            pos = newPos;
            t += dt;
        }

        return new Result(samples, q);
    }
}
