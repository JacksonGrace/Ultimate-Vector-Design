package uvd;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** D259 §1.9 gate — the STRAIGHT/CURVE ELEMENT MODEL (Stage 1a, the
 *  curvature-defined Straight / Planar / Helical elements on the
 *  ROLL-FREE axis). Fixtures are built in code; the one file input is
 *  row h's recorded checksum dump. Rows (docs/SC-OVERHAUL-DESIGN.md
 *  §1.9, the register line §10.1):
 *  a1) forward map vs integrator: ElementChain.advance's exit pose vs
 *      the integrator's last sample — each kind × dir {0,30,45,90,210}
 *      × entry {level, pitched 20°, banked 30°} × k {−0.05, 0, 0.1}
 *      (helix: c_h × (β0, β1) {(0,20), (20,−10), (0,0)}), plus a 450°
 *      lap per curved kind; blends off, a LOCAL rate-0 roll lane (no
 *      twist, no projection, so the integrator's U IS the roll-free U —
 *      Stage 1b: the lane replaced the interim RATE 0).
 *      Bounds: pos ≤ 1e-7 m, T and U ≤ 1e-6°, and for PLANAR the
 *      max distance of every sample from the ANALYTIC circle (centre
 *      p0 + N/k, N = T0 × a, the axis built here from the level frame
 *      independently) ≤ 1e-7 m — THE headline: a banked or pitched
 *      entry stays an exact circle (the old body-axis law dove
 *      10.9 m on the banked R20/90°/30° case).
 *  a2) κ = 0 planar == straight to ≤ 1e-12 m (sinc forms, not bitwise).
 *  b)  helix closed forms vs a 64-panel GL-8 quadrature of ∫cos β,
 *      ∫sin β (linear β, a (β0, β1) sweep incl. β1 → β0): s_h, Δy, Δψ
 *      recovered from the exit pose ≤ 1e-12 relative, and the inverse
 *      2·atan2(Δy, s_h) − β0 = β1 ≤ 1e-12 rad.
 *  c)  constant-κ split bitwise (Coaster.cutElement: L1 + L2 == L, the
 *      right half's dir re-based, the equal-axis merge fires): planar
 *      R20 90° dir 45 from a pitched entry, a constant-pitch helix, a
 *      straight — samples + qEnd BITWISE; a ramped split ≤ 1e-12 m.
 *  d)  equal-κ merge: straight+straight == straight, two equal-axis
 *      planars == one, bitwise; a blend or a differing dir = a kink
 *      (the border's Bound landed).
 *  e)  Bound records: compile twice bitwise, the exit record == the
 *      last sample, landed vs sharp ≤ 1e-7 m, dt-independence, merged
 *      borders vs the sharp chain and an un-merged twin's landing.
 *  f)  the `el` grammar round trip (every kind, both interps, links,
 *      locks 2047, blend 0/2, the per-element rollg/ratem/rollabs
 *      fields + rn / rsh / rcut lines — Stage 1b; ScRollLaneGate m owns
 *      the lane round trip proper) + a clamp sweep; element 0's blend
 *      writes as 0 and loads 0.
 *  g)  old `elem` lines (v11.0 + an `elem curve 10 50 -20 4.213 1 0`):
 *      the default lane, element 0's blend 0 (Stage 1b: roll dropped).
 *  h)  RE-PINNED in Stage 1b (the legacy switch is GONE): the level,
 *      unbanked, cardinal fixtures of LegacyAxisProbe, built in-JVM,
 *      match legacy-checksums.txt within 1e-9 m (the roll-free axis
 *      equals the body axis exactly there).
 *  i)  vertical entry: an inherited plane, a finite compile, the helix
 *      refusal (changeKind false + HELIX_VERTICAL_WHY).
 *  j)  mirror: mirrorPiece's compile == the z-negated original bitwise.
 *  k)  determinism through Coaster: compile twice, rebuild at rest,
 *      piece x/y/z + yaw rigid, save → load → rebuild bitwise.
 *
 *  Compile + run (from the project root):
 *    javac -encoding UTF-8 -cp target/classes -d gates/out gates/ScElemGate.java
 *    java -cp "target/classes;gates/out" uvd.ScElemGate
 */
public final class ScElemGate {

    private ScElemGate() {}

    static final double DT = 0.01;
    static final double V = 26;
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

    /** LegacyAxisProbe.entry verbatim: identity, pitched nose-up about
     *  −Z (T = (cos, sin, 0)), then banked about the pitched T. */
    static Quat entryQ(double pitchDeg, double bankDeg) {
        double p = Math.toRadians(pitchDeg);
        Vec3 T = new Vec3(Math.cos(p), Math.sin(p), 0);
        Vec3 U = new Vec3(-Math.sin(p), Math.cos(p), 0);
        Quat q = Quat.fromFrame(T, U);
        if (bankDeg != 0) q = Quat.aboutAxis(T, Math.toRadians(bankDeg)).mul(q).normalize();
        return q;
    }

    /** A LOCAL °/s rate-0 roll lane (Stage 1b): no twist, no
     *  projection — the roll-free frame verbatim (the former interim
     *  RATE 0). */
    static StraightCurveSection.Element rate0(StraightCurveSection.Element e) {
        e.roll.setFlags(false, false, false);
        return e;
    }

    /** A flat lane at v with the given settings (Stage 1b fixtures). */
    static StraightCurveSection.Element lane(StraightCurveSection.Element e, double v,
                                             boolean global, boolean perMeter, boolean abs) {
        e.roll.installNodes(BezierBaker.holdLane(0, e.L, v));
        e.roll.setFlags(global, perMeter, abs);
        return e;
    }

    /** null = the two lanes agree bitwise (flags, nodes, shadow, cut). */
    static String laneDiff(RollLane a, RollLane b) {
        if (a.rollGlobal() != b.rollGlobal() || a.ratePerMeter() != b.ratePerMeter()
                || a.rollAbsolute() != b.rollAbsolute()) return "roll flags";
        String d = nodesDiff(a.nodes, b.nodes);
        if (d != null) return "lane " + d;
        if ((a.shadow() == null) != (b.shadow() == null)) return "shadow presence";
        if (a.shadow() != null) {
            d = nodesDiff(a.shadow(), b.shadow());
            if (d != null) return "shadow " + d;
            RollLane.Cut x = a.cut(), y = b.cut();
            if (x == null || y == null) return "cut presence";
            if (x.inserted() != y.inserted() || !bits(x.t(), y.t()) || !bits(x.val(), y.val())
                    || !bits(x.inSlope(), y.inSlope()) || !bits(x.outSlope(), y.outSlope())
                    || x.smooth() != y.smooth() || !bits(x.prevOutSlope(), y.prevOutSlope())) {
                return "cut fingerprint";
            }
        }
        return null;
    }

    static String nodesDiff(List<BezierBaker.Node> a, List<BezierBaker.Node> b) {
        if (a.size() != b.size()) return "node counts " + a.size() + " vs " + b.size();
        for (int i = 0; i < a.size(); i++) {
            BezierBaker.Node x = a.get(i), y = b.get(i);
            if (!bits(x.t, y.t) || !bits(x.val, y.val) || !bits(x.outFrac, y.outFrac)
                    || !bits(x.outDv, y.outDv) || !bits(x.inFrac, y.inFrac)
                    || !bits(x.inDv, y.inDv) || x.smooth != y.smooth
                    || x.straightOut != y.straightOut || x.levelOut != y.levelOut) {
                return "node " + i;
            }
        }
        return null;
    }

    static StraightCurveSection scOf(StraightCurveSection.Element... els) {
        List<StraightCurveSection.Element> l = new ArrayList<>();
        for (StraightCurveSection.Element e : els) l.add(e.copy());
        return new StraightCurveSection(l, V);
    }

    static ScIntegrator.Result integ(StraightCurveSection sc, Vec3 p0, Quat q0) {
        return ScIntegrator.integrate(sc, DT, V, p0, q0);
    }

    static FVDIntegrator.Sample lastOf(List<FVDIntegrator.Sample> s) {
        return s.get(s.size() - 1);
    }

