import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Bisect which output-setting combination breaks the all-settings
 *  convert (46e read 58 m). */
public class ComboProbe {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Vec3 posAtArc(List<FVDIntegrator.Sample> s, double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        double ds = s.get(hi).s() - s.get(lo).s();
        double u = ds < 1e-12 ? 0
            : Math.max(0, Math.min(1, (arc - s.get(lo).s()) / ds));
        return s.get(lo).pos().add(
            s.get(hi).pos().sub(s.get(lo).pos()).scale(u));
    }

    static double dev(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        double sA = a.get(a.size() - 1).s(), sB = b.get(b.size() - 1).s();
        double worst = 0;
        for (int k = 0; k <= 200; k++) {
            double f = k / 200.0;
            worst = Math.max(worst,
                posAtArc(a, f * sA).sub(posAtArc(b, f * sB)).length());
        }
        return worst;
    }

    public static void main(String[] args) {
        List<List<BezierBaker.Node>> g5 = new ArrayList<>();
        g5.add(nodes(new double[][]{ {0,1},{2,1.8},{5,0.8},{8,1} }));
        g5.add(nodes(new double[][]{ {0,0},{3,0.5},{8,0.2} }));
        g5.add(nodes(new double[][]{ {0,0},{4,20},{6,-10},{8,0} }));
        Object[][] combos = {
            {"geo TIME loc/loc perS ", Section.Domain.TIME, false, false, false},
            {"geo TIME loc/loc perM ", Section.Domain.TIME, false, false, true},
            {"geo DIST loc/loc perS ", Section.Domain.DISTANCE, false, false, false},
            {"geo DIST loc/loc perM ", Section.Domain.DISTANCE, false, false, true},
            {"geo TIME rG   perS    ", Section.Domain.TIME, true, false, false},
            {"geo TIME rG   perM    ", Section.Domain.TIME, true, false, true},
            {"geo TIME ypG  perS    ", Section.Domain.TIME, false, true, false},
            {"geo TIME ypG  perM    ", Section.Domain.TIME, false, true, true},
            {"geo DIST rG+ypG perS  ", Section.Domain.DISTANCE, true, true, false},
            {"geo DIST rG+ypG perM  ", Section.Domain.DISTANCE, true, true, true},
        };
        for (Object[] c : combos) {
            Coaster ec = Coaster.singleForce(g5, 24, 0, 21, 0);
            TrackCompiler.Result before = TrackCompiler.compile(ec, dt);
            boolean ok = ec.convertWholeSection(0, 0, true, dt, 0.05, 0,
                (Section.Domain) c[1], (Boolean) c[2], (Boolean) c[3],
                (Boolean) c[4]);
            TrackCompiler.Result after = TrackCompiler.compile(ec, dt);
            System.out.printf(java.util.Locale.US, "%s ok=%b dev=%.4f m%n",
                c[0], ok, dev(before.samples(), after.samples()));
        }
    }
}
