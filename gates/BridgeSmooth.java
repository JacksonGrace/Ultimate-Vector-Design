import uvd.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge interior smoothness probe (v13.3): how do the resulting rate
 * profiles through a bridge distribute vs tangent length? The user
 * reports "spiky ends, flat middles". Theory: with lenA = lenB = gap/2
 * (the solve() default), |p'| at the ends is HALF the interior bulge,
 * so the same du turning near the ends packs into less arc length —
 * curvature (and rates over time) spike at the seams and go flat in
 * the middle. len ~ gap should equalize |p'|.
 */
public class BridgeSmooth {

    static Coaster c;
    static TrackPiece p1, p2;
    static BridgeSection bs;

    /** Chain: 10 m straight + 15 m straight rolling at 15 deg/s, then a
     *  bridge to a target piece (dx, dy, dz away, yawDeg heading) that
     *  opens with a 30-degree pull-up at design 20. */
    static void build(double dx, double dy, double dz, double yawDeg) {
        c = new Coaster();
        p1 = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> e1 = new ArrayList<>();
        e1.add(StraightCurveSection.Element.straight(10, 0));
        StraightCurveSection.Element rollStr =
            StraightCurveSection.Element.straight(15, 4);
        rollStr.rollMode = StraightCurveSection.RollMode.RATE;
        rollStr.rollVal = 15;
        e1.add(rollStr);
        p1.sections.add(new StraightCurveSection(e1, 26));
        c.pieces.add(p1);
        p2 = new TrackPiece(25 + dx, 21 + dy, dz, yawDeg);
        List<StraightCurveSection.Element> e2 = new ArrayList<>();
        e2.add(StraightCurveSection.Element.curve(25, 30, 0, 0));
        p2.sections.add(new StraightCurveSection(e2, 20));
        c.pieces.add(p2);
        TrackCompiler.Result pre = TrackCompiler.compile(c, 0.01);
        bs = BridgeSection.solve(pre.spans().get(0).exit(), p2.entryPose(), 26);
        p1.sections.add(bs);
    }

    static void stats(String label, double lenFactor, boolean profile) {
        double gap = bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length();
        bs.lenA = Math.max(1, gap * lenFactor);
        bs.lenB = Math.max(1, gap * lenFactor);
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span sp = r.spans().get(1);
        double[][] rr = r.rates();
        List<FVDIntegrator.Sample> s = r.samples();
        int a = sp.i0() + 3, b = sp.i1() - 3;   // clear of the seam smear
        double maxSlope = 0, maxRate = 0, endRate = 0, midRate = 0;
        double maxKp = 0;
        for (int i = a; i <= b; i++) {
            double mag = Math.hypot(rr[i][1], rr[i][2]);
            maxRate = Math.max(maxRate, mag);
            if (i <= a + 5 || i >= b - 5) endRate = Math.max(endRate, mag);
            int mid = (a + b) / 2;
            if (Math.abs(i - mid) <= 5) midRate = Math.max(midRate, mag);
            if (i > a) {
                double dt = rr[i][0] - rr[i - 1][0];
                double ds = s.get(i).s() - s.get(i - 1).s();
                double d1 = Math.hypot(rr[i][1] - rr[i - 1][1],
                                       rr[i][2] - rr[i - 1][2]);
                maxSlope = Math.max(maxSlope, d1 / dt);
                // curvature change per meter (kappa ~ rate / v)
                double k1 = Math.hypot(rr[i][1], rr[i][2]) / s.get(i).v();
                double k0 = Math.hypot(rr[i - 1][1], rr[i - 1][2]) / s.get(i - 1).v();
                maxKp = Math.max(maxKp, Math.abs(k1 - k0) / ds);
            }
        }
        System.out.printf(
            "  len=%.2f*gap  maxRate %7.2f  end %7.2f  mid %7.2f  end/mid %5.2f  max|dRate/dt| %8.2f  max|dKdeg/ds| %7.3f%n",
            lenFactor, maxRate, endRate, midRate,
            midRate < 1e-9 ? 99.9 : endRate / midRate, maxSlope, maxKp);
        if (profile) {
            int n = 48;
            System.out.println("  pitch/yaw magnitude profile (deg/s):");
            StringBuilder sb = new StringBuilder("    ");
            for (int j = 0; j < n; j++) {
                int i = a + (int) Math.round((double) j / (n - 1) * (b - a));
                double mag = Math.hypot(rr[i][1], rr[i][2]);
                sb.append(String.format("%4.0f", mag));
                if ((j + 1) % 16 == 0) { System.out.println(sb); sb = new StringBuilder("    "); }
            }
            if (sb.length() > 4) System.out.println(sb);
        }
    }

    static void scenario(String name, double dx, double dy, double dz, double yaw) {
        System.out.println("=== " + name + " ===");
        build(dx, dy, dz, yaw);
        stats(name, 0.5, true);
        stats(name, 0.7, false);
        stats(name, 0.85, false);
        stats(name, 1.0, true);
        stats(name, 1.15, false);
        stats(name, 1.3, false);
    }

    public static void main(String[] args) {
        scenario("gap 20 ahead, 5 m drop", 20, -5, 0, 0);
        scenario("lateral S: 25 ahead, 12 sideways", 25, 0, 12, 0);
        scenario("turnaround-ish: 20 ahead, 15 side, 60 deg yaw", 20, 0, 15, -60);
        scenario("short gap 8, level", 8, 0, 0, 0);
    }
}
