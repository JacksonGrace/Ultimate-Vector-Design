import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Determinism deep dive on the user's FULL TEST COASTER (2026-07-31).
 *
 *  B) pure-compile determinism: compile(doc) twice, bitwise compare;
 *  D) the reconcile write-back loop UNCAPPED: does it quiesce? cycle?
 *     full vDesign trajectories of every section it moves;
 *  A) the app's exact rebuildTrack (3-pass cap): how many "clicks"
 *     until the track stops changing, and how far each click moves it;
 *  C) placement invariance with the write-back frozen: translate
 *     X/Z, translate Y, yaw — frame-free shape fingerprints per piece.
 */
public class ReconProbe {

    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/FULL TEST COASTER 7.30.26.fvd";
    static final double DT = 0.01;

    static Coaster load() throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static List<Section> flatSections(Coaster c) {
        List<Section> out = new ArrayList<>();
        for (TrackPiece p : c.pieces) out.addAll(p.sections);
        return out;
    }

    static double[] vDesigns(Coaster c) {
        List<Section> fs = flatSections(c);
        double[] v = new double[fs.size()];
        for (int i = 0; i < v.length; i++) v[i] = fs.get(i).vDesign();
        return v;
    }

    /** max |pos_a - pos_b| over the common prefix + end-pos delta. */
    static double[] shapeDelta(TrackCompiler.Result a, TrackCompiler.Result b) {
        int n = Math.min(a.samples().size(), b.samples().size());
        double mx = 0;
        for (int i = 0; i < n; i++)
            mx = Math.max(mx, a.samples().get(i).pos()
                    .sub(b.samples().get(i).pos()).length());
        double end = a.samples().get(a.samples().size() - 1).pos()
            .sub(b.samples().get(b.samples().size() - 1).pos()).length();
        return new double[]{ mx, end };
    }

    public static void main(String[] args) throws Exception {

        // ---------- B: pure-compile determinism ----------
        {
            Coaster c = load();
            TrackCompiler.Result r1 = TrackCompiler.compile(c, DT);
            TrackCompiler.Result r2 = TrackCompiler.compile(c, DT);
            double[] d = shapeDelta(r1, r2);
            System.out.printf(Locale.US,
                "B) compile twice, same doc: maxDelta=%.3e  endDelta=%.3e  (samples %d/%d)%n",
                d[0], d[1], r1.samples().size(), r2.samples().size());
        }

        // ---------- D: the reconcile loop, uncapped ----------
        {
            Coaster c = load();
            List<Section> fs = flatSections(c);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Result r0 = r;
            System.out.printf(Locale.US,
                "%nD) UNCAPPED reconcile loop  (%d sections, len0=%.3f m)%n",
                fs.size(), r.samples().get(r.samples().size() - 1).s());
            Map<String, Integer> seen = new HashMap<>();
            int it;
            for (it = 1; it <= 300; it++) {
                double[] before = vDesigns(c);
                boolean ch = c.reconcileChainedSpeeds(r);
                boolean ch2 = c.autoReoptimizeBridges(r);
                if (!ch && !ch2) { System.out.println("QUIESCED before iteration " + it); break; }
                TrackCompiler.Result rn = TrackCompiler.compile(c, DT);
                double[] after = vDesigns(c);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < after.length; i++)
                    if (Math.abs(after[i] - before[i]) > 1e-9) {
                        // replicate the reconcile's own reads for context
                        double vIn = Double.NaN, vTrue = Double.NaN;
                        if (i > 0 && i - 1 < r.spans().size()) {
                            int ip = r.spans().get(i - 1).i1();
                            vIn = Math.max(0.1, r.samples().get(ip).v());
                            if (ip < r.trueV().length)
                                vTrue = Math.max(0.1, r.trueV()[ip]);
                        }
                        sb.append(String.format(Locale.US,
                            "  s%d %.4f->%.4f (vIn=%.4f vTrue=%.4f)",
                            i, before[i], after[i], vIn, vTrue));
                    }
                double[] dPrev = shapeDelta(rn, r);
                double[] d0 = shapeDelta(rn, r0);
                System.out.printf(Locale.US,
                    "it %3d: dPrev=%.4f dLoad=%.4f end=%.4f len=%.3f%s%n",
                    it, dPrev[0], d0[0], d0[1],
                    rn.samples().get(rn.samples().size() - 1).s(), sb);
                String key = Arrays.toString(vDesigns(c));
                Integer first = seen.putIfAbsent(key, it);
                if (first != null) {
                    System.out.println("LIMIT CYCLE: state of it " + it
                        + " == state of it " + first
                        + " (period " + (it - first) + ")");
                    break;
                }
                r = rn;
            }
            if (it > 300) System.out.println("NO QUIESCENCE, NO EXACT CYCLE in 300 iterations");
        }

