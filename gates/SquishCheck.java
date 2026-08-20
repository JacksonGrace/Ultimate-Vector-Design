import coaster.BezierBaker;

import java.util.ArrayList;
import java.util.List;

/** The point of fractional handles: moving a neighbor closer must squish
 *  the curve horizontally without changing its shape. Two-node segment,
 *  same fractions, second node at t=2 vs t=1: v2(u) must equal v1(2u). */
public class SquishCheck {
    static List<BezierBaker.Node> seg(double endT) {
        BezierBaker.Node a = new BezierBaker.Node(0, 0);
        BezierBaker.Node b = new BezierBaker.Node(endT, 1);
        a.outFrac = 0.42; a.outDv = 0.35;   // deliberately non-default shape
        b.inFrac = 0.27;  b.inDv = -0.15;
        List<BezierBaker.Node> l = new ArrayList<>();
        l.add(a); l.add(b);
        return l;
    }

    public static void main(String[] args) {
        List<BezierBaker.Node> wide = seg(2.0), narrow = seg(1.0);
        double maxErr = 0;
        for (int i = 1; i < 100; i++) {
            double u = i / 100.0;
            double err = Math.abs(BezierBaker.evalAt(wide, 2 * u) - BezierBaker.evalAt(narrow, u));
            maxErr = Math.max(maxErr, err);
        }
        System.out.printf(java.util.Locale.US, "max |wide(2u) - narrow(u)| = %.6f%n", maxErr);
        System.out.println(maxErr < 0.005 ? "PASS" : "FAIL");
    }
}
