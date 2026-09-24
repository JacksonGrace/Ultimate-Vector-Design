import coaster.*;

import java.io.*;
import java.util.*;

/** MergeGate (v19.18, D247) — Coaster.mergeSections, the seam menu's
 *  "Connect sections (merge into one)".
 *
 *  Part 1 recreates the LOST D131 connect fixture (historic Phase2Gate
 *  261 + the v16.20 held-lane strengthening, surviving only in
 *  gates/Phase2Gate.lost-edits.txt): split → connect round-trips the
 *  original EXACTLY (matched seams stay lossless), unlike sections
 *  refuse, the Phase3Gate 55f controls/marks re-cover. It was shown
 *  green on the pre-D247 code first — the matched path and the
 *  control/mark append are untouched by D247 and must stay bitwise.
 *
 *  Part 2 gates the D247 KEEP-BOTH-VALUES rule that supersedes D131's
 *  averaging (and D132's held-side averaging): an unmatched seam whose
 *  values differ becomes a STEP over δ = Coaster.mergeStepLen(domain)
 *  (0.01 s TIME / 0.1 m DISTANCE — the editor's minimum transition):
 *  A's value at seam−δ, B's value at the seam, flat facing handles,
 *  the outer handles keeping their slopes (the D105 dv rescale), the
 *  two nodes broken unless their outer slope is flat; equal values
 *  with differing slopes = ONE broken node carrying both handles
 *  verbatim; a lane A held early gets a flat hold node at seam−δ
 *  (equal values: b0 keeps its value, flat in, out verbatim). The
 *  fallbacks (A's last gap < 2δ mirrors onto B's side; both short →
 *  δ' = max(gap)/2; both zero → the old average) are gated too, as is
 *  the save→load bitwise reload of the δ-spaced pair.
 *
 *  Run: java -cp "target/classes;gates/out" MergeGate */
public class MergeGate {

