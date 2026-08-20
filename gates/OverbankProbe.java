import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** The user's overbanked-turn report: ~0.3 m endpoint miss on a mild
 *  150 m force turn even after area matching. Decompose:
 *   (1) fitted converts at several densities (the shipped path)
 *   (2) DIRECT keyframes from the extracted data at FULL density —
 *       no fit at all: the extraction+consumption floor
 *   (3) direct keyframes resampled at the 0.05 bake grid — separates
 *       grid-resolution effects (non-commutativity across cells)
 *       from extraction bias
 *   (4) roll-lane swap: direct keyframes but roll replaced by the
 *       AUTHORED bake — isolates the measured-roll channel */
public class OverbankProbe {

    static double dt = 0.01;

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster wrap(LaneSection sec, double v) {
        Coaster c = new Coaster();
        c.v0 = v;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(sec);
        c.pieces.add(p);
        return c;
    }

    static Vec3 posAtArc(List<FVDIntegrator.Sample> s, double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        double ds = s.get(hi).s() - s.get(lo).s();
        double u = ds < 1e-12 ? 0
            : Math.max(0, Math.min(1, (arc - s.get(lo).s()) / ds));
        return s.get(lo).pos().add(
            s.get(hi).pos().sub(s.get(lo).pos()).scale(u));
    }

    static void report(String name, List<FVDIntegrator.Sample> orig,
                       List<FVDIntegrator.Sample> got) {
        FVDIntegrator.Sample e0 = orig.get(orig.size() - 1);
        FVDIntegrator.Sample e1 = got.get(got.size() - 1);
        double endMiss = e0.pos().sub(e1.pos()).length();
        double worst = 0;
        double sA = e0.s(), sB = e1.s();
        for (int k = 0; k <= 300; k++) {
            double f = k / 300.0;
            worst = Math.max(worst, posAtArc(orig, f * sA)
                .sub(posAtArc(got, f * sB)).length());
        }
        double tang = Math.toDegrees(Math.acos(Math.max(-1,
            Math.min(1, e0.T().dot(e1.T())))));
        // end bank difference
        double b0 = bank(e0), b1 = bank(e1);
        System.out.printf(java.util.Locale.US,
            "%-42s endMiss=%8.4f m  dev=%8.4f  endTang=%7.4f°  dBank=%7.3f°  arcΔ=%+.3f%n",
            name, endMiss, worst, tang, b1 - b0, e1.s() - e0.s());
    }

