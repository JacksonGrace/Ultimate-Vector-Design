package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE VERIFY (2026-09-25) — family ui-ops, reported configuration:
 *  "As saved (REAL_ONLY targets, pitch+roll held) but bridge 0
 *   Length := 120 m, then pitch typed 80° on its target".
 *
 *  Independent rebuild of the setup and of the bridge-window click
 *  (GraphEditor.setBridgeTargetOrient → bridgeTargetWrite →
 *  placementEdit [no carry: REAL_ONLY + affect off] → applyBridgeGap =
 *  optimizeBridge(bs, p+1, lastOptDist, alignYaw) + ONE rebuildTrack),
 *  with the shell's rebuild loop copied verbatim (12 fast + 40 exact
 *  passes, one compile each). Prints per pass: the signature slots that
 *  differ from the stamp for every live bridge (index, name, stamp,
 *  now), which bridges the auto pass re-marched, and per-phase times;
 *  standalone timings of Coaster.marchSpine / BridgeSection.integrate
 *  per state (the per-call march cost); and a SIMULATED FIX variant —
 *  TrackCompiler.captureTargetHead on the target right before
 *  optimizeBridge (what settleLockstepAlign does for LOCKSTEP only) —
 *  measured against the unfixed path with the final placements diffed.
 *
 *  Class is package-private so the file may keep the requested name
 *  (a hyphen is not legal in a class name). Run:
 *    javac -cp target/classes -d gates/out "gates/CascadeVerify_ui-ops_As_saved__REAL_ONLY_targets__p.java"
 *    java -cp "target/classes;gates/out" uvd.CascadeVerify_ui_ops_As_saved__REAL_ONLY_targets__p [reported|controls|all]
 */
