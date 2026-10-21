import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** D198 gate — BIT-EXACT save/load determinism, incl. the chaotic
 *  stall-knot regime:
 *   a) settled doc: save -> load -> rebuild reproduces BITWISE, zero
 *      reload reconcile passes;
 *   b) LIVE STALL KNOT (0.1-floor design speed pinned): the chaotic
 *      geometry itself reproduces BITWISE through save/load — the
 *      regime where %.10f decorrelated (the user's screenshot);
 *   c) save -> load -> save stays byte-identical with toString.
 */
public class DetermGate198 {

    static final String FILE = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26 determinism test.fvd";
    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
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

    static Object[] rebuild(Coaster c) {
        TrackCompiler.Result compiled = TrackCompiler.compile(c, DT);
        int used = 0;
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            used++;
            compiled = TrackCompiler.compile(c, DT);
        }
        // EXACT settle tail (v18.28, D200) - mirrors rebuildTrack's
        // at-rest behavior: land every reconciled speed ON its bits
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled, true);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            used++;
            compiled = TrackCompiler.compile(c, DT);
        }
        return new Object[]{ compiled, used };
    }

    /** true when every sample's pos/t/v and the trueV array are
     *  BIT-identical. */
    static boolean bitEqual(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return false;
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            if (Double.doubleToLongBits(x.pos().x()) != Double.doubleToLongBits(y.pos().x())
             || Double.doubleToLongBits(x.pos().y()) != Double.doubleToLongBits(y.pos().y())
             || Double.doubleToLongBits(x.pos().z()) != Double.doubleToLongBits(y.pos().z())
             || Double.doubleToLongBits(x.v()) != Double.doubleToLongBits(y.v())
             || Double.doubleToLongBits(x.t()) != Double.doubleToLongBits(y.t()))
                return false;
        }
        if (a.trueV().length != b.trueV().length) return false;
        for (int i = 0; i < a.trueV().length; i++)
            if (Double.doubleToLongBits(a.trueV()[i]) != Double.doubleToLongBits(b.trueV()[i]))
                return false;
        return true;
    }


    /** The knot text: the on-disk fixture IF it still carries a
     *  sub-1 m/s section — he re-saves test files, and on 2026-08-05
     *  it came back knotless — else the deterministic constructed
     *  knot (KnotFixture), settled and saved. */
    static String fixtureText() throws Exception {
        try {
            String t = new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);
            Coaster c = load(t);
            for (TrackPiece p : c.pieces)
                for (Section s : p.sections)
                    if (s.vDesign() < 1.0) return t;
        } catch (Exception ignore) { }
        Coaster c = KnotFixture.build();
        rebuild(c);
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (s.vDesign() < 1.0) return save(c);
        throw new IllegalStateException("constructed knot failed to stall");
    }

    public static void main(String[] args) throws Exception {
        String text = fixtureText();

        // ---- a) settled doc reproduces bitwise ----
        {
            Coaster c = load(text);
            rebuild(c);                                        // settle
            TrackCompiler.Result session = (TrackCompiler.Result) rebuild(c)[0];
            String s1 = save(c);
            Coaster c2 = load(s1);
            Object[] rb = rebuild(c2);
            check("settled doc: save->load->rebuild is BITWISE",
                bitEqual(session, (TrackCompiler.Result) rb[0])
                    && (Integer) rb[1] == 0,
                "reload passes=" + rb[1]);
        }

        // ---- b) LIVE stall knot reproduces bitwise ----
        {
            Coaster c = load(text);
            // pin the floor-speed section so the knot SURVIVES the
            // reconcile (a deliberate-conflict state the editor allows)
            boolean pinned = false;
            for (TrackPiece p : c.pieces)
                for (Section s : p.sections)
                    if (!pinned && s.vDesign() < 1.0) { s.vPinned(true); pinned = true; }
            TrackCompiler.Result session = (TrackCompiler.Result) rebuild(c)[0];
            // the chaotic regime is DESIGN-side: a section generating
            // at the 0.1 floor (curvature ~ 1/v²) — assert the pin
            // held it there through the rebuild
            double vKnot = Double.NaN;
            for (TrackPiece p : c.pieces)
                for (Section s : p.sections)
                    if (s.vPinned()) vKnot = s.vDesign();
            check("knot fixture is live (pinned floor design speed survives)",
                pinned && vKnot < 1.0, "pinned vDesign=" + vKnot);
            String s1 = save(c);
            Coaster c2 = load(s1);
            Object[] rb = rebuild(c2);
            check("CHAOTIC KNOT: save->load->rebuild is BITWISE",
                bitEqual(session, (TrackCompiler.Result) rb[0])
                    && (Integer) rb[1] == 0,
                "reload passes=" + rb[1]);
        }

        // ---- c) canonical round-trip stays byte-identical ----
        {
            Coaster c = load(text);
            String s1 = save(c);
            String s2 = save(load(s1));
            check("save->load->save byte-identical (toString)", s1.equals(s2), "");
        }

        // ---- d) s/c growth (v20.1, D259 §2.9 m): a straight/curve
        // section with per-element roll lanes — a sloped local °/s lane
        // in a CUT state (shadow + fingerprint), an absolute bank lane
        // and the default — save -> load -> rebuild BITWISE, zero reload
        // passes, save -> load -> save byte-identical ----
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            List<StraightCurveSection.Element> els = new ArrayList<>();
            els.add(StraightCurveSection.Element.straight(10));
            StraightCurveSection.Element arc = StraightCurveSection.Element.curve(20, 90, 0, 0);
            List<BezierBaker.Node> lane = new ArrayList<>();
            for (double[] q : new double[][]{ {0, 0}, {7, 12}, {14, -5}, {22, 30}, {arc.L, 8} }) {
                lane.add(new BezierBaker.Node(q[0], q[1]));
            }
            BezierBaker.applyDefaultHandles(lane);
            lane.get(1).outDv = 3; lane.get(1).inDv = -3;
            arc.roll.installNodes(lane);
            arc.roll.setFlags(false, false, false);
            arc.setLength(20);                      // cut: shadow + fingerprint persist
            els.add(arc);
            StraightCurveSection.Element s2 = StraightCurveSection.Element.straight(10);
            s2.roll.installNodes(BezierBaker.holdLane(0, 10, 25));
            s2.roll.setFlags(true, true, true);     // absolute bank 25°
            els.add(s2);
            p.sections.add(new StraightCurveSection(els, 22));
            c.pieces.add(p);
            // RE-PINNED v20.1 (D259 §3 — the straight/curve invariant): an
            // s/c after an s/c is its own piece behind an AUTOMATIC strict
            // seam (the model append cuts it); the follower's first section
            // is real-anchored, so the k == 0 reconcile re-freezes it like
            // the old in-piece continuation did. An in-piece pair would be
            // split by the loader's §3.2 pass — not a round trip any more.
            c.appendSectionWithInvariant(0, StraightCurveSection.straight(10, 20), DT);
            rebuild(c);
            TrackCompiler.Result session = (TrackCompiler.Result) rebuild(c)[0];
            String s1 = save(c);
            Coaster c2 = load(s1);
            Object[] rb = rebuild(c2);
            StraightCurveSection sc2 = (StraightCurveSection) c2.pieces.get(0).sections.get(0);
            boolean cutKept = sc2.elements.get(1).roll.isCut()
                && sc2.elements.get(1).roll.cut().t() == 20.0
                && sc2.elements.get(1).roll.shadow().size() == 5;
            check("s/c roll lanes (a cut sloped lane + an absolute lane): save->load->rebuild is BITWISE",
                bitEqual(session, (TrackCompiler.Result) rb[0]) && (Integer) rb[1] == 0 && cutKept,
                "reload passes=" + rb[1] + ", cut kept " + cutKept);
            String s2b = save(load(s1));
            check("s/c roll lanes: save->load->save byte-identical (rn / rsh / rcut lines)",
                s1.equals(s2b) && s1.contains("\nrsh ") && s1.contains("\nrcut "), "");
        }

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
