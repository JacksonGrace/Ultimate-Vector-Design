import uvd.BezierBaker;

import java.util.ArrayList;
import java.util.List;

/** MAX_FRAC is now 1.5: handles can reach past the neighbor, folding the
 *  cubic's time axis. The baked polyline must stay strictly monotonic and
 *  finite, and evalAt/splitSegment must not blow up. */
public class ExtremeFracCheck {
    public static void main(String[] args) {
        int bad = 0;
        double[][] shapes = {
            { 1.5, 2.0, 1.5, -2.0 },   // both handles at the cap, big dvs
            { 1.5, 0.0, 0.05, 0.0 },
            { 1.0, -3.0, 1.2, 3.0 },
            { 0.9, 1.0, 1.5, 1.0 },
        };
        for (double[] s : shapes) {
            BezierBaker.Node a = new BezierBaker.Node(0, 0);
            BezierBaker.Node b = new BezierBaker.Node(1, 1);
            a.outFrac = s[0]; a.outDv = s[1];
            b.inFrac = s[2];  b.inDv = s[3];
            List<BezierBaker.Node> lane = new ArrayList<>(List.of(a, b));

            List<double[]> poly = BezierBaker.polyline(lane);
            for (int i = 0; i < poly.size(); i++) {
                if (!Double.isFinite(poly.get(i)[0]) || !Double.isFinite(poly.get(i)[1])) bad++;
                if (i > 0 && poly.get(i)[0] <= poly.get(i - 1)[0]) bad++;
            }
            for (int i = 1; i < 50; i++) {
                if (!Double.isFinite(BezierBaker.evalAt(lane, i / 50.0))) bad++;
            }
            BezierBaker.Node mid = BezierBaker.splitSegment(a, b, 0.5);
            if (!Double.isFinite(mid.t) || !Double.isFinite(mid.val)
                    || !Double.isFinite(mid.inDv) || !Double.isFinite(mid.outDv)) bad++;
        }
        System.out.println("violations: " + bad + (bad == 0 ? "  PASS" : "  FAIL"));
    }
}
