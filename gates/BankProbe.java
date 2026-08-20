import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Compare analytic phi' (worldRates) vs finite-difference d(bank)/dt
 *  on the 42c fixture. */
public class BankProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static double bankOf(FVDIntegrator.Sample sm) {
        Vec3 T = sm.T();
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < 1e-6) return Double.NaN;
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.atan2(ul.cross(sm.U()).dot(T), sm.U().dot(ul));
    }

    public static void main(String[] args) throws Exception {
        double dt = 0.01;
        List<List<BezierBaker.Node>> fb = new ArrayList<>();
        fb.add(nodes(new double[][]{ {0,1},{2,2.2},{5,0.4},{8,1} }));
        fb.add(nodes(new double[][]{ {0,0},{8,0} }));
        fb.add(nodes(new double[][]{ {0,0},{3,30},{5,-30},{8,0} }));
        Coaster fbk = Coaster.singleForce(fb, 24, 0, 21, 0);
        TrackCompiler.Result r = TrackCompiler.compile(fbk, dt);
        List<FVDIntegrator.Sample> s = r.samples();
        // use worldRates via reflection? it's package coaster, static
        // package-visible — this probe is outside the package. Recompute
        // inline with the same math instead.
        System.out.println("t  elevDeg  bankDeg  fdBankRate  anPhi'  anPsi'");
        for (int i = 40; i < s.size() - 40; i += 40) {
            FVDIntegrator.Sample sm = s.get(i);
            double vSafe = Math.max(0.5, sm.v());
            double pitch = Math.toDegrees(FVDIntegrator.G0
                * (sm.Gv() - sm.U().y()) / vSafe);
            double yaw = Math.toDegrees(FVDIntegrator.G0
                * (sm.Gl() - sm.R().y()) / vSafe);
            // measured roll (±1 sample) like the converter
            FVDIntegrator.Sample a = s.get(i - 1), b = s.get(i + 1);
            Vec3 dU = b.U().sub(a.U());
            double ds = b.s() - a.s();
            double roll = Math.toDegrees(-dU.dot(sm.R()) / ds) * sm.v();
            Vec3 T = sm.T(), U = sm.U(), R = sm.R();
            Vec3 om = U.scale(Math.toRadians(yaw))
                .add(R.scale(-Math.toRadians(pitch)))
                .add(T.scale(Math.toRadians(roll)));
            Vec3 tp = om.cross(T);
            double horiz2 = T.x() * T.x() + T.z() * T.z();
            double cosTh = Math.sqrt(horiz2);
            double psp = (T.z() * tp.x() - T.x() * tp.z()) / horiz2;
            double php = om.dot(T) + psp * T.y();
            double phpMinus = om.dot(T) - psp * T.y();
            // finite-difference bank rate over +-5 samples
            double b0 = bankOf(s.get(i - 5)), b1 = bankOf(s.get(i + 5));
            double fdt = s.get(i + 5).t() - s.get(i - 5).t();
            double d = b1 - b0;
            while (d > Math.PI) d -= 2 * Math.PI;
            while (d < -Math.PI) d += 2 * Math.PI;
            double fd = Math.toDegrees(d / fdt);
            System.out.printf("%.2f  %7.2f  %7.2f  %8.2f  %8.2f(+) %8.2f(-)  psi'=%.2f%n",
                sm.t(), Math.toDegrees(Math.asin(T.y())),
                Math.toDegrees(bankOf(sm)), fd,
                Math.toDegrees(php), Math.toDegrees(phpMinus),
                Math.toDegrees(psp));
        }
    }
}
