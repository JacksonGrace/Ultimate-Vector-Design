import uvd.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dump the F4b@32 bridge sample-by-sample: pitch, heading, bank,
 *  Gl — where does the lateral come from on a planar fixture? */
public class F4bDebug {

    static List<BezierBaker.Node> hold(double v, double T) {
        List<BezierBaker.Node> l = new ArrayList<>();
        l.add(new BezierBaker.Node(0, v));
        l.add(new BezierBaker.Node(T, v));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<BezierBaker.Node> ramp(double a, double b, double T) {
        List<BezierBaker.Node> l = new ArrayList<>();
        BezierBaker.Node n0 = new BezierBaker.Node(0, a);
        BezierBaker.Node n1 = new BezierBaker.Node(T, b);
        n0.outFrac = 1.0 / 3; n0.outDv = (b - a) / 3;
        n1.inFrac = 1.0 / 3;  n1.inDv = -(b - a) / 3;
        l.add(n0);
        l.add(n1);
        return l;
    }

    public static void main(String[] args) {
        Coaster c = new Coaster();
        TrackPiece pA = new TrackPiece(0, 40, 0, 0);
        List<List<BezierBaker.Node>> lA = new ArrayList<>();
        lA.add(ramp(0, 60, 2));
        lA.add(hold(0, 2));
        lA.add(hold(0, 2));
        pA.sections.add(new GeoSection(lA, 26));
        c.pieces.add(pA);
        TrackPiece pB = new TrackPiece(50, 42, 12, 15);
        List<List<BezierBaker.Node>> lB = new ArrayList<>();
        lB.add(hold(0, 2));
        lB.add(hold(0, 2));
        lB.add(hold(0, 2));
        pB.sections.add(new GeoSection(lB, 26));
        c.pieces.add(pB);
        BridgeSection bs = BridgeSection.solve(
            new TrackPose(new Vec3(0, 0, 0), Quat.IDENTITY),
            new TrackPose(new Vec3(1, 0, 0), Quat.IDENTITY), 26);
        pA.sections.add(bs);
        TrackCompiler.compile(c, 0.01);
        c.optimizeBridge(bs, 1, 32, true);
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        if (c.autoReoptimizeBridges(r)) r = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span sp = r.spans().get(1);
        List<FVDIntegrator.Sample> s = r.samples();
        System.out.println("entry seam pitch: " + Math.toDegrees(
            Math.asin(s.get(sp.i0()).T().y())));
        System.out.println("  i |    s |  pitch | heading |  bank | Gv | Gl");
        for (int i = sp.i0(); i <= sp.i1(); i += 8) {
            FVDIntegrator.Sample sm = s.get(i);
            double pitch = Math.toDegrees(Math.asin(Math.max(-1,
                Math.min(1, sm.T().y()))));
            double head = Math.toDegrees(Math.atan2(-sm.T().z(), sm.T().x()));
            double h2 = 1 - sm.T().y() * sm.T().y();
            double bank = 0;
            if (h2 > 1e-9) {
                Vec3 ul = new Vec3(-sm.T().y() * sm.T().x(), h2,
                    -sm.T().y() * sm.T().z()).normalize();
                bank = Math.toDegrees(Math.atan2(
                    ul.cross(sm.U()).dot(sm.T()), sm.U().dot(ul)));
            }
            System.out.printf(Locale.US,
                "%4d | %5.1f | %6.1f | %7.1f | %5.1f | %5.2f | %5.2f%n",
                i - sp.i0(), sm.s() - s.get(sp.i0()).s(), pitch, head,
                bank, sm.Gv(), sm.Gl());
        }
    }
}
