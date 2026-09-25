import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Isolate the D199 gate failures: pure-compile vs rebuild, first
 *  divergent sample + owning section, for Y+50 / yaw+37 / split. */
public class DiagProbe199 {

    static final String FILE = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26 determinism test.fvd";
    static final double DT = 0.01;

    static Coaster load(String text) throws IOException {
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

    static Coaster knotDoc(String text) throws IOException {
        Coaster c = load(text);
        boolean pinned = false;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (!pinned && s.vDesign() < 1.0) { s.vPinned(true); pinned = true; }
        rebuild(c);
        return c;
    }

    static String secAt(TrackCompiler.Result r, int i) {
        for (int k = 0; k < r.spans().size(); k++) {
            TrackCompiler.Span sp = r.spans().get(k);
            if (i >= sp.i0() && i <= sp.i1()) return "span " + k;
        }
        return "past";
    }

    static void firstDiff(String tag, TrackCompiler.Result a,
                          TrackCompiler.Result b, double dy) {
        int n = Math.min(a.samples().size(), b.samples().size());
        int fv = -1, fy = -1;
        for (int i = 0; i < n; i++) {
            if (fv < 0 && Double.doubleToLongBits(a.samples().get(i).v())
                    != Double.doubleToLongBits(b.samples().get(i).v())) fv = i;
            if (fy < 0 && Math.abs(a.samples().get(i).pos().y()
                    - b.samples().get(i).pos().y() - dy) > 1e-9) fy = i;
            if (fv >= 0 && fy >= 0) break;
        }
        System.out.printf(Locale.US,
            "%s: sizes %d/%d  firstVdiff=%d %s  firstYdev=%d %s%n",
            tag, a.samples().size(), b.samples().size(),
            fv, fv >= 0 ? secAt(b, fv) : "", fy, fy >= 0 ? secAt(b, fy) : "");
    }

    public static void main(String[] args) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);

        Coaster base = knotDoc(text);
        TrackCompiler.Result r0 = TrackCompiler.compile(base, DT);

        // Y+50, PURE COMPILE (no reconcile)
        {
            Coaster c = knotDoc(text);
            c.pieces.get(0).y += 50;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            firstDiff("Y+50 pure-compile", r, r0, 50);
        }
        // yaw+37, PURE COMPILE
        {
            Coaster c = knotDoc(text);
            c.pieces.get(0).yawDeg += 37;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            firstDiff("yaw+37 pure-compile", r, r0, 0);
        }
        // split, PURE COMPILE — with duplicate-boundary awareness
        {
            Coaster c = knotDoc(text);
            TrackCompiler.Result rPre = TrackCompiler.compile(c, DT);
            int cut = 5;
            c.splitPiece(0, cut, rPre.spans().get(cut - 1));
            TrackCompiler.Result rPost = TrackCompiler.compile(c, DT);
            int b = rPre.spans().get(cut - 1).i1();
            System.out.printf(Locale.US,
                "split: pre %d samples, post %d (boundary idx %d)%n",
                rPre.samples().size(), rPost.samples().size(), b);
            // compare with the +1 duplicate offset after the boundary
            int worstI = -1; double worst = 0; boolean vOk = true; int vAt = -1;
            for (int i = 0; i < rPre.samples().size(); i++) {
                int j = i <= b ? i : i + (rPost.samples().size()
                    - rPre.samples().size());
                if (j >= rPost.samples().size()) break;
                double d = rPre.samples().get(i).pos()
                    .sub(rPost.samples().get(j).pos()).length();
                if (d > worst) { worst = d; worstI = i; }
                if (vOk && Double.doubleToLongBits(rPre.samples().get(i).v())
                        != Double.doubleToLongBits(rPost.samples().get(j).v())) {
                    vOk = false; vAt = i;
                }
            }
            System.out.printf(Locale.US,
                "split geo compare: worst %.3e m at %d %s, vBitwise=%b (first %d)%n",
                worst, worstI, worstI >= 0 ? secAt(rPre, worstI) : "",
                vOk, vAt);
        }
    }
}
