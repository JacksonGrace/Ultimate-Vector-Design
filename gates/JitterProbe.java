import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Diagnosis probe for the two 2026-08-02 reports:
 *
 *  1) .nl2elem JITTER at 0.2 m spacing (~935 m): quantify the
 *     high-frequency curvature noise of the exported vertex polyline
 *     under four regimes — (a) lerp resample at full precision,
 *     (b) lerp + %e 7-digit quantization (the shipped writer),
 *     (c) cubic-Hermite resample full precision, (d) Hermite + %.9e.
 *     This separates the two candidate causes: chord-sag aliasing of
 *     the linear resample vs the %e format quantization.
 *
 *  2) the STRICT SEAM roughness near the end: measure the design and
 *     TRUE felt/rates through the smoothing window, the ≠v split at
 *     the piece-2 entry, and the blended orientation kink.
 */
public class JitterProbe {

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

    /** resample the spine at `step` — mode 0 = lerp (the shipped
     *  construction), 1 = cubic Hermite on positions using the sample
     *  tangents (m = T * segment arc). */
    static Vec3[] resample(List<FVDIntegrator.Sample> samples,
                           double heart, double step, int mode) {
        int n = samples.size();
        Vec3[] p = new Vec3[n];
        Vec3[] t = new Vec3[n];
        double[] arc = new double[n];
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = samples.get(i);
            p[i] = sm.pos().sub(sm.U().scale(heart));
            t[i] = sm.T();
            if (i > 0) arc[i] = arc[i - 1] + p[i].sub(p[i - 1]).length();
        }
        double total = arc[n - 1];
        // mode 2: tangents of the EXPORTED LINE itself (central
        // differences over arc) — T is the heartline tangent, wrong
        // for the offset spine by heart*dU/ds
        Vec3[] tl = t;
        if (mode == 2) {
            tl = new Vec3[n];
            for (int i = 0; i < n; i++) {
                int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
                double run = arc[i1] - arc[i0];
                tl[i] = run < 1e-12 ? t[i]
                    : p[i1].sub(p[i0]).scale(1 / run);
            }
        }
        int nSeg = Math.max(1, (int) Math.round(total / step));
        double d = total / nSeg;
        Vec3[] out = new Vec3[nSeg + 1];
        int idx = 0;
        for (int k = 0; k <= nSeg; k++) {
            double target = Math.min(k * d, total);
            while (idx < n - 2 && arc[idx + 1] < target) idx++;
            double span = arc[idx + 1] - arc[idx];
            double a = span < 1e-12 ? 0 : (target - arc[idx]) / span;
            if (mode == 0) {
                out[k] = p[idx].add(p[idx + 1].sub(p[idx]).scale(a));
            } else {
                double h00 = (1 + 2 * a) * (1 - a) * (1 - a);
                double h10 = a * (1 - a) * (1 - a);
                double h01 = a * a * (3 - 2 * a);
                double h11 = a * a * (a - 1);
                out[k] = p[idx].scale(h00)
                    .add(tl[idx].scale(h10 * span))
                    .add(p[idx + 1].scale(h01))
                    .add(tl[idx + 1].scale(h11 * span));
            }
        }
        return out;
    }

    /** quantize every component through the given printf format —
     *  what the file writer costs. */
    static Vec3[] quant(Vec3[] v, String fmt) {
        Vec3[] out = new Vec3[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = new Vec3(
                Double.parseDouble(String.format(Locale.US, fmt, v[i].x())),
                Double.parseDouble(String.format(Locale.US, fmt, v[i].y())),
                Double.parseDouble(String.format(Locale.US, fmt, v[i].z())));
        }
        return out;
    }

    /** high-frequency curvature noise: per interior vertex the local
     *  curvature (turn angle / step), minus its 5-point local mean —
     *  the alternating component NL2's spline turns into jitter.
     *  Returns {max noise, arc of max, rms}. */
    static double[] kNoise(Vec3[] v, double step) {
        int n = v.length;
        double[] kap = new double[n];
        for (int k = 1; k < n - 1; k++) {
            Vec3 a = v[k].sub(v[k - 1]);
            Vec3 b = v[k + 1].sub(v[k]);
            double cosang = a.dot(b) / (a.length() * b.length());
            kap[k] = Math.acos(Math.min(1, Math.max(-1, cosang))) / step;
        }
        double worst = 0, at = 0, sum = 0;
        int cnt = 0;
        for (int k = 3; k < n - 3; k++) {
            double mean = (kap[k - 2] + kap[k - 1] + kap[k + 1] + kap[k + 2]) / 4;
            double noise = Math.abs(kap[k] - mean);
            if (noise > worst) { worst = noise; at = k * step; }
            sum += noise * noise;
            cnt++;
        }
        return new double[]{ worst, at, Math.sqrt(sum / cnt) };
    }

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        DesignIO.LoadResult lr =
            DesignIO.load(new BufferedReader(new StringReader(text)));
        Coaster c = lr.coaster();
        double heart = lr.cosmetics().heart();
        TrackCompiler.Result r = (TrackCompiler.Result) rebuild(c)[0];
        List<FVDIntegrator.Sample> S = r.samples();

        // ---- 1) jitter regimes at 0.2 m ----
        System.out.println("== .nl2elem jitter regimes (spine, 0.2 m) ==");
        String[] names = { "lerp full-precision   ", "lerp + %e (shipped)   ",
                           "Hermite T-tangents    ", "Hermite line-tangents ",
                           "line-Hermite + %e     ", "line-Hermite + %.9e   " };
        for (int m = 0; m < 6; m++) {
            int mode = m <= 1 ? 0 : m == 2 ? 1 : 2;
            Vec3[] v = resample(S, heart, 0.2, mode);
            if (m == 1 || m == 4) v = quant(v, "%e");
            if (m == 5) v = quant(v, "%.9e");
            double[] kn = kNoise(v, 0.2);
            System.out.printf(Locale.US,
                "%s  max dK %.5f 1/m at %7.1f m   rms %.6f%n",
                names[m], kn[0], kn[1], kn[2]);
        }
        // sample spacing + curvature near his 935 m for the beat story
        for (int i = 1; i < S.size(); i++) {
            if (S.get(i).s() >= 935 && S.get(i - 1).s() < 935) {
                double ds = S.get(i).s() - S.get(i - 1).s();
                System.out.printf(Locale.US,
                    "at 935 m: sample spacing %.3f m (v=%.1f m/s design, %.1f true)%n",
                    ds, S.get(i).v(), r.trueV()[i]);
            }
        }

        // ---- 2) the strict seam near the end ----
        System.out.println("== strict seam (piece 2 follow, smoothing 10 m) ==");
        int nsec1 = c.pieces.get(0).sections.size();
        TrackCompiler.Span sa = r.spans().get(nsec1 - 1);
        int seam = sa.i1();
        double sSeam = S.get(seam).s();
        System.out.printf(Locale.US,
            "seam at %.1f m (sample %d); piece2 sec0 vDesign %.3f, true arrival %.3f (split %.2f m/s)%n",
            sSeam, seam, c.pieces.get(1).sections.get(0).vDesign(),
            r.trueV()[seam], Math.abs(r.trueV()[seam]
                - c.pieces.get(1).sections.get(0).vDesign()));
        // body-rate + felt profile across the +-8 m around the seam:
        // report max |rates| inside the 10 m window vs the 10 m just
        // outside each side (design arrays + TRUE arrays)
        double[][] rates = r.rates();
        double[][] tR = r.trueRates();
        double[][] tF = r.trueFelt();
        double inMaxR = 0, outMaxR = 0, inMaxTR = 0, outMaxTR = 0;
        double inMaxG = 0, outMaxG = 0;
        for (int i = 1; i < S.size() - 1; i++) {
            double ds = S.get(i).s() - sSeam;
            double mr = Math.max(Math.abs(rates[i][1]),
                Math.max(Math.abs(rates[i][2]), Math.abs(rates[i][3])));
            double mtr = Math.max(Math.abs(tR[i][1]),
                Math.max(Math.abs(tR[i][2]), Math.abs(tR[i][3])));
            double g = Math.abs(tF[i][0] - 1) + Math.abs(tF[i][1]);
            if (Math.abs(ds) <= 5) {
                inMaxR = Math.max(inMaxR, mr);
                inMaxTR = Math.max(inMaxTR, mtr);
                inMaxG = Math.max(inMaxG, g);
            } else if (Math.abs(ds) <= 15) {
                outMaxR = Math.max(outMaxR, mr);
                outMaxTR = Math.max(outMaxTR, mtr);
                outMaxG = Math.max(outMaxG, g);
            }
        }
        System.out.printf(Locale.US,
            "design body rates: window max %.1f deg/s vs neighborhood %.1f%n",
            inMaxR, outMaxR);
        System.out.printf(Locale.US,
            "TRUE body rates:   window max %.1f deg/s vs neighborhood %.1f%n",
            inMaxTR, outMaxTR);
        System.out.printf(Locale.US,
            "TRUE felt |Gv-1|+|Gl|: window max %.2f g vs neighborhood %.2f%n",
            inMaxG, outMaxG);
        // the kink being blended: frames just outside the window
        int a = seam, b = seam;
        while (a > 0 && S.get(a).s() > sSeam - 5) a--;
        while (b < S.size() - 1 && S.get(b).s() < sSeam + 5) b++;
        Vec3 t0 = S.get(a).T(), t1 = S.get(b).T();
        System.out.printf(Locale.US,
            "window-end tangent turn: %.1f deg over %.1f m of arc%n",
            Math.toDegrees(Math.acos(Math.min(1, t0.dot(t1)))),
            S.get(b).s() - S.get(a).s());
    }
}
