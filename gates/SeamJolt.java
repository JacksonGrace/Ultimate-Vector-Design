import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * v13.5 seam-jolt root cause: with the seam DESIGN SPEEDS drifted
 * apart, rate-matched geo|force seams still показывают a VALUE step in
 * the felt Gv/Gl display (and the true ride feels it): rates matched
 * at different speeds = a curvature step, ω·Δv/g of felt G exactly on
 * the boundary line. The fix re-freezes the downstream section's
 * vDesign to the upstream design-end speed before matching (the '+'
 * creation rule). This probe replicates the editor flow with and
 * without the re-freeze on a banked rolling seam, both mode orders.
 */
public class SeamJolt {

    static final double DT = 0.01;
    static final double G0 = FVDIntegrator.G0;
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

    static Coaster doc;
    static GeoSection geo;
    static ForceSection force;
    static boolean geoFirst;

    static void build(boolean geoLeadsIn, double vGeo, double vForce) {
        doc = new Coaster();
        geoFirst = geoLeadsIn;
        doc.v0 = geoLeadsIn ? vGeo : vForce;
        TrackPiece piece = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, 0}, {1.0, 16}, {1.6, 12} }));
        gl.add(nodes(new double[][]{ {0, 0}, {1.6, 8} }));
        gl.add(nodes(new double[][]{ {0, 25}, {1.6, 25} }));
        geo = new GeoSection(gl, vGeo);
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0, 1.30}, {1.5, 0.9} }));
        fl.add(nodes(new double[][]{ {0, 0.20}, {1.5, -0.2} }));
        fl.add(nodes(new double[][]{ {0, 25}, {1.5, 0} }));
        force = new ForceSection(fl, vForce);
        if (geoLeadsIn) {
            piece.sections.add(geo);
            piece.sections.add(force);
        } else {
            piece.sections.add(force);
            piece.sections.add(geo);
        }
        doc.pieces.add(piece);
    }

    static TrackCompiler.Result comp() { return TrackCompiler.compile(doc, DT); }

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

    /** The editor's re-freeze: downstream vDesign := upstream design-end
     *  sample speed. */
    static void refreeze(TrackCompiler.Result r) {
        TrackCompiler.Span up = r.spans().get(0);
        double v = r.samples().get(up.i1()).v();
        Section down = doc.pieces.get(0).sections.get(1);
        down.vDesign(Math.max(1, Math.min(100, v)));
    }

    /** Match the FORCE side's edge (node0 or last) from the geo
     *  neighbor's authored rates — v13.4 analytic, both orders. The
     *  shared seam is section0's END meeting section1's START:
     *  geoFirst ⇒ geo's END + force's START. */
    static void matchForceSide(TrackCompiler.Result r) {
        TrackPose seam = r.spans().get(0).exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double gEnd = BezierBaker.rideEnd(geo.lanes);
        boolean geoEdgeIsStart = !geoFirst;
        boolean forceEdgeIsStart = geoFirst;
        double[] p = laneEdge(geo.lanes.get(0), gEnd, geoEdgeIsStart);
        double[] y = laneEdge(geo.lanes.get(1), gEnd, geoEdgeIsStart);
        double[] ro = laneEdge(geo.lanes.get(2), gEnd, geoEdgeIsStart);
        Vec3 omega = omegaAt(T, U, R, p[0], y[0], ro[0]);
        // active force side's seam design speed: entry vDesign at its
        // start edge, compiled end-sample speed at its end edge
        double vAct = forceEdgeIsStart ? force.vDesign
            : r.samples().get(r.spans().get(0).i1()).v();
        for (int L = 0; L < 2; L++) {
            double[] e = L == 0 ? p : y;
            Vec3 axis = L == 0 ? U : R;
            double w = Math.toRadians(e[0]);
            double val = w * vAct / G0 + axis.y();
            double slope = Math.toRadians(e[1]) * vAct / G0
                - w * T.y() + omega.cross(axis).y();
            List<BezierBaker.Node> ln = force.lanes.get(L);
            if (forceEdgeIsStart) {
                BezierBaker.Node n = ln.get(0);
                n.val = val;
                n.outDv = slope * BezierBaker.effOutDt(n, ln.get(1));
            } else {
                BezierBaker.Node n = ln.get(ln.size() - 1);
                n.val = val;
                n.inDv = slope * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
            }
        }
        // roll: authored copy
        List<BezierBaker.Node> fr = force.lanes.get(2);
        if (forceEdgeIsStart) {
            fr.get(0).val = ro[0];
            fr.get(0).outDv = ro[1] * BezierBaker.effOutDt(fr.get(0), fr.get(1));
        } else {
            BezierBaker.Node n = fr.get(fr.size() - 1);
            n.val = ro[0];
            n.inDv = ro[1] * BezierBaker.effInDt(fr.get(fr.size() - 2), n);
        }
    }

    /** Match the GEO side's edge from the force neighbor's authored
     *  targets, inverted at the force side's seam design speed. */
    static void matchGeoSide(TrackCompiler.Result r) {
        TrackPose seam = r.spans().get(0).exit();
        Vec3 T = seam.T(), U = seam.U();
        Vec3 R = U.cross(T);
        double fEnd = BezierBaker.rideEnd(force.lanes);
        boolean geoEdgeIsStart = !geoFirst;
        boolean forceEdgeIsStart = geoFirst;
        double[] gv = laneEdge(force.lanes.get(0), fEnd, forceEdgeIsStart);
        double[] gl = laneEdge(force.lanes.get(1), fEnd, forceEdgeIsStart);
        double[] ro = laneEdge(force.lanes.get(2), fEnd, forceEdgeIsStart);
        double vNb = forceEdgeIsStart
            ? force.vDesign                                // force starts there
            : r.samples().get(r.spans().get(0).i1()).v();  // force ends at seam
        vNb = Math.max(0.5, vNb);
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
            if (geoEdgeIsStart) {
                BezierBaker.Node n = ln.get(0);
                n.val = Math.toDegrees(rateRad);
                n.outDv = Math.toDegrees(slopeRad)
                    * BezierBaker.effOutDt(n, ln.get(1));
            } else {
                BezierBaker.Node n = ln.get(ln.size() - 1);
                n.val = Math.toDegrees(rateRad);
                n.inDv = Math.toDegrees(slopeRad)
                    * BezierBaker.effInDt(ln.get(ln.size() - 2), n);
            }
        }
        List<BezierBaker.Node> gr = geo.lanes.get(2);
        if (geoEdgeIsStart) {
            gr.get(0).val = ro[0];
            gr.get(0).outDv = ro[1] * BezierBaker.effOutDt(gr.get(0), gr.get(1));
        } else {
            BezierBaker.Node n = gr.get(gr.size() - 1);
            n.val = ro[0];
            n.inDv = ro[1] * BezierBaker.effInDt(gr.get(gr.size() - 2), n);
        }
    }

    /** The displayed-quantity steps at the seam: {feltGv, feltGl,
     *  rateP, rateY, rateR} — each side's curve linearly extrapolated
     *  from just inside (samples ±3..±8) to the seam time, so sloped
     *  curves don't fake a step across the measurement window. */
    static double[] seamSteps(TrackCompiler.Result r) {
        TrackCompiler.Span a = r.spans().get(0);
        int e = a.i1();
        double tB = r.samples().get(e).t();
        double[] out = new double[5];
        for (int q = 0; q < 5; q++) {
            double left = extrapTo(r, q, e - 8, e - 3, tB);
            double right = extrapTo(r, q, e + 3, e + 8, tB);
            out[q] = Math.abs(right - left);
        }
        return out;
    }

    static double quantity(TrackCompiler.Result r, int q, int i) {
        if (q == 0) return r.samples().get(i).Gv();
        if (q == 1) return r.samples().get(i).Gl();
        return r.rates()[i][q - 1];   // q 2/3/4 → rates 1/2/3
    }

    static double extrapTo(TrackCompiler.Result r, int q, int i0, int i1,
                           double tB) {
        double t0 = r.samples().get(i0).t(), t1 = r.samples().get(i1).t();
        double v0 = quantity(r, q, i0), v1 = quantity(r, q, i1);
        double slope = (v1 - v0) / (t1 - t0);
        double tRef = Math.abs(t1 - tB) < Math.abs(t0 - tB) ? t1 : t0;
        double vRef = Math.abs(t1 - tB) < Math.abs(t0 - tB) ? v1 : v0;
        return vRef + slope * (tB - tRef);
    }

    static void run(String name, boolean geoLeads, boolean withRefreeze) {
        build(geoLeads, 26, 20);
        TrackCompiler.Result r = comp();
        for (int round = 0; round < 2; round++) {
            if (withRefreeze) { refreeze(r); r = comp(); }
            matchForceSide(r);
            r = comp();
            matchGeoSide(r);
            r = comp();
        }
        double[] st = seamSteps(r);
        double vDown = doc.pieces.get(0).sections.get(1).vDesign();
        System.out.printf(
            "%-34s felt dGv %6.4f dGl %6.4f | rate dP %5.2f dY %5.2f dR %5.2f | vDown %.2f%n",
            name, st[0], st[1], st[2], st[3], st[4], vDown);
        if (withRefreeze) {
            check(st[0] < 0.02 && st[1] < 0.02,
                name + ": felt G display continuous (< 0.02 g)");
            check(st[2] < 1.2 && st[3] < 1.2 && st[4] < 1.2,
                name + ": rates stay continuous");
        } else {
            check(st[0] > 0.05 || st[1] > 0.05,
                name + ": fixture really shows the old felt jolt");
        }
    }

    public static void main(String[] args) {
        run("geo->force NO refreeze (old)", true, false);
        run("geo->force WITH refreeze", true, true);
        run("force->geo NO refreeze (old)", false, false);
        run("force->geo WITH refreeze", false, true);
        System.out.println(failures == 0 ? "ALL SEAM-JOLT PROBES PASS"
            : failures + " FAILURES");
    }
}
