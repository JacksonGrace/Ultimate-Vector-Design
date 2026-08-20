import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe for the geo->force seam matching bug (v13.3):
 *  - a GEO section ending with nonzero pitch/yaw/roll rates
 *  - a FORCE hold section with a DIFFERENT design speed (simulating the
 *    normal post-edit state: '+' froze vDesign, later edits moved the
 *    upstream design end speed)
 * Replicates the editor's B-matching in both the OLD form (copy the
 * neighbor's felt G / raw inside-sample) and the NEW form (extrapolated
 * rate targets converted at the active side's seam design speed), and
 * alternates sides like the user's Shift+B ping-pong.
 */
public class SeamProbe {

    static final double DT = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster doc;
    static GeoSection geo;
    static ForceSection force;

    static void build() {
        doc = new Coaster();
        doc.v0 = 26;
        TrackPiece piece = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, 0}, {1.0, 14}, {2.0, 12} }));  // pitch
        gl.add(nodes(new double[][]{ {0, 0}, {2.0, 5} }));              // yaw
        gl.add(nodes(new double[][]{ {0, 0}, {1.5, 20}, {2.0, 20} })); // roll
        geo = new GeoSection(gl, 26);
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0, 1.35}, {1.5, 1.1} }));  // Gv
        fl.add(nodes(new double[][]{ {0, 0.10}, {1.5, 0.1} }));  // Gl
        fl.add(nodes(new double[][]{ {0, 20}, {1.5, 0} }));      // roll
        force = new ForceSection(fl, 20);   // deliberately != geo design end
        piece.sections.add(geo);
        piece.sections.add(force);
        doc.pieces.add(piece);
    }

    static TrackCompiler.Result comp() {
        return TrackCompiler.compile(doc, DT);
    }

    static int idxAt(double[][] rr, double t) {
        int lo = 0, hi = rr.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (rr[mid][0] <= t) lo = mid; else hi = mid;
        }
        return t - rr[lo][0] <= rr[hi][0] - t ? lo : hi;
    }

    /** {seam time, pitchA, pitchB, rollA, rollB, feltGvA, feltGvB, vA, vB} */
    static double[] seamState(TrackCompiler.Result r) {
        TrackCompiler.Span spG = r.spans().get(0);
        double tB = spG.t1();
        double[][] rr = r.rates();
        int ia = idxAt(rr, tB - 3 * DT), ib = idxAt(rr, tB + 3 * DT);
        return new double[]{ tB,
            rr[ia][1], rr[ib][1],
            rr[ia][3], rr[ib][3],
            r.samples().get(ia).Gv(), r.samples().get(ib).Gv(),
            r.samples().get(ia).v(), r.samples().get(ib).v() };
    }

    // ---- OLD matching ----------------------------------------------------

    /** force side, lane 0 (Gv) + lane 2 (roll authored copy). */
    static void oldMatchForce(TrackCompiler.Result r) {
        double[][] rr = r.rates();
        double tB = r.spans().get(0).t1();
        int i1 = idxAt(rr, tB - 3 * DT);
        int i0 = Math.max(i1 - 5, 0);
        // OLD: copy the neighbor's felt Gv sample verbatim (no extrapolation)
        double val = r.samples().get(i1).Gv();
        double span = rr[i1][0] - rr[i0][0];
        double slope = span < 1e-9 ? 0
            : (r.samples().get(i1).Gv() - r.samples().get(i0).Gv()) / span;
        BezierBaker.Node n = force.lanes.get(0).get(0);
        n.val = val;
        n.outDv = slope * BezierBaker.effOutDt(n, force.lanes.get(0).get(1));
        // roll: authored copy (unchanged old and new)
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        force.lanes.get(2).get(0).val = gr.get(gr.size() - 1).val;
    }

    /** geo side, lane 0 (pitch rate from the force side's resulting rates). */
    static void oldMatchGeo(TrackCompiler.Result r) {
        double[][] rr = r.rates();
        double tB = r.spans().get(0).t1();
        int i0 = idxAt(rr, tB + 3 * DT);
        int i1 = Math.min(i0 + 5, rr.length - 1);
        double val = rr[i0][1];   // OLD: no extrapolation
        double span = rr[i1][0] - rr[i0][0];
        double slope = span < 1e-9 ? 0 : (rr[i1][1] - rr[i0][1]) / span;
        List<BezierBaker.Node> gp = geo.lanes.get(0);
        BezierBaker.Node n = gp.get(gp.size() - 1);
        n.val = val;
        n.inDv = slope * BezierBaker.effInDt(gp.get(gp.size() - 2), n);
        // roll authored copy
        List<BezierBaker.Node> groll = geo.lanes.get(2);
        groll.get(groll.size() - 1).val = force.lanes.get(2).get(0).val;
    }

    // ---- NEW matching ----------------------------------------------------

    static void newMatchForce(TrackCompiler.Result r) {
        double[][] rr = r.rates();
        TrackCompiler.Span spG = r.spans().get(0);
        double tB = spG.t1();
        int i1 = idxAt(rr, tB - 3 * DT);
        int i0 = Math.max(i1 - 5, 0);
        int at = i1;
        double span = rr[i1][0] - rr[i0][0];
        double slopeR = span < 1e-9 ? 0 : (rr[i1][1] - rr[i0][1]) / span;
        double rateDeg = rr[at][1] - slopeR * (rr[at][0] - tB);
        double vSeam = force.vDesign;          // active entry = vDesign exactly
        TrackPose seam = spG.exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double w = Math.toRadians(rateDeg);
        double val = w * vSeam / FVDIntegrator.G0 + U.y();
        Vec3 omega = U.scale(Math.toRadians(rr[at][2]))
            .add(R.scale(-Math.toRadians(rr[at][1])))
            .add(T.scale(Math.toRadians(rr[at][3])));
        double slope = Math.toRadians(slopeR) * vSeam / FVDIntegrator.G0
            - w * T.y() + omega.cross(U).y();
        BezierBaker.Node n = force.lanes.get(0).get(0);
        n.val = val;
        n.outDv = slope * BezierBaker.effOutDt(n, force.lanes.get(0).get(1));
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        force.lanes.get(2).get(0).val = gr.get(gr.size() - 1).val;
    }

    static void newMatchGeo(TrackCompiler.Result r) {
        double[][] rr = r.rates();
        double tB = r.spans().get(0).t1();
        int i0 = idxAt(rr, tB + 3 * DT);
        int i1 = Math.min(i0 + 5, rr.length - 1);
        double span = rr[i1][0] - rr[i0][0];
        double slope = span < 1e-9 ? 0 : (rr[i1][1] - rr[i0][1]) / span;
        double val = rr[i0][1] - slope * (rr[i0][0] - tB);   // extrapolated
        List<BezierBaker.Node> gp = geo.lanes.get(0);
        BezierBaker.Node n = gp.get(gp.size() - 1);
        n.val = val;
        n.inDv = slope * BezierBaker.effInDt(gp.get(gp.size() - 2), n);
        List<BezierBaker.Node> groll = geo.lanes.get(2);
        groll.get(groll.size() - 1).val = force.lanes.get(2).get(0).val;
    }

    static void run(String name, boolean useNew) {
        build();
        System.out.println("=== " + name + " ===");
        TrackCompiler.Result r = comp();
        double[] s0 = seamState(r);
        System.out.printf("  start: pitch %.2f | %.2f  roll %.2f | %.2f  Gv %.3f | %.3f  v %.2f | %.2f%n",
            s0[1], s0[2], s0[3], s0[4], s0[5], s0[6], s0[7], s0[8]);
        for (int round = 1; round <= 8; round++) {
            if (useNew) newMatchForce(r); else oldMatchForce(r);
            r = comp();
            if (useNew) newMatchGeo(r); else oldMatchGeo(r);
            r = comp();
            double[] s = seamState(r);
            System.out.printf(
                "  r%d: pitch %.3f | %.3f (step %+.3f)  roll %.3f | %.3f  Gv-node0 %.4f  geo-pitch-end %.3f%n",
                round, s[1], s[2], s[2] - s[1], s[3], s[4],
                force.lanes.get(0).get(0).val,
                geo.lanes.get(0).get(geo.lanes.get(0).size() - 1).val);
        }
    }

    public static void main(String[] args) {
        run("OLD matching (felt-G copy, no extrapolation)", false);
        run("NEW matching (rate targets at own seam design speed)", true);
    }
}
