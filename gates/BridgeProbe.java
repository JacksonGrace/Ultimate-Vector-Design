import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Probe the septic bridge's seam behavior (gate fixture). */
public class BridgeProbe {
    public static void main(String[] args) {
        Coaster c = new Coaster();
        TrackPiece p1 = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> e1 = new ArrayList<>();
        e1.add(StraightCurveSection.Element.straight(10, 0));
        StraightCurveSection.Element rollStr =
            StraightCurveSection.Element.straight(15, 4);
        rollStr.rollMode = StraightCurveSection.RollMode.RATE;
        rollStr.rollVal = 15;
        e1.add(rollStr);
        p1.sections.add(new StraightCurveSection(e1, 26));
        c.pieces.add(p1);
        TrackPiece p2 = new TrackPiece(45, 16, 0, 0);
        List<StraightCurveSection.Element> e2 = new ArrayList<>();
        e2.add(StraightCurveSection.Element.curve(25, 30, 0, 0));
        p2.sections.add(new StraightCurveSection(e2, 20));
        c.pieces.add(p2);
        TrackCompiler.Result pre = TrackCompiler.compile(c, 0.01);
        BridgeSection bs = BridgeSection.solve(
            pre.spans().get(0).exit(), p2.entryPose(), 26);
        p1.sections.add(bs);
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        System.out.printf("accA=%s  jerkA=%s%n", bs.accA, bs.jerkA);
        System.out.printf("accB=%s  jerkB=%s%n", bs.accB, bs.jerkB);
        System.out.printf("rollA=%.4f rollB=%.4f vB=%.3f vDesign=%.3f%n",
            bs.rollA, bs.rollB, bs.vB, bs.vDesign);
        TrackCompiler.Span sp = r.spans().get(1);
        double[][] rates = r.rates();
        List<FVDIntegrator.Sample> s = r.samples();
        System.out.println("rows near ENTRY seam (i0=" + sp.i0() + "):");
        for (int i = -5; i <= 8; i++) {
            int a = sp.i0() + i;
            System.out.printf("  i0%+d t=%.3f  pitch=%8.2f yaw=%8.2f roll=%8.2f  v=%.3f  Ty=%+.4f%n",
                i, rates[a][0], rates[a][1], rates[a][2], rates[a][3],
                s.get(a).v(), s.get(a).T().y());
        }
        System.out.println("rows near EXIT seam (i1=" + sp.i1() + "):");
        for (int i = -5; i <= 8; i++) {
            int a = sp.i1() + i;
            System.out.printf("  i1%+d t=%.3f  pitch=%8.2f yaw=%8.2f roll=%8.2f  v=%.3f  Ty=%+.4f%n",
                i, rates[a][0], rates[a][1], rates[a][2], rates[a][3],
                s.get(a).v(), s.get(a).T().y());
        }
    }
}
