import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Repro: strict seam + all-unlocked + upstream edit, then resolve the
 *  follower's speed conflict — the user reports both ops "launch off
 *  into the distance". */
public class DbgStrictResolve {

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

    static void report(String tag, TrackCompiler.Result r) {
        TrackCompiler.Span sp = r.spans().get(1);
        int i1 = Math.min(sp.i1(), r.samples().size() - 1);
        FVDIntegrator.Sample e = r.samples().get(sp.i0());
        FVDIntegrator.Sample x = r.samples().get(i1);
        System.out.printf(
            "%s: B arc=%.1f m  dur=%.1f s  entry v=%.2f  end pos=(%.0f,"
            + " %.0f, %.0f)  |end-entry|=%.1f m%n",
            tag, x.s() - e.s(), x.t() - e.t(), e.v(),
            x.pos().x(), x.pos().y(), x.pos().z(),
            x.pos().sub(e.pos()).length());
    }

    public static void main(String[] args) {
        for (boolean makeReal : new boolean[]{ false, true }) {
            Coaster c = new Coaster();
            c.v0 = 24;
            TrackPiece a = new TrackPiece(0, 21, 0, 0);
            a.sections.add(new ForceSection(hillLanes(6), 24));
            a.sections.add(new ForceSection(hillLanes(5), 24));
            c.pieces.add(a);
            c.fricRoll = 0.01;
            c.fricAir = 0.0003;
            settle(c);
            c.makeSeamStrict(0, 1, 0.01);
            TrackPiece f = c.pieces.get(1);
            f.sLockYaw = false;      // all-unlocked (continuous)
            f.sLockPitch = false;
            f.sLockRoll = false;
            // EDIT UPSTREAM violently: the seam pitches AND banks hard
            ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
            up.lanes.get(0).get(1).val -= 1.2;
            up.lanes.get(0).get(2).val += 1.5;
            up.lanes.get(2).get(1).val = 60;   // roll rate → banked seam
            TrackCompiler.Result r0 = settle(c);
            {
                int s0 = r0.spans().get(1).i0();
                FVDIntegrator.Sample sm = r0.samples().get(s0);
                System.out.printf("   seam: pitch=%.1f deg  U.y=%.3f%n",
                    Math.toDegrees(Math.asin(sm.T().y())), sm.U().y());
            }
            report("pre-resolve      ", r0);
            System.out.printf("   follower vDesign=%.2f  true@entry=%.2f%n",
                f.sections.get(0).vDesign(),
                r0.trueV()[r0.spans().get(1).i0()]);

            boolean did = makeReal
                ? c.makeGraphsReal(1, 0, 0.01, 0.05, 0)
                : c.refreezeSpeed(1, 0, 0.01);
            TrackCompiler.Result r1 = settle(c);
            report((makeReal ? "after MAKE-REAL  " : "after RE-FREEZE  "),
                r1);
            System.out.printf("   did=%b  follower vDesign=%.2f%n%n",
                did, f.sections.get(0).vDesign());
        }
    }
}
