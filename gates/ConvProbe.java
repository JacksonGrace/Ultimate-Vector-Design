import uvd.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe for the 2026-07-11 conversion feedback (report-style, no
 * PASS/FAIL):
 *   1. CONVERGENCE BIAS: convert the default layout force->geo at
 *      maximum density — where does the rate-graph residual plateau,
 *      and is it a TIME SHIFT (re-time skew) or a VALUE error?
 *   2. PINNED CONVERT: does converting a speed-conflicted (pinned)
 *      section lose the pin -> silently reconcile -> reshape?
 *   3. PIECE PITCH RIGIDITY: does pitching a piece rotate a FORCE
 *      section rigidly (shape preserved) or regenerate it (gravity
 *      bends a different shape)? Geo section as control.
 */
public class ConvProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> defaultLanes() {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));
        return lanes;
    }

    static double[] rateAtT(double[][] rates, double t, int comp) {
        int lo = 0, hi = rates.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (rates[mid][0] <= t) lo = mid; else hi = mid;
        }
        double u = (t - rates[lo][0])
            / Math.max(1e-12, rates[hi][0] - rates[lo][0]);
        u = Math.max(0, Math.min(1, u));
        return new double[]{ rates[lo][comp]
            + u * (rates[hi][comp] - rates[lo][comp]) };
    }

    /** Max |a-b| over comp 1..3 sampled mid-section, with b's clock
     *  shifted by `shift`. */
    static double rateDev(double[][] a, double[][] b, double t0,
                          double t1, double shift) {
        double worst = 0;
        for (double t = t0; t <= t1; t += 0.02) {
            for (int comp = 1; comp <= 3; comp++) {
                double va = rateAtT(a, t, comp)[0];
                double vb = rateAtT(b, t + shift, comp)[0];
                worst = Math.max(worst, Math.abs(va - vb));
            }
        }
        return worst;
    }

    static FVDIntegrator.Sample atS(List<FVDIntegrator.Sample> l, double s) {
        int lo = 0, hi = l.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).s() <= s) lo = mid; else hi = mid;
        }
        return s - l.get(lo).s() <= l.get(hi).s() - s ? l.get(lo) : l.get(hi);
    }

    public static void main(String[] args) {
        part1();
        part2();
        part3();
    }

    // ---------- 1. convergence bias ----------
    static void part1() {
        System.out.println("== 1. CONVERT CONVERGENCE BIAS (force->geo, default layout) ==");
        for (double[] cfg : new double[][]{ {0.1, 0}, {0.02, 0}, {0.1, 0.0001} }) {
            Coaster c = new Coaster();
            TrackPiece tp = new TrackPiece(0, 21, 0, 0);
            tp.sections.add(new ForceSection(defaultLanes(), 26));
            c.pieces.add(tp);
            TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
            double dur = r0.samples().get(r0.samples().size() - 1).t();
            c.convertWholeSection(0, 0, true, 0.01, cfg[0], cfg[1]);
            TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
            // value residual at zero shift, then the best shift
            double dev0 = rateDev(r0.rates(), r1.rates(), 1, dur - 1, 0);
            double best = dev0, bestShift = 0;
            for (double s = -0.06; s <= 0.0601; s += 0.005) {
                double d = rateDev(r0.rates(), r1.rates(), 1, dur - 1, s);
                if (d < best) { best = d; bestShift = s; }
            }
            // shape
            List<FVDIntegrator.Sample> s0 = r0.samples(), s1 = r1.samples();
            double sTot = Math.min(s0.get(s0.size() - 1).s(),
                                   s1.get(s1.size() - 1).s());
            double shape = 0;
            for (int i = 1; i <= 9; i++) {
                double s = sTot * i / 10.0;
                shape = Math.max(shape,
                    atS(s0, s).pos().sub(atS(s1, s).pos()).length());
            }
            double d1 = s1.get(s1.size() - 1).t();
            System.out.printf(java.util.Locale.US,
                "spacing %.2fs adaptTol %.4f: rate dev %.3f deg/s at shift 0"
                + " -> %.3f at best shift %+.3fs | dur %.3f->%.3f | shape %.3f m%n",
                cfg[0], cfg[1], dev0, best, bestShift, dur, d1, shape);
        }
        // control: does a SECOND convert (geo->force->geo?) drift more?
        // and what does the roll lane alone look like (bake-grid test)
    }

    // ---------- 2. pinned convert ----------
    static void part2() {
        System.out.println("== 2. PINNED (speed-conflicted) SECTION CONVERT ==");
        Coaster c = new Coaster();
        TrackPiece tp = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> la = new ArrayList<>();
        la.add(nodes(new double[][]{ {0, 2.2}, {2, 2.2} }));
        la.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        la.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        tp.sections.add(new ForceSection(la, 26));
        ForceSection b = Coaster.holdSection(1.4, 0, 0, 15);  // conflicted
        b.vPinned = true;                                     // stitched pin
        tp.sections.add(b);
        c.pieces.add(tp);
        TrackCompiler.Result r = rebuildLoop(c);
        int i0 = r.spans().get(1).i0();
        System.out.printf(java.util.Locale.US,
            "before: vDesign %.2f pinned %b drift %.2f m/s%n",
            b.vDesign, b.vPinned, r.trueV()[i0] - r.samples().get(i0).v());
        List<FVDIntegrator.Sample> shapeBefore =
            new ArrayList<>(r.samples().subList(i0, r.spans().get(1).i1() + 1));
        c.convertWholeSection(0, 1, true, 0.01, 0.1, 0);
        Section conv = tp.sections.get(1);
        TrackCompiler.Result r2 = rebuildLoop(c);
        int j0 = r2.spans().get(1).i0();
        double drift2 = r2.trueV()[j0] - r2.samples().get(j0).v();
        double shape = 0;
        List<FVDIntegrator.Sample> after =
            r2.samples().subList(j0, r2.spans().get(1).i1() + 1);
        double sTot = Math.min(
            shapeBefore.get(shapeBefore.size() - 1).s() - shapeBefore.get(0).s(),
            after.get(after.size() - 1).s() - after.get(0).s());
        for (int i = 1; i <= 9; i++) {
            double s = shapeBefore.get(0).s() + sTot * i / 10.0;
            double s2 = after.get(0).s() + sTot * i / 10.0;
            shape = Math.max(shape,
                atS(shapeBefore, s).pos().sub(atS(after, s2).pos()).length());
        }
        System.out.printf(java.util.Locale.US,
            "after convert->geo + rebuild loop: vDesign %.2f pinned %b "
            + "drift %.2f m/s | section shape moved %.3f m%n",
            conv.vDesign(), conv.vPinned(), drift2, shape);
    }

    static TrackCompiler.Result rebuildLoop(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int pass = 0; pass < 3; pass++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    // ---------- 3. piece pitch rigidity ----------
    static void part3() {
        System.out.println("== 3. PIECE PITCH: rigid or regenerated? ==");
        for (boolean geo : new boolean[]{ false, true }) {
            Coaster c = new Coaster();
            TrackPiece tp = new TrackPiece(0, 21, 0, 0);
            if (geo) {
                List<List<BezierBaker.Node>> gl = new ArrayList<>();
                gl.add(nodes(new double[][]{ {0, 10}, {6, 10} }));
                gl.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
                gl.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
                tp.sections.add(new GeoSection(gl, 26));
            } else {
                tp.sections.add(new ForceSection(defaultLanes(), 26));
            }
            c.pieces.add(tp);
            List<FVDIntegrator.Sample> s0 =
                TrackCompiler.compile(c, 0.01).samples();
            tp.pitchDeg = 10;
            List<FVDIntegrator.Sample> s1 =
                TrackCompiler.compile(c, 0.01).samples();
            // rigid prediction: rotate s0's local offsets by +-10 deg
            // about the entry RIGHT axis
            Vec3 T0 = s0.get(0).T(), U0 = s0.get(0).U();
            Vec3 R0 = U0.cross(T0);
            Vec3 o0 = s0.get(0).pos(), o1 = s1.get(0).pos();
            double bestDev = Double.MAX_VALUE;
            for (double sign : new double[]{ 1, -1 }) {
                double dev = 0;
                int n = Math.min(s0.size(), s1.size());
                for (int i = 0; i < n; i++) {
                    Vec3 rigid = s0.get(i).pos().sub(o0)
                        .rotate(R0, Math.toRadians(sign * 10));
                    dev = Math.max(dev,
                        rigid.sub(s1.get(i).pos().sub(o1)).length());
                }
                bestDev = Math.min(bestDev, dev);
            }
            System.out.printf(java.util.Locale.US,
                "%s section: pitch 10 deg -> deviation from RIGID rotation"
                + " %.3f m (sample counts %d -> %d)%n",
                geo ? "GEO" : "FORCE", bestDev, s0.size(), s1.size());
        }
    }
}