        // ---------- A: the app's exact rebuild (3-pass cap) per "click" ----------
        {
            Coaster c = load();
            TrackCompiler.Result view = null;
            System.out.printf(Locale.US, "%nA) app-style rebuilds (3-pass cap):%n");
            for (int click = 1; click <= 40; click++) {
                TrackCompiler.Result compiled = TrackCompiler.compile(c, DT);
                if (view == null) {
                    double[] z = { 0, 0 };
                    view = compiled;
                }
                for (int pass = 0; pass < 3; pass++) {
                    boolean again = c.reconcileChainedSpeeds(compiled);
                    if (c.autoReoptimizeBridges(compiled)) again = true;
                    if (!again) break;
                    compiled = TrackCompiler.compile(c, DT);
                }
                double[] d = shapeDelta(compiled, view);
                System.out.printf(Locale.US,
                    "click %2d: moved %.4f m (end %.4f m) vs previous view%n",
                    click, d[0], d[1]);
                if (d[0] < 1e-12 && click > 1) {
                    System.out.println("track stopped changing at click " + click);
                    break;
                }
                view = compiled;
            }
        }

        // ---------- C: placement invariance, write-back frozen ----------
        {
            System.out.printf(Locale.US, "%nC) placement invariance (no reconcile, fresh-loaded doc):%n");
            Coaster base = load();
            TrackCompiler.Result rb = TrackCompiler.compile(base, DT);
            int n1 = base.pieces.get(0).sections.size();
            int i1end = rb.spans().get(n1 - 1).i1();   // last sample of piece 1

            // translate X/Z and Y
            for (double[] off : new double[][]{ {100, 0, -45}, {0, 50, 0} }) {
                Coaster c = load();
                TrackPiece p = c.pieces.get(0);
                p.x += off[0]; p.y += off[1]; p.z += off[2];
                TrackCompiler.Result r = TrackCompiler.compile(c, DT);
                Vec3 o = new Vec3(off[0], off[1], off[2]);
                double mx1 = 0, mx2 = 0;
                int n = Math.min(r.samples().size(), rb.samples().size());
                for (int i = 0; i < n; i++) {
                    double d = r.samples().get(i).pos().sub(o)
                        .sub(rb.samples().get(i).pos()).length();
                    if (i <= i1end) mx1 = Math.max(mx1, d); else mx2 = Math.max(mx2, d);
                }
                System.out.printf(Locale.US,
                    "  translate (%5.0f,%3.0f,%4.0f): piece1 max %.3e m, piece2 max %.3e m (samples %d/%d)%n",
                    off[0], off[1], off[2], mx1, mx2,
                    r.samples().size(), rb.samples().size());
            }

            // yaw: frame-free fingerprint (chord lengths, v, Gv, Gl; y-rel for piece 1)
            {
                Coaster c = load();
                c.pieces.get(0).yawDeg += 37.0;
                TrackCompiler.Result r = TrackCompiler.compile(c, DT);
                int n = Math.min(r.samples().size(), rb.samples().size());
                double mx1 = 0, mx2 = 0; int argmax1 = -1;
                for (int i = 0; i < n; i++) {
                    FVDIntegrator.Sample a = r.samples().get(i), b = rb.samples().get(i);
                    double d = Math.abs(a.v() - b.v())
                        + Math.abs(a.Gv() - b.Gv()) + Math.abs(a.Gl() - b.Gl());
                    if (i > 0) d += Math.abs(
                        a.pos().sub(r.samples().get(i - 1).pos()).length()
                        - b.pos().sub(rb.samples().get(i - 1).pos()).length());
                    if (i <= i1end) {
                        d += Math.abs((a.pos().y() - r.samples().get(0).pos().y())
                                    - (b.pos().y() - rb.samples().get(0).pos().y()));
                        if (d > mx1) { mx1 = d; argmax1 = i; }
                    } else mx2 = Math.max(mx2, d);
                }
                System.out.printf(Locale.US,
                    "  yaw +37deg: piece1 fingerprint max %.3e (at sample %d), piece2 %.3e (samples %d/%d)%n",
                    mx1, argmax1, mx2, r.samples().size(), rb.samples().size());
            }
        }
    }
}
