import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Probe for strict-seam geometric smoothing (v17.2, D139):
 *  locality (nothing outside the window reorients; upstream bitwise),
 *  kink/felt-step collapse at the seam, translation propagation to the
 *  follower's later sections, and the field-18 save round-trip. */
public class SmoothProbe {

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

    static Coaster fixture() {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        a.sections.add(new ForceSection(hillLanes(5), 24));
        a.sections.add(new ForceSection(hillLanes(4), 24));
        c.pieces.add(a);
        settle(c);
        c.makeSeamStrict(0, 1, 0.01);   // follower = [sec1, sec2], locked p/r
        // violent upstream edit: the seam pitches, banks and re-speeds
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(1).val -= 1.2;
        up.lanes.get(0).get(2).val += 1.5;
        up.lanes.get(2).get(1).val = 60;
        settle(c);
        return c;
    }

    /** Largest per-sample step of |ΔGv| and the U-frame angle (deg)
     *  within ±win m of the seam. */
    static double[] seamSteps(TrackCompiler.Result r, double win) {
        TrackCompiler.Span sa = r.spans().get(0);
        double sSeam = r.samples().get(sa.i1()).s();
        double maxDG = 0, maxAng = 0;
        for (int i = 1; i < r.samples().size(); i++) {
            FVDIntegrator.Sample p = r.samples().get(i - 1);
            FVDIntegrator.Sample q = r.samples().get(i);
            if (Math.abs(p.s() - sSeam) > win) continue;
            maxDG = Math.max(maxDG, Math.abs(q.Gv() - p.Gv()));
            double d = Math.max(-1, Math.min(1, p.U().dot(q.U())));
            maxAng = Math.max(maxAng, Math.toDegrees(Math.acos(d)));
        }
        return new double[]{ maxDG, maxAng };
    }

    public static void main(String[] args) throws Exception {
        Coaster c0 = fixture();
        TrackCompiler.Result r0 = TrackCompiler.compile(c0, 0.01);
        double[] st0 = seamSteps(r0, 5);

        Coaster c1 = fixture();
        c1.pieces.get(1).sSmoothLen = 8;
        TrackCompiler.Result r1 = TrackCompiler.compile(c1, 0.01);
        double[] st1 = seamSteps(r1, 5);

        System.out.printf("sample counts: off=%d on=%d (%s)%n",
            r0.samples().size(), r1.samples().size(),
            r0.samples().size() == r1.samples().size() ? "match" : "MISMATCH");

        // locality: upstream of the window bitwise; downstream of it
        // frames bitwise + positions offset by ONE constant vector
        double sSeam = r0.samples().get(r0.spans().get(0).i1()).s();
        boolean upBit = true;
        double frameDev = 0, dpVar = 0;
        Vec3 dpRef = null;
        for (int i = 0; i < r0.samples().size(); i++) {
            FVDIntegrator.Sample a = r0.samples().get(i);
            FVDIntegrator.Sample b = r1.samples().get(i);
            if (a.s() < sSeam - 4.2) {
                if (a.pos().sub(b.pos()).length() > 0
                        || a.U().sub(b.U()).length() > 0
                        || a.Gv() != b.Gv()) upBit = false;
            } else if (a.s() > sSeam + 4.2) {
                frameDev = Math.max(frameDev, Math.max(
                    a.T().sub(b.T()).length(), a.U().sub(b.U()).length()));
                Vec3 dp = b.pos().sub(a.pos());
                if (dpRef == null) dpRef = dp;
                dpVar = Math.max(dpVar, dp.sub(dpRef).length());
            }
        }
        System.out.printf(
            "upstream-of-window bitwise: %s%n"
            + "beyond-window frames bitwise: dev=%.3g (0 = exact)%n"
            + "beyond-window pure translation: |dp|=%.3f m, variation=%.3g m%n",
            upBit ? "YES" : "NO", frameDev,
            dpRef == null ? 0 : dpRef.length(), dpVar);
        System.out.printf(
            "seam step ±5 m: |dGv/sample| %.3f -> %.3f g   U kink"
            + " %.2f -> %.3f deg/sample%n",
            st0[0], st1[0], st0[1], st1[1]);

        // arc consistency: chord lengths still track the s field
        double worst = 0;
        for (int i = 1; i < r1.samples().size(); i++) {
            FVDIntegrator.Sample p = r1.samples().get(i - 1);
            FVDIntegrator.Sample q = r1.samples().get(i);
            if (Math.abs(p.s() - sSeam) > 6) continue;
            worst = Math.max(worst, Math.abs(
                q.pos().sub(p.pos()).length() - (q.s() - p.s())));
        }
        System.out.printf("in-window chord-vs-arc dev: %.4f m%n", worst);

        // follower's LATER section (span 2): frames bitwise, same dp
        TrackCompiler.Span s2a = r0.spans().get(2);
        double f2 = 0, dv2 = 0;
        for (int i = s2a.i0(); i <= s2a.i1(); i++) {
            FVDIntegrator.Sample a = r0.samples().get(i);
            FVDIntegrator.Sample b = r1.samples().get(i);
            f2 = Math.max(f2, a.U().sub(b.U()).length());
            dv2 = Math.max(dv2, b.pos().sub(a.pos()).sub(dpRef).length());
        }
        System.out.printf(
            "later-section locality: frame dev=%.3g  translation"
            + " variation=%.3g m%n", f2, dv2);

        // Pass-D honesty: the true speed profile stays continuous
        double maxDv = 0;
        for (int i = 1; i < r1.trueV().length; i++) {
            maxDv = Math.max(maxDv,
                Math.abs(r1.trueV()[i] - r1.trueV()[i - 1]));
        }
        System.out.printf("max per-sample trueV step (smooth): %.4f m/s%n",
            maxDv);

        // save round-trip carries field 18 (and stays byte-stable)
        java.io.StringWriter w1 = new java.io.StringWriter();
        DesignIO.save(c1, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(w1));
        DesignIO.LoadResult lr = DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(w1.toString())));
        java.io.StringWriter w2 = new java.io.StringWriter();
        DesignIO.save(lr.coaster(), lr.cosmetics(),
            new java.io.PrintWriter(w2));
        System.out.printf(
            "IO round-trip sSmoothLen: %.2f (want 8)   canonical"
            + " re-save: %s%n",
            lr.coaster().pieces.get(1).sSmoothLen,
            w1.toString().equals(w2.toString()) ? "byte-identical"
                : "DIFFERS");
    }
}
