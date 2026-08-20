import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Constant-rate case: keyframes exact on ANY grid — if this deviates,
 *  the map/integrator has a real bug; if ~0, the geo 8 mm was bake-grid
 *  lerp noise. */
public class DomainProbe2 {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        double dt = 0.01;
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0,0},{8,0} }));
        gl.add(nodes(new double[][]{ {0,10},{8,10} }));   // constant yaw
        gl.add(nodes(new double[][]{ {0,0},{8,0} }));
        Coaster gc = new Coaster();
        gc.v0 = 24;
        TrackPiece gp = new TrackPiece(0, 21, 0, 0);
        gp.sections.add(new GeoSection(gl, 24));
        gc.pieces.add(gp);
        TrackCompiler.Result a = TrackCompiler.compile(gc, dt);
        gc.switchLaneDomain(0, 0, dt);
        TrackCompiler.Result b = TrackCompiler.compile(gc, dt);
        double sEndA = a.samples().get(a.samples().size() - 1).s();
        double sEndB = b.samples().get(b.samples().size() - 1).s();
        double worst = 0;
        for (int k = 0; k <= 400; k++) {
            double f = k / 400.0;
            worst = Math.max(worst, posAtArc(a.samples(), f * sEndA)
                .sub(posAtArc(b.samples(), f * sEndB)).length());
        }
        System.out.printf("constant-yaw switch: dev=%.3e m, sEnd %.6f -> %.6f%n",
            worst, sEndA, sEndB);
    }

    static Vec3 posAtArc(List<FVDIntegrator.Sample> s, double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        double ds = s.get(hi).s() - s.get(lo).s();
        double u = ds < 1e-12 ? 0
            : Math.max(0, Math.min(1, (arc - s.get(lo).s()) / ds));
        return s.get(lo).pos().add(
            s.get(hi).pos().sub(s.get(lo).pos()).scale(u));
    }
}