    static int failures = 0, passes = 0;
    static final double DT = 0.01;
    static final double EPS = Coaster.MERGE_EPS;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++; else passes++;
    }

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** Gentle hills: Gv wave (0.6..1.4), zero lateral/roll (Phase3Gate). */
    static List<List<BezierBaker.Node>> hillLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0, 1}, {dur * 0.2, 0.6}, {dur * 0.5, 1.4},
            {dur * 0.8, 0.6}, {dur, 1}}));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return lanes;
    }

    /** The lost D131 fixture (+ the v16.20 held lane 2). */
    static Coaster d131Fixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> ln = new ArrayList<>();
        ln.add(nodes(new double[][]{ {0, 1}, {0.8, 2.2}, {1.9, 0.6}, {3, 1} }));
        ln.add(nodes(new double[][]{ {0, 0}, {1.9, 0}, {3, 0} }));
        // lane 2 ends EARLY (holds 40 to the end) — the v16.20
        // hold-lane coverage: its dead edge handles must not bend the
        // reconnected join
        ln.add(nodes(new double[][]{ {0, 0}, {1.2, 40} }));
        p.sections.add(new ForceSection(ln, 26));
        c.pieces.add(p);
        return c;
    }

    /** Two adjacent force sections A (2 s) + B (1 s) on one piece. */
    static Coaster pair(List<List<BezierBaker.Node>> la,
                        List<List<BezierBaker.Node>> lb, boolean dist) {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection fa = new ForceSection(la, 26);
        ForceSection fb = new ForceSection(lb, 24);
        if (dist) {
            fa.domain(Section.Domain.DISTANCE);
            fb.domain(Section.Domain.DISTANCE);
        }
        p.sections.add(fa);
        p.sections.add(fb);
        c.pieces.add(p);
        return c;
    }

    static List<List<BezierBaker.Node>> flat3(double dur, double v0) {
        List<List<BezierBaker.Node>> l = new ArrayList<>();
        l.add(nodes(new double[][]{ {0, v0}, {dur, v0} }));
        l.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        l.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return l;
    }

    static List<BezierBaker.Node> lane(Coaster c, int L) {
        return ((LaneSection) c.pieces.get(0).sections.get(0)).lanes.get(L);
    }

    static BezierBaker.Node at(List<BezierBaker.Node> l, double t) {
        for (BezierBaker.Node n : l) if (Math.abs(n.t - t) < 1e-9) return n;
        return null;
    }

    static double maxDev(TrackCompiler.Result r0, TrackCompiler.Result r1) {
        double dev = 0;
        int n = Math.min(r0.samples().size(), r1.samples().size());
        for (int i = 0; i < n; i++) {
            dev = Math.max(dev, r0.samples().get(i).pos()
                .sub(r1.samples().get(i).pos()).length());
        }
        return dev;
    }

    static boolean bitsEqual(TrackCompiler.Result r0, TrackCompiler.Result r1) {
        if (r0.samples().size() != r1.samples().size()) return false;
        for (int i = 0; i < r0.samples().size(); i++) {
            FVDIntegrator.Sample a = r0.samples().get(i), b = r1.samples().get(i);
            if (Double.doubleToLongBits(a.pos().x()) != Double.doubleToLongBits(b.pos().x())
                || Double.doubleToLongBits(a.pos().y()) != Double.doubleToLongBits(b.pos().y())
                || Double.doubleToLongBits(a.pos().z()) != Double.doubleToLongBits(b.pos().z())
                || Double.doubleToLongBits(a.v()) != Double.doubleToLongBits(b.v())
                || Double.doubleToLongBits(a.Gv()) != Double.doubleToLongBits(b.Gv())
                || Double.doubleToLongBits(a.Gl()) != Double.doubleToLongBits(b.Gl())) {
                return false;
            }
        }
        return true;
    }

    static Coaster load(String text) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static String save(Coaster c) {
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3], null);
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        return sw.toString();
    }

    static String fmt(BezierBaker.Node n) {
        return String.format(Locale.US, "(t=%.4f v=%.4f inF=%.3f inDv=%.4f "
            + "outF=%.3f outDv=%.4f sm=%b so=%b lo=%b)", n.t, n.val, n.inFrac,
            n.inDv, n.outFrac, n.outDv, n.smooth, n.straightOut, n.levelOut);
    }

    // ---------------------------------------------------------------
    // PART 1 — the lost D131 fixture (must stay green: matched path
    // + controls/marks append are untouched by D247)
    // ---------------------------------------------------------------
    static void part1() {
        // 1a) split → connect ROUND-TRIPS to the original exactly —
        // WITH deliberately dirtied dead handles on the held lane
        // (v16.20: merge zeroes them; appending them verbatim bent
        // the join, the user's "connect changes the slope")
        Coaster c0 = d131Fixture();
        TrackCompiler.Result r0 = TrackCompiler.compile(c0, DT);
        Coaster c1 = d131Fixture();
        c1.splitSectionAt(0, 0, 1.9, DT);
        LaneSection lh = (LaneSection) c1.pieces.get(0).sections.get(0);
        List<BezierBaker.Node> hz = lh.lanes.get(2);
        hz.get(hz.size() - 1).outDv = 25;    // dead out-handle, left
        LaneSection rh = (LaneSection) c1.pieces.get(0).sections.get(1);
        rh.lanes.get(2).get(0).inDv = -40;   // dead in-handle, right
        boolean ok = c1.mergeSections(0, 0);
        TrackCompiler.Result r1 = TrackCompiler.compile(c1, DT);
        double dev = maxDev(r0, r1);
        check(ok && c1.pieces.get(0).sections.size() == 1
            && r1.samples().size() == r0.samples().size() && dev < 1e-12,
            String.format(Locale.US, "1a split -> connect round-trips the"
            + " original (dev %.2e m, %d/%d samples)", dev,
            r1.samples().size(), r0.samples().size()));
        check(ok && bitsEqual(r0, r1),
            "1b the round-tripped compile is BITWISE the original's");

        // 1c) UNLIKE neighbors refuse (force + geo)
        Coaster c3 = d131Fixture();
        c3.splitSectionAt(0, 0, 1.9, DT);
        LaneSection right = (LaneSection) c3.pieces.get(0).sections.get(1);
        GeoSection geo = new GeoSection(right.lanes, right.vDesign());
        c3.pieces.get(0).sections.set(1, geo);
        check(!c3.mergeSections(0, 0) && c3.pieces.get(0).sections.size() == 2,
            "1c unlike sections (force + geo) refuse");

        // 1d) Phase3Gate 55f — controls/marks partition + re-cover
        {
            Coaster co = new Coaster();
            co.v0 = 24;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection fs = new ForceSection(hillLanes(6), 24);
            SpeedControl sc = new SpeedControl(1, 2);   // [1,3] over cut 2
            sc.speed = 12;
            fs.speedCtls.add(sc);
            fs.speedMarks.add(4.5);
            p.sections.add(fs);
            co.pieces.add(p);
            boolean sp = co.splitSectionAt(0, 0, 2.0, DT);
            boolean merged = sp && co.mergeSections(0, 0);
            LaneSection M = (LaneSection) p.sections.get(0);
            boolean cover = merged && M.speedCtls.size() == 2
                && Math.abs(M.speedCtls.get(0).x0 - 1) < 1e-9
                && Math.abs(M.speedCtls.get(1).x0 + M.speedCtls.get(1).len
                    - 3) < 1e-9
                && M.speedMarks.size() == 1
                && Math.abs(M.speedMarks.get(0) - 4.5) < 1e-9;
            check(cover, "1d controls/marks re-cover [1,3] + the 4.5 mark"
                + " after a split -> merge (Phase3Gate 55f)");
        }

        // 1e) a B-MATCHED hold (equal value, B flat) merges LOSSLESSLY
        // with the dead handles killed (D132): lane 2 of A holds 40
        // from 1.2 s, B's lane 2 is flat 40
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(2, nodes(new double[][]{ {0, 0}, {1.2, 40} }));
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(2, nodes(new double[][]{ {0, 40}, {1, 40} }));
            la.get(2).get(1).outDv = 25;     // dirtied dead out-handle
            lb.get(2).get(0).inDv = -40;     // dirtied dead in-handle
            Coaster c = pair(la, lb, false);
            boolean okm = c.mergeSections(0, 0);
            List<BezierBaker.Node> l2 = lane(c, 2);
            BezierBaker.Node e = at(l2, 1.2), b0 = at(l2, 2.0), b1 = at(l2, 3.0);
            boolean good = okm && l2.size() == 4 && e != null && b0 != null
                && b1 != null && e.outDv == 0 && b0.inDv == 0 && b0.val == 40
                && b0.outDv == 0 && b0.smooth && e.val == 40;
            check(good, "1e B-matched hold merges losslessly: dead handles"
                + " zeroed, b0 keeps 40 flat/smooth"
                + (good ? "" : " " + l2.stream().map(MergeGate::fmt).toList()));
        }
    }

    public static void main(String[] args) {
        part1();
        if (args.length == 0 || !args[0].equals("part1")) part2();
        System.out.println(failures == 0
            ? "\nALL MERGE GATES PASS (" + passes + ")"
            : "\n" + failures + " MERGE GATE FAILURES");
        if (failures > 0) System.exit(1);
    }

    // ---------------------------------------------------------------
    // PART 2 — the D247 keep-both-values step
    // ---------------------------------------------------------------
    /** A: {0,1}->{2,0.6} with a sloped out handle at 0 (outDv 0.3) and a
     *  sloped in handle at 2 (inDv 0.2 → slope −0.3 per unit);
     *  B: {0,1.4}->{1,1} with out slope −0.3 (outDv −0.1). Lanes 1/2
     *  flat zero (matched). */
    static Coaster stepFixture(boolean dist) {
        List<List<BezierBaker.Node>> la = flat3(2, 1);
        la.set(0, nodes(new double[][]{ {0, 1}, {2, 0.6} }));
        la.get(0).get(0).outDv = 0.3;
        la.get(0).get(1).inDv = 0.2;
        List<List<BezierBaker.Node>> lb = flat3(1, 1);
        lb.set(0, nodes(new double[][]{ {0, 1.4}, {1, 1} }));
        lb.get(0).get(0).outDv = -0.1;
        return pair(la, lb, dist);
    }

    static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    static void part2() {
        check(Coaster.mergeStepLen(Section.Domain.TIME) == 0.01
            && Coaster.mergeStepLen(Section.Domain.DISTANCE) == 0.1,
            "2a mergeStepLen = 0.01 s TIME / 0.1 m DISTANCE (the editor's"
            + " minimum transition, MIN_GAP / minGapOf mirrored)");

        // 2b) the STEP on a TIME seam: A's value at seam−δ, B's at the
        // seam, outer slopes kept, flat facing handles, both broken
        {
            Coaster c = stepFixture(false);
            List<BezierBaker.Node> a0 = ((LaneSection) c.pieces.get(0)
                .sections.get(0)).lanes.get(0);
            double sStart = BezierBaker.slopeAt(a0, 1e-9);
            double sEndA = BezierBaker.slopeAt(a0, 2 - 1e-9);
            List<BezierBaker.Node> b0l = ((LaneSection) c.pieces.get(0)
                .sections.get(1)).lanes.get(0);
            double sStartB = BezierBaker.slopeAt(b0l, 1e-9);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l0 = lane(c, 0);
            BezierBaker.Node p0 = at(l0, 0), left = at(l0, 1.99),
                right = at(l0, 2.0), b1 = at(l0, 3.0);
            double r = 1.99 / 2;
            boolean layout = ok && l0.size() == 4 && p0 != null && left != null
                && right != null && b1 != null
                && left.val == 0.6 && right.val == 1.4
                && left.outDv == 0 && left.outFrac == BezierBaker.DEFAULT_FRAC
                && !left.straightOut && !left.levelOut && !left.smooth
                && right.inDv == 0 && right.inFrac == BezierBaker.DEFAULT_FRAC
                && right.outDv == -0.1 && !right.smooth
                && Double.doubleToLongBits(left.inDv)
                    == Double.doubleToLongBits(0.2 * r)
                && Double.doubleToLongBits(p0.outDv)
                    == Double.doubleToLongBits(0.3 * r);
            check(layout, "2b TIME step: nodes at seam-0.01 (0.6) and the"
                + " seam (1.4), flat facing handles, D105 dv rescale, both"
                + " broken, left unlocked"
                + (layout ? "" : " " + l0.stream().map(MergeGate::fmt).toList()));
            // (exactAt solves the cubic's u by bisection — values AT a
            // node land within ~1e-15 of it, hence the tolerances)
            boolean vals = ok && near(BezierBaker.exactAt(l0, 1.99), 0.6, 1e-12)
                && BezierBaker.exactAt(l0, 2.0) == 1.4
                && near(BezierBaker.exactAt(l0, 1.995), 1.0, 1e-9);
            check(vals, "2c exactAt: 0.6 at seam-delta, 1.4 at the seam, the"
                + " symmetric S passes 1.0 at seam-delta/2");
            double sL = BezierBaker.slopeAt(l0, 1.99 - 1e-9);
            double sR = BezierBaker.slopeAt(l0, 2.0 + 1e-9);
            double s0 = BezierBaker.slopeAt(l0, 1e-9);
            check(ok && near(sL, sEndA, 1e-6) && near(sR, sStartB, 1e-6)
                && near(s0, sStart, 1e-6) && near(sEndA, -0.3, 1e-6),
                String.format(Locale.US, "2d slopes kept on both sides of"
                + " the step: %.4f|%.4f (A end %.4f, B start %.4f), A's"
                + " start %.4f", sL, sR, sEndA, sStartB, s0));
            boolean others = ok && lane(c, 1).size() == 3 && lane(c, 2).size() == 3
                && at(lane(c, 1), 2.0) != null && at(lane(c, 1), 2.0).smooth;
            check(others, "2e the matched lanes 1/2 merge losslessly beside"
                + " the stepped lane (3 nodes, smooth seam)");
            TrackCompiler.Result r1 = TrackCompiler.compile(c, DT);
            TrackCompiler.Result r2 = TrackCompiler.compile(c, DT);
            int n = r1.samples().size();
            check(n == 301 && bitsEqual(r1, r2)
                && near(r1.samples().get(n - 1).t(), 3.0, 1e-9),
                String.format(Locale.US, "2f the merged section compiles"
                + " (301 samples, ends at %.4f s), rebuild bitwise",
                r1.samples().get(n - 1).t()));
            // felt Gv reads the step: the sample AT the seam is on B's
            // value, the sample at seam−δ on A's
            int iSeam = -1, iLeft = -1;
            for (int i = 0; i < n; i++) {
                if (near(r1.samples().get(i).t(), 2.0, 1e-6)) iSeam = i;
                if (near(r1.samples().get(i).t(), 1.99, 1e-6)) iLeft = i;
            }
            boolean felt = iSeam >= 0 && iLeft >= 0
                && near(r1.samples().get(iSeam).Gv(), 1.4, 2e-2)
                && near(r1.samples().get(iLeft).Gv(), 0.6, 2e-2);
            check(felt, String.format(Locale.US, "2g the felt Gv steps"
                + " 0.6 -> 1.4 across the seam samples (%.3f | %.3f)",
                iLeft >= 0 ? r1.samples().get(iLeft).Gv() : Double.NaN,
                iSeam >= 0 ? r1.samples().get(iSeam).Gv() : Double.NaN));

            // 2h) save -> load -> rebuild BITWISE (the δ-spaced pair is
            // the first sub-INSERT_GAP spacing the app writes)
            try {
                Coaster c2 = load(save(c));
                TrackCompiler.Result r3 = TrackCompiler.compile(c2, DT);
                List<BezierBaker.Node> l0b = lane(c2, 0);
                check(l0b.size() == 4 && at(l0b, 1.99) != null
                    && bitsEqual(r1, r3),
                    "2h save -> load -> rebuild of the stepped section is"
                    + " bitwise (node spacing 0.01 admitted)");
            } catch (IOException e) {
                check(false, "2h save/load threw " + e);
            }

            // 2i) split AT the step's seam node, then connect: the
            // equal-value/different-slope rule rebuilds the SAME node
            // (in flat, out B's) — round-trips bitwise
            Coaster c3 = stepFixture(false);
            c3.mergeSections(0, 0);
            boolean sp = c3.splitSectionAt(0, 0, 2.0, DT);
            boolean mg = sp && c3.mergeSections(0, 0);
            TrackCompiler.Result r4 = TrackCompiler.compile(c3, DT);
            BezierBaker.Node seam3 = at(lane(c3, 0), 2.0);
            check(mg && seam3 != null && !seam3.smooth && seam3.inDv == 0
                && seam3.outDv == -0.1 && bitsEqual(r1, r4),
                "2i split at the step's seam -> connect round-trips"
                + " bitwise (the broken-node rule)");
        }

        // 2j) EQUAL values, DIFFERING slopes: ONE broken node carrying
        // both handles verbatim (lock flag carried from B)
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(0, nodes(new double[][]{ {0, 1}, {2, 0.6} }));
            la.get(0).get(1).inDv = 0.2;
            la.get(0).get(1).inFrac = 0.4;
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(0, nodes(new double[][]{ {0, 0.6}, {1, 1} }));
            lb.get(0).get(0).outDv = 0.05;
            lb.get(0).get(0).outFrac = 0.25;
            lb.get(0).get(0).straightOut = true;
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l0 = lane(c, 0);
            BezierBaker.Node s = at(l0, 2.0);
            boolean good = ok && l0.size() == 3 && s != null && s.val == 0.6
                && !s.smooth && s.inDv == 0.2 && s.inFrac == 0.4
                && s.outDv == 0.05 && s.outFrac == 0.25 && s.straightOut
                && !s.levelOut;
            check(good, "2j equal values + differing slopes: ONE broken node,"
                + " A's in handle + B's out handle (and lock) verbatim"
                + (good ? "" : " " + l0.stream().map(MergeGate::fmt).toList()));
        }

        // 2k) HELD lane, differing values: a flat hold node at seam-δ,
        // b0 at the seam with a flat in handle + its out handle
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(1, nodes(new double[][]{ {0, 0}, {1.2, 0.3} }));
            la.get(1).get(1).outDv = 7;          // dirtied dead handle
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(1, nodes(new double[][]{ {0, 0.5}, {1, 0} }));
            lb.get(1).get(0).outDv = -0.2;
            lb.get(1).get(0).inDv = 9;           // dirtied dead handle
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l1 = lane(c, 1);
            BezierBaker.Node e = at(l1, 1.2), h = at(l1, 1.99), b0 = at(l1, 2.0);
            boolean good = ok && l1.size() == 5 && e != null && h != null
                && b0 != null && e.outDv == 0 && h.val == 0.3 && h.smooth
                && h.inDv == 0 && h.outDv == 0 && !h.straightOut && !h.levelOut
                && b0.val == 0.5 && b0.inDv == 0 && b0.outDv == -0.2
                && !b0.smooth
                && near(BezierBaker.exactAt(l1, 1.5), 0.3, 1e-12)
                && near(BezierBaker.exactAt(l1, 1.99), 0.3, 1e-12)
                && BezierBaker.exactAt(l1, 2.0) == 0.5;
            check(good, "2k held lane, differing values: flat hold node at"
                + " seam-delta (0.3), b0 at the seam (0.5) in-flat/out-verbatim,"
                + " dead handles killed"
                + (good ? "" : " " + l1.stream().map(MergeGate::fmt).toList()));
        }

        // 2l) HELD lane, EQUAL values but B sloped: b0 keeps its value,
        // flat in, out verbatim, broken; no hold node
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(2, nodes(new double[][]{ {0, 0}, {1.2, 40} }));
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(2, nodes(new double[][]{ {0, 40}, {1, 0} }));
            lb.get(2).get(0).outDv = -10;
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l2 = lane(c, 2);
            BezierBaker.Node b0 = at(l2, 2.0);
            boolean good = ok && l2.size() == 4 && b0 != null && b0.val == 40
                && b0.inDv == 0 && b0.outDv == -10 && !b0.smooth
                && at(l2, 1.99) == null;
            check(good, "2l held lane, equal values + B sloped: b0 keeps 40,"
                + " flat in, out verbatim, broken, no hold node"
                + (good ? "" : " " + l2.stream().map(MergeGate::fmt).toList()));
        }

        // 2m) HELD lane with a TINY hold gap (< 2δ): δ' = gap/2
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(1, nodes(new double[][]{ {0, 0}, {1.995, 0.3} }));
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(1, nodes(new double[][]{ {0, 0.5}, {1, 0} }));
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l1 = lane(c, 1);
            BezierBaker.Node h = at(l1, 1.9975), b0 = at(l1, 2.0);
            check(ok && l1.size() == 5 && h != null && h.val == 0.3
                && b0 != null && b0.val == 0.5,
                "2m held lane with a 0.005 hold gap: hold node at the"
                + " midpoint (delta' = gap/2)");
        }

        // 2n) DISTANCE domain: δ = 0.1 m
        {
            Coaster c = stepFixture(true);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l0 = lane(c, 0);
            BezierBaker.Node left = at(l0, 1.9), right = at(l0, 2.0);
            check(ok && l0.size() == 4 && left != null && right != null
                && left.val == 0.6 && right.val == 1.4
                && ((LaneSection) c.pieces.get(0).sections.get(0)).domain()
                    == Section.Domain.DISTANCE
                && at(l0, 1.99) == null,
                "2n DISTANCE seam: the step spans 0.1 m (nodes at 1.9 m and"
                + " 2.0 m), domain kept");
        }

        // 2o) FALLBACK — A's last segment shorter than 2δ: the step
        // mirrors onto B's side (B's first node moves to seam+δ)
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(0, nodes(new double[][]{ {0, 1}, {1.995, 0.8}, {2, 0.6} }));
            la.get(0).get(2).inDv = 0.05;
            la.get(0).get(2).levelOut = true;    // a dead out lock on A's end
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(0, nodes(new double[][]{ {0, 1.4}, {1, 1} }));
            lb.get(0).get(0).outDv = -0.1;
            lb.get(0).get(1).inDv = 0.02;
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l0 = lane(c, 0);
            BezierBaker.Node mid = at(l0, 1.995), left = at(l0, 2.0),
                right = at(l0, 2.01), b1 = at(l0, 3.0);
            double r = (1.0 - 0.01) / 1.0;     // the model's (gB − δ)/gB bits
            boolean good = ok && l0.size() == 5 && mid != null && left != null
                && right != null && b1 != null
                && left.val == 0.6 && left.inDv == 0.05 && left.outDv == 0
                && !left.levelOut && !left.straightOut
                && right.val == 1.4 && right.inDv == 0
                && right.inFrac == BezierBaker.DEFAULT_FRAC
                && Double.doubleToLongBits(right.outDv)
                    == Double.doubleToLongBits(-0.1 * r)
                && Double.doubleToLongBits(b1.inDv)
                    == Double.doubleToLongBits(0.02 * r)
                && !right.smooth && !left.smooth
                && near(BezierBaker.exactAt(l0, 2.0), 0.6, 1e-12)
                && near(BezierBaker.exactAt(l0, 2.01), 1.4, 1e-12);
            check(good, "2o A's last gap 0.005 < 2delta: step mirrors onto B"
                + " (nodes at 2.0 / 2.01, B's out slope kept, A's dead"
                + " level lock cleared)"
                + (good ? "" : " " + l0.stream().map(MergeGate::fmt).toList()));
        }

        // 2p) FALLBACK — BOTH sides short: δ' = max(gap)/2 on the longer
        {
            List<List<BezierBaker.Node>> la = flat3(2, 1);
            la.set(0, nodes(new double[][]{ {0, 1}, {1.995, 0.8}, {2, 0.6} }));
            List<List<BezierBaker.Node>> lb = flat3(1, 1);
            lb.set(0, nodes(new double[][]{ {0, 1.4}, {0.01, 1.2}, {1, 1} }));
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l0 = lane(c, 0);
            BezierBaker.Node left = at(l0, 2.0), right = at(l0, 2.005),
                nb = at(l0, 2.01);
            check(ok && l0.size() == 6 && left != null && right != null
                && nb != null && left.val == 0.6 && right.val == 1.4
                && nb.val == 1.2,
                "2p both gaps short (0.005 | 0.01): delta' = 0.005 on B's side"
                + " (right node at 2.005)");
        }

        // 2q) LAST RESORT — two zero-duration lanes (single nodes both
        // sides): today's average
        {
            List<List<BezierBaker.Node>> la = new ArrayList<>();
            la.add(nodes(new double[][]{ {0, 0.6} }));
            la.add(nodes(new double[][]{ {0, 0} }));
            la.add(nodes(new double[][]{ {0, 0} }));
            List<List<BezierBaker.Node>> lb = new ArrayList<>();
            lb.add(nodes(new double[][]{ {0, 1.4} }));
            lb.add(nodes(new double[][]{ {0, 0} }));
            lb.add(nodes(new double[][]{ {0, 0} }));
            Coaster c = pair(la, lb, false);
            boolean ok = c.mergeSections(0, 0);
            List<BezierBaker.Node> l0 = lane(c, 0);
            check(ok && l0.size() == 1 && l0.get(0).val == 1.0 && l0.get(0).smooth,
                "2q two one-node lanes (no gap on either side): the old"
                + " average as the last resort");
        }

        // 2r) speed controls/marks of B still append shifted by dA
        // beside a stepped lane (the D160 append is untouched)
        {
            Coaster c = stepFixture(false);
            ForceSection fb = (ForceSection) c.pieces.get(0).sections.get(1);
            SpeedControl sc = new SpeedControl(0.2, 0.5);
            sc.speed = 10;
            fb.speedCtls.add(sc);
            fb.speedMarks.add(0.9);
            boolean ok = c.mergeSections(0, 0);
            LaneSection M = (LaneSection) c.pieces.get(0).sections.get(0);
            check(ok && M.speedCtls.size() == 1
                && near(M.speedCtls.get(0).x0, 2.2, 1e-12)
                && M.speedMarks.size() == 1
                && near(M.speedMarks.get(0), 2.9, 1e-12)
                && M.vDesign() == 26,
                "2r B's control/mark append shifted by dA; the merged"
                + " section keeps A's vDesign");
        }
    }
}
