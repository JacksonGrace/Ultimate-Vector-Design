import coaster.FVDIntegrator;
import coaster.Nl2CsvExporter;
import coaster.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Locate the worst spine deviation: distance to the offset POLYLINE (segments). */
public class Nl2Debug {

    static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.sub(a);
        double len2 = ab.dot(ab);
        double t = len2 < 1e-18 ? 0 : Math.max(0, Math.min(1, p.sub(a).dot(ab) / len2));
        return a.add(ab.scale(t)).sub(p).length();
    }

    public static void main(String[] args) {
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        opts.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Keyframe> hilly = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0, 0),
            new FVDIntegrator.Keyframe(2, 3.5, 0, 0),
            new FVDIntegrator.Keyframe(4, -0.5, 0.3, 40),
            new FVDIntegrator.Keyframe(7, 2.0, -0.2, -30),
            new FVDIntegrator.Keyframe(10, 1, 0, 0));
        List<FVDIntegrator.Sample> s3 = FVDIntegrator.integrateTrack(hilly, opts);
        double h = 1.1;

        double vMax = 0, yMin = 1e9, yMax = -1e9;
        List<Vec3> spine = new ArrayList<>();
        for (FVDIntegrator.Sample sm : s3) {
            spine.add(sm.pos().sub(sm.U().scale(h)));
            vMax = Math.max(vMax, sm.v());
            yMin = Math.min(yMin, sm.pos().y());
            yMax = Math.max(yMax, sm.pos().y());
        }
        System.out.printf(Locale.US, "samples %d, vMax %.1f, y %.1f..%.1f%n",
            s3.size(), vMax, yMin, yMax);

        Nl2CsvExporter.Result r = Nl2CsvExporter.export(s3, false, h, 1.0);
        String[] lines = r.csv().split("\n");
        double worst = 0; int worstRow = -1;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t");
            Vec3 p = new Vec3(Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                              -Double.parseDouble(f[3]));
            double best = 1e9;
            for (int j = 0; j + 1 < spine.size(); j++)
                best = Math.min(best, distToSegment(p, spine.get(j), spine.get(j + 1)));
            if (best > worst) { worst = best; worstRow = i; }
        }
        System.out.printf(Locale.US, "worst dist to spine POLYLINE: %.6f m at row %d of %d%n",
            worst, worstRow, lines.length - 1);
        if (worstRow > 0) {
            System.out.println("row: " + lines[worstRow]);
            // neighbors of arc position: reconstruct target arc for that row
            double[] arc = new double[spine.size()];
            for (int i = 1; i < spine.size(); i++)
                arc[i] = arc[i - 1] + spine.get(i).sub(spine.get(i - 1)).length();
            double step = r.step();
            double target = (worstRow - 1) * step;
            int idx = 0;
            while (idx < spine.size() - 2 && arc[idx + 1] < target) idx++;
            System.out.printf(Locale.US, "target arc %.3f, idx %d, arc[idx]=%.3f arc[idx+1]=%.3f, chord %.3f%n",
                target, idx, arc[idx], arc[idx + 1], spine.get(idx + 1).sub(spine.get(idx)).length());
            System.out.println("p[idx]   = " + spine.get(idx));
            System.out.println("p[idx+1] = " + spine.get(idx + 1));
        }
    }
}
