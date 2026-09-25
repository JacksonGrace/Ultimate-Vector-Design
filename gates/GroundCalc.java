import uvd.BezierBaker;
import uvd.FVDIntegrator;
import uvd.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Compute the shipped default layout's lowest rail Y (heartOffset 1.0,
 *  startY 0) — the ground grid used to sit at floor(lowest-1); with the
 *  ground fixed at y=0 the default startY must become -floor(lowest-1). */
public class GroundCalc {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));

        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        opts.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Sample> samples =
            FVDIntegrator.integrateTrack(BezierBaker.bake(lanes, 0.05), opts);

        double lowest = 1e9, highest = -1e9;
        for (FVDIntegrator.Sample sm : samples) {
            double railY = sm.pos().y() - sm.U().y() * 1.0;
            lowest = Math.min(lowest, railY);
            highest = Math.max(highest, railY);
        }
        double oldGround = Math.floor(lowest - 1);
        System.out.printf(Locale.US,
            "rail Y %.3f..%.3f, old ground %.0f, needed default startY %.0f%n",
            lowest, highest, oldGround, -oldGround);
    }
}