    /** Angle between two directions (deg) — atan2 form, resolves 1e-9°
     *  (acos cannot below ~1e-6°). */
    static double angDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.atan2(a.cross(b).length(), a.dot(b)));
    }

    static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    static boolean vecBits(Vec3 a, Vec3 b) {
        return bits(a.x(), b.x()) && bits(a.y(), b.y()) && bits(a.z(), b.z());
    }

    static boolean quatBits(Quat a, Quat b) {
        return bits(a.w(), b.w()) && bits(a.x(), b.x())
            && bits(a.y(), b.y()) && bits(a.z(), b.z());
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

    /** Max position difference by sample index (counts must match). */
    static double maxPosDiff(List<FVDIntegrator.Sample> a, List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return Double.POSITIVE_INFINITY;
        double w = 0;
        for (int i = 0; i < a.size(); i++) {
            w = Math.max(w, a.get(i).pos().sub(b.get(i).pos()).length());
        }
        return w;
    }

    static String boundDiff(List<ScIntegrator.Bound> a, List<ScIntegrator.Bound> b) {
        if (a == null || b == null) return "bounds null";
        if (a.size() != b.size()) return "bound counts " + a.size() + " vs " + b.size();
        for (int i = 0; i < a.size(); i++) {
            ScIntegrator.Bound x = a.get(i), y = b.get(i);
            boolean eq = x.idx() == y.idx() && bits(x.s(), y.s()) && bits(x.t(), y.t())
                && vecBits(x.pos(), y.pos()) && quatBits(x.q(), y.q())
                && bits(x.v(), y.v()) && bits(x.loss(), y.loss())
                && x.landed() == y.landed() && vecBits(x.sharpPos(), y.sharpPos())
                && quatBits(x.sharpQ(), y.sharpQ());
            if (!eq) return "bound " + i;
        }
        return null;
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

    static boolean allFinite(List<FVDIntegrator.Sample> ss) {
        for (FVDIntegrator.Sample s : ss) {
            for (double x : sampleFields(s)) if (!Double.isFinite(x)) return false;
        }
        return true;
    }

    /** The level frame of §1.2, built HERE (independent of
     *  ElementChain): ul = Y − (Y·T)T normalized, rl = ul × T. */
    static Vec3[] levelFrame(Vec3 T) {
        double horiz2 = 1 - T.y() * T.y();
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return new Vec3[] { ul, ul.cross(T) };
    }

    /** Max distance of every sample from the circle (centre c, radius
     *  R, plane normal a): sqrt((ρ − R)² + h²). */
    static double circleDev(List<FVDIntegrator.Sample> ss, Vec3 p0, Vec3 T0,
                            Vec3 a, double k) {
        Vec3 N = T0.cross(a);
        double R = 1 / Math.abs(k);
        Vec3 c = p0.add(N.scale(1 / k));
        double w = 0;
        for (FVDIntegrator.Sample sm : ss) {
            Vec3 d = sm.pos().sub(c);
            double h = d.dot(a);
            Vec3 in = d.sub(a.scale(h));
            double dr = in.length() - R;
            w = Math.max(w, Math.sqrt(dr * dr + h * h));
        }
        return w;
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

    static long[] vdBits(Coaster c) {
        List<Long> l = new ArrayList<>();
        for (TrackPiece p : c.pieces) {
            for (Section s : p.sections) l.add(Double.doubleToRawLongBits(s.vDesign()));
        }
        long[] out = new long[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = l.get(i);
        return out;
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

    /** Every element field of the record (§1.1) incl. the interim roll
     *  pair, as comparable bits; null = equal. */
    static String elemDiff(StraightCurveSection.Element a, StraightCurveSection.Element b) {
        if (a.kind != b.kind) return "kind";
        if (!bits(a.L, b.L)) return "L " + a.L + " vs " + b.L;
        if (!bits(a.k0, b.k0)) return "k0 " + a.k0 + " vs " + b.k0;
        if (!bits(a.k1, b.k1)) return "k1 " + a.k1 + " vs " + b.k1;
        if (a.kLink != b.kLink) return "kLink";
        if (a.kInterp != b.kInterp) return "kInterp";
        if (!bits(a.dirDeg, b.dirDeg)) return "dirDeg " + a.dirDeg + " vs " + b.dirDeg;
        if (!bits(a.b1Deg, b.b1Deg)) return "b1Deg " + a.b1Deg + " vs " + b.b1Deg;
        if (a.bLink != b.bLink) return "bLink";
        if (a.bInterp != b.bInterp) return "bInterp";
        if (!bits(a.blendIn, b.blendIn)) return "blendIn " + a.blendIn + " vs " + b.blendIn;
        if (a.locks != b.locks) return "locks " + a.locks + " vs " + b.locks;
        String ld = laneDiff(a.roll, b.roll);
        if (ld != null) return "roll " + ld;
        return null;
    }

    static String docElemDiff(Coaster a, Coaster b) {
        if (a.pieces.size() != b.pieces.size()) return "piece counts";
        for (int p = 0; p < a.pieces.size(); p++) {
            List<Section> sa = a.pieces.get(p).sections, sb = b.pieces.get(p).sections;
            if (sa.size() != sb.size()) return "section counts piece " + p;
            for (int k = 0; k < sa.size(); k++) {
                if (sa.get(k) instanceof StraightCurveSection x) {
                    if (!(sb.get(k) instanceof StraightCurveSection y)) return "type " + p + "/" + k;
                    if (x.elements.size() != y.elements.size()) return "element counts " + p + "/" + k;
                    for (int i = 0; i < x.elements.size(); i++) {
                        String d = elemDiff(x.elements.get(i), y.elements.get(i));
                        if (d != null) return "piece " + p + " sec " + k + " el " + i + ": " + d;
                    }
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // a1 / a2 / e — forward maps vs the integrator
    // ---------------------------------------------------------------

    record Case(String name, StraightCurveSection.Element e, double pitch, double bank) {}

    static List<Case> a1Cases() {
        List<Case> cs = new ArrayList<>();
        double[][] ent = { {0, 0}, {20, 0}, {0, 30} };
        String[] entN = { "level", "pitch20", "bank30" };
        for (int j = 0; j < ent.length; j++) {
            cs.add(new Case("STRAIGHT L30 " + entN[j],
                rate0(StraightCurveSection.Element.straight(30)), ent[j][0], ent[j][1]));
        }
        double[] dirs = { 0, 30, 45, 90, 210 };
        double[] ks = { -0.05, 0, 0.1 };
        for (double d : dirs) {
            for (int j = 0; j < ent.length; j++) {
                for (double k : ks) {
                    cs.add(new Case(f("PLANAR dir %s %s k %s L25", fmt(d), entN[j], fmt(k)),
                        rate0(StraightCurveSection.Element.planar(25, k, d)),
                        ent[j][0], ent[j][1]));
                }
            }
        }
        // the 450° lap: R10 in the 30°-tilted plane from the banked entry
        double kLap = 0.1;
        cs.add(new Case("PLANAR LAP 450° dir 30 bank30 k 0.1",
            rate0(StraightCurveSection.Element.planar(2.5 * Math.PI / kLap, kLap, 30)), 0, 30));
        double[][] pairs = { {0, 20}, {20, -10}, {0, 0} };
        for (double cH : ks) {
            for (double[] pr : pairs) {
                double[][] he = pr[0] == 0
                    ? new double[][] { {0, 0}, {0, 30} }
                    : new double[][] { {pr[0], 0}, {pr[0], 30} };
                String[] heN = pr[0] == 0
                    ? new String[] { "level", "bank30" }
                    : new String[] { "pitch20", "pitch20bank30" };
                for (int j = 0; j < he.length; j++) {
                    StraightCurveSection.Element e =
                        StraightCurveSection.Element.helical(25, cH, pr[0]);
                    e.b1Deg = pr[1];
                    cs.add(new Case(f("HELICAL c_h %s (b0,b1) (%s,%s) %s L25", fmt(cH),
                        fmt(pr[0]), fmt(pr[1]), heN[j]), rate0(e), he[j][0], he[j][1]));
                }
            }
        }
        // the 450° helix lap: c_h 0.1, linear β 0 → 10, L from s_h = 2.5π/c_h
        {
            double b1 = Math.toRadians(10);
            double sH = 2.5 * Math.PI / 0.1;
            double L = sH * b1 / Math.sin(b1);
            StraightCurveSection.Element e = StraightCurveSection.Element.helical(L, 0.1, 0.0);
            e.b1Deg = 10;
            cs.add(new Case("HELICAL LAP 450° c_h 0.1 (b0,b1) (0,10) bank30", rate0(e), 0, 30));
        }
        return cs;
    }

    static String fmt(double x) {
        return x == Math.rint(x) ? Long.toString((long) x) : Double.toString(x);
    }

    static void rowA1E() {
        List<Case> cs = a1Cases();
        double[] worstPos = new double[3], worstT = new double[3], worstU = new double[3];
        double worstCirc = 0;
        int boundsTwiceBad = 0, exitBad = 0, nBounds = 0;
        String boundsFirst = null, exitFirst = null;
        double worstSharp = 0, worstSharpT = 0, worstDtInd = 0;
        String worstSharpName = "", worstDtName = "";
        for (Case c : cs) {
            Quat q0 = entryQ(c.pitch(), c.bank());
            StraightCurveSection sc = scOf(c.e());
            ScIntegrator.Result r = integ(sc, P0, q0);
            ElementChain.Pose en = ElementChain.entry(P0, q0);
            ElementChain.Pose ex = ElementChain.advance(en, c.e());
            FVDIntegrator.Sample last = lastOf(r.samples());
            double dPos = ex.pos().sub(last.pos()).length();
            double dT = angDeg(ex.T(), last.T());
            double dU = angDeg(ex.U(), last.U());
            int ki = c.e().kind.ordinal();
            worstPos[ki] = Math.max(worstPos[ki], dPos);
            worstT[ki] = Math.max(worstT[ki], dT);
            worstU[ki] = Math.max(worstU[ki], dU);
            double circ = Double.NaN;
            String axisNote = "";
            if (c.e().kind == StraightCurveSection.Kind.PLANAR && c.e().k0 != 0) {
                Vec3 T0 = en.T();
                Vec3[] lf = levelFrame(T0);
                double d = Math.toRadians(c.e().dirDeg);
                Vec3 a = lf[0].scale(Math.cos(d)).add(lf[1].scale(Math.sin(d)));
                Vec3 ca = ElementChain.axisOf(c.e(), en);
                double axDev = a.sub(ca).length();
                if (axDev > 1e-12) axisNote = f(", chain axis off the level-frame axis by %.2e", axDev);
                circ = circleDev(r.samples(), P0, T0, a, c.e().k0);
                worstCirc = Math.max(worstCirc, circ);
            }
            boolean ok = dPos <= 1e-7 && dT <= 1e-6 && dU <= 1e-6
                && (Double.isNaN(circ) || circ <= 1e-7) && axisNote.isEmpty();
            check(ok, "ScElemGate a1 " + c.name(), f("pos %.2e m, T %.2e°, U %.2e°%s%s",
                dPos, dT, dU, Double.isNaN(circ) ? "" : f(", circle %.2e m", circ), axisNote));

            // ---- e (on the a1 fixtures) ----
            ScIntegrator.Result r2 = integ(scOf(c.e()), P0, q0);
            String bd = boundDiff(r.bounds(), r2.bounds());
            if (bd != null || sampleDiff(r.samples(), r2.samples()) != null) {
                boundsTwiceBad++;
                if (boundsFirst == null) boundsFirst = c.name() + ": " + bd;
            }
            List<ScIntegrator.Bound> bl = r.bounds();
            nBounds += bl.size();
            ScIntegrator.Bound b0 = bl.get(0), bn = bl.get(bl.size() - 1);
            boolean exitOk = b0.landed() && bn.landed() && vecBits(b0.pos(), P0)
                && vecBits(bn.pos(), last.pos()) && vecBits(bn.q().T(), last.T())
                && vecBits(bn.q().U(), last.U()) && bits(bn.s(), last.s())
                && bits(bn.t(), last.t());
            if (!exitOk) {
                exitBad++;
                if (exitFirst == null) exitFirst = c.name();
            }
            for (ScIntegrator.Bound b : bl) {
                if (!b.landed()) continue;
                double ds = b.pos().sub(b.sharpPos()).length();
                double dq = Math.max(angDeg(b.q().T(), b.sharpQ().T()),
                    angDeg(b.q().U(), b.sharpQ().U()));
                if (ds > worstSharp) { worstSharp = ds; worstSharpName = c.name(); }
                worstSharpT = Math.max(worstSharpT, dq);
            }
            // sample-INDEPENDENT landing: the same arc at another dt grid
            ScIntegrator.Result r3 = ScIntegrator.integrate(scOf(c.e()), 0.0137, V, P0, q0);
            ScIntegrator.Bound bn3 = r3.bounds().get(r3.bounds().size() - 1);
            double dInd = bn3.pos().sub(bn.pos()).length();
            if (dInd > worstDtInd) { worstDtInd = dInd; worstDtName = c.name(); }
        }
        String[] kn = { "STRAIGHT", "PLANAR", "HELICAL" };
        for (int k = 0; k < 3; k++) {
            info("ScElemGate a1 worst " + kn[k], f("pos %.2e m, T %.2e°, U %.2e°",
                worstPos[k], worstT[k], worstU[k]));
        }
        info("ScElemGate a1 worst PLANAR circle", f("%.2e m over every curved planar fixture", worstCirc));
        check(boundsTwiceBad == 0, "ScElemGate e1 a1 fixtures: Bound lists + samples bitwise across two compiles",
            boundsTwiceBad == 0 ? cs.size() + " fixtures" : boundsTwiceBad + " differ, first " + boundsFirst);
        check(exitBad == 0, "ScElemGate e2 a1 fixtures: entry/exit records landed, entry == p0, exit == the last sample bitwise",
            exitBad == 0 ? nBounds + " records" : exitBad + " bad, first " + exitFirst);
        check(worstSharp <= 1e-7 && worstSharpT <= 1e-6,
            "ScElemGate e3 a1 fixtures: landed pos vs sharpPos ≤ 1e-7 m, q vs sharpQ ≤ 1e-6° (blends off)",
            f("worst %.2e m (%s), %.2e°", worstSharp, worstSharpName, worstSharpT));
        check(worstDtInd <= 1e-7,
            "ScElemGate e4 a1 fixtures: the landed exit is sample-grid independent (dt 0.01 vs 0.0137) ≤ 1e-7 m",
            f("worst %.2e m (%s)", worstDtInd, worstDtName));
    }

    static void rowA2() {
        double[][] ent = { {0, 0}, {20, 0}, {0, 30}, {20, 30} };
        String[] entN = { "level", "pitch20", "bank30", "pitch20bank30" };
        double[] dirs = { 0, 30, 90, 210 };
        double worst = 0;
        String bad = null;
        int n = 0;
        for (int j = 0; j < ent.length; j++) {
            Quat q0 = entryQ(ent[j][0], ent[j][1]);
            ScIntegrator.Result rs = integ(scOf(rate0(StraightCurveSection.Element.straight(37.5))), P0, q0);
            for (double d : dirs) {
                ScIntegrator.Result rp = integ(scOf(rate0(
                    StraightCurveSection.Element.planar(37.5, 0, d))), P0, q0);
                double w = maxPosDiff(rs.samples(), rp.samples());
                n++;
                if (w > worst) worst = w;
                if (!(w <= 1e-12) && bad == null) bad = entN[j] + " dir " + fmt(d) + ": " + w;
            }
        }
        check(worst <= 1e-12, "ScElemGate a2 κ = 0 planar == straight (L 37.5, every sample) ≤ 1e-12 m",
            f("%d fixtures, worst %.2e m%s", n, worst, bad == null ? "" : ", first " + bad));
    }

    // ---------------------------------------------------------------
    // b — helix closed forms vs quadrature
    // ---------------------------------------------------------------

    static final double[] GL_X = {
        -0.9602898564975363, -0.7966664774136267, -0.5255324099163290,
        -0.1834346424956498, 0.1834346424956498, 0.5255324099163290,
        0.7966664774136267, 0.9602898564975363 };
    static final double[] GL_W = {
        0.1012285362903763, 0.2223810344533745, 0.3137066458778873,
        0.3626837833783620, 0.3626837833783620, 0.3137066458778873,
        0.2223810344533745, 0.1012285362903763 };

    /** {∫cos β, ∫sin β} over [0, L], linear β0 → β1, 64-panel GL-8. */
    static double[] quadCosSin(double L, double b0, double b1) {
        int n = 64;
        double h = L / n, ic = 0, is = 0;
        for (int p = 0; p < n; p++) {
            double s0 = p * h;
            for (int g = 0; g < 8; g++) {
                double s = s0 + h * (GL_X[g] + 1) / 2;
                double w = GL_W[g] * h / 2;
                double beta = b0 + (b1 - b0) * s / L;
                ic += w * Math.cos(beta);
                is += w * Math.sin(beta);
            }
        }
        return new double[] { ic, is };
    }

    static double rel(double got, double ref, double scale) {
        return Math.abs(got - ref) / Math.max(Math.abs(ref), scale);
    }

    static void rowB() {
        double L = 30;
        double[] cHs = { 0.05, -0.03, 0 };
        double[][] pairs = { {0, 20}, {20, -10}, {0, 0}, {-15, 25}, {10, 10.001},
            {10, 10 + 1e-7}, {30, 30 - 1e-9}, {10, Double.NaN} /* β1 := β0 exactly */ };
        double wS = 0, wY = 0, wPsi = 0, wInv = 0;
        int n = 0;
        String first = null;
        for (double cH : cHs) {
            for (double[] pr : pairs) {
                Quat q0 = entryQ(pr[0], 0);
                ElementChain.Pose en = ElementChain.entry(Vec3.ZERO, q0);
                double beta0 = Math.asin(Math.max(-1, Math.min(1, en.T().y())));
                StraightCurveSection.Element e = StraightCurveSection.Element.helical(L, cH, pr[0]);
                e.b1Deg = Double.isNaN(pr[1]) ? Math.toDegrees(beta0) : pr[1];
                double beta1 = Math.toRadians(e.b1Deg);
                ElementChain.Pose ex = ElementChain.advance(en, e);
                double[] qd = quadCosSin(L, beta0, beta1);
                double sHq = qd[0], dyq = qd[1];
                // recover s_h, Δy, Δψ from the exit pose
                Vec3 T0 = en.T();
                double len = Math.hypot(T0.x(), T0.z());
                Vec3 Th = new Vec3(T0.x() / len, 0, T0.z() / len);
                Vec3 Nh = Th.cross(new Vec3(0, 1, 0));
                Vec3 dp = ex.pos().sub(en.pos());
                double dy = dp.y();
                Vec3 plan = new Vec3(dp.x(), 0, dp.z());
                Vec3 T1 = ex.T();
                double dpsi = Math.atan2(T1.dot(Nh), T1.dot(Th));
                double sh = ElementChain.sinc(dpsi / 2);
                double sHr = plan.length() / sh;
                double eS = rel(sHr, sHq, 1e-300);
                double eY = rel(dy, dyq, L);   // Δy = 0 for a level helix: scale by L
                double ePsi = cH == 0 ? Math.abs(dpsi) / L : rel(dpsi, cH * sHq, 1e-300);
                double eInv = Math.abs(2 * Math.atan2(dy, sHr) - beta0 - beta1);
                wS = Math.max(wS, eS); wY = Math.max(wY, eY);
                wPsi = Math.max(wPsi, ePsi); wInv = Math.max(wInv, eInv);
                n++;
                if (first == null && !(eS <= 1e-12 && eY <= 1e-12 && ePsi <= 1e-12 && eInv <= 1e-12)) {
                    first = f("c_h %s (%s,%s): s_h %.2e Δy %.2e Δψ %.2e inv %.2e", fmt(cH),
                        fmt(pr[0]), Double.isNaN(pr[1]) ? "β0" : Double.toString(pr[1]),
                        eS, eY, ePsi, eInv);
                }
            }
        }
        check(wS <= 1e-12, "ScElemGate b s_h (closed form, from the exit pose) vs 64-panel GL-8 ∫cos β ≤ 1e-12 rel",
            f("%d cases, worst %.2e%s", n, wS, first == null ? "" : "; first miss " + first));
        check(wY <= 1e-12, "ScElemGate b Δy vs GL-8 ∫sin β ≤ 1e-12 rel",
            f("worst %.2e", wY));
        check(wPsi <= 1e-12, "ScElemGate b Δψ (exit heading) vs c_h·∫cos β ≤ 1e-12 rel",
            f("worst %.2e", wPsi));
        check(wInv <= 1e-12, "ScElemGate b inverse 2·atan2(Δy, s_h) − β0 recovers β1 ≤ 1e-12 rad",
            f("worst %.2e rad", wInv));
    }

    // ---------------------------------------------------------------
    // c — constant-κ split bitwise
    // ---------------------------------------------------------------

    static void splitCase(String name, StraightCurveSection.Element parent, double pitch,
                          double bank, double cut, boolean bitwise) {
        Quat q0 = entryQ(pitch, bank);
        ElementChain.Pose en = ElementChain.entry(P0, q0);
        StraightCurveSection.Element[] h = Coaster.cutElement(parent, cut, en);
        boolean sumOk = bits(h[0].L + h[1].L, parent.L);
        String extra = "";
        if (parent.kind == StraightCurveSection.Kind.PLANAR) {
            ElementChain.Pose mid = ElementChain.advance(en, h[0]);
            Vec3 a = ElementChain.axisOf(parent, en), a2 = ElementChain.axisOf(h[1], mid);
            double ax = Math.max(Math.abs(a.x() - a2.x()),
                Math.max(Math.abs(a.y() - a2.y()), Math.abs(a.z() - a2.z())));
            boolean dirKept = bits(h[0].dirDeg, parent.dirDeg);
            extra = f(", left dir %s, right dir %.12f (re-based), axis gap %.2e%s",
                dirKept ? "kept" : "CHANGED", h[1].dirDeg, ax,
                ax <= StraightCurveSection.AXIS_MERGE_EPS ? "" : " > AXIS_MERGE_EPS");
            if (!dirKept) sumOk = false;
        }
        StraightCurveSection one = scOf(parent), two = scOf(h[0], h[1]);
        ScIntegrator.Profile pr = new ScIntegrator.Profile(two, P0, q0);
        extra += ", border " + (pr.merged[1] ? "MERGED" : "a kink");
        ScIntegrator.Result r1 = integ(one, P0, q0), r2 = integ(two, P0, q0);
        if (bitwise) {
            String d = sampleDiff(r1.samples(), r2.samples());
            boolean qe = quatBits(r1.qEnd(), r2.qEnd());
            check(sumOk && d == null && qe, "ScElemGate c " + name + ": samples + qEnd BITWISE == unsplit",
                f("L1 %s + L2 %s %s L%s%s%s", h[0].L, h[1].L, bits(h[0].L + h[1].L, parent.L) ? "==" : "!=",
                    extra, d == null ? "" : f("; %s (max pos diff %.2e m)", d,
                        maxPosDiff(r1.samples(), r2.samples())),
                    qe ? "" : f("; qEnd differs by %.2e°", angDeg(r1.qEnd().T(), r2.qEnd().T()))));
        } else {
            double w = maxPosDiff(r1.samples(), r2.samples());
            check(sumOk && w <= 1e-12, "ScElemGate c " + name + ": ≤ 1e-12 m of unsplit (to dust)",
                f("max pos diff %.2e m (counts %d/%d)%s", w, r1.samples().size(),
                    r2.samples().size(), extra));
        }
    }

    static void rowC() {
        StraightCurveSection.Element pl = rate0(StraightCurveSection.Element.curve(20, 90, 45, 0));
        splitCase("planar R20 90° dir 45, pitched 20° entry, cut 11.3 m", pl, 20, 0, 11.3, true);
        StraightCurveSection.Element plG = StraightCurveSection.Element.curve(20, 90, 45, 0);
        plG.roll.setFlags(true, true, false);   // the DEFAULT lane: global °/m 0
        splitCase("planar R20 90° dir 45, pitched 20° entry, the default global °/m 0 lane, cut 11.3 m",
            plG, 20, 0, 11.3, true);
        StraightCurveSection.Element plB = rate0(StraightCurveSection.Element.curve(20, 90, 45, 0));
        splitCase("planar R20 90° dir 45, pitched 20° + banked 30° entry, cut 11.3 m", plB, 20, 30, 11.3, true);
        StraightCurveSection.Element hx = StraightCurveSection.Element.helical(30, 1 / 20.0, 20.0);
        hx.setBLink(true, 20.0);
        rate0(hx);
        splitCase("constant-pitch helix c_h 1/20 (bLink), pitched 20° entry, cut 11.3 m", hx, 20, 0, 11.3, true);
        StraightCurveSection.Element hx0 = StraightCurveSection.Element.helical(30, 1 / 20.0, 0.0);
        hx0.setBLink(true, 0.0);
        rate0(hx0);
        splitCase("constant-pitch helix c_h 1/20 (bLink), level entry, cut 11.3 m", hx0, 0, 0, 11.3, true);
        splitCase("straight 30, pitched 20° + banked 30° entry, cut 11.3 m",
            rate0(StraightCurveSection.Element.straight(30)), 20, 30, 11.3, true);
        StraightCurveSection.Element rp = rate0(StraightCurveSection.Element.planar(30, 0.02, 45));
        rp.kLink = false;
        rp.k1 = 0.08;
        splitCase("RAMPED planar k 0.02 → 0.08 LINEAR dir 45, pitched 20° entry, cut 11.3 m", rp, 20, 0, 11.3, false);
    }

    // ---------------------------------------------------------------
    // d + e (merged borders) — equal-κ merge
    // ---------------------------------------------------------------

    static void mergeCase(String name, StraightCurveSection one, StraightCurveSection two,
                          double pitch, double bank) {
        Quat q0 = entryQ(pitch, bank);
        ScIntegrator.Result r1 = integ(one, P0, q0), r2 = integ(two, P0, q0);
        String d = sampleDiff(r1.samples(), r2.samples());
        boolean qe = quatBits(r1.qEnd(), r2.qEnd());
        ScIntegrator.Bound b = r2.bounds().get(1);
        check(d == null && qe && !b.landed(), "ScElemGate d " + name + ": BITWISE == one element, border merged",
            (d == null ? "samples equal" : d) + (qe ? "" : "; qEnd differs")
                + (b.landed() ? "; border record LANDED (not merged)" : ""));
        // e: the merged border's record vs the sharp chain and vs an
        // UN-MERGED twin (the second element's roll flags differ — the
        // default global °/m 0 lane against the fixtures' local 0: no
        // merge, the same geometry on these level / flat-bank fixtures)
        // that lands there
        double dSharp = b.pos().sub(b.sharpPos()).length();
        StraightCurveSection twin = scOf(two.elements.get(0), two.elements.get(1));
        twin.elements.get(1).roll.setFlags(true, true, false);
        ScIntegrator.Result rt = integ(twin, P0, q0);
        ScIntegrator.Bound bt = rt.bounds().get(1);
        double dTwin = b.pos().sub(bt.pos()).length();
        check(dSharp <= 1e-7 && bt.landed() && dTwin <= 1e-7,
            "ScElemGate e5 " + name + ": merged record ≤ 1e-7 m of the sharp chain and of an un-merged twin's landing",
            f("vs sharp %.2e m, vs twin %.2e m, twin border %s", dSharp, dTwin,
                bt.landed() ? "landed" : "NOT landed"));
    }

    static void kinkCase(String name, StraightCurveSection two, double pitch, double bank,
                         boolean sharpTest) {
        Quat q0 = entryQ(pitch, bank);
        ScIntegrator.Result r = integ(two, P0, q0);
        ScIntegrator.Bound b = r.bounds().get(1);
        double dSharp = b.pos().sub(b.sharpPos()).length();
        boolean ok = b.landed() && (!sharpTest || dSharp <= 1e-7);
        check(ok, "ScElemGate d " + name + ": the border is a kink (record landed)",
            "landed " + b.landed() + (sharpTest ? f(", vs sharp %.2e m", dSharp) : ""));
    }

    static void rowD() {
        StraightCurveSection.Element s10 = rate0(StraightCurveSection.Element.straight(10));
        StraightCurveSection.Element s20 = rate0(StraightCurveSection.Element.straight(20));
        mergeCase("straight 10 + straight 10 vs straight 20, level", scOf(s20), scOf(s10, s10), 0, 0);
        mergeCase("straight 10 + straight 10 vs straight 20, pitched 20 + banked 30",
            scOf(s20), scOf(s10, s10), 20, 30);
        StraightCurveSection.Element c45 = rate0(StraightCurveSection.Element.curve(20, 45, 0, 0));
        StraightCurveSection.Element c90 = rate0(StraightCurveSection.Element.curve(20, 90, 0, 0));
        check(bits(c45.L + c45.L, c90.L), "ScElemGate d fixture: L(R20 45°)·2 == L(R20 90°) bitwise",
            c45.L + " + " + c45.L + " vs " + c90.L);
        mergeCase("R20 45° + R20 45° (dir 0, equal axes) vs R20 90°, level", scOf(c90), scOf(c45, c45), 0, 0);
        mergeCase("R20 45° + R20 45° (dir 0, equal axes) vs R20 90°, banked 30", scOf(c90), scOf(c45, c45), 0, 30);
        StraightCurveSection.Element c45b = c45.copy();
        c45b.blendIn = 1.0;
        kinkCase("R20 45° + R20 45° with blendIn 1 on the second", scOf(c45, c45b), 0, 0, false);
        StraightCurveSection.Element c45d = rate0(StraightCurveSection.Element.curve(20, 45, 5, 0));
        kinkCase("R20 45° dir 0 + R20 45° dir 5 (differing axes)", scOf(c45, c45d), 0, 0, true);
        StraightCurveSection.Element s10b = s10.copy();
        s10b.blendIn = 2.0;
        kinkCase("straight 10 + straight 10 with blendIn 2", scOf(s10, s10b), 0, 0, false);
        kinkCase("straight 10 + R20 45° (different kinds)", scOf(s10, c45), 0, 30, true);
    }

    /** e6: a chain with every kind from a banked entry — every border
     *  lands, each record within 1e-7 m of the sharp chain. */
    static void rowE6() {
        StraightCurveSection.Element h = StraightCurveSection.Element.helical(20, 1 / 25.0, 0.0);
        h.b1Deg = 12;
        StraightCurveSection sc = scOf(
            rate0(StraightCurveSection.Element.straight(8)),
            rate0(StraightCurveSection.Element.curve(20, 60, 30, 0)),
            rate0(h),
            rate0(StraightCurveSection.Element.curve(15, -45, 210, 0)),
            rate0(StraightCurveSection.Element.straight(6)));
        Quat q0 = entryQ(0, 30);
        ScIntegrator.Result r = integ(sc, P0, q0), r2 = integ(sc, P0, q0);
        double w = 0;
        boolean allLanded = true;
        for (ScIntegrator.Bound b : r.bounds()) {
            allLanded &= b.landed();
            w = Math.max(w, b.pos().sub(b.sharpPos()).length());
        }
        FVDIntegrator.Sample last = lastOf(r.samples());
        ScIntegrator.Bound bn = r.bounds().get(r.bounds().size() - 1);
        String bd = boundDiff(r.bounds(), r2.bounds());
        check(allLanded && w <= 1e-7 && bd == null && vecBits(bn.pos(), last.pos()),
            "ScElemGate e6 5-element chain (every kind, banked entry): every border landed, ≤ 1e-7 m of sharp, records bitwise twice",
            f("%d records, worst vs sharp %.2e m%s", r.bounds().size(), w, bd == null ? "" : ", " + bd));
    }

    // ---------------------------------------------------------------
    // f — the `el` grammar round trip
    // ---------------------------------------------------------------

    static Coaster fFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> els = new ArrayList<>();
        StraightCurveSection.Element e0 = StraightCurveSection.Element.straight(7.25);
        e0.roll.setFlags(false, false, false);
        e0.locks = StraightCurveSection.Element.LOCK_LEN | StraightCurveSection.Element.LOCK_Y;
        els.add(e0);
        StraightCurveSection.Element e1 = StraightCurveSection.Element.curve(20, 60, 30, 0);
        lane(e1, 12.5, true, true, true);   // ABSOLUTE bank 12.5°
        els.add(e1);
        StraightCurveSection.Element e2 = StraightCurveSection.Element.planar(18.3, 0.02, 210, 2);
        e2.kLink = false;
        e2.k1 = -0.03;
        e2.kInterp = StraightCurveSection.Interp.SINUSOIDAL;
        e2.locks = StraightCurveSection.Element.LOCK_MASK;   // 2047: every bit
        // a SLOPED local °/s lane with a CUT state (shadow + fingerprint)
        List<BezierBaker.Node> sl = new ArrayList<>();
        for (double[] pt : new double[][] { {0, -7.25}, {6, 4}, {12, -2.5}, {18.3, 9} }) {
            sl.add(new BezierBaker.Node(pt[0], pt[1]));
        }
        BezierBaker.applyDefaultHandles(sl);
        sl.get(1).outDv = 1.5;
        sl.get(1).inDv = -1.5;
        e2.roll.installNodes(sl);
        e2.roll.setFlags(false, false, false);
        e2.setLength(9.7);    // cut → shadow + rcut
        e2.setLength(18.3);   // grown back: bitwise the original, uncut
        e2.setLength(14.2);   // cut again (the saved state)
        els.add(e2);
        StraightCurveSection.Element e3 = StraightCurveSection.Element.helical(22.5, 0.03, 0.0);
        e3.kLink = false;
        e3.k1 = 0.05;
        e3.kInterp = StraightCurveSection.Interp.SINUSOIDAL;
        e3.b1Deg = -5.125;
        e3.bInterp = StraightCurveSection.Interp.SINUSOIDAL;
        e3.blendIn = 2;
        e3.locks = StraightCurveSection.Element.LOCK_B1 | StraightCurveSection.Element.LOCK_HEAD;
        lane(e3, -15, true, false, false);   // global °/s −15
        els.add(e3);
        StraightCurveSection.Element e4 = StraightCurveSection.Element.helical(15, -1 / 30.0, 0.0);
        e4.setBLink(true, 3.0);
        e4.locks = StraightCurveSection.Element.LOCK_X | StraightCurveSection.Element.LOCK_Z;
        lane(e4, 4.75, false, true, false);   // local °/m 4.75
        els.add(e4);
        StraightCurveSection.Element e5 = StraightCurveSection.Element.planar(9.0, 1 / 7.0, -33.3333);
        e5.blendIn = 0;
        els.add(e5);   // the default lane (global °/m 0)
        p.sections.add(new StraightCurveSection(els, 24.5));
        c.pieces.add(p);
        return c;
    }

    /** The clamp sweep: every field at the edge of the loader's range
     *  (the writer may emit all of them — D198). */
    static Coaster clampFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> els = new ArrayList<>();
        double kM = StraightCurveSection.KAPPA_MAX, bM = StraightCurveSection.B1_MAX;
        StraightCurveSection.Element a = StraightCurveSection.Element.straight(0.01);
        a.locks = StraightCurveSection.Element.LOCK_MASK;
        els.add(a);
        StraightCurveSection.Element b = StraightCurveSection.Element.planar(0.5, kM, 1080.25);
        els.add(b);
        StraightCurveSection.Element cc = StraightCurveSection.Element.planar(0.5, -kM, -359.999999999);
        cc.kLink = false;
        cc.k1 = kM;
        els.add(cc);
        StraightCurveSection.Element d = StraightCurveSection.Element.helical(1, -kM, 0.0);
        d.b1Deg = bM;
        d.blendIn = 1e-300;
        els.add(d);
        StraightCurveSection.Element e = StraightCurveSection.Element.helical(1, 4.9e-324, 0.0);
        e.b1Deg = -bM;
        lane(e, -1e6, true, true, true);   // an absolute lane at −1e6°
        e.locks = 1;
        els.add(e);
        StraightCurveSection.Element g = StraightCurveSection.Element.planar(1e6, -Double.MIN_NORMAL, 0.1 + 0.2);
        els.add(g);
        p.sections.add(new StraightCurveSection(els, 26));
        c.pieces.add(p);
        return c;
    }

    static void rowF() throws Exception {
        Coaster c = fFixture();
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        String t1 = save(c);
        DesignIO.LoadResult lr = loadText(t1);
        Coaster c2 = lr.coaster();
        String t2 = save(c2);
        int elLines = 0;
        for (String ln : t1.split("\n")) if (ln.startsWith("el ")) elLines++;
        int rnLines = 0, rshLines = 0, rcutLines = 0;
        for (String ln : t1.split("\n")) {
            if (ln.startsWith("rn ")) rnLines++;
            if (ln.startsWith("rsh ")) rshLines++;
            if (ln.startsWith("rcut ")) rcutLines++;
        }
        check(t1.equals(t2) && elLines == 6 && rnLines >= 12 && rshLines == 4 && rcutLines == 1
                && t1.contains("rollg=") && !t1.contains(" rm="),
            "ScElemGate f save → load → save byte-identical (6 `el` lines: every kind, both interps, links, locks 2047, blend 0/2, rollg/ratem/rollabs + rn/rsh/rcut, no rm/rv)",
            elLines + " el, " + rnLines + " rn, " + rshLines + " rsh, " + rcutLines + " rcut lines, "
                + t1.length() + " bytes, warnings " + lr.warnings());
        String ed = docElemDiff(c, c2);
        check(ed == null && lr.warnings() == 0,
            "ScElemGate f every element field bitwise after the round trip (no loader clamp hit)",
            ed == null ? "all fields equal" : ed);
        TrackCompiler.Result r1 = TrackCompiler.compile(c2, DT);
        String rd = resultDiff(r0, r1);
        check(rd == null, "ScElemGate f load → compile BITWISE == the session's compile",
            rd == null ? r0.samples().size() + " samples" : rd);
        TrackCompiler.Result rs = rebuild(c), rl = rebuild(c2);
        String rd2 = resultDiff(rs, rl);
        check(rd2 == null, "ScElemGate f load → rebuild BITWISE == the session's rebuild",
            rd2 == null ? "" : rd2);
        // element 0's blend is INERT and always 0 (Stage 1b, §1.1): a
        // session value writes as 0, loads as 0, compiles identically
        Coaster cb = fFixture();
        ((StraightCurveSection) cb.pieces.get(0).sections.get(0)).elements.get(0).blendIn = 2;
        String tb = save(cb);
        Coaster cb2 = loadText(tb).coaster();
        double b0 = ((StraightCurveSection) cb2.pieces.get(0).sections.get(0)).elements.get(0).blendIn;
        String rdb = resultDiff(TrackCompiler.compile(cb, DT), r0);
        check(tb.equals(t1) && bits(b0, 0.0) && rdb == null,
            "ScElemGate f element 0's blendIn 2 in the session: writes `blend=0.0`, loads 0, compile bitwise the 0 case",
            "loaded blend " + b0 + (rdb == null ? "" : ", " + rdb));
        // the clamp sweep
        Coaster k = clampFixture();
        String k1 = save(k);
        DesignIO.LoadResult kl = loadText(k1);
        String k2 = save(kl.coaster());
        String kd = docElemDiff(k, kl.coaster());
        check(k1.equals(k2) && kd == null && kl.warnings() == 0,
            "ScElemGate f clamp sweep (L 0.01, |k| = KAPPA_MAX, |b1| = B1_MAX, dir 1080.25, subnormals, locks 2047): byte-identical + fields bitwise",
            kd == null ? "warnings " + kl.warnings() : kd);
    }

    // ---------------------------------------------------------------
    // g — old `elem` lines
    // ---------------------------------------------------------------

    static void rowG() throws Exception {
        String old = "FVDD3\npiece 0 21 0 0\nsection sc 26.0 distance 0\n"
            + "elem straight 12.0 0.0\nelem curve 20.0 90.0 0.0 2.0\n"
            + "endsection\n"
            + "section sc 26.0 distance 0\n"
            + "elem curve 10 50 -20 4.213 1 0\n"
            + "endsection\nendpiece\n";
        DesignIO.LoadResult lr;
        try {
            lr = loadText(old);
        } catch (Exception ex) {
            check(false, "ScElemGate g old elem lines load", ex.toString());
            return;
        }
        List<Section> secs = lr.coaster().pieces.get(0).sections;
        check(secs.size() == 2 && lr.warnings() == 0,
            "ScElemGate g old elem lines load (two s/c sections, no damaged line)",
            secs.size() + " sections, warnings " + lr.warnings());
        check(lr.legacyScSections() == 2
                && SectionNames.SC_LEGACY_CONVERTED.equals(lr.legacyNote()),
            "ScElemGate g one legacy count per section (2) + legacyNote == SC_LEGACY_CONVERTED",
            "legacyScSections " + lr.legacyScSections() + ", note \"" + lr.legacyNote() + "\"");
        StraightCurveSection s0 = (StraightCurveSection) secs.get(0);
        StraightCurveSection s1 = (StraightCurveSection) secs.get(1);
        StraightCurveSection.Element e0 = s0.elements.get(0), e1 = s0.elements.get(1);
        // the loaded planar vs Element.curve(20, 90, 0, 2) (the κ-form)
        StraightCurveSection ref = scOf(rate0(StraightCurveSection.Element.straight(12, 0)),
            rate0(StraightCurveSection.Element.curve(20, 90, 0, 2)));
        ScIntegrator.Result rl = integ(s0, P0, Quat.IDENTITY), rr = integ(ref, P0, Quat.IDENTITY);
        double w = maxPosDiff(rl.samples(), rr.samples());
        boolean planar = e1.kind == StraightCurveSection.Kind.PLANAR && bits(e1.k0, 1 / 20.0)
            && e1.kLink && bits(e1.L, Math.abs(Math.toRadians(90)) * 20);
        check(w <= 1e-9 && planar,
            "ScElemGate g the migrated R20 90° planar == Element.curve(20, 90, 0, 2)'s integrate ≤ 1e-9 m",
            f("max pos diff %.2e m, k0 %s, L %s", w, e1.k0, e1.L));
        check(bits(e0.blendIn, 0.0) && isDefaultLane(e0) && isDefaultLane(e1)
                && bits(e1.blendIn, 2.0),
            "ScElemGate g section 1: element 0 blend 0, element 1 blend 2.0, both lanes the DEFAULT (global °/m 0 — the v11.0 roll fields dropped, Stage 1b)",
            "el0 blend " + e0.blendIn + " default " + isDefaultLane(e0)
                + ", el1 blend " + e1.blendIn + " default " + isDefaultLane(e1));
        StraightCurveSection.Element g = s1.elements.get(0);
        boolean gOk = s1.elements.size() == 1 && g.kind == StraightCurveSection.Kind.PLANAR
            && bits(g.k0, 1 / 10.0) && bits(g.k1, 1 / 10.0)
            && bits(g.L, Math.abs(Math.toRadians(50)) * 10) && bits(g.dirDeg, -20)
            && bits(g.blendIn, 0.0) && isDefaultLane(g);
        check(gOk, "ScElemGate g `elem curve 10 50 -20 4.213 1 0` → planar k 0.1, L = toRadians(50)·10, dir −20, element-0 blend DROPPED to 0, the default lane (rm/rv dropped)",
            f("k0 %s L %s dir %s blend %s default %s", g.k0, g.L, g.dirDeg, g.blendIn, isDefaultLane(g)));
    }

    /** The §2.1 default: global °/m, not absolute, nodes (0, 0), (L, 0)
     *  with flat DEFAULT_FRAC handles, uncut. */
    static boolean isDefaultLane(StraightCurveSection.Element e) {
        RollLane r = e.roll;
        return r.rollGlobal() && r.ratePerMeter() && !r.rollAbsolute() && !r.isCut()
            && nodesDiff(r.nodes, BezierBaker.holdLane(0, e.L, 0)) == null;
    }

    // ---------------------------------------------------------------
    // h — the LEGACY switch (file comparison)
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

    /** name|entry → last pos {x, y, z}. */
    static Map<String, double[]> lastPos(List<String> lines) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (String ln : lines) {
            String[] f = ln.split("\\s*\\|\\s*");
            if (f.length < 5) continue;
            String[] p = f[4].trim().split("\\s+");
            m.put(f[0].trim() + "|" + f[1].trim(), new double[] {
                Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]) });
        }
        return m;
    }

    /** The recorded fixtures' last positions, keyed "name|entry". */
    static Map<String, double[]> recordedLastPos() throws IOException {
        Path rec = Paths.get(LEGACY_DIR).resolve("legacy-checksums.txt");
        if (!Files.exists(rec)) return null;
        return lastPos(dataLines(rec));
    }

    /** LegacyAxisProbe's level cardinal fixtures, built in-JVM with the
     *  Stage-1b lanes: RATE0 = a local °/s 0 lane, GLOBAL0 = the default
     *  global °/m 0 lane (holds the arriving level bank). */
    static Map<String, StraightCurveSection> cardinalFixtures() {
        Map<String, StraightCurveSection> m = new LinkedHashMap<>();
        for (boolean rate : new boolean[] { true, false }) {
            String r = rate ? "RATE0" : "GLOBAL0";
            m.put("straight50/" + r + "|level", cardinal(rate,
                StraightCurveSection.Element.straight(50, 0)));
            m.put("R20-90dir0/" + r + "|level", cardinal(rate,
                StraightCurveSection.Element.curve(20, -90, 0, 0)));
            m.put("R30+45dir90/" + r + "|level", cardinal(rate,
                StraightCurveSection.Element.curve(30, 45, 90, 0)));
            m.put("R20+90dir0/" + r + "|level", cardinal(rate,
                StraightCurveSection.Element.curve(20, 90, 0, 0)));
        }
        return m;
    }

    static StraightCurveSection cardinal(boolean rate, StraightCurveSection.Element e) {
        if (rate) e.roll.setFlags(false, false, false);
        else e.roll.setFlags(true, true, false);
        List<StraightCurveSection.Element> l = new ArrayList<>();
        l.add(e);
        return new StraightCurveSection(l, 26);
    }

    static void rowH() throws Exception {
        Map<String, double[]> mr = recordedLastPos();
        if (mr == null) {
            System.out.println("SKIP  ScElemGate h legacy record — " + LEGACY_DIR
                + "/legacy-checksums.txt missing");
            return;
        }
        double worst = 0;
        int n = 0;
        boolean missing = false;
        String worstName = "";
        for (Map.Entry<String, StraightCurveSection> en : cardinalFixtures().entrySet()) {
            double[] rec = mr.get(en.getKey());
            if (rec == null) { missing = true; continue; }
            ScIntegrator.Result r = ScIntegrator.integrate(en.getValue(), DT, 26, P0, Quat.IDENTITY);
            Vec3 p = lastOf(r.samples()).pos();
            double d = Math.sqrt((p.x() - rec[0]) * (p.x() - rec[0]) + (p.y() - rec[1]) * (p.y() - rec[1])
                + (p.z() - rec[2]) * (p.z() - rec[2]));
            n++;
            if (d > worst) { worst = d; worstName = en.getKey(); }
        }
        check(!missing && n == 8 && worst <= 1e-9,
            "ScElemGate h the level, unbanked, cardinal fixtures of LegacyAxisProbe (straight50, R20-90dir0, R30+45dir90, R20+90dir0 × RATE0/GLOBAL0) match legacy-checksums.txt within 1e-9 m (the legacy switch is gone)",
            f("%d fixtures, worst %.2e m (%s)%s", n, worst, worstName, missing ? "; some fixture missing" : ""));
    }

    // ---------------------------------------------------------------
    // i — vertical entry
    // ---------------------------------------------------------------

    static void rowI() {
        Vec3 T0 = new Vec3(0, 1, 0), U0 = new Vec3(-1, 0, 0);
        Quat q0 = Quat.fromFrame(T0, U0);
        ElementChain.Pose en = ElementChain.entry(P0, q0);
        StraightCurveSection.Element e = rate0(StraightCurveSection.Element.curve(20, 90, 30, 0));
        Vec3 a = ElementChain.axisOf(e, en);
        Vec3 R0 = en.U().cross(en.T());
        double d = Math.toRadians(30);
        Vec3 exp = en.U().scale(Math.cos(d)).add(R0.scale(Math.sin(d)));
        boolean fin = a != null && Double.isFinite(a.x()) && Double.isFinite(a.y()) && Double.isFinite(a.z());
        double dev = fin ? a.sub(exp).length() : Double.NaN;
        double dU = en.U().sub(U0).length();
        ScIntegrator.Result r = integ(scOf(e), P0, q0);
        ElementChain.Pose ex = ElementChain.advance(en, e);
        double dEnd = ex.pos().sub(lastOf(r.samples()).pos()).length();
        check(fin && dev <= 1e-7 && dU <= 1e-7 && allFinite(r.samples()) && dEnd <= 1e-7,
            "ScElemGate i1 planar dir 30 at a vertical entry (T (0,1,0), U (−1,0,0)): finite axis = cos d·U0 + sin d·R0, finite compile",
            f("axis dev %.2e, ul vs U0 %.2e, exit vs chain %.2e m, %d samples", dev, dU, dEnd, r.samples().size()));

        // a planar AFTER a vertical border inside the section: element 0
        // pulls up to vertical from level, element 1 inherits its plane
        StraightCurveSection.Element up = rate0(StraightCurveSection.Element.curve(20, 90, 90, 0));
        StraightCurveSection.Element nx = rate0(StraightCurveSection.Element.curve(20, 45, 30, 0));
        StraightCurveSection sc2 = scOf(up, nx);
        List<ElementChain.Pose> ps = ElementChain.poses(sc2.elements, P0, Quat.IDENTITY);
        ElementChain.Pose mid = ps.get(1);
        double horiz2 = 1 - mid.T().y() * mid.T().y();
        // the analytic roll-free end frame of the pull-up: T (0,1,0), U (−1,0,0), R (0,0,−1)
        Vec3 expA = new Vec3(-1, 0, 0).scale(Math.cos(d)).add(new Vec3(0, 0, -1).scale(Math.sin(d)));
        Vec3 a1 = ElementChain.axisOf(sc2.elements.get(1), mid);
        ScIntegrator.Profile pr = new ScIntegrator.Profile(sc2, P0, Quat.IDENTITY);
        double dev1 = a1.sub(expA).length();
        boolean profSame = vecBits(pr.axis[1], a1);
        ScIntegrator.Result r2 = integ(sc2, P0, Quat.IDENTITY);
        double dEnd2 = ps.get(2).pos().sub(lastOf(r2.samples()).pos()).length();
        check(horiz2 < StraightCurveSection.HELIX_VERT_EPS && dev1 <= 1e-7 && profSame
                && allFinite(r2.samples()) && dEnd2 <= 1e-7,
            "ScElemGate i2 planar after a vertical border inherits the predecessor's roll-free end frame (≤ 1e-7), finite compile",
            f("horiz2 %.2e, axis vs analytic %.2e, Profile axis %s, exit vs chain %.2e m", horiz2, dev1,
                profSame ? "== axisOf" : "DIFFERS", dEnd2));

        // an EXISTING helix whose entry is vertical compiles (h → −R fallback)
        StraightCurveSection.Element hv = rate0(StraightCurveSection.Element.helical(10, 0.05, 0.0));
        hv.b1Deg = 0;
        ScIntegrator.Result r3 = integ(scOf(up, hv), P0, Quat.IDENTITY);
        check(allFinite(r3.samples()),
            "ScElemGate i3 an existing helix at a vertical entry compiles finitely (the h → −R fallback, no sentence)",
            r3.samples().size() + " samples");

        // the model refusal
        StraightCurveSection.Element p = rate0(StraightCurveSection.Element.curve(20, 45, 30, 0));
        boolean refused = !p.changeKind(StraightCurveSection.Kind.HELICAL, 90, 90);
        check(refused && p.kind == StraightCurveSection.Kind.PLANAR
                && "A helix can't start on vertical track — add a curve first."
                    .equals(SectionNames.HELIX_VERTICAL_WHY),
            "ScElemGate i4 changeKind(HELICAL, 90, 90) refuses (false, kind unchanged) + HELIX_VERTICAL_WHY verbatim",
            "returned " + !refused + ", kind " + p.kind);
    }

    // ---------------------------------------------------------------
    // j — mirror
    // ---------------------------------------------------------------

    static Coaster jFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> els = new ArrayList<>();
        els.add(StraightCurveSection.Element.straight(6, 2));
        StraightCurveSection.Element a = StraightCurveSection.Element.curve(20, 60, 45, 0);
        lane(a, 15, true, true, true);   // absolute bank 15°
        els.add(a);
        StraightCurveSection.Element h = StraightCurveSection.Element.helical(20, 1 / 25.0, 0.0);
        h.b1Deg = 20;
        rate0(h);
        els.add(h);
        StraightCurveSection.Element rp = StraightCurveSection.Element.planar(18, 0.02, -30, 1);
        rp.kLink = false;
        rp.k1 = -0.04;
        rp.kInterp = StraightCurveSection.Interp.SINUSOIDAL;
        lane(rp, 10, false, false, false);   // local °/s 10
        els.add(rp);
        StraightCurveSection.Element h2 = StraightCurveSection.Element.helical(15, -0.03, 0.0);
        h2.setBLink(true, 0);
        h2.bInterp = StraightCurveSection.Interp.SINUSOIDAL;
        lane(h2, -10, true, true, true);   // absolute bank −10°
        els.add(h2);
        els.add(StraightCurveSection.Element.straight(5, 2));
        p.sections.add(new StraightCurveSection(els, 26));
        c.pieces.add(p);
        return c;
    }

    static String mirrorCompare(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) {
            return "sample counts " + a.samples().size() + " vs " + b.samples().size();
        }
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample x = a.samples().get(i), y = b.samples().get(i);
            Vec3[] va = { x.pos(), x.T(), x.U() }, vb = { y.pos(), y.T(), y.U() };
            String[] nm = { "pos", "T", "U" };
            for (int k = 0; k < 3; k++) {
                if (va[k].x() != vb[k].x() || va[k].y() != vb[k].y() || vb[k].z() != -va[k].z()) {
                    return f("%s at sample %d: (%.17g,%.17g,%.17g) vs (%.17g,%.17g,%.17g)", nm[k], i,
                        va[k].x(), va[k].y(), va[k].z(), vb[k].x(), vb[k].y(), vb[k].z());
                }
            }
            if (x.v() != y.v() || x.Gv() != y.Gv() || y.Gl() != -x.Gl() || x.t() != y.t()) {
                return "v/felt/t at sample " + i;
            }
        }
        return null;
    }

    static void rowJ() {
        Coaster c = jFixture();
        TrackCompiler.Result r0 = rebuild(c);
        boolean ok = c.mirrorPiece(0);
        TrackCompiler.Result rm = rebuild(c);
        String d = ok ? mirrorCompare(r0, rm) : "mirrorPiece refused";
        check(d == null, "ScElemGate j mirrorPiece (every kind, planar dir 45, helix β1 20, ramps, abs + local roll lanes): compile == the z-negated original BITWISE",
            d == null ? r0.samples().size() + " samples (pos/T/U z negated, x/y equal, Gl negated)" : d);
        boolean ok2 = c.mirrorPiece(0);
        TrackCompiler.Result r2 = rebuild(c);
        String d2 = ok2 ? resultDiff(r0, r2) : "mirrorPiece refused";
        check(d2 == null, "ScElemGate j mirror² == identity bitwise", d2 == null ? "" : d2);
    }

    // ---------------------------------------------------------------
    // k — determinism through Coaster
    // ---------------------------------------------------------------

    static Coaster kFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> els = new ArrayList<>();
        StraightCurveSection.Element e0 = StraightCurveSection.Element.straight(6, 2);
        e0.locks = StraightCurveSection.Element.LOCK_LEN;
        els.add(e0);
        StraightCurveSection.Element e1 = StraightCurveSection.Element.curve(20, 60, 30, 0);
        e1.locks = StraightCurveSection.Element.LOCK_K0 | StraightCurveSection.Element.LOCK_DIR;
        lane(e1, 20, true, true, true);   // absolute bank 20°
        els.add(e1);
        StraightCurveSection.Element e2 = StraightCurveSection.Element.helical(20, 1 / 25.0, 0.0);
        e2.b1Deg = 12;
        e2.bInterp = StraightCurveSection.Interp.SINUSOIDAL;
        e2.blendIn = 1.5;
        e2.locks = StraightCurveSection.Element.LOCK_B1 | StraightCurveSection.Element.LOCK_HEAD;
        els.add(e2);
        StraightCurveSection.Element e3 = StraightCurveSection.Element.planar(18, 0.02, 210);
        e3.kLink = false;
        e3.k1 = 0.06;
        e3.kInterp = StraightCurveSection.Interp.SINUSOIDAL;
        e3.locks = StraightCurveSection.Element.LOCK_MASK;
        lane(e3, 8, false, false, false);   // local °/s 8
        els.add(e3);
        StraightCurveSection.Element e4 = StraightCurveSection.Element.helical(15, -0.03, 0.0);
        e4.setBLink(true, 0);
        e4.kLink = false;
        e4.k1 = -0.05;
        e4.locks = StraightCurveSection.Element.LOCK_X | StraightCurveSection.Element.LOCK_Y
            | StraightCurveSection.Element.LOCK_Z;
        els.add(e4);
        StraightCurveSection.Element e5 = StraightCurveSection.Element.straight(8, 3);
        e5.locks = StraightCurveSection.Element.LOCK_ELEV;
        els.add(e5);
        p.sections.add(new StraightCurveSection(els, 26));
        // a continuation the reconcile re-freezes (design 20 m/s → real)
        p.sections.add(StraightCurveSection.straight(10, 20));
        c.pieces.add(p);
        return c;
    }

    static boolean vdEq(long[] a, long[] b) { return Arrays.equals(a, b); }

    static void rowK() throws Exception {
        Coaster c = kFixture();
        TrackCompiler.Result a = TrackCompiler.compile(c, DT), b = TrackCompiler.compile(c, DT);
        String d = resultDiff(a, b);
        check(d == null, "ScElemGate k compile twice bitwise (6 elements, every kind + locks, + a continuation)",
            d == null ? a.samples().size() + " samples" : d);
        TrackCompiler.Result r0 = rebuild(c);
        long[] vd0 = vdBits(c);
        TrackCompiler.Result r1 = rebuild(c);
        long[] vd1 = vdBits(c);
        String d1 = resultDiff(r0, r1);
        check(d1 == null && vdEq(vd0, vd1), "ScElemGate k rebuild at rest is a bitwise no-op (no vDesign rewrite)",
            (d1 == null ? "" : d1) + (vdEq(vd0, vd1) ? "" : " vDesign rewritten"));
        TrackCompiler.Result r2 = TrackCompiler.compile(c, DT);
        String d2 = resultDiff(r0, r2);
        check(d2 == null, "ScElemGate k a third compile after the no-op == the settled one", d2 == null ? "" : d2);

        // x/y/z translate
        {
            Coaster m = kFixture();
            m.pieces.get(0).x += 37.5;
            m.pieces.get(0).y += 12.25;
            m.pieces.get(0).z -= 8.5;
            TrackCompiler.Result rm = rebuild(m);
            Vec3 off = new Vec3(37.5, 12.25, -8.5);
            boolean same = rm.samples().size() == r0.samples().size();
            double worst = 0;
            String bad = null;
            for (int i = 0; same && i < r0.samples().size(); i++) {
                FVDIntegrator.Sample x = r0.samples().get(i), y = rm.samples().get(i);
                worst = Math.max(worst, y.pos().sub(off).sub(x.pos()).length());
                if (bad == null && !(vecBits(x.T(), y.T()) && vecBits(x.U(), y.U()) && bits(x.v(), y.v())
                        && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl()) && bits(x.t(), y.t())
                        && bits(x.s(), y.s()) && bits(r0.trueV()[i], rm.trueV()[i]))) {
                    bad = "sample " + i;
                }
            }
            check(same && bad == null && worst <= 1e-9 && vdEq(vd0, vdBits(m)),
                "ScElemGate k piece x/y/z move bitwise-rigid (T/U/v/felt/t/s/trueV bitwise, pos shifted ≤ 1e-9 m, no rewrite)",
                f("worst %.2e m%s%s", worst, bad == null ? "" : ", " + bad,
                    vdEq(vd0, vdBits(m)) ? "" : ", vDesign rewritten"));
        }
        // yaw
        {
            Coaster m = kFixture();
            m.pieces.get(0).yawDeg += 37;
            TrackCompiler.Result rm = rebuild(m);
            Quat rot = Quat.aboutY(37);
            boolean same = rm.samples().size() == r0.samples().size();
            double worstP = 0, worstH = 0;
            String bad = null;
            for (int i = 0; same && i < r0.samples().size(); i++) {
                FVDIntegrator.Sample x = r0.samples().get(i), y = rm.samples().get(i);
                Vec3 exp = P0.add(rot.rotate(x.pos().sub(P0)));
                worstP = Math.max(worstP, exp.sub(y.pos()).length());
                double hx = Math.toDegrees(Math.atan2(-x.T().z(), x.T().x()));
                double hy = Math.toDegrees(Math.atan2(-y.T().z(), y.T().x()));
                if (Math.hypot(x.T().x(), x.T().z()) > 1e-3) {
                    double dh = (hy - hx - 37 + 540) % 360 - 180;
                    worstH = Math.max(worstH, Math.abs(dh));
                }
                if (bad == null && !(bits(x.pos().y(), y.pos().y()) && bits(x.v(), y.v())
                        && bits(x.Gv(), y.Gv()) && bits(x.Gl(), y.Gl())
                        && bits(r0.trueV()[i], rm.trueV()[i]))) {
                    bad = "sample " + i;
                }
            }
            check(same && bad == null && worstP <= 1e-9 && worstH <= 1e-9 && vdEq(vd0, vdBits(m)),
                "ScElemGate k piece yaw +37 bitwise-rigid (heights/v/felt/trueV bitwise, heading +37 ≤ 1e-9°, pos ≤ 1e-9 m, no rewrite)",
                f("pos %.2e m, heading %.2e°%s%s", worstP, worstH, bad == null ? "" : ", " + bad,
                    vdEq(vd0, vdBits(m)) ? "" : ", vDesign rewritten"));
        }
        // save → load → rebuild
        {
            String t = save(c);
            Coaster l = loadText(t).coaster();
            TrackCompiler.Result rl = rebuild(l);
            String dl = resultDiff(r0, rl);
            check(dl == null && vdEq(vd0, vdBits(l)) && t.equals(save(l)),
                "ScElemGate k save → load → rebuild BITWISE (and re-save byte-identical)",
                dl == null ? "" : dl);
        }
    }

    // ---------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("ScElemGate — D259 §1.9 (s/c element model, Stage 1a; re-pinned for the"
            + " per-element roll lane in Stage 1b — the legacy-axis switch is gone)");
        rowA1E();
        rowA2();
        rowB();
        rowC();
        rowD();
        rowE6();
        rowF();
        rowG();
        rowH();
        rowI();
        rowJ();
        rowK();
        System.out.println();
        System.out.println("ScElemGate: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
