package coaster;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/** Step through the v17.15 rescue externally for file 4 at chosen
 *  lengths: stage-1 pitch converge, then the twist ramp — printing
 *  io[], the measured bank gap, crossings and end state per step. */
public class RampTrace {

    static Method mPitch, mBank, mMarch;

    static Coaster load(String path) throws Exception {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            return DesignIO.load(in).coaster();
        }
    }

    static TrackCompiler.Result settle(Coaster c, double dt) {
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        for (int i = 0; i < 6; i++) {
            boolean a = c.autoReoptimizeBridges(r);
            boolean b = c.reconcileChainedSpeeds(r);
            if (!a && !b) break;
            r = TrackCompiler.compile(c, dt);
        }
        return r;
    }

    static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }

    public static void main(String[] args) throws Exception {
        mPitch = Coaster.class.getDeclaredMethod("pitchConverge",
            Vec3.class, Vec3.class, Vec3.class, double.class,
            double[].class, double[].class, double[].class,
            boolean[].class, Vec3.class, double.class, int.class,
            double[].class);
        mPitch.setAccessible(true);
        mBank = Coaster.class.getDeclaredMethod("bankGap", Vec3.class,
            Vec3.class, Vec3.class, Vec3.class);
        mBank.setAccessible(true);
        Method mSeam = Coaster.class.getDeclaredMethod("seamState",
            Vec3.class, Vec3.class, Vec3.class, Vec3.class,
            double.class, double.class, double.class);
        mSeam.setAccessible(true);
        Method mDead = Coaster.class.getDeclaredMethod("deadbandSlopes",
            double[].class, double[].class, double.class);
        mDead.setAccessible(true);

        String f4 = "C:\\Users\\jacks\\OneDrive\\Documents\\AI Fun\\"
            + "Odd bridge behavior 4 [7.24.26].fvd";
        for (double d : new double[]{ 64, 90 }) {
            Coaster c = load(f4);
            settle(c, 0.01);
            BridgeSection bs =
                (BridgeSection) c.pieces.get(0).sections.get(1);
            Vec3 pA = bs.solvedEntry.pos();
            Vec3 tA = bs.solvedEntry.T(), uA = bs.solvedEntry.U();
            Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
            double[] sa = (double[]) mSeam.invoke(null, bs.accA, bs.jerkA,
                uA, uA.cross(tA), bs.rollA, bs.rollAccA, bs.vDesign);
            double[] sb = (double[]) mSeam.invoke(null, bs.accB, bs.jerkB,
                uB, uB.cross(tB),
                bs.vB > 0 ? bs.rollB : 0, bs.vB > 0 ? bs.rollAccB : 0,
                bs.vB > 0 ? bs.vB : bs.vDesign);
            mDead.invoke(null, sa, sb, d);
            boolean[] align = { true, false, false };
            double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
            double[] meta = new double[2];
            double[] io = { 0, 0, 0 };
            System.out.printf(Locale.US, "=== d=%.0f ===%n", d);
            Vec3[] st = (Vec3[]) mPitch.invoke(null, pA, tA, uA, d, sa,
                sb, io, align, tB, hb2, 8, meta);
            System.out.printf(Locale.US,
                "  stage1: resP=%8.2f deg  cross=%d  endP=%6.2f%n",
                Math.toDegrees(io[0]), (int) meta[1],
                Math.toDegrees(Math.asin(clamp(st[1].y()))));
            double kScale = Math.max(
                Math.max(Math.abs(sa[0]), Math.abs(sb[0])),
                Math.max(Math.abs(sa[1]), Math.abs(sb[1])));
            double maxStep = 0.8 / Math.max(1, kScale * d);
            double lastGood = 0;
            for (int gs = 0; gs < 16; gs++) {
                if (meta[1] != 0) {
                    System.out.printf(Locale.US,
                        "  gs%02d CROSSED (cross=%d) -> revert to %.2f,"
                        + " halve step to %.3f%n",
                        gs, (int) meta[1], Math.toDegrees(lastGood),
                        Math.toDegrees(maxStep * 0.5));
                    io[2] = lastGood;
                    maxStep *= 0.5;
                    st = (Vec3[]) mPitch.invoke(null, pA, tA, uA, d, sa,
                        sb, io, align, tB, hb2, 8, meta);
                    continue;
                }
                double rem = (double) mBank.invoke(null, st[1], st[2],
                    tB, uB);
                System.out.printf(Locale.US,
                    "  gs%02d io2=%8.2f rem=%8.2f resP=%8.2f endP=%6.2f"
                    + " cross=%d%n",
                    gs, Math.toDegrees(io[2]), Math.toDegrees(rem),
                    Math.toDegrees(io[0]),
                    Math.toDegrees(Math.asin(clamp(st[1].y()))),
                    (int) meta[1]);
                if (Math.abs(rem) <= 1e-4) break;
                lastGood = io[2];
                io[2] += Math.max(-maxStep, Math.min(maxStep, rem));
                st = (Vec3[]) mPitch.invoke(null, pA, tA, uA, d, sa, sb,
                    io, align, tB, hb2, 8, meta);
            }
        }
    }
}
