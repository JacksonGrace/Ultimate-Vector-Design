import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Is the residual "noise" real track content? Measure the SAME
 *  high-pass metrics directly on the RAW integration samples (spine
 *  line at the samples' own spacing) and locate the 367 m feature
 *  against the section spans. */
public class JitterProbe4 {

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

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        DesignIO.LoadResult lr =
            DesignIO.load(new BufferedReader(new StringReader(text)));
        Coaster c = lr.coaster();
        double heart = lr.cosmetics().heart();
        TrackCompiler.Result r = (TrackCompiler.Result) rebuild(c)[0];
        List<FVDIntegrator.Sample> S = r.samples();

        int n = S.size();
        Vec3[] p = new Vec3[n];
        double[] arc = new double[n];
        for (int i = 0; i < n; i++) {
            p[i] = S.get(i).pos().sub(S.get(i).U().scale(heart));
            if (i > 0) arc[i] = arc[i - 1] + p[i].sub(p[i - 1]).length();
        }
        // raw-sample curvature + twist-rate, high-passed like the
        // export metric
        double[] kap = new double[n];
        double[] tw = new double[n];
        for (int k = 1; k < n - 1; k++) {
            Vec3 a = p[k].sub(p[k - 1]);
            Vec3 b = p[k + 1].sub(p[k]);
            double ds = (a.length() + b.length()) / 2;
            if (ds < 1e-9) continue;
            kap[k] = Math.acos(Math.min(1, Math.max(-1,
                a.dot(b) / (a.length() * b.length())))) / ds;
            tw[k] = Math.toDegrees(Math.acos(Math.min(1,
                S.get(k - 1).U().dot(S.get(k + 1).U())))) / (2 * ds);
        }
        double wp = 0, ap = 0, wb = 0, ab = 0;
        for (int k = 3; k < n - 3; k++) {
            double mk = (kap[k - 2] + kap[k - 1] + kap[k + 1] + kap[k + 2]) / 4;
            double mt = (tw[k - 2] + tw[k - 1] + tw[k + 1] + tw[k + 2]) / 4;
            double np = Math.abs(kap[k] - mk);
            double nb = Math.abs(tw[k] - mt);
            if (np > wp) { wp = np; ap = arc[k]; }
            if (nb > wb) { wb = nb; ab = arc[k]; }
        }
        System.out.printf(Locale.US,
            "RAW samples: curv hi-pass max %.5f 1/m at %.1f m; twist hi-pass max %.3f deg/m at %.1f m%n",
            wp, ap, wb, ab);

        // EVERY sample through the spike zone: step length, curvature,
        // twist, and the heartline-vs-spine step ratio
        System.out.println("per-sample dump 366.6..368.0 m:");
        for (int k = 1; k < n - 1; k++) {
            if (arc[k] >= 366.6 && arc[k] <= 368.0) {
                double ds = p[k].sub(p[k - 1]).length();
                double dsH = S.get(k).pos().sub(S.get(k - 1).pos()).length();
                System.out.printf(Locale.US,
                    "  s=%8.3f ds_spine=%.4f ds_heart=%.4f kap=%.4f tw=%.2f t=%.3f%n",
                    arc[k], ds, dsH, kap[k], tw[k], S.get(k).t());
            }
        }
        int si = 0;
        for (TrackCompiler.Span sp : r.spans()) {
            double s0 = S.get(sp.i0()).s(), s1 = S.get(sp.i1()).s();
            if (s1 > 360 && s0 < 375) {
                System.out.printf(Locale.US,
                    "  span %d covers %.1f..%.1f m%n", si, s0, s1);
            }
            si++;
        }
    }
}
