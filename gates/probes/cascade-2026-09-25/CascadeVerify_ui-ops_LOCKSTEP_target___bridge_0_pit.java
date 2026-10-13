package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFY (2026-09-25) of the reported ui-ops cascade
 *  "LOCKSTEP target + bridge 0 pitch UNLOCKED (march aligns pitch) +
 *  Length 120 m, roll held 0/1/-90" on the user's file. Rebuilds the
 *  configuration from the setup description, times the REAL
 *  optimizeBridge + the shell's rebuild loop (verbatim), then runs a
 *  faithful REPLICA of Coaster.optimizeBridgeBody's LOCKSTEP settle
 *  loop on a second, identically-prepared Coaster to print the
 *  per-round alignment deltas, the per-round march cost and the
 *  signature slots that move — and checks the replica lands bitwise
 *  on the real call's placement so the trace is trustworthy. */
class CascadeVerify_ui_ops_LOCKSTEP_target___bridge_0_pit {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len" };

    static long tCompile; static int nCompile;

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
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
        tCompile += System.nanoTime() - t0; nCompile++;
        return r;
    }

    /** Which signature slots of each optimize-mode bridge differ from
     *  its stamp (what autoReoptimizeBridges will act on). */
    static String sigDiffs(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs.optMode == BridgeSection.OPT_NONE) continue;
            double[] sig = bs.optSignature();
            if (bs.optSig == null) { sb.append(" b").append(p).append(":firstSight"); continue; }
            if (bs.optSig == BridgeSection.SIG_STALE) { sb.append(" b").append(p).append(":STALE"); continue; }
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - bs.optSig[i]) > 1e-9) {
                    d.append(String.format(Locale.US, " [%d %s %.6g→%.6g]", i, SLOT[i], bs.optSig[i], sig[i]));
                }
            }
            sb.append(" b").append(p).append(d.length() == 0 ? ":match" : ":" + d);
        }
        return sb.toString();
    }

    /** FvdDesigner.rebuildTrack's settle loop verbatim (at rest),
     *  printing the signature slots each pass acts on. */
    static String rebuild(Coaster c, boolean verbose) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            boolean again = c.reconcileChainedSpeeds(r);
            String diffs = sigDiffs(c);
            boolean re = c.autoReoptimizeBridges(r);
            if (verbose) System.out.printf(Locale.US, "      fast pass %d: reconcile=%b reopt=%b |%s%n", pass, again, re, diffs);
            if (re) again = true;
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            boolean again = c.reconcileChainedSpeeds(r, true);
            String diffs = sigDiffs(c);
            boolean re = c.autoReoptimizeBridges(r);
            if (verbose) System.out.printf(Locale.US, "      exact pass %d: reconcile=%b reopt=%b |%s%n", pass, again, re, diffs);
            if (re) again = true;
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return String.format(Locale.US, "fast=%d exact=%d quiesced=%b rebuild %.0f ms (%d compiles)",
            fast, exact, quiesced, (System.nanoTime() - t0) / 1e6, fast + exact);
    }

    static double[] placementOf(TrackPiece t) {
        return new double[] { t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg, t.designPitchDeg, t.designRollDeg };
    }

    static void restore(TrackPiece t, double[] s) {
        t.x = s[0]; t.y = s[1]; t.z = s[2]; t.yawDeg = s[3]; t.pitchDeg = s[4];
        t.rollDeg = s[5]; t.designPitchDeg = s[6]; t.designRollDeg = s[7];
    }

    /** Last replica settle's summary for the sweep. */
    static int lastRounds; static boolean lastConverged; static double lastBest, lastRatio;

    /** REPLICA of Coaster.optimizeBridgeBody (alignPieceForBridge +
     *  settleLockstepAlign + fallback lens + stamp), instrumented.
     *  accel = the PROPOSED FIX: once two consecutive signed pitch
     *  deltas show a slow contraction (|ratio| > 0.5), take the Aitken
     *  step p := pre + δ/(1 − g) through editPitch (one tier rule)
     *  instead of the raw aligned value; fast cases stay bitwise. */
    static void replicaOptimize(Coaster c, BridgeSection bs, int targetIdx, double dist,
                                boolean orient, boolean verbose, boolean accel) {
        TrackPiece t = c.pieces.get(targetIdx);
        bs.lastOptDist = dist;
        long t0 = System.nanoTime();
        c.alignPieceForBridge(bs, targetIdx, dist, orient);
        if (verbose) System.out.printf(Locale.US, "      initial align: %.0f ms → pitch %.9f design %.9f roll %.6f%n",
            (System.nanoTime() - t0) / 1e6, t.pitchDeg, t.designPitchDeg, t.rollDeg);
        boolean[] align = { !bs.lockYaw, !bs.lockPitch, !bs.lockRoll };
        boolean settled = false;
        lastRounds = 0; lastConverged = false; lastBest = Double.NaN; lastRatio = Double.NaN;
        if (orient && t.orientMode == TrackPiece.OrientMode.LOCKSTEP && (align[1] || align[2])) {
            settled = true;
            double[] best = null; double bestDelta = Double.POSITIVE_INFINITY;
            boolean converged = false;
            double prevDelta = Double.NaN, prevDPitch = Double.NaN;
            double ratioSum = 0; int ratioN = 0;
            for (int round = 0; round < 24; round++) {
                lastRounds = round + 1;
                long a = System.nanoTime();
                TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
                long b = System.nanoTime();
                double accBu = bs.accB.dot(bs.solvedTarget.U());
                double[] pre = placementOf(t);
                c.alignPieceForBridge(bs, targetIdx, dist, orient);
                long e = System.nanoTime();
                double dPitch = t.pitchDeg - pre[4];
                String acc = "";
                if (accel && align[1] && !Double.isNaN(prevDPitch) && prevDPitch != 0) {
                    double g = dPitch / prevDPitch;   // the map's local gain
                    if (Math.abs(g) > 0.5 && g < 0.98) {
                        double lam = 1 / (1 - g);
                        t.editPitch(pre[4] + lam * dPitch);
                        acc = String.format(Locale.US, " AITKEN g=%.3f λ=%.3f → pitch %.9f", g, lam, t.pitchDeg);
                        dPitch = Double.NaN;   // the raw ratio is meaningless after a jump
                    }
                }
                double delta = Math.max(Math.abs(TrackPiece.wrap180(t.yawDeg - pre[3])),
                    Math.max(Math.abs(t.pitchDeg - pre[4]), Math.abs(TrackPiece.wrap180(t.rollDeg - pre[5]))));
                if (!Double.isNaN(prevDelta) && prevDelta > 0) { ratioSum += delta / prevDelta; ratioN++; }
                if (verbose) System.out.printf(Locale.US,
                    "      round %2d: capture %.1f ms  align %.1f ms | accB.u %.7f tB.y %.7f vB %.4f | pitch %.9f Δpitch %+.3e Δyaw %+.3e | ratio %s%s%n",
                    round, (b - a) / 1e6, (e - b) / 1e6, accBu, bs.solvedTarget.T().y(), bs.vB,
                    t.pitchDeg, t.pitchDeg - pre[4], TrackPiece.wrap180(t.yawDeg - pre[3]),
                    Double.isNaN(prevDelta) ? "—" : String.format(Locale.US, "%.3f", delta / prevDelta), acc);
                prevDelta = delta; prevDPitch = dPitch;
                if (delta < bestDelta) { bestDelta = delta; best = pre; }
                if (delta < 1e-9) { converged = true; break; }
                if (align[1] && Math.abs(t.pitchDeg) >= 85) break;
            }
            if (!converged && best != null) restore(t, best);
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            lastConverged = converged; lastBest = bestDelta; lastRatio = ratioN > 0 ? ratioSum / ratioN : Double.NaN;
            if (verbose) System.out.printf(Locale.US, "      settle: converged=%b bestDelta=%.3e%n", converged, bestDelta);
        }
        if (settled && bs.optLen) throw new IllegalStateException("optLen not in this configuration");
        TrackPiece tgt = c.pieces.get(targetIdx);
        double chord = new Vec3(tgt.x, tgt.y, tgt.z).sub(bs.solvedEntry.pos()).length();
        double fb = Math.min(dist, Math.max(1, 1.5 * chord));
        bs.lenA = fb; bs.lenB = fb;
        bs.optSig = bs.optSignature();
    }

    static String placement(TrackPiece t) {
        return String.format(Locale.US, "x %.6f y %.6f z %.6f yaw %.6f pitch %.9f roll %.6f dPitch %.9f dRoll %.6f",
            t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg, t.designPitchDeg, t.designRollDeg);
    }

    static boolean samePlacement(TrackPiece a, TrackPiece b) {
        double[] pa = placementOf(a), pb = placementOf(b);
        for (int i = 0; i < pa.length; i++) {
            if (Double.doubleToLongBits(pa[i]) != Double.doubleToLongBits(pb[i])) return false;
        }
        return true;
    }

    /** Both coasters through the shared prologue. */
    static void prologue(Coaster c, double len) {
        for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
        rebuild(c, false);
        BridgeSection b0 = bridgeOf(c, 0);
        b0.lockPitch = false;
        c.optimizeBridge(b0, 1, len, true);
        rebuild(c, false);
    }

    /** Does the stamped placement LAND (the march spine renders) or
     *  fall to the septic? Mirrors BridgeSection.integrate's gate, and
     *  prints the gate's three numbers + the march cost via reflection
     *  on Coaster's private marchEnd / march / bankGap. */
    static String landing(BridgeSection bs) {
        boolean orient = bs.optMode == BridgeSection.OPT_POS_YAW;
        String verdict = Coaster.marchSpine(bs, orient) == null ? "SEPTIC (march misses)" : "march lands";
        try {
            java.lang.reflect.Method me = Coaster.class.getDeclaredMethod("marchEnd",
                BridgeSection.class, double.class, boolean[].class, List.class, List.class);
            me.setAccessible(true);
            java.lang.reflect.Method bg = Coaster.class.getDeclaredMethod("bankGap",
                Vec3.class, Vec3.class, Vec3.class, Vec3.class);
            bg.setAccessible(true);
            java.lang.reflect.Method ma = Coaster.class.getDeclaredMethod("march",
                Vec3.class, Vec3.class, Vec3.class, double.class, double[].class, double[].class,
                double.class, double.class, double.class, List.class, List.class, double[].class);
            ma.setAccessible(true);
            boolean[] align = orient ? new boolean[]{ !bs.lockYaw, !bs.lockPitch, !bs.lockRoll } : new boolean[3];
            long t0 = System.nanoTime();
            Vec3[] st = (Vec3[]) me.invoke(null, bs, bs.lastOptDist, align, null, null);
            long t1 = System.nanoTime();
            double[] meta = new double[2];
            ma.invoke(null, bs.solvedEntry.pos(), bs.solvedEntry.T(), bs.solvedEntry.U(), bs.lastOptDist,
                new double[6], new double[6], 0.0, 0.0, 0.0, null, null, meta);
            long t2 = System.nanoTime();
            double miss = st[0].sub(bs.solvedTarget.pos()).length();
            double tAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, st[1].dot(bs.solvedTarget.T())))));
            double bMiss = Math.toDegrees(Math.abs((double) bg.invoke(null, st[1], st[2],
                bs.solvedTarget.T(), bs.solvedTarget.U())));
            double mEnd = (t1 - t0) / 1e6, mRaw = (t2 - t1) / 1e6;
            return String.format(Locale.US, "%s [miss %.4f m, tAng %.4f°, bMiss %.3f°; endT.y %.4f; marchEnd %.1f ms vs one raw march %.2f ms ≈ %.0f marches]",
                verdict, miss, tAng, bMiss, st[1].y(), mEnd, mRaw, mEnd / Math.max(1e-9, mRaw));
        } catch (ReflectiveOperationException e) {
            return verdict + " (reflection failed: " + e + ")";
        }
    }

    /** SWEEP: the settle loop's gain vs bridge length (replica, roll
     *  clicks 0 / 1 / -90 as in the report), plain vs the Aitken fix. */
    static void sweep(boolean accel) throws IOException {
        System.out.println("=== sweep " + (accel ? "WITH Aitken fix" : "plain (shipped loop)") + " ===");
        for (double len : new double[]{ 27, 40, 60, 90, 120 }) {
            Coaster c = load();
            prologue(c, len);
            for (double v : new double[]{ 0, 1, -90 }) {
                TrackPiece t = c.pieces.get(1);
                BridgeSection b0 = bridgeOf(c, 0);
                long t0 = System.nanoTime();
                t.setDispRoll(v, true);
                replicaOptimize(c, b0, 1, len, true, false, accel);
                long t1 = System.nanoTime();
                String loop = rebuild(c, false);
                long t2 = System.nanoTime();
                String idle = rebuild(c, false);
                System.out.printf(Locale.US,
                    "  len %3.0f roll %4.0f: optimize %5.0f ms (%2d rounds, converged=%-5b best %.2e, mean |ratio| %.3f) + rebuild %4.0f ms — %s — pitch %.6f — %s — idle: %s%n",
                    len, v, (t1 - t0) / 1e6, lastRounds, lastConverged, lastBest, lastRatio,
                    (t2 - t1) / 1e6, loop, t.pitchDeg, landing(b0), idle);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        if (a.length > 0 && a[0].equals("sweep")) {
            sweep(false);   // cold
            sweep(false);   // warm
            sweep(true);
            sweep(true);
            return;
        }
        double[] lens = a.length > 0 ? new double[]{ Double.parseDouble(a[0]) } : new double[]{ 120, 27 };
        for (int rep = 0; rep < 2; rep++) {   // rep 0 warms the JIT
            for (double len : lens) {
                System.out.printf(Locale.US, "=== rep %d  Length %.0f m ===%n", rep, len);
                Coaster c = load();       // real calls, timed
                Coaster cd = load();      // replica calls, traced
                for (int p = 0; p < c.pieces.size(); p++) {
                    TrackPiece tp = c.pieces.get(p);
                    System.out.printf(Locale.US, "  piece %d mode=%s design=(%.4f,%.4f) true=(%.4f,%.4f) v=%.3f%n",
                        p, tp.orientMode, tp.designPitchDeg, tp.designRollDeg, tp.pitchDeg, tp.rollDeg,
                        tp.sections.get(0).vDesign());
                }
                BridgeSection b0 = bridgeOf(c, 0);
                System.out.printf(Locale.US, "  b0 optMode=%d optLen=%b lastOptDist=%.2f locks=%b/%b/%b%n",
                    b0.optMode, b0.optLen, b0.lastOptDist, b0.lockYaw, b0.lockPitch, b0.lockRoll);
                long t0 = System.nanoTime();
                prologue(c, len);
                System.out.printf(Locale.US, "  prologue (LOCKSTEP + unlock pitch + optimize %.0f + rebuild): %.0f ms%n",
                    len, (System.nanoTime() - t0) / 1e6);
                prologue(cd, len);
                System.out.println("  replica-coaster identical after prologue: " + samePlacement(c.pieces.get(1), cd.pieces.get(1)));
                System.out.println("  piece 1 after prologue: " + placement(c.pieces.get(1)));

                for (double v : new double[]{ 0, 1, -90 }) {
                    System.out.printf(Locale.US, "  --- click: setDispRoll(%.0f, true) on piece 1 ---%n", v);
                    // REAL path, timed
                    TrackPiece t = c.pieces.get(1);
                    b0 = bridgeOf(c, 0);
                    tCompile = 0; nCompile = 0;
                    long c0 = System.nanoTime();
                    t.setDispRoll(v, true);
                    long c1 = System.nanoTime();
                    c.optimizeBridge(b0, 1, len, true);
                    long c2 = System.nanoTime();
                    String loop = rebuild(c, true);
                    long c3 = System.nanoTime();
                    System.out.printf(Locale.US, "    REAL: total %.0f ms = optimizeBridge %.0f ms + rebuild %.0f ms — %s — compile %d× %.0f ms%n",
                        (c3 - c0) / 1e6, (c2 - c1) / 1e6, (c3 - c2) / 1e6, loop, nCompile, tCompile / 1e6);
                    System.out.println("    piece 1 after: " + placement(t) + " — " + landing(b0));
                    // REPLICA path, traced
                    TrackPiece td = cd.pieces.get(1);
                    BridgeSection bd = bridgeOf(cd, 0);
                    td.setDispRoll(v, true);
                    long d0 = System.nanoTime();
                    replicaOptimize(cd, bd, 1, len, true, true, false);
                    System.out.printf(Locale.US, "    REPLICA optimize: %.0f ms; placement bitwise == real: %b%n",
                        (System.nanoTime() - d0) / 1e6, samePlacement(t, td));
                    rebuild(cd, false);
                    System.out.println("    after rebuild bitwise == real: " + samePlacement(t, td));
                    // an IDLE rebuild afterwards must be a no-op
                    String idle = rebuild(c, false);
                    System.out.println("    idle rebuild: " + idle);
                }
            }
        }
    }
}
