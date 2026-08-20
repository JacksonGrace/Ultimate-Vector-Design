import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Diagnosis for the determinism-test file (2026-07-31 report #2):
 *  1) where does the ride STALL (trueV at the 0.1 floor)? which
 *     sections hold design speeds at/near the floor?
 *  2) rebuild quiescence + idempotence on THIS file;
 *  3) save->load->rebuild divergence: WHERE does it start (vs the
 *     stall knot)?
 *  4) placement moves: X/Z translate, pitch tilt (expect exact),
 *     Y translate, yaw (expect knot-chaos) — divergence start;
 *  5) do Y/yaw moves re-trigger reconcile writes (doc rewrites)?
 */
public class DiagProbe198 {

    static final String FILE = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26 determinism test.fvd";
    static final double DT = 0.01;

    static Coaster load() throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static Object[] rebuild(Coaster c) {
        TrackCompiler.Result compiled = TrackCompiler.compile(c, DT);
        int used = 0;
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            used++;
            compiled = TrackCompiler.compile(c, DT);
        }
        return new Object[]{ compiled, used };
    }

    /** first sample index where |posA-posB| > tol (after applying
     *  offset to A), or -1 */
    static int firstDiverge(TrackCompiler.Result a, TrackCompiler.Result b,
                            Vec3 off, double tol) {
        int n = Math.min(a.samples().size(), b.samples().size());
        for (int i = 0; i < n; i++)
            if (a.samples().get(i).pos().sub(off)
                    .sub(b.samples().get(i).pos()).length() > tol) return i;
        return -1;
    }

    static double maxDelta(TrackCompiler.Result a, TrackCompiler.Result b, Vec3 off) {
        int n = Math.min(a.samples().size(), b.samples().size());
        double mx = 0;
        for (int i = 0; i < n; i++)
            mx = Math.max(mx, a.samples().get(i).pos().sub(off)
                .sub(b.samples().get(i).pos()).length());
        return mx;
    }

    static String secAt(TrackCompiler.Result r, int i) {
        for (int k = 0; k < r.spans().size(); k++) {
            TrackCompiler.Span sp = r.spans().get(k);
            if (i >= sp.i0() && i <= sp.i1())
                return "sec " + k + " (samples " + sp.i0() + "-" + sp.i1() + ")";
        }
        return "past spans";
    }

    public static void main(String[] args) throws Exception {

        // 1) stall + floor map
        Coaster c = load();
        Object[] rb = rebuild(c);
        TrackCompiler.Result r0 = (TrackCompiler.Result) rb[0];
        System.out.printf(Locale.US, "rebuild: %d passes, %d samples, len %.2f m%n",
            (Integer) rb[1], r0.samples().size(),
            r0.samples().get(r0.samples().size() - 1).s());
        int stall0 = -1, stall1 = -1;
        for (int i = 0; i < r0.trueV().length; i++)
            if (r0.trueV()[i] <= 0.1 + 1e-9) { if (stall0 < 0) stall0 = i; stall1 = i; }
        System.out.println("trueV at/below floor: samples " + stall0 + ".." + stall1
            + (stall0 >= 0 ? "  [" + secAt(r0, stall0) + "]" : ""));
        int si = 0;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections) {
                if (s.vDesign() < 1.0)
                    System.out.printf(Locale.US,
                        "  vDesign at floor: flat sec %d = %.6f m/s%n", si, s.vDesign());
                si++;
            }

        // 2) idempotence on THIS file
        Object[] rb2 = rebuild(c);
        double dIdem = maxDelta((TrackCompiler.Result) rb2[0], r0, Vec3.ZERO);
        System.out.printf(Locale.US,
            "second rebuild: passes=%d delta=%.3e  (must be 0)%n",
            (Integer) rb2[1], dIdem);

        // 3) save -> load -> rebuild divergence point (current %.10f writes)
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3]);
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        Coaster c2 = DesignIO.load(new BufferedReader(new StringReader(sw.toString()))).coaster();
        Object[] rb3 = rebuild(c2);
        TrackCompiler.Result r3 = (TrackCompiler.Result) rb3[0];
        int div = firstDiverge(r3, r0, Vec3.ZERO, 1e-3);
        System.out.printf(Locale.US,
            "save->load->rebuild: passes=%d maxDelta=%.4f m, diverges (>1mm) at sample %d %s%n",
            (Integer) rb3[1], maxDelta(r3, r0, Vec3.ZERO), div,
            div >= 0 ? "[" + secAt(r0, div) + "]" : "");

        // 4) placement moves — where divergence starts
        String[] names = { "X+100/Z-45", "Y+50", "yaw+37", "pitchDelta+11" };
        for (int t = 0; t < names.length; t++) {
            Coaster cm = load();
            rebuild(cm);                        // settle first (his workflow)
            TrackCompiler.Result base = (TrackCompiler.Result) rebuild(cm)[0];
            double[] vdBefore = new double[64]; int bi = 0;
            for (TrackPiece p : cm.pieces) for (Section s : p.sections) vdBefore[bi++] = s.vDesign();
            Vec3 off = Vec3.ZERO;
            TrackPiece p1 = cm.pieces.get(0);
            switch (t) {
                case 0 -> { p1.x += 100; p1.z -= 45; off = new Vec3(100, 0, -45); }
                case 1 -> { p1.y += 50; off = new Vec3(0, 50, 0); }
                case 2 -> p1.yawDeg += 37;
                case 3 -> p1.pitchDeg += 11;
            }
            Object[] rbm = rebuild(cm);
            TrackCompiler.Result rm = (TrackCompiler.Result) rbm[0];
            int writes = 0; bi = 0;
            for (TrackPiece p : cm.pieces) for (Section s : p.sections)
                if (Math.abs(vdBefore[bi++] - s.vDesign()) > 1e-9) writes++;
            if (t <= 1) {   // pure translations: exact comparison
                int dv = firstDiverge(rm, base, off, 1e-6);
                System.out.printf(Locale.US,
                    "%s: passes=%d vDesign rewrites=%d maxDelta=%.4e m, diverges (>1um) at %d %s%n",
                    names[t], (Integer) rbm[1], writes, maxDelta(rm, base, off), dv,
                    dv >= 0 ? "[" + secAt(base, dv) + "]" : "");
            } else {        // rotations: compare frame-free speeds/chords
                int n = Math.min(rm.samples().size(), base.samples().size());
                double mx = 0; int first = -1;
                for (int i = 1; i < n; i++) {
                    double d = Math.abs(
                        rm.samples().get(i).pos().sub(rm.samples().get(i - 1).pos()).length()
                        - base.samples().get(i).pos().sub(base.samples().get(i - 1).pos()).length())
                        + Math.abs(rm.samples().get(i).v() - base.samples().get(i).v());
                    if (d > 1e-6 && first < 0) first = i;
                    mx = Math.max(mx, d);
                }
                System.out.printf(Locale.US,
                    "%s: passes=%d vDesign rewrites=%d maxFingerprint=%.4e, diverges (>1e-6) at %d %s%n",
                    names[t], (Integer) rbm[1], writes, mx, first,
                    first >= 0 ? "[" + secAt(base, first) + "]" : "");
            }
        }
    }
}
