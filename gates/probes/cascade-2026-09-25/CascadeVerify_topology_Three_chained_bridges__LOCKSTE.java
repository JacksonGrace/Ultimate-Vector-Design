package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFICATION (2026-09-25) of the reported cascade
 *  "Three chained bridges, LOCKSTEP targets with pitch FREE and
 *  Optimize length ON (C2f)" — built from the setup description only:
 *  the user's file + one copied target (P3 = P2.copy(+5 z)) + a third
 *  bridge b3 (P2 → P3, optimize position+orientation, lockPitch =
 *  false), all targets LOCKSTEP; b1 and b2 lockPitch = false + optLen
 *  = true, knee + optimizeBridge; settle with two rebuild() loops.
 *  Click = target.setDispRoll(+1, true), then knee + optimizeBridge on
 *  that bridge, then the shell's rebuild loop VERBATIM. Per pass it
 *  prints: reconcile vDesign writes, every bridge's signature slots
 *  that differ from its stamp, which bridges re-marched (with the knee
 *  and optimizeBridge timed separately — the auto pass is re-enacted
 *  call-for-call: dist = optLen ? knee : lastOptDist, then
 *  optimizeBridge), the target placements, and the pass/ms totals.
 *  The verbatim loop (BridgeSlowProbe.rebuild) runs on a second,
 *  identical construction so the pass counts / ms are the shell's. */
public class CascadeVerify_topology_Three_chained_bridges__LOCKSTE {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len" };

