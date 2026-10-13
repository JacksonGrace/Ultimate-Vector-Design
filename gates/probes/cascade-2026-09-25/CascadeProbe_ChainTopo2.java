package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY C, part 2 (2026-09-25): clean timing of the
 *  two cost drivers the first probe surfaced, and the chain-depth
 *  scaling. (1) optimalBridgeLength (the "Optimize length" knee) and a
 *  compile timed in isolation with repetitions (min / median) on the
 *  user's file; (2) N chained optimize-orientation bridges (N = 2..5,
 *  REAL_ONLY targets, pitch/roll locked) with Optimize length OFF and
 *  ON: passes, re-marches, knees and ms for a click on the FIRST
 *  bridge, so the linear cascade's growth is measured, not inferred. */
public class CascadeProbe_ChainTopo2 {
    static final double DT = Coaster.INTEGRATE_DT;
    static int nKnee;
    static long tKnee;

    static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    static double min(double[] a) {
        double m = Double.MAX_VALUE;
        for (double v : a) m = Math.min(m, v);
        return m;
    }

    /** N chained bridges on the user's file: P0 →b1→ P1 →b2→ P2 (as
     *  saved) plus copies of P2 bridged on, all REAL_ONLY, locks
     *  default, Optimize length per flag. */
    static Coaster chain(int nBridges, boolean optLen) throws Exception {
        Coaster c = CascadeProbe_ChainTopo.load();
        for (int b = 2; b < nBridges; b++) {
            CascadeProbe_ChainTopo.newTarget(c, c.pieces.size() - 1, 40, -6, 25);
            CascadeProbe_ChainTopo.addBridge(c, c.pieces.size() - 2,
                BridgeSection.OPT_POS_YAW, false, 25, true, true);
        }
        if (optLen) {
            for (int p = 0; p < nBridges; p++) {
                BridgeSection bs = CascadeProbe_ChainTopo.bridgeOf(c, p);
                bs.optLen = true;
                double d = c.optimalBridgeLength(bs, true);
                c.optimizeBridge(bs, p + 1, d, true);
            }
        }
        CascadeProbe_ChainTopo.rebuild(c);
        CascadeProbe_ChainTopo.rebuild(c);
        return c;
    }

