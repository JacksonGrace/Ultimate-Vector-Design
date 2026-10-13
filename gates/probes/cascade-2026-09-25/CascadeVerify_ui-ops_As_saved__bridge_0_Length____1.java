package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFICATION (2026-09-25) of the reported ui-ops cascade
 *  "As saved, bridge 0 Length := 120 m, roll held far off (0/90/150/-149)
 *  at pitch -79" on the user's file "UVD long bridge update time
 *  9.25.26.uvd" (3 pieces, 2 OPT_POS_YAW bridges, targets REAL_ONLY,
 *  pitch/roll locked, yaw free on bridge 0 / all locked on bridge 1).
 *
 *  Rebuilds the state from the setup description ONLY (no hunter file
 *  read): open → idle → bridge 0 Length := 120 (optimizeBridge b0 at
 *  120) → a pitch sweep leaving piece 1 at dispPitch −79 → roll clicks
 *  0 / 90 / 150 / −149 / −90 / −150, each = the app's exact write path
 *  (setDispRoll(v, true) → optimizeBridge(b0, 1, 120, true) → the
 *  rebuildTrack loop verbatim). Prints per click: ms, fast/exact pass
 *  counts, quiesced, per-phase timings, and BEFORE every auto pass the
 *  signature slots of each bridge that differ from its stamp.
 *
 *  Then the same sequence with ONE candidate fix inserted — a fresh
 *  TrackCompiler.captureTargetHead(b0, piece1) between the orientation
 *  write and the explicit march (exactly what the next compile does,
 *  the D249 settle's own recipe) — and compares cost AND the resting
 *  document (placements, sample count, end position) click by click.
 *
 *  Non-public class on purpose: the file name the task prescribes is
 *  not a legal Java identifier.
 *  Build: javac -cp target/classes -d gates/out "gates/CascadeVerify_ui-ops_As_saved__bridge_0_Length____1.java"   (class name != file name on purpose)
 *  Run:   java -cp "target/classes;gates/out" uvd.CascadeVerify_ui_ops_b0len120_rollfar_p79 */
class CascadeVerify_ui_ops_b0len120_rollfar_p79 {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "lenSlot" };

    // per-rebuild accumulators
    static long tCompile, tRecon, tReopt;
    static int nCompile, nRecon, nReopt, nReoptTrue;
    static boolean verbose = true;
    static List<String> passLog = new ArrayList<>();

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(FILE))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return (BridgeSection) s.get(s.size() - 1);
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    /** Which signature slots differ from the stamp (index:name Δ). */
    static String sigDiff(BridgeSection bs) {
        double[] a = bs.optSignature(), b = bs.optSig;
        if (b == null) return "optSig=null(first sight)";
        if (b.length != a.length) return "SIG_STALE";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-9) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "[%d]%s %.6g→%.6g (Δ%.3g)",
                    i, SLOT[i], b[i], a[i], a[i] - b[i]));
            }
        }
        return sb.length() == 0 ? "match" : sb.toString();
    }

    static void logPass(Coaster c, String tag) {
        if (!verbose) return;
        StringBuilder sb = new StringBuilder("      " + tag);
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            sb.append(" | b").append(p).append(": ").append(sigDiff(bs));
        }
        passLog.add(sb.toString());
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). */
    static int[] rebuild(Coaster c) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0; nRecon++;
            logPass(c, "fast pass " + fast + " (reconcile=" + again + ")");
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            long dtRe = System.nanoTime() - t0;
            tReopt += dtRe; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (verbose) passLog.add(String.format(Locale.US,
                "        → autoReopt=%b (%.0f ms)", re, dtRe / 1e6));
            if (!again) break;
            long tc = System.nanoTime();
            r = compile(c);
            if (verbose) passLog.add(String.format(Locale.US,
                "        → compile %.0f ms", (System.nanoTime() - tc) / 1e6));
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            tRecon += System.nanoTime() - t0; nRecon++;
            logPass(c, "exact pass " + exact + " (reconcile=" + again + ")");
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            long dtRe = System.nanoTime() - t0;
            tReopt += dtRe; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (verbose) passLog.add(String.format(Locale.US,
                "        → autoReopt=%b (%.0f ms)", re, dtRe / 1e6));
            if (!again) { quiesced = true; break; }
            long tc = System.nanoTime();
            r = compile(c);
            if (verbose) passLog.add(String.format(Locale.US,
                "        → compile %.0f ms", (System.nanoTime() - tc) / 1e6));
        }
        lastResult = r;
        return new int[]{ fast, exact, quiesced ? 1 : 0 };
    }
    static TrackCompiler.Result lastResult;

    static void reset() {
        tCompile = tRecon = tReopt = 0;
        nCompile = nRecon = nReopt = nReoptTrue = 0;
        passLog.clear();
    }

    /** One bridge-window click on bridge bp's TARGET (the app's path:
     *  write → optimizeBridge at len → rebuild). precapture = the
     *  candidate fix (fresh captureTargetHead before the march). */
    static double[] click(Coaster c, int bp, Runnable write, double len,
                          boolean precapture, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
        long t0 = System.nanoTime();
        write.run();
        long tCap = 0;
        if (precapture) {
            long a = System.nanoTime();
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            tCap = System.nanoTime() - a;
        }
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t1 = System.nanoTime();
        c.optimizeBridge(bs, bp + 1, len, py);
        long tOpt = System.nanoTime() - t1;
        int[] loop = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US,
            "  [%s] %.0f ms | fast=%d exact=%d quiesced=%b | explicit opt %.0f ms%s | compiles %d× %.0f ms | reconcile %d× %.0f ms | autoReopt %d× (%d re-marched) %.0f ms%n",
            label, ms, loop[0], loop[1], loop[2] == 1, tOpt / 1e6,
            precapture ? String.format(Locale.US, " (+precapture %.0f ms)", tCap / 1e6) : "",
            nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReopt, nReoptTrue, tReopt / 1e6);
        if (verbose) for (String s : passLog) System.out.println(s);
        return new double[]{ ms, loop[0], loop[1], loop[2] };
    }

    static String placement(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 1; p < c.pieces.size(); p++) {
            TrackPiece t = c.pieces.get(p);
            sb.append(String.format(Locale.US,
                " p%d(x %.9f y %.9f z %.9f yaw %.9f pit %.9f rol %.9f dp %.6f dr %.6f)",
                p, t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
                t.designPitchDeg, t.designRollDeg));
        }
        var s = lastResult.samples();
        FVDIntegrator.Sample e = s.get(s.size() - 1);
        sb.append(String.format(Locale.US, " | n=%d end(%.9f %.9f %.9f)",
            s.size(), e.pos().x(), e.pos().y(), e.pos().z()));
        return sb.toString();
    }

    static double[] state(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (int p = 1; p < c.pieces.size(); p++) {
            TrackPiece t = c.pieces.get(p);
            v.add(t.x); v.add(t.y); v.add(t.z);
            v.add(TrackPiece.wrap180(t.yawDeg)); v.add(t.pitchDeg);
            v.add(TrackPiece.wrap180(t.rollDeg));
        }
        var s = lastResult.samples();
        FVDIntegrator.Sample e = s.get(s.size() - 1);
        v.add((double) s.size()); v.add(e.pos().x()); v.add(e.pos().y()); v.add(e.pos().z());
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static double maxAbsDiff(double[] a, double[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    /** The reported sequence. Returns per-roll-click {ms, fast, exact,
     *  quiesced} and fills states[] with the resting document. */
    static double[][] sequence(boolean precapture, boolean printPasses,
                               double[][] states, String tag) throws IOException {
        verbose = printPasses;
        Coaster c = load();
        BridgeSection b0 = bridgeOf(c, 0);
        TrackPiece p1 = c.pieces.get(1);
        System.out.println("=== " + tag + " ===");
        reset();
        long t0 = System.nanoTime();
        int[] l = rebuild(c);
        System.out.printf(Locale.US, "  open: %.0f ms fast=%d exact=%d quiesced=%b compiles=%d%n",
            (System.nanoTime() - t0) / 1e6, l[0], l[1], l[2] == 1, nCompile);
        reset(); t0 = System.nanoTime();
        l = rebuild(c);
        System.out.printf(Locale.US, "  idle: %.0f ms fast=%d exact=%d quiesced=%b compiles=%d%n",
            (System.nanoTime() - t0) / 1e6, l[0], l[1], l[2] == 1, nCompile);
        verbose = false;
        // Length := 120 on bridge 0 (the Length spinner's applyBridgeGap)
        reset(); t0 = System.nanoTime();
        c.optimizeBridge(b0, 1, 120, true);
        l = rebuild(c);
        System.out.printf(Locale.US, "  Length := 120: %.0f ms fast=%d exact=%d quiesced=%b compiles=%d | landing(marchSpine)=%s%n",
            (System.nanoTime() - t0) / 1e6, l[0], l[1], l[2] == 1, nCompile,
            Coaster.marchSpine(b0, true) != null ? "MARCH" : "SEPTIC");
        // the pitch sweep leaving the target at dispPitch −79
        verbose = printPasses;
        for (double pv : new double[]{ -20, -40, -60, -79 }) {
            final double v = pv;
            click(c, 0, () -> p1.setDispPitch(v, true), 120, precapture,
                tag + " pitch := " + v);
        }
        System.out.printf(Locale.US, "  now dispPitch=%.3f dispRoll=%.3f pitchDeg=%.3f rollDeg=%.3f mode=%s%n",
            p1.dispPitch(), p1.dispRoll(), p1.pitchDeg, p1.rollDeg, p1.orientMode);
        double[] rolls = { 0, 90, 150, -149, -90, -150 };
        double[][] out = new double[rolls.length][];
        for (int i = 0; i < rolls.length; i++) {
            final double v = rolls[i];
            out[i] = click(c, 0, () -> p1.setDispRoll(v, true), 120, precapture,
                tag + " roll := " + v);
            System.out.printf(Locale.US, "      landing(b0)=%s landing(b1)=%s dispRoll=%.3f%n",
                Coaster.marchSpine(b0, true) != null ? "MARCH" : "SEPTIC",
                Coaster.marchSpine(bridgeOf(c, 1), true) != null ? "MARCH" : "SEPTIC",
                p1.dispRoll());
            states[i] = state(c);
            if (printPasses) System.out.println("      rest:" + placement(c));
        }
        // cost attribution at the final state
        long a = System.nanoTime();
        Coaster.marchSpine(b0, true);
        long ms0 = System.nanoTime() - a;
        a = System.nanoTime();
        Coaster.marchSpine(bridgeOf(c, 1), true);
        long ms1 = System.nanoTime() - a;
        a = System.nanoTime();
        TrackCompiler.pieceHead(p1, DT, c.fric());
        long hd = System.nanoTime() - a;
        a = System.nanoTime();
        TrackCompiler.compile(c, DT);
        long cmp = System.nanoTime() - a;
        a = System.nanoTime();
        c.alignPieceForBridge(b0, 1, 120, true);
        long al = System.nanoTime() - a;
        System.out.printf(Locale.US,
            "  attribution at rest: marchSpine(b0,120m) %.0f ms | marchSpine(b1,%.0fm) %.0f ms | pieceHead(p1) %.0f ms | full compile %.0f ms | alignPieceForBridge(b0,120) %.0f ms%n",
            ms0 / 1e6, bridgeOf(c, 1).lastOptDist, ms1 / 1e6, hd / 1e6, cmp / 1e6, al / 1e6);
        return out;
    }

    public static void main(String[] args) throws Exception {
        // rep 0 warms the JIT (numbers printed but not compared)
        double[][] sA = new double[6][], sB = new double[6][];
        sequence(false, false, sA, "warm-up (app path)");
        sequence(true, false, sB, "warm-up (precapture)");
        double[][] rA = sequence(false, true, sA, "APP PATH — write → optimizeBridge(stale target) → rebuild");
        double[][] rB = sequence(true, true, sB, "FIX — write → captureTargetHead → optimizeBridge → rebuild");
        System.out.println("=== SUMMARY (warm) ===");
        double[] rolls = { 0, 90, 150, -149, -90, -150 };
        for (int i = 0; i < rolls.length; i++) {
            System.out.printf(Locale.US,
                "  roll %5.0f: app %4.0f ms (fast %d exact %d q=%b) | fix %4.0f ms (fast %d exact %d q=%b) | rest-state max|Δ| app vs fix = %.3g%n",
                rolls[i], rA[i][0], (int) rA[i][1], (int) rA[i][2], rA[i][3] == 1,
                rB[i][0], (int) rB[i][1], (int) rB[i][2], rB[i][3] == 1,
                maxAbsDiff(sA[i], sB[i]));
        }
    }
}
