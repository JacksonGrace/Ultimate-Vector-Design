import coaster.*;

import java.io.*;
import java.util.*;

/** D230 gate — MULTI-COASTER files.
 *  a) two coasters → FVDD4; reload = 2 coasters, names, looks, v0/
 *     friction, pieces bit-exact (compile bitwise), camera.
 *  b) one coaster → FVDD3 (+ name line); an OLD reader shape (no name)
 *     → default name; round-trip byte-identical.
 *  c) empty document → FVDD4 with 0 coasters loads (no exception),
 *     while an FVDD3 file with no track still refuses.
 *  d) coaster with a damaged trailing block drops it (fail-soft) and
 *     keeps the rest. */
public class MultiCoasterGate {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster mk(String name, double v0, double gv2) {
        Coaster c = new Coaster();
        c.name = name;
        c.v0 = v0;
        c.fricRoll = 0.02;
        c.fricAir = 1e-4;
        TrackPiece p = new TrackPiece(3, 20, -1, 12);
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.5, gv2}, {4, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.3}, {4, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {4, 0} }));
        p.sections.add(new ForceSection(ls, v0));
        c.pieces.add(p);
        return c;
    }

    static boolean bitwise(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (Double.doubleToLongBits(a.get(i).pos().x()) != Double.doubleToLongBits(b.get(i).pos().x())
                || Double.doubleToLongBits(a.get(i).pos().y()) != Double.doubleToLongBits(b.get(i).pos().y())
                || Double.doubleToLongBits(a.get(i).v()) != Double.doubleToLongBits(b.get(i).v())) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) two coasters ----
        Coaster c1 = mk("Alpha Run", 22, 2.6), c2 = mk("Beta 2", 15, 1.8);
        DesignIO.Cosmetics d = DesignIO.Cosmetics.defaults();
        DesignIO.Cosmetics l1 = new DesignIO.Cosmetics(1.1, 1.2, 0.8, true,
            new double[]{ 0.1, 0.5, 0.9 }, d.gradColors(), d.gradStops(),
            d.latColors(), d.latStops(), null);
        DesignIO.Cosmetics l2 = new DesignIO.Cosmetics(0.9, 1.0, 1.5, false,
            new double[]{ 0.9, 0.2, 0.2 }, d.gradColors(), d.gradStops(),
            d.latColors(), d.latStops(), null);
        double[] cam = { 1, 2, 3, 40, -20, 55 };
        StringWriter w = new StringWriter();
        DesignIO.saveAll(List.of(c1, c2), List.of(l1, l2), cam, new PrintWriter(w));
        String txt = w.toString();
        check(txt.startsWith("FVDD4"), "a1 two coasters write FVDD4");
        DesignIO.LoadResult r = DesignIO.load(new BufferedReader(new StringReader(txt)));
        check(r.coasters().size() == 2 && r.looks().size() == 2,
            "a2 reload: two coasters + two looks");
        check(r.coasters().get(0).name.equals("Alpha Run")
                && r.coasters().get(1).name.equals("Beta 2"),
            "a3 names survive (spaces + digits)");
        check(r.coasters().get(0).v0 == 22 && r.coasters().get(1).v0 == 15
                && r.coasters().get(1).fricRoll == 0.02,
            "a4 per-coaster v0 / friction");
        check(r.looks().get(0).heart() == 1.1 && r.looks().get(0).railSolid()
                && !r.looks().get(1).railSolid()
                && Math.abs(r.looks().get(1).railColor()[0] - 0.9) < 1e-4
                && r.looks().get(1).tiespace() == 1.5,
            "a5 per-coaster looks (heart, rail mode/color, tie spacing)");
        check(r.camera() != null && r.camera()[3] == 40, "a6 doc-level camera");
        List<FVDIntegrator.Sample> s1 = TrackCompiler.compile(c1, 0.01).samples();
        List<FVDIntegrator.Sample> s2 = TrackCompiler.compile(c2, 0.01).samples();
        List<FVDIntegrator.Sample> t1 = TrackCompiler.compile(r.coasters().get(0), 0.01).samples();
        List<FVDIntegrator.Sample> t2 = TrackCompiler.compile(r.coasters().get(1), 0.01).samples();
        check(bitwise(s1, t1) && bitwise(s2, t2), "a7 both coasters compile BITWISE after reload");
        StringWriter w2 = new StringWriter();
        DesignIO.saveAll(r.coasters(), r.looks(), r.camera(), new PrintWriter(w2));
        check(w2.toString().equals(txt), "a8 FVDD4 round-trip byte-identical");

        // ---- (b) one coaster ----
        StringWriter w3 = new StringWriter();
        DesignIO.save(c1, l1, new PrintWriter(w3));
        String one = w3.toString();
        String nl = System.lineSeparator();
        check(one.startsWith("FVDD3") && one.contains(nl + "name Alpha Run" + nl),
            "b1 one coaster writes FVDD3 with a name line");
        DesignIO.LoadResult r1 = DesignIO.load(new BufferedReader(new StringReader(one)));
        check(r1.coasters().size() == 1 && r1.coaster().name.equals("Alpha Run"),
            "b2 single reload keeps the name");
        String noName = one.replace("name Alpha Run" + nl, "");
        DesignIO.LoadResult r2 = DesignIO.load(new BufferedReader(new StringReader(noName)));
        check(r2.coaster().name.equals("Coaster 1"), "b3 old file (no name) = default name");
        StringWriter w4 = new StringWriter();
        DesignIO.save(r1.coaster(), r1.cosmetics(), new PrintWriter(w4));
        check(w4.toString().equals(one), "b4 FVDD3 round-trip byte-identical");

        // ---- (c) empty document ----
        StringWriter w5 = new StringWriter();
        DesignIO.saveAll(List.of(), List.of(), null, new PrintWriter(w5));
        String empty = w5.toString();
        DesignIO.LoadResult r5 = DesignIO.load(new BufferedReader(new StringReader(empty)));
        check(empty.startsWith("FVDD4") && r5.coasters().isEmpty(),
            "c1 empty document round-trips as FVDD4 with zero coasters (no throw)");
        boolean threw = false;
        try {
            DesignIO.load(new BufferedReader(new StringReader("FVDD3\nv0 10\n")));
        } catch (IOException ex) {
            threw = true;
        }
        check(threw, "c2 FVDD3 with no track still refuses");

        // ---- (d) damaged trailing block ----
        String damaged = txt + "coaster Broken\nv0 9\npiece 0 5 0 0\n";
        DesignIO.LoadResult r6 = DesignIO.load(new BufferedReader(new StringReader(damaged)));
        check(r6.coasters().size() == 2 && r6.warnings() >= 1,
            "d  sectionless trailing coaster drops fail-soft, the rest loads");

        System.out.println();
        System.out.println(fail == 0 ? "ALL MULTI-COASTER GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