    public static void main(String[] a) throws Exception {
        // warm-up
        {
            Coaster w = CascadeProbe_ChainTopo.load();
            CascadeProbe_ChainTopo.rebuild(w);
            for (int i = 0; i < 4; i++) CascadeProbe_ChainTopo.click(w, 0, 1, 1.0);
            BridgeSection b = CascadeProbe_ChainTopo.bridgeOf(w, 0);
            for (int i = 0; i < 4; i++) w.optimalBridgeLength(b, true);
        }

        // ---- (1) isolated costs on the user's file
        {
            Coaster c = CascadeProbe_ChainTopo.load();
            CascadeProbe_ChainTopo.rebuild(c);
            int reps = 15;
            double[] tc = new double[reps];
            for (int i = 0; i < reps; i++) {
                long t0 = System.nanoTime();
                TrackCompiler.compile(c, DT);
                tc[i] = (System.nanoTime() - t0) / 1e6;
            }
            System.out.printf(Locale.US, "compile(user file, 3 pieces): min %.1f ms median %.1f ms (%d reps)%n",
                min(tc), median(tc), reps);
            for (int p = 0; p < 2; p++) {
                BridgeSection bs = CascadeProbe_ChainTopo.bridgeOf(c, p);
                double[] tk = new double[reps];
                double d = 0;
                for (int i = 0; i < reps; i++) {
                    long t0 = System.nanoTime();
                    d = c.optimalBridgeLength(bs, true);
                    tk[i] = (System.nanoTime() - t0) / 1e6;
                }
                System.out.printf(Locale.US, "optimalBridgeLength(b@P%d) = %.2f m: min %.1f ms median %.1f ms%n",
                    p, d, min(tk), median(tk));
                double[] to = new double[reps];
                for (int i = 0; i < reps; i++) {
                    long t0 = System.nanoTime();
                    c.optimizeBridge(bs, p + 1, bs.lastOptDist, true);
                    to[i] = (System.nanoTime() - t0) / 1e6;
                }
                System.out.printf(Locale.US, "optimizeBridge(b@P%d, %.2f m, REAL_ONLY): min %.1f ms median %.1f ms%n",
                    p, bs.lastOptDist, min(to), median(to));
            }
        }
        // ---- (1b) compile cost with a SMOOTHED strict follower before b2 (C3s)
        {
            Coaster c = CascadeProbe_ChainTopo.load();
            CascadeProbe_ChainTopo.appendHold(c, 1, 2.0);
            CascadeProbe_ChainTopo.rebuild(c);
            int f = c.makeSeamStrict(1, 1, DT);
            for (double sm : new double[]{ 0, 10 }) {
                c.pieces.get(f).sSmoothLen = sm;
                CascadeProbe_ChainTopo.rebuild(c);
                int reps = 15;
                double[] tc = new double[reps];
                for (int i = 0; i < reps; i++) {
                    long t0 = System.nanoTime();
                    TrackCompiler.compile(c, DT);
                    tc[i] = (System.nanoTime() - t0) / 1e6;
                }
                System.out.printf(Locale.US, "compile(follower P%d before b2, smooth %.0f m): min %.1f ms median %.1f ms%n",
                    f, sm, min(tc), median(tc));
            }
        }

        // ---- (2) chain depth scaling: click on b1 (target P1 pitch +1°)
        for (boolean optLen : new boolean[]{ false, true }) {
            for (int n = 2; n <= 5; n++) {
                Coaster c = chain(n, optLen);
                for (int rep = 0; rep < 3; rep++) {
                    nKnee = 0; tKnee = 0;
                    CascadeProbe_ChainTopo.nCompile = 0; CascadeProbe_ChainTopo.tCompile = 0;
                    CascadeProbe_ChainTopo.nKnee = 0; CascadeProbe_ChainTopo.tKnee = 0;
                    CascadeProbe_ChainTopo.Run r = CascadeProbe_ChainTopo.click(c, 0, 1, 1.0);
                    StringBuilder rm = new StringBuilder();
                    for (String line : r.log) {
                        int i = line.indexOf("re-marched: [");
                        rm.append(rm.length() > 0 ? " → " : "").append(line, i + 13, line.length() - 1);
                    }
                    System.out.printf(Locale.US,
                        "chain N=%d optLen=%b click %d on b1: %.0f ms | fast=%d exact=%d quiesced=%b | re-marches=%d | compiles=%d (%.0f ms) | explicit knee %d× %.0f ms | passes: %s%n",
                        n, optLen, rep + 1, r.ms, r.fast, r.exact, r.quiesced, r.reMarches,
                        CascadeProbe_ChainTopo.nCompile, CascadeProbe_ChainTopo.tCompile / 1e6,
                        CascadeProbe_ChainTopo.nKnee, CascadeProbe_ChainTopo.tKnee / 1e6, rm);
                }
                // and a click on the LAST bridge (no downstream cascade)
                CascadeProbe_ChainTopo.nCompile = 0; CascadeProbe_ChainTopo.tCompile = 0;
                CascadeProbe_ChainTopo.nKnee = 0; CascadeProbe_ChainTopo.tKnee = 0;
                CascadeProbe_ChainTopo.Run r = CascadeProbe_ChainTopo.click(c, n - 1, 1, 1.0);
                System.out.printf(Locale.US,
                    "chain N=%d optLen=%b click on LAST bridge b%d: %.0f ms | fast=%d exact=%d | re-marches=%d | compiles=%d (%.0f ms) | explicit knee %d× %.0f ms%n",
                    n, optLen, n, r.ms, r.fast, r.exact, r.reMarches,
                    CascadeProbe_ChainTopo.nCompile, CascadeProbe_ChainTopo.tCompile / 1e6,
                    CascadeProbe_ChainTopo.nKnee, CascadeProbe_ChainTopo.tKnee / 1e6);
            }
        }
        System.out.println("done");
    }
}
