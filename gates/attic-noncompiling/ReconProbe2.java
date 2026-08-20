import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Round 2: decompose the placement residuals (geometry vs felt,
 *  growth profile along the track), time the compile, and verify
 *  save/load idempotence at the converged fixed point. */
public class ReconProbe2 {

    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/FULL TEST COASTER 7.30.26.fvd";
    static final double DT = 0.01;

    static Coaster load() throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static String save(Coaster c) {
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3]);
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        return sw.toString();
    }

    public static void main(String[] args) throws Exception {

        // ---------- compile timing ----------
        {
            Coaster c = load();
            TrackCompiler.compile(c, DT);   // warm
            long t0 = System.nanoTime();
            int N = 20;
            for (int i = 0; i < N; i++) TrackCompiler.compile(c, DT);
            System.out.printf(Locale.US, "compile: %.1f ms each (%d samples)%n",
                (System.nanoTime() - t0) / 1e6 / N,
                TrackCompiler.compile(c, DT).samples().size());
        }

        Coaster base = load();
        TrackCompiler.Result rb = TrackCompiler.compile(base, DT);
        int n1 = base.pieces.get(0).sections.size();
        int i1end = rb.spans().get(n1 - 1).i1();

        // ---------- Y+50 decomposition ----------
        {
            Coaster c = load();
            c.pieces.get(0).y += 50;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            Vec3 off = new Vec3(0, 50, 0);
            int n = Math.min(r.samples().size(), rb.samples().size());
            double mxPos = 0, mxV = 0, mxG = 0; int am = -1;
            System.out.println("\nY+50 pos-deviation profile (m):");
            for (int i = 0; i < n; i++) {
                double d = r.samples().get(i).pos().sub(off)
                    .sub(rb.samples().get(i).pos()).length();
                if (d > mxPos) { mxPos = d; am = i; }
                mxV = Math.max(mxV, Math.abs(r.samples().get(i).v() - rb.samples().get(i).v()));
                mxG = Math.max(mxG, Math.abs(r.samples().get(i).Gv() - rb.samples().get(i).Gv())
                              + Math.abs(r.samples().get(i).Gl() - rb.samples().get(i).Gl()));
                if (i % 1000 == 0) System.out.printf(Locale.US, "  i=%5d d=%.3e%n", i, d);
            }
            System.out.printf(Locale.US,
                "  max pos dev %.3e at i=%d (of %d, piece1 ends %d); max |dv|=%.3e |dG|=%.3e%n",
                mxPos, am, n, i1end, mxV, mxG);
        }

        // ---------- yaw+37 decomposition (un-rotate about the piece origin) ----------
        {
            Coaster c = load();
            c.pieces.get(0).yawDeg += 37.0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            Vec3 org = new Vec3(base.pieces.get(0).x, base.pieces.get(0).y,
                                base.pieces.get(0).z);
            int n = Math.min(r.samples().size(), rb.samples().size());
            for (double sgn : new double[]{ 1, -1 }) {
                double a = Math.toRadians(37.0) * sgn, ca = Math.cos(a), sa = Math.sin(a);
                double mxPos = 0, mxV = 0, mxG = 0; int am = -1;
                double[] marks = new double[8];
                for (int i = 0; i < n; i++) {
                    Vec3 p = r.samples().get(i).pos().sub(org);
                    Vec3 un = new Vec3(ca * p.x() + sa * p.z(), p.y(),
                                       -sa * p.x() + ca * p.z());
                    double d = un.add(org).sub(rb.samples().get(i).pos()).length();
                    if (d > mxPos) { mxPos = d; am = i; }
                    mxV = Math.max(mxV, Math.abs(r.samples().get(i).v() - rb.samples().get(i).v()));
                    mxG = Math.max(mxG, Math.abs(r.samples().get(i).Gv() - rb.samples().get(i).Gv())
                                  + Math.abs(r.samples().get(i).Gl() - rb.samples().get(i).Gl()));
                    if (i % 1000 == 0 && i / 1000 < marks.length) marks[i / 1000] = d;
                }
                System.out.printf(Locale.US,
                    "%nyaw+37 (unrotate sign %+.0f): max pos dev %.3e at i=%d; |dv|=%.3e |dG|=%.3e%n",
                    sgn, mxPos, am, mxV, mxG);
                System.out.print("  profile:");
                for (int k = 0; k < marks.length; k++)
                    System.out.printf(Locale.US, " i%d=%.2e", k * 1000, marks[k]);
                System.out.println();
            }
        }

        // ---------- converged fixed point: save -> load -> still quiet? ----------
        {
            Coaster c = load();
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            int it = 0;
            while (it++ < 50) {
                boolean ch = c.reconcileChainedSpeeds(r);
                if (c.autoReoptimizeBridges(r)) ch = true;
                if (!ch) break;
                r = TrackCompiler.compile(c, DT);
            }
            System.out.printf(Locale.US, "%nconverged after %d passes; len=%.3f m%n",
                it - 1, r.samples().get(r.samples().size() - 1).s());

            String text = save(c);
            Coaster c2 = DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
            TrackCompiler.Result r2 = TrackCompiler.compile(c2, DT);
            boolean ch = c2.reconcileChainedSpeeds(r2);
            boolean ch2 = c2.autoReoptimizeBridges(r2);
            int m = Math.min(r.samples().size(), r2.samples().size());
            double mx = 0;
            for (int i = 0; i < m; i++)
                mx = Math.max(mx, r.samples().get(i).pos()
                    .sub(r2.samples().get(i).pos()).length());
            System.out.printf(Locale.US,
                "save->load at fixed point: reconcile writes again = %b/%b, shape delta %.3e m (samples %d/%d)%n",
                ch, ch2, mx, r.samples().size(), r2.samples().size());
        }
    }
}
