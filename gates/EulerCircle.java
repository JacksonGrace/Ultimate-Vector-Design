import coaster.Vec3;

/** The OLD (pre-RK4) integrator loop, inlined, on the analytic circle —
 *  radial deviation is the apples-to-apples accuracy metric vs RK4. */
public class EulerCircle {
    static final double G0 = 9.80665;

    public static void main(String[] args) {
        double v0 = 20, gl = 1.5, dt = 0.01;
        double r = v0 * v0 / (G0 * gl);
        double duration = 2 * Math.PI * r / v0;

        Vec3 pos = Vec3.ZERO;
        Vec3 T = new Vec3(1, 0, 0);
        Vec3 U = new Vec3(0, 1, 0);
        Vec3 R = U.cross(T);
        Vec3 center = pos.add(R.scale(r));

        double worstRadial = 0, worstPlanar = 0;
        double t = 0;
        while (t <= duration) {
            worstRadial = Math.max(worstRadial, Math.abs(pos.sub(center).length() - r));
            worstPlanar = Math.max(worstPlanar, Math.abs(pos.y()));

            double v = Math.sqrt(Math.max(v0 * v0 + 2 * G0 * (0 - pos.y()), 0));
            double vSafe = Math.max(v, 0.5);
            double omegaU = (G0 * (1.0 - U.y())) / vSafe;
            double omegaR = (G0 * (gl - R.y())) / vSafe;
            Vec3 omega = U.scale(omegaR).add(R.scale(-omegaU));
            double mag = omega.length();
            if (mag > 1e-9) {
                Vec3 axis = omega.scale(1 / mag);
                double ang = mag * dt;
                T = T.rotate(axis, ang).normalize();
                U = U.rotate(axis, ang).normalize();
                R = R.rotate(axis, ang).normalize();
            }
            pos = pos.add(T.scale(v * dt));
            t += dt;
        }
        System.out.printf(java.util.Locale.US,
            "EULER circle: worstRadialDev=%.9f m  planarDev=%.9f m%n",
            worstRadial, worstPlanar);
    }
}
