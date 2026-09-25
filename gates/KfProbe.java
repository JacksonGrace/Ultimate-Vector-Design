package uvd;

import java.util.ArrayList;
import java.util.List;

/** Dump the baked roll keyframes + bankAt reads for the ramp lane. */
public class KfProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        List<List<BezierBaker.Node>> gb = new ArrayList<>();
        gb.add(nodes(new double[][]{ {0,0},{6,0} }));
        gb.add(nodes(new double[][]{ {0,0},{6,0} }));
        gb.add(nodes(new double[][]{ {0,0},{2,60},{6,60} }));
        List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(gb, 0.05);
        System.out.println("first 8 keyframes (t, roll):");
        for (int i = 0; i < 8 && i < kfs.size(); i++) {
            System.out.printf(java.util.Locale.US, "  %.3f  %.4f%n",
                kfs.get(i).t(), kfs.get(i).rollRateDeg());
        }
        System.out.println("bankAt / true smoothstep:");
        for (double x = 0; x <= 0.13; x += 0.01) {
            double u = x / 2;
            double tru = 60 * u * u * (3 - 2 * u);
            System.out.printf(java.util.Locale.US,
                "  x=%.2f  bankAt=%.4f  true=%.4f%n",
                x, FVDIntegrator.bankAt(kfs, x), tru);
        }
    }
}
