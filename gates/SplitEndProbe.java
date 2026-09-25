import uvd.*;
import java.util.*;

/** D147 probe: split-at-node vs unsplit end-position difference.
 *  Pre-fix: every time-section end truncated to the accumulated-dt
 *  grid (up to one dt of authored profile lost per seam) — a split
 *  introduced a seam and the end position moved (~cm). Post-fix the
 *  closing step lands exactly on the authored end, so the split must
 *  match the unsplit compile to integration noise. */
public class SplitEndProbe {

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

    /** A straight sloped Gv ramp — the user's "angled portion". */
    static List<List<BezierBaker.Node>> rampLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        List<BezierBaker.Node> l0 = nodes(new double[][]{ {0, 0.8}, {dur, 1.6} });
        // collinear handles = exactly the chord (an angled straight line)
        double slope = (1.6 - 0.8) / dur;
        l0.get(0).outDv = slope * l0.get(0).outFrac * dur;
        l0.get(1).inDv  = -slope * l0.get(1).inFrac * dur;
        lanes.add(l0);
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return lanes;
    }

    static Coaster oneForce(List<List<BezierBaker.Node>> lanes, double v0) {
        Coaster c = new Coaster();
        c.v0 = v0;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(lanes, v0));
        c.pieces.add(p);
        return c;
    }

    static Vec3 endPos(TrackCompiler.Result r) {
        return r.samples().get(r.samples().size() - 1).pos();
    }

    static double endT(TrackCompiler.Result r) {
        return r.samples().get(r.samples().size() - 1).t();
    }

    public static void main(String[] args) {
        double dt = 0.01;

        // ---- A: hill fixture (curved lanes), round 5.0 s end ----
        Coaster a1 = oneForce(hillLanes(5), 24);
        TrackCompiler.Result ra1 = TrackCompiler.compile(a1, dt);
        Coaster a2 = oneForce(hillLanes(5), 24);
        boolean okSplit = a2.splitSectionAt(0, 0, 2.13, dt);
        TrackCompiler.Result ra2 = TrackCompiler.compile(a2, dt);
        System.out.println("A hill  split ok=" + okSplit
            + "  endT unsplit=" + endT(ra1)
            + "  endT split=" + endT(ra2)
            + "  |dEnd|=" + endPos(ra1).sub(endPos(ra2)).length() + " m");

        // ---- B: angled straight ramp, non-round end 4.37 s ----
        Coaster b1 = oneForce(rampLanes(4.37), 26);
        TrackCompiler.Result rb1 = TrackCompiler.compile(b1, dt);
        Coaster b2 = oneForce(rampLanes(4.37), 26);
        boolean okSplitB = b2.splitSectionAt(0, 0, 1.618, dt);
        TrackCompiler.Result rb2 = TrackCompiler.compile(b2, dt);
        System.out.println("B ramp  split ok=" + okSplitB
            + "  endT unsplit=" + endT(rb1) + " (authored 4.37)"
            + "  endT split=" + endT(rb2)
            + "  |dEnd|=" + endPos(rb1).sub(endPos(rb2)).length() + " m");

        // ---- C: end landing — last sample exactly at authored end ----
        System.out.println("C landing  A endT==5.0: " + (endT(ra1) == 5.0)
            + "  B endT==4.37: " + (endT(rb1) == 4.37));

        // ---- D: geo section end landing + split ----
        Coaster d1 = new Coaster();
        d1.v0 = 24;
        TrackPiece dp = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, 6}, {3, -8} }));
        gl.add(nodes(new double[][]{ {0, 10}, {3, 10} }));
        gl.add(nodes(new double[][]{ {0, 0}, {3, 0} }));
        dp.sections.add(new GeoSection(gl, 24));
        d1.pieces.add(dp);
        TrackCompiler.Result rd1 = TrackCompiler.compile(d1, dt);

        Coaster d2 = new Coaster();
        d2.v0 = 24;
        TrackPiece dp2 = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl2 = new ArrayList<>();
        gl2.add(nodes(new double[][]{ {0, 6}, {3, -8} }));
        gl2.add(nodes(new double[][]{ {0, 10}, {3, 10} }));
        gl2.add(nodes(new double[][]{ {0, 0}, {3, 0} }));
        dp2.sections.add(new GeoSection(gl2, 24));
        d2.pieces.add(dp2);
        boolean okSplitD = d2.splitSectionAt(0, 0, 1.234, dt);
        TrackCompiler.Result rd2 = TrackCompiler.compile(d2, dt);
        System.out.println("D geo   split ok=" + okSplitD
            + "  endT unsplit=" + endT(rd1)
            + "  endT split=" + endT(rd2)
            + "  |dEnd|=" + endPos(rd1).sub(endPos(rd2)).length() + " m");
    }
}