class CascadeVerify_ui_ops_As_saved__REAL_ONLY_targets__p {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB·uB", "accB·rB", "jerkB·uB", "jerkB·rB", "rollB", "rollAccB",
        "vB", "tB.y", "uB.y", "lastOptDist(0=optLen)" };

    static long tCompile, tRecon, tReopt, tOpt, tWrite, tCap;
    static int nCompile, nRecon, nReopt;
    static final StringBuilder passLog = new StringBuilder();

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> secs = c.pieces.get(p).sections;
        Section last = secs.get(secs.size() - 1);
        return last instanceof BridgeSection b ? b : null;
    }

    static TrackCompiler.Result compile(Coaster c, String tag) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        long dt = System.nanoTime() - t0;
        tCompile += dt;
        nCompile++;
        passLog.append(String.format(Locale.US, "      %s: compile %.0f ms%n", tag, dt / 1e6));
        return r;
    }

    /** The signature slots of every live bridge that differ from its
     *  stamp (what autoReoptimizeBridges will see on this pass). */
    static String sigDiffs(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
            double[] sig = bs.optSignature();
            if (bs.optSig == null) { sb.append("        b").append(p).append(": optSig null (first sight — record only)\n"); continue; }
            if (bs.optSig == BridgeSection.SIG_STALE) { sb.append("        b").append(p).append(": SIG_STALE\n"); continue; }
            int n = 0;
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - bs.optSig[i]) > 1e-9) {
                    sb.append(String.format(Locale.US,
                        "        b%d slot[%d] %-14s stamp=%.6f now=%.6f d=%.3e%n",
                        p, i, SLOT[i], bs.optSig[i], sig[i], sig[i] - bs.optSig[i]));
                    n++;
                }
            }
            if (n == 0) sb.append("        b").append(p).append(": signature == stamp (no re-march)\n");
        }
        return sb.toString();
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest: gestureHot = false),
     *  instrumented. */
    static String rebuild(Coaster c, boolean verbose) {
        passLog.setLength(0);
        TrackCompiler.Result r = compile(c, "compile0");
        int fast = 0, exact = 0;
        boolean quiesced = false;
        List<String> remarched = new ArrayList<>();
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            String diffs = sigDiffs(c);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            long dRecon = System.nanoTime() - t0; tRecon += dRecon; nRecon++;
            double[][] before = stamps(c);
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            long dRe = System.nanoTime() - t0; tReopt += dRe; nReopt++;
            String who = whoRemarched(c, before);
            if (re) remarched.add("fast" + pass + ":" + who);
            passLog.append(String.format(Locale.US,
                "      fast%d: reconcile=%b (%.0f ms) autoReopt=%b (%.0f ms) re-marched=[%s]%n%s",
                pass, again, dRecon / 1e6, re, dRe / 1e6, who, diffs));
            if (re) again = true;
            if (!again) break;
            r = compile(c, "compile(fast" + pass + ")");
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            String diffs = sigDiffs(c);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            long dRecon = System.nanoTime() - t0; tRecon += dRecon; nRecon++;
            double[][] before = stamps(c);
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            long dRe = System.nanoTime() - t0; tReopt += dRe; nReopt++;
            String who = whoRemarched(c, before);
            if (re) remarched.add("exact" + pass + ":" + who);
            passLog.append(String.format(Locale.US,
                "      exact%d: reconcile=%b (%.0f ms) autoReopt=%b (%.0f ms) re-marched=[%s]%n%s",
                pass, again, dRecon / 1e6, re, dRe / 1e6, who, diffs));
            if (re) again = true;
            if (!again) { quiesced = true; break; }
            r = compile(c, "compile(exact" + pass + ")");
        }
        if (verbose) System.out.print(passLog);
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced
            + " remarches=" + remarched + " samples=" + r.samples().size();
    }

    static double[][] stamps(Coaster c) {
        double[][] s = new double[c.pieces.size()][];
        for (int p = 0; p < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            s[p] = bs == null ? null : bs.optSig;
        }
        return s;
    }

    static String whoRemarched(Coaster c, double[][] before) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null) continue;
            if (bs.optSig != before[p]) {
                if (sb.length() > 0) sb.append(",");
                sb.append("b").append(p).append(before[p] == null ? "(first sight)" : "");
            }
        }
        return sb.toString();
    }

    static void reset() {
        tCompile = tRecon = tReopt = tOpt = tWrite = tCap = 0;
        nCompile = nRecon = nReopt = 0;
    }

    static String stats() {
        return String.format(Locale.US,
            "write %.1f ms | head re-capture %.1f ms | explicit optimizeBridge %.0f ms | compile %d× %.0f ms | reconcile %d× %.0f ms | autoReopt %d× %.0f ms",
            tWrite / 1e6, tCap / 1e6, tOpt / 1e6, nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReopt, tReopt / 1e6);
    }

    static double[] pl(Coaster c, int p) {
        TrackPiece t = c.pieces.get(p);
        return new double[] { t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg };
    }

    static String placement(Coaster c, int p) {
        TrackPiece t = c.pieces.get(p);
        return String.format(Locale.US,
            "piece %d: x=%.6f y=%.6f z=%.6f yaw=%.6f pitch=%.6f roll=%.6f | disp pitch=%.3f roll=%.3f | design (%.3f, %.3f) | mode=%s",
            p, t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg, t.dispPitch(), t.dispRoll(),
            t.designPitchDeg, t.designRollDeg, t.orientMode);
    }

    /** Standalone march cost per bridge at the CURRENT captured seams:
     *  Coaster.marchSpine (= what alignPieceForBridge's marchEnd and
     *  integrate's marchSpine both run) — min of `reps` calls — and
     *  BridgeSection.integrate (marchSpine + resample). */
    static void diag(Coaster c, String label, int reps) {
        System.out.println("    -- diag [" + label + "]");
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null) continue;
            boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
            long best = Long.MAX_VALUE, worst = 0;
            List<double[]> spine = null;
            for (int k = 0; k < reps; k++) {
                long t0 = System.nanoTime();
                List<double[]> s = Coaster.marchSpine(bs, py);
                long d = System.nanoTime() - t0;
                best = Math.min(best, d); worst = Math.max(worst, d);
                if (k == 0) spine = s;
                else if ((s == null) != (spine == null)) System.out.println("       !! marchSpine not deterministic?");
            }
            long t0 = System.nanoTime();
            BridgeSection.Result ir = bs.integrate(DT, c.fric());
            long dInt = System.nanoTime() - t0;
            System.out.printf(Locale.US,
                "       b%d optMode=%d optLen=%b lastOptDist=%.2f locks=%b/%b/%b vDesign=%.3f vB=%.3f tB.y=%.4f uB.y=%.4f | marchSpine min %.0f / max %.0f ms (%d×) lands=%b rows=%d | integrate %.0f ms samples=%d%n",
                p, bs.optMode, bs.optLen, bs.lastOptDist, bs.lockYaw, bs.lockPitch, bs.lockRoll,
                bs.vDesign, bs.vB, bs.solvedTarget.T().y(), bs.solvedTarget.U().y(),
                best / 1e6, worst / 1e6, reps, spine != null, spine == null ? 0 : spine.size(),
                dInt / 1e6, ir.samples().size());
        }
        for (int p = 0; p < c.pieces.size(); p++) System.out.println("       " + placement(c, p));
    }

    /** One bridge-window PITCH spinner/typed write on bridge bp's target
     *  (the app's route: setDispPitch(v, true) → optimizeBridge at
     *  lastOptDist with alignYaw per mode → rebuildTrack). fixed = the
     *  SIMULATED FIX: re-capture the target head (what the next compile
     *  will capture) before the explicit optimizeBridge. */
    static double click(Coaster c, int bp, double pitchDisp, String label, boolean verbose, boolean fixed) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        reset();
        long t0 = System.nanoTime();
        t.setDispPitch(pitchDisp, true);
        long t1 = System.nanoTime();
        tWrite = t1 - t0;
        double staleTBy = bs.solvedTarget.T().y();
        if (fixed) {
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            tCap = System.nanoTime() - t1;
            t1 = System.nanoTime();
        }
        double len = bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        c.optimizeBridge(bs, bp + 1, len, py);
        tOpt = System.nanoTime() - t1;
        double stampTBy = bs.optSig[25];
        String loop = rebuild(c, verbose);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "  [%s%s] pitch:=%.1f on piece %d, bridge %d at %.1f m: %.0f ms/click — %s%n      %s%n      explicit march used tB.y=%.6f (stamped %.6f); compile now captures tB.y=%.6f%n",
            label, fixed ? " FIXED" : "", pitchDisp, bp + 1, bp, len, ms, loop, stats(),
            staleTBy, stampTBy, bs.solvedTarget.T().y());
        System.out.println("      " + placement(c, bp + 1));
        return ms;
    }

    static void header(Coaster c) {
        for (int p = 0; p < c.pieces.size(); p++) {
            BridgeSection b = bridgeOf(c, p);
            System.out.println("  " + placement(c, p)
                + (b == null ? "" : String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks=%b/%b/%b",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll)));
        }
    }

    static final double[] CLICKS = { 80, 81, -80, 0 };

    record Run(double[] ms, double[][] p1, double[][] p2, boolean idleNoop) {}

    /** The reported configuration, exactly as described; returns the
     *  per-click ms and the placements after each click. */
    static Run reported(String rep, boolean verbose, boolean fixed, boolean withDiag) throws IOException {
        System.out.println("=== " + rep + (fixed ? " [SIMULATED FIX]" : "") + ": REPORTED — as saved, bridge 0 Length := 120 m, then pitch 80/81/-80/0 on piece 1 ===");
        Coaster c = load();
        if (withDiag) header(c);
        reset();
        long t0 = System.nanoTime();
        String open = rebuild(c, false);
        System.out.printf(Locale.US, "  open rebuild: %.0f ms — %s%n", (System.nanoTime() - t0) / 1e6, open);
        String p1 = placement(c, 1), p2 = placement(c, 2);
        reset();
        t0 = System.nanoTime();
        String idle = rebuild(c, false);
        System.out.printf(Locale.US, "  idle rebuild: %.0f ms — %s — placements unchanged=%b%n",
            (System.nanoTime() - t0) / 1e6, idle,
            p1.equals(placement(c, 1)) && p2.equals(placement(c, 2)));
        if (withDiag) diag(c, "as saved, settled", 3);
        // SETUP: Length := 120 m on bridge 0 (the bridge window's Length
        // spinner path: optimizeBridge at the typed length + rebuild)
        BridgeSection b0 = bridgeOf(c, 0);
        reset();
        t0 = System.nanoTime();
        c.optimizeBridge(b0, 1, 120, true);
        tOpt = System.nanoTime() - t0;
        String setup = rebuild(c, verbose);
        System.out.printf(Locale.US, "  setup Length:=120 rebuild: %.0f ms — %s — %s%n", (System.nanoTime() - t0) / 1e6, setup, stats());
        if (withDiag) diag(c, "120 m, saved pitch", 3);
        // THE CLICKS
        double[] ms = new double[CLICKS.length];
        double[][] q1 = new double[CLICKS.length][], q2 = new double[CLICKS.length][];
        for (int k = 0; k < CLICKS.length; k++) {
            ms[k] = click(c, 0, CLICKS[k], rep + " click" + (k + 1), verbose, fixed);
            q1[k] = pl(c, 1);
            q2[k] = pl(c, 2);
            if (withDiag && k == 0) diag(c, "120 m, pitch 80 (after click1)", 5);
        }
        if (withDiag) diag(c, "120 m, pitch 0 (after click4)", 3);
        // idle rebuild after the clicks: must be a no-op (contract b)
        String r1 = placement(c, 1), r2 = placement(c, 2);
        reset();
        t0 = System.nanoTime();
        String idle2 = rebuild(c, false);
        boolean noop = r1.equals(placement(c, 1)) && r2.equals(placement(c, 2));
        System.out.printf(Locale.US, "  idle rebuild after clicks: %.0f ms — %s — placements unchanged=%b%n",
            (System.nanoTime() - t0) / 1e6, idle2, noop);
        return new Run(ms, q1, q2, noop);
    }

    /** CONTROL A: the same pitch clicks at the SAVED length (27 m) —
     *  separates the length from the pitch. */
    static void controlSavedLength(String rep) throws IOException {
        System.out.println("=== " + rep + ": CONTROL A — as saved (27 m), pitch 80/81/-80/0 on piece 1 ===");
        Coaster c = load();
        rebuild(c, false);
        rebuild(c, false);
        click(c, 0, 80, rep + " A1", false, false);
        diag(c, "27 m, pitch 80", 3);
        click(c, 0, 81, rep + " A2", false, false);
        click(c, 0, -80, rep + " A3", false, false);
        click(c, 0, 0, rep + " A4", false, false);
    }

    /** CONTROL B: 120 m with SMALL pitch clicks (near the saved −9°) —
     *  the length alone. */
    static void controlLengthOnly(String rep) throws IOException {
        System.out.println("=== " + rep + ": CONTROL B — Length := 120 m, pitch -8/-9/-10/-9 on piece 1 ===");
        Coaster c = load();
        rebuild(c, false);
        rebuild(c, false);
        BridgeSection b0 = bridgeOf(c, 0);
        c.optimizeBridge(b0, 1, 120, true);
        rebuild(c, false);
        click(c, 0, -8, rep + " B1", false, false);
        click(c, 0, -9, rep + " B2", false, false);
        click(c, 0, -10, rep + " B3", false, false);
        click(c, 0, -9, rep + " B4", false, false);
    }

    /** CONTROL C: the pitch sweep at 120 m — where does the per-click
     *  cost take off, and does the march land there? */
    static void controlSweep(String rep) throws IOException {
        System.out.println("=== " + rep + ": CONTROL C — Length := 120 m, pitch sweep on piece 1 ===");
        Coaster c = load();
        rebuild(c, false);
        rebuild(c, false);
        BridgeSection b0 = bridgeOf(c, 0);
        c.optimizeBridge(b0, 1, 120, true);
        rebuild(c, false);
        for (double p : new double[] { -9, 0, 20, 40, 60, 70, 80, 85, 60, 20, -20, -40, -60, -80 }) {
            click(c, 0, p, rep + " C", false, false);
            List<double[]> s = Coaster.marchSpine(b0, true);
            System.out.println("      → b0 march lands=" + (s != null));
        }
    }

    /** CONTROL D: 60 m with the pitch sweep — is 120 m load-bearing? */
    static void controlSweep60(String rep) throws IOException {
        System.out.println("=== " + rep + ": CONTROL D — Length := 60 m, pitch sweep on piece 1 ===");
        Coaster c = load();
        rebuild(c, false);
        rebuild(c, false);
        BridgeSection b0 = bridgeOf(c, 0);
        c.optimizeBridge(b0, 1, 60, true);
        rebuild(c, false);
        for (double p : new double[] { -9, 40, 70, 80, 85, -80 }) {
            click(c, 0, p, rep + " D", false, false);
            List<double[]> s = Coaster.marchSpine(b0, true);
            System.out.println("      → b0 march lands=" + (s != null));
        }
    }

    static double maxAbsDiff(double[] a, double[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    public static void main(String[] a) throws Exception {
        String which = a.length > 0 ? a[0] : "all";
        if (which.equals("all") || which.equals("reported")) {
            // rep 0 = cold JIT + full diagnostics; reps 1-2 = warm
            // (per-pass slot diffs printed on rep 1); the summary takes
            // the MIN per click — the machine may be shared with other
            // probes and single timings swing 3× here
            List<Run> unfixed = new ArrayList<>(), fixedRuns = new ArrayList<>();
            for (int rep = 0; rep < 3; rep++) {
                unfixed.add(reported("rep" + rep, rep == 1, false, rep == 0));
            }
            for (int rep = 0; rep < 3; rep++) {
                fixedRuns.add(reported("fixrep" + rep, rep == 0, true, false));
            }
            System.out.println("=== SUMMARY (min ms per click over 3 reps; placement diff = max |Δ| over x/y/z/yaw/pitch/roll, fixed vs unfixed) ===");
            for (int k = 0; k < CLICKS.length; k++) {
                double mu = Double.MAX_VALUE, mf = Double.MAX_VALUE, xu = 0, xf = 0;
                for (Run r : unfixed) { mu = Math.min(mu, r.ms[k]); xu = Math.max(xu, r.ms[k]); }
                for (Run r : fixedRuns) { mf = Math.min(mf, r.ms[k]); xf = Math.max(xf, r.ms[k]); }
                double d1 = maxAbsDiff(unfixed.get(0).p1[k], fixedRuns.get(0).p1[k]);
                double d2 = maxAbsDiff(unfixed.get(0).p2[k], fixedRuns.get(0).p2[k]);
                // rep-to-rep determinism of the unfixed path itself
                double du = maxAbsDiff(unfixed.get(0).p1[k], unfixed.get(2).p1[k]);
                System.out.printf(Locale.US,
                    "  click%d pitch:=%.0f — unfixed min %.0f (max %.0f) ms | fixed min %.0f (max %.0f) ms | piece1 |Δ| %.3e piece2 |Δ| %.3e | unfixed rep0 vs rep2 |Δ| %.3e%n",
                    k + 1, CLICKS[k], mu, xu, mf, xf, d1, d2, du);
            }
            boolean allNoop = true;
            for (Run r : unfixed) allNoop &= r.idleNoop;
            for (Run r : fixedRuns) allNoop &= r.idleNoop;
            System.out.println("  idle rebuild after clicks was a placement no-op in every run: " + allNoop);
        }
        if (which.equals("all") || which.equals("controls")) {
            // one warm-up pass so the controls aren't the cold JIT
            reported("warmup", false, false, false);
            controlSavedLength("warm");
            controlLengthOnly("warm");
            controlSweep("warm");
            controlSweep60("warm");
        }
    }
}
