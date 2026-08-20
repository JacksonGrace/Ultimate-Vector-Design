import coaster.*;

import java.util.ArrayList;
import java.util.List;

/** Discriminator: constant-speed switch (composition exact — any error
 *  is implementation) vs varying-speed (theory residual). */
public class DomainProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        double dt = 0.01;
        // FLAT S-curve turn at constant height: Gv=1 level, Gl ramps —
        // y constant => v constant => s(t) linear => composition exact
        List<List<BezierBaker.Node>> fl = new ArrayList<>();
        fl.add(nodes(new double[][]{ {0,1},{8,1} }));
        fl.add(nodes(new double[][]{ {0,0},{2,0},{4,0.9},{6,-0.5},{8,0} }));
        fl.add(nodes(new double[][]{ {0,0},{3,20},{5,-20},{8,0} }));
        Coaster fc = Coaster.singleForce(fl, 24, 0, 21, 0);
        TrackCompiler.Result a = TrackCompiler.compile(fc, dt);
        fc.switchLaneDomain(0, 0, dt);
        TrackCompiler.Result b = TrackCompiler.compile(fc, dt);
        System.out.printf("flat-turn (const v) switch dev = %.3e m%n",
            dev(a, b));
        // wait: Gv=1 flat + roll banks the frame; U.y drifts off 1 =>
        // omegaU nonzero => y moves a bit. print the v range to see.
        double vLo = 1e9, vHi = -1e9;
        for (FVDIntegrator.Sample sm : a.samples()) {
            vLo = Math.min(vLo, sm.v());
            vHi = Math.max(vHi, sm.v());
        }
        System.out.printf("  v range %.3f..%.3f%n", vLo, vHi);

        // TRUE constant speed: geo section, level yaw sweep only
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0,0},{8,0} }));
        gl.add(nodes(new double[][]{ {0,0},{2,25},{6,-15},{8,0} }));
        gl.add(nodes(new double[][]{ {0,0},{8,0} }));
        Coaster gc = new Coaster();
        gc.v0 = 24;
        TrackPiece gp = new TrackPiece(0, 21, 0, 0);
        gp.sections.add(new GeoSection(gl, 24));
        gc.pieces.add(gp);
        TrackCompiler.Result ga = TrackCompiler.compile(gc, dt);
        gc.switchLaneDomain(0, 0, dt);
        TrackCompiler.Result gb = TrackCompiler.compile(gc, dt);
        System.out.printf("geo yaw-only (const v) switch dev = %.3e m%n",
            dev(ga, gb));

        // default layout: residual PROFILE along the arc
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));
        Coaster dc = Coaster.singleForce(lanes, 26, 0, 21, 0);
        TrackCompiler.Result da = TrackCompiler.compile(dc, dt);
        dc.switchLaneDomain(0, 0, dt);
        TrackCompiler.Result db = TrackCompiler.compile(dc, dt);
        double sEndA = da.samples().get(da.samples().size() - 1).s();
        double sEndB = db.samples().get(db.samples().size() - 1).s();
        System.out.printf("default layout: sEnd %.4f -> %.4f%n", sEndA, sEndB);
        for (int k = 0; k <= 20; k++) {
            double f = k / 20.0;
            Vec3 pa = posAtArc(da.samples(), f * sEndA);
            Vec3 pb = posAtArc(db.samples(), f * sEndB);
            System.out.printf("  f=%.2f  dev=%.4f m%n", f, pa.sub(pb).length());
        }
    }

    static double dev(TrackCompiler.Result a, TrackCompiler.Result b) {
        double sEndA = a.samples().get(a.samples().size() - 1).s();
        double sEndB = b.samples().get(b.samples().size() - 1).s();
        double worst = Math.abs(sEndA - sEndB);
        for (int k = 0; k <= 400; k++) {
            double f = k / 400.0;
            worst = Math.max(worst, posAtArc(a.samples(), f * sEndA)
                .sub(posAtArc(b.samples(), f * sEndB)).length());
        }
        return worst;
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
