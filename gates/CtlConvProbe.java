import uvd.*;

import java.util.*;

/** D235 probe — converts of sections CARRYING SPEED CONTROLS (the
 *  user's "you can't convert a section that has a speed control"):
 *  verify the engine actually carries them on type flips and domain
 *  flips, complex graphs included. */
public class CtlConvProbe {

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    public static void main(String[] args) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 2.2}, {4, 0.6}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 0.3}, {6, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 20}, {6, 0} }));
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection fs = new ForceSection(ls, 26);
        SpeedControl ctl = new SpeedControl(2.0, 1.5);
        ctl.speed = 14;
        fs.speedCtls.add(ctl);
        SpeedControl cx = new SpeedControl(4.5, 1.0);
        cx.complex = true;
        cx.type = SpeedControl.ACCEL;
        cx.accel = 0.4;
        cx.lane.add(new BezierBaker.Node(0, 0.3));
        cx.lane.add(new BezierBaker.Node(1.0, 0.5));
        BezierBaker.applyDefaultHandles(cx.lane);
        fs.speedCtls.add(cx);
        fs.speedMarks.add(1.0);
        p.sections.add(fs);
        c.pieces.add(p);
        boolean t1 = c.convertWholeSection(0, 0, true, 0.01, 0.1, 0.01,
            Section.Domain.TIME, false, false, false, false, false);
        LaneSection out1 = (LaneSection) p.sections.get(0);
        System.out.println("force->geo ok=" + t1
            + " ctls=" + out1.speedCtls.size()
            + " marks=" + out1.speedMarks.size()
            + " complexNodes=" + (out1.speedCtls.size() > 1
                ? out1.speedCtls.get(1).lane.size() : -1));
        boolean t2 = c.convertWholeSection(0, 0, true, 0.01, 1.0, 0.01,
            Section.Domain.DISTANCE, false, false, false, false, false);
        LaneSection out2 = (LaneSection) p.sections.get(0);
        System.out.println("time->distance ok=" + t2
            + " dom=" + out2.domain()
            + " ctls=" + out2.speedCtls.size()
            + " x0[0]=" + String.format(java.util.Locale.US, "%.2f",
                out2.speedCtls.isEmpty() ? -1 : out2.speedCtls.get(0).x0)
            + " marks=" + out2.speedMarks.size());
        boolean t3 = c.convertDomain(0, 0, 0.01, 1.0, 0.01);
        System.out.println("convertDomain back ok=" + t3
            + " ctls=" + ((LaneSection) p.sections.get(0)).speedCtls.size());
    }
}
