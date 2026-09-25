import uvd.*;
import java.util.*;

/** v18.6 probe 2: the REAL pipeline — default layout + huge rolling
 *  friction so the train slows to a crawl mid-layout. Scan trueV for
 *  stair-stepping (repeated values / discrete jumps) in the slow zone. */
public class SlowProbe2 {
    public static void main(String[] args) {
        Coaster c = new Coaster();
        c.fricRoll = 0.06;   // heavy: the train crawls on late hills
        c.fricAir = 1.0e-4;
        TrackCompiler.Result res = TrackCompiler.compile(c, 0.01);
        List<FVDIntegrator.Sample> ss = res.samples();
        double[] tv = res.trueV();
        System.out.println("samples=" + ss.size()
            + "  vMin=" + Arrays.stream(tv).min().getAsDouble());
        // slow zone: first stretch under 3 m/s
        int a = -1, b = -1;
        for (int i = 0; i < tv.length; i++) {
            if (tv[i] < 3 && a < 0) a = i;
            if (a >= 0 && tv[i] > 3 && b < 0 && i > a + 10) { b = i; break; }
        }
        if (a < 0) { System.out.println("never slow — raise friction"); return; }
        if (b < 0) b = tv.length - 1;
        System.out.println("slow zone samples " + a + ".." + b
            + "  t=" + ss.get(a).t() + ".." + ss.get(b).t());
        // repeated-value runs + max jump between consecutive samples
        int runs = 0, longest = 0, cur = 0;
        double maxJump = 0;
        for (int i = a + 1; i <= b; i++) {
            if (tv[i] == tv[i - 1]) {
                cur++;
                if (cur == 1) runs++;
                longest = Math.max(longest, cur + 1);
            } else {
                cur = 0;
            }
            maxJump = Math.max(maxJump, Math.abs(tv[i] - tv[i - 1]));
        }
        System.out.printf(Locale.US,
            "equal-value runs=%d  longest=%d  maxStep=%.9f m/s%n",
            runs, longest, maxJump);
        // dump every 20th sample around the minimum
        int iMin = a;
        for (int i = a; i <= b; i++) if (tv[i] < tv[iMin]) iMin = i;
        System.out.println("--- around the minimum (every 5th sample) ---");
        for (int i = Math.max(0, iMin - 50); i <= Math.min(tv.length - 1, iMin + 50); i += 5) {
            System.out.printf(Locale.US, "i=%6d t=%8.3f s=%11.6f v=%.9f%n",
                i, ss.get(i).t(), ss.get(i).s(), tv[i]);
        }
    }
}
