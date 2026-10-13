package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY A, knee-at-rest check (2026-09-25): under
 *  "Optimize length" the signature's length slot is 0, so a knee that
 *  differs from the rested lastOptDist never triggers a re-opt by
 *  itself (bounded by design, D249 review). This prints, after the
 *  configuration is applied and after each click, each bridge's
 *  requested length vs the knee recomputed off the rested seams and
 *  the target's placement — to see whether the length WALKS between
 *  clicks (a placement jump per click) or merely sits off the knee. */
public class CascadeProbe_A3 extends CascadeProbe_A {

    static void state(Coaster c, String label) {
        StringBuilder sb = new StringBuilder("    " + label + ":");
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            TrackPiece t = c.pieces.get(bp + 1);
            double knee = c.optimalBridgeLength(bs, bs.optMode == BridgeSection.OPT_POS_YAW);
            sb.append(String.format(Locale.US,
                " | b%d req=%.2f knee@rest=%.2f tgt(x %.3f y %.3f z %.3f yaw %.3f pitch %.3f roll %.3f dp %.3f dr %.3f)",
                bp, bs.lastOptDist, knee, t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
                t.designPitchDeg, t.designRollDeg));
        }
        System.out.println(sb);
    }

    static void run(Cfg cfg) throws Exception {
        Applied ap = apply(cfg);
        Coaster c = ap.c();
        System.out.println("### " + cfg.name() + ap.note());
        state(c, "after apply");
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            int axis = pickAxis(bs);
            if (axis < 0) continue;
            for (int k = 0; k < 3; k++) {
                Click ck = click(c, bp, axis, 1.0, ap.note());
                state(c, "b" + bp + " click#" + k + " " + ck.brief());
            }
        }
        // three idle rebuilds at rest: must be no-ops (determinism (b))
        for (int i = 0; i < 3; i++) {
            Rebuild rr = rebuild(c);
            System.out.println("    idle rebuild #" + i + ": " + rr.brief() + " reopts="
                + rr.passes.stream().mapToInt(p -> p.reoptWhy().size()).sum());
        }
        state(c, "after idle");
    }

    public static void main(String[] a) throws Exception {
        runCfg(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, true, true),
            new Summary(), false);   // warm-up
        run(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW, Locks.PITCH_FREE, true, true));
        run(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW, Locks.YAW_FREE, true, true));
        run(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW, Locks.ROLL_FREE, true, true));
    }
}
