import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** End-to-end repro of the 2026-07-19 report: after a plain force
 *  section, append a GEO section with global roll and/or global
 *  yaw/pitch holding zero rates (the add-dialog's current behavior),
 *  then simulate B/Shift+B (world-rate targets) and see which combo
 *  fails to respond. */
public class GeoAddProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster fixture() {
        Coaster c = new Coaster();
        c.v0 = 26;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{ {0, 1}, {2, 0.8}, {4, 1.6}, {6, 1.4} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {3, 0.3}, {6, 0.4} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {3, 0}, {6, 25} }));
        a.sections.add(new ForceSection(lanes, 26));
        c.pieces.add(a);
        return c;
    }

    static TrackCompiler.Result settle(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int i = 0; i < 6 && c.reconcileChainedSpeeds(r); i++) {
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    /** worldRates transliterated (package-private in Coaster):
     *  {elevation', heading', bank'} deg/s from body rates deg/s. */
    static double[] world(FVDIntegrator.Sample sm, double pitchDeg,
                          double yawDeg, double rollDeg) {
        Vec3 T = sm.T(), U = sm.U(), R = sm.R();
        Vec3 om = U.scale(Math.toRadians(yawDeg))
            .add(R.scale(-Math.toRadians(pitchDeg)))
            .add(T.scale(Math.toRadians(rollDeg)));
        Vec3 tp = om.cross(T);
        double horiz2 = T.x() * T.x() + T.z() * T.z();
        double cosTh = Math.sqrt(Math.max(horiz2, 0));
        boolean ok = cosTh > 0.15;
        double thp = ok ? tp.y() / cosTh : Double.NaN;
        double psp = ok
            ? (T.z() * tp.x() - T.x() * tp.z()) / horiz2 : Double.NaN;
        double php = ok ? om.dot(T) - psp * T.y() : Double.NaN;
        return new double[]{ Math.toDegrees(thp), Math.toDegrees(psp),
            Math.toDegrees(php) };
    }

    public static void main(String[] args) {
        String[] names = { "rollG only", "ypG only  ", "BOTH      " };
        boolean[][] flags = { {true, false}, {false, true}, {true, true} };
        for (int f = 0; f < 3; f++) {
            boolean rollG = flags[f][0], ypG = flags[f][1];
            Coaster c = fixture();
            TrackCompiler.Result r0 = settle(c);
            FVDIntegrator.Sample e = r0.samples()
                .get(r0.samples().size() - 1);
            double vEnd = e.v();
            // v17.6 (D143) hold values: GLOBAL-axis lanes take the
            // incoming WORLD rates; local lanes the body rates
            double[] er = r0.rates()[r0.samples().size() - 1];
            double[] wr = world(e, er[1], er[2], er[3]);
            double v0h = ypG ? (Double.isNaN(wr[0]) ? 0 : wr[0]) : er[1];
            double v1h = ypG ? (Double.isNaN(wr[1]) ? 0 : wr[1]) : er[2];
            double rr = rollG ? (Double.isNaN(wr[2]) ? 0 : wr[2]) : er[3];
            List<List<BezierBaker.Node>> gl = new ArrayList<>();
            gl.add(nodes(new double[][]{ {0, v0h}, {2, v0h} }));
            gl.add(nodes(new double[][]{ {0, v1h}, {2, v1h} }));
            gl.add(nodes(new double[][]{ {0, rr}, {2, rr} }));
            GeoSection g = new GeoSection(gl, vEnd);
            g.rollGlobal = rollG;
            g.yawPitchGlobal = ypG;
            c.pieces.get(0).sections.add(g);
            TrackCompiler.Result r1 = settle(c);

            TrackCompiler.Span sp = r1.spans().get(1);
            int ib = sp.i0();
            // seam continuity of the TRUE rates (deg/s), read clear of
            // the +-2-sample smear
            double[] pre = r1.trueRates()[ib - 4];
            double[] post = r1.trueRates()[Math.min(ib + 4, sp.i1())];
            System.out.printf(
                "%s pre-B: true rates before seam {%.1f %.1f %.1f} "
                + "after {%.1f %.1f %.1f}%n",
                names[f], pre[1], pre[2], pre[3],
                post[1], post[2], post[3]);

            // ---- simulate Shift+B: write the matcher's targets ----
            // lanes 0/1: world targets when ypG (the wg sampled path),
            // else the analytic body rates at the seam; lane 2: the
            // rollAcrossSeam conversion when semantics differ
            double dt = 0.01;
            double tB = sp.t0();
            int i1 = ib - 3, i0 = i1 - 5;
            for (int lane = 0; lane < 3; lane++) {
                double target;
                if (lane < 2 && ypG) {
                    double w0 = world(r1.samples().get(i0),
                        r1.trueRates()[i0][1], r1.trueRates()[i0][2],
                        r1.trueRates()[i0][3])[lane];
                    double w1 = world(r1.samples().get(i1),
                        r1.trueRates()[i1][1], r1.trueRates()[i1][2],
                        r1.trueRates()[i1][3])[lane];
                    double span = r1.trueRates()[i1][0]
                        - r1.trueRates()[i0][0];
                    double slope = span < 1e-9 ? 0 : (w1 - w0) / span;
                    target = w1 - slope * (r1.trueRates()[i1][0] - tB);
                } else if (lane == 2 && rollG) {
                    // neighbor local body roll -> bank rate:
                    // phi' = p - psi'*sin(theta), at the boundary sample
                    FVDIntegrator.Sample sm = r1.samples().get(ib);
                    double vS = Math.max(0.5, sm.v());
                    double pit = Math.toDegrees(FVDIntegrator.G0
                        * (sm.Gv() - sm.U().y()) / vS);
                    double yaw = Math.toDegrees(FVDIntegrator.G0
                        * (sm.Gl() - sm.R().y()) / vS);
                    double psi = world(sm, pit, yaw, 0)[1];
                    double auth = r1.rates()[ib][3];
                    target = auth - psi * sm.T().y();
                } else {
                    target = r1.rates()[ib][lane + 1];
                }
                for (BezierBaker.Node n : g.lanes.get(lane)) {
                    n.val = target;   // flat hold at the matched value
                }
            }
            TrackCompiler.Result r2 = settle(c);
            TrackCompiler.Span sp2 = r2.spans().get(1);
            int ib2 = sp2.i0();
            double[] pre2 = r2.trueRates()[ib2 - 4];
            double[] post2 = r2.trueRates()[Math.min(ib2 + 4, sp2.i1())];
            Vec3 end0 = r1.samples().get(r1.samples().size() - 1).pos();
            Vec3 end2 = r2.samples().get(r2.samples().size() - 1).pos();
            System.out.printf(
                "%s postB: true rates before seam {%.1f %.1f %.1f} "
                + "after {%.1f %.1f %.1f}  geo end moved %.2f m%n",
                names[f], pre2[1], pre2[2], pre2[3],
                post2[1], post2[2], post2[3],
                end2.sub(end0).length());
        }
    }
}
