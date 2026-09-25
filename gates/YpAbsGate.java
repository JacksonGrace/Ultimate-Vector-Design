import uvd.*;

import java.util.*;

/** D237 gate — ABSOLUTE YAW/PITCH (v19.8, the rollAbsolute twin for
 *  geo lanes 0/1 under global yaw/pitch): lane 0 = elevation angle
 *  (°), lane 1 = heading (°, reported-left, unwraps past ±180).
 *  a) constant-angle holds read back exactly (projection);
 *  b) a heading ramp to +50° turns LEFT and lands on 50.000;
 *  c) the resulting rates are SMOOTH (the D121 staircase trap);
 *  d) rate → absolute convert reproduces the shape and the fitted
 *     lanes hold real angles (NOT divided by v — the D213 trap —
 *     checked under °/m too);
 *  e) FVDD round-trip: byte-identical, flag survives, compile same;
 *  f) splits stay exact;
 *  g) heading past ±180 keeps authoring continuously (unwrap).
 */
public class YpAbsGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name
            + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name
            + "  (" + detail + ")"); }
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] pnt : pts) l.add(new BezierBaker.Node(pnt[0], pnt[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster ypAbsCoaster(double[][] elev, double[][] head) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(elev));
        ls.add(lane(head));
        ls.add(lane(new double[][]{ {0, 0}, {8, 0} }));
        Coaster c = new Coaster();
        c.v0 = 24;
        c.pieces.add(new TrackPiece(0, 21, 0, 0));
        GeoSection gs = new GeoSection(ls, 24);
        gs.yawPitchGlobal = true;
        gs.ypAbsolute = true;
        c.pieces.get(0).sections.add(gs);
        return c;
    }

    static double headingDegOf(FVDIntegrator.Sample s) {
        return Math.toDegrees(Math.atan2(s.T().z(), s.T().x()));
    }

    static double elevDegOf(FVDIntegrator.Sample s) {
        return Math.toDegrees(Math.asin(Math.max(-1,
            Math.min(1, s.T().y()))));
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) constant-angle holds ----
        Coaster ca = ypAbsCoaster(
            new double[][]{ {0, 10}, {8, 10} },
            new double[][]{ {0, 0}, {8, 0} });
        TrackCompiler.Result ra = TrackCompiler.compile(ca, DT);
        double wE = 0, wH = 0;
        // sample 0 is the ENTRY frame (level) — the lane's 10° is an
        // authored instant snap there, the abs-bank semantics; the
        // projection owns every sample after
        for (int i = 1; i < ra.samples().size(); i++) {
            FVDIntegrator.Sample s = ra.samples().get(i);
            wE = Math.max(wE, Math.abs(elevDegOf(s) - 10));
            wH = Math.max(wH, Math.abs(headingDegOf(s)));
        }
        check(wE < 1e-6 && wH < 1e-6,
            "a1 constant elevation 10° + heading 0 hold exactly",
            String.format(Locale.US, "elev err %.2e° head err %.2e°",
                wE, wH));

        // ---- (b) heading ramp to +50° = a LEFT turn, exact landing ----
        Coaster cb = ypAbsCoaster(
            new double[][]{ {0, 0}, {8, 0} },
            new double[][]{ {0, 0}, {3, 0}, {6, 50}, {8, 50} });
        TrackCompiler.Result rb = TrackCompiler.compile(cb, DT);
        FVDIntegrator.Sample endB =
            rb.samples().get(rb.samples().size() - 1);
        double hEnd = headingDegOf(endB);
        check(Math.abs(hEnd - 50) < 1e-6,
            "b1 heading lane +50° lands exactly, reported-LEFT",
            String.format(Locale.US, "end heading %.6f°", hEnd));
        check(endB.pos().z() > 1,
            "b2 the turn is physically LEFT (+z in the entry frame)",
            String.format(Locale.US, "end z %.2f m", endB.pos().z()));

        // ---- (c) resulting rates smooth (no D121 staircase) ----
        double worstJump = 0;
        double prevRate = Double.NaN;
        for (int i = 1; i < rb.samples().size(); i++) {
            double r1 = (headingDegOf(rb.samples().get(i))
                - headingDegOf(rb.samples().get(i - 1))) / DT;
            if (!Double.isNaN(prevRate)) {
                worstJump = Math.max(worstJump, Math.abs(r1 - prevRate));
            }
            prevRate = r1;
        }
        check(worstJump < 1.5,
            "c1 heading-rate steps stay in the curvature class",
            String.format(Locale.US, "worst %.3f °/s per sample "
                + "(the D121 staircase read ~3.5)", worstJump));

        // ---- (d) rate → ABSOLUTE convert, plain and °/m ----
        List<List<BezierBaker.Node>> rl = new ArrayList<>();
        rl.add(lane(new double[][]{ {0, 3}, {4, -6}, {8, 2} }));
        rl.add(lane(new double[][]{ {0, 0}, {3, 14}, {6, -8}, {8, 0} }));
        rl.add(lane(new double[][]{ {0, 0}, {8, 0} }));
        Coaster cd = new Coaster();
        cd.v0 = 24;
        cd.pieces.add(new TrackPiece(0, 21, 0, 0));
        GeoSection src = new GeoSection(rl, 24);
        src.yawPitchGlobal = true;
        cd.pieces.get(0).sections.add(src);
        TrackCompiler.Result rd = TrackCompiler.compile(cd, DT);
        for (boolean pm : new boolean[]{ false, true }) {
            LaneSection conv = Coaster.convertToLanes(rd.samples(), 24,
                true, DT, 0.05, 0, Section.Domain.TIME,
                false, true, pm, false, FVDIntegrator.Friction.NONE,
                null, 0, false, true);
            check(conv.ypAbsolute && conv.yawPitchGlobal,
                (pm ? "d3" : "d1") + " convert stamps ypAbsolute"
                    + (pm ? " (°/m)" : ""), "");
            // the fitted heading lane must hold REAL degrees — the
            // D213 trap would have divided them by ~24
            double hMax = 0;
            for (BezierBaker.Node n : conv.lanes.get(1)) {
                hMax = Math.max(hMax, Math.abs(n.val));
            }
            double hRef = 0;
            for (FVDIntegrator.Sample s : rd.samples()) {
                hRef = Math.max(hRef, Math.abs(headingDegOf(s)));
            }
            check(Math.abs(hMax - hRef) < 1.0,
                (pm ? "d4" : "d2") + " heading lane holds real degrees"
                    + (pm ? " under °/m (the D213 trap)" : ""),
                String.format(Locale.US, "lane max %.2f° ref %.2f°",
                    hMax, hRef));
            if (!pm) {
                Coaster cc = new Coaster();
                cc.v0 = 24;
                cc.pieces.add(new TrackPiece(0, 21, 0, 0));
                cc.pieces.get(0).sections.add(conv);
                TrackCompiler.Result rc = TrackCompiler.compile(cc, DT);
                double dev = 0;
                int n = Math.min(rd.samples().size(), rc.samples().size());
                for (int i = 0; i < n; i++) {
                    dev = Math.max(dev, rd.samples().get(i).pos()
                        .sub(rc.samples().get(i).pos()).length());
                }
                check(dev < 0.35,
                    "d5 the absolute re-author reproduces the shape",
                    String.format(Locale.US, "dev %.3f m over %.0f m",
                        dev, rd.samples().get(n - 1).s()));
            }
        }

        // ---- (e) IO round-trip ----
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(cb, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        Coaster back = DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
        LaneSection bs = (LaneSection) back.pieces.get(0).sections.get(0);
        check(bs.ypAbsolute && bs.yawPitchGlobal,
            "e1 the flag round-trips (section field 10)", "");
        java.io.StringWriter sw2 = new java.io.StringWriter();
        DesignIO.save(back, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw2));
        check(sw.toString().equals(sw2.toString()),
            "e2 canonical save→load→save byte-identical", "");
        TrackCompiler.Result rBack = TrackCompiler.compile(back, DT);
        boolean bit = rBack.samples().size() == rb.samples().size();
        for (int i = 0; bit && i < rb.samples().size(); i++) {
            bit = rb.samples().get(i).pos()
                .sub(rBack.samples().get(i).pos()).length() == 0;
        }
        check(bit, "e3 reload compiles bitwise", "");

        // ---- (f) split exactness ----
        Coaster cf = ypAbsCoaster(
            new double[][]{ {0, 0}, {2, 12}, {6, -8}, {8, 0} },
            new double[][]{ {0, 0}, {3, 30}, {8, 45} });
        TrackCompiler.Result rf0 = TrackCompiler.compile(cf, DT);
        check(cf.splitSectionAt(0, 0, 4.0, DT), "f1 ypAbs splits", "");
        TrackCompiler.Result rf1 = TrackCompiler.compile(cf, DT);
        double devF = 0;
        int nf = Math.min(rf0.samples().size(), rf1.samples().size());
        for (int i = 0; i < nf; i++) {
            devF = Math.max(devF, rf0.samples().get(i).pos()
                .sub(rf1.samples().get(i).pos()).length());
        }
        // an ABSOLUTE lane's halves read their C1 Hermite one-sided
        // at the cut (Catmull-Rom loses the far knot) — the same
        // sub-mm class an abs-BANK split carries; == authored-as-two
        // exactly, and D118's "one derivative below" acceptance
        check(devF < 5e-4,
            "f2 the split holds the track (abs Hermite-slope class)",
            String.format(Locale.US, "dev %.2e m", devF));

        // ---- (g) heading authors continuously past ±180 ----
        Coaster cg = ypAbsCoaster(
            new double[][]{ {0, 0}, {8, 0} },
            new double[][]{ {0, 0}, {7, 260}, {8, 260} });
        TrackCompiler.Result rg = TrackCompiler.compile(cg, DT);
        // total turned angle = unwrapped heading delta ≈ 260°
        double tot = 0, prevH = headingDegOf(rg.samples().get(0));
        for (int i = 1; i < rg.samples().size(); i++) {
            double h = headingDegOf(rg.samples().get(i));
            double d = h - prevH;
            while (d > 180) d -= 360;
            while (d < -180) d += 360;
            tot += d;
            prevH = h;
        }
        check(Math.abs(tot - 260) < 1e-3,
            "g1 a 260° authored heading turns 260° (unwrap)",
            String.format(Locale.US, "turned %.4f°", tot));

        // ---- (h) PIECE-RELATIVE heading (v19.9, D238 — the user's
        // "yaw always sets the entrance angle to zero" fix: the
        // heading lane's zero is the PIECE entry, continuous across
        // the piece's chain like elevation and bank) ----
        // h1: rate section turns LEFT ~40°, then an abs section
        // HOLDS heading 40 — the track continues STRAIGHT at the
        // world 40° heading (under D237 it turned ANOTHER 40°)
        Coaster ch = new Coaster();
        ch.v0 = 24;
        ch.pieces.add(new TrackPiece(0, 21, 0, 0));
        List<List<BezierBaker.Node>> rlh = new ArrayList<>();
        rlh.add(lane(new double[][]{ {0, 0}, {4, 0} }));
        rlh.add(lane(new double[][]{ {0, 10}, {4, 10} }));   // 40° left
        rlh.add(lane(new double[][]{ {0, 0}, {4, 0} }));
        GeoSection gA = new GeoSection(rlh, 24);
        gA.yawPitchGlobal = true;
        ch.pieces.get(0).sections.add(gA);
        List<List<BezierBaker.Node>> alh = new ArrayList<>();
        alh.add(lane(new double[][]{ {0, 0}, {6, 0} }));
        alh.add(lane(new double[][]{ {0, 40}, {6, 40} }));
        alh.add(lane(new double[][]{ {0, 0}, {6, 0} }));
        GeoSection gB = new GeoSection(alh, 24);
        gB.yawPitchGlobal = true;
        gB.ypAbsolute = true;
        ch.pieces.get(0).sections.add(gB);
        TrackCompiler.Result rh = TrackCompiler.compile(ch, DT);
        TrackCompiler.Span spB = rh.spans().get(1);
        double hMin = 1e9, hMax2 = -1e9;
        for (int i = spB.i0() + 5; i <= spB.i1(); i++) {
            double h = headingDegOf(rh.samples().get(i));
            hMin = Math.min(hMin, h);
            hMax2 = Math.max(hMax2, h);
        }
        check(Math.abs(hMin - 40) < 0.05 && Math.abs(hMax2 - 40) < 0.05,
            "h1 abs heading 40 mid-piece = the WORLD 40° heading",
            String.format(Locale.US, "span heading %.3f..%.3f°",
                hMin, hMax2));
        // h2: piece YAW moves stay rigid — heights bitwise, the
        // heading field rotated wholesale (the D199 contract holds
        // through the D238 shift: chain-psi differences only)
        ch.pieces.get(0).yawDeg = 30;
        TrackCompiler.Result rh2 = TrackCompiler.compile(ch, DT);
        boolean rigid = rh2.samples().size() == rh.samples().size();
        double dHd = 0;
        for (int i = 0; rigid && i < rh.samples().size(); i++) {
            rigid = rh.samples().get(i).pos().y()
                == rh2.samples().get(i).pos().y();
            double d = headingDegOf(rh2.samples().get(i))
                - headingDegOf(rh.samples().get(i)) + 30;
            d -= 360 * Math.round(d / 360);
            dHd = Math.max(dHd, Math.abs(d));
        }
        check(rigid && dHd < 1e-9,
            "h2 piece yaw stays bitwise-rigid on abs-heading track",
            String.format(Locale.US, "y bitwise %s, heading Δ %.2e°",
                rigid, dHd));
        ch.pieces.get(0).yawDeg = 0;
        // h3: SPLIT PIECE at the boundary — the moved abs lane
        // REBASES onto the new piece (chain-psi delta) and the
        // track holds to ulp class
        TrackCompiler.Result rh0 = TrackCompiler.compile(ch, DT);
        int np = ch.splitPiece(0, 1, rh0.spans().get(0));
        check(np == 1, "h3a splitPiece succeeds", "");
        double vB0 = ((LaneSection) ch.pieces.get(1).sections.get(0))
            .lanes.get(1).get(0).val;
        check(Math.abs(vB0) < 0.01,
            "h3b the moved abs lane re-zeroes at the new piece",
            String.format(Locale.US, "lane value %.4f° (was 40)", vB0));
        TrackCompiler.Result rh3 = TrackCompiler.compile(ch, DT);
        // a DETACHED piece keeps its first sample (no continuation
        // drop), so the split compile carries one extra sample that
        // duplicates the boundary — align around it
        int bIdx = rh0.spans().get(0).i1();
        double devH = 0;
        boolean sized = rh3.samples().size() == rh0.samples().size() + 1;
        for (int i = 0; sized && i < rh0.samples().size(); i++) {
            int j = i <= bIdx ? i : i + 1;
            devH = Math.max(devH, rh0.samples().get(i).pos()
                .sub(rh3.samples().get(j).pos()).length());
        }
        check(sized && devH < 1e-9,
            "h3 splitPiece rebases the abs heading (track holds)",
            String.format(Locale.US, "dev %.2e m", devH));
        // h4: a MID-PIECE rate→abs convert extracts PIECE-relative
        // heading — the fitted lane's entry sits at ~40, not 0
        Coaster ch4 = new Coaster();
        ch4.v0 = 24;
        ch4.pieces.add(new TrackPiece(0, 21, 0, 0));
        List<List<BezierBaker.Node>> rlh4 = new ArrayList<>();
        rlh4.add(lane(new double[][]{ {0, 0}, {4, 0} }));
        rlh4.add(lane(new double[][]{ {0, 10}, {4, 10} }));
        rlh4.add(lane(new double[][]{ {0, 0}, {4, 0} }));
        GeoSection g4a = new GeoSection(rlh4, 24);
        g4a.yawPitchGlobal = true;
        ch4.pieces.get(0).sections.add(g4a);
        List<List<BezierBaker.Node>> rlh4b = new ArrayList<>();
        rlh4b.add(lane(new double[][]{ {0, 0}, {6, 0} }));
        rlh4b.add(lane(new double[][]{ {0, 5}, {6, 5} }));
        rlh4b.add(lane(new double[][]{ {0, 0}, {6, 0} }));
        GeoSection g4b = new GeoSection(rlh4b, 24);
        g4b.yawPitchGlobal = true;
        ch4.pieces.get(0).sections.add(g4b);
        TrackCompiler.Result r4pre = TrackCompiler.compile(ch4, DT);
        boolean ok4 = ch4.convertWholeSection(0, 1, true, DT, 0.1, 0,
            Section.Domain.TIME, false, true, false, false, false,
            true);
        LaneSection c4 = (LaneSection) ch4.pieces.get(0).sections.get(1);
        double h40 = c4.lanes.get(1).get(0).val;
        check(ok4 && c4.ypAbsolute && Math.abs(h40 - 40) < 0.5,
            "h4 mid-piece convert extracts PIECE-relative heading",
            String.format(Locale.US, "entry node %.3f° (expect ~40)",
                h40));
        TrackCompiler.Result r4post = TrackCompiler.compile(ch4, DT);
        double dev4 = 0;
        int n4 = Math.min(r4pre.samples().size(), r4post.samples().size());
        for (int i = 0; i < n4; i++) {
            dev4 = Math.max(dev4, r4pre.samples().get(i).pos()
                .sub(r4post.samples().get(i).pos()).length());
        }
        check(dev4 < 0.35,
            "h5 the mid-piece re-author reproduces the shape",
            String.format(Locale.US, "dev %.3f m", dev4));

        System.out.println();
        System.out.println(fail == 0
            ? "ALL YP-ABS GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
