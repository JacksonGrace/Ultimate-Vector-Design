import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Probe: where does the reversed fit diverge on the 48f and 48i
 *  fixtures? Prints per-section boundaries, speeds and the deviation
 *  profile along the arc. */
public class DbgRev {

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

    static List<List<BezierBaker.Node>> flatLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{ {0, 1}, {dur, 1} }));
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

    static TrackCompiler.Result settle(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int i = 0; i < 6 && c.reconcileChainedSpeeds(r); i++) {
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    static void devProfile(String name, List<FVDIntegrator.Sample> pre,
                           List<FVDIntegrator.Sample> post) {
        double S = pre.get(pre.size() - 1).s() - pre.get(0).s();
        double Sp = post.get(post.size() - 1).s() - post.get(0).s();
        System.out.printf("%s: pre arc %.1f m, post arc %.1f m%n",
            name, S, Sp);
        int n = post.size();
        for (int i = 0; i < n; i += Math.max(1, n / 24)) {
            FVDIntegrator.Sample sm = post.get(i);
            double u = sm.s() - post.get(0).s();
            double want = pre.get(0).s() + S - u;
            // nearest pre sample by arc
            int best = 0;
            for (int j = 0; j < pre.size(); j++) {
                if (Math.abs(pre.get(j).s() - want)
                        < Math.abs(pre.get(best).s() - want)) best = j;
            }
            // orientation mismatch: post T vs the mirrored pre T
            double tAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                sm.T().dot(pre.get(best).T().scale(-1))))));
            double uAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                sm.U().dot(pre.get(best).U())))));
            System.out.printf(
                "  u=%7.1f  dev=%8.3f  T-ang=%6.2f  U-ang=%6.2f  v=%6.2f%n",
                u, sm.pos().sub(pre.get(best).pos()).length(),
                tAng, uAng, sm.v());
        }
    }

    public static void main(String[] args) {
        // 48f fixture (current: gentle friction, feasible reversal)
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(hillLanes(5), 24));
        p.sections.add(new GeoSection(geoLanes(6, 12, 15, 3), 24));
        c.pieces.add(p);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        TrackCompiler.Result r0 = settle(c);
        List<FVDIntegrator.Sample> pre = new ArrayList<>(r0.samples());
        c.reversePiece(0, 0.01, 0.02, 0, Double.NaN);
        TrackCompiler.Result r1 = settle(c);
        for (TrackCompiler.Span sp : r1.spans()) {
            System.out.printf("48f span: t %.2f..%.2f  s %.1f+%.1f%n",
                sp.t0(), sp.t1(), sp.s0(), sp.sLen());
        }
        devProfile("48f", pre, r1.samples());

        // 48i fixture (legacy reversed section inside)
        Coaster c2 = new Coaster();
        c2.v0 = 24;
        TrackPiece p2 = new TrackPiece(0, 21, 0, 0);
        p2.sections.add(new ForceSection(hillLanes(6), 24));
        ForceSection legacy = new ForceSection(flatLanes(6), 22);
        legacy.reversed = true;
        p2.sections.add(legacy);
        c2.pieces.add(p2);
        c2.fricRoll = 0.02;
        c2.fricAir = 0.001;
        TrackCompiler.Result q0 = settle(c2);
        List<FVDIntegrator.Sample> pre2 = new ArrayList<>(q0.samples());
        System.out.printf("48i pre: sections=%d  arc=%.1f  trueEnd=%.2f%n",
            p2.sections.size(),
            pre2.get(pre2.size() - 1).s(),
            q0.trueV()[q0.samples().size() - 1]);
        boolean did = c2.reversePiece(0, 0.01, 0.02, 0, Double.NaN);
        System.out.println("48i did=" + did
            + " sections=" + p2.sections.size());
        for (Section s : p2.sections) {
            System.out.printf("  out sec %s vDesign=%.2f rev=%b%n",
                s.getClass().getSimpleName(), s.vDesign(), s.reversed());
        }
        TrackCompiler.Result q1 = settle(c2);
        devProfile("48i", pre2, q1.samples());
    }
}
