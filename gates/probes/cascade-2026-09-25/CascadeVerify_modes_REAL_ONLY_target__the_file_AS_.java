package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE VERIFY (2026-09-25) — family "modes", configuration
 *  "REAL_ONLY target (the file AS SAVED), OPT_POS_YAW, yaw free only,
 *  Optimize length ON on both bridges — click on pitch".
 *
 *  Independent rebuild of the reported setup from its description
 *  (the hunter's probe was NOT read): load the user's file unchanged
 *  (REAL_ONLY targets with a design/true pitch conflict, bridge locks
 *  0/1/1 and 1/1/1), set both bridges to yaw free / pitch+roll locked,
 *  check "Optimize length" on both exactly as GraphEditor.setBridgeOptLen
 *  does (knee → optimizeBridge → rebuildTrack), then click the bridge
 *  window's pitch spinner through the bridgeTargetWrite recipe:
 *  setDispPitch(+1, route) → (no carry: REAL_ONLY + affect off) →
 *  applyOptimalLength = optimalBridgeLength → optimizeBridge → the
 *  shell's rebuildTrack loop verbatim (12 fast + 40 exact passes).
 *
 *  Per click it prints the pass counts, the wall time, a per-stage
 *  attribution (Prof's optimizeBridge counter with -Dfvd.timing=1, the
 *  probe's own compile / reconcile / autoReopt / knee timers) and, for
 *  EVERY pass, the signature slots (index + name + stamped → fresh)
 *  where a bridge's fresh optSignature differs from its stamp by more
 *  than sigEq's 1e-9 — computed read-only BEFORE the real auto pass
 *  runs, so the mechanism is visible.
 *
 *  Then a FIX TRIAL from a fresh reload: the same clicks with the
 *  target's head RE-CAPTURED (TrackCompiler.captureTargetHead — the
 *  exact call the next compile makes) between the pitch write and the
 *  knee, and the final placements of both paths compared.
 *
 *  Build: javac -cp target/classes -d gates/out gates/CascadeVerify_modes_REAL_ONLY_target__the_file_AS_.java
 *  Run:   java -Dfvd.timing=1 -cp "target/classes;gates/out" uvd.CascadeVerify_modes_REAL_ONLY_target__the_file_AS_ */
public class CascadeVerify_modes_REAL_ONLY_target__the_file_AS_ {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.uB", "accB.rB", "jerkB.uB", "jerkB.rB", "rollB", "rollAccB",
        "vB", "tB.y", "uB.y", "len(0=optLen)" };

    // per-click accumulators
    static long tCompile, tRecon, tReopt, tKneeClick, tOptClick;
    static int nCompile, nRecon, nReopt, nReoptTrue, nKneeInLoop;
    static List<String> passLog = new ArrayList<>();
    static boolean verbosePasses = true;

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(FILE))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> secs = c.pieces.get(p).sections;
        Section last = secs.get(secs.size() - 1);
        return last instanceof BridgeSection b ? b : null;
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    /** READ-ONLY diagnostic: which live optimize-mode bridges will the
     *  auto pass re-optimize, and on which signature slots. Mirrors
     *  autoReoptimizeBridges' test (Coaster.sigEq, 1e-9) without
     *  mutating anything. */
    static String sigDiff(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
            double[] sig = bs.optSignature();
            if (bs.optSig == null) { sb.append(" b").append(p).append(":first-sight(record only)"); continue; }
            if (bs.optSig.length != sig.length) { sb.append(" b").append(p).append(":SIG_STALE"); continue; }
            List<String> d = new ArrayList<>();
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - bs.optSig[i]) > 1e-9) {
                    d.add(String.format(Locale.US, "[%d %s] %.10g -> %.10g (d=%.3g)",
                        i, SLOT[i], bs.optSig[i], sig[i], sig[i] - bs.optSig[i]));
                }
            }
            if (!d.isEmpty()) {
                sb.append(" b").append(p).append(" RE-OPT on ").append(d.size())
                  .append(" slot(s): ").append(String.join("; ", d));
            }
        }
        return sb.length() == 0 ? " (every bridge matches its stamp)" : sb.toString();
    }

    /** ONE reconcile + auto-reoptimize pass with the diagnostics. */
    static boolean pass(Coaster c, TrackCompiler.Result r, boolean exact, String tag) {
        String diff = sigDiff(c);
        long t0 = System.nanoTime();
        boolean again = exact ? c.reconcileChainedSpeeds(r, true)
                              : c.reconcileChainedSpeeds(r);
        long tr = System.nanoTime() - t0;
        tRecon += tr; nRecon++;
        Prof.reset();
        t0 = System.nanoTime();
        boolean re = c.autoReoptimizeBridges(r);
        long to = System.nanoTime() - t0;
        tReopt += to; nReopt++;
        String prof = Prof.report();
        if (re) { nReoptTrue++; nKneeInLoop += countOpt(prof); }
        if (verbosePasses) {
            passLog.add(String.format(Locale.US,
                "      pass %s: reconcile %s (%.1f ms) | autoReopt %s %.0f ms [%s] |%s",
                tag, again ? "WROTE" : "-", tr / 1e6, re ? "RE-OPT" : "-", to / 1e6,
                prof.isEmpty() ? "no Prof (run with -Dfvd.timing=1)" : prof, diff));
        }
        return again || re;
    }

    /** "optimizeBridge N× ..." → N (the count of re-opts in this pass). */
    static int countOpt(String prof) {
        int i = prof.indexOf("optimizeBridge ");
        if (i < 0) return 0;
        int j = prof.indexOf('×', i);
        if (j < 0) return 0;
        try { return Integer.parseInt(prof.substring(i + 15, j).trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest: gestureHot
     *  = false — a bridge-window spinner click never sets it). */
    static String rebuild(Coaster c) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int p = 0; p < 12; p++) {
            fast++;
            if (!pass(c, r, false, "f" + p)) break;
            r = compile(c);
        }
        for (int p = 0; p < 40; p++) {
            exact++;
            if (!pass(c, r, true, "e" + p)) { quiesced = true; break; }
            r = compile(c);
        }
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced;
    }

    static void reset() {
        tCompile = tRecon = tReopt = tKneeClick = tOptClick = 0;
        nCompile = nRecon = nReopt = nReoptTrue = nKneeInLoop = 0;
        passLog.clear();
    }

    static String stats() {
        return String.format(Locale.US,
            "compile %d x %.0f ms | reconcile %d x %.0f ms | autoReopt %d x (%d re-opted, %d bridge re-opts) %.0f ms",
            nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReopt, nReoptTrue,
            nKneeInLoop, tReopt / 1e6);
    }

    static void printPieces(Coaster c) {
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            BridgeSection b = bridgeOf(c, p);
            System.out.printf(Locale.US,
                "    piece %d mode=%s affect=%b design=(%.4f,%.4f) true=(%.4f,%.4f) pos=(%.4f,%.4f,%.4f) yaw=%.4f%s%n",
                p, tp.orientMode, tp.affectSubsequentSeams, tp.designPitchDeg, tp.designRollDeg,
                tp.pitchDeg, tp.rollDeg, tp.x, tp.y, tp.z, tp.yawDeg,
                b == null ? "" : String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.4f locks(y/p/r)=%b/%b/%b",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll));
        }
    }

    /** The bridge window's pitch spinner click on bridge bp's target
     *  (GraphEditor.setBridgeTargetOrient(1, disp+1) → bridgeTargetWrite):
     *  the write, the (absent) carry, applyOptimalLength's knee, the
     *  optimize, then rebuildTrack. `recapture` = the FIX TRIAL: re-read
     *  the target's head (the compile's own capture call) between the
     *  write and the knee so the knee/march/stamp see the pitch that
     *  was just typed. Returns total ms. */
    static double click(Coaster c, int bp, double deltaDeg, boolean recapture,
                        TrackCompiler.Result lastResult, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
        long t0 = System.nanoTime();
        // placementEdit(bp+1, write): carry only under REAL_ONLY + affect
        TrackPose before = t.entryPose();
        t.setDispPitch(t.dispPitch() + deltaDeg, true);
        TrackPose after = t.entryPose();
        if (Coaster.carriesDownstream(t) && lastResult != null) {
            c.carryAfterEdit(bp + 1, before, after, lastResult.spans());
        }
        if (recapture) TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d;
        long t1 = System.nanoTime();
        if (bs.optLen) {
            d = c.optimalBridgeLength(bs, py);
            tKneeClick = System.nanoTime() - t1;
        } else {
            d = bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        }
        t1 = System.nanoTime();
        c.optimizeBridge(bs, bp + 1, d, py);
        tOptClick = System.nanoTime() - t1;
        String loop = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US,
            "  [%s] bridge %d pitch %+.0f deg%s: %.0f ms/click — %s — click knee %.0f ms (d=%.4f) + click optimizeBridge %.1f ms | loop: %s%n",
            label, bp, deltaDeg, recapture ? " (FIX: recapture)" : "", ms, loop,
            tKneeClick / 1e6, d, tOptClick / 1e6, stats());
        for (String s : passLog) System.out.println(s);
        return ms;
    }

    /** GraphEditor.setBridgeOptLen(true) → applyOptimalLength → applyBridgeGap. */
    static void checkOptimizeLength(Coaster c, int bp) {
        BridgeSection bs = bridgeOf(c, bp);
        bs.optLen = true;
        reset();
        long t0 = System.nanoTime();
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d = c.optimalBridgeLength(bs, py);
        long tk = System.nanoTime() - t0;
        c.optimizeBridge(bs, bp + 1, d, py);
        String loop = rebuild(c);
        System.out.printf(Locale.US,
            "  check Optimize length on bridge %d: knee = %.4f m in %.0f ms; rebuild %s — %s%n",
            bp, d, tk / 1e6, loop, stats());
        for (String s : passLog) System.out.println(s);
    }

    /** The reported setup: locks yaw free / pitch+roll locked on both
     *  bridges, Optimize length on both. */
    static TrackCompiler.Result setup(Coaster c, boolean carry) {
        for (int p = 0; p < 2; p++) {
            BridgeSection bs = bridgeOf(c, p);
            bs.lockYaw = false; bs.lockPitch = true; bs.lockRoll = true;
        }
        if (carry) for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).affectSubsequentSeams = true;
        reset();
        System.out.println("  open rebuild: " + rebuild(c) + " — " + stats());
        for (String s : passLog) System.out.println(s);
        reset();
        System.out.println("  idle rebuild: " + rebuild(c) + " — " + stats());
        checkOptimizeLength(c, 0);
        checkOptimizeLength(c, 1);
        reset();
        System.out.println("  idle rebuild after optLen: " + rebuild(c) + " — " + stats());
        printPieces(c);
        return TrackCompiler.compile(c, DT);
    }

    /** Is captureTargetHead after a compile bitwise the compile's own
     *  capture (the fix's safety on the auto path)? */
    static void recaptureIsNoOp(Coaster c) {
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null) continue;
            double[] a = bs.optSignature();
            TrackPose st = bs.solvedTarget;
            TrackCompiler.captureTargetHead(bs, c.pieces.get(p + 1), DT, c.fric());
            double[] b = bs.optSignature();
            boolean same = true;
            for (int i = 0; i < a.length; i++) if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(b[i])) same = false;
            boolean samePose = st.pos().equals(bs.solvedTarget.pos()) && st.orient().equals(bs.solvedTarget.orient());
            System.out.printf(Locale.US, "  recapture after compile, bridge %d: signature bitwise-equal=%b, solvedTarget equal=%b%n", p, same, samePose);
        }
    }

    static double[] placement(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece t = c.pieces.get(p);
            v.add(t.x); v.add(t.y); v.add(t.z); v.add(t.yawDeg); v.add(t.pitchDeg); v.add(t.rollDeg);
            v.add(t.designPitchDeg); v.add(t.designRollDeg);
            BridgeSection b = bridgeOf(c, p);
            if (b != null) { v.add(b.lastOptDist); v.add(b.lenA); v.add(b.lenB); }
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static void comparePlacements(double[] a, double[] b, String what) {
        double max = 0; int bitDiff = 0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, Math.abs(a[i] - b[i]));
            if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(b[i])) bitDiff++;
        }
        System.out.printf(Locale.US, "  %s: %d/%d fields differ bitwise, max |delta| = %.3g%n", what, bitDiff, a.length, max);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Prof " + (Prof.ON ? "ON" : "OFF (pass -Dfvd.timing=1 for optimizeBridge attribution)"));
        // ---- warm-up (JIT): one throwaway run of the whole recipe, quiet ----
        verbosePasses = false;
        {
            Coaster w = load();
            TrackCompiler.Result r = setup(w, false);
            for (int i = 0; i < 2; i++) click(w, 0, 1, false, r, "warm");
            click(w, 1, 1, false, r, "warm");
        }
        verbosePasses = true;

        // ---- A: the reported configuration, as described ----
        System.out.println("\n=== A: file AS SAVED (REAL_ONLY targets, conflict) + locks yaw free / pitch+roll locked on both + Optimize length ON on both — clicks on pitch ===");
        Coaster a = load();
        TrackCompiler.Result ra = setup(a, false);
        recaptureIsNoOp(a);
        double[] msA = new double[5];
        msA[0] = click(a, 0, 1, false, ra, "A");
        msA[1] = click(a, 0, 1, false, ra, "A");
        msA[2] = click(a, 0, -1, false, ra, "A");
        msA[3] = click(a, 1, 1, false, ra, "A");
        msA[4] = click(a, 1, 1, false, ra, "A");
        printPieces(a);
        double[] placeA = placement(a);

        // ---- B: same clicks, Optimize length OFF (control) ----
        System.out.println("\n=== B: control — same file + locks, Optimize length OFF (lastOptDist as saved 27 / 22) ===");
        Coaster b = load();
        for (int p = 0; p < 2; p++) { BridgeSection bs = bridgeOf(b, p); bs.lockYaw = false; bs.lockPitch = true; bs.lockRoll = true; }
        reset(); System.out.println("  open rebuild: " + rebuild(b) + " — " + stats());
        reset(); System.out.println("  idle rebuild: " + rebuild(b) + " — " + stats());
        TrackCompiler.Result rb = TrackCompiler.compile(b, DT);
        click(b, 0, 1, false, rb, "B");
        click(b, 0, 1, false, rb, "B");
        click(b, 1, 1, false, rb, "B");

        // ---- C: the file EXACTLY as saved (locks 0/1/1 and 1/1/1, optLen off) ----
        System.out.println("\n=== C: the file exactly as saved (bridge 1 locks 1/1/1, optLen off) ===");
        Coaster cc = load();
        reset(); System.out.println("  open rebuild: " + rebuild(cc) + " — " + stats());
        TrackCompiler.Result rc = TrackCompiler.compile(cc, DT);
        click(cc, 0, 1, false, rc, "C");
        click(cc, 1, 1, false, rc, "C");

        // ---- D: FIX TRIAL — recapture the target head between the write and the knee ----
        System.out.println("\n=== D: FIX TRIAL — configuration A from a fresh reload, target head re-captured after the write ===");
        Coaster d = load();
        TrackCompiler.Result rd = setup(d, false);
        double[] msD = new double[5];
        msD[0] = click(d, 0, 1, true, rd, "D");
        msD[1] = click(d, 0, 1, true, rd, "D");
        msD[2] = click(d, 0, -1, true, rd, "D");
        msD[3] = click(d, 1, 1, true, rd, "D");
        msD[4] = click(d, 1, 1, true, rd, "D");
        printPieces(d);
        comparePlacements(placeA, placement(d), "final placement A (unfixed) vs D (fixed)");
        // and the compiled track itself
        {
            List<FVDIntegrator.Sample> sa = TrackCompiler.compile(a, DT).samples();
            List<FVDIntegrator.Sample> sd = TrackCompiler.compile(d, DT).samples();
            double max = 0;
            int n = Math.min(sa.size(), sd.size());
            for (int i = 0; i < n; i++) max = Math.max(max, sa.get(i).pos().sub(sd.get(i).pos()).length());
            System.out.printf(Locale.US, "  compiled samples A vs D: %d vs %d samples, max position delta %.3g m%n", sa.size(), sd.size(), max);
        }

        // ---- E: the carry variant (affectSubsequentSeams = true on the targets) ----
        System.out.println("\n=== E: carry variant — A's setup with affectSubsequentSeams=true on pieces 1-2 ===");
        Coaster e = load();
        TrackCompiler.Result re = setup(e, true);
        click(e, 0, 1, false, re, "E");
        click(e, 1, 1, false, re, "E");

        System.out.println("\n=== summary ===");
        System.out.printf(Locale.US, "  A (as reported) ms/click: %s%n", Arrays.toString(msA));
        System.out.printf(Locale.US, "  D (fix trial)   ms/click: %s%n", Arrays.toString(msD));
    }
}
