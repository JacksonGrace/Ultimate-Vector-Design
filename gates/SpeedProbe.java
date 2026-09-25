import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Scratch probe: speed-control physics semantics (v18.0). */
public class SpeedProbe {

    static List<List<BezierBaker.Node>> flatLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> l = new ArrayList<>();
            l.add(new BezierBaker.Node(0, i == 0 ? 1 : 0));
            l.add(new BezierBaker.Node(dur, i == 0 ? 1 : 0));
            BezierBaker.applyDefaultHandles(l);
            lanes.add(l);
        }
        return lanes;
    }

    static double vAt(List<FVDIntegrator.Sample> s, double t) {
        for (FVDIntegrator.Sample sm : s) {
            if (Math.abs(sm.t() - t) < 0.006) return sm.v();
        }
        return Double.NaN;
    }

    public static void main(String[] args) {
        double dt = 0.01;

        // ---- 1: const-speed clamp on flat track ----
        {
            ForceSection fs = new ForceSection(flatLanes(6), 26);
            SpeedControl c = new SpeedControl(1, 2);
            c.type = SpeedControl.SPEED;
            c.speed = 15;
            fs.speedCtls.add(c);
            TrackCompiler.Centerline line = TrackCompiler.passG(fs,
                new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY), dt);
            List<FVDIntegrator.Sample> s = line.samples();
            System.out.printf("1 const-speed: v(0.5)=%.4f v(1.05)=%.4f v(2)=%.4f v(3.5)=%.4f v(end)=%.4f%n",
                vAt(s, 0.5), vAt(s, 1.05), vAt(s, 2), vAt(s, 3.5),
                s.get(s.size() - 1).v());
        }

        // ---- 2: const accel launch on flat track (min only) ----
        {
            ForceSection fs = new ForceSection(flatLanes(6), 26);
            SpeedControl c = new SpeedControl(1, 4);
            c.type = SpeedControl.ACCEL;
            c.speed = 40;
            c.accel = 1.0;   // 1 g felt
            c.maxLimit = false;
            fs.speedCtls.add(c);
            TrackCompiler.Centerline line = TrackCompiler.passG(fs,
                new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY), dt);
            List<FVDIntegrator.Sample> s = line.samples();
            // analytic: v = 26 + g*(t-1) until 40 (t ≈ 2.428), then hold
            double vAn = 26 + 9.80665 * (2.0 - 1.0);
            System.out.printf("2 accel launch: v(2)=%.4f (analytic %.4f)  v(3)=%.4f (expect 40)  v(end)=%.4f%n",
                vAt(s, 2), vAn, vAt(s, 3), s.get(s.size() - 1).v());
        }

        // ---- 3: passD applies the same controls (conflict resolution) ----
        {
            Coaster co = new Coaster();
            co.v0 = 26;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection a = new ForceSection(flatLanes(2), 26);
            ForceSection b = new ForceSection(flatLanes(4), 20);   // conflicted: design 20 vs real 26
            b.vPinned = true;
            SpeedControl c = new SpeedControl(1, 2);
            c.type = SpeedControl.SPEED;
            c.speed = 15;
            b.speedCtls.add(c);
            p.sections.add(a);
            p.sections.add(b);
            co.pieces.add(p);
            TrackCompiler.Result r = TrackCompiler.compile(co, dt);
            int last = r.samples().size() - 1;
            double vDesEnd = r.samples().get(last).v();
            double vTrueEnd = r.trueV()[last];
            System.out.printf("3 conflict close: design end v=%.6f true end v=%.6f (both expect 15)%n",
                vDesEnd, vTrueEnd);
            // max |design - true| after the control (t >= 3+2 = section-local 3)
            double worst = 0;
            for (int i = 0; i <= last; i++) {
                if (r.samples().get(i).t() > 5.0) {
                    worst = Math.max(worst,
                        Math.abs(r.samples().get(i).v() - r.trueV()[i]));
                }
            }
            System.out.printf("3 post-control design/true split: %.2e m/s%n", worst);
        }

        // ---- 4: drag-race brake (max only, accel) with friction on ----
        {
            Coaster co = new Coaster();
            co.v0 = 30;
            co.fricRoll = 0.03;
            co.fricAir = 1e-4;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection a = new ForceSection(flatLanes(8), 30);
            SpeedControl c = new SpeedControl(3, 4);
            c.type = SpeedControl.ACCEL;
            c.speed = 5;
            c.accel = 1.2;
            c.minLimit = false;
            a.speedCtls.add(c);
            p.sections.add(a);
            co.pieces.add(p);
            TrackCompiler.Result r = TrackCompiler.compile(co, dt);
            List<FVDIntegrator.Sample> s = r.samples();
            int last = s.size() - 1;
            System.out.printf("4 brake w/ friction: v(2.9)=%.4f v(end)=%.4f (expect ~5) trueV(end)=%.4f%n",
                vAt(s, 2.9), s.get(last).v(), r.trueV()[last]);
        }

        // ---- 5: complex speed graph (ramp 26 -> 10) ----
        {
            ForceSection fs = new ForceSection(flatLanes(6), 26);
            SpeedControl c = new SpeedControl(1, 4);
            c.type = SpeedControl.SPEED;
            c.complex = true;
            c.freshLane(26);
            c.lane.get(1).val = 10;   // ramp down across the control
            fs.speedCtls.add(c);
            TrackCompiler.Centerline line = TrackCompiler.passG(fs,
                new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY), dt);
            List<FVDIntegrator.Sample> s = line.samples();
            System.out.printf("5 complex speed: v(1.5)=%.4f v(3)=%.4f v(4.99)=%.4f (graph %.4f/%.4f/%.4f)%n",
                vAt(s, 1.5), vAt(s, 3), vAt(s, 4.99),
                BezierBaker.exactAt(c.lane, 0.5),
                BezierBaker.exactAt(c.lane, 2.0),
                BezierBaker.exactAt(c.lane, 3.99));
        }

        // ---- 6: zero controls = no change (spot, not bitwise here;
        //         the real bitwise gates are the old suites) ----
        {
            ForceSection fs = new ForceSection(flatLanes(6), 26);
            TrackCompiler.Centerline line = TrackCompiler.passG(fs,
                new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY), dt);
            System.out.printf("6 no-controls: v(end)=%.10f (expect 26)%n",
                line.samples().get(line.samples().size() - 1).v());
        }
    }
}
