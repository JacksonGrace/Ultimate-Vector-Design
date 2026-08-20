import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Probe: WHERE does a convert's endpoint miss come from, and how big
 *  is it per fixture/density?  Measures end-pos miss, end-tangent
 *  angle, whole-track deviation, PLUS the per-lane rate-integral
 *  mismatch between the fitted lanes (baked, as the integrator sees
 *  them) and the measured data — the "area error IS the angle error"
 *  theory check. */
public class ConvEndProbe {

    static double dt = 0.01;

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

    static Coaster wrap(LaneSection sec, double v) {
        Coaster c = new Coaster();
        c.v0 = v;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(sec);
        c.pieces.add(p);
        return c;
    }

    static double valAt(double[] xs, double[][] ys, int c, double x) {
        if (x <= xs[0]) return ys[0][c];
        int hi = xs.length - 1;
        if (x >= xs[hi]) return ys[hi][c];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] <= x) lo = mid; else hi = mid;
        }
        return ys[lo][c] + (ys[hi][c] - ys[lo][c])
            * (x - xs[lo]) / Math.max(1e-12, xs[hi] - xs[lo]);
    }

    static double kv(List<FVDIntegrator.Keyframe> kfs, double x) {
        if (x <= kfs.get(0).t()) return kfs.get(0).Gv();
        int hi = kfs.size() - 1;
        if (x >= kfs.get(hi).t()) return kfs.get(hi).Gv();
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (kfs.get(mid).t() <= x) lo = mid; else hi = mid;
        }
        double u = (x - kfs.get(lo).t())
            / Math.max(1e-12, kfs.get(hi).t() - kfs.get(lo).t());
        return kfs.get(lo).Gv() + u * (kfs.get(hi).Gv() - kfs.get(lo).Gv());
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

    static double trackDev(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        double sA = a.get(a.size() - 1).s(), sB = b.get(b.size() - 1).s();
        double worst = 0;
        for (int k = 0; k <= 200; k++) {
            double f = k / 200.0;
            worst = Math.max(worst,
                posAtArc(a, f * sA).sub(posAtArc(b, f * sB)).length());
        }
        return worst;
    }

    /** Measured body rates (deg/s) per sample off a compile — the
     *  same κ-identity extraction convertToLanes uses (identity
     *  re-time: vG == sm.v()). */
    static double[][] dataRates(List<FVDIntegrator.Sample> s) {
        int n = s.size();
        double[][] rate = new double[n][3];
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = s.get(i);
            double v = Math.max(0.5, sm.v());
            rate[i][0] = Math.toDegrees(
                FVDIntegrator.G0 * (sm.Gv() - sm.U().y()) / v);
            rate[i][1] = Math.toDegrees(
                FVDIntegrator.G0 * (sm.Gl() - sm.R().y()) / v);
            FVDIntegrator.Sample a = s.get(Math.max(0, i - 1));
            FVDIntegrator.Sample b = s.get(Math.min(n - 1, i + 1));
            double ds = b.s() - a.s();
            if (ds < 1e-9) { if (i > 0) rate[i][2] = rate[i - 1][2]; continue; }
            Vec3 dU = b.U().sub(a.U());
            rate[i][2] = Math.toDegrees(-dU.dot(sm.R()) / ds) * sm.v();
        }
        return rate;
    }

    /** Trapezoid ∫rate dt of a data channel over the samples' clock. */
    static double dataInt(List<FVDIntegrator.Sample> s, double[][] rate, int c) {
        double sum = 0;
        for (int i = 1; i < s.size(); i++) {
            sum += (rate[i][c] + rate[i - 1][c]) / 2
                * (s.get(i).t() - s.get(i - 1).t());
        }
        return sum;
    }

    /** Trapezoid ∫ of one baked keyframe channel (what RK4 consumes). */
    static double bakedInt(List<FVDIntegrator.Keyframe> kfs, int c, double dur) {
        double sum = 0;
        for (int i = 1; i < kfs.size(); i++) {
            double t0 = kfs.get(i - 1).t(), t1 = Math.min(kfs.get(i).t(), dur);
            if (t1 <= t0) break;
            double v0 = ch(kfs.get(i - 1), c), v1 = ch(kfs.get(i), c);
            if (kfs.get(i).t() > dur) {   // partial last cell
                double u = (dur - t0) / (kfs.get(i).t() - t0);
                v1 = v0 + (v1 - v0) * u;
            }
            sum += (v0 + v1) / 2 * (t1 - t0);
        }
        // integrator holds the last keyframe's value to dur
        double tLast = kfs.get(kfs.size() - 1).t();
        if (tLast < dur) sum += ch(kfs.get(kfs.size() - 1), c) * (dur - tLast);
        return sum;
    }

    static double ch(FVDIntegrator.Keyframe kf, int c) {
        return c == 0 ? kf.Gv() : c == 1 ? kf.Gl() : kf.rollRateDeg();
    }

    static void probe(String name, List<FVDIntegrator.Sample> orig,
                      double vD, boolean toGeo, double spacing, double adaptTol) {
        probe(name, orig, vD, toGeo, spacing, adaptTol, Section.Domain.TIME);
    }

    static void probe(String name, List<FVDIntegrator.Sample> orig,
                      double vD, boolean toGeo, double spacing, double adaptTol,
                      Section.Domain dom) {
        LaneSection conv = Coaster.convertToLanes(orig, vD, toGeo, dt,
            spacing, adaptTol, dom);
        Coaster c = wrap(conv, vD);
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        List<FVDIntegrator.Sample> got = r.samples();
        FVDIntegrator.Sample e0 = orig.get(orig.size() - 1);
        FVDIntegrator.Sample e1 = got.get(got.size() - 1);
        double posMiss = e0.pos().sub(e1.pos()).length();
        double latMiss = posAtArc(got, Math.min(e0.s(), e1.s()))
            .sub(posAtArc(orig, Math.min(e0.s(), e1.s()))).length();
        double tangDeg = Math.toDegrees(Math.acos(Math.max(-1,
            Math.min(1, e0.T().dot(e1.T())))));
        double dev = trackDev(orig, got);
        // rate-integral mismatch (geo TIME targets only)
        String areas = "";
        if (toGeo && dom == Section.Domain.TIME) {
            double[][] dr = dataRates(orig);
            List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(conv.lanes, 0.05);
            double durK = BezierBaker.rideEnd(conv.lanes);
            StringBuilder sb = new StringBuilder("  ΔintPYR(°)=");
            for (int ch = 0; ch < 3; ch++) {
                double di = dataInt(orig, dr, ch);
                double bi = bakedInt(kfs, ch, durK);
                sb.append(String.format(java.util.Locale.US, "%+.3f ", bi - di));
            }
            areas = sb.toString();
        }
        // per-segment residual check on lane 0 (w=1 view): is the
        // chain actually zeroing segment areas?
        if (toGeo && dom == Section.Domain.TIME) {
            double[][] dr = dataRates(orig);
            double[] tGr = new double[orig.size()];
            for (int i = 1; i < orig.size(); i++) {
                tGr[i] = tGr[i-1] + (orig.get(i).s() - orig.get(i-1).s())
                    / Math.max(0.5, (Math.max(0.5, orig.get(i).v())
                        + Math.max(0.5, orig.get(i-1).v())) / 2);
            }
            List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(conv.lanes, 0.05);
            List<BezierBaker.Node> l0 = conv.lanes.get(0);
            double maxE = 0, sumE = 0;
            for (int k = 0; k + 1 < l0.size(); k++) {
                double xa = l0.get(k).t, xb = l0.get(k+1).t;
                double eD = 0, eK = 0;
                // data trapz on tGr grid
                double xp = xa, vp = valAt(tGr, dr, 0, xa);
                for (int i = 0; i < tGr.length; i++) {
                    if (tGr[i] <= xa) continue;
                    if (tGr[i] >= xb) break;
                    eD += (vp + dr[i][0]) / 2 * (tGr[i] - xp);
                    xp = tGr[i]; vp = dr[i][0];
                }
                eD += (vp + valAt(tGr, dr, 0, xb)) / 2 * (xb - xp);
                // baked trapz
                xp = xa; vp = kv(kfs, xa);
                for (FVDIntegrator.Keyframe kf : kfs) {
                    if (kf.t() <= xa) continue;
                    if (kf.t() >= xb) break;
                    eK += (vp + kf.Gv()) / 2 * (kf.t() - xp);
                    xp = kf.t(); vp = kf.Gv();
                }
                eK += (vp + kv(kfs, xb)) / 2 * (xb - xp);
                maxE = Math.max(maxE, Math.abs(eD - eK));
                sumE += eD - eK;
            }
            System.out.printf(java.util.Locale.US,
                "      [lane0 seg residuals: max=%.6f sum=%+.6f over %d segs]%n",
                maxE, sumE, l0.size() - 1);
        }
        int nn = conv.lanes.get(0).size() + conv.lanes.get(1).size()
            + conv.lanes.get(2).size();
        System.out.printf(java.util.Locale.US,
            "%-34s endMiss=%7.4f m  lat=%7.4f  endTang=%6.4f°  dev=%7.4f  arcΔ=%+.3f  n=%d%s%n",
            name, posMiss, latMiss, tangDeg, dev,
            e1.s() - e0.s(), nn, areas);
    }

    public static void main(String[] args) {
        Coaster base = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        TrackCompiler.Result r0 = TrackCompiler.compile(base, dt);
        List<FVDIntegrator.Sample> orig = r0.samples();
        System.out.println("=== default layout (407 m, force source) ===");
        probe("force→geo uniform 0.1s", orig, 26, true, 0.1, 0);
        probe("force→geo uniform 0.05s", orig, 26, true, 0.05, 0);
        probe("force→geo adaptive 0.1%", orig, 26, true, 0.1, 0.001);
        probe("force→geo adaptive 0.01%", orig, 26, true, 0.1, 0.0001);
        probe("force→force uniform 0.1s", orig, 26, false, 0.1, 0);
        probe("force→force uniform 0.05s", orig, 26, false, 0.05, 0);
        probe("force→force adaptive 0.01%", orig, 26, false, 0.1, 0.0001);
        probe("f→f DIST uniform 0.4m", orig, 26, false, 0.4, 0,
            Section.Domain.DISTANCE);
        probe("f→f DIST uniform 0.7m", orig, 26, false, 0.7, 0,
            Section.Domain.DISTANCE);
        probe("f→f DIST uniform 1.0m", orig, 26, false, 1.0, 0,
            Section.Domain.DISTANCE);
        probe("f→geo DIST uniform 0.4m", orig, 26, true, 0.4, 0,
            Section.Domain.DISTANCE);

        // round-trip decomposition: dist leg, then time leg on ITS
        // compile (the 40d combination)
        LaneSection leg1 = Coaster.convertToLanes(orig, 26, false, dt,
            0.7, 0, Section.Domain.DISTANCE);
        Coaster c1 = wrap(leg1, 26);
        TrackCompiler.Result r1 = TrackCompiler.compile(c1, dt);
        probe("RT leg2: dist→time 0.05s", r1.samples(), 26, false,
            0.05, 0, Section.Domain.TIME);
        probe("RT leg2geo: dist→time 0.05s", r1.samples(), 26, true,
            0.05, 0, Section.Domain.TIME);
        probe("RT leg2geo: dist→time 0.1s", r1.samples(), 26, true,
            0.1, 0, Section.Domain.TIME);

        // bent geo fixture — like 42a's shape but NOT stalling (the
        // 42a lanes climb past the energy ceiling and creep at the
        // 0.5 floor; per-second rates over a stall re-time
        // unstably, poisoning every convert comparison)
        List<List<BezierBaker.Node>> bl = new ArrayList<>();
        bl.add(nodes(new double[][]{ {0,0},{2,-6},{5,5},{8,0} }));
        bl.add(nodes(new double[][]{ {0,0},{3,16},{8,8} }));
        bl.add(nodes(new double[][]{ {0,0},{4,25},{6,-15},{8,0} }));
        Coaster bg = wrap(new GeoSection(bl, 26), 26);
        TrackCompiler.Result rg = TrackCompiler.compile(bg, dt);
        System.out.println("=== bent geo (geo source, dives first, no stall) ===");
        probe("geo→geo uniform 0.1s", rg.samples(), 26, true, 0.1, 0);
        probe("geo→geo adaptive 0.01%", rg.samples(), 26, true, 0.1, 0.0001);
        probe("geo→force adaptive 0.01%", rg.samples(), 26, false, 0.1, 0.0001);
    }
}
