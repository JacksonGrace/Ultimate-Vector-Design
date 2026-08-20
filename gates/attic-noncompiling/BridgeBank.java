import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Repro of the user's banked S-hill bridge blowup: entry seam banked
 * one way (roll rate ~0), target seam banked the other way. Suspicion:
 * the march is bank-BLIND — the residual twist lives only in the
 * render layer, so the far half applies the target's body-frame
 * curvature in a wrongly-banked frame.
 */
public class BridgeBank {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** Signed bank of U vs level-up at T (deg). */
    static double bank(Vec3 t, Vec3 u) {
        double h2 = 1 - t.y() * t.y();
        if (h2 < 1e-9) return 0;
        Vec3 ul = new Vec3(-t.y() * t.x(), h2, -t.y() * t.z()).normalize();
        return Math.toDegrees(Math.atan2(ul.cross(u).dot(t), u.dot(ul)));
    }

    public static void main(String[] args) {
        Coaster c = new Coaster();
        // piece A: gentle hill, rolls to +30 deg bank then holds level
        // roll rate -> the seam has bank +30, roll rate ~0
        TrackPiece pA = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> lA = new ArrayList<>();
        lA.add(nodes(new double[][]{ {0, 12}, {2, 12} }));       // pitch up
        lA.add(nodes(new double[][]{ {0, 0}, {2, 0} }));         // yaw 0
        lA.add(nodes(new double[][]{ {0, 60}, {0.4, 60}, {0.6, 0}, {2, 0} }));
        pA.sections.add(new GeoSection(lA, 26));
        c.pieces.add(pA);
        // piece B: level zero-rate hold, banked THE OTHER way via the
        // v13.13 roll delta
        TrackPiece pB = new TrackPiece(45, 27, 10, 15);
        pB.rollDeg = -30;
        List<List<BezierBaker.Node>> lB = new ArrayList<>();
        lB.add(nodes(new double[][]{ {0, 12}, {1.5, 12} }));   // CURVING head
        lB.add(nodes(new double[][]{ {0, 0}, {1.5, 0} }));
        lB.add(nodes(new double[][]{ {0, 0}, {1.5, 0} }));
        pB.sections.add(new GeoSection(lB, 26));
        c.pieces.add(pB);
        BridgeSection bs = BridgeSection.solve(
            new TrackPose(new Vec3(0, 0, 0), Quat.IDENTITY),
            new TrackPose(new Vec3(1, 0, 0), Quat.IDENTITY), 26);
        pA.sections.add(bs);
        TrackCompiler.compile(c, 0.01);            // capture seams

        System.out.println("== knee ladder (optimalBridgeLength, POS_YAW) ==");
        double knee = c.optimalBridgeLength(bs, true);
        System.out.printf("  pure knee (no budget): %.1f m%n", knee);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        double budget = c.neighborRateBudget(r0.rates(), r0.spans(), 0);
        System.out.printf("  neighbor rate budget: %.1f deg/s-cost%n", budget);
        System.out.printf("  budgeted knee (the app's): %.1f m%n",
            c.optimalBridgeLength(bs, true, budget));

        c.optimizeBridge(bs, 1, 30, true);         // request 30 m
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        if (c.autoReoptimizeBridges(r)) r = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span sp = r.spans().get(1);
        List<FVDIntegrator.Sample> s = r.samples();

        System.out.println("== optimized at 30 m ==");
        System.out.printf("  piece B now at y=%.2f m (was 27)%n",
            c.pieces.get(1).y);
        // seam continuity at the far end: bridge's last sample vs the
        // target's first
        FVDIntegrator.Sample bEnd = s.get(sp.i1());
        FVDIntegrator.Sample tHead = s.get(Math.min(sp.i1() + 2, s.size() - 1));
        System.out.printf("  far seam bank: bridge end %.1f deg, target head %.1f deg%n",
            bank(bEnd.T(), bEnd.U()), bank(tHead.T(), tHead.U()));
        System.out.printf("  far seam felt: bridge end Gv %.2f Gl %.2f | target head Gv %.2f Gl %.2f%n",
            bEnd.Gv(), bEnd.Gl(), tHead.Gv(), tHead.Gl());
        // interior wildness: peak felt lateral + min/max height
        double maxGl = 0, yLo = 1e9, yHi = -1e9, maxRate = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            maxGl = Math.max(maxGl, Math.abs(s.get(i).Gl()));
            yLo = Math.min(yLo, s.get(i).pos().y());
            yHi = Math.max(yHi, s.get(i).pos().y());
            if (i > sp.i0()) {
                maxRate = Math.max(maxRate, Math.abs(r.rates()[i][2]));
            }
        }
        System.out.printf("  bridge interior: |Gl|max %.2f g, height %.1f..%.1f m,"
            + " |yaw rate|max %.1f deg/s%n", maxGl, yLo, yHi, maxRate);
        System.out.printf("  bridge arc %.1f m (requested 30)%n", sp.sLen());
        // WORLD curvature direction continuity across the far seam:
        // the decisive measure of the frame error (pre-fix the target's
        // body components were applied ~60 deg mis-banked)
        Vec3 dIn = s.get(sp.i1() - 1).T().sub(s.get(sp.i1() - 5).T());
        Vec3 dOut = s.get(Math.min(sp.i1() + 6, s.size() - 1)).T()
            .sub(s.get(Math.min(sp.i1() + 2, s.size() - 1)).T());
        if (dIn.length() > 1e-9 && dOut.length() > 1e-9) {
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
                dIn.normalize().dot(dOut.normalize())))));
            System.out.printf("  far-seam curvature DIRECTION step: %.1f deg%n", ang);
        }
    }
}
