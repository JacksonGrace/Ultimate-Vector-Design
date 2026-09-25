package uvd;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Headless repro of the partial-convert bridge-shape bug: optimize a
 * bridge (march spine active), partial-convert one side, and check
 * whether the surviving bridge still renders the march or silently
 * falls back to the septic (marchSpine's 2 cm / 0.3 deg landing gate).
 * Then emulate the user's mode ping-pong "fix" and measure what moves.
 */
public class ScratchPartialConvert {

    static final double DT = 0.01;

    public static void main(String[] args) throws Exception {
        run(false, 15, 25, "RIGHT convert @50%, pitched+turning entry (march has a pitch hump)");
        run(true, 15, 25, "LEFT convert @50%, pitched+turning entry (march has a pitch hump)");
        run(false, 0, 25, "RIGHT convert @50%, level turning entry (no pitch hump)");
        run(false, 0, 0, "RIGHT convert @50%, dead straight (no hump at all)");
    }

    static void run(boolean left, double pitchRate, double yawRate, String label)
            throws Exception {
        System.out.println("==== " + label + " ====");
        Coaster c = new Coaster();
        TrackPiece p0 = new TrackPiece(0, 21, 0, 0);
        p0.sections.add(new GeoSection(LaneSection.holdLanes(pitchRate, yawRate, 0), 26));
        c.pieces.add(p0);
        TrackPiece p1 = new TrackPiece(60, 15, 20, 0);
        p1.sections.add(new GeoSection(LaneSection.holdLanes(0, 0, 0), 26));
        c.pieces.add(p1);
        BridgeSection bs = BridgeSection.solve(
            new TrackPose(new Vec3(0, 0, 0), Quat.IDENTITY),
            new TrackPose(new Vec3(1, 0, 0), Quat.IDENTITY), 26);
        p0.sections.add(bs);

        TrackCompiler.compile(c, DT);          // live-capture the seams
        c.optimizeBridge(bs, 1, 30, true);     // POS_YAW at 30 m
        TrackCompiler.Result before = TrackCompiler.compile(c, DT);
        report("after optimize ", bs, true);

        TrackCompiler.Span bSpan = before.spans().get(1);   // p0 = [geo, bridge]
        double[][] beforeR = slice(before, bSpan);
        double origLen = bSpan.sLen();

        boolean ok = c.partialConvertBridge(0, 1, 0.5, left, true, DT);
        if (!ok) { System.out.println("  convert REFUSED"); return; }

        BridgeSection bs2 = left ? (BridgeSection) p0.sections.get(2) : bs;
        int spanIdx2 = left ? 2 : 1;
        TrackPiece nxt = c.pieces.get(1);
        double[] preNudge = { nxt.x, nxt.y, nxt.z };
        // the app's rebuild loop: compile -> auto pass -> settle compile
        TrackCompiler.Result after = TrackCompiler.compile(c, DT);
        if (c.autoReoptimizeBridges(after)) {
            after = TrackCompiler.compile(c, DT);
        }
        System.out.printf("  auto pass nudged the next piece %.4f m%n",
            new Vec3(nxt.x - preNudge[0], nxt.y - preNudge[1],
                nxt.z - preNudge[2]).length());

        boolean orient = bs2.optMode == BridgeSection.OPT_POS_YAW;
        report("after convert  ", bs2, orient);

        // fraction of the ORIGINAL bridge arc the surviving side covers
        double fStart = left ? 1 - bs2.lastOptDist / origLen : 0;
        double fEnd = left ? 1 : bs2.lastOptDist / origLen;
        TrackCompiler.Span aSpan = after.spans().get(spanIdx2);
        double[] d0 = maxDelta(beforeR, slice(after, aSpan), fStart, fEnd);
        System.out.printf("  graph delta vs original sub-arc: pitch %.2f  yaw %.2f  roll %.2f deg/s%n",
            d0[0], d0[1], d0[2]);

        // ---- the user's "fix": switch to Optimize position ----
        TrackPiece tgt = c.pieces.get(1);
        double[] p = { tgt.x, tgt.y, tgt.z };
        bs2.optMode = BridgeSection.OPT_POS;
        c.optimizeBridge(bs2, 1, bs2.lastOptDist, false);
        TrackCompiler.Result fixed = TrackCompiler.compile(c, DT);
        report("switch to POS  ", bs2, false);
        System.out.printf("  next piece moved %.4f m by the switch%n",
            new Vec3(tgt.x - p[0], tgt.y - p[1], tgt.z - p[2]).length());
        double[] d1 = maxDelta(beforeR, slice(fixed, fixed.spans().get(spanIdx2)),
            fStart, fEnd);
        System.out.printf("  graph delta vs original sub-arc: pitch %.2f  yaw %.2f  roll %.2f deg/s%n",
            d1[0], d1[1], d1[2]);

        // ---- and back to Optimize position and orientation ----
        p = new double[]{ tgt.x, tgt.y, tgt.z };
        bs2.optMode = BridgeSection.OPT_POS_YAW;
        c.optimizeBridge(bs2, 1, bs2.lastOptDist, true);
        TrackCompiler.Result back = TrackCompiler.compile(c, DT);
        report("back to POS_YAW", bs2, true);
        System.out.printf("  next piece moved %.4f m by the switch%n",
            new Vec3(tgt.x - p[0], tgt.y - p[1], tgt.z - p[2]).length());
        double[] d2 = maxDelta(beforeR, slice(back, back.spans().get(spanIdx2)),
            fStart, fEnd);
        System.out.printf("  graph delta vs original sub-arc: pitch %.2f  yaw %.2f  roll %.2f deg/s%n%n",
            d2[0], d2[1], d2[2]);
    }

    /** spine kind + landing miss of a fresh march at lastOptDist. */
    static void report(String tag, BridgeSection bs, boolean orient) throws Exception {
        boolean march = Coaster.marchSpine(bs, orient) != null;
        double[] m = miss(bs, orient);
        System.out.printf("  %s: %s  lastOptDist=%.2f  miss=%.4f m  tAng=%.4f deg  (gate 0.02 m / 0.3 deg)%n",
            tag, march ? "MARCH " : "SEPTIC", bs.lastOptDist, m[0], m[1]);
    }

    static double[] miss(BridgeSection bs, boolean orient) throws Exception {
        if (bs.lastOptDist <= 0) return new double[]{ -1, -1 };
        Method fl = Coaster.class.getDeclaredMethod("alignFlags",
            BridgeSection.class, boolean.class);
        fl.setAccessible(true);
        boolean[] flags = (boolean[]) fl.invoke(null, bs, orient);
        Method me = Coaster.class.getDeclaredMethod("marchEnd",
            BridgeSection.class, double.class, boolean[].class,
            List.class, List.class);
        me.setAccessible(true);
        Vec3[] st = (Vec3[]) me.invoke(null, bs, bs.lastOptDist, flags, null, null);
        double miss = st[0].sub(bs.solvedTarget.pos()).length();
        double tAng = Math.toDegrees(Math.acos(Math.max(-1,
            Math.min(1, st[1].dot(bs.solvedTarget.T())))));
        return new double[]{ miss, tAng };
    }

    /** rows {frac-of-span-arc, pitch, yaw, roll} for a span. */
    static double[][] slice(TrackCompiler.Result r, TrackCompiler.Span sp) {
        double s0 = r.samples().get(sp.i0()).s();
        double sL = Math.max(1e-9, r.samples().get(sp.i1()).s() - s0);
        double[][] out = new double[sp.i1() - sp.i0() + 1][4];
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            out[i - sp.i0()][0] = (r.samples().get(i).s() - s0) / sL;
            for (int k = 1; k < 4; k++) out[i - sp.i0()][k] = r.rates()[i][k];
        }
        return out;
    }

    /** max |after - before| per rate channel, matching the after span's
     *  fractional arc onto [fStart..fEnd] of the original; edges
     *  trimmed (seam smear). */
    static double[] maxDelta(double[][] before, double[][] after,
                             double fStart, double fEnd) {
        double[] max = new double[3];
        for (double[] row : after) {
            if (row[0] < 0.04 || row[0] > 0.96) continue;
            double f = fStart + row[0] * (fEnd - fStart);
            for (int k = 1; k < 4; k++) {
                double b = interp(before, f, k);
                max[k - 1] = Math.max(max[k - 1], Math.abs(row[k] - b));
            }
        }
        return max;
    }

    static double interp(double[][] rows, double f, int k) {
        if (f <= rows[0][0]) return rows[0][k];
        for (int i = 1; i < rows.length; i++) {
            if (rows[i][0] >= f) {
                double f0 = rows[i - 1][0], f1 = rows[i][0];
                double u = f1 - f0 < 1e-12 ? 0 : (f - f0) / (f1 - f0);
                return rows[i - 1][k] + u * (rows[i][k] - rows[i - 1][k]);
            }
        }
        return rows[rows.length - 1][k];
    }
}
