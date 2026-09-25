package uvd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 48g isolation: where does the residual split live after a
 *  custom-speed reversal? */
public class Rev48gProbe {

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

    static List<List<BezierBaker.Node>> geoLanes(double p, double y,
            double r, double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{ {0, p}, {dur, p} }));
        lanes.add(nodes(new double[][]{ {0, y}, {dur, y} }));
        lanes.add(nodes(new double[][]{ {0, r}, {dur, r} }));
        return lanes;
    }

    public static void main(String[] a) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece pc = new TrackPiece(0, 21, 0, 0);
        pc.sections.add(new ForceSection(hillLanes(5), 24));
        pc.sections.add(new GeoSection(geoLanes(6, 12, 15, 3), 24));
        c.pieces.add(pc);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        for (int pass = 0; pass < 3; pass++) {
            if (!c.reconcileChainedSpeeds(r0)) break;
            r0 = TrackCompiler.compile(c, 0.01);
        }
        boolean did = c.reversePiece(0, 0.01, 0.02, 0, 15);
        System.out.println("reversed: " + did);
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        for (int pass = 0; pass < 3; pass++) {
            if (!c.reconcileChainedSpeeds(r1)) break;
            r1 = TrackCompiler.compile(c, 0.01);
        }
        double worst = 0;
        int wi = -1;
        for (int i = 0; i < r1.samples().size(); i++) {
            double d = Math.abs(r1.trueV()[i] - r1.samples().get(i).v());
            if (d > worst) { worst = d; wi = i; }
        }
        System.out.printf(Locale.US,
            "worstSplit=%.5f at i=%d/%d t=%.2f (v_d=%.4f v_t=%.4f)%n",
            worst, wi, r1.samples().size() - 1, r1.samples().get(wi).t(),
            r1.samples().get(wi).v(), r1.trueV()[wi]);
        for (TrackCompiler.Span sp : r1.spans()) {
            System.out.printf(Locale.US, "  span %d..%d%n", sp.i0(), sp.i1());
        }
        List<Section> secs = c.pieces.get(0).sections;
        for (int k = 0; k < secs.size(); k++) {
            System.out.printf(Locale.US, "  sec %d vDesign=%.4f pinned=%b%n",
                k, secs.get(k).vDesign(), secs.get(k).vPinned());
        }
    }
}
