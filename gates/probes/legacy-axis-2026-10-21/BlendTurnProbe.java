import java.util.ArrayList;
import java.util.List;
import uvd.*;

/** Stage 1a diagnostic: Phase2Gate i's "clamped blends keep the turn"
 *  fixture (straight 10 / R5 −20° blend 10 / straight 10 blend 10) — the
 *  heading error vs the analytic 20° turn at several dt, to tell RK4
 *  discretization (shrinks with dt) from a law defect (does not). Run
 *  twice: plain and with -Dfvd.sc.legacyAxis=1. */
public final class BlendTurnProbe {
    public static void main(String[] args) {
        for (double dt : new double[] { 0.01, 0.005, 0.0025, 0.00125 }) {
            List<StraightCurveSection.Element> els = new ArrayList<>();
            els.add(StraightCurveSection.Element.straight(10, 0));
            els.add(StraightCurveSection.Element.curve(5, -20, 0, 10));
            els.add(StraightCurveSection.Element.straight(10, 10));
            for (StraightCurveSection.Element e : els) {
                e.rollMode = StraightCurveSection.RollMode.RATE;
                e.rollVal = 0;
            }
            StraightCurveSection sc = new StraightCurveSection(els, 26);
            ScIntegrator.Result r = ScIntegrator.integrate(sc, dt, 26,
                new Vec3(0, 21, 0), Quat.IDENTITY);
            FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
            Vec3 exp = new Vec3(Math.cos(Math.toRadians(20)), 0, -Math.sin(Math.toRadians(20)));
            double c = Math.max(-1, Math.min(1, last.T().dot(exp)));
            System.out.printf(java.util.Locale.US, "dt %.5f  heading off %.3e deg  n=%d  bounds=%d%n",
                dt, Math.toDegrees(Math.acos(c)), r.samples().size(),
                r.bounds() == null ? -1 : r.bounds().size());
        }
    }
}
