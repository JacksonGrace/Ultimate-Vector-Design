import uvd.*;
import java.util.*;

/** v18.6 probe: is the near-stall speed data itself stair-stepped, or
 *  smooth (=> display-side)? Straight level line, Gv=1, friction only:
 *  v² = v0² − 2μg·s decays smoothly through the whole range. */
public class SlowProbe {
    public static void main(String[] args) {
        List<FVDIntegrator.Keyframe> kfs = new ArrayList<>();
        for (int i = 0; i <= 4000; i++) {
            kfs.add(new FVDIntegrator.Keyframe(i * 0.01, 1.0, 0.0, 0.0));
        }
        FVDIntegrator.Options o = new FVDIntegrator.Options();
        o.v0 = 2.0;
        o.pos0 = new Vec3(0, 21, 0);
        o.fric = new FVDIntegrator.Friction(0.03, 1.0e-4);
        List<FVDIntegrator.Sample> ss = FVDIntegrator.integrateTrack(kfs, o);
        System.out.println("samples=" + ss.size());
        // print the slow zone + look for repeated consecutive v values
        double prev = Double.NaN;
        int rep = 0, printed = 0;
        int flat01 = 0;
        List<String> runs = new ArrayList<>();
        for (FVDIntegrator.Sample s : ss) {
            double v = s.v();
            if (v <= 0.100000001) flat01++;
            if (v < 1.0 && v > 0.100000001) {
                if (v == prev) {
                    rep++;
                } else {
                    if (rep > 0) runs.add(String.format(Locale.US,
                        "v=%.12f held %d samples", prev, rep + 1));
                    rep = 0;
                }
                prev = v;
                if (printed < 40 && s.t() % 0.1 < 0.0100001) {
                    System.out.printf(Locale.US,
                        "t=%7.3f  s=%12.9f  v=%.12f%n", s.t(), s.s(), v);
                    printed++;
                }
            }
        }
        if (rep > 0) runs.add(String.format(Locale.US,
            "v=%.12f held %d samples", prev, rep + 1));
        System.out.println("clamped-at-0.1 samples: " + flat01);
        System.out.println("consecutive-equal runs in (0.1,1): " + runs.size());
        for (int i = 0; i < Math.min(10, runs.size()); i++) {
            System.out.println("  " + runs.get(i));
        }
        // fine dump right at the transition into the clamp
        System.out.println("--- last 25 samples above the clamp + first 5 at it ---");
        for (int i = 1; i < ss.size(); i++) {
            if (ss.get(i).v() <= 0.100000001 && ss.get(i - 1).v() > 0.100000001) {
                for (int j = Math.max(0, i - 25); j < Math.min(ss.size(), i + 5); j++) {
                    System.out.printf(Locale.US, "t=%7.3f  s=%14.11f  v=%.12f%n",
                        ss.get(j).t(), ss.get(j).s(), ss.get(j).v());
                }
                break;
            }
        }
    }
}