    static double bank(FVDIntegrator.Sample sm) {
        Vec3 T = sm.T();
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < 1e-6) return Double.NaN;
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.toDegrees(Math.atan2(ul.cross(sm.U()).dot(T), sm.U().dot(ul)));
    }

    /** Extraction identical to convertToLanes (identity re-time). */
    static double[][] extract(List<FVDIntegrator.Sample> s) {
        int n = s.size();
        double[][] out = new double[n][4];   // t, pitch, yaw, roll (deg/s)
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = s.get(i);
            double v = Math.max(0.5, sm.v());
            out[i][0] = sm.t();
            out[i][1] = Math.toDegrees(
                FVDIntegrator.G0 * (sm.Gv() - sm.U().y()) / v);
            out[i][2] = Math.toDegrees(
                FVDIntegrator.G0 * (sm.Gl() - sm.R().y()) / v);
            FVDIntegrator.Sample a = s.get(Math.max(0, i - 1));
            FVDIntegrator.Sample b = s.get(Math.min(n - 1, i + 1));
            double ds = b.s() - a.s();
            out[i][3] = ds < 1e-9 ? (i > 0 ? out[i - 1][3] : 0)
                : Math.toDegrees(-b.U().sub(a.U()).dot(sm.R()) / ds)
                    * sm.v();
        }
        return out;
    }

    static List<FVDIntegrator.Sample> geoRun(
            List<FVDIntegrator.Keyframe> kfs, double v) {
        GeoIntegrator.Result r = GeoIntegrator.integrate(kfs, dt, v,
            new Vec3(0, 21, 0), Quat.IDENTITY);
        return r.samples();
    }

    public static void main(String[] args) {
        // the user's scenario: mild overbanked force turn, ~150 m
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0,1},{1.5,2.2},{5.5,2.2},{7.5,1} }));
        fl.add(nodes(new double[][]{ {0,0},{7.5,0} }));
        fl.add(nodes(new double[][]{
            {0,0},{1,35},{2.5,35},{3.5,0},{4,0},{5,-35},{6.5,-35},{7.5,0} }));
        Coaster base = wrap(new ForceSection(fl, 20), 20);
        TrackCompiler.Result r0 = TrackCompiler.compile(base, dt);
        List<FVDIntegrator.Sample> orig = r0.samples();
        FVDIntegrator.Sample oe = orig.get(orig.size() - 1);
        System.out.printf(java.util.Locale.US,
            "fixture: %.1f m, %.2f s, vEnd=%.2f, maxBank=%.1f°%n",
            oe.s(), oe.t(), oe.v(), maxBank(orig));

        // (1) the shipped converts
        for (double[] cfg : new double[][]{ {0.1,0}, {0.05,0}, {0.1,0.0001} }) {
            LaneSection conv = Coaster.convertToLanes(orig, 20, true, dt,
                cfg[0], cfg[1]);
            TrackCompiler.Result rc = TrackCompiler.compile(wrap(conv, 20), dt);
            report(String.format(java.util.Locale.US,
                "(1) fit→geo sp=%.2f tol=%.4f", cfg[0], cfg[1]),
                orig, rc.samples());
            LaneSection convF = Coaster.convertToLanes(orig, 20, false, dt,
                cfg[0], cfg[1]);
            TrackCompiler.Result rf = TrackCompiler.compile(wrap(convF, 20), dt);
            report(String.format(java.util.Locale.US,
                "(1) fit→force sp=%.2f tol=%.4f", cfg[0], cfg[1]),
                orig, rf.samples());
        }

        // (2) DIRECT keyframes at FULL sample density — no fit
        double[][] ex = extract(orig);
        List<FVDIntegrator.Keyframe> full = new ArrayList<>();
        for (double[] row : ex) {
            full.add(new FVDIntegrator.Keyframe(row[0], row[1], row[2], row[3]));
        }
        report("(2) DIRECT keyframes, full 0.01 density", orig, geoRun(full, 20));

        // (3) direct keyframes resampled at the 0.05 bake grid
        List<FVDIntegrator.Keyframe> grid = new ArrayList<>();
        for (double t = 0; t <= ex[ex.length - 1][0] + 1e-9; t += 0.05) {
            grid.add(kfAt(ex, t));
        }
        report("(3) DIRECT keyframes, 0.05 grid", orig, geoRun(grid, 20));

        // (4) full density but roll = the AUTHORED bake instead of the
        // measured dU·R read
        List<FVDIntegrator.Keyframe> kbake = BezierBaker.bake(fl, 0.05);
        List<FVDIntegrator.Keyframe> swap = new ArrayList<>();
        for (double[] row : ex) {
            double rollAuth = interp(kbake, row[0]);
            swap.add(new FVDIntegrator.Keyframe(row[0], row[1], row[2], rollAuth));
        }
        report("(4) DIRECT full, roll=authored bake", orig, geoRun(swap, 20));

        midChain();
    }

    /** (5) the user's REAL scenario: the overbank mid-chain — a
     *  lead-in section first, then convert section 2 through the
     *  UI's span path (compile -> span sublist -> convertToLanes ->
     *  replace). Measures the whole-ride end. */
    static void midChain() {
        List<List<BezierBaker.Node>> lead = new ArrayList<>();
        lead.add(nodes(new double[][]{ {0,1},{2,1} }));
        lead.add(nodes(new double[][]{ {0,0},{2,0} }));
        lead.add(nodes(new double[][]{ {0,0},{2,0} }));
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0,1},{1.5,2.2},{5.5,2.2},{7.5,1} }));
        fl.add(nodes(new double[][]{ {0,0},{7.5,0} }));
        fl.add(nodes(new double[][]{
            {0,0},{1,35},{2.5,35},{3.5,0},{4,0},{5,-35},{6.5,-35},{7.5,0} }));
        Coaster c = new Coaster();
        c.v0 = 20;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(lead, 20));
        p.sections.add(new ForceSection(fl, 20));
        c.pieces.add(p);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, dt);
        List<FVDIntegrator.Sample> orig = r0.samples();
        // the UI span path for section index 1
        TrackCompiler.Span sp = r0.spans().get(1);
        System.out.printf(java.util.Locale.US,
            "  span i0=%d i1=%d  t[i0]=%.4f  sec entry t=%.4f (prev exit t=%.4f)%n",
            sp.i0(), sp.i1(), r0.samples().get(sp.i0()).t(),
            r0.spans().get(0).exit().t0(),
            r0.samples().get(sp.i0() - 1).t());
        // the REAL model op (routes through withEntry since v16.6)
        boolean ok1 = c.convertWholeSection(0, 1, true, dt, 0.1, 0);
        TrackCompiler.Result r1 = TrackCompiler.compile(c, dt);
        report("(5) MID-CHAIN overbank op->geo sp=0.10 ok=" + ok1,
            orig, r1.samples());
        p.sections.set(1, new ForceSection(fl, 20));   // restore
        boolean ok2 = c.convertWholeSection(0, 1, false, dt, 0.1, 0);
        TrackCompiler.Result r2 = TrackCompiler.compile(c, dt);
        report("(5) MID-CHAIN overbank op->force sp=0.10 ok=" + ok2,
            orig, r2.samples());
        p.sections.set(1, new ForceSection(fl, 20));
        boolean ok3 = c.convertWholeSection(0, 1, true, dt, 0.1, 0.0001);
        TrackCompiler.Result r3 = TrackCompiler.compile(c, dt);
        report("(5) MID-CHAIN overbank op->geo adaptive ok=" + ok3,
            orig, r3.samples());
    }

    static FVDIntegrator.Keyframe kfAt(double[][] ex, double t) {
        int n = ex.length;
        if (t <= ex[0][0]) return new FVDIntegrator.Keyframe(t, ex[0][1], ex[0][2], ex[0][3]);
        if (t >= ex[n-1][0]) return new FVDIntegrator.Keyframe(t, ex[n-1][1], ex[n-1][2], ex[n-1][3]);
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (ex[mid][0] <= t) lo = mid; else hi = mid;
        }
        double u = (t - ex[lo][0]) / Math.max(1e-12, ex[hi][0] - ex[lo][0]);
        return new FVDIntegrator.Keyframe(t,
            ex[lo][1] + u * (ex[hi][1] - ex[lo][1]),
            ex[lo][2] + u * (ex[hi][2] - ex[lo][2]),
            ex[lo][3] + u * (ex[hi][3] - ex[lo][3]));
    }

    static double interp(List<FVDIntegrator.Keyframe> kfs, double t) {
        FVDIntegrator.Keyframe prev = kfs.get(0);
        if (t <= prev.t()) return prev.rollRateDeg();
        for (FVDIntegrator.Keyframe k : kfs) {
            if (k.t() >= t) {
                double u = k.t() - prev.t() < 1e-12 ? 0
                    : (t - prev.t()) / (k.t() - prev.t());
                return prev.rollRateDeg()
                    + u * (k.rollRateDeg() - prev.rollRateDeg());
            }
            prev = k;
        }
        return prev.rollRateDeg();
    }

    static double maxBank(List<FVDIntegrator.Sample> s) {
        double m = 0;
        for (FVDIntegrator.Sample sm : s) {
            double b = bank(sm);
            if (!Double.isNaN(b)) m = Math.max(m, Math.abs(b));
        }
        return m;
    }
}
