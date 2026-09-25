import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** NL2 CSV export pre-verification on the FULL TEST coaster with the
 *  friction defaults (his shakedown item): both modes (spine /
 *  heartline), three segment lengths, checking
 *   - vertex count / header / sequential indices / all-finite;
 *   - uniform spacing (chord vs step);
 *   - orthonormal frames + the NL2 "up = front x left" convention;
 *   - the D40 Z-NEGATION against the internal samples (first/last
 *     vertex == internal line with z mirrored);
 *   - frame continuity (max bend per vertex);
 *  and writes the default export (spine, 1 m) to the scratchpad.
 */
public class Nl2Probe {

    static final String F1 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26.fvd";
    static final String OUT = "C:/Users/jacks/AppData/Local/Temp/claude/"
        + "C--Users-jacks-OneDrive-Documents-AI-Fun-coaster/"
        + "0dd94887-c791-43f5-9d55-a714762b968a/scratchpad/"
        + "FULL TEST COASTER 7.30.26 - NL2 spine 1m.csv";
    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

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

    static void verify(String label, List<FVDIntegrator.Sample> samples,
                       boolean heartline, double heart, double segLen) {
        Nl2CsvExporter.Result r =
            Nl2CsvExporter.export(samples, heartline, heart, segLen);
        String[] lines = r.csv().split("\n");
        boolean okCount = lines.length == r.points() + 1
            && lines[0].startsWith("\"No.\"");
        double[][] rows = new double[r.points()][13];
        boolean finite = true, seq = true;
        for (int i = 0; i < r.points(); i++) {
            String[] f = lines[i + 1].split("\t");
            if (f.length != 13) { finite = false; break; }
            for (int j = 0; j < 13; j++) {
                rows[i][j] = Double.parseDouble(f[j]);
                if (!Double.isFinite(rows[i][j])) finite = false;
            }
            if ((int) rows[i][0] != i + 1) seq = false;
        }
        check(label + ": table shape + finite + sequential",
            okCount && finite && seq,
            r.points() + " vertices, step "
                + String.format(Locale.US, "%.4f", r.step()) + " m, len "
                + String.format(Locale.US, "%.2f", r.length()) + " m");

        // spacing: chords ~ step (chord <= arc; tight curvature dips)
        double minC = 1e9, maxC = 0;
        for (int i = 1; i < r.points(); i++) {
            double dx = rows[i][1] - rows[i - 1][1];
            double dy = rows[i][2] - rows[i - 1][2];
            double dz = rows[i][3] - rows[i - 1][3];
            double c = Math.sqrt(dx * dx + dy * dy + dz * dz);
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
        }
        check(label + ": uniform spacing (chord within [0.5,1.01]x step)",
            maxC < r.step() * 1.01 && minC > r.step() * 0.5,
            String.format(Locale.US, "chords %.4f..%.4f", minC, maxC));

        // frames: unit, orthogonal, NL2 handedness up = front x left
        double worstU = 0, worstD = 0, worstH = 0, worstBend = 0;
        double[] pf = null;
        for (int i = 0; i < r.points(); i++) {
            double[] w = rows[i];
            double[] f = { w[4], w[5], w[6] }, l = { w[7], w[8], w[9] },
                     u = { w[10], w[11], w[12] };
            worstU = Math.max(worstU, Math.abs(norm(f) - 1));
            worstU = Math.max(worstU, Math.abs(norm(l) - 1));
            worstU = Math.max(worstU, Math.abs(norm(u) - 1));
            worstD = Math.max(worstD, Math.abs(dot(f, l)));
            worstD = Math.max(worstD, Math.abs(dot(f, u)));
            worstD = Math.max(worstD, Math.abs(dot(l, u)));
            double[] fxl = cross(f, l);
            worstH = Math.max(worstH, dist(fxl, u));
            if (pf != null) worstBend = Math.max(worstBend,
                Math.toDegrees(Math.acos(Math.min(1, dot(pf, f)))));
            pf = f;
        }
        check(label + ": frames unit+orthogonal",
            worstU < 3e-5 && worstD < 3e-5,
            String.format(Locale.US, "|len-1| %.1e, dot %.1e", worstU, worstD));
        check(label + ": NL2 handedness up == front x left", worstH < 3e-5,
            String.format(Locale.US, "worst %.1e", worstH));
        System.out.printf(Locale.US,
            "INFO  %s: max frame bend per vertex %.2f deg%n", label, worstBend);

        // D40 Z-negation: vertex 0 == the internal line's start with z
        // mirrored (and the same at the end)
        FVDIntegrator.Sample s0 = samples.get(0);
        Vec3 p0 = heartline ? s0.pos() : s0.pos().sub(s0.U().scale(heart));
        FVDIntegrator.Sample sN = samples.get(samples.size() - 1);
        Vec3 pN = heartline ? sN.pos() : sN.pos().sub(sN.U().scale(heart));
        double e0 = Math.abs(rows[0][1] - p0.x())
            + Math.abs(rows[0][2] - p0.y())
            + Math.abs(rows[0][3] + p0.z());
        int n = r.points() - 1;
        double eN = Math.abs(rows[n][1] - pN.x())
            + Math.abs(rows[n][2] - pN.y())
            + Math.abs(rows[n][3] + pN.z());
        check(label + ": Z-negation anchored at both ends (D40)",
            e0 < 5e-6 && eN < 5e-6,
            String.format(Locale.US, "end errors %.1e / %.1e", e0, eN));
    }

    static double norm(double[] v) { return Math.sqrt(dot(v, v)); }
    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
    static double[] cross(double[] a, double[] b) {
        return new double[]{ a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }
    static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0])
            + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(F1)), StandardCharsets.UTF_8);
        DesignIO.LoadResult lr =
            DesignIO.load(new BufferedReader(new StringReader(text)));
        Coaster c = lr.coaster();
        double heart = lr.cosmetics().heart();
        TrackCompiler.Result compiled =
            (TrackCompiler.Result) rebuild(c)[0];
        System.out.printf(Locale.US,
            "INFO  compiled %d samples, %.2f m, heart %.2f (friction %.3f / %.1e)%n",
            compiled.samples().size(),
            compiled.samples().get(compiled.samples().size() - 1).s(),
            heart, c.fricRoll, c.fricAir);

        verify("spine 1.0m", compiled.samples(), false, heart, 1.0);
        verify("spine 0.5m", compiled.samples(), false, heart, 0.5);
        verify("spine 2.0m", compiled.samples(), false, heart, 2.0);
        verify("heartline 1.0m", compiled.samples(), true, heart, 1.0);

        // the deliverable: the default export, written for NL2 import
        Nl2CsvExporter.Result def =
            Nl2CsvExporter.export(compiled.samples(), false, heart, 1.0);
        Files.write(Paths.get(OUT), def.csv().getBytes(StandardCharsets.UTF_8));
        System.out.println("INFO  wrote " + OUT);

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
