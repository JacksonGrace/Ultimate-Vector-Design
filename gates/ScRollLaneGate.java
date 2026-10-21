package uvd;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** D259 §2.9 gate — the ROLL LANE PER s/c ELEMENT (Stage 1b, v20.1;
 *  D4 as revised 2026-10-21). Fixtures are built in code; fixture F =
 *  [straight 10, planar R20 90° dir 0, straight 10] at 20 m/s from
 *  (0, 21, 0), level entry, unless a row says otherwise. Rows:
 *  a) default lanes: the spine within 1e-9 m of ElementChain's border
 *     poses, every sample's bank 0 ± 1e-12;
 *  b) semantics: a 30 m level straight as s/c vs a GEO section (pitch
 *     0, yaw 0 — a force section with Gv 1 bends under a bank, so the
 *     geo twin is the straight's exact lane-section reference) carrying
 *     the SAME lane per triple {local °/s, local °/m, global °/s, global
 *     °/m, abs} with a 0→40 °/s / 0→2 °/m / 0→25° ramp: per-sample
 *     bank ≤ 1e-6 rad; constant lanes ≤ 1e-12;
 *  c) spine: F under the five triples (ramped) within 1e-9 m of the
 *     default; BITWISE between any two flat triples;
 *  d) jolt-free (D25): F with a 2 m blend and a global °/m ramp 0→2
 *     across border 1 — second difference of the resulting roll rate
 *     ≤ 0.5 °/s per sample, no first difference beyond the authored
 *     ramp's + 1e-3;
 *  e) the cut / shadow state machine e1-e9 (bitwise / 1e-12 / exact);
 *  f) isolation: resize element 0 ±5 m + edit its lane — elements 1/2's
 *     nodes + keyframes BITWISE, their twist ≤ 1e-9 rad;
 *  g) borders: global→global continuous, rate→ABS snap at the landing,
 *     ABS→global re-bind, local→global, a merged border vs its twins;
 *  h) the merge rule: flat equal lanes merge (a split bitwise), a value
 *     step / differing flags land, a sloped split ≤ 1e-9 m / 1e-6 rad;
 *  i) copy / mirror / undo-style round trips in a CUT state (bitwise);
 *  j) B edges — editor code: SKIPPED (run 2);
 *  k) Switch (flags only) / Bake (Coaster.bakeElementRoll) — elements
 *     bitwise, residual ≤ 0.5° (abs) / ≤ 2 % of range (rate), the
 *     shadow dropped;
 *  l) C32: makeGraphsReal on an s/c with a local °/s ramp at a true
 *     entry of 15 m/s — bank per arc ≤ 1e-3 rad of before, °/m and abs
 *     elements bitwise;
 *  m) IO: save → load → rebuild bitwise incl. the shadow + fingerprint,
 *     the missing-`rn` default, the Stage-1a rm/rv migration, damaged
 *     rsh / rcut halves dropped + counted, rollabs forcing rollg;
 *  n) the legacy switch is GONE (reflection) + the level unbanked
 *     cardinal fixtures of LegacyAxisProbe match legacy-checksums.txt
 *     within 1e-9 m.
 *
 *  Compile + run (from the project root):
 *    javac -encoding UTF-8 -cp target/classes -d gates/out gates/ScRollLaneGate.java
 *    java -cp "target/classes;gates/out" uvd.ScRollLaneGate   (':' on Linux)
 */
public final class ScRollLaneGate {

    private ScRollLaneGate() {}

