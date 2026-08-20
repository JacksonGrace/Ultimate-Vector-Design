package coaster;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 51a isolation: force section + rollGlobal geo hold — per-column
 *  true-rate steps at the seam. */
public class Yaw51Probe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] a) {
        Coaster c = new Coaster();
        c.v0 = 26;
        TrackPiece pc = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0, 1}, {2, 0.8}, {4, 1.6}, {6, 1.4} }));
        fl.add(nodes(new double[][]{ {0, 0}, {3, 0.3}, {6, 0.4} }));
        fl.add(nodes(new double[][]{ {0, 0}, {3, 0}, {6, 25} }));
        pc.sections.add(new ForceSection(fl, 26));
        c.pieces.add(pc);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        int last = r0.samples().size() - 1;
        FVDIntegrator.Sample e = r0.samples().get(last);
        double[] er = r0.rates()[last];
        // the app's own worldRates (reported contract)
        double[] wr = Coaster.worldRates(e, er[1], er[2], er[3]);
        System.out.printf(Locale.US,
            "end rates: pit=%.3f yaw=%.3f roll=%.3f | wr: elev=%.3f "
            + "head=%.3f bank=%.3f | T.y=%.3f%n",
            er[1], er[2], er[3], wr[0], wr[1], wr[2], e.T().y());
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, er[1]}, {2, er[1]} }));
        gl.add(nodes(new double[][]{ {0, er[2]}, {2, er[2]} }));
        gl.add(nodes(new double[][]{ {0, wr[2]}, {2, wr[2]} }));
        GeoSection g = new GeoSection(gl, e.v());
        g.rollGlobal = true;
        c.pieces.get(0).sections.add(g);
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span sp = r1.spans().get(1);
        int ib = sp.i0();
        for (int col = 1; col <= 3; col++) {
            System.out.printf(Locale.US,
                "col %d: left=%.3f right=%.3f step=%.3f%n", col,
                r1.trueRates()[ib - 4][col],
                r1.trueRates()[Math.min(ib + 4, sp.i1())][col],
                r1.trueRates()[Math.min(ib + 4, sp.i1())][col]
                    - r1.trueRates()[ib - 4][col]);
        }
    }
}
