import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * v13.4 diagnosis: banked-seam Shift+B ping-pong (user: lateral G + yaw
 * shift on alternating presses when the seam is banked / edge handles
 * sloped; both geo->force and force->geo). Replicates the v13.3 editor
 * matching EXACTLY on all three lanes in both directions and watches
 * the edge values round by round.
 */
public class SeamProbe2 {

    static final double DT = 0.01;
    static final double G0 = FVDIntegrator.G0;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster doc;
    static GeoSection geo;
    static ForceSection force;

    /** bank ~40 deg at the seam (roll 25 deg/s * 1.6 s), sloped pitch/yaw
     *  at the seam, force section with its own slopes. */
    static void build(double vGeo, double vForce) {
        doc = new Coaster();
        doc.v0 = vGeo;
        TrackPiece piece = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, 0}, {1.0, 16}, {1.6, 12} }));  // pitch, sloped at end
        gl.add(nodes(new double[][]{ {0, 0}, {1.6, 8} }));              // yaw, sloped
        gl.add(nodes(new double[][]{ {0, 25}, {1.6, 25} }));            // roll: 40 deg bank at seam
        geo = new GeoSection(gl, vGeo);
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0, 1.30}, {1.5, 0.9} }));   // Gv sloped
        fl.add(nodes(new double[][]{ {0, 0.20}, {1.5, -0.2} })); // Gl sloped
        fl.add(nodes(new double[][]{ {0, 25}, {1.5, 0} }));      // roll decays
        force = new ForceSection(fl, vForce);
        piece.sections.add(geo);
        piece.sections.add(force);
        doc.pieces.add(piece);
    }

    static TrackCompiler.Result comp() { return TrackCompiler.compile(doc, DT); }

    static int idxAt(double[][] rr, double t) {
        int lo = 0, hi = rr.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (rr[mid][0] <= t) lo = mid; else hi = mid;
        }
        return t - rr[lo][0] <= rr[hi][0] - t ? lo : hi;
    }

    // ---- replicate v13.3 boundaryState compiled branch ----

    /** {val, slope} of resulting rate comp (1=pitch 2=yaw 3=roll),
     *  extrapolated to the seam from inside side dir (+1 next / -1 prev). */
    static double[] rateAtSeam(TrackCompiler.Result r, double tB, int comp, int dir) {
        double[][] rr = r.rates();
        int i0, i1, at;
        if (dir > 0) {
            i0 = idxAt(rr, tB + 3 * DT);
            i1 = Math.min(i0 + 5, rr.length - 1);
            at = i0;
        } else {
            i1 = idxAt(rr, tB - 3 * DT);
            i0 = Math.max(i1 - 5, 0);
            at = i1;
        }
        double span = rr[i1][0] - rr[i0][0];
        double slope = span < 1e-9 ? 0 : (rr[i1][comp] - rr[i0][comp]) / span;
        double val = rr[at][comp] - slope * (rr[at][0] - tB);
        return new double[]{ val, slope, at };
    }

    /** v13.3 forceTargetFromRates for the force side's START edge. */
    static double[] forceTarget(TrackCompiler.Result r, int laneIdx) {
        TrackCompiler.Span spG = r.spans().get(0);
        double tB = spG.t1();
        int comp = laneIdx + 1;
        double[] rs = rateAtSeam(r, tB, comp, -1);
        double rateDeg = rs[0], slopeR = rs[1];
        int at = (int) rs[2];
        double[][] rr = r.rates();
        double vSeam = force.vDesign;
        TrackPose seam = spG.exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double w = Math.toRadians(rateDeg);
        double val = w * vSeam / G0 + (laneIdx == 0 ? U.y() : R.y());
        Vec3 omega = U.scale(Math.toRadians(rr[at][2]))
            .add(R.scale(-Math.toRadians(rr[at][1])))
            .add(T.scale(Math.toRadians(rr[at][3])));
        Vec3 axis = laneIdx == 0 ? U : R;
        double slope = Math.toRadians(slopeR) * vSeam / G0
            - w * T.y() + omega.cross(axis).y();
        return new double[]{ val, slope };
    }

    static void matchForceSide(TrackCompiler.Result r) {
        for (int L = 0; L < 2; L++) {
            double[] vs = forceTarget(r, L);
            List<BezierBaker.Node> ln = force.lanes.get(L);
            BezierBaker.Node n = ln.get(0);
            n.val = vs[0];
            n.outDv = vs[1] * BezierBaker.effOutDt(n, ln.get(1));
        }
        // roll: authored copy (value + slope), boundaryState authored branch
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        BezierBaker.Node last = gr.get(gr.size() - 1);
        double inDt = BezierBaker.effInDt(gr.get(gr.size() - 2), last);
        double slope = Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt;
        List<BezierBaker.Node> fr = force.lanes.get(2);
        fr.get(0).val = last.val;
        fr.get(0).outDv = slope * BezierBaker.effOutDt(fr.get(0), fr.get(1));
    }

    static void matchGeoSide(TrackCompiler.Result r) {
        TrackCompiler.Span spG = r.spans().get(0);
        double tB = spG.t1();
        for (int L = 0; L < 2; L++) {
            double[] rs = rateAtSeam(r, tB, L + 1, +1);
            List<BezierBaker.Node> ln = geo.lanes.get(L);
            BezierBaker.Node n = ln.get(ln.size() - 1);
            n.val = rs[0];
            n.inDv = rs[1] * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
        }
        List<BezierBaker.Node> fr = force.lanes.get(2);
        BezierBaker.Node n0 = fr.get(0);
        double outDt = BezierBaker.effOutDt(n0, fr.get(1));
        double slope = Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt;
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        BezierBaker.Node last = gr.get(gr.size() - 1);
        last.val = n0.val;
        last.inDv = slope * BezierBaker.effInDt(gr.get(gr.size() - 2), last);
    }

    // ---- v13.4 ANALYTIC matching (replicates crossModeFromLanes) ----

    static double[] laneEdge(List<BezierBaker.Node> ln, double end, boolean atStart) {
        if (atStart) {
            BezierBaker.Node n0 = ln.get(0);
            double outDt = ln.size() >= 2 ? BezierBaker.effOutDt(n0, ln.get(1)) : 0;
            return new double[]{ n0.val, Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt };
        }
        BezierBaker.Node last = ln.get(ln.size() - 1);
        if (ln.size() < 2 || last.t < end - 1e-9) return new double[]{ last.val, 0 };
        double inDt = BezierBaker.effInDt(ln.get(ln.size() - 2), last);
        return new double[]{ last.val, Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt };
    }

    static Vec3 omegaAt(Vec3 T, Vec3 U, Vec3 R, double p, double y, double ro) {
        return U.scale(Math.toRadians(y)).add(R.scale(-Math.toRadians(p)))
            .add(T.scale(Math.toRadians(ro)));
    }

    /** force active, geo neighbor before: G targets from geo's authored
     *  end rates at the force side's entry design speed. */
    static void matchForceSideNew(TrackCompiler.Result r) {
        TrackPose seam = r.spans().get(0).exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double gEnd = BezierBaker.rideEnd(geo.lanes);
        double[] p = laneEdge(geo.lanes.get(0), gEnd, false);
        double[] y = laneEdge(geo.lanes.get(1), gEnd, false);
        double[] ro = laneEdge(geo.lanes.get(2), gEnd, false);
        Vec3 omega = omegaAt(T, U, R, p[0], y[0], ro[0]);
        double vAct = force.vDesign;
        for (int L = 0; L < 2; L++) {
            double[] e = L == 0 ? p : y;
            Vec3 axis = L == 0 ? U : R;
            double w = Math.toRadians(e[0]);
            double val = w * vAct / G0 + axis.y();
            double slope = Math.toRadians(e[1]) * vAct / G0
                - w * T.y() + omega.cross(axis).y();
            List<BezierBaker.Node> ln = force.lanes.get(L);
            BezierBaker.Node n = ln.get(0);
            n.val = val;
            n.outDv = slope * BezierBaker.effOutDt(n, ln.get(1));
        }
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        BezierBaker.Node last = gr.get(gr.size() - 1);
        double inDt = BezierBaker.effInDt(gr.get(gr.size() - 2), last);
        double slope = Math.abs(inDt) < 1e-9 ? 0 : last.inDv / inDt;
        List<BezierBaker.Node> fr = force.lanes.get(2);
        fr.get(0).val = last.val;
        fr.get(0).outDv = slope * BezierBaker.effOutDt(fr.get(0), fr.get(1));
    }

    /** geo active, force neighbor after: rate targets by inverting the
     *  force side's authored G at ITS entry design speed + seam frame. */
    static void matchGeoSideNew(TrackCompiler.Result r) {
        TrackPose seam = r.spans().get(0).exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double fEnd = BezierBaker.rideEnd(force.lanes);
        double[] gv = laneEdge(force.lanes.get(0), fEnd, true);
        double[] gl = laneEdge(force.lanes.get(1), fEnd, true);
        double[] ro = laneEdge(force.lanes.get(2), fEnd, true);
        double vNb = Math.max(0.5, force.vDesign);
        double pitchDeg = Math.toDegrees(G0 * (gv[0] - U.y()) / vNb);
        double yawDeg = Math.toDegrees(G0 * (gl[0] - R.y()) / vNb);
        Vec3 omega = omegaAt(T, U, R, pitchDeg, yawDeg, ro[0]);
        for (int L = 0; L < 2; L++) {
            double[] e = L == 0 ? gv : gl;
            double rateRad = Math.toRadians(L == 0 ? pitchDeg : yawDeg);
            Vec3 axis = L == 0 ? U : R;
            double slopeRad = G0 / vNb * (e[1] - omega.cross(axis).y())
                + rateRad * G0 * T.y() / vNb;
            List<BezierBaker.Node> ln = geo.lanes.get(L);
            BezierBaker.Node n = ln.get(ln.size() - 1);
            n.val = Math.toDegrees(rateRad);
            n.inDv = Math.toDegrees(slopeRad)
                * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
        }
        List<BezierBaker.Node> fr = force.lanes.get(2);
        BezierBaker.Node n0 = fr.get(0);
        double outDt = BezierBaker.effOutDt(n0, fr.get(1));
        double slope = Math.abs(outDt) < 1e-9 ? 0 : n0.outDv / outDt;
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        BezierBaker.Node last = gr.get(gr.size() - 1);
        last.val = n0.val;
        last.inDv = slope * BezierBaker.effInDt(gr.get(gr.size() - 2), last);
    }

    static void run(String name, double vGeo, double vForce, int rounds,
                    boolean analytic) {
        build(vGeo, vForce);
        System.out.println("=== " + name + " ===");
        TrackCompiler.Result r = comp();
        TrackPose seam0 = r.spans().get(0).exit();
        System.out.printf("  seam frame: T.y %+.3f  U.y %.3f  R.y %+.3f%n",
            seam0.T().y(), seam0.U().y(), seam0.U().cross(seam0.T()).y());
        for (int round = 0; round <= rounds; round++) {
            if (round > 0) {
                if (analytic) matchForceSideNew(r); else matchForceSide(r);
                r = comp();
                if (analytic) matchGeoSideNew(r); else matchGeoSide(r);
                r = comp();
            }
            double tB = r.spans().get(0).t1();
            double[] pA = rateAtSeam(r, tB, 1, -1), pB = rateAtSeam(r, tB, 1, +1);
            double[] yA = rateAtSeam(r, tB, 2, -1), yB = rateAtSeam(r, tB, 2, +1);
            double[] rA = rateAtSeam(r, tB, 3, -1), rB = rateAtSeam(r, tB, 3, +1);
            TrackPose seam = r.spans().get(0).exit();
            Vec3 R = seam.U().cross(seam.T());
            System.out.printf(
                "  r%-2d pitch %8.3f|%8.3f  yaw %8.3f|%8.3f  roll %7.3f|%7.3f  "
                + "geoEnd[p %7.3f y %7.3f r %7.3f] Fn0[Gv %7.4f Gl %+8.4f r %7.3f]  R.y %+9.5f%n",
                round, pA[0], pB[0], yA[0], yB[0], rA[0], rB[0],
                geo.lanes.get(0).get(geo.lanes.get(0).size() - 1).val,
                geo.lanes.get(1).get(geo.lanes.get(1).size() - 1).val,
                geo.lanes.get(2).get(geo.lanes.get(2).size() - 1).val,
                force.lanes.get(0).get(0).val,
                force.lanes.get(1).get(0).val,
                force.lanes.get(2).get(0).val,
                R.y());
        }
    }

    public static void main(String[] args) {
        run("v13.3 SAMPLED: banked, vDesign 26 vs 20", 26, 20, 10, false);
        run("v13.4 ANALYTIC: banked, vDesign 26 vs 20", 26, 20, 10, true);
        run("v13.4 ANALYTIC: banked, vDesign 26 vs 26", 26, 26, 10, true);
    }
}
