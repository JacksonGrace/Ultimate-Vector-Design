package uvd;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/** Verifies the v18.9 derived-display fix on the user's edited file:
 *  the force strip's derived curves, expressed in the ACTIVE geo
 *  section's world/°m semantics, must join the geo's authored first
 *  nodes at the seam. */
public class DerProbe {

    public static void main(String[] args) throws Exception {
        Coaster c;
        try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
            c = DesignIO.load(in).coaster();
        }
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int pass = 0; pass < 3; pass++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, 0.01);
        }
        LaneSection geo = (LaneSection) c.pieces.get(0).sections.get(1);
        TrackCompiler.Span sp0 = r.spans().get(0);
        List<FVDIntegrator.Sample> sm = r.samples();
        int ip = Math.min(sp0.i1(), sm.size() - 1);
        System.out.printf(Locale.US,
            "geo vDes=%.4f authored n0: pitch=%.4f yaw=%.4f roll=%.4f (deg/m)%n",
            geo.vDesign, geo.lanes.get(0).get(0).val,
            geo.lanes.get(1).get(0).val, geo.lanes.get(2).get(0).val);
        // derived (design) world rates on the FORCE side approaching
        // the seam, in deg/m — the new derivedAt recipe
        for (int i = ip - 6; i <= ip; i++) {
            double[] wr = Coaster.worldRates(sm.get(i),
                r.rates()[i][1], r.rates()[i][2], r.rates()[i][3]);
            double v = Math.max(0.5, sm.get(i).v());
            System.out.printf(Locale.US,
                "  i=%d t=%.2f  elev'=%.4f head'=%.4f bank'=%.4f (deg/m)"
                + "  [body: %.2f %.2f %.2f deg/s]%n",
                i, sm.get(i).t(), wr[0] / v, wr[1] / v, wr[2] / v,
                r.rates()[i][1], r.rates()[i][2], r.rates()[i][3]);
        }
    }
}
