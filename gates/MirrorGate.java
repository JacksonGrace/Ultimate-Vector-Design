import coaster.*;

import java.util.*;

/** D237 gate — MIRROR PIECE (v19.8): mirrorPiece is a pure graph
 *  negation whose compile must be BITWISE the z-negated track in the
 *  entry frame (entry at z=0, heading 0 ⇒ world z-negation):
 *  a) force lanes (Gl + roll swings, a speed control riding along);
 *  b) geo lanes (yaw + roll, incl. global axes);
 *  c) s/c elements (flat arc + tilted-plane arc + both roll modes);
 *  d) an INFLUENCED force section (Δyaw bump — delta lane 1 negates);
 *  e) a tilted entry (design roll 20° — the bank mirrors);
 *  f) mirror twice == the original, bitwise.
 */
public class MirrorGate {

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
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
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

    /** Bitwise mirror comparison: x/y equal, z value-negated, Gv
     *  equal, Gl negated. Returns a failure description or null. */
    static String mirrorCompare(TrackCompiler.Result a,
                                TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) {
            return "sample counts " + a.samples().size()
                + " vs " + b.samples().size();
        }
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample sa = a.samples().get(i);
            FVDIntegrator.Sample sb = b.samples().get(i);
            if (sa.pos().x() != sb.pos().x()
                    || sa.pos().y() != sb.pos().y()
                    || sb.pos().z() != -sa.pos().z()) {
                return String.format(Locale.US,
                    "pos mismatch at %d: (%.17g,%.17g,%.17g) vs"
                    + " (%.17g,%.17g,%.17g)", i,
                    sa.pos().x(), sa.pos().y(), sa.pos().z(),
                    sb.pos().x(), sb.pos().y(), sb.pos().z());
            }
            if (sa.Gv() != sb.Gv() || sb.Gl() != -sa.Gl()) {
                return String.format(Locale.US,
                    "felt mismatch at %d: Gv %.17g/%.17g Gl %.17g/%.17g",
                    i, sa.Gv(), sb.Gv(), sa.Gl(), sb.Gl());
            }
        }
        return null;
    }

    static String identicalCompare(TrackCompiler.Result a,
                                   TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return "counts";
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample sa = a.samples().get(i);
            FVDIntegrator.Sample sb = b.samples().get(i);
            if (Double.doubleToLongBits(sa.pos().x())
                        != Double.doubleToLongBits(sb.pos().x())
                    || Double.doubleToLongBits(sa.pos().y())
                        != Double.doubleToLongBits(sb.pos().y())
                    || sa.pos().z() != sb.pos().z()
                    || sa.Gl() != sb.Gl()) {
                return "sample " + i;
            }
        }
        return null;
    }

    static Coaster forceFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 2.4}, {4.2, 0.4}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.8}, {3.5, -0.5},
            {6, 0.2} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 35}, {4.5, -20},
            {6, 0} }));
        ForceSection fs = new ForceSection(ls, 26);
        SpeedControl ctl = new SpeedControl(1.0, 1.2);
        ctl.speed = 22;
        fs.speedCtls.add(ctl);
        p.sections.add(fs);
        c.pieces.add(p);
        return c;
    }

    static Coaster geoFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 4}, {3, -10}, {6, 6} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 25}, {4.5, -14}, {6, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 40}, {6, -10} }));
        GeoSection gs = new GeoSection(ls, 24);
        p.sections.add(gs);
        c.pieces.add(p);
        return c;
    }

    static Coaster scFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        StraightCurveSection sc = StraightCurveSection.straight(8, 24);
        StraightCurveSection.Element arc = new StraightCurveSection.Element();
        arc.kind = StraightCurveSection.Kind.CURVE;
        arc.radius = 20;
        arc.angleDeg = 75;
        arc.dirDeg = 0;
        arc.smoothIn = 3;
        arc.rollMode = StraightCurveSection.RollMode.GLOBAL;
        arc.rollVal = 28;
        sc.elements.add(arc);
        StraightCurveSection.Element arc2 = new StraightCurveSection.Element();
        arc2.kind = StraightCurveSection.Kind.CURVE;
        arc2.radius = 30;
        arc2.angleDeg = -40;
        arc2.dirDeg = 45;
        arc2.smoothIn = 2;
        arc2.rollMode = StraightCurveSection.RollMode.RATE;
        arc2.rollVal = 15;
        sc.elements.add(arc2);
        p.sections.add(sc);
        c.pieces.add(p);
        return c;
    }

    static Coaster inflFixture() {
        Coaster c = forceFixture();
        ForceSection fs = (ForceSection) c.pieces.get(0).sections.get(0);
        fs.speedCtls.clear();
        Coaster.enterInfluence(fs, 6);
        fs.deltaLanes.set(1, lane(new double[][]{ {0, 0}, {2, 0},
            {3, 9}, {4, 0}, {6, 0} }));
        return c;
    }

    static void runCase(String name, Coaster c) throws Exception {
        TrackCompiler.Result r0 = rebuild(c);
        // deep-copy via IO round trip for the twice test
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        Coaster orig = DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
        check(c.mirrorPiece(0), name + "1 mirror applies", "");
        TrackCompiler.Result rm = rebuild(c);
        String cmp = mirrorCompare(r0, rm);
        check(cmp == null, name + "2 compile == BITWISE z-negation",
            cmp == null ? r0.samples().size() + " samples" : cmp);
        check(c.mirrorPiece(0), name + "3 mirror twice applies", "");
        TrackCompiler.Result r2 = rebuild(c);
        TrackCompiler.Result ro = rebuild(orig);
        String cmp2 = identicalCompare(ro, r2);
        check(cmp2 == null, name + "4 mirror² == identity bitwise",
            cmp2 == null ? "" : cmp2);
    }

    public static void main(String[] args) throws Exception {
        runCase("a", forceFixture());
        runCase("b", geoFixture());
        runCase("c", scFixture());
        runCase("d", inflFixture());
        Coaster e = forceFixture();
        e.pieces.get(0).rollDeg = 20;
        e.pieces.get(0).designRollDeg = 20;
        runCase("e", e);

        System.out.println();
        System.out.println(fail == 0
            ? "ALL MIRROR GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
