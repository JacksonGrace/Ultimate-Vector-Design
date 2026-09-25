import uvd.*;

import java.util.*;

/** D234/D236 gate — INFLUENCED sections under the ONE-PASS HYBRID
 *  (v19.7, D236 — deltas inject into the section's own solve per RK4
 *  stage; no ref frame, no split anchor).
 *  a) structure: both halves influenced, axis flags carried, delta
 *     lanes cut continuously, vAuthored stays the plain mirror.
 *  b) deltas ZERO up to the cut: split exact (Markov property).
 *  c) deltas NON-ZERO before the cut: ALSO exact — no anchor needed;
 *     the resulting felt is continuous across the new seam.
 *  d) the piece-split chain (section split + D71 boundary split)
 *     adds nothing on top of the section split.
 *  e) a LEGACY divergent pair (vAuthored != vDesign) splits exactly
 *     too — vAuthored is inert in the hybrid.
 *  f) an influenced half SAVE/LOAD round-trips to the same compile.
 *  g) speed controls drive the influenced solve (v19.6 D235 kept).
 *  h) HYBRID acceptance: after the delta window returns to zero the
 *     felt records equal the authored targets (no residual
 *     laterals) and the downstream section carries no ≠v conflict.
 *  z) all-zero deltas = the uninfluenced twin BITWISE (do-no-harm).
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
            "a2 both halves keep the plain vAuthored mirror (hybrid)",
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
        check(dev < 1e-6,
            "b2 deltas zero before the cut: exact split",
            String.format("worst dev %.2e m", dev));

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

        // ---- (c) deltas NON-ZERO before the cut: exact WITHOUT any
        // anchor (the hybrid's Markov property — at D234 this fixture
        // measured 11.8 m of two-pass tail re-solve). ----
        Coaster c2 = fixture(1.0, 2.5);
        TrackCompiler.Result q0 = rebuild(c2);
        check(c2.splitSectionAt(0, 0, 3.0, DT),
            "c1 splits with deltas before the cut");
        TrackCompiler.Result q1 = rebuild(c2);
        double dev3 = pathDev(q0.samples(), q1.samples());
        check(dev3 < 1e-6,
            "c2 violent prefix: exact split (hybrid, no anchor)",
            String.format("worst dev %.2e m", dev3));
        // resulting felt continuous across the new seam (his
        // "slight jump in resulting values")
        TrackCompiler.Span sL = q1.spans().get(0);
        int ib = sL.i1();
        double gJump = Math.abs(q1.samples().get(ib + 1).Gv()
            - q1.samples().get(ib).Gv());
        check(gJump < 0.05,
            "c3 resulting felt continuous at the seam",
            String.format("Gv step %.4f g", gJump));
        Coaster c4 = fixture(1.0, 2.5);
        LaneSection ls4 = (LaneSection) c4.pieces.get(0).sections.get(0);
        ls4.deltaLanes.set(0, lane(new double[][]{ {0, 0}, {1.0, 0},
            {1.75, 1}, {2.5, 0}, {6, 0} }));   // a 1 °/s nudge
        TrackCompiler.Result g0 = rebuild(c4);
        check(c4.splitSectionAt(0, 0, 3.0, DT),
            "c4 gentle-prefix split applies");
        TrackCompiler.Result g1 = rebuild(c4);
        double dev4 = pathDev(g0.samples(), g1.samples());
        check(dev4 < 1e-6,
            "c5 gentle prefix: exact split",
            String.format("worst dev %.2e m", dev4));

        // ---- (e) divergent legacy pair splits exactly (D236 —
        // vAuthored is INERT in the hybrid; the split resets the
        // mirror) ----
        Coaster c3 = fixture(4.0, 5.5);
        LaneSection ls3 = (LaneSection) c3.pieces.get(0).sections.get(0);
        ls3.vAuthored = ls3.vDesign + 5;
        TrackCompiler.Result e0 = rebuild(c3);
        check(c3.splitSectionAt(0, 0, 3.0, DT),
            "e1 divergent legacy pair splits");
        TrackCompiler.Result e1r = rebuild(c3);
        double devE = pathDev(e0.samples(), e1r.samples());
        check(devE < 1e-6,
            "e2 divergent split exact (vAuthored inert)",
            String.format("worst dev %.2e m", devE));

        // ---- (f) influenced half IO round-trip ----
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c2, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        Coaster back = DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
        LaneSection rb = (LaneSection) back.pieces.get(0).sections.get(1);
        check(rb.influenced(), "f1 the influenced half round-trips");
        TrackCompiler.Result q2 = rebuild(back);
        double devIo = pathDev(q1.samples(), q2.samples());
        check(devIo < 1e-9,
            "f2 save/load compiles to the same track",
            String.format("worst dev %.2e m", devIo));

        // ---- (g) SPEED CONTROLS on an influenced section (v19.6,
        // D235 — the D160 exclusion lifted): a const-speed clamp
        // acts on the single hybrid solve ----
        Coaster cg = fixture(2.0, 3.5);   // live deltas
        LaneSection lg = (LaneSection) cg.pieces.get(0).sections.get(0);
        SpeedControl gctl = new SpeedControl(4.0, 1.5);
        gctl.speed = 12;   // hard clamp well below the natural ~17
        lg.speedCtls.add(gctl);
        check(lg.speedControllable(),
            "g1 influenced sections take speed controls");
        TrackCompiler.Result rg = rebuild(cg);
        double vMid = Double.NaN;
        TrackCompiler.Span sg = rg.spans().get(0);
        for (int i = sg.i0(); i <= sg.i1(); i++) {
            if (rg.samples().get(i).t() - sg.t0() >= 5.0) {
                vMid = rg.samples().get(i).v();
                break;
            }
        }
        check(Math.abs(vMid - 12) < 0.6,
            "g2 the control drives the influenced design profile",
            String.format("v@mid-control %.2f (want 12)", vMid));

        // ---- (h) HYBRID acceptance: the user's 2026-08-25 re-spec.
        // Δyaw bump over [1, 2.5]; after the window the felt records
        // must equal the AUTHORED targets (no residual laterals) and
        // the tail section must carry no ≠v conflict. ----
        Coaster ch = new Coaster();
        TrackPiece ph = new TrackPiece(0, 21, 0, 0);
        ForceSection fh = new ForceSection(hillLanes(6), 26);
        Coaster.enterInfluence(fh, 6);
        fh.deltaLanes.set(1, lane(new double[][]{ {0, 0}, {1.0, 0},
            {1.75, 8}, {2.5, 0}, {6, 0} }));   // Δyaw bump, °/s
        ph.sections.add(fh);
        ph.sections.add(new ForceSection(hillLanes(5), 26));
        ch.pieces.add(ph);
        TrackCompiler.Result rh = rebuild(ch);
        TrackCompiler.Span sh = rh.spans().get(0);
        double worstGl = 0, worstGv = 0;
        for (int i = sh.i0(); i <= sh.i1(); i++) {
            FVDIntegrator.Sample s = rh.samples().get(i);
            double t = s.t() - sh.t0();
            if (t < 2.6 || t > 5.9) continue;   // after the window
            worstGv = Math.max(worstGv, Math.abs(s.Gv()
                - BezierBaker.exactAt(fh.lanes.get(0), t)));
            worstGl = Math.max(worstGl, Math.abs(s.Gl()
                - BezierBaker.exactAt(fh.lanes.get(1), t)));
        }
        check(worstGl < 1e-9 && worstGv < 1e-9,
            "h1 felt == authored targets after the delta window",
            String.format("worst dGv %.2e dGl %.2e g", worstGv, worstGl));
        LaneSection tail = (LaneSection) ph.sections.get(1);
        double vSeam = rh.samples().get(sh.i1()).v();
        check(Math.abs(tail.vDesign - vSeam) < 1e-9,
            "h2 no leftover speed conflict downstream",
            String.format("tail vDesign %.4f seam v %.4f",
                tail.vDesign, vSeam));

        // ---- (z) all-zero deltas == the uninfluenced twin BITWISE ----
        Coaster cz = new Coaster();
        TrackPiece pz = new TrackPiece(0, 21, 0, 0);
        ForceSection fz = new ForceSection(hillLanes(6), 26);
        Coaster.enterInfluence(fz, 6);   // deltas stay flat zero
        pz.sections.add(fz);
        cz.pieces.add(pz);
        Coaster cu = new Coaster();
        TrackPiece pu = new TrackPiece(0, 21, 0, 0);
        pu.sections.add(new ForceSection(hillLanes(6), 26));
        cu.pieces.add(pu);
        TrackCompiler.Result rz = rebuild(cz);
        TrackCompiler.Result ru = rebuild(cu);
        boolean bit = rz.samples().size() == ru.samples().size();
        if (bit) {
            for (int i = 0; i < rz.samples().size(); i++) {
                Vec3 pa = rz.samples().get(i).pos();
                Vec3 pb = ru.samples().get(i).pos();
                if (pa.x() != pb.x() || pa.y() != pb.y()
                        || pa.z() != pb.z()) { bit = false; break; }
            }
        }
        check(bit, "z1 all-zero deltas compile BITWISE == uninfluenced");

        System.out.println();
        System.out.println(fail == 0
            ? "ALL INFL-SPLIT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
