import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Repro probe for the user's attach report: attach a FOREIGN floating
 *  piece (different vDesign, different placement/orientation) via
 *  connectStrict and inspect — does the design speed survive? does the
 *  shape stay rigid? does the conflict show? */
public class DbgAttach {

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
        for (int i = 0; i < 6; i++) {
            boolean a = c.reconcileChainedSpeeds(r);
            boolean b = c.autoReoptimizeBridges(r);
            if (!a && !b) break;
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    static List<Vec3> localShape(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i0 = sp.i0(), i1 = Math.min(sp.i1(), r.samples().size() - 1);
        FVDIntegrator.Sample e = r.samples().get(i0);
        List<Vec3> out = new ArrayList<>();
        for (int i = i0; i <= i1; i += 3) {
            Vec3 d = r.samples().get(i).pos().sub(e.pos());
            out.add(new Vec3(d.dot(e.T()), d.dot(e.U()), d.dot(e.R())));
        }
        return out;
    }

    static double shapeDelta(List<Vec3> a, List<Vec3> b) {
        double worst = 0;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, a.get(i).sub(b.get(i)).length());
        }
        return worst;
    }

    public static void main(String[] args) {
        // piece A: hills at 24 from the origin. piece B: FLOATING hills
        // at vDesign 20, parked elsewhere with yaw 90 and pitch 10.
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(200, 35, 60, 90);
        b.pitchDeg = 10;
        b.uiLockPitch = false;
        b.sections.add(new ForceSection(hillLanes(5), 20));
        c.pieces.add(b);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;

        TrackCompiler.Result r0 = settle(c);
        List<Vec3> shapeB0 = localShape(r0, 1);
        System.out.printf("PRE : B vDesign=%.3f  spans=%d%n",
            b.sections.get(0).vDesign(), r0.spans().size());

        // the UI flow: pushUndo (irrelevant here) -> connectStrict ->
        // rebuild (compile + reconcile/reopt settle)
        int np = c.connectStrict(0, 1);
        System.out.println("connectStrict -> np=" + np
            + " followPrev=" + c.pieces.get(np).followPrev
            + " locks=" + c.pieces.get(np).sLockYaw + "/"
            + c.pieces.get(np).sLockPitch + "/"
            + c.pieces.get(np).sLockRoll);

        TrackCompiler.Result r1 = settle(c);
        Section bFirst = c.pieces.get(np).sections.get(0);
        TrackCompiler.Span spB = r1.spans().get(1);
        int i0 = spB.i0();
        double vDesignSeam = r1.samples().get(i0).v();
        double vTrueSeam = r1.trueV()[i0];
        double prevDesignEnd = r1.samples().get(i0 - 1).v();
        System.out.printf(
            "POST: B vDesign=%.3f  (design@seam=%.3f  true@seam=%.3f  "
            + "prev design end=%.3f)%n",
            bFirst.vDesign(), vDesignSeam, vTrueSeam, prevDesignEnd);
        double worstSplit = 0;
        for (int i = spB.i0(); i <= Math.min(spB.i1(),
                r1.samples().size() - 1); i++) {
            worstSplit = Math.max(worstSplit,
                Math.abs(r1.trueV()[i] - r1.samples().get(i).v()));
        }
        System.out.printf("      worst design/true split over B: %.3f m/s%n",
            worstSplit);
        double dShape = shapeDelta(shapeB0, localShape(r1, 1));
        System.out.printf("      B local-shape delta vs pre-attach: %.6f m%n",
            dShape);
        FVDIntegrator.Sample in = r1.samples().get(i0 - 1);
        FVDIntegrator.Sample fo = r1.samples().get(i0);
        System.out.printf(
            "      seam frame: T continuity %.6f, pos gap %.6f m%n",
            fo.T().sub(in.T()).length(),
            fo.pos().sub(in.pos()).length());
        System.out.printf("      B entry pitch: pre=%.2f deg  post=%.2f deg  "
            + "(seam pitch=%.2f)%n",
            Math.toDegrees(Math.asin(r0.samples()
                .get(r0.spans().get(1).i0()).T().y())),
            Math.toDegrees(Math.asin(fo.T().y())),
            Math.toDegrees(Math.asin(in.T().y())));
    }
}
