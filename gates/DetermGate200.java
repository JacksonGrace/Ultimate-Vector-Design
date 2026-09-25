import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** D200 gate — the reconcile's ±1e-3 tube is CLOSED: a hand-reverted
 *  graph edit returns the document AND the track to their original
 *  BITS (Ctrl+Z was always exact; this is the retype-the-value path).
 *
 *   a) plain full-test file: edit an early pitch node, settle, restore
 *      the exact original double, settle -> every vDesign and every
 *      sample BITWISE == pre-edit;
 *   b) the SAME with the chaotic 0.1-knot pinned alive — any ±1e-3
 *      residue would re-roll the knot and fail loudly;
 *   c) settled idempotence still holds (leg b of the contract);
 *   d) perf report: settled rebuild cost after an early edit vs at
 *      rest (informational).
 */
public class DetermGate200 {

    static final String F1 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26.fvd";
    static final String F2 = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26 determinism test.fvd";
    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

    static Coaster load(String path) throws IOException {
        String text = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static boolean hasKnot(Coaster c) {
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (s.vDesign() < 1.0) return true;
        return false;
    }

    /** mirrors rebuildTrack at rest: fast loop + exact settle tail */
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
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled, true);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            used++;
            compiled = TrackCompiler.compile(c, DT);
        }
        return new Object[]{ compiled, used };
    }

    static boolean bitEqual(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return false;
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            if (Double.doubleToLongBits(x.pos().x()) != Double.doubleToLongBits(y.pos().x())
             || Double.doubleToLongBits(x.pos().y()) != Double.doubleToLongBits(y.pos().y())
             || Double.doubleToLongBits(x.pos().z()) != Double.doubleToLongBits(y.pos().z())
             || Double.doubleToLongBits(x.v()) != Double.doubleToLongBits(y.v()))
                return false;
        }
        return true;
    }

    static double[] vdSnap(Coaster c) {
        double[] out = new double[64];
        int i = 0;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections) out[i++] = s.vDesign();
        return out;
    }

    static int vdBitDiffs(Coaster c, double[] before) {
        int i = 0, n = 0;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (Double.doubleToLongBits(before[i++])
                        != Double.doubleToLongBits(s.vDesign())) n++;
        return n;
    }

    static BezierBaker.Node earlyNode(Coaster c) {
        // piece 0, section 0 (geo), lane 0 (pitch), node 1 — an edit
        // here cascades through the WHOLE downstream chain
        return ((LaneSection) c.pieces.get(0).sections.get(0))
            .lanes.get(0).get(1);
    }

    static void revertCase(String label, Coaster c) {
        rebuild(c);
        TrackCompiler.Result r0 = (TrackCompiler.Result) rebuild(c)[0];
        double[] vd0 = vdSnap(c);

        BezierBaker.Node nd = earlyNode(c);
        double orig = nd.val;
        nd.val = orig + 2.0;                       // the excursion
        Object[] rbE = rebuild(c);
        boolean moved = !bitEqual((TrackCompiler.Result) rbE[0], r0);

        nd.val = orig;                             // exact hand-revert
        long t0 = System.nanoTime();
        Object[] rbR = rebuild(c);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        TrackCompiler.Result rR = (TrackCompiler.Result) rbR[0];

        check(label + ": the edit genuinely reshaped", moved, "");
        check(label + ": hand-revert -> every vDesign BITWISE",
            vdBitDiffs(c, vd0) == 0, "");
        check(label + ": hand-revert -> whole track BITWISE",
            bitEqual(rR, r0),
            String.format(Locale.US, "revert settle: %d passes, %d ms",
                rbR[1], ms));
    }

    public static void main(String[] args) throws Exception {

        // ---- a) plain file ----
        revertCase("plain", load(F1));

        // ---- b) pinned chaotic knot ----
        Coaster ck;
        {   // the on-disk knot IF it survives his re-saves, else
            // the deterministic constructed one (KnotFixture)
            Coaster cand;
            try { cand = load(F2); } catch (Exception ex) { cand = null; }
            if (cand == null || !hasKnot(cand)) {
                cand = KnotFixture.build();
                rebuild(cand);
            }
            ck = cand;
        }
        boolean pinned = false;
        for (TrackPiece p : ck.pieces)
            for (Section s : p.sections)
                if (!pinned && s.vDesign() < 1.0) { s.vPinned(true); pinned = true; }
        if (!pinned) throw new IllegalStateException("fixture lost its knot");
        revertCase("knot", ck);

        // ---- c) settled idempotence (leg b) ----
        {
            Coaster c = load(F1);
            rebuild(c);
            TrackCompiler.Result a = (TrackCompiler.Result) rebuild(c)[0];
            Object[] rb = rebuild(c);
            check("settled idempotence: rebuild is a bitwise no-op",
                bitEqual((TrackCompiler.Result) rb[0], a)
                    && (Integer) rb[1] == 0,
                "passes=" + rb[1]);
        }

        // ---- d) perf report ----
        {
            Coaster c = load(F1);
            rebuild(c);
            long t0 = System.nanoTime();
            rebuild(c);
            long msIdle = (System.nanoTime() - t0) / 1_000_000;
            BezierBaker.Node nd = earlyNode(c);
            nd.val += 2.0;
            t0 = System.nanoTime();
            Object[] rb = rebuild(c);
            long msEdit = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf(Locale.US,
                "INFO  settled rebuild: at rest %d ms; after an early edit"
                + " %d ms (%d passes)%n", msIdle, msEdit, rb[1]);
        }

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
