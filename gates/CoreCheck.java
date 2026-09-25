import uvd.BezierBaker;
import uvd.FVDIntegrator;
import uvd.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Headless regression check after the fractional-handle refactor:
 *  default layout invariants from docs/TESTING.md must still hold. */
public class CoreCheck {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        // same node tables as FvdDesigner's default layout
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));

        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(lanes, 0.05);

        int bad = 0;
        for (int i = 0; i < kfs.size(); i++) {
            FVDIntegrator.Keyframe k = kfs.get(i);
            if (!Double.isFinite(k.Gv()) || !Double.isFinite(k.Gl()) || !Double.isFinite(k.rollRateDeg())) bad++;
            if (i > 0 && k.t() <= kfs.get(i - 1).t()) bad++;
        }
        System.out.println("keyframes: " + kfs.size() + ", violations: " + bad);

        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        opts.pos0 = Vec3.ZERO;
        opts.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Sample> samples = FVDIntegrator.integrateTrack(kfs, opts);

        FVDIntegrator.Sample last = samples.get(samples.size() - 1);
        double pitchDeg = Math.toDegrees(Math.asin(last.T().y()));
        System.out.printf(java.util.Locale.US,
            "ride %.2f s, track %.1f m, end pitch %.2f deg, end U.y %.4f%n",
            last.t(), last.s(), pitchDeg, last.U().y());

        boolean ok = bad == 0 && Math.abs(pitchDeg) < 3 && last.U().y() > 0.99;
        System.out.println(ok ? "PASS" : "FAIL");
    }
}
