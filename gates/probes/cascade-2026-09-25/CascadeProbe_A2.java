package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY A, cost attribution (2026-09-25): the sweep in
 *  CascadeProbe_A found NO pass-count cascade (max f4/e1) but 0.5-3.7 s
 *  clicks under "Optimize length". This run isolates WHERE a click's
 *  time goes on representative configurations: the standalone knee
 *  (Coaster.optimalBridgeLength) per bridge, a standalone compile, and
 *  per click the optimizeBridge phase vs the loop's compile / re-opt
 *  time. Also the REAL_ONLY + "Affect Subsequent Strict Seams" (carry)
 *  variant, one checkbox away from the file as saved. */
public class CascadeProbe_A2 extends CascadeProbe_A {

    static double kneeMs(Coaster c, int bp, double[] out) {
        BridgeSection bs = bridgeOf(c, bp);
        long t0 = System.nanoTime();
        out[0] = c.optimalBridgeLength(bs, bs.optMode == BridgeSection.OPT_POS_YAW);
        return (System.nanoTime() - t0) / 1e6;
    }

    static double compileMs(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.compile(c, DT);
        return (System.nanoTime() - t0) / 1e6;
    }

    /** A click WITH the D221 carry (REAL_ONLY + affect on): mirrors
     *  FvdDesigner.placementEdit's before/after entry poses. */
    static Click clickCarry(Coaster c, int bp, int axis, double delta, StringBuilder note) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        long t0 = System.nanoTime();
        TrackPose before = t.entryPose();
        String ax;
        if (axis == 1) { t.setDispPitch(t.dispPitch() + delta, true); ax = "pitch"; }
        else if (axis == 2) { t.setDispRoll(t.dispRoll() + delta, true); ax = "roll"; }
        else { t.setDispYaw(t.dispYaw() + delta); ax = "yaw"; }
        TrackPose after = t.entryPose();
        boolean carried = c.carryAfterEdit(bp + 1, before, after,
            lastResult == null ? null : lastResult.spans());
        ax += carried ? "+carry" : "(nocarry)";
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t1 = System.nanoTime();
        if (bs.optLen) {
            double d = c.optimalBridgeLength(bs, py);
            if (d <= 1 + 1e-9) {
                note.append(" [b").append(bp).append(" click knee≤1m → prompt NO, optLen off]");
                bs.optLen = false;
                double optMs = (System.nanoTime() - t1) / 1e6;
                Rebuild rr = rebuild(c);
                return new Click(ax, optMs, rr, (System.nanoTime() - t0) / 1e6);
            }
            c.optimizeBridge(bs, bp + 1, d, py);
        } else {
            c.optimizeBridge(bs, bp + 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, py);
        }
        double optMs = (System.nanoTime() - t1) / 1e6;
        Rebuild rr = rebuild(c);
        return new Click(ax, optMs, rr, (System.nanoTime() - t0) / 1e6);
    }

    static void costCfg(Cfg cfg, boolean carry) throws Exception {
        Applied ap = apply(cfg);
        Coaster c = ap.c();
        if (carry) for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).affectSubsequentSeams = true;
        System.out.println("### " + cfg.name() + (carry ? " AFFECT=on" : "") + ap.note());
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            double[] k = new double[1];
            double k1 = kneeMs(c, bp, k), k2 = kneeMs(c, bp, k);
            System.out.printf(Locale.US,
                "    b%d: lastOptDist=%.2f optLen=%b locks=%b/%b/%b | standalone knee → %.2f m in %.0f / %.0f ms%n",
                bp, bs.lastOptDist, bs.optLen, bs.lockYaw, bs.lockPitch, bs.lockRoll, k[0], k1, k2);
        }
        double cm1 = compileMs(c), cm2 = compileMs(c), cm3 = compileMs(c);
        System.out.printf(Locale.US, "    standalone compile: %.0f / %.0f / %.0f ms (samples %d)%n",
            cm1, cm2, cm3, lastResult.samples().size());
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            int axis = pickAxis(bs);
            for (int kk = 0; kk < 3; kk++) {
                Click ck;
                if (axis < 0) { System.out.println("    b" + bp + ": no editable axis"); break; }
                ck = carry ? clickCarry(c, bp, axis, 1.0, ap.note())
                           : click(c, bp, axis, 1.0, ap.note());
                Rebuild rr = ck.rr();
                double passMs = 0;
                int reopts = 0;
                for (Pass p : rr.passes) { passMs += p.ms(); reopts += p.reoptWhy().size(); }
                System.out.printf(Locale.US,
                    "    b%d click#%d %-13s total %5.0f ms = opt %4.0f + loop %5.0f [compiles %d = %4.0f ms, reconcile+reopt passes %4.0f ms, re-opts in loop %d, %s]%n",
                    bp, kk, ck.axis(), ck.totalMs(), ck.optMs(), rr.ms, rr.compiles, rr.compileMs,
                    passMs, reopts, rr.brief());
                if (rr.total() > SLOW_PASSES || !rr.quiesced) printPasses("b" + bp + " click#" + kk, rr);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        // warm-up
        runCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, true, true),
            new Summary(), false);
        runCfg(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, true, true),
            new Summary(), false);
        System.out.println("=== FAMILY A cost attribution ===");
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, false, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, true, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, true, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, true, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, false, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS, Locks.ALL_FREE, false, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS, Locks.YAW_FREE, true, true), false);
        costCfg(new Cfg(TrackPiece.OrientMode.DESIGN_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, true, true), false);
        System.out.println("=== REAL_ONLY + Affect Subsequent Strict Seams (carry) ===");
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, false, true), true);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, false, true), true);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS, Locks.YAW_FREE, false, true), true);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, true, true), true);
        costCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS, Locks.ALL_FREE, true, true), true);
    }
}
