import coaster.*;
import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/** Root-cause probe for the "Odd bridge behavior" crest files:
 *  sweep bridge lengths on files 1+2 (OPT_POS_YAW, yaw-only unlocked),
 *  tracking the bridge span's UNWRAPPED pitch (pole crossings), the
 *  placement's yaw/pitch/roll deltas, far-seam felt jumps, and whether
 *  the march spine rendered. File 3 (manual septic) reports gap vs
 *  tangent lengths and seam spikes. */
public class CrestProbe {

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

    /** {unwrapped end pitch deg, min pitch, max pitch, crossings, minUy} */
    static double[] pitchTrace(TrackCompiler.Result r, int i0, int i1) {
        List<FVDIntegrator.Sample> s = r.samples();
        double acc = Math.asin(clamp(s.get(i0).T().y()));
        double mn = acc, mx = acc;
        double hx = s.get(i0).T().x(), hz = s.get(i0).T().z();
        double minUy = s.get(i0).U().y();
        int cross = 0;
        for (int i = i0 + 1; i <= i1 && i < s.size(); i++) {
            Vec3 T = s.get(i).T();
            double aPrev = Math.asin(clamp(s.get(i - 1).T().y()));
            double aNew = Math.asin(clamp(T.y()));
            double d;
            double h2 = T.x() * T.x() + T.z() * T.z();
            if (h2 > 1e-18 && hx * T.x() + hz * T.z() < 0) {
                d = (aPrev > 0 ? Math.PI : -Math.PI) - aNew - aPrev;
                cross++;
            } else {
                d = aNew - aPrev;
            }
            if (h2 > 1e-18) { hx = T.x(); hz = T.z(); }
            acc += d;
            mn = Math.min(mn, acc);
            mx = Math.max(mx, acc);
            minUy = Math.min(minUy, s.get(i).U().y());
        }
        return new double[]{ Math.toDegrees(acc), Math.toDegrees(mn),
            Math.toDegrees(mx), cross, minUy };
    }

    static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }

    static boolean marchLands(Coaster c, BridgeSection bs, boolean py)
            throws Exception {
        Method m = Coaster.class.getDeclaredMethod("marchSpine",
            BridgeSection.class, boolean.class);
        m.setAccessible(true);
        return m.invoke(null, bs, py) != null;
    }

    static void sweep(String path, String name) throws Exception {
        System.out.println("=== " + name + " ===");
        Coaster c0 = load(path);
        BridgeSection bs0 = (BridgeSection) c0.pieces.get(0).sections.get(1);
        settle(c0, 0.01);   // capture seams
        System.out.printf(Locale.US,
            "  seams: kA=(%.4f) jA=|%.5f|  kB=(%.4f) jB=|%.5f|  "
            + "gap=%.2f  mode=%d locks(y/p/r)=%b/%b/%b optLen=%b "
            + "lastOpt=%.1f%n",
            bs0.accA.length(), bs0.jerkA.length(),
            bs0.accB.length(), bs0.jerkB.length(),
            bs0.solvedTarget.pos().sub(bs0.solvedEntry.pos()).length(),
            bs0.optMode, bs0.lockYaw, bs0.lockPitch, bs0.lockRoll,
            bs0.optLen, bs0.lastOptDist);
        boolean py = bs0.optMode == BridgeSection.OPT_POS_YAW;
        long t0 = System.nanoTime();
        double knee = c0.optimalBridgeLength(bs0, py);
        System.out.printf(Locale.US, "  KNEE length = %.2f m  (%.1f ms)%n",
            knee, (System.nanoTime() - t0) / 1e6);
        System.out.println(
            "   len | endPitch  min..max  cross minUy | dYaw dPitch dRoll"
            + " | feltB jump | march");
        for (double d : new double[]{ 5, 8, 11, 15, 20, 25, 30, 35, 40,
                                      46, 54, 64, 80, 100, 120 }) {
            Coaster c = load(path);
            BridgeSection bs =
                (BridgeSection) c.pieces.get(0).sections.get(1);
            settle(c, 0.01);
            TrackPiece tp = c.pieces.get(1);
            double y0 = tp.yawDeg, p0 = tp.pitchDeg, r0 = tp.rollDeg;
            c.optimizeBridge(bs, 1, d, py);
            TrackCompiler.Result r = settle(c, 0.01);
            int iA = r.spans().get(1).i0();
            int iB = r.spans().get(2).i0();
            double[] tr = pitchTrace(r, iA, iB);
            double fj = 0;
            if (iB > 0 && iB < r.trueFelt().length) {
                fj = Math.max(
                    Math.abs(r.trueFelt()[iB][0] - r.trueFelt()[iB - 1][0]),
                    Math.abs(r.trueFelt()[iB][1] - r.trueFelt()[iB - 1][1]));
            }
            System.out.printf(Locale.US,
                "  %4.0f | %8.1f  %6.1f..%6.1f  %d  %5.2f |"
                + " %6.1f %6.1f %6.1f | %7.3f g | %s%n",
                d, tr[0], tr[1], tr[2], (int) tr[3], tr[4],
                tp.yawDeg - y0, tp.pitchDeg - p0, tp.rollDeg - r0,
                fj, marchLands(c, bs, py) ? "march" : "SEPTIC");
        }
    }

    public static void main(String[] args) throws Exception {
        String dir = "C:\\Users\\jacks\\OneDrive\\Documents\\AI Fun\\";
        sweep(dir + "Odd bridge behavior 1 [7.24.26].fvd", "FILE 1");
        sweep(dir + "Odd bridge behavior 2 [7.24.26].fvd", "FILE 2");

        System.out.println("=== FILE 3 (manual septic) ===");
        Coaster c = load(dir + "Odd bridge behavior 3 [7.24.26].fvd");
        BridgeSection bs = (BridgeSection) c.pieces.get(0).sections.get(1);
        TrackCompiler.Result r = settle(c, 0.01);
        System.out.printf(Locale.US,
            "  gap=%.2f lenA=%.1f lenB=%.1f mode=%d optLen=%b lastOpt=%.1f"
            + " march=%b%n",
            bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length(),
            bs.lenA, bs.lenB, bs.optMode, bs.optLen, bs.lastOptDist,
            marchLands(c, bs, bs.optMode == BridgeSection.OPT_POS_YAW));
        int iA = r.spans().get(1).i0(), iB = r.spans().get(2).i0();
        // peak |rate| in thirds of the bridge (spiky-ends signature)
        double[] peak = new double[3];
        for (int i = Math.max(1, iA); i < iB && i < r.trueRates().length;
                i++) {
            int third = Math.min(2, (int) (3.0 * (i - iA) / (iB - iA)));
            for (int col = 0; col < 3; col++) {
                peak[third] = Math.max(peak[third],
                    Math.abs(r.trueRates()[i][col]));
            }
        }
        System.out.printf(Locale.US,
            "  peak |rate| by thirds: %.1f / %.1f / %.1f deg/s%n",
            peak[0], peak[1], peak[2]);
    }
}
