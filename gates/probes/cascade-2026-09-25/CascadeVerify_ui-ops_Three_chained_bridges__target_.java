package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFICATION (2026-09-25) of the ui-ops family report
 *  "Three chained bridges: target piece 1 split mid-section + splitPiece,
 *  a fresh bridge piece1→piece2 switched to PY with Optimize length".
 *
 *  Rebuilds the configuration from the setup description ALONE (the
 *  hunter's probes were not read), runs the shell's rebuild loop
 *  verbatim (BridgeSlowProbe.rebuild's 12 fast + 40 exact passes),
 *  simulates bridge-window spinner clicks the way the shell does
 *  (GraphEditor.bridgeTargetWrite: the target's setDisp* write, then
 *  applyOptimalLength / applyBridgeGap = optimizeBridge on the LAST
 *  COMPILE's captured seams, then rebuildTrack) and prints per pass
 *  which bridge signatures differ from the stamped ones (slot index +
 *  name + old→new), the phase timings and the per-click total. The
 *  diagnostics run OUTSIDE the timed window.
 *
 *  Also runs the same click sequence on a second, identically built
 *  document with ONE change in the explicit path — re-capture the
 *  target head (TrackCompiler.captureTargetHead, what the next compile
 *  will capture anyway) BEFORE the explicit knee + march — and
 *  compares the final placements BITWISE.
 *
 *  javac -cp target/classes -d gates/out "gates/CascadeVerify_ui-ops_Three_chained_bridges__target_.java"
 *  java  -cp "target/classes;gates/out" uvd.CascadeVerify_ui_ops_Three_chained_bridges__target_ */
class CascadeVerify_ui_ops_Three_chained_bridges__target_ {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";

    /** BridgeSection.optSignature's 28 slots, by index. */
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "lenSlot(optLen?0:lastOptDist)" };

    // ---- timing accumulators (per click) ----
    static long tCompile, tRecon, tReopt, tKnee, tOpt, tWrite, tDiag, tCap;
    static int nCompile, nRecon, nReopt, nReoptTrue;
    static StringBuilder passLog;

    static void reset() {
        tCompile = tRecon = tReopt = tKnee = tOpt = tWrite = tDiag = tCap = 0;
        nCompile = nRecon = nReopt = nReoptTrue = 0;
        passLog = new StringBuilder();
    }

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
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
        if (secs.isEmpty()) return null;
        return secs.get(secs.size() - 1) instanceof BridgeSection b ? b : null;
    }

    static int spanIdxOf(Coaster c, int p, int k) {
        int s = 0;
        for (int i = 0; i < p; i++) s += c.pieces.get(i).sections.size();
        return s + k;
    }

    /** Which live optimize-mode bridges would the auto pass re-march
     *  RIGHT NOW (post-compile signature vs the stamped one)? Prints
     *  the differing slots (verbose) and logs "tag:b0,b1". Read-only;
     *  its time is excluded from the click total. */
    static void diag(Coaster c, String tag, boolean verbose) {
        long t0 = System.nanoTime();
        StringBuilder who = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE
                    || p + 1 >= c.pieces.size()) continue;
            double[] sig = bs.optSignature();
            if (bs.optSig == null) {
                if (verbose) System.out.printf(Locale.US, "      %s b%d: FIRST SIGHT (record only)%n", tag, p);
                continue;
            }
            if (bs.optSig.length != sig.length) {
                if (verbose) System.out.printf(Locale.US, "      %s b%d: SIG_STALE%n", tag, p);
                who.append(who.length() == 0 ? "" : ",").append("b").append(p).append("!");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - bs.optSig[i]) > 1e-9) {
                    n++;
                    if (n <= 12) sb.append(String.format(Locale.US, " [%d %s %.6g→%.6g]",
                        i, SLOT[i], bs.optSig[i], sig[i]));
                }
            }
            if (n > 0) {
                who.append(who.length() == 0 ? "" : ",").append("b").append(p)
                   .append(bs.optLen ? "(knee)" : "");
                if (verbose) System.out.printf(Locale.US,
                    "      %s b%d WILL RE-MARCH (%d slots differ, optLen=%b lastOptDist=%.2f):%s%s%n",
                    tag, p, n, bs.optLen, bs.lastOptDist, sb, n > 12 ? " …" : "");
            }
        }
        if (who.length() > 0) passLog.append(' ').append(tag).append(':').append(who);
        tDiag += System.nanoTime() - t0;
    }

    record Loop(int fast, int exact, boolean quiesced, int samples) {}

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). */
    static Loop rebuild(Coaster c, boolean verbose) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            diag(c, "fast" + pass, verbose);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0; nRecon++;
            if (again) passLog.append(" fast").append(pass).append(":RECONCILE");
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tReopt += System.nanoTime() - t0; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            diag(c, "exact" + pass, verbose);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            tRecon += System.nanoTime() - t0; nRecon++;
            if (again) passLog.append(" exact").append(pass).append(":RECONCILE");
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tReopt += System.nanoTime() - t0; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return new Loop(fast, exact, quiesced, r.samples().size());
    }

    static String stats() {
        return String.format(Locale.US,
            "compile %d× %.0f ms | reconcile %d× %.0f ms | autoReopt %d× (%d true) %.0f ms | explicit: write %.1f, recapture %.1f, knee %.0f, optimizeBridge %.0f ms",
            nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReopt, nReoptTrue,
            tReopt / 1e6, tWrite / 1e6, tCap / 1e6, tKnee / 1e6, tOpt / 1e6);
    }

    /** One bridge-window spinner click on piece bp's trailing bridge
     *  — GraphEditor.bridgeTargetWrite's recipe: the target's setDisp*
     *  write, then optimizeBridge at lastOptDist (or the knee under
     *  optLen) against the LAST COMPILE's captured seams, then the
     *  rebuild loop. recapture = the candidate fix: re-capture the
     *  target head first (what the next compile captures anyway). */
    static double click(Coaster c, int bp, int axis, double delta, String label,
                        boolean verbose, boolean recapture) {
        return click(c, bp, axis, delta, label, verbose, recapture ? 1 : 0);
    }

    /** mode 0 = the shell's path today (stale explicit knee + march),
     *  1 = re-capture the target head first, 2 = NO explicit step at
     *  all (write + rebuild loop only — the auto pass acts alone). */
    static double click(Coaster c, int bp, int axis, double delta, String label,
                        boolean verbose, int mode) {
        boolean recapture = mode == 1;
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        tWrite = System.nanoTime() - t0;
        if (recapture) {
            long tc = System.nanoTime();
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            tCap = System.nanoTime() - tc;
        }
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d = Double.NaN;
        if (mode != 2) {
            long t1 = System.nanoTime();
            if (bs.optLen) {
                d = c.optimalBridgeLength(bs, py);
                tKnee = System.nanoTime() - t1;
            } else {
                d = bs.lastOptDist > 0 ? bs.lastOptDist : 1;
            }
            long t2 = System.nanoTime();
            c.optimizeBridge(bs, bp + 1, d, py);
            tOpt = System.nanoTime() - t2;
        }
        Loop lp = rebuild(c, verbose);
        double ms = (System.nanoTime() - t0 - tDiag) / 1e6;
        System.out.printf(Locale.US,
            "  [%s%s] b%d axis %d: %.0f ms/click — fast=%d exact=%d quiesced=%b samples=%d — explicit dist %.2f → final lastOptDist %.2f — re-marches:%s%n      %s%n",
            label, mode == 1 ? " +RECAPTURE" : mode == 2 ? " NO-EXPLICIT" : "", bp, axis, ms, lp.fast, lp.exact, lp.quiesced,
            lp.samples, d, bs.lastOptDist, passLog.length() == 0 ? " none" : passLog, stats());
        return ms;
    }

    static void dump(Coaster c, String tag) {
        System.out.println("  --- pieces (" + tag + ") ---");
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            BridgeSection b = bridgeOf(c, p);
            System.out.printf(Locale.US,
                "  piece %d: %d sections, mode=%s affect=%b follow=%b design=(%.3f,%.3f) true=(%.3f,%.3f) yaw=%.3f pos=(%.2f,%.2f,%.2f)%s%n",
                p, tp.sections.size(), tp.orientMode, tp.affectSubsequentSeams, tp.followPrev,
                tp.designPitchDeg, tp.designRollDeg, tp.pitchDeg, tp.rollDeg, tp.yawDeg,
                tp.x, tp.y, tp.z,
                b != null ? String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.2f lenA/B=%.2f/%.2f locks=%b/%b/%b optSig=%s",
                    b.optMode, b.optLen, b.lastOptDist, b.lenA, b.lenB, b.lockYaw, b.lockPitch, b.lockRoll,
                    b.optSig == null ? "null" : b.optSig.length == 0 ? "STALE" : "stamped") : "");
        }
    }

    /** Standalone knee cost of a bridge (read-only), min of n calls. */
    static double kneeMs(Coaster c, BridgeSection bs, int n) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            c.optimalBridgeLength(bs, bs.optMode == BridgeSection.OPT_POS_YAW);
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        return best;
    }

    /** The reported setup: load; rebuild; splitSectionAt(1,0,0.5);
     *  splitPiece(1,1,span); rebuild; fresh OPT_NONE bridge piece1→
     *  piece2; rebuild; PY + optLen; knee; optimizeBridge; rebuild. */
    static Coaster build(boolean verbose) throws Exception {
        Coaster c = load();
        reset();
        Loop lp = rebuild(c, false);
        if (verbose) {
            System.out.printf(Locale.US, "  open: fast=%d exact=%d quiesced=%b samples=%d — %s%n",
                lp.fast, lp.exact, lp.quiesced, lp.samples, stats());
            dump(c, "as loaded");
        }
        boolean ok = c.splitSectionAt(1, 0, 0.5, DT);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        TrackCompiler.Span boundary = r.spans().get(spanIdxOf(c, 1, 0));
        int np = c.splitPiece(1, 1, boundary);
        reset();
        lp = rebuild(c, verbose);
        if (verbose) {
            System.out.printf(Locale.US, "  splitSectionAt(1,0,0.5)=%b, splitPiece(1,1,span) → piece %d; rebuild: fast=%d exact=%d quiesced=%b — %s%n",
                ok, np, lp.fast, lp.exact, lp.quiesced, stats());
        }
        r = TrackCompiler.compile(c, DT);
        TrackPiece p1 = c.pieces.get(1), p2 = c.pieces.get(2);
        TrackCompiler.Span endSpan = r.spans().get(spanIdxOf(c, 1, p1.sections.size() - 1));
        double vEnd = r.samples().get(Math.min(endSpan.i1(), r.samples().size() - 1)).v();
        BridgeSection nb = BridgeSection.solve(endSpan.exit(), p2.entryPose(), vEnd);
        nb.optMode = BridgeSection.OPT_NONE;
        p1.sections.add(nb);
        reset();
        lp = rebuild(c, verbose);
        if (verbose) {
            System.out.printf(Locale.US, "  fresh OPT_NONE bridge piece1→piece2: gap %.4f m, vEnd %.3f, locks=%b/%b/%b; rebuild: fast=%d exact=%d quiesced=%b — %s%n",
                p2.entryPose().pos().sub(endSpan.exit().pos()).length(), vEnd,
                nb.lockYaw, nb.lockPitch, nb.lockRoll, lp.fast, lp.exact, lp.quiesced, stats());
        }
        nb.optMode = BridgeSection.OPT_POS_YAW;
        nb.optLen = true;
        long t0 = System.nanoTime();
        double knee = c.optimalBridgeLength(nb, true);
        double kMs = (System.nanoTime() - t0) / 1e6;
        t0 = System.nanoTime();
        c.optimizeBridge(nb, 2, knee, true);
        double oMs = (System.nanoTime() - t0) / 1e6;
        reset();
        lp = rebuild(c, verbose);
        if (verbose) {
            System.out.printf(Locale.US, "  PY + optLen: explicit knee %.3f m (%.0f ms), optimizeBridge %.0f ms, lastOptDist %.3f; rebuild: fast=%d exact=%d quiesced=%b re-marches:%s — %s%n",
                knee, kMs, oMs, nb.lastOptDist, lp.fast, lp.exact, lp.quiesced, passLog, stats());
            dump(c, "three chained bridges");
            reset();
            lp = rebuild(c, true);
            System.out.printf(Locale.US, "  idle rebuild: fast=%d exact=%d quiesced=%b re-marches:%s — %s%n",
                lp.fast, lp.exact, lp.quiesced, passLog.length() == 0 ? " none" : passLog, stats());
        }
        return c;
    }

    /** Bitwise placement + bridge-field comparison of two documents. */
    static int compareDocs(Coaster a, Coaster b, String tag) {
        int diff = 0;
        if (a.pieces.size() != b.pieces.size()) {
            System.out.println("  " + tag + ": piece COUNT differs");
            return 1;
        }
        for (int p = 0; p < a.pieces.size(); p++) {
            TrackPiece x = a.pieces.get(p), y = b.pieces.get(p);
            double[] fx = { x.x, x.y, x.z, x.yawDeg, x.pitchDeg, x.rollDeg, x.designPitchDeg, x.designRollDeg };
            double[] fy = { y.x, y.y, y.z, y.yawDeg, y.pitchDeg, y.rollDeg, y.designPitchDeg, y.designRollDeg };
            String[] nm = { "x", "y", "z", "yaw", "pitch", "roll", "dPitch", "dRoll" };
            for (int i = 0; i < fx.length; i++) {
                if (Double.doubleToLongBits(fx[i]) != Double.doubleToLongBits(fy[i])) {
                    diff++;
                    System.out.printf(Locale.US, "  %s: piece %d %s differs: %.17g vs %.17g (Δ %.3g)%n",
                        tag, p, nm[i], fx[i], fy[i], fx[i] - fy[i]);
                }
            }
            BridgeSection ba = bridgeOf(a, p), bb = bridgeOf(b, p);
            if ((ba == null) != (bb == null)) { diff++; continue; }
            if (ba == null) continue;
            double[] gx = { ba.lastOptDist, ba.lenA, ba.lenB, ba.vDesign, ba.vB };
            double[] gy = { bb.lastOptDist, bb.lenA, bb.lenB, bb.vDesign, bb.vB };
            String[] gn = { "lastOptDist", "lenA", "lenB", "vDesign", "vB" };
            for (int i = 0; i < gx.length; i++) {
                if (Double.doubleToLongBits(gx[i]) != Double.doubleToLongBits(gy[i])) {
                    diff++;
                    System.out.printf(Locale.US, "  %s: bridge b%d %s differs: %.17g vs %.17g%n",
                        tag, p, gn[i], gx[i], gy[i]);
                }
            }
        }
        TrackCompiler.Result ra = TrackCompiler.compile(a, DT), rb = TrackCompiler.compile(b, DT);
        if (ra.samples().size() != rb.samples().size()) {
            diff++;
            System.out.println("  " + tag + ": SAMPLE COUNT differs " + ra.samples().size() + " vs " + rb.samples().size());
        } else {
            int bad = 0; double maxD = 0;
            for (int i = 0; i < ra.samples().size(); i++) {
                Vec3 pa = ra.samples().get(i).pos(), pb = rb.samples().get(i).pos();
                if (Double.doubleToLongBits(pa.x()) != Double.doubleToLongBits(pb.x())
                        || Double.doubleToLongBits(pa.y()) != Double.doubleToLongBits(pb.y())
                        || Double.doubleToLongBits(pa.z()) != Double.doubleToLongBits(pb.z())) {
                    bad++;
                    maxD = Math.max(maxD, pa.sub(pb).length());
                }
            }
            if (bad > 0) {
                diff++;
                System.out.printf(Locale.US, "  %s: %d of %d compiled samples differ, max %.3g m%n", tag, bad, ra.samples().size(), maxD);
            }
        }
        System.out.println("  " + tag + ": " + (diff == 0 ? "BITWISE IDENTICAL (placements, bridge fields, compiled samples)" : diff + " differences"));
        return diff;
    }

    static void scenario(int rep) throws Exception {
        System.out.println("=== rep " + rep + (rep == 0 ? " (cold JIT)" : " (warm JIT)") + " ===");
        Coaster c = build(true);
        BridgeSection nb = bridgeOf(c, 1);
        System.out.printf(Locale.US, "  standalone knee cost b1 (min of 3): %.0f ms; b0 at its seams: %.0f ms; b2: %.0f ms%n",
            kneeMs(c, nb, 3), kneeMs(c, bridgeOf(c, 0), 3), kneeMs(c, bridgeOf(c, 2), 3));

        System.out.println("  --- clicks on b0 (piece 0 → piece 1, REAL_ONLY target, P/R locked, no optLen) ---");
        click(c, 0, 1, 1.0, "b0 pitch", true, false);
        click(c, 0, 1, 1.0, "b0 pitch", false, false);
        click(c, 0, 2, 1.0, "b0 roll", false, false);
        System.out.println("  --- clicks on b1 (NEW, piece 1 → piece 2, LOCKSTEP split piece, P/R locked, optLen) ---");
        click(c, 1, 1, 1.0, "b1 pitch optLen", true, false);
        click(c, 1, 1, 1.0, "b1 pitch optLen", false, false);
        click(c, 1, 2, 1.0, "b1 roll optLen", false, false);
        click(c, 1, 0, 1.0, "b1 yaw optLen", false, false);
        System.out.println("  --- clicks on b2 (piece 2 → piece 3, REAL_ONLY target, all locked, no optLen) ---");
        click(c, 2, 1, 1.0, "b2 pitch", false, false);
        click(c, 2, 2, 1.0, "b2 roll", false, false);

        System.out.println("  --- variant: b1 Optimize length OFF at 10 m ---");
        nb.optLen = false;
        c.optimizeBridge(nb, 2, 10, true);
        rebuild(c, false);
        click(c, 1, 1, 1.0, "b1 pitch @10m", false, false);
        click(c, 1, 2, 1.0, "b1 roll @10m", false, false);
        click(c, 0, 1, 1.0, "b0 pitch (b1 @10m)", false, false);

        System.out.println("  --- variant: b1 optLen ON again ---");
        nb.optLen = true;
        c.optimizeBridge(nb, 2, c.optimalBridgeLength(nb, true), true);
        rebuild(c, false);
        click(c, 1, 1, 1.0, "b1 pitch optLen", false, false);
        click(c, 1, 1, 1.0, "b1 pitch optLen", true, true);
        click(c, 1, 2, 1.0, "b1 roll optLen", false, true);
        click(c, 0, 1, 1.0, "b0 pitch", false, true);

        System.out.println("  --- variant: piece 2 LOCKSTEP + b1 pitch FREE (lockPitch=false), optLen ---");
        nb.lockPitch = false;
        c.optimizeBridge(nb, 2, c.optimalBridgeLength(nb, true), true);
        rebuild(c, false);
        click(c, 1, 2, 1.0, "b1 roll pitch-free", true, false);
        click(c, 1, 2, 1.0, "b1 roll pitch-free", false, false);
        nb.lockPitch = true;

        System.out.println("  --- variant: piece 2 REAL_ONLY, b1 optLen ---");
        c.pieces.get(2).orientMode = TrackPiece.OrientMode.REAL_ONLY;
        c.optimizeBridge(nb, 2, c.optimalBridgeLength(nb, true), true);
        rebuild(c, false);
        click(c, 1, 1, 1.0, "b1 pitch realonly optLen", false, false);
        click(c, 0, 1, 1.0, "b0 pitch (p2 realonly)", false, false);

        // ---- the candidate fix, compared bitwise on two identically built documents ----
        System.out.println("  --- FIX CHECK: same click sequence, explicit path today vs with target-head re-capture ---");
        Coaster A = build(false), B = build(false), C = build(false);
        System.out.println("  fresh builds identical?");
        compareDocs(A, B, "  pre-click A vs B");
        compareDocs(B, C, "  pre-click B vs C");
        int[][] seq = { {1, 1}, {1, 1}, {1, 2}, {0, 1}, {2, 2}, {1, 0}, {0, 2}, {1, 1} };
        double msA = 0, msB = 0, msC = 0;
        for (int[] s : seq) {
            msA += click(A, s[0], s[1], 1.0, "today", false, 0);
            msB += click(B, s[0], s[1], 1.0, "fix", false, 1);
            msC += click(C, s[0], s[1], 1.0, "auto-only", false, 2);
        }
        System.out.printf(Locale.US, "  sequence of %d clicks: today %.0f ms, with re-capture %.0f ms, auto pass alone %.0f ms%n", seq.length, msA, msB, msC);
        compareDocs(A, B, "  post-sequence TODAY vs FIX");
        compareDocs(B, C, "  post-sequence FIX vs AUTO-ONLY");
        // and the idle rebuild after the fix stays a no-op
        reset();
        Loop lp = rebuild(B, false);
        System.out.printf(Locale.US, "  idle rebuild after the fixed sequence: fast=%d exact=%d quiesced=%b re-marches:%s%n",
            lp.fast, lp.exact, lp.quiesced, passLog.length() == 0 ? " none" : passLog);
    }

    static void fixCheck() throws Exception {
        System.out.println("=== FIX CHECK ONLY ===");
        Coaster A = build(false), B = build(false), C = build(false);
        compareDocs(A, B, "  pre-click A vs B");
        compareDocs(B, C, "  pre-click B vs C");
        int[][] seq = { {1, 1}, {1, 1}, {1, 2}, {0, 1}, {2, 2}, {1, 0}, {0, 2}, {1, 1} };
        double msA = 0, msB = 0, msC = 0;
        for (int[] s : seq) {
            msA += click(A, s[0], s[1], 1.0, "today", false, 0);
            msB += click(B, s[0], s[1], 1.0, "fix", false, 1);
            msC += click(C, s[0], s[1], 1.0, "auto-only", false, 2);
        }
        System.out.printf(Locale.US, "  sequence of %d clicks: today %.0f ms, with re-capture %.0f ms, auto pass alone %.0f ms%n", seq.length, msA, msB, msC);
        compareDocs(A, B, "  post-sequence TODAY vs FIX");
        compareDocs(B, C, "  post-sequence FIX vs AUTO-ONLY");
        reset();
        Loop lp = rebuild(B, false);
        System.out.printf(Locale.US, "  idle rebuild after the fixed sequence: fast=%d exact=%d quiesced=%b re-marches:%s%n",
            lp.fast, lp.exact, lp.quiesced, passLog.length() == 0 ? " none" : passLog);
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("fix")) { fixCheck(); return; }
        for (int rep = 0; rep < 2; rep++) scenario(rep);
    }
}
