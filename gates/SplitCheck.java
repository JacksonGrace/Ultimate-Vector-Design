import uvd.BezierBaker;

import java.util.ArrayList;
import java.util.List;

/** De Casteljau split must not change the curve: build an angled segment,
 *  split it at an arbitrary time, compare eval before/after everywhere. */
public class SplitCheck {
    public static void main(String[] args) {
        double worst = 0;
        // several segment shapes, several split points
        double[][] shapes = {
            // outFrac, outDv, inFrac, inDv, endVal
            { 0.42,  0.35, 0.27, -0.15, 1.0 },
            { 0.333, 0.0,  0.333, 0.0,  2.5 },   // flat-handled ramp
            { 0.80,  1.20, 0.70,  0.90, -1.0 },  // aggressive fold-ish
            { 0.05, -0.40, 0.90,  0.05, 0.3 },
        };
        for (double[] s : shapes) {
            for (double tc = 0.3; tc < 2.0; tc += 0.37) {
                BezierBaker.Node a0 = new BezierBaker.Node(0, 0);
                BezierBaker.Node b0 = new BezierBaker.Node(2, s[4]);
                a0.outFrac = s[0]; a0.outDv = s[1];
                b0.inFrac = s[2];  b0.inDv = s[3];
                List<BezierBaker.Node> orig = new ArrayList<>(List.of(a0, b0));

                BezierBaker.Node a = a0.copy(), b = b0.copy();
                BezierBaker.Node mid = BezierBaker.splitSegment(a, b, tc);
                List<BezierBaker.Node> split = new ArrayList<>(List.of(a, mid, b));

                for (int i = 1; i < 200; i++) {
                    double t = 2.0 * i / 200;
                    double err = Math.abs(BezierBaker.evalAt(orig, t) - BezierBaker.evalAt(split, t));
                    worst = Math.max(worst, err);
                }
            }
        }
        System.out.printf(java.util.Locale.US, "max |orig - split| over all cases = %.6f%n", worst);
        System.out.println(worst < 0.01 ? "PASS" : "FAIL");
    }
}
