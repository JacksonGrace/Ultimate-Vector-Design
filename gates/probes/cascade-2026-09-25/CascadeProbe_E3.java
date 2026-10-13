package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY E, part 3 (2026-09-25): per-call costs on
 *  the slow configurations. For each: time captureTargetHead ×5 and
 *  alignPieceForBridge (= one marchEnd) ×5 at the applied length, run
 *  the settle replica recording the best round's PRE placement, then
 *  the real optimizeBridge and check it landed on that placement
 *  (bitwise) — the replica's faithfulness — and its wall time. Under
 *  optLen also time optimalBridgeLength alone. */
public class CascadeProbe_E3 {
    static final double DT = Coaster.INTEGRATE_DT;

    public static void main(String[] a) {
        CascadeProbe_E.Cfg[] cfgs = {
            new CascadeProbe_E.Cfg("D", 2, 30, 10, false, 0, 0, false, true, false),
            new CascadeProbe_E.Cfg("D", 2, 30, 10, true, 0, 0, false, true, false),
            new CascadeProbe_E.Cfg("D", 6, -30, 30, false, 0, 0, false, true, false),
            new CascadeProbe_E.Cfg("D", 4, 30, 20, false, 0, 0, false, true, false),
            new CascadeProbe_E.Cfg("C", 4, 30, 20, false, 0, 0, false, false, true),
            new CascadeProbe_E.Cfg("A", 8, -30, 40, false, 0, 0, false, false, false),
            new CascadeProbe_E.Cfg("A", 2, 25, 10, false, 0, 0, false, false, false),
        };
        // warm-up
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        for (int w = 0; w < 3; w++) for (CascadeProbe_E.Cfg k : cfgs) one(k);
        System.setOut(out);
        for (CascadeProbe_E.Cfg k : cfgs) one(k);
    }

    static void one(CascadeProbe_E.Cfg k) {
        Coaster c = CascadeProbe_E.build(k);
        BridgeSection bs = CascadeProbe_E.bridgeOf(c);
        TrackPiece t = c.pieces.get(1);
        double chord = new Vec3(t.x, t.y, t.z).sub(bs.solvedEntry.pos()).length();
        double dist = k.optLen() ? c.optimalBridgeLength(bs, true) : chord * 1.2;
        double[] start = CascadeProbe_E.placement(t);
        // captureTargetHead ×5
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        double capMs = (System.nanoTime() - t0) / 5e6;
        // alignPieceForBridge ×5 at dist (each restores the placement + recaptures first)
        double[] alignMs = new double[5];
        for (int i = 0; i < 5; i++) {
            CascadeProbe_E.restore(t, start);
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            long t1 = System.nanoTime();
            c.alignPieceForBridge(bs, 1, dist, true);
            alignMs[i] = (System.nanoTime() - t1) / 1e6;
        }
        CascadeProbe_E.restore(t, start);
        TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        // replica with the best round's PRE placement recorded
        c.alignPieceForBridge(bs, 1, dist, true);
        boolean[] align = { !bs.lockYaw, !bs.lockPitch, !bs.lockRoll };
        double best = Double.POSITIVE_INFINITY;
        double[] bestPre = null;
        int rounds = 0;
        boolean conv = false;
        double[] roundMs = new double[24];
        for (int round = 0; round < 24; round++) {
            long t1 = System.nanoTime();
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            double[] pre = CascadeProbe_E.placement(t);
            c.alignPieceForBridge(bs, 1, dist, true);
            roundMs[round] = (System.nanoTime() - t1) / 1e6;
            double delta = Math.max(Math.abs(TrackPiece.wrap180(t.yawDeg - pre[3])),
                Math.max(Math.abs(t.pitchDeg - pre[4]),
                    Math.abs(TrackPiece.wrap180(t.rollDeg - pre[5]))));
            rounds++;
            if (delta < best) { best = delta; bestPre = pre; }
            if (delta < 1e-9) { conv = true; break; }
            if (align[1] && Math.abs(t.pitchDeg) >= 85) break;
        }
        double rMin = Double.MAX_VALUE, rMax = 0, rSum = 0;
        for (int i = 0; i < rounds; i++) { rMin = Math.min(rMin, roundMs[i]); rMax = Math.max(rMax, roundMs[i]); rSum += roundMs[i]; }
        CascadeProbe_E.restore(t, start);
        TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        // the real thing
        long t2 = System.nanoTime();
        c.optimizeBridge(bs, 1, dist, true);
        double optMs = (System.nanoTime() - t2) / 1e6;
        double[] real = CascadeProbe_E.placement(t);
        boolean faithful = !conv && bestPre != null
            && Arrays.equals(CascadeProbe_E.bits(bestPre), CascadeProbe_E.bits(real));
        String kneeS = "";
        if (k.optLen()) {
            long t3 = System.nanoTime();
            double kn = c.optimalBridgeLength(bs, true);
            kneeS = String.format(Locale.US, " | optimalBridgeLength alone %.0f ms (→ %.3f)", (System.nanoTime() - t3) / 1e6, kn);
        }
        System.out.printf(Locale.US,
            "[%s] dist %.3f: captureTargetHead %.2f ms | alignPieceForBridge (one marchEnd) %s ms | replica %d rounds conv=%b bestδ %.2e, per round min/avg/max %.1f/%.1f/%.1f ms | real optimizeBridge %.0f ms applied %.3f, lands on replica best pre-placement: %b%s%n",
            k.label(), dist, capMs, Arrays.toString(Arrays.stream(alignMs).mapToObj(v -> String.format(Locale.US, "%.1f", v)).toArray()),
            rounds, conv, best, rMin, rSum / rounds, rMax, optMs, bs.lastOptDist,
            faithful || (conv ? true : false), kneeS);
    }
}
