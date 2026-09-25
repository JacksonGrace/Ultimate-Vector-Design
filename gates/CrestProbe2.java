package uvd;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/** Round-2 probe (user report #4): banked crest bridges flip from
 *  clean to garbage at ~45-51 m. For each length: optimize, then
 *  re-run the march and measure how far its END misses the target
 *  ORIENTATION (tAng — the marchSpine render gate) and the target
 *  BANK (twist starvation vs divergence), plus the usual shape
 *  symptoms. Scenario B = file 4 as saved (entry +19°, target −22°);
 *  scenario A = same with the target's roll delta zeroed. */
public class CrestProbe2 {

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

    static double wrapDeg(double a) {
        while (a > 180) a -= 360;
        while (a < -180) a += 360;
        return a;
    }

    /** march end {pos,T,U} via the private marchEnd. */
    static Vec3[] endState(BridgeSection bs, double d, boolean[] align)
            throws Exception {
        Method m = Coaster.class.getDeclaredMethod("marchEnd",
            BridgeSection.class, double.class, boolean[].class,
            List.class, List.class);
        m.setAccessible(true);
        return (Vec3[]) m.invoke(null, bs, d, align, null, null);
    }

    /** {min pitch, max pitch, minUy} over the bridge span. */
    static double[] trace(TrackCompiler.Result r, int i0, int i1) {
        double mn = 90, mx = -90, minUy = 1;
        for (int i = i0; i <= i1 && i < r.samples().size(); i++) {
            double p = Math.toDegrees(
                Math.asin(clamp(r.samples().get(i).T().y())));
            mn = Math.min(mn, p);
            mx = Math.max(mx, p);
            minUy = Math.min(minUy, r.samples().get(i).U().y());
        }
        return new double[]{ mn, mx, minUy };
    }

    static void sweep(String path, String name, boolean zeroTargetRoll)
            throws Exception {
        System.out.println("=== " + name + " ===");
        {
            Coaster c0 = load(path);
            if (zeroTargetRoll) c0.pieces.get(1).rollDeg = 0;
            settle(c0, 0.01);
            BridgeSection b0 =
                (BridgeSection) c0.pieces.get(0).sections.get(1);
            System.out.printf(Locale.US,
                "  |kA|=%.4f |jA|=%.5f rollA=%.4f  |kB|=%.4f rollB=%.4f"
                + "  gap=%.2f  KNEE=%.2f m%n",
                b0.accA.length(), b0.jerkA.length(), b0.rollA,
                b0.accB.length(), b0.rollB,
                b0.solvedTarget.pos().sub(b0.solvedEntry.pos()).length(),
                c0.optimalBridgeLength(b0, true));
        }
        System.out.println("   len | tAng(deg) bankMiss | endP min..max"
            + "  minUy | dYaw | render");
        for (double d : new double[]{ 20, 30, 35, 40, 42, 44, 46, 48, 50,
                                      52, 54, 58, 64, 72, 80, 90, 100 }) {
            Coaster c = load(path);
            if (zeroTargetRoll) c.pieces.get(1).rollDeg = 0;
            settle(c, 0.01);
            BridgeSection bs =
                (BridgeSection) c.pieces.get(0).sections.get(1);
            TrackPiece tp = c.pieces.get(1);
            double y0 = tp.yawDeg;
            c.optimizeBridge(bs, 1, d, true);
            TrackCompiler.Result r = settle(c, 0.01);
            boolean[] align = { !bs.lockYaw, !bs.lockPitch, !bs.lockRoll };
            Vec3[] st = endState(bs, d, align);
            Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
            double tAng = Math.toDegrees(Math.acos(clamp(
                st[1].dot(tB))));
            double bM = FVDIntegrator.bankOfTU(st[1], st[2]);
            double bB = FVDIntegrator.bankOfTU(tB, uB);
            double bankMiss = Double.isNaN(bM) || Double.isNaN(bB) ? -999
                : wrapDeg(Math.toDegrees(bB - bM));
            int iA = r.spans().get(1).i0();
            int iB = r.spans().get(2).i0();
            double[] tr = trace(r, iA, iB);
            double endP = Math.toDegrees(Math.asin(clamp(st[1].y())));
            System.out.printf(Locale.US,
                "  %4.0f | %8.3f %8.2f | %6.1f %6.1f..%6.1f  %5.2f |"
                + " %6.1f | %s%n",
                d, tAng, bankMiss, endP, tr[0], tr[1], tr[2],
                wrapDeg(tp.yawDeg - y0),
                Coaster.marchSpine(bs, true) != null ? "march" : "SEPTIC");
        }
    }

    public static void main(String[] args) throws Exception {
        String f4 = "C:\\Users\\jacks\\OneDrive\\Documents\\AI Fun\\"
            + "Odd bridge behavior 4 [7.24.26].fvd";
        sweep(f4, "SCENARIO B: file 4 (entry +19, target -22)", false);
        sweep(f4, "SCENARIO A: file 4 with target roll 0 (entry +19)",
            true);
    }
}
