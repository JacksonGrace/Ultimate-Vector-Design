import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe for v18.37 (D209): B on an influence-surface EDGE node should
 * set the delta so the RESULTING graphs (base + delta) line up with
 * the neighbor at the seam. The probe replicates the app's target
 * arithmetic (neighborRate − base) at the seam and verifies that
 * applying it to the delta lane's start node closes the resulting
 * pitch-rate step, value AND slope, at the boundary.
 */
public class InflMatchProbe {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster fixture() {
        // A: a pull-up force section whose end approaches with a live
        // pitch rate; B: flat 1 g — the resulting pitch rate steps at
        // the seam until the influence supplies it.
        List<List<BezierBaker.Node>> la = new ArrayList<>();
        la.add(nodes(new double[][]{ {0, 1}, {2.0, 2.2}, {4, 1.6} }));
        la.add(nodes(new double[][]{ {0, 0}, {4, 0} }));
        la.add(nodes(new double[][]{ {0, 0}, {4, 0} }));
        List<List<BezierBaker.Node>> lb = new ArrayList<>();
        lb.add(nodes(new double[][]{ {0, 1}, {4, 1} }));
        lb.add(nodes(new double[][]{ {0, 0}, {4, 0} }));
        lb.add(nodes(new double[][]{ {0, 0}, {4, 0} }));
        Coaster c = Coaster.singleForce(la, 26, 0, 21, 0);
        c.pieces.get(0).sections.add(new ForceSection(lb, 26));
        return c;
    }

    public static void main(String[] args) {
        double dt = 0.01;
        Coaster c = fixture();
        TrackCompiler.Result r0 = TrackCompiler.compile(c, dt);
        LaneSection b = (LaneSection) c.pieces.get(0).sections.get(1);
        TrackCompiler.Span spB = r0.spans().get(1);
        double durB = spB.t1() - spB.t0();
        Coaster.enterInfluence(b, durB);
        r0 = TrackCompiler.compile(c, dt);   // baseCurves populated
        spB = r0.spans().get(1);
        TrackCompiler.Span spA = r0.spans().get(0);

        // the un-matched seam: resulting pitch rate steps A -> B
        double rateA = r0.rates()[spA.i1()][1];
        double step0 = Math.abs(r0.rates()[spB.i0()][1] - rateA);

        // the app's target arithmetic (sampled tail, D209): value +
        // slope extrapolated to the seam from ~3 samples inside A,
        // minus the base row at the delta's t=0
        double tB = spA.t1();
        int i1 = Math.max(0, spB.i0() - 1 - 3);
        int i0 = Math.max(0, i1 - 5);
        double span = r0.rates()[i1][0] - r0.rates()[i0][0];
        double slope = span < 1e-9 ? 0
            : (r0.rates()[i1][1] - r0.rates()[i0][1]) / span;
        double val = r0.rates()[i1][1] - slope * (r0.rates()[i1][0] - tB);
        double dVal = val - b.baseValAt(0, 0);
        double dSlope = slope - b.baseSlopeAt(0, 0);

        List<BezierBaker.Node> dl = b.deltaLanes.get(0);
        BezierBaker.Node n0 = dl.get(0);
        n0.val = dVal;
        n0.outDv = dSlope * BezierBaker.effOutDt(n0, dl.get(1));

        TrackCompiler.Result r1 = TrackCompiler.compile(c, dt);
        double step1 = Math.abs(r1.rates()[spB.i0()][1]
            - r1.rates()[spA.i1()][1]);
        check(step0 > 3 && step1 < Math.max(0.35, step0 / 20),
            String.format("matched delta closes the seam rate step "
                + "%.2f -> %.3f deg/s", step0, step1));

        // slope continuity: d(rate)/dt just inside B vs just inside A
        double slA = (r1.rates()[spA.i1()][1] - r1.rates()[spA.i1() - 4][1])
            / (r1.rates()[spA.i1()][0] - r1.rates()[spA.i1() - 4][0]);
        double slB = (r1.rates()[spB.i0() + 4][1] - r1.rates()[spB.i0()][1])
            / (r1.rates()[spB.i0() + 4][0] - r1.rates()[spB.i0()][0]);
        check(Math.abs(slB - slA) < Math.max(1.5, Math.abs(slA) * 0.35),
            String.format("slopes join too: %.2f vs %.2f deg/s^2",
                slA, slB));

        // resulting == base + delta EXACTLY still holds with the
        // matched node (the two-pass contract the match rides on)
        double worst = 0;
        for (int i = spB.i0() + 2; i <= spB.i1() - 2; i++) {
            double t = r1.rates()[i][0] - spB.t0();
            double want = b.baseValAt(0, t)
                + BezierBaker.evalAt(b.deltaLanes.get(0), t);
            worst = Math.max(worst, Math.abs(r1.rates()[i][1] - want));
        }
        check(worst < 0.6, String.format(
            "resulting == base + delta through the span (worst %.3f deg/s)",
            worst));

        System.out.println(failures == 0 ? "ALL INFL-MATCH CHECKS PASS"
            : failures + " FAILURES");
    }
}
