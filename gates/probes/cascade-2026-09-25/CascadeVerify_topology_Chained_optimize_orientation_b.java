package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE VERIFY (2026-09-25) — family "topology", configuration
 *  "Chained optimize-orientation bridges with Optimize length ON —
 *  click on the FIRST bridge (N = 2..5)". An INDEPENDENT rebuild of
 *  the reported configuration from its setup description (the
 *  hunter's probes were deliberately not read): the user's file
 *  P0 ->b1-> P1 ->b2-> P2 (both bridges OPT_POS_YAW, targets
 *  REAL_ONLY, pitch/roll locked), extended to N bridges by copying
 *  the last piece 40/-6/25 m away and bridging to it; Optimize length
 *  ON everywhere; one +1° pitch spinner click on b1's target, run
 *  through the shell's verbatim 12 + 40 rebuild loop with every pass
 *  instrumented: which signature slots differ from the stamp, which
 *  bridges re-march, how the placed pieces move, what the knee reads.
 *  Then the same click with the target head RE-CAPTURED before the
 *  click's march (the stale-stamp hypothesis), a click on the LAST
 *  bridge, the as-saved baseline (Optimize length off), and isolated
 *  stage timings. Prints only — moves nothing on disk. */
public class CascadeVerify_topology_Chained_optimize_orientation_b {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len|0" };

    // ---- stage accumulators (per click) ----
    static long tCompile, tRecon, tReopt, tOpt, tKnee;
    static int nCompile, nRecon, nReopt, nReMarch, nOpt, nKnee;

    static void reset() {
        tCompile = tRecon = tReopt = tOpt = tKnee = 0;
        nCompile = nRecon = nReopt = nReMarch = nOpt = nKnee = 0;
    }

    static String stats() {
        return String.format(Locale.US,
            "compile %d× %.0f ms | reconcile %d× %.0f ms | autoReopt %d× %.0f ms (%d re-marches) | click knee %d× %.0f ms | click optimizeBridge %d× %.0f ms",
            nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReopt, tReopt / 1e6,
            nReMarch, nKnee, tKnee / 1e6, nOpt, tOpt / 1e6);
    }

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(FILE))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    record Br(int p, BridgeSection bs) {}

    /** Every trailing bridge that has a target piece, in list order. */
    static List<Br> bridges(Coaster c) {
        List<Br> out = new ArrayList<>();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            if (!secs.isEmpty() && secs.get(secs.size() - 1) instanceof BridgeSection bs) {
                out.add(new Br(p, bs));
            }
        }
        return out;
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    static String f(double v) {
        return String.format(Locale.US, "%.6g", v);
    }

    /** Signature slots of every stamped bridge that differ from the
     *  stamp (the auto pass's sigEq test, 1e-9), before the pass runs. */
    static String diffs(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (Br b : bridges(c)) {
            if (b.bs.optMode == BridgeSection.OPT_NONE) continue;
            if (b.bs.optSig == null) { sb.append(" b").append(b.p + 1).append(":first-sight"); continue; }
            if (b.bs.optSig.length == 0) { sb.append(" b").append(b.p + 1).append(":SIG_STALE"); continue; }
            double[] sig = b.bs.optSignature();
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - b.bs.optSig[i]) > 1e-9) {
                    if (d.length() > 0) d.append(", ");
                    d.append(i).append(':').append(SLOT[i]).append(' ')
                        .append(f(b.bs.optSig[i])).append("→").append(f(sig[i]))
                        .append(" (Δ").append(f(sig[i] - b.bs.optSig[i])).append(')');
                }
            }
            sb.append(" b").append(b.p + 1).append(d.length() == 0 ? ": =" : ": {" + d + "}");
        }
        return sb.toString();
    }

    static double[] placements(Coaster c) {
        double[] out = new double[c.pieces.size() * 4];
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece t = c.pieces.get(p);
            out[4 * p] = t.x; out[4 * p + 1] = t.y; out[4 * p + 2] = t.z; out[4 * p + 3] = t.yawDeg;
        }
        return out;
    }

    static String moved(Coaster c, double[] before, double[] after) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            double dx = after[4 * p] - before[4 * p], dy = after[4 * p + 1] - before[4 * p + 1];
            double dz = after[4 * p + 2] - before[4 * p + 2], dyaw = after[4 * p + 3] - before[4 * p + 3];
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) + Math.abs(dyaw) > 1e-12) {
                sb.append(String.format(Locale.US, " P%d(Δx %.4f Δy %.4f Δz %.4f Δyaw %.4f°)", p, dx, dy, dz, dyaw));
            }
        }
        return sb.length() == 0 ? " (no piece moved)" : sb.toString();
    }

    static String knees(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (Br b : bridges(c)) sb.append(String.format(Locale.US, " b%d.len=%.4f", b.p + 1, b.bs.lastOptDist));
        return sb.toString();
    }

    /** The auto pass with re-march detection: optimizeBridge assigns a
     *  NEW optSig array, so an identity change = that bridge re-marched. */
    static String reopt(Coaster c, TrackCompiler.Result r, boolean[] changed) {
        List<Br> bl = bridges(c);
        double[][] before = new double[bl.size()][];
        for (int i = 0; i < bl.size(); i++) before[i] = bl.get(i).bs.optSig;
        long t0 = System.nanoTime();
        changed[0] = c.autoReoptimizeBridges(r);
        tReopt += System.nanoTime() - t0;
        nReopt++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bl.size(); i++) {
            if (bl.get(i).bs.optSig != before[i] && before[i] != null) {
                nReMarch++;
                sb.append(" b").append(bl.get(i).p + 1);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    /** FvdDesigner.rebuildTrack's at-rest loop (12 fast + 40 exact),
     *  every pass instrumented. */
    static String rebuild(Coaster c, boolean verbose) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            String d = verbose ? diffs(c) : "";
            double[] pl = placements(c);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0; nRecon++;
            boolean[] re = new boolean[1];
            String rm = reopt(c, r, re);
            if (verbose) {
                System.out.printf(Locale.US, "      fast pass %d: reconcile=%b | sig deltas:%s | re-marched: %s |%s |%s%n",
                    pass, again, d, rm, moved(c, pl, placements(c)), knees(c));
            }
            if (re[0]) again = true;
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            String d = verbose ? diffs(c) : "";
            double[] pl = placements(c);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            tRecon += System.nanoTime() - t0; nRecon++;
            boolean[] re = new boolean[1];
            String rm = reopt(c, r, re);
            if (verbose) {
                System.out.printf(Locale.US, "      exact pass %d: reconcile=%b | sig deltas:%s | re-marched: %s |%s%n",
                    pass, again, d, rm, moved(c, pl, placements(c)));
            }
            if (re[0]) again = true;
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced;
    }

    /** The user's file extended to N chained optimize-orientation
     *  bridges per the reported setup: copy the last piece (trailing
     *  bridge dropped) 40/-6/25 m away, detached; compile; solve a
     *  bridge from the previous last span's exit to its entry at the
     *  recorded end speed; OPT_POS_YAW, pitch + roll locked; compile
     *  once (the seam capture); optimizeBridge at 25 m. */
    static Coaster chain(int nBridges) throws IOException {
        Coaster c = load();
        while (bridges(c).size() < nBridges) {
            int li = c.pieces.size() - 1;
            TrackPiece last = c.pieces.get(li);
            TrackPiece t = last.copy(0);
            if (!t.sections.isEmpty()
                    && t.sections.get(t.sections.size() - 1) instanceof BridgeSection) {
                t.sections.remove(t.sections.size() - 1);
            }
            t.x += 40; t.y -= 6; t.z += 25;
            t.followPrev = false;
            c.pieces.add(t);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            int spanIdx = -1;
            for (int p = 0; p <= li; p++) spanIdx += c.pieces.get(p).sections.size();
            TrackCompiler.Span sp = r.spans().get(spanIdx);
            double vEnd = r.samples().get(sp.i1()).v();
            BridgeSection bs = BridgeSection.solve(sp.exit(), t.entryPose(), vEnd);
            bs.optMode = BridgeSection.OPT_POS_YAW;
            bs.lockPitch = true;
            bs.lockRoll = true;
            last.sections.add(bs);
            TrackCompiler.compile(c, DT);
            c.optimizeBridge(bs, li + 1, 25, true);
        }
        return c;
    }

    /** Optimize length ON for every bridge, kneed + applied, then two
     *  rebuild loops to settle. */
    static void allOptLen(Coaster c) {
        for (Br b : bridges(c)) {
            b.bs.optLen = true;
            double d = c.optimalBridgeLength(b.bs, true);
            c.optimizeBridge(b.bs, b.p + 1, d, true);
        }
        reset();
        String s1 = rebuild(c, false);
        String s2 = rebuild(c, false);
        System.out.println("    settle after optLen: " + s1 + " ; " + s2 + " ; " + stats());
    }

    /** One bridge-window pitch spinner click (+dPitch°) on bridge bp's
     *  target: GraphEditor.setBridgeTargetOrient → bridgeTargetWrite →
     *  placementEdit (no carry: REAL_ONLY + affect off) → applyOptimal
     *  Length / applyBridgeGap → optimizeBridge → rebuildTrack. With
     *  recapture = true the target head is re-captured BEFORE the
     *  click's march (the stale-stamp hypothesis test — not the app). */
    static double click(Coaster c, int bp, double dPitch, boolean verbose,
                        boolean recapture, String label) {
        Br b = null;
        for (Br x : bridges(c)) if (x.p == bp) b = x;
        if (b == null) throw new IllegalStateException("no bridge trailing piece " + bp);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
        long t0 = System.nanoTime();
        t.setDispPitch(t.dispPitch() + dPitch, true);
        if (recapture) TrackCompiler.captureTargetHead(b.bs, t, DT, c.fric());
        boolean py = b.bs.optMode == BridgeSection.OPT_POS_YAW;
        double d;
        if (b.bs.optLen) {
            long k0 = System.nanoTime();
            d = c.optimalBridgeLength(b.bs, py);
            tKnee += System.nanoTime() - k0; nKnee++;
        } else {
            d = b.bs.lastOptDist > 0 ? b.bs.lastOptDist : 1;
        }
        long o0 = System.nanoTime();
        c.optimizeBridge(b.bs, bp + 1, d, py);
        tOpt += System.nanoTime() - o0; nOpt++;
        if (verbose) System.out.printf(Locale.US, "    click on b%d (target P%d pitch %+.1f°): knee %.4f m; stamp vs fresh capture:%s%n",
            bp + 1, bp + 1, dPitch, d, diffs(c));
        String loop = rebuild(c, verbose);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "    [%s] click b%d: %.0f ms — %s — %s%n", label, bp + 1, ms, loop, stats());
        return ms;
    }

    public static void main(String[] a) throws Exception {
        // warm the JIT once on a throwaway chain so the numbers below are warm
        {
            Coaster w = chain(3);
            allOptLen(w);
            for (int i = 0; i < 2; i++) click(w, 0, 1.0, false, false, "warm-up");
        }

        System.out.println("=== A. as-saved baseline: N=2, Optimize length OFF (lastOptDist 27 / 22) ===");
        {
            Coaster c = load();
            reset();
            System.out.println("    open: " + rebuild(c, false) + " ; " + stats());
            reset();
            System.out.println("    idle: " + rebuild(c, false) + " ; " + stats());
            for (int rep = 0; rep < 3; rep++) click(c, 0, 1.0, rep == 0, false, "A optLen off");
            click(c, 1, 1.0, false, false, "A optLen off, last bridge");
        }

        System.out.println("=== B. chained optimize-orientation bridges, Optimize length ON, click on the FIRST bridge ===");
        for (int n = 2; n <= 5; n++) {
            System.out.println("  --- N = " + n + " bridges (" + (n + 1) + " pieces) ---");
            Coaster c = chain(n);
            for (Br b : bridges(c)) {
                TrackPiece tg = c.pieces.get(b.p + 1);
                System.out.printf(Locale.US, "    b%d: optMode=%d locks=%b/%b/%b lastOptDist=%.2f | target P%d mode=%s affect=%b design=(%.2f,%.2f) true=(%.2f,%.2f)%n",
                    b.p + 1, b.bs.optMode, b.bs.lockYaw, b.bs.lockPitch, b.bs.lockRoll, b.bs.lastOptDist,
                    b.p + 1, tg.orientMode, tg.affectSubsequentSeams, tg.designPitchDeg, tg.designRollDeg, tg.pitchDeg, tg.rollDeg);
            }
            allOptLen(c);
            reset();
            System.out.println("    idle rebuild: " + rebuild(c, false) + " ; " + stats());
            double[] ms = new double[3];
            for (int rep = 0; rep < 3; rep++) ms[rep] = click(c, 0, 1.0, rep == 0, false, "B N=" + n);
            System.out.printf(Locale.US, "    >>> N=%d per-click ms: %.0f / %.0f / %.0f%n", n, ms[0], ms[1], ms[2]);
            // the same click on the LAST bridge
            click(c, bridges(c).get(bridges(c).size() - 1).p, 1.0, n == 2, false, "B N=" + n + " LAST bridge");
        }

        System.out.println("=== C. stale-stamp check: N=3, the click RE-CAPTURES the target head before its march ===");
        {
            Coaster c = chain(3);
            allOptLen(c);
            reset();
            System.out.println("    idle rebuild: " + rebuild(c, false) + " ; " + stats());
            for (int rep = 0; rep < 3; rep++) click(c, 0, 1.0, rep == 0, true, "C recapture");
            System.out.println("    (control: the same chain, plain click)");
            for (int rep = 0; rep < 2; rep++) click(c, 0, 1.0, rep == 0, false, "C plain");
        }

        System.out.println("=== D. isolated stage timings (N=3 chain, optLen ON, warm) ===");
        {
            Coaster c = chain(3);
            allOptLen(c);
            rebuild(c, false);
            List<Br> bl = bridges(c);
            double[] tc = new double[7];
            for (int i = 0; i < 7; i++) { long t0 = System.nanoTime(); TrackCompiler.compile(c, DT); tc[i] = (System.nanoTime() - t0) / 1e6; }
            Arrays.sort(tc);
            System.out.printf(Locale.US, "    compile: min %.1f ms median %.1f ms%n", tc[0], tc[3]);
            for (Br b : bl) {
                double[] tk = new double[7];
                double d = 0;
                for (int i = 0; i < 7; i++) { long t0 = System.nanoTime(); d = c.optimalBridgeLength(b.bs, true); tk[i] = (System.nanoTime() - t0) / 1e6; }
                Arrays.sort(tk);
                // optimizeBridge at the knee from a FRESH capture each time
                // (repeat calls without one would compound the yaw delta)
                double[] to = new double[7];
                for (int i = 0; i < 7; i++) {
                    TrackCompiler.captureTargetHead(b.bs, c.pieces.get(b.p + 1), DT, c.fric());
                    long t0 = System.nanoTime(); c.optimizeBridge(b.bs, b.p + 1, d, true); to[i] = (System.nanoTime() - t0) / 1e6;
                }
                Arrays.sort(to);
                System.out.printf(Locale.US, "    b%d: optimalBridgeLength min %.1f ms median %.1f ms (knee %.4f m) | optimizeBridge(march only) min %.1f ms median %.1f ms%n",
                    b.p + 1, tk[0], tk[3], d, to[0], to[3]);
            }
            rebuild(c, false);
        }
    }
}
