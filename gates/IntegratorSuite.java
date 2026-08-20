import coaster.BezierBaker;
import coaster.FVDIntegrator;
import coaster.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Integrator validation suite — run before AND after the RK4 upgrade.
 *  1. Circle closure: flat turn, v0=20, Gl=1.5, Gv=1 => r = v^2/(g*Gl).
 *  2. Default layout invariants: keyframes, ride time, length, end pitch, U.y.
 *  3. Banked-turn identity spot check: Gv = 1/cos(40 deg) at fixed 40 deg bank
 *     should hold a level turn (end height ~ start height).
 */
public class IntegratorSuite {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.GERMANY);

        // ---- 1. circle closure ----
        double v0 = 20, gl = 1.5;
        double r = v0 * v0 / (FVDIntegrator.G0 * gl);
        double circumference = 2 * Math.PI * r;
        double duration = circumference / v0;
        List<FVDIntegrator.Keyframe> circle = List.of(
            new FVDIntegrator.Keyframe(0, 1, gl, 0),
            new FVDIntegrator.Keyframe(duration, 1, gl, 0));
        FVDIntegrator.Options o1 = new FVDIntegrator.Options();
        o1.dt = 0.01; o1.v0 = v0; o1.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Sample> s1 = FVDIntegrator.integrateTrack(circle, o1);
        // (a) radial deviation from the analytic center (isolates the
        // integrator from end-time quantization): center = pos0 + R0*r
        Vec3 center = s1.get(0).pos().add(s1.get(0).R().scale(r));
        double worstRadial = 0, worstPlanar = 0;
        for (FVDIntegrator.Sample sm : s1) {
            worstRadial = Math.max(worstRadial, Math.abs(sm.pos().sub(center).length() - r));
            worstPlanar = Math.max(worstPlanar, Math.abs(sm.pos().y()));
        }
        // (b) position LERPED to the exact closure time vs the start
        int bi = (int) Math.floor(duration / o1.dt);
        FVDIntegrator.Sample sa = s1.get(Math.min(bi, s1.size() - 1));
        FVDIntegrator.Sample sb = s1.get(Math.min(bi + 1, s1.size() - 1));
        double al = sb.t() > sa.t() ? (duration - sa.t()) / (sb.t() - sa.t()) : 0;
        Vec3 atClose = sa.pos().add(sb.pos().sub(sa.pos()).scale(al));
        double closeErr = atClose.sub(s1.get(0).pos()).length();
        System.out.printf(Locale.US,
            "circle: r=%.3f  worstRadialDev=%.9f m  planarDev=%.9f m  closureErr=%.9f m%n",
            r, worstRadial, worstPlanar, closeErr);

        // ---- 2. default layout ----
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(lanes, 0.05);
        FVDIntegrator.Options o2 = new FVDIntegrator.Options();
        o2.dt = 0.01; o2.v0 = 26; o2.pos0 = new Vec3(0, 21, 0);
        o2.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Sample> s2 = FVDIntegrator.integrateTrack(kfs, o2);
        FVDIntegrator.Sample end2 = s2.get(s2.size() - 1);
        double pitch = Math.toDegrees(Math.asin(end2.T().y()));
        double maxV = 0; double minY = 1e9, maxY = -1e9;
        for (FVDIntegrator.Sample sm : s2) {
            maxV = Math.max(maxV, sm.v());
            minY = Math.min(minY, sm.pos().y());
            maxY = Math.max(maxY, sm.pos().y());
        }
        System.out.printf(Locale.US,
            "default: kfs=%d samples=%d ride=%.2f s len=%.3f m endPitch=%.4f deg U.y=%.6f "
            + "maxV=%.2f y=[%.3f..%.3f]%n",
            kfs.size(), s2.size(), end2.t(), end2.s(), pitch, end2.U().y(),
            maxV, minY, maxY);

        // ---- 3. banked-turn identity: 40 deg bank, Gv = 1/cos(40) ----
        // roll to 40 deg in 1 s (40 deg/s), then hold the turn 6 s
        double bank = 40, gv = 1 / Math.cos(Math.toRadians(bank));
        List<FVDIntegrator.Keyframe> banked = new ArrayList<>();
        banked.add(new FVDIntegrator.Keyframe(0, 1, 0, bank));
        banked.add(new FVDIntegrator.Keyframe(1, 1, 0, bank));
        banked.add(new FVDIntegrator.Keyframe(1.0001, gv, 0, 0));
        banked.add(new FVDIntegrator.Keyframe(7, gv, 0, 0));
        FVDIntegrator.Options o3 = new FVDIntegrator.Options();
        o3.dt = 0.01; o3.v0 = 26; o3.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Sample> s3 = FVDIntegrator.integrateTrack(banked, o3);
        double y1 = 1e9, y2 = -1e9;   // height drift DURING the held turn
        for (FVDIntegrator.Sample sm : s3) {
            if (sm.t() < 1.5) continue;
            y1 = Math.min(y1, sm.pos().y());
            y2 = Math.max(y2, sm.pos().y());
        }
        System.out.printf(Locale.US,
            "banked 40deg: Gv=1/cos=%.4f  heightDrift(1.5s..end)=%.4f m%n", gv, y2 - y1);

        // ---- 4. bodyRates extraction ----
        // straight line: all rates ~ 0
        List<FVDIntegrator.Keyframe> straight = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0, 0), new FVDIntegrator.Keyframe(5, 1, 0, 0));
        FVDIntegrator.Options o4 = new FVDIntegrator.Options();
        o4.dt = 0.01; o4.v0 = 26; o4.startHeading = new Vec3(1, 0, 0);
        double worstStraight = 0;
        for (double[] row : FVDIntegrator.bodyRates(FVDIntegrator.integrateTrack(straight, o4)))
            for (int c = 1; c <= 3; c++) worstStraight = Math.max(worstStraight, Math.abs(row[c]));
        // flat circle: yaw = g*Gl/v (deg/s), pitch ~ 0
        double yawTheory = Math.toDegrees(FVDIntegrator.G0 * gl / v0);
        double[][] circRates = FVDIntegrator.bodyRates(s1);
        double worstYaw = 0, worstPitch = 0;
        for (int i = 5; i < circRates.length - 5; i++) {   // skip edge diffs
            worstYaw = Math.max(worstYaw, Math.abs(circRates[i][2] - yawTheory));
            worstPitch = Math.max(worstPitch, Math.abs(circRates[i][1]));
        }
        // constant roll 50 deg/s: extracted roll matches
        List<FVDIntegrator.Keyframe> roll = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0, 50), new FVDIntegrator.Keyframe(3, 1, 0, 50));
        double worstRoll = 0;
        double[][] rollRates = FVDIntegrator.bodyRates(FVDIntegrator.integrateTrack(roll, o4));
        for (int i = 5; i < rollRates.length - 5; i++)
            worstRoll = Math.max(worstRoll, Math.abs(rollRates[i][3] - 50));
        // default layout: extracted roll rate tracks the BAKED roll lane
        double[][] defRates = FVDIntegrator.bodyRates(s2);
        double worstLane = 0;
        for (int i = 5; i < defRates.length - 5; i++) {
            // find the baked keyframe roll at this time (lerp over kfs)
            double t = defRates[i][0];
            double lane = Double.NaN;
            for (int k = 0; k + 1 < kfs.size(); k++) {
                if (kfs.get(k).t() <= t && t <= kfs.get(k + 1).t()) {
                    double u = (t - kfs.get(k).t()) / (kfs.get(k + 1).t() - kfs.get(k).t());
                    lane = kfs.get(k).rollRateDeg()
                         + (kfs.get(k + 1).rollRateDeg() - kfs.get(k).rollRateDeg()) * u;
                    break;
                }
            }
            if (!Double.isNaN(lane)) worstLane = Math.max(worstLane, Math.abs(defRates[i][3] - lane));
        }
        System.out.printf(Locale.US,
            "rates: straight worst=%.6f  circleYaw theory=%.3f worstDev=%.5f  pitchDev=%.5f  "
            + "roll50 worstDev=%.5f  defaultLayout rollLane worstDev=%.4f (deg/s)%n",
            worstStraight, yawTheory, worstYaw, worstPitch, worstRoll, worstLane);
    }
}
