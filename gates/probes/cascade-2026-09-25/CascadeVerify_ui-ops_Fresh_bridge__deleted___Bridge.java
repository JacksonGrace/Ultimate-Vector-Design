package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFICATION (2026-09-25) of the reported ui-ops cascade
 *  "Fresh bridge (deleted + BridgeSection.solve as OPT_NONE) switched
 *  NONE→PY the setBridgeOptMode way (no stash → Optimize length ON,
 *  knee ~60 m)": rebuilds the configuration from the setup description
 *  on the user's file, runs the shell's rebuildTrack loop VERBATIM, and
 *  prints per pass which optSignature slots differ from the stamped
 *  signature (the auto re-optimize trigger), per-phase timings and the
 *  ms per click. Then two controls (Optimize length OFF at the same
 *  length; the as-saved bridge 0) and a candidate-fix emulation
 *  (re-capture the target head BEFORE the explicit knee + march). */
class CascadeVerify_ui_ops_Fresh_bridge__deleted___Bridge {   // package-private: the file name carries hyphens
    static final double DT = Coaster.INTEGRATE_DT;
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len(0=optLen)" };

    static long tCompile, tRecon, tReopt, tKnee, tOpt;
    static int nCompile, nReoptTrue;

    static Coaster load(String path) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(path))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    static void reset() {
        tCompile = tRecon = tReopt = tKnee = tOpt = 0;
        nCompile = nReoptTrue = 0;
    }

    static String stats() {
        return String.format(Locale.US,
            "compile %d× %.0f ms | reconcile %.0f ms | autoReopt (%d re-marches) %.0f ms"
            + " | explicit knee %.0f ms | explicit optimizeBridge %.0f ms",
            nCompile, tCompile / 1e6, tRecon / 1e6, nReoptTrue, tReopt / 1e6,
            tKnee / 1e6, tOpt / 1e6);
    }

    /** Every piece's trailing bridge: {pieceIdx, bridge}. */
    static List<Object[]> bridges(Coaster c) {
        List<Object[]> out = new ArrayList<>();
        for (int p = 0; p < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            if (!secs.isEmpty() && secs.get(secs.size() - 1) instanceof BridgeSection bs) {
                out.add(new Object[]{ p, bs });
            }
        }
        return out;
    }

    /** The slots where the CURRENT signature (what the compile just
     *  captured) differs from the STAMPED one — exactly sigEq's test
     *  (1e-9), listed so the re-march trigger is visible. */
    static String sigDiff(BridgeSection bs) {
        if (bs.optMode == BridgeSection.OPT_NONE) return "NONE mode (auto pass skips)";
        double[] sig = bs.optSignature();
        if (bs.optSig == null) return "optSig null (first sight: record only)";
        if (bs.optSig == BridgeSection.SIG_STALE) return "SIG_STALE";
        if (bs.optSig.length != sig.length) return "length mismatch";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sig.length; i++) {
            if (Math.abs(sig[i] - bs.optSig[i]) > 1e-9) {
                sb.append(String.format(Locale.US, " [%d]%s stamped=%.9g now=%.9g (Δ%.3g)",
                    i, SLOT[i], bs.optSig[i], sig[i], sig[i] - bs.optSig[i]));
            }
        }
        return sb.length() == 0 ? "MATCH" : "DIFF" + sb;
    }

    static void printSigs(Coaster c, String when) {
        for (Object[] b : bridges(c)) {
            BridgeSection bs = (BridgeSection) b[1];
            System.out.printf(Locale.US, "      %s b%d (mode %d optLen %b len %.2f): %s%n",
                when, (int) b[0], bs.optMode, bs.optLen, bs.lastOptDist, sigDiff(bs));
        }
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). verbose =
     *  print per pass the signature diffs BEFORE the auto pass. */
    static String rebuild(Coaster c, boolean verbose) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            if (verbose) printSigs(c, "fast" + pass + " pre-auto");
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0;
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            long dtRe = System.nanoTime() - t0;
            tReopt += dtRe;
            if (re) { again = true; nReoptTrue++; }
            if (verbose) System.out.printf(Locale.US,
                "      fast%d: reconcile=%b autoReopt=%b (%.0f ms)%n", pass, again && !re, re, dtRe / 1e6);
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            if (verbose) printSigs(c, "exact" + pass + " pre-auto");
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            tRecon += System.nanoTime() - t0;
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            long dtRe = System.nanoTime() - t0;
            tReopt += dtRe;
            if (re) { again = true; nReoptTrue++; }
            if (verbose) System.out.printf(Locale.US,
                "      exact%d: reconcile=%b autoReopt=%b (%.0f ms)%n", pass, again && !re, re, dtRe / 1e6);
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced;
    }

    static BridgeSection trailing(Coaster c, int p) {
        List<Section> secs = c.pieces.get(p).sections;
        return (BridgeSection) secs.get(secs.size() - 1);
    }

    /** One bridge-window spinner click on piece bp's trailing bridge, the
     *  GraphEditor.bridgeTargetWrite recipe: the target's disp write →
     *  (optLen ? optimalBridgeLength : lastOptDist) → optimizeBridge →
     *  rebuildTrack. preCapture = the candidate fix: re-capture the
     *  target's head (TrackCompiler.captureTargetHead, what the next
     *  compile will capture) BEFORE the knee + march. */
    static double click(Coaster c, int bp, int axis, double delta, String label,
                        boolean preCapture, boolean verbose) {
        BridgeSection bs = trailing(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        if (preCapture) TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d;
        if (bs.optLen) {
            long t1 = System.nanoTime();
            d = c.optimalBridgeLength(bs, py);
            tKnee += System.nanoTime() - t1;
        } else {
            d = bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        }
        long t2 = System.nanoTime();
        c.optimizeBridge(bs, bp + 1, d, py);
        tOpt += System.nanoTime() - t2;
        if (verbose) printSigs(c, "post-explicit-optimize");
        String loop = rebuild(c, verbose);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "  [%s] b%d axis %d %+.0f: %.0f ms/click — %s — len %.2f — %s%n",
            label, bp, axis, delta, ms, loop, bs.lastOptDist, stats());
        return ms;
    }

    static void describe(Coaster c, String when) {
        System.out.println("  --- " + when);
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            Section last = tp.sections.get(tp.sections.size() - 1);
            System.out.printf(Locale.US,
                "    piece %d mode=%s affect=%b nSec=%d pos=(%.3f,%.3f,%.3f) yaw=%.3f true=(%.3f,%.3f) design=(%.3f,%.3f)%s%n",
                p, tp.orientMode, tp.affectSubsequentSeams, tp.sections.size(), tp.x, tp.y, tp.z, tp.yawDeg,
                tp.pitchDeg, tp.rollDeg, tp.designPitchDeg, tp.designRollDeg,
                last instanceof BridgeSection b ? String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks=%b/%b/%b optSig=%s",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll,
                    b.optSig == null ? "null" : b.optSig == BridgeSection.SIG_STALE ? "STALE" : "stamped") : "");
        }
    }

    static double timeIntegrate(BridgeSection bs) {
        long t0 = System.nanoTime();
        bs.integrate(DT);
        return (System.nanoTime() - t0) / 1e6;
    }

    public static void main(String[] a) throws Exception {
        String path = a.length > 0 ? a[0]
            : "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
        for (int rep = 0; rep < 2; rep++) {
            System.out.println("=== rep " + rep + (rep == 0 ? " (cold JIT)" : " (warm)") + " ===");
            Coaster c = load(path);
            reset();
            System.out.println("  open: " + rebuild(c, false) + " — " + stats());
            reset();
            System.out.println("  idle rebuild: " + rebuild(c, false) + " — " + stats());
            describe(c, "as loaded + settled");

            // ---- CONTROL 0: the as-saved bridge 0 (optLen OFF), one pitch click
            click(c, 0, 1, 1.0, "control: as-saved b0, optLen off", false, rep == 1);
            click(c, 0, 1, -1.0, "control: as-saved b0, optLen off (revert)", false, false);

            // ---- SETUP: remove b0, fresh solve as OPT_NONE, add, rebuild
            TrackPiece p0 = c.pieces.get(0);
            Section removed = p0.sections.remove(p0.sections.size() - 1);
            if (!(removed instanceof BridgeSection)) throw new IllegalStateException("piece 0 did not end in a bridge");
            TrackCompiler.Result r = compile(c);
            int spanIdx = p0.sections.size() - 1;   // piece 0 is first: its last section's span
            TrackCompiler.Span sp = r.spans().get(spanIdx);
            double vEnd = r.samples().get(sp.i1()).v();
            BridgeSection br = BridgeSection.solve(sp.exit(), c.pieces.get(1).entryPose(), vEnd);
            br.optMode = BridgeSection.OPT_NONE;
            p0.sections.add(br);
            reset();
            System.out.println("  after fresh OPT_NONE add: " + rebuild(c, false) + " — " + stats());
            describe(c, "fresh OPT_NONE bridge added (locks = class defaults)");

            // ---- NONE → PY the setBridgeOptMode way (no stash → optLen ON + applyOptimalLength)
            br.optMode = BridgeSection.OPT_POS_YAW;
            br.optLen = true;
            reset();
            long t0 = System.nanoTime();
            long t1 = System.nanoTime();
            double d = c.optimalBridgeLength(br, true);
            tKnee += System.nanoTime() - t1;
            System.out.printf(Locale.US, "  NONE→PY: knee = %.2f m (%.0f ms)%n", d, tKnee / 1e6);
            if (d <= 1 + 1e-9) throw new IllegalStateException("knee at the 1 m floor — the UI would offer the attach");
            t1 = System.nanoTime();
            c.optimizeBridge(br, 1, d, true);
            tOpt += System.nanoTime() - t1;
            printSigs(c, "post-switch-optimize");
            String loop = rebuild(c, true);
            System.out.printf(Locale.US, "  NONE→PY switch total: %.0f ms — %s — %s%n",
                (System.nanoTime() - t0) / 1e6, loop, stats());
            describe(c, "after NONE→PY switch");
            System.out.printf(Locale.US, "  b0.integrate(DT) standalone at %.2f m: %.0f ms; b1.integrate(DT) at %.2f m: %.0f ms%n",
                br.lastOptDist, timeIntegrate(br), trailing(c, 1).lastOptDist, timeIntegrate(trailing(c, 1)));

            // ---- THE REPORTED CLICKS (optLen ON, REAL_ONLY target, pitch+roll locked)
            double m1 = click(c, 0, 1, 1.0, "REPORTED: optLen on", false, true);
            double m2 = click(c, 0, 1, 1.0, "REPORTED: optLen on", false, true);
            double m3 = click(c, 0, 2, 1.0, "REPORTED: optLen on", false, true);
            System.out.printf(Locale.US, "  >>> reported-config clicks: %.0f / %.0f / %.0f ms%n", m1, m2, m3);

            // ---- CONTROL 1: same bridge, Optimize length OFF at the same length
            br.optLen = false;
            c.optimizeBridge(br, 1, br.lastOptDist, true);
            reset();
            System.out.println("  optLen off rebuild: " + rebuild(c, false) + " — " + stats());
            double o1 = click(c, 0, 1, 1.0, "control: optLen OFF same len", false, true);
            double o2 = click(c, 0, 2, 1.0, "control: optLen OFF same len", false, false);
            System.out.printf(Locale.US, "  >>> optLen-off clicks: %.0f / %.0f ms%n", o1, o2);

            // ---- CANDIDATE FIX: re-capture the target head before the explicit knee + march
            br.optLen = true;
            reset();
            t1 = System.nanoTime();
            d = c.optimalBridgeLength(br, true);
            c.optimizeBridge(br, 1, d, true);
            System.out.printf(Locale.US, "  re-check optLen: knee %.2f, apply %.0f ms, %s%n", d,
                (System.nanoTime() - t1) / 1e6, rebuild(c, false));
            double f1 = click(c, 0, 1, 1.0, "FIX: pre-capture + optLen on", true, true);
            double f2 = click(c, 0, 1, 1.0, "FIX: pre-capture + optLen on", true, true);
            double f3 = click(c, 0, 2, 1.0, "FIX: pre-capture + optLen on", true, false);
            System.out.printf(Locale.US, "  >>> fix clicks: %.0f / %.0f / %.0f ms%n", f1, f2, f3);
            // does the fix change the settled answer? compare the stale-knee vs fresh-knee lengths
            TrackPiece tg = c.pieces.get(1);
            tg.setDispPitch(tg.dispPitch() + 1.0, true);
            double staleKnee = c.optimalBridgeLength(br, true);
            TrackCompiler.captureTargetHead(br, tg, DT, c.fric());
            double freshKnee = c.optimalBridgeLength(br, true);
            System.out.printf(Locale.US, "  knee on STALE capture %.4f vs FRESH capture %.4f (Δ %.4g)%n",
                staleKnee, freshKnee, freshKnee - staleKnee);
            c.optimizeBridge(br, 1, freshKnee, true);
            System.out.println("  settle after that: " + rebuild(c, false));

            // ---- CONTROL 2: fix applied to the as-saved b1 (optLen off, all locked) for the record
            double g1 = click(c, 1, 1, 1.0, "FIX: as-saved b1 optLen off", true, true);
            double g0 = click(c, 1, 1, 1.0, "NO FIX: as-saved b1 optLen off", false, true);
            System.out.printf(Locale.US, "  >>> b1 clicks fix/no-fix: %.0f / %.0f ms%n", g1, g0);
        }
    }
}