    static final double DT = 0.01;
    static final double V = 20;
    static final Vec3 P0 = new Vec3(0, 21, 0);
    static final String LEGACY_DIR = "gates/probes/legacy-axis-2026-10-21";

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name
            + (detail == null || detail.isEmpty() ? "" : "  (" + detail + ")"));
    }

    static void info(String name, String detail) {
        System.out.println("INFO  " + name + "  (" + detail + ")");
    }

    static String f(String fmt, Object... a) { return String.format(Locale.US, fmt, a); }

    // ---------------------------------------------------------------
    // shared helpers
    // ---------------------------------------------------------------

    static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    static boolean vecBits(Vec3 a, Vec3 b) {
        return bits(a.x(), b.x()) && bits(a.y(), b.y()) && bits(a.z(), b.z());
    }

    static double[] sampleFields(FVDIntegrator.Sample s) {
        return new double[] { s.t(), s.s(), s.pos().x(), s.pos().y(), s.pos().z(),
            s.T().x(), s.T().y(), s.T().z(), s.U().x(), s.U().y(), s.U().z(),
            s.R().x(), s.R().y(), s.R().z(), s.v(), s.Gv(), s.Gl() };
    }

    static final String[] SAMPLE_FIELD_NAMES = { "t", "s", "pos.x", "pos.y", "pos.z",
        "T.x", "T.y", "T.z", "U.x", "U.y", "U.z", "R.x", "R.y", "R.z", "v", "Gv", "Gl" };

    /** null = every sample field bitwise equal. */
    static String sampleDiff(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return "sample counts " + a.size() + " vs " + b.size();
        for (int i = 0; i < a.size(); i++) {
            double[] x = sampleFields(a.get(i)), y = sampleFields(b.get(i));
            for (int k = 0; k < x.length; k++) {
                if (!bits(x[k], y[k])) {
                    return f("sample %d %s %.17g vs %.17g", i, SAMPLE_FIELD_NAMES[k], x[k], y[k]);
                }
            }
        }
        return null;
    }

    static double maxPosDiff(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return Double.POSITIVE_INFINITY;
        double w = 0;
        for (int i = 0; i < a.size(); i++) {
            w = Math.max(w, a.get(i).pos().sub(b.get(i).pos()).length());
        }
        return w;
    }

    static String resultDiff(TrackCompiler.Result a, TrackCompiler.Result b) {
        String d = sampleDiff(a.samples(), b.samples());
        if (d != null) return d;
        if (a.trueV().length != b.trueV().length) return "trueV lengths";
        for (int i = 0; i < a.trueV().length; i++) {
            if (!bits(a.trueV()[i], b.trueV()[i])) return "trueV " + i;
        }
        if (a.spans().size() != b.spans().size()) return "span counts";
        return null;
    }

    /** The bank vs the horizon of a sample (rad; NaN vertical). */
    static double bank(FVDIntegrator.Sample s) {
        return FVDIntegrator.bankOfTU(s.T(), s.U());
    }

    static double bank(Quat q) {
        return FVDIntegrator.bankOfTU(q.T(), q.U());
    }

    /** Max |bank_a − bank_b| by sample index (counts must match). */
    static double maxBankDiff(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return Double.POSITIVE_INFINITY;
        double w = 0;
        for (int i = 0; i < a.size(); i++) {
            double x = bank(a.get(i)), y = bank(b.get(i));
            if (Double.isNaN(x) || Double.isNaN(y)) continue;
            w = Math.max(w, Math.abs(wrap(x - y)));
        }
        return w;
    }

    static double wrap(double r) {
        while (r > Math.PI) r -= 2 * Math.PI;
        while (r < -Math.PI) r += 2 * Math.PI;
        return r;
    }

    /** The BODY roll rate (°/s) per sample off the frames — the fit
     *  engine's ±1-sample differencing (−dU·R/ds · v). NaN at the ends. */
    static double[] bodyRollRate(List<FVDIntegrator.Sample> s) {
        double[] out = new double[s.size()];
        Arrays.fill(out, Double.NaN);
        for (int i = 1; i < s.size() - 1; i++) {
            FVDIntegrator.Sample a = s.get(i - 1), b = s.get(i + 1), m = s.get(i);
            double ds = b.s() - a.s();
            if (ds < 1e-9) continue;
            Vec3 dU = b.U().sub(a.U());
            out[i] = Math.toDegrees(-dU.dot(m.R()) / ds) * m.v();
        }
        return out;
    }

    static List<BezierBaker.Node> nodes(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** A straight-line (exactly linear) two-node lane from v0 to v1. */
    static List<BezierBaker.Node> linearLane(double x1, double v0, double v1) {
        List<BezierBaker.Node> l = nodes(new double[][] { {0, v0}, {x1, v1} });
        double slope = (v1 - v0) / x1;
        l.get(0).outDv = slope * BezierBaker.effOutDt(l.get(0), l.get(1));
        l.get(1).inDv = slope * BezierBaker.effInDt(l.get(0), l.get(1));
        return l;
    }

    static StraightCurveSection.Element setLane(StraightCurveSection.Element e,
            List<BezierBaker.Node> lane, boolean global, boolean perMeter, boolean abs) {
        e.roll.installNodes(lane);
        e.roll.setFlags(global, perMeter, abs);
        return e;
    }

    static StraightCurveSection.Element flat(StraightCurveSection.Element e, double v,
            boolean global, boolean perMeter, boolean abs) {
        return setLane(e, BezierBaker.holdLane(0, e.L, v), global, perMeter, abs);
    }

    /** Fixture F: straight 10, planar R20 90° dir 0, straight 10; 20 m/s. */
    static StraightCurveSection F() {
        List<StraightCurveSection.Element> els = new ArrayList<>();
        els.add(StraightCurveSection.Element.straight(10));
        els.add(StraightCurveSection.Element.curve(20, 90, 0, 0));
        els.add(StraightCurveSection.Element.straight(10));
        return new StraightCurveSection(els, V);
    }

    static StraightCurveSection scOf(double v, StraightCurveSection.Element... els) {
        List<StraightCurveSection.Element> l = new ArrayList<>();
        for (StraightCurveSection.Element e : els) l.add(e);
        return new StraightCurveSection(l, v);
    }

    static ScIntegrator.Result integ(StraightCurveSection sc) {
        return ScIntegrator.integrate(sc, DT, sc.vDesign, P0, Quat.IDENTITY);
    }

    static Coaster docOf(Section... secs) {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        for (Section s : secs) p.sections.add(s);
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

    static String save(Coaster c) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            DesignIO.save(c, DesignIO.Cosmetics.defaults(), pw);
        }
        return sw.toString();
    }

    static DesignIO.LoadResult loadText(String text) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(text)));
    }

    static List<BezierBaker.Node> copyLane(List<BezierBaker.Node> l) {
        return RollLane.deepCopy(l);
    }

    static String nodesDiff(List<BezierBaker.Node> a, List<BezierBaker.Node> b) {
        if (a.size() != b.size()) return "node counts " + a.size() + " vs " + b.size();
        for (int i = 0; i < a.size(); i++) {
            BezierBaker.Node x = a.get(i), y = b.get(i);
            if (!bits(x.t, y.t)) return f("node %d t %.17g vs %.17g", i, x.t, y.t);
            if (!bits(x.val, y.val)) return f("node %d val %.17g vs %.17g", i, x.val, y.val);
            if (!bits(x.outFrac, y.outFrac) || !bits(x.outDv, y.outDv)) return "node " + i + " out handle";
            if (!bits(x.inFrac, y.inFrac) || !bits(x.inDv, y.inDv)) return "node " + i + " in handle";
            if (x.smooth != y.smooth) return "node " + i + " smooth";
            if (x.straightOut != y.straightOut || x.levelOut != y.levelOut) return "node " + i + " lock";
        }
        return null;
    }

    static String laneDiff(RollLane a, RollLane b) {
        if (a.rollGlobal() != b.rollGlobal() || a.ratePerMeter() != b.ratePerMeter()
                || a.rollAbsolute() != b.rollAbsolute()) return "flags";
        String d = nodesDiff(a.nodes, b.nodes);
        if (d != null) return "lane " + d;
        if ((a.shadow() == null) != (b.shadow() == null)) return "shadow presence";
        if (a.shadow() != null) {
            d = nodesDiff(a.shadow(), b.shadow());
            if (d != null) return "shadow " + d;
            if (!cutEq(a.cut(), b.cut())) return "cut fingerprint";
        }
        return null;
    }

    static boolean cutEq(RollLane.Cut x, RollLane.Cut y) {
        if (x == null || y == null) return x == y;
        return x.inserted() == y.inserted() && bits(x.t(), y.t()) && bits(x.val(), y.val())
            && bits(x.inSlope(), y.inSlope()) && bits(x.outSlope(), y.outSlope())
            && x.smooth() == y.smooth() && bits(x.prevOutSlope(), y.prevOutSlope());
    }

    static String keyframesDiff(List<FVDIntegrator.Keyframe> a, List<FVDIntegrator.Keyframe> b) {
        if (a.size() != b.size()) return "keyframe counts " + a.size() + " vs " + b.size();
        for (int i = 0; i < a.size(); i++) {
            FVDIntegrator.Keyframe x = a.get(i), y = b.get(i);
            if (!bits(x.t(), y.t()) || !bits(x.Gv(), y.Gv()) || !bits(x.Gl(), y.Gl())
                    || !bits(x.rollRateDeg(), y.rollRateDeg())) return "keyframe " + i;
        }
        return null;
    }

    /** The 5-node sloped lane of the cut rows over [0, L]. */
    static List<BezierBaker.Node> slopedLane(double L) {
        List<BezierBaker.Node> l = nodes(new double[][] { {0, 0}, {7, 12}, {14, -5}, {22, 30}, {L, 8} });
        l.get(1).outDv = 3; l.get(1).inDv = -3;
        l.get(2).outDv = -2; l.get(2).inDv = 2;
        return l;
    }

    /** The lane value (°/m or °/s or °) at the section-local arc s. */
    static double laneValueAt(StraightCurveSection sc, double s) {
        double at = 0;
        for (int i = 0; i < sc.elements.size(); i++) {
            StraightCurveSection.Element e = sc.elements.get(i);
            if (s < at + e.L || i == sc.elements.size() - 1) {
                return e.roll.valueAt(s - at);
            }
            at += e.L;
        }
        return 0;
    }

    /** Linear interpolation of the bank at arc s off a sample list. */
    static double bankAtArc(List<FVDIntegrator.Sample> ss, double s) {
        for (int i = 0; i + 1 < ss.size(); i++) {
            FVDIntegrator.Sample a = ss.get(i), b = ss.get(i + 1);
            if (s >= a.s() && s <= b.s()) {
                double u = b.s() - a.s() < 1e-12 ? 0 : (s - a.s()) / (b.s() - a.s());
                double ba = bank(a), bb = bank(b);
                return ba + wrap(bb - ba) * u;
            }
        }
        return Double.NaN;
    }

    // ---------------------------------------------------------------
    // a — default lanes
    // ---------------------------------------------------------------

    static void rowA() {
        StraightCurveSection sc = F();
        ScIntegrator.Result r = integ(sc);
        double wPos = 0, wBank = 0;
        for (ScIntegrator.Bound b : r.bounds()) {
            wPos = Math.max(wPos, b.pos().sub(b.sharpPos()).length());
        }
        for (FVDIntegrator.Sample s : r.samples()) wBank = Math.max(wBank, Math.abs(bank(s)));
        check(wPos <= 1e-9 && wBank <= 1e-12,
            "ScRollLaneGate a F with default lanes (global °/m 0): spine within 1e-9 m of ElementChain's border poses, every sample's bank 0 ± 1e-12",
            f("worst border %.2e m, worst bank %.2e rad, %d samples", wPos, wBank, r.samples().size()));
    }

    // ---------------------------------------------------------------
    // b — semantics vs a lane section carrying the SAME lane
    // ---------------------------------------------------------------

    record Triple(String name, boolean global, boolean perMeter, boolean abs) {}

    static final Triple[] TRIPLES = {
        new Triple("local °/s", false, false, false),
        new Triple("local °/m", false, true, false),
        new Triple("global °/s", true, false, false),
        new Triple("global °/m", true, true, false),
        new Triple("absolute bank", true, true, true),
    };

    static double rampEnd(Triple t) {
        return t.abs ? 25 : t.perMeter ? 2 : 40;
    }

    static double flatValue(Triple t) {
        return t.abs ? 15 : t.perMeter ? 0.5 : 10;
    }

    static GeoSection geoTwin(List<BezierBaker.Node> lane, double len, Triple t) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(nodes(new double[][] { {0, 0}, {len, 0} }));
        ls.add(nodes(new double[][] { {0, 0}, {len, 0} }));
        ls.add(copyLane(lane));
        GeoSection g = new GeoSection(ls, V);
        g.domain(Section.Domain.DISTANCE);
        g.rollGlobal = t.global;
        g.ratePerMeter = t.perMeter;
        g.rollAbsolute = t.abs;
        return g;
    }

    static void rowB() {
        double len = 30;
        for (Triple t : TRIPLES) {
            for (boolean ramp : new boolean[] { true, false }) {
                List<BezierBaker.Node> lane = ramp
                    ? nodes(new double[][] { {0, 0}, {len, rampEnd(t)} })
                    : BezierBaker.holdLane(0, len, flatValue(t));
                StraightCurveSection sc = scOf(V, setLane(
                    StraightCurveSection.Element.straight(len), lane, t.global, t.perMeter, t.abs));
                TrackCompiler.Result rs = TrackCompiler.compile(docOf(sc), DT);
                TrackCompiler.Result rg = TrackCompiler.compile(docOf(geoTwin(lane, len, t)), DT);
                List<FVDIntegrator.Sample> a = rs.samples(), b = rg.samples();
                double wS = 0, wB = 0;
                boolean same = a.size() == b.size();
                for (int i = 0; same && i < a.size(); i++) {
                    wS = Math.max(wS, Math.abs(a.get(i).s() - b.get(i).s()));
                    wB = Math.max(wB, Math.abs(wrap(bank(a.get(i)) - bank(b.get(i)))));
                }
                double bound = ramp ? 1e-6 : 1e-12;
                double endBank = Math.toDegrees(bank(a.get(a.size() - 1)));
                check(same && wS <= 1e-9 && wB <= bound,
                    f("ScRollLaneGate b %s, %s lane: s/c straight == geo straight with the same lane, per-sample bank ≤ %.0e rad",
                        t.name, ramp ? "0→" + rampEnd(t) + " ramp" : "flat " + flatValue(t), bound),
                    f("%d/%d samples, worst Δs %.2e m, worst Δbank %.2e rad, end bank %.4f°", a.size(), b.size(), wS, wB, endBank));
            }
        }
    }

    // ---------------------------------------------------------------
    // c — the spine under every triple
    // ---------------------------------------------------------------

    static StraightCurveSection fWithTriple(Triple t, boolean ramp) {
        StraightCurveSection sc = F();
        StraightCurveSection.Element e1 = sc.elements.get(1);
        List<BezierBaker.Node> lane = ramp
            ? nodes(new double[][] { {0, 0}, {e1.L, rampEnd(t)} })
            : BezierBaker.holdLane(0, e1.L, 0);
        setLane(e1, lane, t.global, t.perMeter, t.abs);
        for (int i : new int[] { 0, 2 }) sc.elements.get(i).roll.setFlags(t.global, t.perMeter, t.abs);
        return sc;
    }

    static void rowC() {
        ScIntegrator.Result base = integ(F());
        double worst = 0;
        String worstName = "";
        for (Triple t : TRIPLES) {
            ScIntegrator.Result r = integ(fWithTriple(t, true));
            double w = maxPosDiff(base.samples(), r.samples());
            if (w > worst) { worst = w; worstName = t.name; }
        }
        check(worst <= 1e-9,
            "ScRollLaneGate c F under the five triples (element 1 ramped): positions within 1e-9 m of the default's spine",
            f("worst %.2e m (%s)", worst, worstName));
        List<ScIntegrator.Result> flats = new ArrayList<>();
        for (Triple t : TRIPLES) flats.add(integ(fWithTriple(t, false)));
        String d = null;
        for (int i = 0; d == null && i < flats.size(); i++) {
            for (int j = i + 1; d == null && j < flats.size(); j++) {
                String x = sampleDiff(flats.get(i).samples(), flats.get(j).samples());
                if (x != null) d = TRIPLES[i].name + " vs " + TRIPLES[j].name + ": " + x;
            }
        }
        String d0 = sampleDiff(base.samples(), flats.get(3).samples());
        check(d == null && d0 == null,
            "ScRollLaneGate c F under every FLAT-0 triple: BITWISE identical samples (and == the default)",
            d == null ? (d0 == null ? "5 triples bitwise" : d0) : d);
    }

    // ---------------------------------------------------------------
    // d — jolt-free blends (D25)
    // ---------------------------------------------------------------

    static void rowD() {
        StraightCurveSection sc = F();
        sc.elements.get(1).blendIn = 2;
        setLane(sc.elements.get(0), nodes(new double[][] { {0, 0}, {10, 2} }), true, true, false);
        setLane(sc.elements.get(1), BezierBaker.holdLane(0, sc.elements.get(1).L, 2), true, true, false);
        setLane(sc.elements.get(2), BezierBaker.holdLane(0, 10, 2), true, true, false);
        ScIntegrator.Result r = integ(sc);
        List<FVDIntegrator.Sample> ss = r.samples();
        double[] rate = bodyRollRate(ss);
        double maxD1 = 0, maxD2 = 0, maxAuth = 0;
        for (int i = 2; i < ss.size() - 2; i++) {
            double s = ss.get(i).s();
            if (s < 6 || s > 14) continue;   // the window around border 1 (blend ±1 m)
            double d1 = rate[i + 1] - rate[i];
            double d2 = rate[i + 1] - 2 * rate[i] + rate[i - 1];
            maxD1 = Math.max(maxD1, Math.abs(d1));
            maxD2 = Math.max(maxD2, Math.abs(d2));
            double a0 = laneValueAt(sc, ss.get(i).s()) * ss.get(i).v();
            double a1 = laneValueAt(sc, ss.get(i + 1).s()) * ss.get(i + 1).v();
            maxAuth = Math.max(maxAuth, Math.abs(a1 - a0));
        }
        check(maxD2 <= 0.5 && maxD1 <= maxAuth + 1e-3,
            "ScRollLaneGate d F, 2 m blend on border 1, global °/m ramp 0→2 across it: resulting roll rate's second difference ≤ 0.5 °/s per sample, first difference ≤ the authored ramp's + 1e-3 (no jolt, D25)",
            f("max |Δ²| %.3e °/s, max |Δ| %.4f vs authored %.4f °/s per sample", maxD2, maxD1, maxAuth));
    }

    // ---------------------------------------------------------------
    // e — the cut / shadow state machine
    // ---------------------------------------------------------------

    static StraightCurveSection.Element e1Fixture() {
        StraightCurveSection.Element e = StraightCurveSection.Element.curve(20, 90, 0, 0);
        setLane(e, slopedLane(e.L), false, false, false);
        return e;
    }

    static void rowE() {
        double L = 10 * Math.PI;
        StraightCurveSection.Element e = e1Fixture();
        List<BezierBaker.Node> orig = copyLane(e.roll.nodes);
        // e1 shrink to 20: the curve over [0, 20] unchanged, the cut node last
        e.setLength(20);
        double w = 0;
        for (int i = 0; i <= 1000; i++) {
            double x = 20.0 * i / 1000;
            w = Math.max(w, Math.abs(BezierBaker.exactAt(e.roll.nodes, x) - BezierBaker.exactAt(orig, x)));
        }
        BezierBaker.Node last = e.roll.nodes.get(e.roll.nodes.size() - 1);
        check(w <= 1e-12 && bits(last.t, 20.0) && e.roll.isCut() && e.roll.cut().inserted()
                && e.roll.shadow().size() == 5,
            "ScRollLaneGate e1 shrink 10π → 20: cut on the curve (1000 samples ≤ 1e-12 of the original over [0, 20]), the cut node last at t = 20, inserted, the shadow = the 5-node original, the stub flag (isCut) on",
            f("curve dev %.2e, last t %s, cut %s", w, last.t, e.roll.cut()));
        // e2 grow back: bitwise the original, shadow null
        e.setLength(L);
        String d = nodesDiff(e.roll.nodes, orig);
        check(d == null && !e.roll.isCut() && e.roll.cut() == null,
            "ScRollLaneGate e2 grow back to 10π: BITWISE the original list, shadow + fingerprint null",
            d == null ? "5 nodes bitwise" : d);
        // e3 shrink 20 → 12 → grow 16 == cut(original, 16)
        e.setLength(20); e.setLength(12); e.setLength(16);
        StraightCurveSection.Element ref = e1Fixture();
        ref.setLength(16);
        d = nodesDiff(e.roll.nodes, ref.roll.nodes);
        String ds = nodesDiff(e.roll.shadow(), orig);
        check(d == null && ds == null && cutEq(e.roll.cut(), ref.roll.cut()),
            "ScRollLaneGate e3 shrink 20 → 12 → grow 16: nodes == cut(original, 16) bitwise, the shadow == the original, the fingerprint == a direct cut's",
            d == null ? (ds == null ? "bitwise" : "shadow: " + ds) : d);
        e.setLength(L);
        d = nodesDiff(e.roll.nodes, orig);
        check(d == null && !e.roll.isCut(), "ScRollLaneGate e3 then grow back: bitwise the original", d == null ? "" : d);
        // e4 shrink exactly onto a node's t (14): inserted == false, grow → bitwise
        e.setLength(14);
        boolean onNode = e.roll.isCut() && !e.roll.cut().inserted() && e.roll.nodes.size() == 3
            && bits(e.roll.nodes.get(2).t, 14.0);
        e.setLength(L);
        d = nodesDiff(e.roll.nodes, orig);
        check(onNode && d == null && !e.roll.isCut(),
            "ScRollLaneGate e4 shrink exactly onto node 2's t (14): inserted == false (3 nodes, nothing inserted), grow → bitwise the original",
            (onNode ? "on-node cut" : "NOT an on-node cut") + (d == null ? "" : "; " + d));
        // e5 each touch of the cut node ends the tail at the next length write
        String[] touches = { "t", "val", "in slope", "out slope", "smooth toggle", "delete" };
        for (String touch : touches) {
            StraightCurveSection.Element x = e1Fixture();
            x.setLength(20);
            List<BezierBaker.Node> ln = x.roll.nodes;
            BezierBaker.Node c = ln.get(ln.size() - 1);
            switch (touch) {
                case "t" -> c.t = 19.5;
                case "val" -> c.val += 0.5;
                case "in slope" -> c.inDv += 1;
                case "out slope" -> c.outDv += 1;
                case "smooth toggle" -> c.smooth = !c.smooth;
                default -> ln.remove(ln.size() - 1);
            }
            boolean touched = !x.roll.untouched();
            x.setLength(L);
            BezierBaker.Node nl = x.roll.nodes.get(x.roll.nodes.size() - 1);
            boolean noTail = !x.roll.isCut() && x.roll.cut() == null && nl.t <= 20.0 + 1e-9
                && x.roll.nodes.size() <= 4;
            check(touched && noTail,
                "ScRollLaneGate e5 touch the cut node (" + touch + ") then grow: untouched() false, no tail returns (the lane holds its last node), shadow null",
                f("untouched %s, nodes %d, last t %s, cut %s", !touched, x.roll.nodes.size(), nl.t, x.roll.isCut()));
        }
        // e6 an interior edit while cut survives a return + the tail returns bitwise
        {
            StraightCurveSection.Element x = e1Fixture();
            x.setLength(20);
            x.roll.nodes.get(1).val = 99;
            x.setLength(L);
            List<BezierBaker.Node> exp = copyLane(orig);
            exp.get(1).val = 99;
            d = nodesDiff(x.roll.nodes, exp);
            check(d == null && !x.roll.isCut(),
                "ScRollLaneGate e6 interior node 1 re-valued while cut, then grow: the edit stays AND the tail returns bitwise",
                d == null ? "" : d);
        }
        // e7 the previous node moved while cut (D105 keeps its slope)
        {
            StraightCurveSection.Element x = e1Fixture();
            x.setLength(20);
            double[] gaps = BezierBaker.segGaps(x.roll.nodes);
            x.roll.nodes.get(2).t = 15;
            BezierBaker.rescaleDvToGaps(x.roll.nodes, gaps);
            boolean still = x.roll.untouched();
            x.setLength(L);
            BezierBaker.Node prev = x.roll.nodes.get(2), next = x.roll.nodes.get(3);
            BezierBaker.Node sp = orig.get(2), sn = orig.get(3);
            double r = (next.t - prev.t) / (sn.t - sp.t);
            boolean handle = bits(prev.outFrac, sp.outFrac) && bits(prev.outDv, sp.outDv * r)
                && bits(prev.t, 15.0);
            List<BezierBaker.Node> tail = x.roll.nodes.subList(3, x.roll.nodes.size());
            String dt = nodesDiff(tail, orig.subList(3, orig.size()));
            check(still && handle && dt == null && !x.roll.isCut() && x.roll.nodes.size() == 5,
                "ScRollLaneGate e7 the previous node moved 14 → 15 while cut (D105 rescale): still untouched, grow restores its out handle at the shadow's slope over the new gap (outDv × newGap/oldGap bitwise), the tail bitwise",
                f("untouched %s, prev outDv %s (shadow %s × %s), tail %s", still, prev.outDv, sp.outDv, r, dt == null ? "bitwise" : dt));
        }
        // e8 node 0 stays at 0 for any legal length (L ≥ MIN_ELEM_LEN > 0)
        {
            StraightCurveSection.Element x = e1Fixture();
            x.setLength(StraightCurveSection.MIN_ELEM_LEN);
            boolean ok = x.roll.nodes.size() >= 2 && bits(x.roll.nodes.get(0).t, 0.0)
                && x.roll.nodes.get(x.roll.nodes.size() - 1).t <= StraightCurveSection.MIN_ELEM_LEN + 1e-9;
            x.setLength(L);
            d = nodesDiff(x.roll.nodes, orig);
            check(ok && d == null,
                "ScRollLaneGate e8 shrink to MIN_ELEM_LEN (0.5): node 0 stays at 0 (a cut below node 0's t cannot occur), grow → bitwise",
                d == null ? "" : d);
        }
        // e9 setLength to the same L is a no-op bitwise
        {
            StraightCurveSection.Element x = e1Fixture();
            x.setLength(20);
            List<BezierBaker.Node> before = copyLane(x.roll.nodes);
            List<BezierBaker.Node> shBefore = copyLane(x.roll.shadow());
            RollLane.Cut cb = x.roll.cut();
            x.setLength(20);
            d = nodesDiff(x.roll.nodes, before);
            String d2 = nodesDiff(x.roll.shadow(), shBefore);
            check(d == null && d2 == null && cutEq(cb, x.roll.cut()) && bits(x.L, 20.0),
                "ScRollLaneGate e9 setLength to the same L: a bitwise no-op (nodes, shadow, fingerprint)",
                d == null ? (d2 == null ? "" : d2) : d);
        }
    }

    // ---------------------------------------------------------------
    // f — isolation
    // ---------------------------------------------------------------

    static StraightCurveSection fIsolation() {
        StraightCurveSection sc = F();
        setLane(sc.elements.get(1), linearLane(sc.elements.get(1).L, 0, 1), true, true, false);
        setLane(sc.elements.get(2), linearLane(10, 5, 15), false, false, false);
        return sc;
    }

    static double[] twists(ScIntegrator.Result r) {
        List<ScIntegrator.Bound> b = r.bounds();
        return new double[] { wrap(bank(b.get(2).q()) - bank(b.get(1).q())),
                              wrap(bank(b.get(3).q()) - bank(b.get(2).q())) };
    }

    static void rowF() {
        StraightCurveSection sc = fIsolation();
        List<BezierBaker.Node> n1 = copyLane(sc.elements.get(1).roll.nodes);
        List<BezierBaker.Node> n2 = copyLane(sc.elements.get(2).roll.nodes);
        List<FVDIntegrator.Keyframe> k1 = ScIntegrator.laneKeyframes(sc.elements.get(1));
        List<FVDIntegrator.Keyframe> k2 = ScIntegrator.laneKeyframes(sc.elements.get(2));
        double[] tw0 = twists(integ(sc));
        sc.elements.get(0).setLength(15);
        sc.elements.get(0).setLength(5);
        setLane(sc.elements.get(0), nodes(new double[][] { {0, 0}, {2.5, 3}, {5, -1} }), true, true, false);
        double[] tw1 = twists(integ(sc));
        String d1 = nodesDiff(sc.elements.get(1).roll.nodes, n1);
        String d2 = nodesDiff(sc.elements.get(2).roll.nodes, n2);
        String dk1 = keyframesDiff(ScIntegrator.laneKeyframes(sc.elements.get(1)), k1);
        String dk2 = keyframesDiff(ScIntegrator.laneKeyframes(sc.elements.get(2)), k2);
        double wt = Math.max(Math.abs(tw1[0] - tw0[0]), Math.abs(tw1[1] - tw0[1]));
        check(d1 == null && d2 == null && dk1 == null && dk2 == null && wt <= 1e-9,
            "ScRollLaneGate f resize element 0 by +5 and −5 m, then edit its lane: elements 1 and 2's nodes and keyframes BITWISE, their twists (bank change border to border) ≤ 1e-9 rad",
            f("twists %.6f / %.6f rad, Δ %.2e rad%s", tw1[0], tw1[1], wt,
                d1 == null && d2 == null && dk1 == null && dk2 == null ? "" : "; " + d1 + d2 + dk1 + dk2));
    }

    // ---------------------------------------------------------------
    // g — borders
    // ---------------------------------------------------------------

    static void rowG() {
        // g1 global → global, continuous: the landing at the straight→
        // planar border (kinds differ) with flat 1 °/m on both sides
        {
            StraightCurveSection sc = F();
            for (StraightCurveSection.Element e : sc.elements) flat(e, 1, true, true, false);
            ScIntegrator.Result r = integ(sc);
            List<FVDIntegrator.Sample> ss = r.samples();
            double w = 0;
            for (int bi = 1; bi <= 2; bi++) {
                double S = r.bounds().get(bi).s();
                for (int i = 0; i + 1 < ss.size(); i++) {
                    FVDIntegrator.Sample a = ss.get(i), b = ss.get(i + 1);
                    if (a.s() < S && b.s() >= S) {
                        double want = Math.toRadians(1.0) * (b.s() - a.s());
                        w = Math.max(w, Math.abs(wrap(bank(b) - bank(a)) - want));
                    }
                }
            }
            boolean landed = r.bounds().get(1).landed() && r.bounds().get(2).landed();
            check(landed && w <= 1e-9,
                "ScRollLaneGate g1 global °/m 1 → global °/m 1 across the straight→planar landings: the bank continues (Δbank == rate·Δs across each straddle ≤ 1e-9 rad)",
                f("worst %.2e rad, borders landed %s", w, landed));
        }
        // g2 rate → ABS with a value ≠ the arriving bank: the step lands
        // at the border landing, the bank == the lane value one leg later
        {
            StraightCurveSection sc = scOf(V, flat(StraightCurveSection.Element.straight(10), 1, true, true, false),
                flat(StraightCurveSection.Element.straight(10), 30, true, true, true));
            ScIntegrator.Result r = integ(sc);
            ScIntegrator.Bound b1 = r.bounds().get(1);
            double before = Double.NaN, after = Double.NaN;
            double atBorder = Double.NaN;
            for (FVDIntegrator.Sample s : r.samples()) {
                if (s.s() < b1.s() - 1e-9) before = Math.toDegrees(bank(s));
                else if (Math.abs(s.s() - b1.s()) <= 1e-9) atBorder = Math.toDegrees(bank(s));
                else if (Double.isNaN(after)) after = Math.toDegrees(bank(s));
            }
            // a dt step ends exactly ON the border here (20 m/s × 0.01 s
            // × 50 = 10 m): that sample IS the landing — 10° — and the
            // snap shows on the first sample past it
            boolean borderSample = Double.isNaN(atBorder) || Math.abs(atBorder - 10) <= 1e-9;
            check(b1.landed() && b1.idx() == 1 && Math.abs(Math.toDegrees(bank(b1.q())) - 10) <= 1e-9
                    && borderSample && Math.abs(after - 30) <= 1e-9 && before < 10.001,
                "ScRollLaneGate g2 global °/m 1 (bank 10° at the border) → ABSOLUTE 30°: the border landing (idx 1) still carries 10° (the sample on it too), the first sample PAST it reads the lane's 30° (the snap one leg later)",
                f("border bank %.9f°, last before %.6f°, on the border %.9f°, first past %.9f°", Math.toDegrees(bank(b1.q())), before, atBorder, after));
        }
        // g3 ABS → global rate: bank0 re-binds to the abs bank
        {
            for (double rate : new double[] { 0, 1 }) {
                StraightCurveSection sc = scOf(V, flat(StraightCurveSection.Element.straight(10), 30, true, true, true),
                    flat(StraightCurveSection.Element.straight(10), rate, true, true, false));
                ScIntegrator.Result r = integ(sc);
                double S = r.bounds().get(1).s();
                double w = 0;
                int n = 0;
                for (FVDIntegrator.Sample s : r.samples()) {
                    if (s.s() < S) continue;
                    double want = Math.toRadians(30 + rate * (s.s() - S));
                    w = Math.max(w, Math.abs(wrap(bank(s) - want)));
                    n++;
                }
                check(n > 10 && w <= 1e-9,
                    f("ScRollLaneGate g3 ABSOLUTE 30° → global °/m %s: the bank re-binds to the abs bank and continues as 30° + ∫rate (≤ 1e-9 rad on every sample after the border)", rate),
                    f("%d samples, worst %.2e rad", n, w));
            }
        }
        // g4 local → global: the global 0 holds what the local twist left
        {
            StraightCurveSection sc = scOf(V, flat(StraightCurveSection.Element.straight(10), 10, false, false, false),
                flat(StraightCurveSection.Element.straight(10), 0, true, true, false));
            ScIntegrator.Result r = integ(sc);
            ScIntegrator.Bound b1 = r.bounds().get(1);
            double held = bank(b1.q());
            double w = 0;
            int n = 0;
            for (FVDIntegrator.Sample s : r.samples()) {
                if (s.s() < b1.s()) continue;
                w = Math.max(w, Math.abs(wrap(bank(s) - held)));
                n++;
            }
            double expectTwist = Math.toRadians(10) * b1.t();
            check(n > 10 && w <= 1e-9 && Math.abs(wrap(held - expectTwist)) <= 1e-9,
                "ScRollLaneGate g4 local °/s 10 → global °/m 0: the border's bank == 10°/s × t (local twist), the global element holds it on every sample ≤ 1e-9 rad",
                f("held %.6f°, expected %.6f°, worst %.2e rad over %d samples", Math.toDegrees(held), Math.toDegrees(expectTwist), w, n));
        }
        // g5 a merged border (flat equal lanes) vs the single-element
        // twin: BITWISE for the ZERO lane (the default — exactAt returns
        // an exact 0 whatever the segment), to dust for a flat NON-ZERO
        // lane (BezierBaker.exactAt sums a flat segment's cubic to
        // v ± 1 ulp depending on the segment's x-extent — the lane
        // sections' own bake, untouched); vs an un-merged twin
        // (differing flags: a landing) ≤ 1e-9
        {
            for (double val : new double[] { 0, 1 }) {
                StraightCurveSection two = scOf(V, flat(StraightCurveSection.Element.straight(10), val, true, true, false),
                    flat(StraightCurveSection.Element.straight(10), val, true, true, false));
                StraightCurveSection one = scOf(V, flat(StraightCurveSection.Element.straight(20), val, true, true, false));
                StraightCurveSection twin = scOf(V, flat(StraightCurveSection.Element.straight(10), val, true, true, false),
                    flat(StraightCurveSection.Element.straight(10), val, false, true, false));
                ScIntegrator.Result r2 = integ(two), r1 = integ(one), rt = integ(twin);
                String d = sampleDiff(r1.samples(), r2.samples());
                double w1 = Math.max(maxPosDiff(r1.samples(), r2.samples()), maxBankDiff(r1.samples(), r2.samples()));
                double w = Math.max(maxPosDiff(r2.samples(), rt.samples()), maxBankDiff(r2.samples(), rt.samples()));
                boolean oneOk = val == 0 ? d == null : w1 <= 1e-12;
                check(oneOk && !r2.bounds().get(1).landed() && rt.bounds().get(1).landed() && w <= 1e-9,
                    val == 0
                        ? "ScRollLaneGate g5 straight 10 + straight 10 with the default (flat 0) lanes: MERGED (no landing) and BITWISE == straight 20; the local-flag twin lands there and agrees ≤ 1e-9 (pos + bank)"
                        : "ScRollLaneGate g5 straight 10 + straight 10 with flat equal global °/m 1 lanes: MERGED (no landing), == straight 20 to ≤ 1e-12 (the bake's ±1 ulp on a flat non-zero segment); the local-flag twin lands there and agrees ≤ 1e-9",
                    (val == 0 ? (d == null ? "bitwise" : d) : f("vs one %.2e", w1))
                        + f(", merged %s, twin landed %s, twin Δ %.2e", !r2.bounds().get(1).landed(), rt.bounds().get(1).landed(), w));
            }
        }
    }

    // ---------------------------------------------------------------
    // h — the merge rule
    // ---------------------------------------------------------------

    static void rowH() {
        // h1 equal flags + a flat continuous lane: the border MERGES and
        // the ARC-SUM split is BITWISE the parent for the ZERO lane (the
        // default, = ScElemGate c), to ≤ 1e-12 for a flat non-zero lane
        // (the bake's ±1 ulp, see g5)
        {
            for (double val : new double[] { 0, 1.5 }) {
                StraightCurveSection.Element parent = flat(StraightCurveSection.Element.curve(20, 90, 0, 0), val, true, true, false);
                ElementChain.Pose en = ElementChain.entry(P0, Quat.IDENTITY);
                StraightCurveSection.Element[] h = Coaster.cutElement(parent, 11.3, en);
                StraightCurveSection one = scOf(V, parent), two = scOf(V, h[0], h[1]);
                ScIntegrator.Result r1 = integ(one), r2 = integ(two);
                String d = sampleDiff(r1.samples(), r2.samples());
                double w = Math.max(maxPosDiff(r1.samples(), r2.samples()), maxBankDiff(r1.samples(), r2.samples()));
                boolean ok = val == 0 ? d == null : w <= 1e-12;
                check(ok && !r2.bounds().get(1).landed() && bits(h[0].L + h[1].L, parent.L),
                    val == 0 ? "ScRollLaneGate h1 equal flags + the flat default lane: the border MERGES and the ARC-SUM split is BITWISE the parent"
                             : "ScRollLaneGate h1 equal flags + a flat continuous global °/m 1.5 lane: the border MERGES and the ARC-SUM split reproduces the parent to ≤ 1e-12 (the bake's ±1 ulp on a flat non-zero segment)",
                    (val == 0 ? (d == null ? "bitwise" : d) : f("Δ %.2e", w))
                        + f(", merged %s, L1 + L2 == L %s", !r2.bounds().get(1).landed(), bits(h[0].L + h[1].L, parent.L)));
            }
        }
        // h2 a value step → a landing; h3 differing flags → a landing
        {
            StraightCurveSection step = scOf(V, flat(StraightCurveSection.Element.straight(10), 1, true, true, false),
                flat(StraightCurveSection.Element.straight(10), 2, true, true, false));
            StraightCurveSection flags = scOf(V, flat(StraightCurveSection.Element.straight(10), 1, true, true, false),
                flat(StraightCurveSection.Element.straight(10), 1, true, false, false));
            ScIntegrator.Result rs = integ(step), rf = integ(flags);
            check(rs.bounds().get(1).landed(), "ScRollLaneGate h2 a value step (1 → 2 °/m) at an otherwise mergeable border: a LANDING", "landed " + rs.bounds().get(1).landed());
            check(rf.bounds().get(1).landed(), "ScRollLaneGate h3 differing roll flags (°/m vs °/s) at an otherwise mergeable border: a LANDING", "landed " + rf.bounds().get(1).landed());
        }
        // h4 a sloped lane's split: the parent to grid-phase dust. The
        // cut at 11.33 m is OFF the 0.05 bake grid, so the right half's
        // keyframes sit at another phase than the parent's and the
        // per-leg trapezoid reads the piecewise-linear lane at other
        // points (a cut ON the grid, 11.3, reproduces the parent to
        // 4e-15 rad); the halves' lanes are value-continuous (the de
        // Casteljau mid node), so the border MERGES by the rule. The
        // dust scales with the lane's curvature × the cell²: a gentle
        // lane (±3 °/m — 60 °/s at 20 m/s) ≤ 1e-5 rad, pinned; the
        // cut-row fixture (±30 °/m, 600 °/s) reported as INFO. The
        // design's 1e-6 expectation is RE-PINNED to the measured order.
        {
            ElementChain.Pose en = ElementChain.entry(P0, Quat.IDENTITY);
            double[] scales = { 0.1, 1 };
            for (double scale : scales) {
                StraightCurveSection.Element parent = StraightCurveSection.Element.curve(20, 90, 0, 0);
                List<BezierBaker.Node> ln = slopedLane(parent.L);
                for (BezierBaker.Node nd : ln) { nd.val *= scale; nd.inDv *= scale; nd.outDv *= scale; }
                setLane(parent, ln, true, true, false);
                StraightCurveSection.Element[] h = Coaster.cutElement(parent, 11.33, en);
                StraightCurveSection one = scOf(V, parent), two = scOf(V, h[0], h[1]);
                ScIntegrator.Result r1 = integ(one), r2 = integ(two);
                double wp = maxPosDiff(r1.samples(), r2.samples());
                double wb = maxBankDiff(r1.samples(), r2.samples());
                if (scale < 1) {
                    check(wp <= 1e-9 && wb <= 1e-5 && !r2.bounds().get(1).landed(),
                        "ScRollLaneGate h4 a SLOPED global °/m lane (±3 °/m) split at 11.33 m (de Casteljau halves, OFF the bake grid): the parent to ≤ 1e-9 m / ≤ 1e-5 rad (grid-phase dust, never bitwise — documented); the value-continuous border merges",
                        f("pos %.2e m, bank %.2e rad, merged %s", wp, wb, !r2.bounds().get(1).landed()));
                } else {
                    info("ScRollLaneGate h4 the cut-row fixture (±30 °/m) split off-grid: the same dust scaled by the lane's curvature",
                        f("pos %.2e m, bank %.2e rad", wp, wb));
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // i — copy / mirror / undo-style round trips in a CUT state
    // ---------------------------------------------------------------

    static Coaster cutDoc() {
        StraightCurveSection sc = F();
        StraightCurveSection.Element e1 = sc.elements.get(1);
        setLane(e1, slopedLane(e1.L), false, false, false);
        e1.setLength(20);   // cut
        flat(sc.elements.get(2), 12, true, true, true);
        return docOf(sc);
    }

    static void rowI() {
        Coaster c = cutDoc();
        StraightCurveSection sc = (StraightCurveSection) c.pieces.get(0).sections.get(0);
        // copySection
        StraightCurveSection cp = (StraightCurveSection) Coaster.copySection(sc, 5);
        String d = null;
        for (int i = 0; d == null && i < sc.elements.size(); i++) {
            RollLane a = sc.elements.get(i).roll, b = cp.elements.get(i).roll;
            if (a == b || a.nodes == b.nodes) d = "element " + i + " shares a lane object";
            else d = laneDiff(a, b);
        }
        check(d == null && cp.elements.get(1).roll.isCut(),
            "ScRollLaneGate i1 Coaster.copySection of a cut element: new lane objects, nodes + flags + shadow + fingerprint BITWISE",
            d == null ? "" : d);
        // Element.copy() round trip (the SecState / undo path) + independence
        StraightCurveSection.Element e1 = sc.elements.get(1);
        StraightCurveSection.Element k = e1.copy();
        d = laneDiff(e1.roll, k.roll);
        k.roll.nodes.get(1).val = 77;
        k.setLength(e1.L + 5);
        boolean independent = e1.roll.isCut() && !bits(e1.roll.nodes.get(1).val, 77);
        check(d == null && k.roll != e1.roll && independent,
            "ScRollLaneGate i2 Element.copy() (the undo SecState path) round-trips the cut state bitwise; editing the copy never touches the original",
            d == null ? "" : d);
        // mirror once (negated) and twice (identity)
        List<RollLane> before = new ArrayList<>();
        for (StraightCurveSection.Element e : sc.elements) before.add(e.roll.copy());
        boolean ok1 = c.mirrorPiece(0);
        boolean neg = ok1;
        for (int i = 0; neg && i < sc.elements.size(); i++) {
            RollLane a = before.get(i), b = sc.elements.get(i).roll;
            for (int n = 0; neg && n < a.nodes.size(); n++) {
                neg = bits(b.nodes.get(n).val, -a.nodes.get(n).val)
                    && bits(b.nodes.get(n).outDv, -a.nodes.get(n).outDv)
                    && bits(b.nodes.get(n).inDv, -a.nodes.get(n).inDv);
            }
            if (a.isCut()) {
                neg &= b.isCut() && bits(b.cut().val(), -a.cut().val())
                    && bits(b.cut().inSlope(), -a.cut().inSlope())
                    && bits(b.cut().prevOutSlope(), -a.cut().prevOutSlope());
                for (int n = 0; neg && n < a.shadow().size(); n++) {
                    neg = bits(b.shadow().get(n).val, -a.shadow().get(n).val);
                }
            }
            neg &= a.rollGlobal() == b.rollGlobal() && a.ratePerMeter() == b.ratePerMeter()
                && a.rollAbsolute() == b.rollAbsolute();
        }
        boolean ok2 = c.mirrorPiece(0);
        d = null;
        for (int i = 0; d == null && i < sc.elements.size(); i++) d = laneDiff(before.get(i), sc.elements.get(i).roll);
        check(ok1 && neg && ok2 && d == null,
            "ScRollLaneGate i3 mirrorPiece negates every lane's values / dv / fingerprint incl. the shadow (flags kept); mirror² == identity bitwise",
            d == null ? "" : d);
        // the whole document through save → load (the file half of undo)
        try {
            String t = save(c);
            Coaster l = loadText(t).coaster();
            StraightCurveSection ls = (StraightCurveSection) l.pieces.get(0).sections.get(0);
            d = null;
            for (int i = 0; d == null && i < sc.elements.size(); i++) d = laneDiff(sc.elements.get(i).roll, ls.elements.get(i).roll);
            check(d == null, "ScRollLaneGate i4 the cut document's lanes survive save → load bitwise (shadow + fingerprint)", d == null ? "" : d);
        } catch (IOException ex) {
            check(false, "ScRollLaneGate i4 save → load", ex.toString());
        }
    }

    // ---------------------------------------------------------------
    // k — Switch / Bake
    // ---------------------------------------------------------------

    static Coaster bakeDoc() {
        StraightCurveSection sc = F();
        StraightCurveSection.Element e1 = sc.elements.get(1);
        setLane(e1, nodes(new double[][] { {0, 0}, {e1.L, 30} }), false, false, false);
        return docOf(sc);
    }

    static void rowK() {
        Triple[] targets = { TRIPLES[1], TRIPLES[2], TRIPLES[3], TRIPLES[4] };
        for (Triple t : targets) {
            Coaster c = bakeDoc();
            StraightCurveSection sc = (StraightCurveSection) c.pieces.get(0).sections.get(0);
            StraightCurveSection.Element e1 = sc.elements.get(1);
            e1.setLength(e1.L * 0.9);   // a cut state: the bake must drop the shadow
            double L = e1.L, k0 = e1.k0, k1 = e1.k1, dir = e1.dirDeg;
            TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
            boolean ok = c.bakeElementRoll(0, 0, 1, t.global, t.perMeter, t.abs, DT,
                Coaster.NODE_SPACING_DEFAULT_DIST, 0);
            TrackCompiler.Result r1 = TrackCompiler.compile(c, DT);
            boolean elemsBitwise = bits(e1.L, L) && bits(e1.k0, k0) && bits(e1.k1, k1) && bits(e1.dirDeg, dir);
            boolean flags = e1.roll.rollGlobal() == t.global && e1.roll.ratePerMeter() == t.perMeter
                && e1.roll.rollAbsolute() == t.abs;
            // the residual over element 1's arc
            TrackCompiler.Span sp = r0.spans().get(0);
            double sA = sp.s0() + sp.scBounds().get(1).s(), sB = sp.s0() + sp.scBounds().get(2).s();
            double wBank = 0, wRate = 0, rMin = Double.POSITIVE_INFINITY, rMax = Double.NEGATIVE_INFINITY;
            double[] rate0 = bodyRollRate(r0.samples()), rate1 = bodyRollRate(r1.samples());
            boolean sameGrid = r0.samples().size() == r1.samples().size();
            for (int i = 0; sameGrid && i < r0.samples().size(); i++) {
                double s = r0.samples().get(i).s();
                if (s < sA || s > sB) continue;
                wBank = Math.max(wBank, Math.abs(wrap(bank(r1.samples().get(i)) - bank(r0.samples().get(i)))));
                if (!Double.isNaN(rate0[i]) && !Double.isNaN(rate1[i])) {
                    wRate = Math.max(wRate, Math.abs(rate1[i] - rate0[i]));
                    rMin = Math.min(rMin, rate0[i]);
                    rMax = Math.max(rMax, rate0[i]);
                }
            }
            double range = rMax - rMin;
            boolean within = t.abs ? Math.toDegrees(wBank) <= 0.5 : wRate <= 0.02 * range;
            check(ok && sameGrid && elemsBitwise && flags && !e1.roll.isCut() && within
                    && bits(e1.roll.nodes.get(0).t, 0.0),
                "ScRollLaneGate k Bake element 1 (local °/s 0→30 ramp, cut) → " + t.name + ": elements bitwise, flags := new, shadow dropped, twist residual ≤ 0.5° (abs) / ≤ 2 % of range (rate) at default density",
                f("ok %s, %d nodes, bank residual %.4f°, rate residual %.3f of range %.2f °/s, cut %s",
                    ok, e1.roll.nodes.size(), Math.toDegrees(wBank), wRate, range, e1.roll.isCut()));
        }
        // Switch = flags only
        {
            Coaster c = bakeDoc();
            StraightCurveSection sc = (StraightCurveSection) c.pieces.get(0).sections.get(0);
            StraightCurveSection.Element e1 = sc.elements.get(1);
            List<BezierBaker.Node> before = copyLane(e1.roll.nodes);
            e1.roll.setFlags(true, true, true);
            String d = nodesDiff(e1.roll.nodes, before);
            check(d == null && e1.roll.rollAbsolute() && e1.roll.rollGlobal(),
                "ScRollLaneGate k Switch (RollLane.setFlags) writes the flags only — nodes bitwise", d == null ? "" : d);
        }
    }

    // ---------------------------------------------------------------
    // l — C32: Convert Real to Design on an s/c with a °/s lane
    // ---------------------------------------------------------------

    static void rowL() {
        List<List<BezierBaker.Node>> hold = new ArrayList<>();
        hold.add(nodes(new double[][] { {0, 1}, {1, 1} }));
        hold.add(nodes(new double[][] { {0, 0}, {1, 0} }));
        hold.add(nodes(new double[][] { {0, 0}, {1, 0} }));
        ForceSection fs = new ForceSection(hold, 15);
        StraightCurveSection sc = F();
        StraightCurveSection.Element e1 = sc.elements.get(1);
        setLane(e1, nodes(new double[][] { {0, 0}, {e1.L, 30} }), false, false, false);
        sc.vPinned = true;   // a deliberate conflict: design 20 vs the real 15
        Coaster c = docOf(fs, sc);
        TrackCompiler.Result r0 = rebuild(c);
        List<BezierBaker.Node> n0 = copyLane(sc.elements.get(0).roll.nodes);
        List<BezierBaker.Node> n2 = copyLane(sc.elements.get(2).roll.nodes);
        TrackCompiler.Span sp0 = r0.spans().get(1);
        boolean ok = c.makeGraphsReal(0, 1, DT, Coaster.NODE_SPACING_DEFAULT_DIST, 0);
        TrackCompiler.Result r1 = rebuild(c);
        TrackCompiler.Span sp1 = r1.spans().get(1);
        double sA0 = sp0.s0() + sp0.scBounds().get(1).s(), sB0 = sp0.s0() + sp0.scBounds().get(2).s();
        double sA1 = sp1.s0() + sp1.scBounds().get(1).s();
        double w = 0;
        int n = 0;
        List<FVDIntegrator.Sample> a = r0.samples().subList(sp0.i0(), sp0.i1() + 1);
        List<FVDIntegrator.Sample> b = r1.samples().subList(sp1.i0(), sp1.i1() + 1);
        for (int i = 0; i <= 200; i++) {
            double x = (sB0 - sA0) * i / 200.0;
            double b0 = bankAtArc(a, sA0 + x), b1 = bankAtArc(b, sA1 + x);
            if (Double.isNaN(b0) || Double.isNaN(b1)) continue;
            w = Math.max(w, Math.abs(wrap(b1 - b0)));
            n++;
        }
        String d0 = nodesDiff(sc.elements.get(0).roll.nodes, n0), d2 = nodesDiff(sc.elements.get(2).roll.nodes, n2);
        check(ok && Math.abs(sc.vDesign - 15) <= 1e-9 && n > 150 && w <= 1e-3 && d0 == null && d2 == null
                && !sc.elements.get(1).roll.ratePerMeter() && !sc.elements.get(1).roll.rollAbsolute(),
            "ScRollLaneGate l C32 Convert Real to Design on F after a 15 m/s hold (design 20, pinned): vDesign := 15, element 1's local °/s ramp re-timed — bank per arc ≤ 1e-3 rad of before; the °/m elements 0 and 2 bitwise",
            f("ok %s, vDesign %.6f, %d arcs, worst Δbank %.2e rad, element 1 nodes %d (span %d..%d, arc %.3f..%.3f)%s%s", ok, sc.vDesign, n, w,
                sc.elements.get(1).roll.nodes.size(), sp0.i0(), sp0.i1(), sA0, sB0,
                d0 == null ? "" : "; e0 " + d0, d2 == null ? "" : "; e2 " + d2));
    }

    // ---------------------------------------------------------------
    // m — IO
    // ---------------------------------------------------------------

    static void rowM() throws Exception {
        // m1 F default + m2 F cut: save → load → rebuild bitwise, byte-identical
        for (int variant = 0; variant < 2; variant++) {
            Coaster c = variant == 0 ? docOf(F()) : cutDoc();
            ((TrackPiece) c.pieces.get(0)).sections.add(StraightCurveSection.straight(10, 18));
            TrackCompiler.Result r0 = rebuild(c);
            String t1 = save(c);
            DesignIO.LoadResult lr = loadText(t1);
            Coaster l = lr.coaster();
            TrackCompiler.Result rl = rebuild(l);
            String t2 = save(l);
            StraightCurveSection a = (StraightCurveSection) c.pieces.get(0).sections.get(0);
            StraightCurveSection b = (StraightCurveSection) l.pieces.get(0).sections.get(0);
            String d = null;
            for (int i = 0; d == null && i < a.elements.size(); i++) d = laneDiff(a.elements.get(i).roll, b.elements.get(i).roll);
            String rd = resultDiff(r0, rl);
            check(t1.equals(t2) && d == null && rd == null && lr.warnings() == 0,
                variant == 0 ? "ScRollLaneGate m1 F default: save → load → rebuild BITWISE, lanes bitwise, byte-identical, no warning"
                             : "ScRollLaneGate m2 F with element 1 CUT (rsh + rcut): save → load → rebuild BITWISE incl. the shadow + fingerprint, byte-identical",
                (d == null ? "" : d + "; ") + (rd == null ? "" : rd + "; ") + "warnings " + lr.warnings()
                    + (variant == 1 ? (t1.contains("\nrcut ") ? ", rcut written" : ", NO rcut line") : ""));
        }
        String head = "FVDD3\npiece 0 21 0 0\nsection sc 20.0 distance 0\n";
        String tail = "endsection\nendpiece\n";
        // m3 no rn block → the default lane
        {
            DesignIO.LoadResult lr = loadText(head + "el straight L=10.0 blend=0.0 locks=0 rollg=1 ratem=1 rollabs=0\n" + tail);
            StraightCurveSection.Element e = ((StraightCurveSection) lr.coaster().pieces.get(0).sections.get(0)).elements.get(0);
            String d = nodesDiff(e.roll.nodes, BezierBaker.holdLane(0, 10, 0));
            check(d == null && e.roll.rollGlobal() && e.roll.ratePerMeter() && !e.roll.rollAbsolute() && !e.roll.isCut() && lr.warnings() == 0,
                "ScRollLaneGate m3 an `el` line with no `rn` block: the default lane (0, 0), (L, 0), global °/m, no warning", d == null ? "" : d);
        }
        // m4 the Stage-1a rm/rv migration
        {
            DesignIO.LoadResult lr = loadText(head
                + "el straight L=10.0 blend=0.0 locks=0 rm=1 rv=20.0\n"
                + "el straight L=12.0 blend=0.0 locks=0 rm=0 rv=30.0\n"
                + "el straight L=8.0 blend=0.0 locks=0 rm=1 rv=0.0\n" + tail);
            List<StraightCurveSection.Element> els = ((StraightCurveSection) lr.coaster().pieces.get(0).sections.get(0)).elements;
            StraightCurveSection.Element a = els.get(0), b = els.get(1), z = els.get(2);
            boolean abs = a.roll.rollAbsolute() && a.roll.rollGlobal() && nodesDiff(a.roll.nodes, BezierBaker.holdLane(0, 10, 20)) == null;
            boolean loc = !b.roll.rollGlobal() && !b.roll.ratePerMeter() && !b.roll.rollAbsolute()
                && nodesDiff(b.roll.nodes, BezierBaker.holdLane(0, 12, 30)) == null;
            boolean def = z.roll.rollGlobal() && z.roll.ratePerMeter() && !z.roll.rollAbsolute()
                && nodesDiff(z.roll.nodes, BezierBaker.holdLane(0, 8, 0)) == null;
            boolean zeroed = a.rollVal == 0 && b.rollVal == 0;
            check(abs && loc && def && zeroed && lr.warnings() == 0,
                "ScRollLaneGate m4 Stage-1a `rm=1 rv=20` → an ABSOLUTE 20° bank lane, `rm=0 rv=30` → a LOCAL °/s 30 lane, `rm=1 rv=0` → the default; the inert slots zeroed; no warning",
                f("abs %s, local %s, default %s, zeroed %s, warnings %d", abs, loc, def, zeroed, lr.warnings()));
        }
        // m5 damaged shadow halves: rsh without rcut, rcut without rsh, rcut with the wrong t
        {
            String rn = "rn 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0\n"
                      + "rn 10.0 5.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0\n";
            String rsh = "rsh 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0\n"
                       + "rsh 10.0 5.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0\n"
                       + "rsh 15.0 9.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0\n";
            String good = "rcut ins=1 t=10.0 v=5.0 si=0.5 so=0.5 sm=1 po=0.5\n";
            String bad = "rcut ins=1 t=9.0 v=5.0 si=0.5 so=0.5 sm=1 po=0.5\n";
            String el = "el straight L=10.0 blend=0.0 locks=0 rollg=1 ratem=1 rollabs=0\n";
            DesignIO.LoadResult ok = loadText(head + el + rn + rsh + good + tail);
            DesignIO.LoadResult noCut = loadText(head + el + rn + rsh + tail);
            DesignIO.LoadResult noSh = loadText(head + el + rn + good + tail);
            DesignIO.LoadResult wrongT = loadText(head + el + rn + rsh + bad + tail);
            StraightCurveSection.Element eOk = ((StraightCurveSection) ok.coaster().pieces.get(0).sections.get(0)).elements.get(0);
            StraightCurveSection.Element e1 = ((StraightCurveSection) noCut.coaster().pieces.get(0).sections.get(0)).elements.get(0);
            StraightCurveSection.Element e2 = ((StraightCurveSection) noSh.coaster().pieces.get(0).sections.get(0)).elements.get(0);
            StraightCurveSection.Element e3 = ((StraightCurveSection) wrongT.coaster().pieces.get(0).sections.get(0)).elements.get(0);
            check(eOk.roll.isCut() && eOk.roll.shadow().size() == 3 && ok.warnings() == 0
                    && eOk.roll.cut().t() == 10.0 && eOk.roll.nodes.size() == 2,
                "ScRollLaneGate m5a a well-formed rsh + rcut block installs the shadow (3 nodes) + fingerprint, no warning",
                "cut " + eOk.roll.isCut() + ", warnings " + ok.warnings());
            check(!e1.roll.isCut() && noCut.warnings() == 1 && e1.roll.nodes.size() == 2,
                "ScRollLaneGate m5b `rsh` without `rcut`: the shadow is DROPPED and counted (warnings 1), the lane kept",
                "cut " + e1.roll.isCut() + ", warnings " + noCut.warnings());
            check(!e2.roll.isCut() && noSh.warnings() == 1,
                "ScRollLaneGate m5c `rcut` without `rsh`: dropped and counted (warnings 1)",
                "cut " + e2.roll.isCut() + ", warnings " + noSh.warnings());
            check(!e3.roll.isCut() && wrongT.warnings() == 1,
                "ScRollLaneGate m5d an `rcut` whose t is not the lane's last node's t: dropped and counted (warnings 1)",
                "cut " + e3.roll.isCut() + ", warnings " + wrongT.warnings());
        }
        // m6 rollabs=1 forces rollg=1
        {
            DesignIO.LoadResult lr = loadText(head + "el straight L=10.0 blend=0.0 locks=0 rollg=0 ratem=0 rollabs=1\n" + tail);
            StraightCurveSection.Element e = ((StraightCurveSection) lr.coaster().pieces.get(0).sections.get(0)).elements.get(0);
            check(e.roll.rollGlobal() && e.roll.rollAbsolute() && !e.roll.ratePerMeter(),
                "ScRollLaneGate m6 `rollabs=1 rollg=0` loads as absolute + GLOBAL (the interlock), ratem kept as written",
                f("global %s abs %s pm %s", e.roll.rollGlobal(), e.roll.rollAbsolute(), e.roll.ratePerMeter()));
        }
    }

    // ---------------------------------------------------------------
    // n — the legacy switch is gone + the cardinal fixtures
    // ---------------------------------------------------------------

    static List<String> dataLines(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String ln : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String s = ln.replace("﻿", "").strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            out.add(s);
        }
        return out;
    }

    static Map<String, double[]> lastPos(List<String> lines) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (String ln : lines) {
            String[] fl = ln.split("\\s*\\|\\s*");
            if (fl.length < 5) continue;
            String[] p = fl[4].trim().split("\\s+");
            m.put(fl[0].trim() + "|" + fl[1].trim(), new double[] {
                Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]) });
        }
        return m;
    }

    static void rowN() throws Exception {
        boolean fieldGone, methodGone;
        try { ScIntegrator.class.getDeclaredField("LEGACY_BODY_AXIS"); fieldGone = false; }
        catch (NoSuchFieldException ex) { fieldGone = true; }
        try { ScIntegrator.class.getDeclaredMethod("legacyFlag"); methodGone = false; }
        catch (NoSuchMethodException ex) { methodGone = true; }
        check(fieldGone && methodGone,
            "ScRollLaneGate n1 ScIntegrator.LEGACY_BODY_AXIS and legacyFlag() are GONE (the -Dfvd.sc.legacyAxis switch removed)",
            "field gone " + fieldGone + ", method gone " + methodGone);
        Path rec = Paths.get(LEGACY_DIR).resolve("legacy-checksums.txt");
        if (!Files.exists(rec)) {
            System.out.println("SKIP  ScRollLaneGate n2 legacy record — " + rec + " missing");
            return;
        }
        Map<String, double[]> mr = lastPos(dataLines(rec));
        double worst = 0;
        int n = 0;
        boolean missing = false;
        String worstName = "";
        for (boolean rate : new boolean[] { true, false }) {
            String r = rate ? "RATE0" : "GLOBAL0";
            Object[][] fx = {
                { "straight50/" + r + "|level", StraightCurveSection.Element.straight(50, 0) },
                { "R20-90dir0/" + r + "|level", StraightCurveSection.Element.curve(20, -90, 0, 0) },
                { "R30+45dir90/" + r + "|level", StraightCurveSection.Element.curve(30, 45, 90, 0) },
                { "R20+90dir0/" + r + "|level", StraightCurveSection.Element.curve(20, 90, 0, 0) },
            };
            for (Object[] o : fx) {
                StraightCurveSection.Element e = (StraightCurveSection.Element) o[1];
                if (rate) e.roll.setFlags(false, false, false); else e.roll.setFlags(true, true, false);
                double[] want = mr.get((String) o[0]);
                if (want == null) { missing = true; continue; }
                StraightCurveSection sc = scOf(26, e);
                ScIntegrator.Result rr = ScIntegrator.integrate(sc, DT, 26, P0, Quat.IDENTITY);
                Vec3 p = rr.samples().get(rr.samples().size() - 1).pos();
                double d = Math.sqrt((p.x() - want[0]) * (p.x() - want[0]) + (p.y() - want[1]) * (p.y() - want[1])
                    + (p.z() - want[2]) * (p.z() - want[2]));
                n++;
                if (d > worst) { worst = d; worstName = (String) o[0]; }
            }
        }
        check(!missing && n == 8 && worst <= 1e-9,
            "ScRollLaneGate n2 the level, unbanked, cardinal fixtures of LegacyAxisProbe (RATE0 = a local lane, GLOBAL0 = the default) match legacy-checksums.txt within 1e-9 m",
            f("%d fixtures, worst %.2e m (%s)%s", n, worst, worstName, missing ? "; some fixture missing" : ""));
    }

    // ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("ScRollLaneGate — D259 §2.9 (the roll lane per s/c element, Stage 1b run 1)");
        rowA();
        rowB();
        rowC();
        rowD();
        rowE();
        rowF();
        rowG();
        rowH();
        rowI();
        System.out.println("SKIP  ScRollLaneGate j B edges — editor code (run 2)");
        rowK();
        rowL();
        rowM();
        rowN();
        System.out.println();
        System.out.println("ScRollLaneGate: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
