import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Ground-truth jitter measurement: the settled doc re-integrated at
 *  dt/4 (same vDesigns, no reconcile) is a 16x-less-sagged reference.
 *  Each export regime's vertices and up-vectors are compared against
 *  it by radial deviation / bank-angle error — the actual noise NL2
 *  receives — globally and around the reported 935 m. */
public class JitterProbe2 {

    static final String F1 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26.fvd";
    static final double DT = 0.01;

    static Object[] rebuild(Coaster c) {
        TrackCompiler.Result compiled = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            compiled = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled, true);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            compiled = TrackCompiler.compile(c, DT);
        }
        return new Object[]{ compiled };
    }

    record Line(Vec3[] p, Vec3[] t, Vec3[] u, double[] arc, double total) {}

    static Line lineOf(List<FVDIntegrator.Sample> samples, double heart) {
        int n = samples.size();
        Vec3[] p = new Vec3[n];
        Vec3[] t = new Vec3[n];
        Vec3[] u = new Vec3[n];
        double[] arc = new double[n];
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            p[i] = sm.pos().sub(sm.U().scale(heart));
            t[i] = sm.T();
            u[i] = sm.U();
            if (i > 0) arc[i] = arc[i - 1] + p[i].sub(p[i - 1]).length();
        }
        return new Line(p, t, u, arc, arc[n - 1]);
    }

    /** export-vertex positions + ups at k*step: mode 0 = lerp (the
     *  shipped construction), 1 = cubic Hermite with LINE tangents
     *  (central differences) for positions AND component-cubic ups. */
    static Vec3[][] export(Line L, double step, int mode) {
        int n = L.p.length;
        Vec3[] tl = new Vec3[n];
        for (int i = 0; i < n; i++) {
            int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
            double run = L.arc[i1] - L.arc[i0];
            tl[i] = run < 1e-12 ? L.t[i] : L.p[i1].sub(L.p[i0]).scale(1 / run);
        }
        Vec3[] ud = new Vec3[n];   // dU/darc, central
        for (int i = 0; i < n; i++) {
            int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
            double run = L.arc[i1] - L.arc[i0];
            ud[i] = run < 1e-12 ? Vec3.ZERO : L.u[i1].sub(L.u[i0]).scale(1 / run);
        }
        int nSeg = Math.max(1, (int) Math.round(L.total / step));
        double d = L.total / nSeg;
        Vec3[] pos = new Vec3[nSeg + 1];
        Vec3[] up = new Vec3[nSeg + 1];
        int idx = 0;
        for (int k = 0; k <= nSeg; k++) {
            double target = Math.min(k * d, L.total);
            while (idx < L.p.length - 2 && L.arc[idx + 1] < target) idx++;
            double span = L.arc[idx + 1] - L.arc[idx];
            double a = span < 1e-12 ? 0 : (target - L.arc[idx]) / span;
            Vec3 f;
            if (mode == 0) {
                pos[k] = L.p[idx].add(L.p[idx + 1].sub(L.p[idx]).scale(a));
                f = L.t[idx].add(L.t[idx + 1].sub(L.t[idx]).scale(a)).normalize();
                Vec3 upRaw = L.u[idx].add(L.u[idx + 1].sub(L.u[idx]).scale(a));
                Vec3 uo = upRaw.sub(f.scale(upRaw.dot(f))).normalize();
                up[k] = uo;
            } else {
                double h00 = (1 + 2 * a) * (1 - a) * (1 - a);
                double h10 = a * (1 - a) * (1 - a);
                double h01 = a * a * (3 - 2 * a);
                double h11 = a * a * (a - 1);
                pos[k] = L.p[idx].scale(h00)
                    .add(tl[idx].scale(h10 * span))
                    .add(L.p[idx + 1].scale(h01))
                    .add(tl[idx + 1].scale(h11 * span));
                // front from the Hermite derivative
                double g00 = 6 * a * a - 6 * a;
                double g10 = 3 * a * a - 4 * a + 1;
                double g01 = 6 * a - 6 * a * a;
                double g11 = 3 * a * a - 2 * a;
                f = L.p[idx].scale(g00 / span)
                    .add(tl[idx].scale(g10))
                    .add(L.p[idx + 1].scale(g01 / span))
                    .add(tl[idx + 1].scale(g11)).normalize();
                Vec3 upRaw = L.u[idx].scale(h00)
                    .add(ud[idx].scale(h10 * span))
                    .add(L.u[idx + 1].scale(h01))
                    .add(ud[idx + 1].scale(h11 * span));
                up[k] = upRaw.sub(f.scale(upRaw.dot(f))).normalize();
            }
        }
        return new Vec3[][]{ pos, up };
    }

    /** radial deviation of q from the dense reference polyline near
     *  arc s (local window search), and the reference up there. */
    static int refIdx = 0;
    static double[] refDev(Line ref, double s, Vec3 q) {
        while (refIdx < ref.p.length - 2 && ref.arc[refIdx + 1] < s) refIdx++;
        while (refIdx > 0 && ref.arc[refIdx] > s) refIdx--;
        double best = 1e18;
        for (int i = Math.max(0, refIdx - 6);
                i < Math.min(ref.p.length - 1, refIdx + 6); i++) {
            Vec3 ab = ref.p[i + 1].sub(ref.p[i]);
            double len2 = ab.dot(ab);
            double tt = len2 < 1e-18 ? 0
                : Math.min(1, Math.max(0, q.sub(ref.p[i]).dot(ab) / len2));
            Vec3 proj = ref.p[i].add(ab.scale(tt));
            best = Math.min(best, q.sub(proj).length());
        }
        return new double[]{ best };
    }

    static Vec3 refUp(Line ref, double s) {
        int i = refIdx;
        while (i < ref.p.length - 2 && ref.arc[i + 1] < s) i++;
        double span = ref.arc[i + 1] - ref.arc[i];
        double a = span < 1e-12 ? 0 : (s - ref.arc[i]) / span;
        return ref.u[i].add(ref.u[i + 1].sub(ref.u[i]).scale(a)).normalize();
    }

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        DesignIO.LoadResult lr =
            DesignIO.load(new BufferedReader(new StringReader(text)));
        Coaster c = lr.coaster();
        double heart = lr.cosmetics().heart();
        TrackCompiler.Result shipped = (TrackCompiler.Result) rebuild(c)[0];
        // ground truth: same settled doc, 4x finer integration
        TrackCompiler.Result fine = TrackCompiler.compile(c, DT / 4);
        Line L = lineOf(shipped.samples(), heart);
        Line R = lineOf(fine.samples(), heart);
        System.out.printf(Locale.US,
            "shipped %d samples / truth %d; lengths %.3f / %.3f m%n",
            shipped.samples().size(), fine.samples().size(), L.total, R.total);

        for (int mode = 0; mode <= 1; mode++) {
            Vec3[][] ex = export(L, 0.2, mode);
            Vec3[] pos = ex[0], up = ex[1];
            double step = L.total / (pos.length - 1);
            refIdx = 0;
            double worstP = 0, atP = 0, rmsP = 0;
            double worstB = 0, atB = 0, rmsB = 0;
            double worstP935 = 0, worstB935 = 0;
            for (int k = 0; k < pos.length; k++) {
                double s = k * step;
                double dev = refDev(R, s, pos[k])[0];
                double bank = Math.toDegrees(Math.acos(
                    Math.min(1, refUp(R, s).dot(up[k]))));
                if (dev > worstP) { worstP = dev; atP = s; }
                if (bank > worstB) { worstB = bank; atB = s; }
                rmsP += dev * dev;
                rmsB += bank * bank;
                if (Math.abs(s - 935) < 15) {
                    worstP935 = Math.max(worstP935, dev);
                    worstB935 = Math.max(worstB935, bank);
                }
            }
            rmsP = Math.sqrt(rmsP / pos.length);
            rmsB = Math.sqrt(rmsB / pos.length);
            System.out.printf(Locale.US,
                "%s: pos dev max %.5f mm at %.1f m (rms %.5f) | bank err max %.4f deg at %.1f m (rms %.5f) | @935: %.5f mm, %.4f deg%n",
                mode == 0 ? "LERP (shipped)" : "CUBIC         ",
                worstP * 1000, atP, rmsP * 1000,
                worstB, atB, rmsB, worstP935 * 1000, worstB935);
        }
    }
}
