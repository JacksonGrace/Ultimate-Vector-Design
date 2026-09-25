import uvd.*;

import java.util.ArrayList;
import java.util.List;

/** Repro probes for the 2026-07-18 report:
 *  A) converting a section whose graphs JUMP at the entry seam
 *     oscillates near the start whatever the density; node 0 lands
 *     BETWEEN the neighbor's end value and the section's own value.
 *  B) "Make graphs real" on a strict-seam follower leaves the ≠v
 *     drift behind even though the convert applies. */
public class StrictConvProbe {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> lanes3(double[][] gv, double[][] gl,
                                               double[][] roll) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(gv));
        lanes.add(nodes(gl));
        lanes.add(nodes(roll));
        return lanes;
    }

    static List<List<BezierBaker.Node>> hillLanes(double dur) {
        return lanes3(
            new double[][]{ {0, 1}, {dur * 0.2, 0.6}, {dur * 0.5, 1.4},
                            {dur * 0.8, 0.6}, {dur, 1} },
            new double[][]{ {0, 0}, {dur, 0} },
            new double[][]{ {0, 0}, {dur, 0} });
    }

    static TrackCompiler.Result settle(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int i = 0; i < 6 && c.reconcileChainedSpeeds(r); i++) {
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    static double spanDrift(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(), r.samples().size() - 1);
        double worst = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    // ---------- Probe A: deliberate value jump at an ordinary seam ----

    static void probeA(double spacing) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        // sec 1: Gv flat 1, roll rate ramps 0 -> 40 deg/s by the end
        a.sections.add(new ForceSection(lanes3(
            new double[][]{ {0, 1}, {6, 1} },
            new double[][]{ {0, 0}, {6, 0} },
            new double[][]{ {0, 0}, {4, 0}, {6, 40} }), 24));
        // sec 2: Gv flat 2.2, roll flat 0 -- BOTH lanes JUMP at the seam
        a.sections.add(new ForceSection(lanes3(
            new double[][]{ {0, 2.2}, {5, 2.2} },
            new double[][]{ {0, 0}, {5, 0} },
            new double[][]{ {0, 0}, {5, 0} }), 24));
        c.pieces.add(a);
        settle(c);

        boolean did = c.convertWholeSection(0, 1, false, 0.01, spacing, 0);
        TrackCompiler.Result r = settle(c);
        LaneSection ls = (LaneSection) c.pieces.get(0).sections.get(1);
        double n0gv = ls.lanes.get(0).get(0).val;
        double n0rr = ls.lanes.get(2).get(0).val;
        // compiled felt over the converted section vs the original
        // design (flat 2.2 / roll 0): oscillation shows as maxdev well
        // inside the section
        TrackCompiler.Span sp = r.spans().get(1);
        double t0 = r.samples().get(sp.i0()).t();
        double devGv = 0;
        int flips = 0; double prevSign = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double dt0 = sm.t() - t0;
            if (dt0 < 0.05 || dt0 > 2.0) continue;
            double d = sm.Gv() - 2.2;
            devGv = Math.max(devGv, Math.abs(d));
            double sg = Math.signum(d);
            if (Math.abs(d) > 0.02 && sg != 0) {
                if (prevSign != 0 && sg != prevSign) flips++;
                prevSign = sg;
            }
        }
        System.out.printf(
            "A spacing=%.2f: did=%b  node0 Gv=%.3f (want 2.200, prev-end"
            + " 1.0)  node0 roll=%.2f (want 0, prev-end 40)  Gv maxdev"
            + " [0.05..2s]=%.3f g  sign flips=%d%n",
            spacing, did, n0gv, n0rr, devGv, flips);
    }

    // ---------- Probe B: strict seam, upstream edit, make-real -------

    static void probeB(boolean locked) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        a.sections.add(new ForceSection(hillLanes(5), 24));
        c.pieces.add(a);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        settle(c);
        c.makeSeamStrict(0, 1, 0.01);
        TrackPiece f = c.pieces.get(1);
        if (!locked) {
            f.sLockPitch = false;
            f.sLockRoll = false;
        }
        // EDIT UPSTREAM: pitch + bank the seam, change the energy
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(1).val -= 1.2;
        up.lanes.get(0).get(2).val += 1.5;
        up.lanes.get(2).get(1).val = 60;
        TrackCompiler.Result r0 = settle(c);
        double drift0 = spanDrift(r0, 1);
        double vTrue0 = r0.trueV()[r0.spans().get(1).i0()];

        // the make-real fit reproduces the TRUE ride: node 0 should
        // land on the true felt at the section's entry, and the felt
        // curve near the seam should track the pre-resolve true felt
        // (compared BY ARC — the clock re-times at vTrue)
        TrackCompiler.Span sp0 = r0.spans().get(1);
        double expGv = r0.trueFelt()[sp0.i0()][0];
        double sBase0 = r0.samples().get(sp0.i0()).s();

        boolean did = c.makeGraphsReal(1, 0, 0.01, 0.1, 0);
        TrackCompiler.Result r1 = settle(c);
        double drift1 = spanDrift(r1, 1);
        LaneSection ls = (LaneSection) c.pieces.get(1).sections.get(0);
        TrackCompiler.Span sp1 = r1.spans().get(1);
        double sBase1 = r1.samples().get(sp1.i0()).s();
        double entryDev = 0;
        for (int i = sp1.i0(); i <= sp1.i1(); i++) {
            double ds = r1.samples().get(i).s() - sBase1;
            if (ds > 25) break;   // first ~25 m after the seam
            // nearest pre-resolve sample at the same arc offset
            int j = sp0.i0();
            while (j < sp0.i1()
                    && r0.samples().get(j).s() - sBase0 < ds) j++;
            entryDev = Math.max(entryDev, Math.abs(
                r1.samples().get(i).Gv() - r0.trueFelt()[j][0]));
        }
        System.out.printf(
            "B %s: pre drift=%.3f (trueV@entry=%.2f)  did=%b  post"
            + " drift=%.3f m/s (badge %s)  vDesign now=%.2f  node0"
            + " Gv=%.3f (want trueFelt %.3f)  entry-25m felt dev=%.3f g%n",
            locked ? "LOCKED p/r" : "all-unlocked",
            drift0, vTrue0, did, drift1,
            drift1 > 0.2 ? "STAYS" : "clears",
            ls.vDesign(), ls.lanes.get(0).get(0).val, expGv, entryDev);
    }

    public static void main(String[] args) {
        probeA(0.1);
        probeA(0.02);
        probeB(true);
        probeB(false);
    }
}
