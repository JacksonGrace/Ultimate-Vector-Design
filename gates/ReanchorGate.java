import uvd.*;

import java.util.*;

/** D235 gate — DELETE-FIRST-SECTION RE-ANCHOR (v19.6, user spec:
 *  deleting a piece's first section must leave the remaining track
 *  exactly where it was).
 *  a) plain piece: reanchorAtBoundary + remove(0) keeps the tail
 *     BITWISE (the D199 canonical pair).
 *  b) a piece with a TRUE-tier tilt (design conflict): the tail
 *     stays put to dust (the D224 design-chain handoff).
 *  c) the re-anchored document save/loads to the same compile.
 */
public class ReanchorGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static void check(boolean ok, String name, String detail) {
        check(ok, name + "  (" + detail + ")");
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes(double len, double amp) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 1 + amp},
            {len * 0.7, 1 - amp * 0.6}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.3}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 25}, {len, 0} }));
        return ls;
    }

    static Coaster fixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(hillLanes(4, 1.2), 26));
        p.sections.add(new ForceSection(hillLanes(5, 0.9), 24));
        p.sections.add(new ForceSection(hillLanes(4, 1.1), 23));
        c.pieces.add(p);
        return c;
    }

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** worst position gap sample-by-sample from the boundary on:
     *  the deleted doc's samples vs the original tail. */
    static double tailDev(TrackCompiler.Result orig, int tail0,
                          TrackCompiler.Result cut) {
        double worst = 0;
        int n = Math.min(orig.samples().size() - tail0,
            cut.samples().size());
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, orig.samples().get(tail0 + i).pos()
                .sub(cut.samples().get(i).pos()).length());
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) plain piece: BITWISE tail ----
        Coaster c = fixture();
        TrackCompiler.Result r0 = rebuild(c);
        // the boundary SAMPLE (span 0's exit) — a continuation span
        // starts one dt after it (D84), while the re-anchored piece
        // keeps its entry sample, so alignment starts there
        int tail0 = r0.spans().get(0).i1();
        TrackPiece p = c.pieces.get(0);
        c.reanchorAtBoundary(p, r0.spans().get(0));
        p.sections.remove(0);
        c.v0 = p.sections.get(0).vDesign();
        TrackCompiler.Result r1 = rebuild(c);
        double devA = tailDev(r0, tail0, r1);
        check(devA == 0.0,
            "a1 plain delete-first: the tail is BITWISE",
            String.format("worst dev %.2e m", devA));

        // ---- (b) tilted piece (a live design/true conflict) ----
        Coaster ct = fixture();
        TrackPiece pt = ct.pieces.get(0);
        pt.pitchDeg = 5;   // true-tier tilt, design stays (0,0)
        pt.rollDeg = -3;
        TrackCompiler.Result t0 = rebuild(ct);
        int tTail = t0.spans().get(0).i1();
        ct.reanchorAtBoundary(pt, t0.spans().get(0));
        pt.sections.remove(0);
        TrackCompiler.Result t1 = rebuild(ct);
        double devB = tailDev(t0, tTail, t1);
        check(devB < 1e-9,
            "b1 tilted delete-first: the tail stays put to dust",
            String.format("worst dev %.2e m", devB));

        // ---- (c) IO round trip of the re-anchored doc ----
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        Coaster back = DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
        TrackCompiler.Result r2 = rebuild(back);
        double devC = tailDev(r1, 0, r2);
        check(devC == 0.0,
            "c1 re-anchored doc save/loads to the same compile",
            String.format("worst dev %.2e m", devC));

        System.out.println();
        System.out.println(fail == 0
            ? "ALL REANCHOR GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
