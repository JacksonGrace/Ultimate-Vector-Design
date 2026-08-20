import coaster.*;

import java.util.*;

/** D234 gate — INFLUENCED sections SPLIT (v19.5, user re-spec
 *  lifting the D98 refusal).
 *  a) structure: both halves influenced, mirrored pairs, axis flags
 *     carried, delta lanes cut continuously at the boundary.
 *  b) deltas ZERO up to the cut: the split holds the track in the
 *     normal seam class (the right half's base re-anchors at the
 *     cut's resulting frame == the ref frame there).
 *  c) deltas NON-ZERO before the cut: the tail re-solves slightly
 *     (the ACCEPTED CORNER — base is defined from the section's own
 *     entry); measured and bounded.
 *  d) the piece-split chain (section split + D71 boundary split)
 *     adds nothing on top of the section split.
 *  e) a LEGACY divergent pair (vAuthored != vDesign) refuses.
 */
public class InflSplitGate {

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

    static List<List<BezierBaker.Node>> hillLanes(double len) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 2.2},
            {len * 0.7, 0.5}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.3}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 25}, {len, 0} }));
        return ls;
    }

    /** One piece: an influenced 6 s force section + a plain 5 s
     *  tail section. The Δpitch delta lane carries one bump over
     *  [b0, b1] (°/s). */
    static Coaster fixture(double b0, double b1) {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection fs = new ForceSection(hillLanes(6), 26);
        Coaster.enterInfluence(fs, 6);
        double mid = (b0 + b1) / 2;
        fs.deltaLanes.set(0, lane(new double[][]{ {0, 0}, {b0, 0},
            {mid, 6}, {b1, 0}, {6, 0} }));
        p.sections.add(fs);
        p.sections.add(new ForceSection(hillLanes(5), 26));
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

    /** worst nearest-point distance from b's samples to a's polyline. */
    static double pathDev(List<FVDIntegrator.Sample> a,
                          List<FVDIntegrator.Sample> b) {
        double worst = 0;
        int lo = 0;
        for (int i = 0; i < b.size(); i += 3) {
            Vec3 q = b.get(i).pos();
            double best = Double.MAX_VALUE;
            int bestK = lo;
            for (int k = Math.max(0, lo - 80); k + 1 < a.size()
                    && k < lo + 500; k++) {
                Vec3 s0 = a.get(k).pos(), s1 = a.get(k + 1).pos();
                Vec3 d = s1.sub(s0);
                double l2 = d.dot(d);
                double t = l2 < 1e-18 ? 0
                    : Math.max(0, Math.min(1, q.sub(s0).dot(d) / l2));
                double dist = q.sub(s0.add(d.scale(t))).length();
                if (dist < best) { best = dist; bestK = k; }
            }
            lo = bestK;
            worst = Math.max(worst, best);
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        // ---- (a)+(b): deltas ZERO up to the cut at x = 3 ----
        Coaster c = fixture(4.0, 5.5);
        TrackCompiler.Result r0 = rebuild(c);
        boolean ok = c.splitSectionAt(0, 0, 3.0, DT);
        check(ok, "b1 an influenced section splits");
        LaneSection a = (LaneSection) c.pieces.get(0).sections.get(0);
        LaneSection b = (LaneSection) c.pieces.get(0).sections.get(1);
        check(a.influenced() && b.influenced(),
            "a1 both halves come out influenced");
        check(a.vAuthored == a.vDesign && b.vAuthored == b.vDesign,
            "a2 both halves keep the mirrored pair (D102)",
            String.format("left %.2f/%.2f right %.2f/%.2f",
                a.vAuthored, a.vDesign, b.vAuthored, b.vDesign));
        check(a.inflRollGlobal == false && b.inflRollGlobal == false
                && a.deltaLanes != null && b.deltaLanes != null,
            "a3 influence axis flags + delta lanes carried");
        // delta continuity at the boundary: left end == right start
        double dl = BezierBaker.exactAt(a.deltaLanes.get(0), 3.0);
        double dr = BezierBaker.exactAt(b.deltaLanes.get(0), 0.0);
        check(Math.abs(dl - dr) < 1e-9,
            "a4 delta lanes cut continuously",
            String.format("left %.6f right %.6f", dl, dr));
        TrackCompiler.Result r1 = rebuild(c);
        double dev = pathDev(r0.samples(), r1.samples());
        check(dev < 0.08,
            "b2 deltas zero before the cut: normal seam class",
            String.format("worst dev %.4f m", dev));

        // ---- (d) the piece-split chain on top ----
        int spanIdx = 1;
        TrackCompiler.Result rs = rebuild(c);
        int np = c.splitPiece(0, 1, rs.spans().get(spanIdx - 1));
        check(np == 1, "d1 piece split at the fresh boundary");
        TrackCompiler.Result r2 = rebuild(c);
        double dev2 = pathDev(r1.samples(), r2.samples());
        check(dev2 < 1e-6,
            "d2 the D71 boundary split adds nothing",
            String.format("worst dev %.2e m", dev2));

        // ---- (c) deltas NON-ZERO before the cut: the ACCEPTED
        // CORNER (documented in splitSectionAt + CLAUDE.md). The
        // right half's base re-derives from the cut's RESULTING
        // frame — a force-mode base re-solves against gravity
        // there, so the tail moves ~ tail-length x the prefix
        // deltas' integrated rotation (the violent fixture's
        // 6 deg/s bump = ~4.5 deg -> ~11.8 m over ~170 m at D234;
        // a 1 deg/s nudge = ~0.75 deg -> ~1.8 m). c2/c4 are
        // REGRESSION bounds on those measurements. ----
        Coaster c2 = fixture(1.0, 2.5);
        TrackCompiler.Result q0 = rebuild(c2);
        check(c2.splitSectionAt(0, 0, 3.0, DT),
            "c1 splits with deltas before the cut");
        TrackCompiler.Result q1 = rebuild(c2);
        double dev3 = pathDev(q0.samples(), q1.samples());
        check(dev3 < 15.0,
            "c2 violent-prefix tail re-solve (regression bound)",
            String.format("worst dev %.4f m", dev3));
        Coaster c4 = fixture(1.0, 2.5);
        LaneSection ls4 = (LaneSection) c4.pieces.get(0).sections.get(0);
        ls4.deltaLanes.set(0, lane(new double[][]{ {0, 0}, {1.0, 0},
            {1.75, 1}, {2.5, 0}, {6, 0} }));   // a 1 °/s nudge
        TrackCompiler.Result g0 = rebuild(c4);
        check(c4.splitSectionAt(0, 0, 3.0, DT),
            "c3 gentle-prefix split applies");
        TrackCompiler.Result g1 = rebuild(c4);
        double dev4 = pathDev(g0.samples(), g1.samples());
        check(dev4 < 3.0,
            "c4 gentle prefix stays in its measured class",
            String.format("worst dev %.4f m", dev4));

        // ---- (e) divergent legacy pair refuses ----
        Coaster c3 = fixture(4.0, 5.5);
        LaneSection ls3 = (LaneSection) c3.pieces.get(0).sections.get(0);
        ls3.vAuthored = ls3.vDesign + 5;
        check(!c3.splitSectionAt(0, 0, 3.0, DT),
            "e1 divergent vAuthored/vDesign pair refuses");

        System.out.println();
        System.out.println(fail == 0
            ? "ALL INFL-SPLIT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
