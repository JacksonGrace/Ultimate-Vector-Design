import uvd.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Repro of the user's mode-switch glitch: POS w/ manual length ->
 *  NONE (move target) -> back to POS_YAW = "instant jump in values at
 *  the end of the graph"; POS<->POS_YAW ping-pong = wrong in POS_YAW,
 *  right in POS. Measures the far-seam step each hop. */
public class BridgeModeSwitch {

    static List<BezierBaker.Node> hold(double v, double T) {
        List<BezierBaker.Node> l = new ArrayList<>();
        l.add(new BezierBaker.Node(0, v));
        l.add(new BezierBaker.Node(T, v));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster c;
    static BridgeSection bs;

    public static void main(String[] args) {
        c = new Coaster();
        TrackPiece pA = new TrackPiece(0, 40, 0, 0);
        List<List<BezierBaker.Node>> lA = new ArrayList<>();
        lA.add(hold(10, 2));
        lA.add(hold(15, 2));
        lA.add(hold(0, 2));
        pA.sections.add(new GeoSection(lA, 26));
        c.pieces.add(pA);
        TrackPiece pB = new TrackPiece(55, 38, 14, 30);
        List<List<BezierBaker.Node>> lB = new ArrayList<>();
        lB.add(hold(0, 2));
        lB.add(hold(0, 2));
        lB.add(hold(0, 2));
        pB.sections.add(new GeoSection(lB, 26));
        c.pieces.add(pB);
        bs = BridgeSection.solve(
            new TrackPose(new Vec3(0, 0, 0), Quat.IDENTITY),
            new TrackPose(new Vec3(1, 0, 0), Quat.IDENTITY), 26);
        bs.optMode = BridgeSection.OPT_POS;
        pA.sections.add(bs);
        TrackCompiler.compile(c, 0.01);

        step("POS @25 (optLen off)", 25, false);
        // manual mode: user drags the target 6 m
        bs.optMode = BridgeSection.OPT_NONE;
        c.pieces.get(1).x += 6;
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        report("NONE, target moved +6x (septic)", r);
        double arc = r.spans().get(1).sLen();
        bs.optMode = BridgeSection.OPT_POS_YAW;
        step("back to POS_YAW @arc " + String.format("%.1f", arc), arc, true);
        step("ping POS  same len", bs.lastOptDist, false);
        step("ping POS_YAW same len", bs.lastOptDist, true);
        step("ping POS  same len", bs.lastOptDist, false);
        step("ping POS_YAW same len", bs.lastOptDist, true);
    }

    static void step(String label, double len, boolean py) {
        bs.optMode = py ? BridgeSection.OPT_POS_YAW : BridgeSection.OPT_POS;
        c.optimizeBridge(bs, 1, len, py);
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        if (c.autoReoptimizeBridges(r)) r = TrackCompiler.compile(c, 0.01);
        report(label, r);
    }

    static void report(String label, TrackCompiler.Result r) {
        TrackCompiler.Span sp = r.spans().get(1);
        List<FVDIntegrator.Sample> s = r.samples();
        FVDIntegrator.Sample e = s.get(sp.i1());
        FVDIntegrator.Sample h = s.get(Math.min(sp.i1() + 1, s.size() - 1));
        double dGv = Math.abs(e.Gv() - h.Gv());
        double dGl = Math.abs(e.Gl() - h.Gl());
        double uAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            e.U().dot(h.U())))));
        double tAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            e.T().dot(h.T())))));
        boolean march = Math.abs(sp.sLen() - bs.lastOptDist) < 0.5;
        // also the biggest rate step in the LAST quarter of the bridge
        double maxRateStep = 0;
        for (int i = sp.i0() + (sp.i1() - sp.i0()) * 3 / 4; i < sp.i1(); i++) {
            double d1 = Math.abs(r.rates()[i + 1][1] - r.rates()[i][1]);
            double d2 = Math.abs(r.rates()[i + 1][2] - r.rates()[i][2]);
            double d3 = Math.abs(r.rates()[i + 1][3] - r.rates()[i][3]);
            maxRateStep = Math.max(maxRateStep, Math.max(d1, Math.max(d2, d3)));
        }
        double dHead = Math.toDegrees(
            Math.atan2(-e.T().z(), e.T().x())
            - Math.atan2(-h.T().z(), h.T().x()));
        double dPitch = Math.toDegrees(
            Math.asin(Math.max(-1, Math.min(1, e.T().y())))
            - Math.asin(Math.max(-1, Math.min(1, h.T().y()))));
        System.out.printf(Locale.US,
            "%-32s | %s len %6.2f arc %6.2f | seam dGv %.3f dGl %.3f"
            + " T %.3f U %.3f deg (head %+.4f pitch %+.4f)"
            + " | rateStep(last quarter) %.2f%n",
            label, march ? "MARCH " : "SEPTIC", bs.lastOptDist, sp.sLen(),
            dGv, dGl, tAng, uAng, dHead, dPitch, maxRateStep);
    }
}
