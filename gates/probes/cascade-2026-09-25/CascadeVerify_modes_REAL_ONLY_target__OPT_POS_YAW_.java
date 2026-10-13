package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFY (2026-09-25) of the reported "modes" cascade:
 *  REAL_ONLY bridge targets, both bridges in "Optimize position and
 *  orientation" (OPT_POS_YAW) with yaw LOCKED / pitch FREE / roll
 *  LOCKED and "Optimize length" ON; a bridge-window yaw spinner click
 *  (t.setDispYaw(+1) → knee → optimizeBridge → the shell's rebuild
 *  loop verbatim). Rebuilt from the setup description on the user's
 *  file, NOT from the hunter's probe. Prints per click: fast/exact
 *  passes, quiesced, ms, the stamp-vs-capture delta of the signature
 *  slots right after the click's own optimizeBridge, and per rebuild
 *  pass the signature slots that differ between the stamp and the
 *  compile's capture (index + values) for every bridge that re-opts.
 *  Then emulates two candidate fixes WITHOUT touching src/ (the
 *  probe's own optimize wrapper + a copy of autoReoptimizeBridges):
 *    F1 = re-capture the target head (TrackCompiler.captureTargetHead)
 *         after a non-LOCKSTEP alignment that moved pitch/roll, then
 *         stamp — the D249 REST GUARANTEE extended to REAL_ONLY/
 *         DESIGN_ONLY targets;
 *    F2 = F1 + ONE re-knee off the re-captured seams under Optimize
 *         length (the mirror of optimizeBridgeBody's LOCKSTEP block).
 *  Build: javac -cp target/classes -d gates/out gates/CascadeVerify_modes_REAL_ONLY_target__OPT_POS_YAW_.java
 *  Run:   java -cp "target/classes;gates/out" uvd.CascadeVerify_modes_REAL_ONLY_target__OPT_POS_YAW_ */
public class CascadeVerify_modes_REAL_ONLY_target__OPT_POS_YAW_ {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign", "accB.u", "accB.r", "jerkB.u", "jerkB.r",
        "rollB", "rollAccB", "vB", "tB.y", "uB.y", "len(0=optLen)" };

    /** 0 = stock Coaster paths; 1 = F1 (re-capture + stamp); 2 = F2
     *  (F1 + one re-knee); 3 = F3 (F1 + a re-knee ONLY when the
     *  alignment moved the head's tB.y/uB.y beyond sigEq's 1e-9). */
    static int fix = 0;
    static boolean verbose = true;

    static long tCompile, tRecon, tReopt, tKnee, tOpt;
    static int nCompile, nRecon, nReoptRun, nKnee, nOpt;

    static void reset() {
        tCompile = tRecon = tReopt = tKnee = tOpt = 0;
        nCompile = nRecon = nReoptRun = nKnee = nOpt = 0;
    }

    static String stats() {
        return String.format(Locale.US,
            "compile %d× %.0f ms | reconcile %d× %.0f ms | re-opts in loop %d (%.0f ms) | knees %d× %.0f ms | optimizeBridge %d× %.0f ms",
            nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReoptRun, tReopt / 1e6,
            nKnee, tKnee / 1e6, nOpt, tOpt / 1e6);
    }

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(FILE))) {
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

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> secs = c.pieces.get(p).sections;
        return secs.get(secs.size() - 1) instanceof BridgeSection b ? b : null;
    }

    static boolean sigEq(double[] a, double[] b) {
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > 1e-9) return false;
        return true;
    }

    static String diffSlots(double[] cur, double[] stamp) {
        if (stamp == null) return "(no stamp)";
        if (stamp.length != cur.length) return "(stale marker)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cur.length; i++) {
            double d = cur[i] - stamp[i];
            if (Math.abs(d) > 1e-9) {
                sb.append(String.format(Locale.US, " [%d]%s Δ%.3g (stamp %.9g → capture %.9g)",
                    i, SLOT[i], d, stamp[i], cur[i]));
            }
        }
        return sb.length() == 0 ? " (equal)" : sb.toString();
    }

    static double knee(Coaster c, BridgeSection bs, boolean py) {
        long t0 = System.nanoTime();
        double d = c.optimalBridgeLength(bs, py);
        tKnee += System.nanoTime() - t0;
        nKnee++;
        return d;
    }

    /** The click's / the auto pass's optimize — stock, or with the
     *  emulated fix on top. */
    static void optimize(Coaster c, BridgeSection bs, int tIdx, double dist, boolean py) {
        long t0 = System.nanoTime();
        c.optimizeBridge(bs, tIdx, dist, py);
        nOpt++;
        TrackPiece t = c.pieces.get(tIdx);
        boolean freePR = py && (!bs.lockPitch || !bs.lockRoll);
        if (fix >= 1 && freePR && t.orientMode != TrackPiece.OrientMode.LOCKSTEP) {
            // F1: the alignment rotated the target's TRUE tier — the
            // stamp must carry what the NEXT compile will capture
            double preTy = bs.solvedTarget.T().y(), preUy = bs.solvedTarget.U().y();
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            boolean moved = Math.abs(bs.solvedTarget.T().y() - preTy) > 1e-9
                || Math.abs(bs.solvedTarget.U().y() - preUy) > 1e-9;
            if ((fix == 2 || (fix == 3 && moved)) && bs.optLen) {
                // F2: one re-knee off the re-captured seams, like the
                // LOCKSTEP block; re-align at the new length, re-capture
                tOpt += System.nanoTime() - t0;
                double d2 = knee(c, bs, py);
                t0 = System.nanoTime();
                if (Math.abs(d2 - dist) > 1e-9) {
                    c.optimizeBridge(bs, tIdx, d2, py);
                    nOpt++;
                    TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
                }
            }
            bs.optSig = bs.optSignature();
        }
        tOpt += System.nanoTime() - t0;
    }

    /** Coaster.autoReoptimizeBridges, copied so the emulated fix can
     *  ride the loop; fix == 0 delegates to the real one (after
     *  printing the differing slots, which is read-only). */
    static boolean autoReopt(Coaster c, TrackCompiler.Result r, String tag) {
        // read-only: which bridges WILL re-opt and on which slots
        int spanIdx = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++, spanIdx++) {
                if (!(secs.get(k) instanceof BridgeSection bs)) continue;
                if (bs.optMode == BridgeSection.OPT_NONE || p + 1 >= c.pieces.size()) continue;
                double[] sig = bs.optSignature();
                if (bs.optSig == null) { if (verbose) System.out.println("      " + tag + " bridge " + p + ": first sight"); continue; }
                if (sigEq(sig, bs.optSig)) continue;
                nReoptRun++;
                if (verbose) System.out.println("      " + tag + " bridge " + p + " RE-OPT on" + diffSlots(sig, bs.optSig));
            }
        }
        long t0 = System.nanoTime();
        boolean changed;
        if (fix == 0) {
            changed = c.autoReoptimizeBridges(r);
        } else {
            changed = false;
            spanIdx = 0;
            for (int p = 0; p < c.pieces.size(); p++) {
                List<Section> secs = c.pieces.get(p).sections;
                for (int k = 0; k < secs.size(); k++, spanIdx++) {
                    if (!(secs.get(k) instanceof BridgeSection bs)) continue;
                    if (bs.optMode == BridgeSection.OPT_NONE || p + 1 >= c.pieces.size()) continue;
                    double[] sig = bs.optSignature();
                    if (bs.optSig == null) { bs.optSig = sig; continue; }
                    if (sigEq(sig, bs.optSig)) continue;
                    boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
                    double dist = bs.optLen ? knee(c, bs, py)
                        : bs.lastOptDist > 0 ? bs.lastOptDist
                        : Math.max(1, spanIdx < r.spans().size() ? r.spans().get(spanIdx).sLen() : 1);
                    optimize(c, bs, p + 1, dist, py);
                    changed = true;
                }
            }
        }
        tReopt += System.nanoTime() - t0;
        return changed;
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). */
    static String rebuild(Coaster c) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0; nRecon++;
            if (again && verbose) System.out.println("      fast pass " + pass + ": reconcile wrote a speed");
            if (autoReopt(c, r, "fast pass " + pass)) again = true;
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            tRecon += System.nanoTime() - t0; nRecon++;
            if (again && verbose) System.out.println("      exact pass " + pass + ": reconcile wrote a speed");
            if (autoReopt(c, r, "exact pass " + pass)) again = true;
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced;
    }

    /** GraphEditor.setBridgeLock's model effect under OPT_POS_YAW:
     *  re-apply at the last requested length, then rebuild. */
    static void setLock(Coaster c, int bp, int axis, boolean locked, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        if (axis == 0) bs.lockYaw = locked; else if (axis == 1) bs.lockPitch = locked; else bs.lockRoll = locked;
        reset();
        long t0 = System.nanoTime();
        if (bs.optMode == BridgeSection.OPT_POS_YAW) {
            optimize(c, bs, bp + 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, true);
        }
        String loop = rebuild(c);
        System.out.printf(Locale.US, "  [%s] bridge %d axis %d locked=%b: %.0f ms — %s — %s%n",
            label, bp, axis, locked, (System.nanoTime() - t0) / 1e6, loop, stats());
    }

    /** GraphEditor.setBridgeOptLen(true) → applyOptimalLength: knee →
     *  applyBridgeGap (optimizeBridge + rebuild). */
    static void setOptLen(Coaster c, int bp, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        bs.optLen = true;
        reset();
        long t0 = System.nanoTime();
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d = knee(c, bs, py);
        optimize(c, bs, bp + 1, d, py);
        String loop = rebuild(c);
        System.out.printf(Locale.US, "  [%s] Optimize length ON bridge %d: knee %.2f m — %.0f ms — %s — %s%n",
            label, bp, d, (System.nanoTime() - t0) / 1e6, loop, stats());
    }

    /** One bridge-window spinner click (GraphEditor.setBridgeTargetOrient
     *  → bridgeTargetWrite → placementEdit (no carry: REAL_ONLY + affect
     *  off) → applyOptimalLength / applyBridgeGap → optimizeBridge →
     *  rebuildTrack). */
    static double click(Coaster c, int bp, int axis, double delta, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d = bs.optLen ? knee(c, bs, py) : bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        double pitchBefore = t.pitchDeg, rollBefore = t.rollDeg;
        double[] stampTBy = { bs.solvedTarget.T().y(), bs.solvedTarget.U().y() };
        optimize(c, bs, bp + 1, d, py);
        // STAMP vs the NEXT compile's capture, right after the click's
        // own optimizeBridge: the stamp's tB.y / uB.y come from
        // bs.solvedTarget (the compile's PRE-alignment capture); the
        // target's entry frame now carries the alignment
        TrackPose ep = t.entryPose();
        System.out.printf(Locale.US,
            "    click optimize: dist %.3f, align moved pitch %.5f° roll %.5f°; stamp tB.y=%.9g uB.y=%.9g vs target entry T.y=%.9g U.y=%.9g (Δ %.3g / %.3g)%n",
            d, t.pitchDeg - pitchBefore, t.rollDeg - rollBefore,
            bs.optSig[25], bs.optSig[26], ep.T().y(), ep.U().y(),
            ep.T().y() - bs.optSig[25], ep.U().y() - bs.optSig[26]);
        if (verbose) System.out.printf(Locale.US, "    (pre-click solvedTarget tB.y=%.9g uB.y=%.9g)%n", stampTBy[0], stampTBy[1]);
        String loop = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "  [%s] CLICK bridge %d axis %d %+.0f: %.0f ms — %s — %s%n",
            label, bp, axis, delta, ms, loop, stats());
        System.out.println("    render after the click: " + lands(c));
        return ms;
    }

    /** The render's own march-vs-septic decision per bridge, off the
     *  LAST compile's captured seams (Coaster.marchSpine == null = the
     *  placement no longer lands within 2 cm / 0.3° / 1° bank → the
     *  SEPTIC renders). */
    static String lands(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
            boolean m = Coaster.marchSpine(bs, bs.optMode == BridgeSection.OPT_POS_YAW) != null;
            TrackPiece t = c.pieces.get(p + 1);
            TrackPose ep = t.entryPose();
            double hTgt = Math.toDegrees(Math.atan2(-ep.T().z(), ep.T().x()));
            double hCap = Math.toDegrees(Math.atan2(-bs.solvedTarget.T().z(), bs.solvedTarget.T().x()));
            sb.append(String.format(Locale.US, "bridge %d %s (target heading %.4f°, captured %.4f°) ",
                p, m ? "MARCH lands" : "SEPTIC (march misses)", hTgt, hCap));
        }
        return sb.toString();
    }

    static void describe(Coaster c) {
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            BridgeSection b = bridgeOf(c, p);
            System.out.printf(Locale.US, "  piece %d mode=%s affect=%b design=(%.3f,%.3f) true=(%.3f,%.3f) yaw=%.3f%s%n",
                p, tp.orientMode, tp.affectSubsequentSeams, tp.designPitchDeg, tp.designRollDeg,
                tp.pitchDeg, tp.rollDeg, tp.yawDeg,
                b != null ? String.format(Locale.US, " | bridge optMode=%d optLen=%b lastOptDist=%.3f locks y/p/r=%b/%b/%b",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll) : "");
        }
    }

    /** The reported configuration, built the way the UI builds it. */
    static Coaster setup(String label) throws IOException {
        Coaster c = load();
        System.out.println("--- " + label + ": load + open ---");
        describe(c);
        reset();
        long t0 = System.nanoTime();
        System.out.printf(Locale.US, "  open: %.0f ms — %s — %s%n", 0.0, rebuild(c), stats());
        reset(); t0 = System.nanoTime();
        System.out.printf(Locale.US, "  idle rebuild: %.0f ms — %s — %s%n", (System.nanoTime() - t0) / 1e6, rebuild(c), stats());
        // locks: yaw LOCKED / pitch FREE / roll LOCKED on both bridges
        setLock(c, 0, 0, true, label + " setup");      // bridge 0 yaw: free → locked (file had 0)
        setLock(c, 0, 1, false, label + " setup");     // bridge 0 pitch: locked → FREE
        setLock(c, 1, 1, false, label + " setup");     // bridge 1 pitch: locked → FREE (yaw already locked)
        // Optimize length ON, both bridges
        setOptLen(c, 0, label + " setup");
        setOptLen(c, 1, label + " setup");
        reset(); t0 = System.nanoTime();
        System.out.printf(Locale.US, "  idle rebuild after setup: %.0f ms — %s — %s%n", (System.nanoTime() - t0) / 1e6, rebuild(c), stats());
        describe(c);
        return c;
    }

    public static void main(String[] a) throws Exception {
        // warm the JIT once, quietly, on the stock path
        verbose = false;
        fix = 0;
        {
            Coaster w = setup("warm-up");
            click(w, 0, 0, 1.0, "warm-up");
            click(w, 1, 0, 1.0, "warm-up");
        }
        verbose = true;

        // ---------- BASELINE: the file as saved (no lock/optLen change) ----------
        System.out.println();
        System.out.println("=== BASELINE: file as saved (bridge 0 yaw free + pitch/roll locked, bridge 1 all locked, optLen off) ===");
        {
            Coaster c = load();
            reset(); rebuild(c);
            reset(); System.out.printf(Locale.US, "  idle rebuild: %s — %s%n", rebuild(c), stats());
            click(c, 0, 0, 1.0, "as saved");
            click(c, 0, 2, 1.0, "as saved");
            click(c, 1, 0, 1.0, "as saved");
            click(c, 1, 2, 1.0, "as saved");
        }

        // ---------- REPORTED CONFIGURATION, stock code ----------
        System.out.println();
        System.out.println("=== REPORTED CONFIG (stock): REAL_ONLY targets, OPT_POS_YAW, yaw LOCKED / pitch FREE / roll LOCKED, Optimize length ON ===");
        fix = 0;
        Coaster c0 = setup("stock");
        System.out.println("  --- clicks (stock) ---");
        double[] stock = {
            click(c0, 0, 0, 1.0, "stock"),
            click(c0, 0, 0, 1.0, "stock"),
            click(c0, 1, 0, 1.0, "stock"),
            click(c0, 1, 0, 1.0, "stock"),
            click(c0, 0, 2, 1.0, "stock"),
        };
        reset(); System.out.printf(Locale.US, "  idle rebuild after clicks: %s — %s%n", rebuild(c0), stats());

        // ---------- variant: same config but pitch LOCKED again (no free pitch/roll) ----------
        System.out.println();
        System.out.println("=== CONTROL (stock): same but pitch re-LOCKED on both (yaw-only alignment never touches tB.y/uB.y) ===");
        {
            Coaster c = load();
            reset(); rebuild(c);
            setLock(c, 0, 0, true, "control setup");
            setOptLen(c, 0, "control setup");
            setOptLen(c, 1, "control setup");
            click(c, 0, 0, 1.0, "control");
            click(c, 1, 0, 1.0, "control");
        }

        // ---------- variant: pitch FREE, Optimize length OFF ----------
        System.out.println();
        System.out.println("=== CONTROL (stock): pitch FREE on both, Optimize length OFF (same extra passes, cheap re-opts) ===");
        {
            Coaster c = load();
            reset(); rebuild(c);
            setLock(c, 0, 0, true, "ctl2 setup");
            setLock(c, 0, 1, false, "ctl2 setup");
            setLock(c, 1, 1, false, "ctl2 setup");
            click(c, 0, 0, 1.0, "ctl2");
            click(c, 1, 0, 1.0, "ctl2");
        }

        // ---------- FIX F1: re-capture + stamp ----------
        System.out.println();
        System.out.println("=== FIX F1 emulated: re-capture the target head after a non-LOCKSTEP pitch/roll alignment, then stamp ===");
        fix = 1;
        Coaster c1 = setup("F1");
        System.out.println("  --- clicks (F1) ---");
        double[] f1 = {
            click(c1, 0, 0, 1.0, "F1"),
            click(c1, 0, 0, 1.0, "F1"),
            click(c1, 1, 0, 1.0, "F1"),
            click(c1, 1, 0, 1.0, "F1"),
            click(c1, 0, 2, 1.0, "F1"),
        };
        reset(); System.out.printf(Locale.US, "  idle rebuild after clicks: %s — %s%n", rebuild(c1), stats());

        // ---------- FIX F2: F1 + one re-knee ----------
        System.out.println();
        System.out.println("=== FIX F2 emulated: F1 + ONE re-knee off the re-captured seams under Optimize length ===");
        fix = 2;
        Coaster c2 = setup("F2");
        System.out.println("  --- clicks (F2) ---");
        double[] f2 = {
            click(c2, 0, 0, 1.0, "F2"),
            click(c2, 0, 0, 1.0, "F2"),
            click(c2, 1, 0, 1.0, "F2"),
            click(c2, 1, 0, 1.0, "F2"),
            click(c2, 0, 2, 1.0, "F2"),
        };
        reset(); System.out.printf(Locale.US, "  idle rebuild after clicks: %s — %s%n", rebuild(c2), stats());

        // ---------- FIX F3: F1 + re-knee only when the head moved ----------
        System.out.println();
        System.out.println("=== FIX F3 emulated: F1 + a re-knee only when the alignment moved tB.y/uB.y (> 1e-9) ===");
        fix = 3;
        Coaster c3 = setup("F3");
        System.out.println("  --- clicks (F3) ---");
        double[] f3 = {
            click(c3, 0, 0, 1.0, "F3"),
            click(c3, 0, 0, 1.0, "F3"),
            click(c3, 1, 0, 1.0, "F3"),
            click(c3, 1, 0, 1.0, "F3"),
            click(c3, 0, 2, 1.0, "F3"),
        };
        reset(); System.out.printf(Locale.US, "  idle rebuild after clicks: %s — %s%n", rebuild(c3), stats());

        // ---------- placement comparison: do the fixes land the same track? ----------
        System.out.println();
        System.out.println("=== placements after the same 5 clicks (stock vs F1 vs F2) ===");
        for (int p = 1; p < 3; p++) {
            TrackPiece s = c0.pieces.get(p), a1 = c1.pieces.get(p), a2 = c2.pieces.get(p), a3 = c3.pieces.get(p);
            System.out.printf(Locale.US, "  piece %d: stock (%.4f,%.4f,%.4f) yaw %.4f pitch %.4f roll %.4f | F1 Δpos %.3g Δpitch %.3g | F2 Δpos %.3g Δpitch %.3g | F3 Δpos %.3g Δpitch %.3g | knees stock %.3f F1 %.3f F2 %.3f F3 %.3f%n",
                p, s.x, s.y, s.z, s.yawDeg, s.pitchDeg, s.rollDeg,
                Math.sqrt((a1.x - s.x) * (a1.x - s.x) + (a1.y - s.y) * (a1.y - s.y) + (a1.z - s.z) * (a1.z - s.z)), a1.pitchDeg - s.pitchDeg,
                Math.sqrt((a2.x - s.x) * (a2.x - s.x) + (a2.y - s.y) * (a2.y - s.y) + (a2.z - s.z) * (a2.z - s.z)), a2.pitchDeg - s.pitchDeg,
                Math.sqrt((a3.x - s.x) * (a3.x - s.x) + (a3.y - s.y) * (a3.y - s.y) + (a3.z - s.z) * (a3.z - s.z)), a3.pitchDeg - s.pitchDeg,
                bridgeOf(c0, p - 1).lastOptDist, bridgeOf(c1, p - 1).lastOptDist, bridgeOf(c2, p - 1).lastOptDist, bridgeOf(c3, p - 1).lastOptDist);
        }
        System.out.println();
        System.out.printf(Locale.US, "SUMMARY ms/click (5 clicks): stock %s | F1 %s | F2 %s | F3 %s%n",
            Arrays.toString(round(stock)), Arrays.toString(round(f1)), Arrays.toString(round(f2)), Arrays.toString(round(f3)));
    }

    static long[] round(double[] v) {
        long[] r = new long[v.length];
        for (int i = 0; i < v.length; i++) r[i] = Math.round(v[i]);
        return r;
    }
}
