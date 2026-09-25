import uvd.*;

import java.util.*;

/** Diagnose OrientGate f1/f3: per-span rigid-image deviation. */
public class CarryDiag {

    static final double DT = 0.01;

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.2, 1}, {2.4, 3.2}, {4, 0.4},
            {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.4}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> flatLanes(double len) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        return ls;
    }

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    public static void main(String[] args) {
        Coaster cc = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(hillLanes(), 26));
        cc.pieces.add(hA);
        TrackPiece hB = new TrackPiece(120, 14, 30, 20);
        hB.sections.add(new ForceSection(flatLanes(3), 24));
        cc.pieces.add(hB);
        TrackCompiler.Result rc0 = TrackCompiler.compile(cc, DT);
        BridgeSection hbr = BridgeSection.solve(rc0.spans().get(0).exit(),
            hB.entryPose(),
            rc0.samples().get(rc0.spans().get(0).i1()).v());
        hbr.optMode = BridgeSection.OPT_NONE;
        hA.sections.add(hbr);
        TrackCompiler.Result rcB = rebuild(cc);
        System.out.printf("base: lenA=%.4f lenB=%.4f samples=%d%n",
            hbr.lenA, hbr.lenB, rcB.samples().size());
        for (int s = 0; s < rcB.spans().size(); s++) {
            System.out.printf("  span %d: i0=%d i1=%d%n", s,
                rcB.spans().get(s).i0(), rcB.spans().get(s).i1());
        }
        TrackPose be2 = hA.entryPose();
        hA.pitchDeg = 6;
        TrackPose af2 = hA.entryPose();
        Vec3 oldB = new Vec3(hB.x, hB.y, hB.z);
        Quat oldO = hB.entryPose().orient();
        cc.carryDownstream(0, be2, af2, rcB.spans());
        Quat dq = af2.orient().mul(be2.orient().conjugate());
        Vec3 a0 = be2.pos();
        Vec3 wantB = a0.add(dq.rotate(oldB.sub(a0)));
        System.out.printf("target pos err: %.3e%n",
            new Vec3(hB.x, hB.y, hB.z).sub(wantB).length());
        Quat wantO = dq.mul(oldO);
        Quat newO = hB.entryPose().orient();
        System.out.printf("target orient err: %.3e  (deltas y=%.4f p=%.4f r=%.4f)%n",
            Math.min(Math.abs(newO.w() - wantO.w()) + Math.abs(newO.x() - wantO.x())
                + Math.abs(newO.y() - wantO.y()) + Math.abs(newO.z() - wantO.z()),
                Math.abs(newO.w() + wantO.w()) + Math.abs(newO.x() + wantO.x())
                + Math.abs(newO.y() + wantO.y()) + Math.abs(newO.z() + wantO.z())),
            hB.yawDeg, hB.pitchDeg, hB.rollDeg);
        TrackCompiler.Result rcR = rebuild(cc);
        System.out.printf("after: lenA=%.4f lenB=%.4f samples=%d%n",
            hbr.lenA, hbr.lenB, rcR.samples().size());
        for (int s = 0; s < rcR.spans().size(); s++) {
            TrackCompiler.Span sb = rcB.spans().get(s);
            TrackCompiler.Span sr = rcR.spans().get(s);
            double worst = 0;
            int n = Math.min(sb.i1(), sr.i1());
            int i0 = Math.max(sb.i0(), sr.i0());
            for (int i = i0; i <= n && i < rcB.samples().size()
                    && i < rcR.samples().size(); i++) {
                Vec3 want = a0.add(dq.rotate(
                    rcB.samples().get(i).pos().sub(a0)));
                worst = Math.max(worst,
                    rcR.samples().get(i).pos().sub(want).length());
            }
            System.out.printf(
                "  span %d: base [%d..%d] new [%d..%d] worstDev=%.3e%n",
                s, sb.i0(), sb.i1(), sr.i0(), sr.i1(), worst);
        }
        // felt split on the target
        int b0 = rcR.spans().get(2).i0();
        double fs = 0;
        double vs = 0;
        for (int i = b0; i < rcR.samples().size(); i++) {
            fs = Math.max(fs, Math.abs(
                rcR.trueFelt()[i][0] - rcR.samples().get(i).Gv()));
            vs = Math.max(vs, Math.abs(
                rcR.trueV()[i] - rcR.samples().get(i).v()));
        }
        System.out.printf("target felt split max %.5f g, v split %.4f m/s, "
            + "vDesign=%.4f vPinned=%b%n", fs, vs,
            hB.sections.get(0).vDesign(), hB.sections.get(0).vPinned());
        // SPINE rigidity: nearest-segment distance of each new bridge
        // sample to the transformed OLD bridge polyline (the time
        // parameterization legitimately shifts with the new heights)
        List<Vec3> oldSpine = new ArrayList<>();
        for (int i = rcB.spans().get(1).i0() - 1;
                i <= rcB.spans().get(1).i1() + 1
                && i < rcB.samples().size(); i++) {
            if (i < 0) continue;
            oldSpine.add(a0.add(dq.rotate(
                rcB.samples().get(i).pos().sub(a0))));
        }
        double worstSpine = 0;
        for (int i = rcR.spans().get(1).i0();
                i <= rcR.spans().get(1).i1(); i++) {
            Vec3 q = rcR.samples().get(i).pos();
            double best = Double.MAX_VALUE;
            for (int k = 0; k + 1 < oldSpine.size(); k++) {
                Vec3 s0 = oldSpine.get(k), s1 = oldSpine.get(k + 1);
                Vec3 d = s1.sub(s0);
                double L2 = d.dot(d);
                double t = L2 < 1e-18 ? 0
                    : Math.max(0, Math.min(1, q.sub(s0).dot(d) / L2));
                best = Math.min(best,
                    q.sub(s0.add(d.scale(t))).length());
            }
            worstSpine = Math.max(worstSpine, best);
        }
        System.out.printf("bridge SPINE worst nearest-dist: %.3e m%n",
            worstSpine);
        // span-aligned rigidity for the piece spans
        for (int s : new int[]{ 0, 2 }) {
            TrackCompiler.Span sb = rcB.spans().get(s);
            TrackCompiler.Span sr = rcR.spans().get(s);
            int nAl = Math.min(sb.i1() - sb.i0(), sr.i1() - sr.i0());
            double worst = 0;
            for (int k = 0; k <= nAl; k++) {
                Vec3 want = a0.add(dq.rotate(
                    rcB.samples().get(sb.i0() + k).pos().sub(a0)));
                worst = Math.max(worst, rcR.samples()
                    .get(sr.i0() + k).pos().sub(want).length());
            }
            System.out.printf("span %d ALIGNED worstDev=%.3e m%n", s, worst);
        }
    }
}
