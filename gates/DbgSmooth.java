import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Where exactly do the felt/rate steps sit around a smoothed strict
 *  seam, and what do the rate columns hold? */
public class DbgSmooth {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0, 1}, {dur * 0.2, 0.6}, {dur * 0.5, 1.4},
            {dur * 0.8, 0.6}, {dur, 1}}));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return lanes;
    }

    static TrackCompiler.Result settle(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int i = 0; i < 6 && c.reconcileChainedSpeeds(r); i++) {
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    static Coaster fixture(double len) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        a.sections.add(new ForceSection(hillLanes(5), 24));
        a.sections.add(new ForceSection(hillLanes(4), 24));
        c.pieces.add(a);
        settle(c);
        c.makeSeamStrict(0, 1, 0.01);
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(1).val -= 0.4;
        settle(c);
        c.pieces.get(1).sSmoothLen = len;
        return c;
    }

    public static void main(String[] args) {
        for (double len : new double[]{ 0, 8 }) {
            TrackCompiler.Result r =
                TrackCompiler.compile(fixture(len), 0.01);
            int iSeam = r.spans().get(0).i1();
            double sSeam = r.samples().get(iSeam).s();
            System.out.printf("== len=%.0f  iSeam=%d  rates cols=%d ==%n",
                len, iSeam, r.rates()[0].length);
            double worstTf = 0; int worstI = -1;
            for (int i = 1; i < r.samples().size(); i++) {
                double d = Math.abs(r.trueFelt()[i][0]
                    - r.trueFelt()[i - 1][0]);
                if (Math.abs(r.samples().get(i).s() - sSeam) < 5
                        && d > worstTf) { worstTf = d; worstI = i; }
            }
            System.out.printf("worst trueFelt step %.3f at i=%d "
                + "(seam%+d, s-sSeam=%.2f)%n", worstTf, worstI,
                worstI - iSeam, r.samples().get(worstI).s() - sSeam);
            for (int i = iSeam - 3; i <= iSeam + 4; i++) {
                FVDIntegrator.Sample sm = r.samples().get(i);
                System.out.printf(
                    "i=%d%+d  v=%.2f trueV=%.2f Gv=%.3f tF=%.3f "
                    + "rates[0..2]=%.1f %.1f %.1f  tR[0..2]=%.1f %.1f %.1f%n",
                    i, i - iSeam, sm.v(), r.trueV()[i], sm.Gv(),
                    r.trueFelt()[i][0], r.rates()[i][0], r.rates()[i][1],
                    r.rates()[i][2], r.trueRates()[i][0],
                    r.trueRates()[i][1], r.trueRates()[i][2]);
            }
            if (len > 0) {
                // downstream translation decomposition: along the
                // window chord vs perpendicular to it
                TrackCompiler.Result rOff =
                    TrackCompiler.compile(fixture(0), 0.01);
                int iEnd = rOff.samples().size() - 1;
                Vec3 dp = r.samples().get(iEnd).pos()
                    .sub(rOff.samples().get(iEnd).pos());
                // window chord ~ straight through the seam
                Vec3 ca = rOff.samples().get(iSeam - 40).pos();
                Vec3 cb = rOff.samples().get(iSeam + 40).pos();
                Vec3 ch = cb.sub(ca).normalize();
                double par = dp.dot(ch);
                double perp = dp.sub(ch.scale(par)).length();
                System.out.printf(
                    "downstream dp: |dp|=%.4f  along-chord=%.4f  "
                    + "perp=%.4f m%n", dp.length(), par, perp);
            }
            if (worstI > 2 && Math.abs(worstI - iSeam) > 4) {
                for (int i = worstI - 2; i <= worstI + 2; i++) {
                    FVDIntegrator.Sample sm = r.samples().get(i);
                    System.out.printf(
                        "  near worst: i=%d%+d  v=%.2f Gv=%.3f tF=%.3f%n",
                        i, i - iSeam, sm.v(), sm.Gv(),
                        r.trueFelt()[i][0]);
                }
            }
        }
    }
}