    static Coaster load(String path) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(path))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection trailing(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return s.isEmpty() ? null
            : s.get(s.size() - 1) instanceof BridgeSection b ? b : null;
    }

    static String pl(TrackPiece t) {
        return String.format(Locale.US,
            "x=%.4f y=%.4f z=%.4f yaw=%.4f pitch=%.4f roll=%.4f dPitch=%.4f dRoll=%.4f",
            t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
            t.designPitchDeg, t.designRollDeg);
    }

    static String sigDiff(double[] a, double[] stamp) {
        if (stamp == null) return "stamp=null (first sight)";
        if (stamp == BridgeSection.SIG_STALE) return "stamp=SIG_STALE";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - stamp[i]) > 1e-9) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "[%d %s] %.6g→%.6g (Δ%.3g)",
                    i, SLOT[i], stamp[i], a[i], a[i] - stamp[i]));
            }
        }
        return sb.length() == 0 ? "equal" : sb.toString();
    }

    static double[] vDesigns(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) v.add(s.vDesign());
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static String vWrites(double[] before, double[] after) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < before.length; i++) {
            if (Double.doubleToLongBits(before[i]) != Double.doubleToLongBits(after[i])) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "sec#%d %.9g→%.9g", i, before[i], after[i]));
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ---------------- the verbatim shell loop (BridgeSlowProbe.rebuild) ----------------
    static int vCompiles;
    static String rebuildVerbatim(Coaster c) {
        vCompiles = 0;
        TrackCompiler.Result r = TrackCompiler.compile(c, DT); vCompiles++;
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT); vCompiles++;
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) { quiesced = true; break; }
            r = TrackCompiler.compile(c, DT); vCompiles++;
        }
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced
            + " compiles=" + vCompiles;
    }

    // ---------------- the instrumented loop: same calls, every step printed/timed ----------------
    static int iKnees; static double iKneeMs, iOptMs, iCompileMs;
    static int iRemarches;

    /** autoReoptimizeBridges re-enacted call-for-call (the model method
     *  is: for each live optimize-mode bridge whose sig != stamp:
     *  dist = optLen ? optimalBridgeLength : lastOptDist (or span arc);
     *  optimizeBridge(bs, p+1, dist, py)). Returns whether any ran. */
    static boolean autoPassInstrumented(Coaster c, TrackCompiler.Result r, String tag) {
        boolean changed = false;
        int spanIdx = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++, spanIdx++) {
                if (!(secs.get(k) instanceof BridgeSection bs)) continue;
                if (bs.optMode == BridgeSection.OPT_NONE || p + 1 >= c.pieces.size()) continue;
                double[] sig = bs.optSignature();
                if (bs.optSig == null) {
                    System.out.println("      " + tag + " b" + (p + 1) + ": first sight — record only");
                    bs.optSig = sig;
                    continue;
                }
                String diff = sigDiff(sig, bs.optSig);
                if (diff.equals("equal")) {
                    System.out.println("      " + tag + " b" + (p + 1) + ": sig equal");
                    continue;
                }
                System.out.println("      " + tag + " b" + (p + 1) + ": sig DIFFERS → " + diff);
                boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
                double dist;
                long t0 = System.nanoTime();
                if (bs.optLen) {
                    dist = c.optimalBridgeLength(bs, py);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    iKnees++; iKneeMs += ms;
                    System.out.printf(Locale.US, "        auto knee b%d = %.4f m (%.0f ms; lastOptDist was %.4f)%n",
                        p + 1, dist, ms, bs.lastOptDist);
                } else {
                    dist = bs.lastOptDist > 0 ? bs.lastOptDist
                        : Math.max(1, spanIdx < r.spans().size() ? r.spans().get(spanIdx).sLen() : 1);
                }
                TrackPiece t = c.pieces.get(p + 1);
                String before = pl(t);
                t0 = System.nanoTime();
                c.optimizeBridge(bs, p + 1, dist, py);
                double ms = (System.nanoTime() - t0) / 1e6;
                iOptMs += ms; iRemarches++;
                System.out.printf(Locale.US, "        optimizeBridge b%d at %.4f → lastOptDist %.4f (%s) %.0f ms%n",
                    p + 1, dist, bs.lastOptDist,
                    Math.abs(bs.lastOptDist - dist) > 1e-9 ? "RE-KNEE changed the length" : "length kept", ms);
                System.out.println("          P" + (p + 1) + " before: " + before);
                System.out.println("          P" + (p + 1) + " after:  " + pl(t));
                changed = true;
            }
        }
        return changed;
    }

    static String rebuildInstrumented(Coaster c, String tag) {
        iKnees = 0; iKneeMs = iOptMs = iCompileMs = 0; iRemarches = 0;
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        iCompileMs += (System.nanoTime() - t0) / 1e6;
        int fast = 0, exact = 0, compiles = 1;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r);
            System.out.println("    fast pass " + (pass + 1) + ": reconcile writes: " + vWrites(vb, vDesigns(c)));
            if (autoPassInstrumented(c, r, "fast" + (pass + 1))) again = true;
            if (!again) break;
            t0 = System.nanoTime();
            r = TrackCompiler.compile(c, DT); compiles++;
            iCompileMs += (System.nanoTime() - t0) / 1e6;
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r, true);
            System.out.println("    exact pass " + (pass + 1) + ": reconcile writes: " + vWrites(vb, vDesigns(c)));
            if (autoPassInstrumented(c, r, "exact" + (pass + 1))) again = true;
            if (!again) { quiesced = true; break; }
            t0 = System.nanoTime();
            r = TrackCompiler.compile(c, DT); compiles++;
            iCompileMs += (System.nanoTime() - t0) / 1e6;
        }
        return String.format(Locale.US,
            "fast=%d exact=%d quiesced=%b compiles=%d (%.0f ms) re-marches=%d (optimizeBridge %.0f ms) auto-knees=%d (%.0f ms)",
            fast, exact, quiesced, compiles, iCompileMs, iRemarches, iOptMs, iKnees, iKneeMs);
    }

    // ---------------- construction ----------------
    /** Build the configuration from the description. b3Len/b3OptLen
     *  = the third bridge's initial length / Optimize-length flag
     *  (the description does not fix them; the main variant uses the
     *  chord and optLen off). Returns the coaster BEFORE any settle. */
    static Coaster build(String path, boolean b3OptLen, boolean verbose) throws IOException {
        Coaster c = load(path);
        // the file as saved: settle it first like the shell does on open
        rebuildVerbatim(c);
        rebuildVerbatim(c);
        // P3 = a deep copy of the last target, 5 m along +Z (Copy's offset)
        TrackPiece p3 = c.pieces.get(2).copy(Coaster.COPY_DZ);
        p3.followPrev = false;
        c.pieces.add(p3);
        // b3: P2's chain end → P3's entry
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        int spanIdx = -1;
        for (int p = 0; p <= 2; p++) spanIdx += c.pieces.get(p).sections.size();
        TrackCompiler.Span sp = r0.spans().get(spanIdx);
        BridgeSection b3 = BridgeSection.solve(sp.exit(), p3.entryPose(),
            r0.samples().get(sp.i1()).v());
        b3.optMode = BridgeSection.OPT_POS_YAW;
        b3.lockYaw = false; b3.lockPitch = false; b3.lockRoll = true;
        c.pieces.get(2).sections.add(b3);
        double chord = p3.entryPose().pos().sub(sp.exit().pos()).length();
        if (verbose) System.out.printf(Locale.US, "  b3 chord = %.3f m, vEnd = %.3f%n", chord, b3.vDesign);
        // all targets LOCKSTEP (P1..P3)
        for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
        b3.optLen = b3OptLen;
        if (b3OptLen) {
            double d = c.optimalBridgeLength(b3, true);
            c.optimizeBridge(b3, 3, d, true);
        } else {
            c.optimizeBridge(b3, 3, Math.max(1, chord), true);
        }
        // b1, b2: pitch FREE + Optimize length ON, knee + apply
        for (int p = 0; p < 2; p++) {
            BridgeSection bs = trailing(c, p);
            bs.lockPitch = false;
            bs.optLen = true;
            long t0 = System.nanoTime();
            double d = c.optimalBridgeLength(bs, true);
            double kms = (System.nanoTime() - t0) / 1e6;
            t0 = System.nanoTime();
            c.optimizeBridge(bs, p + 1, d, true);
            if (verbose) System.out.printf(Locale.US,
                "  b%d: knee %.4f m (%.0f ms), optimizeBridge → lastOptDist %.4f (%.0f ms)%n",
                p + 1, d, kms, bs.lastOptDist, (System.nanoTime() - t0) / 1e6);
        }
        return c;
    }

    static void printState(Coaster c) {
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            BridgeSection b = trailing(c, p);
            System.out.printf(Locale.US, "  P%d mode=%s %s%s%n", p, tp.orientMode, pl(tp),
                b == null ? "" : String.format(Locale.US,
                    " | b%d optMode=%d optLen=%b lastOptDist=%.4f locks=%b/%b/%b",
                    p + 1, b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll));
        }
    }

    /** The click: setDispRoll(+1) on the target, knee + optimizeBridge
     *  on that bridge (bridgeTargetWrite → applyOptimalLength →
     *  applyBridgeGap), then the loop. instrumented = the printed
     *  loop, else the verbatim one. */
    static double click(Coaster c, int bp, boolean instrumented, String label) {
        BridgeSection bs = trailing(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t0 = System.nanoTime();
        t.setDispRoll(t.dispRoll() + 1.0, true);
        long t1 = System.nanoTime();
        double d;
        if (bs.optLen) d = c.optimalBridgeLength(bs, py);
        else d = bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        long t2 = System.nanoTime();
        c.optimizeBridge(bs, bp + 1, d, py);
        long t3 = System.nanoTime();
        if (instrumented) {
            System.out.printf(Locale.US, "  [%s] click b%d: explicit knee %.4f m (%.0f ms), optimizeBridge → lastOptDist %.4f (%s) %.0f ms%n",
                label, bp + 1, d, (t2 - t1) / 1e6, bs.lastOptDist,
                Math.abs(bs.lastOptDist - d) > 1e-9 ? "RE-KNEE changed the length" : "length kept",
                (t3 - t2) / 1e6);
            System.out.println("    P" + (bp + 1) + " after click: " + pl(t));
            // STALE-STAMP CHECK: what the next compile captures vs the stamp
            TrackCompiler.Result r1 = TrackCompiler.compile(c, DT);
            for (int p = 0; p < c.pieces.size() - 1; p++) {
                BridgeSection b = trailing(c, p);
                if (b == null) continue;
                System.out.println("    post-click compile b" + (p + 1) + " vs stamp: " + sigDiff(b.optSignature(), b.optSig));
            }
            // (that compile only re-captured the bridge fields; the loop
            //  below compiles again — same bits, compile is a pure function)
            String loop = rebuildInstrumented(c, label);
            double ms = (System.nanoTime() - t0) / 1e6 - 0; // includes the check compile
            System.out.printf(Locale.US, "  [%s] click b%d LOOP: %s — total incl. one diagnostic compile %.0f ms%n",
                label, bp + 1, loop, ms);
            return ms;
        } else {
            String loop = rebuildVerbatim(c);
            double ms = (System.nanoTime() - t0) / 1e6;
            System.out.printf(Locale.US, "  [%s] click b%d: %.0f ms total — %s (knee %.0f ms, optimizeBridge %.0f ms)%n",
                label, bp + 1, ms, loop, (t2 - t1) / 1e6, (t3 - t2) / 1e6);
            return ms;
        }
    }

    static boolean run(String sec) {
        String only = System.getProperty("only");
        return only == null || only.contains(sec);
    }

    public static void main(String[] a) throws Exception {
        String path = a.length > 0 ? a[0]
            : "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
        // ---- warm-up (JIT) on the plain file
        {
            Coaster w = load(path);
            for (int i = 0; i < 3; i++) rebuildVerbatim(w);
            for (int i = 0; i < 2; i++) click(w, 0, false, "warmup");
        }

        if (run("A")) {
        System.out.println("=== A. VERBATIM shell loop on the described construction (b3 optLen off) ===");
            Coaster c = build(path, false, true);
            long t0 = System.nanoTime();
            String s1 = rebuildVerbatim(c);
            System.out.printf(Locale.US, "  construction settle 1: %.0f ms — %s%n", (System.nanoTime() - t0) / 1e6, s1);
            t0 = System.nanoTime();
            String s2 = rebuildVerbatim(c);
            System.out.printf(Locale.US, "  construction settle 2: %.0f ms — %s%n", (System.nanoTime() - t0) / 1e6, s2);
            printState(c);
            for (int i = 0; i < 3; i++) click(c, 0, false, "b1 roll+1 #" + (i + 1));
            for (int i = 0; i < 3; i++) click(c, 1, false, "b2 roll+1 #" + (i + 1));
            for (int i = 0; i < 2; i++) click(c, 2, false, "b3 roll+1 #" + (i + 1));
            t0 = System.nanoTime();
            System.out.printf(Locale.US, "  idle rebuild after clicks: %s (%.0f ms)%n", rebuildVerbatim(c), (System.nanoTime() - t0) / 1e6);
            printState(c);
        }

        System.out.println();
        if (run("B")) {
        System.out.println("=== B. INSTRUMENTED loop on a fresh identical construction (per-pass slot deltas) ===");
            Coaster c = build(path, false, false);
            System.out.println("  construction settle 1:");
            System.out.println("  → " + rebuildInstrumented(c, "settle1"));
            System.out.println("  construction settle 2:");
            System.out.println("  → " + rebuildInstrumented(c, "settle2"));
            printState(c);
            click(c, 0, true, "b1 roll+1 #1");
            click(c, 0, true, "b1 roll+1 #2");
            click(c, 1, true, "b2 roll+1 #1");
            click(c, 2, true, "b3 roll+1 #1");
            printState(c);
            // standalone knee cost on the settled seams, min of 3
            for (int p = 0; p < 3; p++) {
                BridgeSection bs = trailing(c, p);
                double best = Double.MAX_VALUE, d = 0;
                for (int i = 0; i < 3; i++) {
                    long t0 = System.nanoTime();
                    d = c.optimalBridgeLength(bs, true);
                    best = Math.min(best, (System.nanoTime() - t0) / 1e6);
                }
                long t0 = System.nanoTime();
                TrackCompiler.compile(c, DT);
                double cms = (System.nanoTime() - t0) / 1e6;
                System.out.printf(Locale.US, "  standalone knee b%d = %.4f m: min %.0f ms | one compile %.0f ms%n", p + 1, d, best, cms);
            }
        }

        System.out.println();
        if (run("C")) {
        System.out.println("=== C. CONTROL: same construction, LOCKSTEP but Optimize length OFF on b1/b2 (lastOptDist kept) ===");
            Coaster c = build(path, false, false);
            rebuildVerbatim(c); rebuildVerbatim(c);
            for (int p = 0; p < 2; p++) trailing(c, p).optLen = false;
            // stamping: the length slot now carries lastOptDist — re-apply so the stamp matches
            for (int p = 0; p < 2; p++) { BridgeSection bs = trailing(c, p); c.optimizeBridge(bs, p + 1, bs.lastOptDist, true); }
            rebuildVerbatim(c); rebuildVerbatim(c);
            printState(c);
            for (int i = 0; i < 2; i++) click(c, 0, false, "optLen-off b1 roll+1 #" + (i + 1));
            for (int i = 0; i < 2; i++) click(c, 1, false, "optLen-off b2 roll+1 #" + (i + 1));
        }

        System.out.println();
        if (run("D")) {
        System.out.println("=== D. CONTROL: same construction, REAL_ONLY targets (no inner settle), optLen ON ===");
            Coaster c = build(path, false, false);
            rebuildVerbatim(c); rebuildVerbatim(c);
            for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.REAL_ONLY;
            rebuildVerbatim(c); rebuildVerbatim(c);
            printState(c);
            for (int i = 0; i < 2; i++) click(c, 0, false, "realonly b1 roll+1 #" + (i + 1));
            for (int i = 0; i < 2; i++) click(c, 1, false, "realonly b2 roll+1 #" + (i + 1));
            System.out.println("  --- D instrumented: one more click on b1, per-pass slot deltas ---");
            click(c, 0, true, "realonly b1 roll+1 #3");
            System.out.println("  --- D instrumented: REAL_ONLY with optLen OFF on b1/b2 (stale-stamp check without the knee) ---");
            for (int p = 0; p < 2; p++) trailing(c, p).optLen = false;
            for (int p = 0; p < 2; p++) { BridgeSection bs = trailing(c, p); c.optimizeBridge(bs, p + 1, bs.lastOptDist, true); }
            rebuildVerbatim(c); rebuildVerbatim(c);
            click(c, 0, true, "realonly optLen-off b1 roll+1");
        }

        System.out.println();
        if (run("E")) {
        System.out.println("=== E. VARIANT: b3 also optLen ON ===");
            Coaster c = build(path, true, true);
            long t0 = System.nanoTime();
            System.out.printf(Locale.US, "  construction settle 1: %s (%.0f ms)%n", rebuildVerbatim(c), (System.nanoTime() - t0) / 1e6);
            t0 = System.nanoTime();
            System.out.printf(Locale.US, "  construction settle 2: %s (%.0f ms)%n", rebuildVerbatim(c), (System.nanoTime() - t0) / 1e6);
            printState(c);
            for (int i = 0; i < 2; i++) click(c, 0, false, "b3optLen b1 roll+1 #" + (i + 1));
            for (int i = 0; i < 2; i++) click(c, 1, false, "b3optLen b2 roll+1 #" + (i + 1));
            for (int i = 0; i < 2; i++) click(c, 2, false, "b3optLen b3 roll+1 #" + (i + 1));
        }
    }
}
