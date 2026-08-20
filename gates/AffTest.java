import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Is the baked lane's segment area affine in a node value with the
 *  predicted h/2-ish response? Nudge one node, re-bake, measure. */
public class AffTest {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static double area(List<FVDIntegrator.Keyframe> kfs, double x0, double x1) {
        double sum = 0, xp = x0, vp = kv(kfs, x0);
        for (FVDIntegrator.Keyframe kf : kfs) {
            if (kf.t() <= x0) continue;
            if (kf.t() >= x1) break;
            sum += (vp + kf.Gv()) / 2 * (kf.t() - xp);
            xp = kf.t(); vp = kf.Gv();
        }
        sum += (vp + kv(kfs, x1)) / 2 * (x1 - xp);
        return sum;
    }

    static double kv(List<FVDIntegrator.Keyframe> kfs, double x) {
        FVDIntegrator.Keyframe prev = kfs.get(0);
        if (x <= prev.t()) return prev.Gv();
        for (FVDIntegrator.Keyframe k : kfs) {
            if (k.t() >= x) {
                double u = k.t() - prev.t() < 1e-12 ? 0
                    : (x - prev.t()) / (k.t() - prev.t());
                return prev.Gv() + u * (k.Gv() - prev.Gv());
            }
            prev = k;
        }
        return prev.Gv();
    }

    public static void main(String[] args) {
        // a simple 5-node lane, segments of 0.8 s
        List<BezierBaker.Node> lane = nodes(new double[][]{
            {0,1},{0.8,2},{1.6,1.2},{2.4,0.5},{3.2,1} });
        // slopes: give the handles nonzero dv like a fit does
        for (int k = 0; k + 1 < lane.size(); k++) {
            lane.get(k).outDv = 0.3 * lane.get(k).outFrac * 0.8;
            lane.get(k + 1).inDv = -0.3 * lane.get(k + 1).inFrac * 0.8;
        }
        List<List<BezierBaker.Node>> tmp = new ArrayList<>();
        for (int i = 0; i < 3; i++) tmp.add(lane);
        List<FVDIntegrator.Keyframe> k0 = BezierBaker.bake(tmp, 0.05);
        System.out.println("keyframes: " + k0.size() + "  first t="
            + k0.get(0).t() + "  second t=" + k0.get(1).t()
            + "  last t=" + k0.get(k0.size()-1).t());
        double a1pre = area(k0, 0.8, 1.6);   // segment 1 (nodes 1-2)
        double a0pre = area(k0, 0.0, 0.8);   // segment 0 (nodes 0-1)

        // nudge node 1 by +0.01
        lane.get(1).val += 0.01;
        List<FVDIntegrator.Keyframe> k1 = BezierBaker.bake(tmp, 0.05);
        double a1post = area(k1, 0.8, 1.6);
        double a0post = area(k1, 0.0, 0.8);
        // predicted: dA = 0.01 * ∫g d x. For seg 0 the nudged node is
        // the RIGHT end: ∫g1 = h/2·... exactly h·(1/2) = 0.4·0.01?
        // g-basis integrals: ∫0..1 g0 du = 1/2, so dA = 0.01·0.8/2 = 0.004
        System.out.printf("seg0 dA=%.7f (pred 0.0040)  seg1 dA=%.7f (pred 0.0040)%n",
            a0post - a0pre, a1post - a1pre);

        // and does the bake mutate node objects? print node 1 handles
        BezierBaker.Node n1 = lane.get(1);
        System.out.printf("node1 after bakes: val=%.4f outFrac=%.4f outDv=%.4f inFrac=%.4f inDv=%.4f%n",
            n1.val, n1.outFrac, n1.outDv, n1.inFrac, n1.inDv);
    }
}
