package coaster;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/** Round-15: his Speed Stairstepping file — dump samples around the
 *  slow zone and locate his exact readout values (1.992, 1.666,
 *  1.261, 0.637). Which array carries them, and at what spacing? */
public class StairFileProbe {

    public static void main(String[] a) throws Exception {
        Coaster c;
        try (BufferedReader in = new BufferedReader(new FileReader(a[0]))) {
            c = DesignIO.load(in).coaster();
        }
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int pass = 0; pass < 3; pass++) {
            if (!c.reconcileChainedSpeeds(r)) break;
            r = TrackCompiler.compile(c, 0.01);
        }
        int n = r.samples().size();
        System.out.printf(Locale.US, "samples=%d rideTime=%.2f%n",
            n, r.samples().get(n - 1).t());
        // find the first stall (trueV == 0) and print the approach
        int iz = -1;
        for (int i = 0; i < n; i++) {
            if (r.trueV()[i] <= 1e-9) { iz = i; break; }
        }
        System.out.println("first zero trueV at i=" + iz);
        if (iz < 0) iz = n - 1;
        int from = Math.max(0, iz - 40);
        for (int i = from; i <= Math.min(n - 1, iz + 3); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double y0 = r.samples().get(0).pos().y();
            double vFree = Math.sqrt(Math.max(0, 26 * 26
                + 2 * 9.80665 * (y0 - sm.pos().y())));
            System.out.printf(Locale.US,
                "i=%d t=%.3f s=%.4f y=%.3f  vDesign=%.4f vFree=%.4f  trueV=%.4f  ds=%.4f%n",
                i, sm.t(), sm.s(), sm.pos().y(), sm.v(), vFree, r.trueV()[i],
                i > 0 ? sm.s() - r.samples().get(i - 1).s() : 0);
        }
    }
}
