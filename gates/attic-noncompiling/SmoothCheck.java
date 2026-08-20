import coaster.BezierBaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Headless checks for BezierBaker.smoothValues. */
public class SmoothCheck {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) { failures++; System.out.println("FAIL: " + what); }
    }

    static List<BezierBaker.Node> lane(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        // UNEVEN spacing on purpose: chord targets are time-weighted
        double[][] shape = { {0, 1}, {1, 3}, {1.5, -2}, {4, 5}, {5, 0} };

        // strength 1: one pass lands each selected node EXACTLY on the
        // chord of its (pre-pass) neighbors
        List<BezierBaker.Node> l1 = lane(shape);
        BezierBaker.smoothValues(l1, Set.of(2), 1.0);
        double u = (1.5 - 1) / (4 - 1);
        double want = 3 + (5 - 3) * u;
        check(Math.abs(l1.get(2).val - want) < 1e-12, "strength-1 exact chord");
        check(l1.get(1).val == 3 && l1.get(3).val == 5, "unselected untouched");

        // order independence: all interior nodes at once uses PRE-pass values
        List<BezierBaker.Node> l2 = lane(shape);
        BezierBaker.smoothValues(l2, Set.of(1, 2, 3), 0.5);
        // node 1 target from ORIGINAL 0 & 2 values (1, -2):
        double u1 = (1 - 0) / (1.5 - 0);
        double t1 = 1 + (-2 - 1) * u1;
        check(Math.abs(l2.get(1).val - (3 + 0.5 * (t1 - 3))) < 1e-12, "pre-pass values used");

        // endpoints never move even if given
        List<BezierBaker.Node> l3 = lane(shape);
        BezierBaker.smoothValues(l3, Set.of(0, 4), 1.0);
        check(l3.get(0).val == 1 && l3.get(4).val == 0, "endpoints fixed");

        // repeated passes converge to the straight line through endpoints
        List<BezierBaker.Node> l4 = lane(shape);
        for (int k = 0; k < 500; k++) BezierBaker.smoothValues(l4, Set.of(1, 2, 3), 0.5);
        double worst = 0;
        for (int i = 1; i <= 3; i++) {
            BezierBaker.Node n = l4.get(i);
            double lu = (n.t - 0) / (5 - 0);
            double line = 1 + (0 - 1) * lu;
            worst = Math.max(worst, Math.abs(n.val - line));
        }
        check(worst < 1e-9, "converges to endpoint chord (worst " + worst + ")");

        // times and handles untouched
        List<BezierBaker.Node> l5 = lane(shape);
        l5.get(2).outDv = 0.7; l5.get(2).outFrac = 0.42;
        BezierBaker.smoothValues(l5, Set.of(2), 0.5);
        check(l5.get(2).t == 1.5 && l5.get(2).outDv == 0.7 && l5.get(2).outFrac == 0.42,
            "t/handles untouched");

        System.out.println(failures == 0 ? "ALL SMOOTH CHECKS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
