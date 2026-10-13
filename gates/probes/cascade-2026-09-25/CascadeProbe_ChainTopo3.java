package uvd;

import java.util.*;

/** FAMILY C, part 3: does the click's stamp being one edit stale (the
 *  spinner write happens AFTER the last compile captured the target
 *  head, and optimizeBridge stamps from that capture) cost the extra
 *  pass? Variant click: re-capture the head (TrackCompiler.
 *  captureTargetHead, what the next compile will do) BEFORE marching. */
public class CascadeProbe_ChainTopo3 {
    static final double DT = Coaster.INTEGRATE_DT;

    static CascadeProbe_ChainTopo.Run clickFresh(Coaster c, int bp, int axis, double delta) {
        BridgeSection bs = CascadeProbe_ChainTopo.bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        TrackCompiler.captureTargetHead(bs, t, DT, c.fric());   // the variant
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        c.optimizeBridge(bs, bp + 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, py);
        CascadeProbe_ChainTopo.Run r = CascadeProbe_ChainTopo.rebuild(c);
        r.ms = (System.nanoTime() - t0) / 1e6;
        return r;
    }

    public static void main(String[] a) throws Exception {
        for (int n : new int[]{ 2, 3, 5 }) {
            Coaster c = CascadeProbe_ChainTopo2.chain(n, false);
            for (int rep = 0; rep < 2; rep++) {
                CascadeProbe_ChainTopo.Run r = CascadeProbe_ChainTopo.click(c, 0, 1, 1.0);
                System.out.printf(Locale.US, "N=%d harness click : fast=%d exact=%d re-marches=%d ms=%.0f | pass0 b1: %s%n",
                    n, r.fast, r.exact, r.reMarches, r.ms, r.log.get(0).substring(0, Math.min(120, r.log.get(0).length())));
                r = clickFresh(c, 0, 1, 1.0);
                System.out.printf(Locale.US, "N=%d re-captured  : fast=%d exact=%d re-marches=%d ms=%.0f | pass0 b1: %s%n",
                    n, r.fast, r.exact, r.reMarches, r.ms, r.log.get(0).substring(0, Math.min(120, r.log.get(0).length())));
            }
        }
    }
}
