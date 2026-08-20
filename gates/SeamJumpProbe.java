import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The "instantaneous jump at the smoothed strict seam" report:
 *  sample-by-sample dump of design v, trueV, design felt, TRUE felt
 *  and TRUE rates across the seam at smoothing 10 / 30 / 0 m —
 *  whatever steps independently of the length is the culprit. */
public class SeamJumpProbe {

    static final String F1 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26.fvd";
    static final double DT = 0.01;

    static TrackCompiler.Result rebuild(Coaster c) {
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
        return compiled;
    }

    static void dump(String label, double smooth) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        Coaster c = DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
        c.pieces.get(1).sSmoothLen = smooth;
        TrackCompiler.Result r = rebuild(c);
        List<FVDIntegrator.Sample> S = r.samples();
        int nsec1 = c.pieces.get(0).sections.size();
        int seam = r.spans().get(nsec1 - 1).i1();
        System.out.println("== " + label + " (seam sample " + seam
            + ", s=" + String.format(Locale.US, "%.2f", S.get(seam).s()) + ") ==");
        System.out.println("   i    ds      vD     vT     Gv      Gl     GvT     GlT    pitT    yawT    rolT");
        for (int i = seam - 6; i <= seam + 6; i++) {
            FVDIntegrator.Sample sm = S.get(i);
            System.out.printf(Locale.US,
                "%6d %6.3f %6.2f %6.2f %7.3f %7.3f %7.3f %7.3f %7.1f %7.1f %7.1f%s%n",
                i, sm.s() - S.get(seam).s(), sm.v(), r.trueV()[i],
                sm.Gv(), sm.Gl(),
                r.trueFelt()[i][0], r.trueFelt()[i][1],
                r.trueRates()[i][1], r.trueRates()[i][2], r.trueRates()[i][3],
                i == seam ? "   <== seam" : "");
        }
        // the STEP measure: change across the seam sample pair vs the
        // typical per-sample change just outside
        double dGvT = Math.abs(r.trueFelt()[seam + 1][0] - r.trueFelt()[seam][0]);
        double dGlT = Math.abs(r.trueFelt()[seam + 1][1] - r.trueFelt()[seam][1]);
        double dGv = Math.abs(S.get(seam + 1).Gv() - S.get(seam).Gv());
        double refGvT = Math.abs(r.trueFelt()[seam + 5][0] - r.trueFelt()[seam + 4][0]);
        System.out.printf(Locale.US,
            "seam-step: design Gv %.3f | TRUE Gv %.3f  Gl %.3f  (typical neighbor step %.4f)%n%n",
            dGv, dGvT, dGlT, refGvT);
    }

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

    /** D203 gate: the TRUE felt must be CONTINUOUS through a smoothed
     *  conflicted strict seam (design felt steps by the honest ≠v),
     *  and a wider window must shrink the residual. */
    static double[] seamStep(double smooth) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        Coaster c = DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
        c.pieces.get(1).sSmoothLen = smooth;
        TrackCompiler.Result r = rebuild(c);
        int nsec1 = c.pieces.get(0).sections.size();
        int seam = r.spans().get(nsec1 - 1).i1();
        double dGvT = Math.abs(r.trueFelt()[seam + 1][0] - r.trueFelt()[seam][0]);
        double dGv = Math.abs(r.samples().get(seam + 1).Gv()
            - r.samples().get(seam).Gv());
        double ref = Math.abs(r.trueFelt()[seam + 5][0] - r.trueFelt()[seam + 4][0]);
        return new double[]{ dGvT, dGv, ref };
    }

    public static void main(String[] args) throws Exception {
        dump("smoothing 10 m (his file)", 10.0);
        dump("smoothing 30 m", 30.0);
        dump("smoothing OFF", 0.0);

        double[] s10 = seamStep(10);
        double[] s30 = seamStep(30);
        double[] off = seamStep(0);
        check("10 m: TRUE felt continuous at the conflicted seam (pre-fix: 1.86 g)",
            s10[0] < 0.5 && s10[0] < 6 * Math.max(0.02, s10[2]),
            String.format(Locale.US, "step %.3f g (neighbor %.3f)", s10[0], s10[2]));
        check("30 m: wider window shrinks the residual (pre-fix: 2.09 g)",
            s30[0] < 0.15 && s30[0] < s10[0],
            String.format(Locale.US, "step %.3f g", s30[0]));
        check("design felt still steps (the honest ≠v display)",
            s10[1] > 1.0, String.format(Locale.US, "%.2f g", s10[1]));
        check("smoothing OFF: the honest hard kink is untouched",
            off[0] > 2.0, String.format(Locale.US, "%.2f g", off[0]));
        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
