import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe for the user's "only the gizmo moved" bug: new project ->
 * convert the section to GEO + DISTANCE + deg/m + global yaw-pitch +
 * global roll + rate behavior -> move the piece origin -> does the
 * compiled geometry follow?
 */
public class GizmoProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** File > New's document: one 1 s force/time section holding 1 g. */
    static Coaster newDoc() {
        List<List<BezierBaker.Node>> nl = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            nl.add(nodes(new double[][]{ {0, i == 0 ? 1 : 0}, {1, i == 0 ? 1 : 0} }));
        }
        Coaster c = Coaster.singleForce(nl, 26, 0, 21, 0);
        c.fricRoll = 0.03;
        c.fricAir = 1.0e-4;
        return c;
    }

    static Vec3 at(TrackCompiler.Result r, int i) {
        return r.samples().get(Math.min(i, r.samples().size() - 1)).pos();
    }

    public static void main(String[] args) throws Exception {
        double dt = 0.01;
        // every convert-flag combination that targets a lane section:
        // toGeo x domain x rateM x ypG x rollG (rollAbs needs global)
        for (int toGeo = 0; toGeo <= 1; toGeo++)
        for (int dist = 0; dist <= 1; dist++)
        for (int rateM = 0; rateM <= 1; rateM++)
        for (int ypG = 0; ypG <= 1; ypG++)
        for (int rollG = 0; rollG <= 1; rollG++) {
            String tag = (toGeo == 1 ? "geo" : "force")
                + (dist == 1 ? "/dist" : "/time")
                + (rateM == 1 ? "/degm" : "/degs")
                + (ypG == 1 ? "/ypG" : "/ypL")
                + (rollG == 1 ? "/rollG" : "/rollL");
            try {
                Coaster c = newDoc();
                TrackCompiler.compile(c, dt);
                boolean ok = c.convertWholeSection(0, 0, toGeo == 1, dt,
                    0.1, 0, dist == 1 ? Section.Domain.DISTANCE
                                      : Section.Domain.TIME,
                    rollG == 1, ypG == 1, rateM == 1, false);
                if (!ok) {
                    System.out.println("CONVERT-REFUSED  " + tag);
                    continue;
                }
                TrackCompiler.Result r0 = TrackCompiler.compile(c, dt);
                int n = r0.samples().size();
                Vec3 a0 = at(r0, 0), a1 = at(r0, n / 2), a2 = at(r0, n - 1);
                TrackPiece p = c.pieces.get(0);
                p.x += 10; p.y += 3; p.z -= 4;
                TrackCompiler.Result r1 = TrackCompiler.compile(c, dt);
                Vec3 d = new Vec3(10, 3, -4);
                double miss = Math.max(
                    at(r1, 0).sub(a0.add(d)).length(), Math.max(
                    at(r1, n / 2).sub(a1.add(d)).length(),
                    at(r1, n - 1).sub(a2.add(d)).length()));
                int n1 = r1.samples().size();
                System.out.println((miss < 1e-9 && n1 == n ? "PASS  " : "FAIL  ")
                    + tag + "  miss=" + miss + " n " + n + "->" + n1);
            } catch (Throwable ex) {
                System.out.println("THROW  " + tag + "  " + ex);
                ex.printStackTrace(System.out);
            }
        }
    }
}
