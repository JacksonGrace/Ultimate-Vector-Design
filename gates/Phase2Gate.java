import uvd.*;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-2 gate (geo sections v10.0 + straight/curve sections v11.0).
 * Run against target/classes:
 *   javac -cp target/classes -d <scratch> Phase2Gate.java
 *   java  -cp "target/classes;<scratch>" Phase2Gate
 * Extends the Phase-1 gates (TESTING.md #11): the bit-for-bit force
 * baseline must STILL pass, plus the geo analytic/invariance/IO checks
 * (TESTING #12), reversal (#13) and the straight/curve suite (#14):
 * exact line/circle/direction, smoothing turn-angle preservation +
 * clamping, reversal, chain invariance + spans, FVDD3 round-trip.
 */
public class Phase2Gate {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    // ---------- shared fixtures ----------

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** The shipped default layout (same tables as FvdDesigner). */
    static List<List<BezierBaker.Node>> defaultLanes() {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));
        return lanes;
    }

    static List<FVDIntegrator.Keyframe> constRate(double p, double y, double r, double dur) {
        List<FVDIntegrator.Keyframe> kfs = new ArrayList<>();
        kfs.add(new FVDIntegrator.Keyframe(0, p, y, r));
        kfs.add(new FVDIntegrator.Keyframe(dur, p, y, r));
        return kfs;
    }

    static boolean bitsEq(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    static boolean vecBitsEq(Vec3 a, Vec3 b) {
        return bitsEq(a.x(), b.x()) && bitsEq(a.y(), b.y()) && bitsEq(a.z(), b.z());
    }

    static double angleDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.dot(b)))));
    }

    public static void main(String[] args) throws Exception {
        bitForBitBaseline();
        geoAnalytic();
        geoForceEquivalence();
        mixedChainInvariance();
        roundTrip();
        feltForces();
        reversal();
        scAnalytic();
        scSmoothing();
        scReversal();
        scChainAndSpans();
        scRoundTrip();
        scRoll();
        multiPiece();
        pieceReversal();
        bridges();
        bridgeSmooth();
        // re-wired after the 2026-08-18 gate recovery (their main()
        // call lines were in a lost session transcript)
        influence();
        convRound();
        // NOTE: the recovered Phase2Gate is PARTIAL — the convert /
        // partial-convert / node-split / connect-sections / speed-
        // reconcile / split-resolution / orientAlign / delete-keeps-
        // handles families (~130 checks, historic total 258) were
        // authored in a session whose transcript is gone; see
        // Phase2Gate.lost-edits.txt for the surviving fragments.
        System.out.println(failures == 0 ? "\nALL GATES PASS" : "\n" + failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    // ---------- straight/curve (v11.0, TESTING #14) ----------

    static StraightCurveSection scOf(double vDesign, StraightCurveSection.Element... els) {
        List<StraightCurveSection.Element> l = new ArrayList<>(List.of(els));
        return new StraightCurveSection(l, vDesign);
    }

    static FVDIntegrator.Sample lastOf(List<FVDIntegrator.Sample> s) {
        return s.get(s.size() - 1);
    }

    /** |dT/ds| around sample i (central difference over ±3 samples). */
    static double sampleCurvature(List<FVDIntegrator.Sample> s, int i) {
        int i0 = Math.max(0, i - 3), i1 = Math.min(s.size() - 1, i + 3);
        double ds = s.get(i1).s() - s.get(i0).s();
        return ds < 1e-9 ? 0
            : s.get(i1).T().sub(s.get(i0).T()).length() / ds;
    }

    /** |ΔT|/Δs between two specific samples (tight seam probe). */
    static double stepCurvature(List<FVDIntegrator.Sample> s, int i, int j) {
        double ds = s.get(j).s() - s.get(i).s();
        return ds < 1e-9 ? 0
            : s.get(j).T().sub(s.get(i).T()).length() / ds;
    }

    // (h) analytic elements: exact line, exact circle, direction planes
    static void scAnalytic() {
        // one straight, level: endpoint exact, frame untouched, s exact
        ScIntegrator.Result r = ScIntegrator.integrate(
            scOf(26, StraightCurveSection.Element.straight(50, 0)),
            0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        FVDIntegrator.Sample last = lastOf(r.samples());
        check(last.pos().sub(new Vec3(50, 21, 0)).length() < 1e-9,
            String.format("s/c: straight 50 m lands exactly (err %.2e)",
                last.pos().sub(new Vec3(50, 21, 0)).length()));
        check(bitsEq(last.s(), 50.0), "s/c: s lands EXACTLY on the total length");
        check(angleDeg(last.T(), new Vec3(1, 0, 0)) < 1e-9
                && angleDeg(last.U(), new Vec3(0, 1, 0)) < 1e-9,
            "s/c: straight keeps the frame");
        boolean felt = true;
        for (FVDIntegrator.Sample sm : r.samples()) {
            felt &= Math.abs(sm.Gv() - 1) < 1e-9 && Math.abs(sm.Gl()) < 1e-9;
        }
        check(felt, "s/c felt G: straight level = 1 g / 0");

        // flat 90-degree right turn R20: exact circle, exact endpoint
        // (angle -90 since D187: +angle = rider-LEFT now — the same
        // physical right circle as the historic +90)
        r = ScIntegrator.integrate(
            scOf(26, StraightCurveSection.Element.curve(20, -90, 0, 0)),
            0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        last = lastOf(r.samples());
        Vec3 center = new Vec3(0, 21, -20);   // -angle/dir 0 = rider-right
        double maxRadErr = 0;
        for (FVDIntegrator.Sample sm : r.samples()) {
            maxRadErr = Math.max(maxRadErr,
                Math.abs(sm.pos().sub(center).length() - 20));
        }
        check(maxRadErr < 1e-7, String.format(
            "s/c: R20 turn is an exact circle (max radial err %.2e)", maxRadErr));
        check(last.pos().sub(new Vec3(20, 21, -20)).length() < 1e-7,
            String.format("s/c: 90° right turn endpoint exact (err %.2e)",
                last.pos().sub(new Vec3(20, 21, -20)).length()));
        check(angleDeg(last.T(), new Vec3(0, 0, -1)) < 1e-6,
            "s/c: 90° right turn ends heading -z (right of +x)");
        // D184: a RIGHT turn pushes the body LEFT — negative now
        double expGl = -(26 * 26 / 20.0 / FVDIntegrator.G0);
        check(Math.abs(r.samples().get(0).Gl() - expGl) < 1e-9,
            String.format("s/c felt G: flat R20 right turn Gl = -v²/Rg = %.3f", expGl));

        // dir 90 = pull up: stays in the x-y plane, exact pitch angle
        r = ScIntegrator.integrate(
            scOf(26, StraightCurveSection.Element.curve(30, 45, 90, 0)),
            0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        last = lastOf(r.samples());
        boolean planar = true;
        for (FVDIntegrator.Sample sm : r.samples()) {
            planar &= Math.abs(sm.pos().z()) < 1e-7;
        }
        check(planar && last.pos().y() > 21 + 5,
            "s/c: dir 90 curve pulls UP in the vertical plane");
        check(Math.abs(angleDeg(last.T(), new Vec3(1, 0, 0)) - 45) < 1e-6,
            String.format("s/c: 45° vertical curve = exact pitch (got %.9f)",
                angleDeg(last.T(), new Vec3(1, 0, 0))));
        double gv0 = r.samples().get(0).Gv();
        double expGv = 26 * 26 / 30.0 / FVDIntegrator.G0 + 1;
        check(Math.abs(gv0 - expGv) < 1e-9,
            String.format("s/c felt G: pull-up entry Gv = %.3f", expGv));

        // the mirror: dir 0, angle +90 turns LEFT (+z) since D187
        r = ScIntegrator.integrate(
            scOf(26, StraightCurveSection.Element.curve(20, 90, 0, 0)),
            0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        check(angleDeg(lastOf(r.samples()).T(), new Vec3(0, 0, 1)) < 1e-6,
            "s/c: positive angle turns LEFT (+z, D187)");
    }

    // (i) smoothing: symmetric sine blend preserves the total turn
    // angle EXACTLY for straight<->arc transitions; halves clamp inside
    // short elements without breaking that
    static void scSmoothing() {
        StraightCurveSection sharp = scOf(26,
            StraightCurveSection.Element.straight(15, 0),
            StraightCurveSection.Element.curve(20, -90, 0, 0),   // D187
            StraightCurveSection.Element.straight(15, 0));
        StraightCurveSection smooth = scOf(26,
            StraightCurveSection.Element.straight(15, 0),
            StraightCurveSection.Element.curve(20, -90, 0, 6),
            StraightCurveSection.Element.straight(15, 6));
        ScIntegrator.Result rs = ScIntegrator.integrate(
            sharp, 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        ScIntegrator.Result rm = ScIntegrator.integrate(
            smooth, 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        FVDIntegrator.Sample es = lastOf(rs.samples()), em = lastOf(rm.samples());
        // both land on the ANALYTIC 90-degree heading: the sharp chain
        // via breakpoint-aligned sub-steps, the smooth one because the
        // symmetric sine blend integrates to the mean curvature
        Vec3 exp90 = new Vec3(0, 0, -1);
        check(angleDeg(es.T(), exp90) < 1e-6,
            String.format("s/c: SHARP chain heading analytic (off %.2e°)",
                angleDeg(es.T(), exp90)));
        check(angleDeg(em.T(), exp90) < 1e-6,
            String.format("s/c smoothing: total turn preserved analytically (off %.2e°)",
                angleDeg(em.T(), exp90)));
        check(bitsEq(es.s(), em.s()), "s/c smoothing: arc length unchanged");
        check(es.pos().sub(em.pos()).length() > 0.05,
            "s/c smoothing: the path itself DOES change (eased corner)");

        // blendIn 0 == sharp bitwise (the blend machinery must add
        // zero arithmetic when it's off)
        StraightCurveSection zero = scOf(26,
            StraightCurveSection.Element.straight(15, 0),
            StraightCurveSection.Element.curve(20, -90, 0, 0),   // D187
            StraightCurveSection.Element.straight(15, 0));
        ScIntegrator.Result rz = ScIntegrator.integrate(
            zero, 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        boolean bitEq = rz.samples().size() == rs.samples().size();
        for (int i = 0; bitEq && i < rz.samples().size(); i++) {
            bitEq = vecBitsEq(rz.samples().get(i).pos(), rs.samples().get(i).pos());
        }
        check(bitEq, "s/c smoothing: blendIn 0 is bitwise sharp");

        // overflowing blends clamp inside a short middle element and
        // STILL preserve the turn (each blend stays boundary-centered)
        StraightCurveSection clamped = scOf(26,
            StraightCurveSection.Element.straight(10, 0),
            StraightCurveSection.Element.curve(5, -20, 0, 10),   // D187
            StraightCurveSection.Element.straight(10, 10));
        FVDIntegrator.Sample ec = lastOf(ScIntegrator.integrate(
            clamped, 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY).samples());
        Vec3 exp20 = new Vec3(Math.cos(Math.toRadians(20)), 0,
            -Math.sin(Math.toRadians(20)));
        // v20.0 (D259.1a): every element border is a leg LANDING now — a
        // blended border lands at its centre too — so the step phases
        // inside this 1.745 m middle element changed; the RK4 residual is
        // 2.96e-6° at dt 0.01 and exactly 0 at dt ≤ 0.005 (BlendTurnProbe),
        // a discretization phase, not a law defect: the bound re-baselined
        // from 1e-6° to 1e-5° (the legacy switch keeps today's 0).
        check(angleDeg(ec.T(), exp20) < 1e-5,
            String.format("s/c smoothing: clamped blends keep the turn (off %.2e°)",
                angleDeg(ec.T(), exp20)));
    }

    // (j) reversal of a straight/curve section: same point set, entry
    // pose landed, t/s ascending (mirrors the force reversal gate)
    static void scReversal() {
        StraightCurveSection fwd = scOf(26,
            StraightCurveSection.Element.straight(10, 0),
            StraightCurveSection.Element.curve(20, 90, 0, 4),
            StraightCurveSection.Element.straight(5, 4));
        StraightCurveSection rev = scOf(26,
            StraightCurveSection.Element.straight(10, 0),
            StraightCurveSection.Element.curve(20, 90, 0, 4),
            StraightCurveSection.Element.straight(5, 4));
        rev.reversed = true;
        TrackPose id = new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY);
        List<FVDIntegrator.Sample> f = TrackCompiler.passG(fwd, id, 0.01).samples();
        List<FVDIntegrator.Sample> r = TrackCompiler.passG(rev, id, 0.01).samples();
        int n = f.size();
        check(r.size() == n, "s/c reversal: same sample count");
        boolean dist = true;
        int[][] pairs = { {0, n - 1}, {0, n / 2}, {n / 3, 2 * n / 3} };
        for (int[] p : pairs) {
            double df = f.get(p[0]).pos().sub(f.get(p[1]).pos()).length();
            double dr = r.get(n - 1 - p[0]).pos().sub(r.get(n - 1 - p[1]).pos()).length();
            dist &= Math.abs(df - dr) < 1e-9;
        }
        check(dist, "s/c reversal: point set rigid-motion identical");
        check(r.get(0).pos().sub(id.pos()).length() < 1e-9
                && Math.abs(r.get(0).t()) < 1e-12,
            "s/c reversal: entry lands on the entry pose");
        boolean mono = true;
        for (int i = 1; i < n; i++) {
            mono &= r.get(i).t() > r.get(i - 1).t() && r.get(i).s() >= r.get(i - 1).s();
        }
        check(mono, "s/c reversal: t/s ascend in traversal order");
    }

    // (k) force -> s/c chain: continuity, §0 prefix invariance, spans
    static void scChainAndSpans() {
        Coaster solo = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        List<FVDIntegrator.Sample> soloS = TrackCompiler.compile(solo, 0.01).samples();

        Coaster c = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        c.pieces.get(0).sections.add(scOf(24,
            StraightCurveSection.Element.straight(12, 0),
            StraightCurveSection.Element.curve(25, 60, 0, 5),
            StraightCurveSection.Element.straight(8, 5)));
        TrackCompiler.Result res = TrackCompiler.compile(c, 0.01);
        List<FVDIntegrator.Sample> all = res.samples();

        boolean mono = true, cont = true;
        for (int i = 1; i < all.size(); i++) {
            mono &= all.get(i).t() > all.get(i - 1).t();
            cont &= all.get(i).pos().sub(all.get(i - 1).pos()).length() < 1.0;
        }
        check(mono && cont, "s/c chain: continuous, t monotonic");

        boolean prefix = true;
        for (int i = 0; i < soloS.size(); i++) {
            prefix &= vecBitsEq(soloS.get(i).pos(), all.get(i).pos());
        }
        check(prefix, "s/c chain: force prefix bitwise == solo compile");

        // editing an element reshapes ONLY the s/c stretch
        Coaster c2 = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        c2.pieces.get(0).sections.add(scOf(24,
            StraightCurveSection.Element.straight(12, 0),
            StraightCurveSection.Element.curve(25, -60, 0, 5),   // mirrored turn
            StraightCurveSection.Element.straight(8, 5)));
        List<FVDIntegrator.Sample> all2 = TrackCompiler.compile(c2, 0.01).samples();
        boolean prefix2 = true;
        for (int i = 0; i < soloS.size(); i++) {
            prefix2 &= vecBitsEq(soloS.get(i).pos(), all2.get(i).pos());
        }
        check(prefix2 && !vecBitsEq(lastOf(all).pos(), lastOf(all2).pos()),
            "s/c §0: element edit moves the s/c stretch, never upstream");

        // spans: aligned, contiguous, s-length = the authored total
        List<TrackCompiler.Span> spans = res.spans();
        check(spans.size() == 2, "spans: one per section");
        TrackCompiler.Span s0 = spans.get(0), s1 = spans.get(1);
        check(s0.i0() == 0 && s1.i0() == s0.i1() + 1
                && s1.i1() == all.size() - 1,
            "spans: contiguous sample index ranges");
        check(bitsEq(s0.t1(), s1.t0()) && bitsEq(s0.s0(), 0.0),
            "spans: time/arc offsets chain exactly");
        double scLen = 12 + Math.toRadians(60) * 25 + 8;
        check(Math.abs(s1.sLen() - scLen) < 1e-6,
            String.format("spans: s/c span length = authored %.3f m (got %.3f)",
                scLen, s1.sLen()));
        check(Math.abs(lastOf(all).t() - s1.t1()) < 1e-12,
            "spans: last span ends at the ride end");
    }

    // (m) roll (v11.1, D69): rate mode = the roll lane's semantics;
    // global mode = bank held against the horizon
    static void scRoll() {
        // RATE on a straight: exact body roll, path unbent
        StraightCurveSection rate = scOf(15,
            StraightCurveSection.Element.straight(30, 0));
        rate.elements.get(0).rollMode = StraightCurveSection.RollMode.RATE;
        rate.elements.get(0).rollVal = 40;
        ScIntegrator.Result r = ScIntegrator.integrate(
            rate, 0.01, 15, new Vec3(0, 21, 0), Quat.IDENTITY);
        FVDIntegrator.Sample last = lastOf(r.samples());
        check(Math.abs(angleDeg(last.U(), new Vec3(0, 1, 0)) - 40 * last.t()) < 1e-6,
            String.format("s/c roll RATE: 40°/s = exact twist (got %.6f over %.3f s)",
                angleDeg(last.U(), new Vec3(0, 1, 0)), last.t()));
        check(angleDeg(last.T(), new Vec3(1, 0, 0)) < 1e-9
                && last.pos().sub(new Vec3(30, 21, 0)).length() < 1e-9,
            "s/c roll RATE: roll never bends the spine");

        // GLOBAL 25 on a straight: bank eases in, holds exactly
        StraightCurveSection glob = scOf(26,
            StraightCurveSection.Element.straight(30, 0));
        glob.elements.get(0).rollVal = 25;   // factory default is GLOBAL
        r = ScIntegrator.integrate(
            glob, 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        last = lastOf(r.samples());
        check(Math.abs(angleDeg(last.U(), new Vec3(0, 1, 0)) - 25) < 1e-9,
            String.format("s/c roll GLOBAL: 25° bank held exactly (got %.9f)",
                angleDeg(last.U(), new Vec3(0, 1, 0))));
        check(last.pos().sub(new Vec3(30, 21, 0)).length() < 1e-9,
            "s/c roll GLOBAL: the bank projection never moves the spine");
        // bank sign matches the roll lane: +roll about T moves U toward −R
        check(last.U().z() > 0,
            "s/c roll GLOBAL: positive bank rolls the same way as +roll rate");

        // THE user scenario: slope down, then a flat turn — GLOBAL 0
        // keeps the rails LEVEL (v11.0 rate-0 drifts into a cone bank)
        StraightCurveSection level = scOf(26,
            StraightCurveSection.Element.curve(30, -20, 90, 0),   // dip 20°
            StraightCurveSection.Element.straight(15, 4),
            StraightCurveSection.Element.curve(20, 90, 0, 4));    // then turn
        StraightCurveSection drift = scOf(26,
            StraightCurveSection.Element.curve(30, -20, 90, 0),
            StraightCurveSection.Element.straight(15, 4),
            StraightCurveSection.Element.curve(20, 90, 0, 4));
        for (StraightCurveSection.Element e : drift.elements) {
            e.rollMode = StraightCurveSection.RollMode.RATE;      // v11.0 behavior
        }
        FVDIntegrator.Sample lv = lastOf(ScIntegrator.integrate(
            level, 0.01, 26, new Vec3(0, 40, 0), Quat.IDENTITY).samples());
        FVDIntegrator.Sample dr = lastOf(ScIntegrator.integrate(
            drift, 0.01, 26, new Vec3(0, 40, 0), Quat.IDENTITY).samples());
        check(Math.abs(lv.R().y()) < 1e-9,
            String.format("s/c roll GLOBAL 0: slope+turn stays LEVEL (R.y %.2e)",
                lv.R().y()));
        check(Math.abs(dr.R().y()) > 0.01,
            String.format("s/c roll RATE 0: same chain DRIFTS banked (R.y %.3f)"
                + " — the option exists for a reason", dr.R().y()));

        // v11.0-format elem lines (no roll fields) load as RATE 0
        String old = "FVDD3\npiece 0 21 0 0\nsection sc 26.0 distance 0\n"
            + "elem straight 12.0 0.0\nelem curve 20.0 90.0 0.0 2.0\n"
            + "endsection\nendpiece\n";
        try {
            DesignIO.LoadResult lr = DesignIO.load(
                new BufferedReader(new StringReader(old)));
            StraightCurveSection sc =
                (StraightCurveSection) lr.coaster().pieces.get(0).sections.get(0);
            boolean ok = true;
            for (StraightCurveSection.Element e : sc.elements) {
                ok &= e.rollMode == StraightCurveSection.RollMode.RATE
                   && e.rollVal == 0;
            }
            check(ok, "FVDD3: v11.0 elem lines load as RATE 0 (geometry kept)");
        } catch (Exception ex) {
            check(false, "FVDD3: v11.0 elem lines load (" + ex + ")");
        }
    }

    // (n) pieces (v12.0, D70): split / attach / placement, TESTING #15
    static void multiPiece() {
        // ---- split at an EXACTLY LEVEL boundary reproduces the ride
        // bitwise (modulo the duplicated boundary sample a new piece
        // keeps): s/c straight exits level/identity by construction
        Coaster orig = new Coaster();
        TrackPiece tp = new TrackPiece(3, 21, -2, 0);
        tp.sections.add(StraightCurveSection.straight(10, 26));
        tp.sections.add(new ForceSection(defaultLanes(), 26));
        orig.pieces.add(tp);
        TrackCompiler.Result or = TrackCompiler.compile(orig, 0.01);
        List<FVDIntegrator.Sample> os = or.samples();

        Coaster split = new Coaster();
        TrackPiece sp0 = new TrackPiece(3, 21, -2, 0);
        sp0.sections.add(StraightCurveSection.straight(10, 26));
        sp0.sections.add(new ForceSection(defaultLanes(), 26));
        split.pieces.add(sp0);
        FVDIntegrator.Sample bnd = os.get(or.spans().get(0).i1());
        int np = split.splitPiece(0, 1, or.spans().get(0));
        check(np == 1 && split.pieces.size() == 2
                && split.pieces.get(0).sections.size() == 1
                && split.pieces.get(1).sections.size() == 1,
            "split: sections move to the new piece");
        List<FVDIntegrator.Sample> ss = TrackCompiler.compile(split, 0.01).samples();
        int nSc = or.spans().get(0).i1() + 1;   // piece-1 sample count
        boolean bitwise = ss.size() == os.size() + 1;
        for (int i = 0; bitwise && i < os.size(); i++) {
            FVDIntegrator.Sample a = os.get(i);
            FVDIntegrator.Sample b = ss.get(i < nSc ? i : i + 1);
            bitwise = vecBitsEq(a.pos(), b.pos()) && vecBitsEq(a.T(), b.T())
                   && vecBitsEq(a.U(), b.U()) && bitsEq(a.t(), b.t());
        }
        check(bitwise, "split: LEVEL boundary re-lays the ride bitwise"
            + " (+1 duplicated boundary sample)");
        check(vecBitsEq(ss.get(nSc).pos(), ss.get(nSc - 1).pos()),
            "split: the piece's first sample sits ON the boundary");

        // ---- attach = the inverse: back to one piece, bitwise original
        Coaster back = new Coaster();
        back.pieces.add(split.pieces.get(0));
        back.pieces.add(split.pieces.get(1));
        back.attachPiece(0, 1);
        check(back.pieces.size() == 1
                && back.pieces.get(0).sections.size() == 2,
            "attach: sections re-chain onto the target piece");
        List<FVDIntegrator.Sample> bs = TrackCompiler.compile(back, 0.01).samples();
        boolean same = bs.size() == os.size();
        for (int i = 0; same && i < os.size(); i++) {
            same = vecBitsEq(os.get(i).pos(), bs.get(i).pos())
                && bitsEq(os.get(i).t(), bs.get(i).t());
        }
        check(same, "attach after split: bitwise the original ride");

        // ---- split at a PITCHED/BANKED boundary (v12.1, D71): the
        // full boundary orientation lands in baseOrient, so the split
        // changes NOTHING — bitwise, per the user rule
        Coaster pit = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        pit.pieces.get(0).sections.add(Coaster.holdSection(1, 0.4, 0, 20));
        TrackCompiler.Result pr = TrackCompiler.compile(pit, 0.01);
        Coaster pit2 = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        pit2.pieces.get(0).sections.add(Coaster.holdSection(1, 0.4, 0, 20));
        pit2.splitPiece(0, 1, pr.spans().get(0));
        List<FVDIntegrator.Sample> ps = TrackCompiler.compile(pit2, 0.01).samples();
        int pn = pr.spans().get(0).i1() + 1;   // piece-1 sample count
        boolean pbit = ps.size() == pr.samples().size() + 1;
        for (int i = 0; pbit && i < pr.samples().size(); i++) {
            FVDIntegrator.Sample a = pr.samples().get(i);
            FVDIntegrator.Sample b = ps.get(i < pn ? i : i + 1);
            pbit = vecBitsEq(a.pos(), b.pos()) && vecBitsEq(a.T(), b.T())
                && vecBitsEq(a.U(), b.U());
        }
        check(pbit, "split: PITCHED boundary also bitwise — orientation"
            + " never changes in a split (baseOrient)");

        // ---- placement moves are rigid and §0-isolated
        Coaster mv = new Coaster();
        TrackPiece m0 = new TrackPiece(3, 21, -2, 0);
        m0.sections.add(StraightCurveSection.straight(10, 26));
        m0.sections.add(new ForceSection(defaultLanes(), 26));
        mv.pieces.add(m0);
        mv.splitPiece(0, 1, or.spans().get(0));
        TrackPiece moved = mv.pieces.get(1);
        moved.x += 37;
        moved.z -= 12;
        moved.yawDeg = 30;
        TrackCompiler.Result mr = TrackCompiler.compile(mv, 0.01);
        List<FVDIntegrator.Sample> ms = mr.samples();
        boolean p1same = true;
        for (int i = 0; i < nSc; i++) {
            p1same &= vecBitsEq(ms.get(i).pos(), os.get(i).pos());
        }
        check(p1same, "placement: moving piece 2 never touches piece 1 (§0)");
        int mi0 = mr.spans().get(1).i0(), mi1 = mr.spans().get(1).i1();
        boolean rigid = true;
        int[][] pairs = { {0, (mi1 - mi0) / 2}, {0, mi1 - mi0},
                          {(mi1 - mi0) / 3, mi1 - mi0} };
        for (int[] pr2 : pairs) {
            double dOld = ss.get(nSc + pr2[0]).pos()
                .sub(ss.get(nSc + pr2[1]).pos()).length();
            double dNew = ms.get(mi0 + pr2[0]).pos()
                .sub(ms.get(mi0 + pr2[1]).pos()).length();
            rigid &= Math.abs(dOld - dNew) < 1e-9;
        }
        check(rigid && ms.get(mi0).pos()
                .sub(new Vec3(moved.x, moved.y, moved.z)).length() < 1e-12,
            "placement: piece 2 moves RIGIDLY onto its new origin/yaw");

        // ---- FVDD3 round-trip with two placed pieces
        try {
            StringWriter w1 = new StringWriter();
            DesignIO.save(mv, DesignIO.Cosmetics.defaults(), new PrintWriter(w1));
            DesignIO.LoadResult lr = DesignIO.load(
                new BufferedReader(new StringReader(w1.toString())));
            StringWriter w2 = new StringWriter();
            DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));
            TrackPiece lp = lr.coaster().pieces.get(1);
            check(w1.toString().equals(w2.toString())
                    && lr.coaster().pieces.size() == 2
                    && Math.abs(lp.x - moved.x) < 5e-5
                    && Math.abs(lp.yawDeg - 30) < 5e-5,
                "FVDD3 round-trip byte-identical with two placed pieces");
        } catch (Exception ex) {
            check(false, "FVDD3 two-piece round-trip (" + ex + ")");
        }
    }

    // (o) piece-level reversal — REWORKED as a CONVERT in v17.0
    // (D134, user re-spec; the D71 model transformation is gone): the
    // compiled geometry re-fits as forward sections running the other
    // way, entry speed = the recorded end speed, no speed conflict.
    // Deep coverage lives in Phase3Gate 48f-48i; this pins the
    // Phase-2 fixture's behavior under the new op.
    static void pieceReversal() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(3, 21, -2, 0);
        p.sections.add(StraightCurveSection.straight(10, 26));
        List<StraightCurveSection.Element> els = new ArrayList<>();
        els.add(StraightCurveSection.Element.curve(20, 90, 0, 0));
        p.sections.add(new StraightCurveSection(els, 26));   // level turn
        c.pieces.add(p);
        TrackCompiler.Result fw = TrackCompiler.compile(c, 0.01);
        Vec3 fwFirst = fw.samples().get(0).pos();
        Vec3 fwLast = lastOf(fw.samples()).pos();
        double fwLen = lastOf(fw.samples()).s();

        check(c.reversePiece(0, 0.01, 0.02, 0, Double.NaN),
            "reverse piece (convert): applies");
        boolean fwd = true;
        for (Section s : p.sections) fwd &= !s.reversed();
        check(p.sections.size() == 2 && fwd
                && p.sections.get(0) instanceof ForceSection
                && p.sections.get(1) instanceof ForceSection,
            "reverse piece: two FORWARD sections at the old boundaries "
            + "(s/c re-authors as force lanes)");
        TrackCompiler.Result rv = TrackCompiler.compile(c, 0.01);
        check(rv.samples().get(0).pos().sub(fwLast).length() < 1e-9,
            "reverse piece: starts exactly at the old exit");
        // the sharp R20 element entry (blendIn 0) rounds into the
        // lane carpet — the honest fit residual on this fixture
        double endDev = lastOf(rv.samples()).pos().sub(fwFirst).length();
        check(endDev < 0.6, String.format(
            "reverse piece: ends on the old entry (dev %.3f m — the "
            + "sharp-element fit residual)", endDev));
        check(Math.abs(lastOf(rv.samples()).s() - fwLen) < 0.5,
            "reverse piece: arc length preserved (fit residual)");
        double worstSplit = 0;
        for (int i = 0; i < rv.samples().size(); i++) {
            worstSplit = Math.max(worstSplit,
                Math.abs(rv.trueV()[i] - rv.samples().get(i).v()));
        }
        check(worstSplit < 5e-3, String.format(
            "reverse piece: NO speed conflict (worst split %.2e m/s)",
            worstSplit));

        // reversing again lands back on the original shape (a fit of
        // a fit — the exact involution went with the old op)
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        check(c.reversePiece(0, 0.01, 0.02, 0, Double.NaN),
            "reverse piece twice: applies");
        List<FVDIntegrator.Sample> back = TrackCompiler.compile(c, 0.01).samples();
        double worst = 0;
        for (FVDIntegrator.Sample sm : back) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < fw.samples().size(); i += 4) {
                best = Math.min(best,
                    sm.pos().sub(fw.samples().get(i).pos()).length());
            }
            worst = Math.max(worst, best);
        }
        check(worst < 1.0, String.format(
            "reverse piece twice: geometry lands back on the original "
            + "(worst %.3f m nearest-point)", worst));
    }

    // (p) bridges (v13.0, D72): G¹ endpoint exactness, frozen-under-
    // move, chain seams, degenerate line, round-trip — TESTING #16
    static void bridges() {
        // arbitrary posed endpoints: both ends must land exactly with
        // matched tangent directions AND matched roll
        TrackPose A = new TrackPose(new Vec3(0, 21, 0), Quat.aboutY(30));
        TrackPose B = new TrackPose(new Vec3(40, 27, -18),
            Quat.aboutY(150).mul(Quat.aboutAxis(new Vec3(0, 0, 1), 0.15)));
        BridgeSection br = BridgeSection.solve(A, B, 22);
        List<FVDIntegrator.Sample> s = br.integrate(0.01).samples();
        FVDIntegrator.Sample first = s.get(0), last = lastOf(s);
        check(first.pos().sub(A.pos()).length() < 1e-12
                && last.pos().sub(B.pos()).length() < 1e-12,
            "bridge: both endpoints land exactly");
        check(angleDeg(first.T(), A.T()) < 1e-6
                && angleDeg(last.T(), B.T()) < 1e-6,
            "bridge: G1 — tangent directions match at both seams");
        check(angleDeg(first.U(), A.U()) < 1e-6
                && angleDeg(last.U(), B.U()) < 1e-4,
            String.format("bridge: roll matched at both ends (end U off %.2e°)",
                angleDeg(last.U(), B.U())));
        boolean mono = true, finite = true;
        for (int i = 1; i < s.size(); i++) {
            mono &= s.get(i).t() > s.get(i - 1).t()
                 && s.get(i).s() >= s.get(i - 1).s();
            finite &= Double.isFinite(s.get(i).pos().x())
                   && Double.isFinite(s.get(i).Gv());
        }
        check(mono && finite, "bridge: t/s monotonic, all finite");

        // collinear matched endpoints degenerate to an EXACT line
        TrackPose LA = new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY);
        TrackPose LB = new TrackPose(new Vec3(30, 21, 0), Quat.IDENTITY);
        List<FVDIntegrator.Sample> ls =
            BridgeSection.solve(LA, LB, 26).integrate(0.01).samples();
        boolean line = true, felt = true;
        for (FVDIntegrator.Sample sm : ls) {
            line &= Math.abs(sm.pos().y() - 21) < 1e-12
                 && Math.abs(sm.pos().z()) < 1e-12;
            felt &= Math.abs(sm.Gv() - 1) < 1e-9 && Math.abs(sm.Gl()) < 1e-9;
        }
        check(line && Math.abs(lastOf(ls).s() - 30) < 1e-9,
            "bridge: collinear endpoints = exact straight line");
        check(felt, "bridge felt G: straight level = 1 g / 0");

        // 2-piece doc: bridge joins the gap; seams exact; LIVE-FOLLOW
        // (v13.1): moving the target re-joins on the next compile;
        // deleting it freezes the bridge at its last capture
        Coaster c = new Coaster();
        TrackPiece p1 = new TrackPiece(3, 21, -2, 0);
        p1.sections.add(StraightCurveSection.straight(10, 26));
        c.pieces.add(p1);
        TrackPiece p2 = new TrackPiece(40, 24, -12, 35);
        p2.sections.add(StraightCurveSection.straight(8, 24));
        c.pieces.add(p2);
        TrackCompiler.Result pre = TrackCompiler.compile(c, 0.01);
        BridgeSection join = BridgeSection.solve(
            pre.spans().get(0).exit(), p2.entryPose(), 26);
        p1.sections.add(join);
        TrackCompiler.Result wb = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span bsp = wb.spans().get(1);   // the bridge
        boolean cont = true;
        for (int i = 1; i < wb.samples().size(); i++) {
            cont &= wb.samples().get(i).pos()
                .sub(wb.samples().get(i - 1).pos()).length() < 1.0;
        }
        check(cont, "bridge chain: whole compile continuous (gap closed)");
        // the bridged piece's duplicate entry sample is DROPPED (D84),
        // so the bridge's exit is compared with the target's entry
        // POSE (the landing) and the next sample is one normal step
        // (check re-baselined after the 2026-08-18 gate recovery)
        check(wb.samples().get(bsp.i1()).pos()
                .sub(p2.entryPose().pos()).length() < 1e-6
              && wb.samples().get(bsp.i1()).pos()
                .sub(wb.samples().get(bsp.i1() + 1).pos()).length() < 0.5,
            "bridge chain: exit sits exactly on the next piece's entry");

        p2.x += 9;
        p2.yawDeg = 80;   // move the target: the bridge FOLLOWS (v13.1)
        TrackCompiler.Result mv = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span msp = mv.spans().get(1);
        check(mv.samples().get(msp.i1()).pos()
                .sub(p2.entryPose().pos()).length() < 1e-12,
            "bridge LIVE-FOLLOW: moving the target re-joins next compile");
        boolean cont2 = true;
        for (int i = 1; i < mv.samples().size(); i++) {
            cont2 &= mv.samples().get(i).pos()
                .sub(mv.samples().get(i - 1).pos()).length() < 1.0;
        }
        check(cont2, "bridge LIVE-FOLLOW: chain stays continuous after the move");

        // orphaned (target gone): frozen at the last capture, bitwise
        List<FVDIntegrator.Sample> before = new ArrayList<>();
        for (int i = msp.i0(); i <= msp.i1(); i++) before.add(mv.samples().get(i));
        c.pieces.remove(1);
        TrackCompiler.Result orp = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span osp = orp.spans().get(1);
        boolean frozen = osp.i1() - osp.i0() == msp.i1() - msp.i0();
        for (int i = 0; frozen && i <= osp.i1() - osp.i0(); i++) {
            frozen = vecBitsEq(before.get(i).pos(),
                orp.samples().get(osp.i0() + i).pos());
        }
        check(frozen, "bridge ORPHAN: target deleted = frozen at last capture");
        c.pieces.add(p2);   // restore for the round-trip below

        // G² seams: the same finite-difference curvature estimator on
        // BOTH sides of each seam must agree (v13.1; the old G¹ bridge
        // read ~0 on its side against 0.05 on the arc side). Tame
        // geometry: target roughly dead ahead so the spline's own
        // curvature varies slowly near the seams.
        Coaster g2 = new Coaster();
        TrackPiece q1 = new TrackPiece(0, 21, 0, 0);
        q1.sections.add(StraightCurveSection.straight(10, 26));
        List<StraightCurveSection.Element> e1 = new ArrayList<>();
        e1.add(StraightCurveSection.Element.curve(20, 30, 0, 0));   // R20
        q1.sections.add(new StraightCurveSection(e1, 26));
        g2.pieces.add(q1);
        TrackPiece q2 = new TrackPiece(48, 21, -22, 35);
        List<StraightCurveSection.Element> e2 = new ArrayList<>();
        e2.add(StraightCurveSection.Element.curve(25, 30, 0, 0));   // R25
        q2.sections.add(new StraightCurveSection(e2, 24));
        g2.pieces.add(q2);
        TrackCompiler.Result gpre = TrackCompiler.compile(g2, 0.01);
        q1.sections.add(BridgeSection.solve(
            gpre.spans().get(1).exit(), q2.entryPose(), 26));
        TrackCompiler.Result gr = TrackCompiler.compile(g2, 0.01);
        TrackCompiler.Span gsp = gr.spans().get(2);   // the bridge
        // one-sided |ΔT|/Δs RIGHT at each seam (the bridge's curvature
        // relaxes quickly toward its straight-ish middle, so a wide
        // window under-reads; i1/i1+1 duplicate the same point — skip)
        double kArcIn = stepCurvature(gr.samples(), gsp.i0() - 1, gsp.i0());
        double kBrIn = stepCurvature(gr.samples(), gsp.i0(), gsp.i0() + 1);
        double kBrOut = stepCurvature(gr.samples(), gsp.i1() - 1, gsp.i1());
        double kArcOut = stepCurvature(gr.samples(), gsp.i1() + 1, gsp.i1() + 2);
        check(Math.abs(kBrIn - kArcIn) < 0.15 * kArcIn, String.format(
            "bridge G²: entry seam curvature continuous (arc %.4f vs bridge %.4f)",
            kArcIn, kBrIn));
        check(Math.abs(kBrOut - kArcOut) < 0.15 * kArcOut, String.format(
            "bridge G²: exit seam curvature continuous (bridge %.4f vs arc %.4f)",
            kBrOut, kArcOut));

        // optimize placement (§4 mode 2, closed form): target aligned
        // dead ahead at its gap distance, headings matched — the
        // live-follow bridge becomes a straight line
        Coaster op = new Coaster();
        TrackPiece r1 = new TrackPiece(3, 21, -2, 0);
        r1.sections.add(StraightCurveSection.straight(10, 26));
        op.pieces.add(r1);
        TrackPiece r2 = new TrackPiece(60, 21, 25, 130);
        r2.sections.add(StraightCurveSection.straight(8, 24));
        op.pieces.add(r2);
        TrackCompiler.Result opre = TrackCompiler.compile(op, 0.01);
        TrackPose r1end = opre.spans().get(0).exit();
        BridgeSection obs = BridgeSection.solve(r1end, r2.entryPose(), 26);
        r1.sections.add(obs);
        // fresh solve = zero seam curvature: the continuation march
        // (v13.4) must reduce to the old dead-ahead exactly here
        op.alignPieceForBridge(obs, 1, 0);
        check(Math.abs(angleDeg(r2.entryPose().T(), r1end.T())) < 1e-9,
            "optimize: target heading aligned dead ahead");
        TrackCompiler.Result or2 = TrackCompiler.compile(op, 0.01);
        TrackCompiler.Span osp2 = or2.spans().get(1);
        Vec3 chordA = or2.samples().get(osp2.i0()).pos();
        Vec3 chordB = or2.samples().get(osp2.i1()).pos();
        Vec3 dir = chordB.sub(chordA).normalize();
        double maxDev = 0;
        for (int i = osp2.i0(); i <= osp2.i1(); i++) {
            Vec3 rel = or2.samples().get(i).pos().sub(chordA);
            maxDev = Math.max(maxDev, rel.sub(dir.scale(rel.dot(dir))).length());
        }
        check(maxDev < 1e-6, String.format(
            "optimize: the bridge straightens (max chord dev %.2e m)", maxDev));

        // any-piece bridging reorders the target to come next
        Coaster mp = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        TrackPiece b = new TrackPiece(50, 21, 0, 0);
        TrackPiece d = new TrackPiece(100, 21, 0, 0);
        a.sections.add(StraightCurveSection.straight(5, 26));
        b.sections.add(StraightCurveSection.straight(5, 26));
        d.sections.add(StraightCurveSection.straight(5, 26));
        mp.pieces.add(a);
        mp.pieces.add(b);
        mp.pieces.add(d);
        mp.movePiece(2, 1);   // bridge a→d: d reorders to follow a
        check(mp.pieces.get(0) == a && mp.pieces.get(1) == d
                && mp.pieces.get(2) == b,
            "movePiece: bridging to an arbitrary piece reorders it next");

        // FVDD3 round-trip with the bridge aboard
        try {
            StringWriter w1 = new StringWriter();
            DesignIO.save(c, DesignIO.Cosmetics.defaults(), new PrintWriter(w1));
            DesignIO.LoadResult lr = DesignIO.load(
                new BufferedReader(new StringReader(w1.toString())));
            StringWriter w2 = new StringWriter();
            DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));
            Section ld = lr.coaster().pieces.get(0).sections.get(1);
            check(w1.toString().equals(w2.toString())
                    && ld instanceof BridgeSection lb
                    && lb.solvedTarget.pos().sub(join.solvedTarget.pos()).length() < 1e-8
                    && Math.abs(lb.lenA - join.lenA) < 1e-3,
                "FVDD3 round-trip byte-identical with a bridge aboard");
        } catch (Exception ex) {
            check(false, "FVDD3 bridge round-trip (" + ex + ")");
        }
    }

    /** Largest rate change between CONSECUTIVE samples (°/s per step)
     *  in rows [from..to] for component comp; duplicate-time pairs
     *  (piece-boundary samples) are skipped. A seam SNAP shows up as
     *  one giant step here; smooth curves change gradually. */
    static double maxConsecDelta(double[][] rates, int from, int to, int comp) {
        double worst = 0;
        for (int i = from; i < to; i++) {
            if (rates[i + 1][0] - rates[i][0] < 1e-9) continue;
            worst = Math.max(worst, Math.abs(rates[i + 1][comp] - rates[i][comp]));
        }
        return worst;
    }

    // (q) v13.2 seam SMOOTHNESS (D74): no rate SNAP at either bridge
    // seam — pitch/yaw/roll rates step gradually across (value+slope
    // continuity by construction), the roll rate joins the neighbors',
    // and the far seam has no speed step. Geometry chosen so a smooth
    // continuation EXISTS (dead-ahead target, dropped 5 m); conflicting
    // end headings legitimately bulge the interior and are the
    // optimize/gap tools' job — curvature flow through CURVED seams is
    // gated tightly in bridges().
    static void bridgeSmooth() {
        Coaster c = new Coaster();
        TrackPiece p1 = new TrackPiece(0, 21, 0, 0);
        List<StraightCurveSection.Element> e1 = new ArrayList<>();
        e1.add(StraightCurveSection.Element.straight(10, 0));
        StraightCurveSection.Element rollStr =
            StraightCurveSection.Element.straight(15, 4);
        rollStr.rollMode = StraightCurveSection.RollMode.RATE;
        rollStr.rollVal = 15;   // rolling INTO the bridge at 15 °/s
        e1.add(rollStr);
        p1.sections.add(new StraightCurveSection(e1, 26));
        c.pieces.add(p1);
        // dead ahead, gently lower over a real gap — a smooth
        // continuation exists (a cramped 5 m dive over 20 m would
        // LEGITIMATELY need violent pitch rates however smooth)
        TrackPiece p2 = new TrackPiece(60, 19.5, 0, 0);
        List<StraightCurveSection.Element> e2 = new ArrayList<>();
        e2.add(StraightCurveSection.Element.curve(25, 30, 0, 0));
        p2.sections.add(new StraightCurveSection(e2, 20));  // slower design
        c.pieces.add(p2);
        TrackCompiler.Result pre = TrackCompiler.compile(c, 0.01);
        p1.sections.add(BridgeSection.solve(
            pre.spans().get(0).exit(), p2.entryPose(), 26));
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span bsp = r.spans().get(1);   // the bridge
        List<FVDIntegrator.Sample> s = r.samples();
        double[][] rates = r.rates();

        // the fixture is NONTRIVIAL: pure energy would land ~7.8 m/s
        // off the target's design speed; the blend erases the step
        BridgeSection bs = (BridgeSection) p1.sections.get(1);
        double vePlain = Math.sqrt(Math.max(0,
            bs.vDesign * bs.vDesign + 2 * FVDIntegrator.G0
                * (bs.solvedEntry.pos().y() - p2.entryPose().pos().y())));
        check(Math.abs(vePlain - 20) > 1,
            "seam smoothness: fixture has a real v mismatch to erase");
        check(Math.abs(s.get(bsp.i1()).v() - s.get(bsp.i1() + 1).v()) < 1e-6,
            String.format("seam smoothness: far seam has NO speed step"
                + " (%.3f vs %.3f)", s.get(bsp.i1()).v(),
                s.get(bsp.i1() + 1).v()));

        // roll rate flows through the entry seam (~15 °/s both sides;
        // the old sine twist pinned the bridge side to 0 = a 15 °/s snap)
        double rollBefore = rates[bsp.i0() - 4][3];
        double rollAfter = rates[bsp.i0() + 4][3];
        check(Math.abs(rollBefore - 15) < 2,
            String.format("seam smoothness: chain rolls in at ~15 °/s (%.2f)",
                rollBefore));
        check(Math.abs(rollAfter - rollBefore) < 3,
            String.format("seam smoothness: roll rate continuous (%.2f vs %.2f)",
                rollBefore, rollAfter));

        // NO SNAP: pitch/yaw/roll rates never jump between consecutive
        // samples across the entry seam; the exit is probed clear of
        // the duplicate boundary sample, whose zero-dt row corrupts
        // bodyRates' central differences (measurement, not geometry)
        for (int comp = 1; comp <= 3; comp++) {
            String name = comp == 1 ? "pitch" : comp == 2 ? "yaw" : "roll";
            double inJump = maxConsecDelta(rates, bsp.i0() - 3, bsp.i0() + 4, comp);
            check(inJump < 3.5, String.format(
                "seam smoothness: %s rate steps gradually into the bridge"
                + " (max %.2f °/s per sample)", name, inJump));
            double across = Math.abs(rates[bsp.i1() + 2][comp]
                - rates[bsp.i1() - 3][comp]);
            check(across < 8, String.format(
                "seam smoothness: %s rate flows out of the bridge"
                + " (%.2f °/s across the seam window)", name, across));
        }

        // bdyn round-trip: the captured dynamics survive save/load
        try {
            StringWriter w1 = new StringWriter();
            DesignIO.save(c, DesignIO.Cosmetics.defaults(), new PrintWriter(w1));
            DesignIO.LoadResult lr = DesignIO.load(
                new BufferedReader(new StringReader(w1.toString())));
            StringWriter w2 = new StringWriter();
            DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));
            BridgeSection lb = (BridgeSection)
                lr.coaster().pieces.get(0).sections.get(1);
            check(w1.toString().equals(w2.toString())
                    && Math.abs(lb.vB - bs.vB) < 1e-6
                    && Math.abs(lb.rollA - bs.rollA) < 1e-6
                    && lb.jerkB.sub(bs.jerkB).length() < 1e-6,
                "FVDD3: bridge dynamics (bdyn) round-trip byte-identical");
        } catch (Exception ex) {
            check(false, "FVDD3 bdyn round-trip (" + ex + ")");
        }
    }

    // (l) FVDD3 round-trip with all three modes aboard
    static void scRoundTrip() throws Exception {
        Coaster c = mixedDoc();   // force + geo + force
        StraightCurveSection sc = scOf(19,
            StraightCurveSection.Element.straight(12.5, 0),
            StraightCurveSection.Element.curve(21.25, -75.5, 90, 3.75),
            StraightCurveSection.Element.straight(6, 2));
        sc.reversed = true;
        sc.elements.get(1).rollMode = StraightCurveSection.RollMode.RATE;
        sc.elements.get(1).rollVal = 15;      // rate rides the elem line
        sc.elements.get(2).rollVal = -12.5;   // held global bank
        c.pieces.get(0).sections.add(sc);
        DesignIO.Cosmetics cos = DesignIO.Cosmetics.defaults();

        StringWriter w1 = new StringWriter();
        DesignIO.save(c, cos, new PrintWriter(w1));
        DesignIO.LoadResult lr = DesignIO.load(new BufferedReader(new StringReader(w1.toString())));
        StringWriter w2 = new StringWriter();
        DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));
        check(w1.toString().equals(w2.toString()),
            "FVDD3 round-trip byte-identical with s/c section");

        Section s3 = lr.coaster().pieces.get(0).sections.get(3);
        check(s3 instanceof StraightCurveSection, "FVDD3: s/c mode survives");
        StraightCurveSection ls = (StraightCurveSection) s3;
        StraightCurveSection.Element e1 = ls.elements.get(1);
        check(ls.reversed && ls.vDesign == 19 && ls.elements.size() == 3
                && ls.elements.get(0).kind == StraightCurveSection.Kind.STRAIGHT
                && Math.abs(ls.elements.get(0).L - 12.5) < 1e-9
                && e1.kind == StraightCurveSection.Kind.PLANAR
                && Math.abs(e1.radius() - 21.25) < 1e-9
                && Math.abs(e1.turnDeg() + 75.5) < 1e-9
                && Math.abs(e1.dirDeg - 90) < 1e-9
                && Math.abs(e1.blendIn - 3.75) < 1e-9,
            "FVDD3: element kinds/params/smoothing survive");
        check(e1.rollMode == StraightCurveSection.RollMode.RATE
                && Math.abs(e1.rollVal - 15) < 1e-9
                && ls.elements.get(2).rollMode == StraightCurveSection.RollMode.GLOBAL
                && Math.abs(ls.elements.get(2).rollVal + 12.5) < 1e-9,
            "FVDD3: roll mode/value survive");
        check(lastOf(TrackCompiler.compile(lr.coaster(), 0.01).samples()).t() > 0,
            "FVDD3: loaded s/c document compiles");
    }

    // (g) SECTIONS-DESIGN §9: reversal — forward/backward centerlines
    // are the same point set traversed the other way
    static void reversal() {
        ForceSection fwd = new ForceSection(defaultLanes(), 26);
        ForceSection rev = new ForceSection(defaultLanes(), 26);
        rev.reversed = true;
        TrackPose id = new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY);
        List<FVDIntegrator.Sample> f = TrackCompiler.passG(fwd, id, 0.01).samples();
        List<FVDIntegrator.Sample> r = TrackCompiler.passG(rev, id, 0.01).samples();
        int n = f.size();
        check(r.size() == n, "reversal: same sample count");

        // rigid motion preserves pairwise distances; reversal maps i -> n-1-i
        boolean dist = true;
        int[][] pairs = { {0, n - 1}, {0, n / 2}, {n / 3, 2 * n / 3}, {n / 4, n - 1} };
        for (int[] p : pairs) {
            double df = f.get(p[0]).pos().sub(f.get(p[1]).pos()).length();
            double dr = r.get(n - 1 - p[0]).pos().sub(r.get(n - 1 - p[1]).pos()).length();
            dist &= Math.abs(df - dr) < 1e-9;
        }
        check(dist, "reversal: point set rigid-motion identical");

        // yaw placement touches x/z only: U.y, v, Gv survive BITWISE;
        // Gl negates; t/s re-base to the traversal direction
        boolean mono = true;
        String bitsFail = null;
        for (int i = 0; i < n && bitsFail == null; i++) {
            FVDIntegrator.Sample a = f.get(n - 1 - i), b = r.get(i);
            if (!bitsEq(a.U().y(), b.U().y()))
                bitsFail = String.format("U.y @%d: %.17g vs %.17g", i, a.U().y(), b.U().y());
            else if (!bitsEq(a.v(), b.v()))
                bitsFail = String.format("v @%d: %.17g vs %.17g", i, a.v(), b.v());
            else if (!bitsEq(a.Gv(), b.Gv()))
                bitsFail = String.format("Gv @%d: %.17g vs %.17g", i, a.Gv(), b.Gv());
            else if (!bitsEq(-a.Gl(), b.Gl()))
                bitsFail = String.format("Gl @%d: %.17g vs %.17g", i, -a.Gl(), b.Gl());
        }
        for (int i = 1; i < n; i++) {
            mono &= r.get(i).t() > r.get(i - 1).t() && r.get(i).s() >= r.get(i - 1).s();
        }
        check(bitsFail == null, "reversal: U.y/v/Gv bitwise, Gl negated"
            + (bitsFail == null ? "" : " (" + bitsFail + ")"));
        check(mono, "reversal: t/s ascend in traversal order");
        check(Math.abs(r.get(0).t()) < 1e-12
                && r.get(0).pos().sub(id.pos()).length() < 1e-12,
            "reversal: entry lands exactly on the entry pose");
        double hr = Math.atan2(-r.get(0).T().z(), r.get(0).T().x());
        double he = Math.atan2(-id.T().z(), id.T().x());
        check(Math.abs(hr - he) < 1e-9,
            String.format("reversal: entry HORIZONTAL heading matches the entry pose"
                + " (off %.2e rad)", Math.abs(hr - he)));

        // chained: force + REVERSED force compiles continuously
        Coaster c = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        ForceSection tail = new ForceSection(defaultLanes(), 26);
        tail.reversed = true;
        c.pieces.get(0).sections.add(tail);
        List<FVDIntegrator.Sample> all = TrackCompiler.compile(c, 0.01).samples();
        boolean cont = true;
        for (int i = 1; i < all.size(); i++) {
            cont &= all.get(i).t() > all.get(i - 1).t()
                 && all.get(i).pos().sub(all.get(i - 1).pos()).length() < 1.0;
        }
        check(cont, "reversal: chained compile continuous, t monotonic");

        // reversed geo: roll rate is traversal-INVARIANT — T flips AND
        // the bank's change-per-second flips, the two cancel (this
        // CORRECTS SECTIONS-DESIGN §3's "roll sign flips" note; felt Gl
        // flips because only R flips, the force vector doesn't)
        GeoSection groll = new GeoSection(new ArrayList<>(), 26);
        groll.lanes.addAll(java.util.List.of(
            nodes(new double[][]{ {0, 0}, {2, 0} }),
            nodes(new double[][]{ {0, 0}, {2, 0} }),
            nodes(new double[][]{ {0, 50}, {2, 50} })));
        groll.reversed = true;
        Coaster gc = new Coaster();
        TrackPiece gp = new TrackPiece(0, 21, 0, 0);
        gp.sections.add(groll);
        gc.pieces.add(gp);
        TrackCompiler.Result gr = TrackCompiler.compile(gc, 0.01);
        double midRoll = gr.rates()[gr.rates().length / 2][3];
        check(Math.abs(midRoll - 50) < 0.1,
            String.format("reversal: roll rate traversal-invariant (+50 stays, got %.3f)", midRoll));
    }

    // (a) Phase-1 gate: compile == old direct call, on raw double bits
    static void bitForBitBaseline() {
        List<List<BezierBaker.Node>> lanes = defaultLanes();
        Coaster c = Coaster.singleForce(lanes, 26, 0, 21, 0);
        TrackCompiler.Result res = TrackCompiler.compile(c, 0.01);

        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        // D199 (v18.27) re-baseline: compile generates CANONICALLY —
        // position zero, heading stripped — and places rigidly, so
        // the pass-through contract is now direct-at-ORIGIN + the
        // translation (the default layout's identity heading makes
        // the placement rotation bitwise-transparent)
        opts.pos0 = Vec3.ZERO;
        List<FVDIntegrator.Sample> direct =
            FVDIntegrator.integrateTrack(BezierBaker.bake(lanes, 0.01), opts);
        // (0.01 = the compiler's TIME grid since v17.11 D151 — the
        // direct call must bake identically for the bit compare)

        Vec3 off = new Vec3(0, 21, 0);
        boolean same = res.samples().size() == direct.size();
        for (int i = 0; same && i < direct.size(); i++) {
            FVDIntegrator.Sample a = res.samples().get(i), b = direct.get(i);
            same = bitsEq(a.t(), b.t()) && bitsEq(a.s(), b.s())
                && vecBitsEq(a.pos(), off.add(b.pos())) && vecBitsEq(a.T(), b.T())
                && vecBitsEq(a.U(), b.U()) && vecBitsEq(a.R(), b.R())
                && bitsEq(a.v(), b.v()) && bitsEq(a.Gv(), b.Gv()) && bitsEq(a.Gl(), b.Gl());
        }
        check(same, "bit-for-bit: compile == placed direct integrator (D199 canonical)");

        FVDIntegrator.Sample last = direct.get(direct.size() - 1);
        double endPitch = Math.toDegrees(Math.atan2(last.T().y(),
            Math.hypot(last.T().x(), last.T().z())));
        check(direct.size() == 1347, "baseline: 1347 samples (got " + direct.size() + ")");
        // re-baselined v17.11 (D151 — exact bake values + 0.01 time
        // grid; the 0.05 polyline bake read 406.878 / 0.2284)
        check(Math.abs(last.s() - 406.931) < 0.005,
            String.format("baseline: length 406.931 (got %.3f)", last.s()));
        check(Math.abs(endPitch - 0.2131) < 0.001,
            String.format("baseline: end pitch 0.2131 (got %.4f)", endPitch));
        check(last.U().y() > 0.99997, "baseline: end U.y (got " + last.U().y() + ")");

        boolean trueVeq = true;
        for (int i = 0; i < direct.size(); i++) {
            trueVeq &= bitsEq(res.trueV()[i], direct.get(i).v());
        }
        check(trueVeq, "pass D pass-through: trueV == Sample.v bitwise");
    }

    // (b) SECTIONS-DESIGN §9: constant rates give exact angle changes.
    // Expected angle = rate x the LAST SAMPLE'S t: the loop records its
    // final sample at the largest accumulated t <= duration (same
    // cadence as the force integrator), so nominal-duration asserts
    // would be one dt short whenever float accumulation overshoots.
    static void geoAnalytic() {
        // constant pitch +30 deg/s over 3 s (vDesign 40: no stall on the climb)
        GeoIntegrator.Result r = GeoIntegrator.integrate(
            constRate(30, 0, 0, 3), 0.01, 40, new Vec3(0, 21, 0), Quat.IDENTITY);
        FVDIntegrator.Sample last = r.samples().get(r.samples().size() - 1);
        check(Math.abs(angleDeg(new Vec3(1, 0, 0), last.T()) - 30 * last.t()) < 1e-6,
            String.format("geo: 30 deg/s pitch = exact angle (got %.9f over %.4f s)",
                angleDeg(new Vec3(1, 0, 0), last.T()), last.t()));
        boolean planar = true;
        for (FVDIntegrator.Sample sm : r.samples()) planar &= Math.abs(sm.pos().z()) < 1e-9;
        check(planar, "geo: pure pitch stays in the x-y plane");

        // constant yaw 45 deg/s over 2 s, level: exact heading change, height const
        r = GeoIntegrator.integrate(
            constRate(0, 45, 0, 2), 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        last = r.samples().get(r.samples().size() - 1);
        check(Math.abs(angleDeg(new Vec3(1, 0, 0), last.T()) - 45 * last.t()) < 1e-6,
            String.format("geo: 45 deg/s yaw = exact angle (got %.9f over %.4f s)",
                angleDeg(new Vec3(1, 0, 0), last.T()), last.t()));
        check(Math.abs(last.pos().y() - 21) < 1e-9 && Math.abs(last.U().y() - 1) < 1e-9,
            "geo: level yaw keeps height and up");
        double geoTurnY = new Vec3(1, 0, 0).cross(last.T()).y();

        // chirality: +yaw must turn the SAME WAY as the force integrator's +Gl
        FVDIntegrator.Options fo = new FVDIntegrator.Options();
        fo.dt = 0.01;
        fo.v0 = 20;
        fo.pos0 = new Vec3(0, 21, 0);
        List<FVDIntegrator.Keyframe> fkfs = new ArrayList<>();
        fkfs.add(new FVDIntegrator.Keyframe(0, 1, 1.5, 0));
        fkfs.add(new FVDIntegrator.Keyframe(2, 1, 1.5, 0));
        List<FVDIntegrator.Sample> fs = FVDIntegrator.integrateTrack(fkfs, fo);
        double forceTurnY = new Vec3(1, 0, 0)
            .cross(fs.get(fs.size() - 1).T()).y();
        check(Math.signum(geoTurnY) == Math.signum(forceTurnY),
            "geo: +yaw turns the same way as +Gl again (D185: both LEFT)");

        // constant roll 50 deg/s over 2 s: T fixed, straight line, exact U angle
        r = GeoIntegrator.integrate(
            constRate(0, 0, 50, 2), 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        last = r.samples().get(r.samples().size() - 1);
        check(Math.abs(angleDeg(new Vec3(0, 1, 0), last.U()) - 50 * last.t()) < 1e-6,
            String.format("geo: 50 deg/s roll = exact angle (got %.9f over %.4f s)",
                angleDeg(new Vec3(0, 1, 0), last.U()), last.t()));
        check(angleDeg(new Vec3(1, 0, 0), last.T()) < 1e-6
                && Math.abs(last.pos().z()) < 1e-9,
            "geo: pure roll leaves the path straight");

        // rate integration is speed-independent: -20 deg/s dive, exact angle
        r = GeoIntegrator.integrate(
            constRate(-20, 0, 0, 4), 0.01, 10, new Vec3(0, 60, 0), Quat.IDENTITY);
        last = r.samples().get(r.samples().size() - 1);
        check(Math.abs(angleDeg(new Vec3(1, 0, 0), last.T()) - 20 * last.t()) < 1e-6,
            String.format("geo: pitch angle independent of speed profile (got %.9f)",
                angleDeg(new Vec3(1, 0, 0), last.T())));
    }

    // (c) canary: author a geo section FROM the default layout's resulting
    // body rates -> geometry must land where the force section does
    static void geoForceEquivalence() {
        List<List<BezierBaker.Node>> lanes = defaultLanes();
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        opts.pos0 = new Vec3(0, 21, 0);
        List<FVDIntegrator.Sample> fs =
            FVDIntegrator.integrateTrack(BezierBaker.bake(lanes, 0.05), opts);
        double[][] rates = FVDIntegrator.bodyRates(fs);
        List<FVDIntegrator.Keyframe> kfs = new ArrayList<>();
        for (double[] row : rates) {
            kfs.add(new FVDIntegrator.Keyframe(row[0], row[1], row[2], row[3]));
        }
        GeoIntegrator.Result r = GeoIntegrator.integrate(
            kfs, 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        FVDIntegrator.Sample fe = fs.get(fs.size() - 1);
        FVDIntegrator.Sample ge = r.samples().get(r.samples().size() - 1);
        double posErr = fe.pos().sub(ge.pos()).length();
        double frameErr = angleDeg(fe.T(), ge.T());
        check(posErr < 1.0, String.format(
            "geo==force via resulting rates: end pos err %.4f m over %.0f m", posErr, fe.s()));
        check(frameErr < 0.5, String.format(
            "geo==force via resulting rates: end T err %.4f deg", frameErr));
    }

    // (d) mixed force->geo->force chain: continuity + the section-0
    // invariants (editing/geo/v0 must never move compiled upstream bits)
    static Coaster mixedDoc() {
        Coaster c = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        List<List<BezierBaker.Node>> geoLanes = new ArrayList<>();
        geoLanes.add(nodes(new double[][]{ {0, 0}, {2, 40} }));   // pitch ramp
        geoLanes.add(nodes(new double[][]{ {0, 0}, {2, 10} }));   // slight yaw
        geoLanes.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        c.pieces.get(0).sections.add(new GeoSection(geoLanes, 30));
        c.pieces.get(0).sections.add(Coaster.holdSection(1, 0, 0, 20));
        return c;
    }

    static void mixedChainInvariance() {
        Coaster solo = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        List<FVDIntegrator.Sample> soloS = TrackCompiler.compile(solo, 0.01).samples();

        Coaster c = mixedDoc();
        TrackCompiler.Result res = TrackCompiler.compile(c, 0.01);
        List<FVDIntegrator.Sample> all = res.samples();

        boolean mono = true, cont = true, finite = true;
        for (int i = 1; i < all.size(); i++) {
            FVDIntegrator.Sample a = all.get(i - 1), b = all.get(i);
            mono &= b.t() > a.t();
            cont &= b.pos().sub(a.pos()).length() < 1.0;   // ~ v*dt, generous
            finite &= Double.isFinite(b.pos().x()) && Double.isFinite(b.pos().y())
                   && Double.isFinite(b.pos().z());
        }
        check(mono && finite, "chain: t strictly increasing, all finite");
        check(cont, "chain: no position jump at section boundaries");

        boolean prefix = true;
        for (int i = 0; i < soloS.size(); i++) {
            prefix &= vecBitsEq(soloS.get(i).pos(), all.get(i).pos())
                   && vecBitsEq(soloS.get(i).T(), all.get(i).T())
                   && vecBitsEq(soloS.get(i).U(), all.get(i).U());
        }
        check(prefix, "chain: force prefix bitwise == solo compile");

        // edit the GEO section -> force prefix must not move
        Coaster c2 = mixedDoc();
        ((LaneSection) c2.pieces.get(0).sections.get(1))
            .lanes.get(0).get(1).val = -25;   // pitch ramp flipped
        List<FVDIntegrator.Sample> all2 = TrackCompiler.compile(c2, 0.01).samples();
        boolean prefix2 = true;
        for (int i = 0; i < soloS.size(); i++) {
            prefix2 &= vecBitsEq(soloS.get(i).pos(), all2.get(i).pos());
        }
        boolean moved = !vecBitsEq(all.get(all.size() - 1).pos(),
                                   all2.get(all2.size() - 1).pos());
        check(prefix2 && moved, "sec0 invariance: geo edit moves geo, not upstream");

        // change dynamics v0 -> NO geometry bit moves anywhere
        Coaster c3 = mixedDoc();
        c3.v0 = 5;
        List<FVDIntegrator.Sample> all3 = TrackCompiler.compile(c3, 0.01).samples();
        boolean geom = all3.size() == all.size();
        for (int i = 0; geom && i < all.size(); i++) {
            geom = vecBitsEq(all.get(i).pos(), all3.get(i).pos())
                && vecBitsEq(all.get(i).T(), all3.get(i).T())
                && vecBitsEq(all.get(i).U(), all3.get(i).U());
        }
        check(geom, "sec0 invariance: v0 change leaves all geometry bitwise");
    }

    // (e) FVDD3 canonical round-trip with a geo section aboard
    static void roundTrip() throws Exception {
        Coaster c = mixedDoc();
        c.pieces.get(0).yawDeg = 30;
        LaneSection geo = (LaneSection) c.pieces.get(0).sections.get(1);
        geo.lanes.get(0).get(0).smooth = false;          // broken node
        geo.lanes.get(1).get(0).straightOut = true;      // straight-lock
        geo.lanes.get(1).get(0).levelOut = false;
        geo.lanes.get(2).get(1).inFrac = 0.42;           // non-default handle
        geo.lanes.get(2).get(1).inDv = -3.5;
        geo.reversed = true;                             // reversal flag rides too
        // SPEED CONTROLS ride the round-trip (v18.0, D160 — replaced
        // the retired SpeedRegion stub): one complex + one plain on
        // the force section, plus a bare mark
        LaneSection f0 = (LaneSection) c.pieces.get(0).sections.get(0);
        SpeedControl sc1 = new SpeedControl(0.5, 1.25);
        sc1.type = SpeedControl.SPEED;
        sc1.speed = 12.5;
        sc1.maxLimit = true;
        sc1.minLimit = false;
        f0.speedCtls.add(sc1);
        SpeedControl sc2 = new SpeedControl(2, 0.75);
        sc2.type = SpeedControl.ACCEL;
        sc2.speed = 33;
        sc2.accel = 0.85;
        sc2.complex = true;
        sc2.freshLane(0.85);
        sc2.lane.get(1).val = 0.25;
        sc2.lane.get(0).outDv = 0.1;
        f0.speedCtls.add(sc2);
        f0.speedMarks.add(3.25);
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(0.5, 1.2, 0.8, true,
            new double[]{ 0.1, 0.2, 0.3 },
            DesignIO.Cosmetics.defaults().gradColors(),
            new double[]{ -1.5, -1, -0.5, 1, 2.5, 4 },   // SIX stops (v16.15)
            DesignIO.Cosmetics.defaults().latColors(),   // v16.13 lateral
            new double[]{ 0.5, 1, 2 },                   // THREE |Gl| stops (v16.14)
            null);                                       // camera (D210)

        StringWriter w1 = new StringWriter();
        DesignIO.save(c, cos, new PrintWriter(w1));
        DesignIO.LoadResult lr = DesignIO.load(new BufferedReader(new StringReader(w1.toString())));
        StringWriter w2 = new StringWriter();
        DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));

        check(w1.toString().equals(w2.toString()),
            "FVDD3 round-trip byte-identical with geo section");
        check(lr.coaster().pieces.get(0).sections.get(0) instanceof ForceSection
           && lr.coaster().pieces.get(0).sections.get(1) instanceof GeoSection
           && lr.coaster().pieces.get(0).sections.get(2) instanceof ForceSection,
            "FVDD3: section modes survive the round-trip");
        LaneSection lgeo = (LaneSection) lr.coaster().pieces.get(0).sections.get(1);
        check(lgeo.vDesign == 30 && !lgeo.lanes.get(0).get(0).smooth
           && lgeo.lanes.get(1).get(0).straightOut,
            "FVDD3: geo vDesign/flags survive");
        LaneSection lf0 = (LaneSection) lr.coaster().pieces.get(0).sections.get(0);
        check(lf0.speedCtls.size() == 2
           && lf0.speedCtls.get(0).type == SpeedControl.SPEED
           && Math.abs(lf0.speedCtls.get(0).speed - 12.5) < 1e-9
           && lf0.speedCtls.get(0).maxLimit && !lf0.speedCtls.get(0).minLimit
           && lf0.speedCtls.get(1).complex
           && lf0.speedCtls.get(1).lane.size() == 2
           && Math.abs(lf0.speedCtls.get(1).lane.get(1).val - 0.25) < 1e-9
           && lf0.speedMarks.size() == 1
           && Math.abs(lf0.speedMarks.get(0) - 3.25) < 1e-9,
            "FVDD3: speed controls + marks survive the round-trip (v18.0)");
    }

    // (f) felt forces recorded by the geo integrator + continuation helpers
    static void feltForces() {
        // zero rates, level entry: the rider feels exactly 1 g / 0
        GeoIntegrator.Result r = GeoIntegrator.integrate(
            constRate(0, 0, 0, 1), 0.01, 26, new Vec3(0, 21, 0), Quat.IDENTITY);
        boolean ok = true;
        for (FVDIntegrator.Sample sm : r.samples()) {
            ok &= Math.abs(sm.Gv() - 1) < 1e-9 && Math.abs(sm.Gl()) < 1e-9;
        }
        check(ok, "geo felt G: straight level = 1 g / 0");

        // constant pitch p at speed v, level start: Gv(0) = p*v/g + 1
        r = GeoIntegrator.integrate(
            constRate(30, 0, 0, 1), 0.01, 40, new Vec3(0, 21, 0), Quat.IDENTITY);
        double expect = Math.toRadians(30) * 40 / FVDIntegrator.G0 + 1;
        check(Math.abs(r.samples().get(0).Gv() - expect) < 1e-9,
            String.format("geo felt G: pull-up entry Gv = %.4f", expect));

        ForceSection hold = Coaster.holdSection(1.8, -0.3, 12, 22);
        check(hold.lanes.get(0).get(0).val == 1.8 && hold.lanes.get(0).get(1).val == 1.8
           && hold.lanes.get(1).get(1).val == -0.3 && hold.lanes.get(2).get(1).val == 12
           && hold.vDesign == 22,
            "holdSection: values + vDesign held");
        GeoSection st = GeoSection.straight(19);
        check(st.lanes.get(0).get(1).val == 0 && st.lanes.get(2).get(1).val == 0
           && st.vDesign == 19, "GeoSection.straight: zero rates");

        // v10.1: continuation is same-mode generic — a geo prev yields a
        // geo hold of its final rates
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, 0}, {2, 35} }));
        gl.add(nodes(new double[][]{ {0, 0}, {2, -12} }));
        gl.add(nodes(new double[][]{ {0, 0}, {2, 50} }));
        LaneSection cont = Coaster.continuation(new GeoSection(gl, 24), 21);
        check(cont instanceof GeoSection
           && cont.lanes.get(0).get(0).val == 35 && cont.lanes.get(0).get(1).val == 35
           && cont.lanes.get(1).get(1).val == -12 && cont.lanes.get(2).get(1).val == 50
           && cont.vDesign == 21,
            "continuation(geo): geo hold of the end rates");
        LaneSection fcont = Coaster.continuation(
            (LaneSection) mixedDoc().pieces.get(0).sections.get(0), 26);
        check(fcont instanceof ForceSection && fcont.lanes.get(0).get(1).val == 1
           && fcont.lanes.get(2).get(1).val == 0,
            "continuation(force): force hold of the end values");
    }

    // ---------- INFLUENCE (v15.0 D98, SECTIONS-DESIGN §5.4, TESTING #37)
    // cross-mode delta lanes over a self-computed base: resulting ==
    // base + delta by construction; zero deltas at unchanged speed
    // take the plain path bitwise.

    /** {max nearest-point deviation of b's samples from a's polyline,
     *  end-position distance} — re-created after the 2026-08-18 gate
     *  recovery (the original helper's edit was not replayable). */
    static double[] shapeDev(List<FVDIntegrator.Sample> a,
                             List<FVDIntegrator.Sample> b) {
        double worst = 0;
        int lo = 0;
        for (int i = 0; i < b.size(); i += 4) {
            Vec3 q = b.get(i).pos();
            double best = Double.MAX_VALUE;
            int bestK = lo;
            for (int k = Math.max(0, lo - 60); k + 1 < a.size()
                    && k < lo + 400; k++) {
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
        double end = a.get(a.size() - 1).pos()
            .sub(b.get(b.size() - 1).pos()).length();
        return new double[]{ worst, end };
    }

    static boolean samplesBitwise(List<FVDIntegrator.Sample> a,
                                  List<FVDIntegrator.Sample> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            FVDIntegrator.Sample x = a.get(i), y = b.get(i);
            if (!vecBitsEq(x.pos(), y.pos()) || !vecBitsEq(x.U(), y.U())
                    || !vecBitsEq(x.T(), y.T()) || !bitsEq(x.v(), y.v())
                    || !bitsEq(x.Gv(), y.Gv()) || !bitsEq(x.Gl(), y.Gl())) {
                return false;
            }
        }
        return true;
    }

    static void influence() throws Exception {
        // (a) DO-NO-HARM: zero-delta entry at unchanged speed is
        // BITWISE (the fast path takes the plain branch), and
        // discard restores the un-influenced document exactly.
        Coaster c = new Coaster();
        TrackPiece tp = new TrackPiece(0, 21, 0, 0);
        ForceSection f = new ForceSection(defaultLanes(), 26);
        tp.sections.add(f);
        c.pieces.add(tp);
        List<FVDIntegrator.Sample> before =
            TrackCompiler.compile(c, 0.01).samples();
        Coaster.enterInfluence(f, 13.46);   // dur = the section span
        check(f.influenced() && f.vAuthored == 26 && f.vDesign == 26,
            "influence (a): entry NEVER touches the speeds (v15.4) — "
            + "authored pair mirrors, operating speed bitwise");
        List<FVDIntegrator.Sample> after =
            TrackCompiler.compile(c, 0.01).samples();
        check(samplesBitwise(before, after),
            "influence (a): zero-delta entry is BITWISE");
        check(f.baseCurves != null && f.baseCurves.length == before.size(),
            "influence (a): fast path still exports the base curves");
        Coaster.discardInfluence(f);
        check(!f.influenced() && f.vDesign == 26 && f.deltaLanes == null,
            "influence (a): discard drops the delta state");
        check(samplesBitwise(before, TrackCompiler.compile(c, 0.01).samples()),
            "influence (a): discard restores BITWISE");

        // (b) vAUTHORED IS INERT (v19.7, D236 — the one-pass hybrid
        // retired the two-pass ref frame): a hand-edited DIVERGENT
        // pair (field write; the setter mirrors since v15.4) still
        // loads, but the compile is driven by the OPERATING speed
        // alone — zero deltas at vDesign 29 compile BITWISE as the
        // plain section at 29. (The two-pass held the 26-shape and
        // re-clocked; that ref-frame concept is gone.)
        Coaster.enterInfluence(f, 13.46);
        f.vDesign = 29;   // FIELD write: setter-only mirror
        check(f.vAuthored == 26 && Math.abs(f.vDesign - 29) < 1e-12,
            "influence (b): a field write leaves the pair divergent");
        List<FVDIntegrator.Sample> at29 =
            TrackCompiler.compile(c, 0.01).samples();
        Coaster c29 = new Coaster();
        TrackPiece tp29 = new TrackPiece(0, 21, 0, 0);
        tp29.sections.add(new ForceSection(defaultLanes(), 29));
        c29.pieces.add(tp29);
        check(samplesBitwise(TrackCompiler.compile(c29, 0.01).samples(), at29),
            "influence (b): vAuthored INERT — divergent zero-delta pair "
            + "compiles BITWISE as the plain section at vDesign (D236)");
        Coaster.discardInfluence(f);

        // (c) ADDITIVITY, force section: a yaw-delta hump lands on the
        // resulting yaw rate EXACTLY (base + delta vs Pass A).
        Coaster.enterInfluence(f, 13.46);
        f.deltaLanes.set(1, nodes(new double[][]{
            {0, 0}, {4, 0}, {6, 15}, {8, 0}, {13.46, 0} }));
        TrackCompiler.Result rc = TrackCompiler.compile(c, 0.01);
        double worstAdd = 0, peakYaw = 0;
        double durC = lastOf(rc.samples()).t();
        for (int i = 0; i < rc.rates().length; i++) {
            double t = rc.rates()[i][0];
            if (t < 0.5 || t > durC - 0.5) continue;
            double want = LaneSection.baseAt(f.baseCurves, 1, t)
                + BezierBaker.evalAt(f.deltaLanes.get(1), t);
            worstAdd = Math.max(worstAdd, Math.abs(rc.rates()[i][2] - want));
            peakYaw = Math.max(peakYaw, Math.abs(rc.rates()[i][2]));
        }
        check(worstAdd < 0.15 && peakYaw > 10, String.format(
            "influence (c): resulting yaw == base + delta (worst %.3f °/s, "
            + "peak %.1f °/s)", worstAdd, peakYaw));
        double[] devC = shapeDev(before, rc.samples());
        check(devC[0] > 1.0,
            String.format("influence (c): the delta genuinely bends the "
            + "track (dev %.1f m)", devC[0]));
        Coaster.discardInfluence(f);
        check(samplesBitwise(before, TrackCompiler.compile(c, 0.01).samples()),
            "influence (c): discard after sculpting restores BITWISE");

        // (d) ADDITIVITY, geo section: a ΔGv hump lands on the FELT
        // vertical G exactly (the force pass chases base + delta).
        Coaster cg = new Coaster();
        TrackPiece tg = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(nodes(new double[][]{ {0, 8}, {6, 8} }));    // pitch 8 °/s
        gl.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
        gl.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
        GeoSection g = new GeoSection(gl, 26);
        tg.sections.add(g);
        cg.pieces.add(tg);
        List<FVDIntegrator.Sample> gBefore =
            TrackCompiler.compile(cg, 0.01).samples();
        Coaster.enterInfluence(g, 6);
        g.deltaLanes.set(0, nodes(new double[][]{
            {0, 0}, {1.5, 0.4}, {4.5, 0.4}, {6, 0} }));
        TrackCompiler.Result rg = TrackCompiler.compile(cg, 0.01);
        double worstFelt = 0, feltShift = 0;
        double durG = lastOf(rg.samples()).t();
        for (int i = 0; i < rg.samples().size(); i++) {
            FVDIntegrator.Sample sm = rg.samples().get(i);
            if (sm.t() < 0.3 || sm.t() > durG - 0.3) continue;
            double want = LaneSection.baseAt(g.baseCurves, 0, sm.t())
                + BezierBaker.evalAt(g.deltaLanes.get(0), sm.t());
            worstFelt = Math.max(worstFelt, Math.abs(sm.Gv() - want));
            feltShift = Math.max(feltShift, Math.abs(sm.Gv()
                - gBefore.get(Math.min(i, gBefore.size() - 1)).Gv()));
        }
        check(worstFelt < 5e-3 && feltShift > 0.3, String.format(
            "influence (d): geo felt Gv == base + ΔGv (worst %.4f g, "
            + "shift %.2f g)", worstFelt, feltShift));

        // (e) THE USER'S EXAMPLE: a no-laterals section + yaw influence
        // — the laterals appear in the felt/true curves while the
        // authored Gl lane stays zero.
        Coaster ce = new Coaster();
        TrackPiece te = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> el = new ArrayList<>();
        el.add(nodes(new double[][]{ {0, 1}, {6, 1} }));   // Gv 1 = straight
        el.add(nodes(new double[][]{ {0, 0}, {6, 0} }));   // NO laterals
        el.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
        ForceSection fe = new ForceSection(el, 26);
        te.sections.add(fe);
        ce.pieces.add(te);
        TrackCompiler.compile(ce, 0.01);
        Coaster.enterInfluence(fe, 6);
        List<BezierBaker.Node> flat10 = new ArrayList<>();
        flat10.add(new BezierBaker.Node(0, 10));   // hold +10 °/s yaw
        fe.deltaLanes.set(1, flat10);
        TrackCompiler.Result re = TrackCompiler.compile(ce, 0.01);
        // D185: +10 deg/s yaw = a LEFT turn = body-RIGHT felt = +Gl
        double expGl = Math.toRadians(10) * 26 / FVDIntegrator.G0;
        int mid = re.samples().size() / 2;
        double gotGl = re.samples().get(mid).Gl();
        double gotTrue = re.trueFelt()[mid][1];
        check(Math.abs(gotGl - expGl) < 0.02
                && Math.abs(gotTrue - expGl) < 0.02, String.format(
            "influence (e): yaw influence on a no-lateral section — felt "
            + "Gl %.3f g (expected %.3f), true %.3f; authored lane "
            + "still 0", gotGl, expGl, gotTrue));
        check(fe.lanes.get(1).get(0).val == 0
                && fe.lanes.get(1).get(1).val == 0,
            "influence (e): the design Gl lane is untouched");

        // (f) §0 / TRANSLATION INVARIANCE: moving the piece leaves the
        // influenced section's local shape put (float noise only).
        List<FVDIntegrator.Sample> loc0 = re.samples();
        te.y = 51;
        List<FVDIntegrator.Sample> loc1 =
            TrackCompiler.compile(ce, 0.01).samples();
        double worstLoc = 0;
        Vec3 o0 = loc0.get(0).pos(), o1 = loc1.get(0).pos();
        for (int i = 0; i < Math.min(loc0.size(), loc1.size()); i++) {
            worstLoc = Math.max(worstLoc, loc0.get(i).pos().sub(o0)
                .sub(loc1.get(i).pos().sub(o1)).length());
        }
        check(loc0.size() == loc1.size() && worstLoc < 1e-6, String.format(
            "influence (f): upstream translation leaves the local shape "
            + "(worst %.2e m)", worstLoc));
        te.y = 21;

        // (g) INFLUENCED == NORMAL for speeds (v15.4, D102 — user
        // re-spec): a same-piece influenced continuation RECONCILES
        // like any section, the pair moving together...
        Coaster cr = new Coaster();
        TrackPiece tr = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> rl = new ArrayList<>();
        rl.add(nodes(new double[][]{ {0, 2.0}, {2, 2.0} }));
        rl.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        rl.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        ForceSection rA = new ForceSection(rl, 26);
        tr.sections.add(rA);
        cr.pieces.add(tr);
        double vE = lastOf(TrackCompiler.compile(cr, 0.01).samples()).v();
        ForceSection rB = Coaster.holdSection(1.2, 0, 0, vE);
        tr.sections.add(rB);
        Coaster.enterInfluence(rB, 1.2);
        rA.vDesign = 32;   // upstream drift
        TrackCompiler.Result rr2 = TrackCompiler.compile(cr, 0.01);
        boolean touched = cr.reconcileChainedSpeeds(rr2);
        check(touched && Math.abs(rB.vDesign - vE) > 0.5
                && rB.vAuthored == rB.vDesign, String.format(
            "influence (g): influenced continuation reconciles like a "
            + "normal section (%.2f -> %.2f m/s, pair locked)",
            vE, rB.vDesign));

        // (g2) THE USER'S BUG: entering influence on a CONFLICTED
        // (pinned) section must not move ITS speed nor, through its
        // design end, the NEXT section's conflict.
        Coaster cb = new Coaster();
        TrackPiece tb = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> bl = new ArrayList<>();
        bl.add(nodes(new double[][]{ {0, 2.2}, {2, 2.2} }));
        bl.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        bl.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        tb.sections.add(new ForceSection(bl, 26));
        ForceSection bA = Coaster.holdSection(1.5, 0, 0, 15);
        bA.vPinned = true;   // the conflicted section
        tb.sections.add(bA);
        cb.pieces.add(tb);
        TrackCompiler.Result rb0 = rebuildLoop(cb);
        ForceSection bB = Coaster.holdSection(1.2, 0, 0,
            rb0.samples().get(rb0.spans().get(1).i1()).v());
        tb.sections.add(bB);
        rb0 = rebuildLoop(cb);
        double bDriftBefore = Math.abs(rb0.trueV()[rb0.spans().get(2).i0()]
            - rb0.samples().get(rb0.spans().get(2).i0()).v());
        double bVBefore = bB.vDesign;
        Coaster.enterInfluence(bA, 1.5);
        TrackCompiler.Result rb1 = rebuildLoop(cb);
        double bDriftAfter = Math.abs(rb1.trueV()[rb1.spans().get(2).i0()]
            - rb1.samples().get(rb1.spans().get(2).i0()).v());
        check(bA.vDesign == 15 && bA.vPinned
                && Math.abs(bB.vDesign - bVBefore) < 1e-9
                && Math.abs(bDriftAfter - bDriftBefore) < 0.02,
            String.format("influence (g2): influencing a conflicted "
            + "section leaves ITS speed (15.0 bitwise) and the NEXT "
            + "section's conflict (drift %.2f -> %.2f m/s, vDesign "
            + "%.2f kept) — the user's bug", bDriftBefore, bDriftAfter,
            bB.vDesign));

        // (h) IO ROUND-TRIP: canonical save→load→save byte-identical
        // with influence lines; the state survives; an uninfluenced
        // save carries no influence lines at all.
        StringWriter w1 = new StringWriter();
        DesignIO.save(ce, DesignIO.Cosmetics.defaults(),
            new PrintWriter(w1));
        String s1 = w1.toString();
        check(s1.contains("infl ") && s1.contains("dlane 1")
                && s1.contains("dn "),
            "influence (h): influenced save carries infl/dlane/dn lines");
        DesignIO.LoadResult lr = DesignIO.load(
            new BufferedReader(new StringReader(s1)));
        LaneSection lfe = (LaneSection) lr.coaster().pieces.get(0)
            .sections.get(0);
        check(lfe.influenced() && Math.abs(lfe.vAuthored - 26) < 1e-9
                && lfe.deltaLanes.get(1).size() == 1
                && Math.abs(lfe.deltaLanes.get(1).get(0).val - 10) < 1e-9,
            "influence (h): loaded state — vAuthored on the section "
            + "line, operating speed + deltas from the infl block");
        StringWriter w2 = new StringWriter();
        DesignIO.save(lr.coaster(), DesignIO.Cosmetics.defaults(),
            new PrintWriter(w2));
        check(s1.equals(w2.toString()),
            "influence (h): canonical round-trip byte-identical");
        StringWriter w0 = new StringWriter();
        Coaster plain = new Coaster();
        TrackPiece tpl = new TrackPiece(0, 21, 0, 0);
        tpl.sections.add(new ForceSection(defaultLanes(), 26));
        plain.pieces.add(tpl);
        DesignIO.save(plain, DesignIO.Cosmetics.defaults(),
            new PrintWriter(w0));
        check(!w0.toString().contains("infl")
                && !w0.toString().contains("dlane"),
            "influence (h): uninfluenced save has no influence lines");

        // (i) PIECE REVERSAL of an influenced piece — the convert
        // rework (v17.0, D134): reversing FOLDS the influence in like
        // any whole convert (the fit reads the compiled, influenced
        // curves) and produces plain forward sections; reversing
        // twice lands back on the influenced shape to the fit budget.
        TrackCompiler.Result r0 = TrackCompiler.compile(ce, 0.01);
        check(ce.reversePiece(0, 0.01, 0.02, 0, Double.NaN),
            "influence (i): reversing an influenced piece applies "
            + "(influence folds into the convert)");
        boolean plainOut = true;
        for (Section s : ce.pieces.get(0).sections) {
            plainOut &= !(s instanceof LaneSection lso && lso.influenced());
        }
        check(plainOut,
            "influence (i): the reversed output is PLAIN sections");
        check(ce.reversePiece(0, 0.01, 0.02, 0, Double.NaN),
            "influence (i): reversing twice applies");
        TrackCompiler.Result r2b = TrackCompiler.compile(ce, 0.01);
        double worstRev = 0;
        for (int i = 0; i < r2b.samples().size(); i += 3) {
            double best = Double.MAX_VALUE;
            Vec3 pos = r2b.samples().get(i).pos();
            for (int j = 0; j < r0.samples().size(); j += 4) {
                best = Math.min(best,
                    pos.sub(r0.samples().get(j).pos()).length());
            }
            worstRev = Math.max(worstRev, best);
        }
        check(worstRev < 1.0, String.format(
            "influence (i): double reversal lands back on the "
            + "influenced shape (worst %.3f m nearest-point)", worstRev));
    }

    // ---------- CONVERSION round (v15.1-15.2, D99/D100, TESTING #38)
    // analytic rate extraction, conflict-preserving converts, rigid
    // pitch/roll placement.

    static void convRound() {
        // (a) ANALYTIC EXTRACTION: a max-accuracy adaptive convert's
        // rate graphs converge onto the original (the old ±2-sample
        // differencing floored at ~1.1 °/s however dense the fit).
        Coaster c = new Coaster();
        TrackPiece tp = new TrackPiece(0, 21, 0, 0);
        tp.sections.add(new ForceSection(defaultLanes(), 26));
        c.pieces.add(tp);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        double dur = lastOf(r0.samples()).t();
        c.convertWholeSection(0, 0, true, 0.01, 0.1, 0.0001);
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        double worst = 0;
        for (int i = 0; i < r1.rates().length; i++) {
            double t = r1.rates()[i][0];
            if (t < 1 || t > dur - 1) continue;
            for (int comp = 1; comp <= 2; comp++) {
                double orig = rateRowAt(r0.rates(), t, comp);
                worst = Math.max(worst,
                    Math.abs(r1.rates()[i][comp] - orig));
            }
        }
        // 0.6 since v17.11 (D151): the exact bake keeps the ORIGINAL's
        // rate corners sharp, and Pass A's ±2-sample differencing
        // smears them — the ~0.5 °/s corner disagreement is the
        // comparison's smear, not fit error (the old 0.05 polyline
        // low-passed both sides equally, hence the historic 0.2)
        check(worst < 0.6, String.format(
            "conv (a): analytic extraction — max-accuracy convert rates "
            + "converge (worst %.3f °/s; was ~1.1 floored)", worst));

        // (b) CONFLICT-PRESERVING CONVERT: a pinned drifted section
        // keeps vDesign, the pin, the drift AND its shape through
        // whole-convert + the rebuild loop.
        Coaster c2 = new Coaster();
        TrackPiece t2 = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> la = new ArrayList<>();
        la.add(nodes(new double[][]{ {0, 2.2}, {2, 2.2} }));
        la.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        la.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        t2.sections.add(new ForceSection(la, 26));
        ForceSection pinned = Coaster.holdSection(1.4, 0, 0, 15);
        pinned.vPinned = true;
        t2.sections.add(pinned);
        c2.pieces.add(t2);
        TrackCompiler.Result rb = rebuildLoop(c2);
        int i0 = rb.spans().get(1).i0();
        double drift0 = rb.trueV()[i0] - rb.samples().get(i0).v();
        List<FVDIntegrator.Sample> before = new ArrayList<>(
            rb.samples().subList(i0, rb.spans().get(1).i1() + 1));
        c2.convertWholeSection(0, 1, true, 0.01, 0.1, 0.0005);
        Section conv = t2.sections.get(1);
        TrackCompiler.Result ra = rebuildLoop(c2);
        int j0 = ra.spans().get(1).i0();
        double drift1 = ra.trueV()[j0] - ra.samples().get(j0).v();
        double moved = 0;
        int n = Math.min(before.size(), ra.spans().get(1).i1() + 1 - j0);
        for (int i = 0; i < n; i++) {
            moved = Math.max(moved, before.get(i).pos()
                .sub(before.get(0).pos())
                .sub(ra.samples().get(j0 + i).pos()
                    .sub(ra.samples().get(j0).pos())).length());
        }
        check(Math.abs(conv.vDesign() - 15) < 1e-9 && conv.vPinned()
                && Math.abs(drift1 - drift0) < 0.02 && moved < 0.25,   // moved = the convert FIT residual itself, not conflict loss
            String.format("conv (b): pinned convert keeps the conflict "
            + "(vDesign %.1f, pinned %b, drift %.2f -> %.2f m/s, shape "
            + "moved %.3f m)", conv.vDesign(), conv.vPinned(),
            drift0, drift1, moved));

        // (b2) partial convert of a pinned section pins BOTH halves
        Coaster c3 = new Coaster();
        TrackPiece t3 = new TrackPiece(0, 21, 0, 0);
        ForceSection whole = new ForceSection(defaultLanes(), 15);
        whole.vPinned = true;
        t3.sections.add(whole);
        c3.pieces.add(t3);
        TrackCompiler.compile(c3, 0.01);
        check(c3.partialConvertSection(0, 0, 0.5, true, true, 0.01, 0.1, 0)
                && t3.sections.get(0).vPinned()
                && t3.sections.get(1).vPinned(),
            "conv (b2): partial convert pins both halves of a pinned section");

        // (c) RIGID TILT: pitching a piece rotates FORCE geometry
        // rigidly (was a 190 m gravity re-solve) and the felt
        // re-projection keeps the κ identity (trueFelt == recorded
        // felt when no speed drift exists at the entry).
        for (boolean geo : new boolean[]{ false, true }) {
            Coaster c4 = new Coaster();
            TrackPiece t4 = new TrackPiece(0, 21, 0, 0);
            if (geo) {
                List<List<BezierBaker.Node>> gl = new ArrayList<>();
                gl.add(nodes(new double[][]{ {0, 10}, {6, 10} }));
                gl.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
                gl.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
                t4.sections.add(new GeoSection(gl, 26));
            } else {
                t4.sections.add(new ForceSection(defaultLanes(), 26));
            }
            c4.pieces.add(t4);
            List<FVDIntegrator.Sample> s0 =
                TrackCompiler.compile(c4, 0.01).samples();
            t4.pitchDeg = 10;
            t4.rollDeg = -7;
            List<FVDIntegrator.Sample> s1 =
                TrackCompiler.compile(c4, 0.01).samples();
            Quat tilt = t4.tilt();
            Vec3 o = s0.get(0).pos();
            double dev = 0;
            for (int i = 0; i < Math.min(s0.size(), s1.size()); i++) {
                dev = Math.max(dev, tilt.rotate(s0.get(i).pos().sub(o))
                    .add(o).sub(s1.get(i).pos()).length());
            }
            check(s0.size() == s1.size() && dev < 1e-9, String.format(
                "conv (c): %s piece pitch 10° roll -7° is RIGID "
                + "(dev %.2e m)", geo ? "geo" : "force", dev));
            t4.pitchDeg = 0;
            t4.rollDeg = 0;
            List<FVDIntegrator.Sample> s2 =
                TrackCompiler.compile(c4, 0.01).samples();
            check(samplesBitwise(s0, s2),
                "conv (c): zeroing the tilt restores BITWISE ("
                + (geo ? "geo" : "force") + ")");
        }
        // felt identity under tilt: κ_U from the re-projected felt
        // matches the untilted piece's κ_U sample-for-sample
        Coaster c5 = new Coaster();
        TrackPiece t5 = new TrackPiece(0, 21, 0, 0);
        t5.sections.add(new ForceSection(defaultLanes(), 26));
        c5.pieces.add(t5);
        List<FVDIntegrator.Sample> f0 =
            TrackCompiler.compile(c5, 0.01).samples();
        t5.pitchDeg = 12;
        List<FVDIntegrator.Sample> f1 =
            TrackCompiler.compile(c5, 0.01).samples();
        double worstK = 0;
        for (int i = 0; i < f0.size(); i++) {
            double k0 = f0.get(i).Gv() - f0.get(i).U().y();
            double k1 = f1.get(i).Gv() - f1.get(i).U().y();
            worstK = Math.max(worstK, Math.abs(k1 - k0));
        }
        check(worstK < 1e-12, String.format(
            "conv (c): felt re-projection preserves the κ identity "
            + "(worst %.2e g)", worstK));

        // (d) REFREEZE AFTER A CONFLICT (the v15.4 resolve flow):
        // [dive, pinned-conflicted A, B] — B tracks A's design profile
        // so it is conflicted too; RE-FREEZE re-anchors B to the true
        // entry, A keeps its conflict, and the rebuild loop's
        // reconcile RESPECTS the real anchor instead of undoing it
        // (the value/slope line-up is B/Shift+B, editor-side).
        Coaster c6 = new Coaster();
        TrackPiece t6 = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> dl = new ArrayList<>();
        dl.add(nodes(new double[][]{ {0, 2.2}, {2, 2.2} }));
        dl.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        dl.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        t6.sections.add(new ForceSection(dl, 26));
        ForceSection A6 = Coaster.holdSection(1.5, 0, 0, 15);
        A6.vPinned = true;
        t6.sections.add(A6);
        c6.pieces.add(t6);
        TrackCompiler.Result r6 = rebuildLoop(c6);
        // B continues A's DESIGN profile (the '+' "continue" rule)
        ForceSection B6 = Coaster.holdSection(1.2, 0, 0,
            r6.samples().get(r6.spans().get(1).i1()).v());
        t6.sections.add(B6);
        r6 = rebuildLoop(c6);
        int b0 = r6.spans().get(2).i0();
        double bDrift0 = Math.abs(r6.trueV()[b0]
            - r6.samples().get(b0).v());
        check(bDrift0 > 0.5, String.format(
            "conv (d): fixture — B inherits A's conflict (drift %.2f m/s)",
            bDrift0));
        boolean ok = c6.refreezeSpeed(0, 2, 0.01);
        TrackCompiler.Result r7 = rebuildLoop(c6);
        int b1 = r7.spans().get(2).i0();
        double bDrift1 = Math.abs(r7.trueV()[b1]
            - r7.samples().get(b1).v());
        int a1 = r7.spans().get(1).i0();
        double aDrift1 = Math.abs(r7.trueV()[a1]
            - r7.samples().get(a1).v());
        check(ok && bDrift1 < 0.05, String.format(
            "conv (d): RE-FREEZE right of a conflicted section closes "
            + "B's split (%.2f -> %.3f m/s) and the reconcile keeps "
            + "the real anchor (v15.4 flow: refreeze then Shift+B)",
            bDrift0, bDrift1));
        check(aDrift1 > 0.5 && A6.vPinned, String.format(
            "conv (d): A keeps its own conflict (drift %.2f m/s, "
            + "pinned %b)", aDrift1, A6.vPinned));
    }

    static double rateRowAt(double[][] rows, double t, int comp) {
        int lo = 0, hi = rows.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (rows[mid][0] <= t) lo = mid; else hi = mid;
        }
        double u = (t - rows[lo][0])
            / Math.max(1e-12, rows[hi][0] - rows[lo][0]);
        u = Math.max(0, Math.min(1, u));
        return rows[lo][comp] + u * (rows[hi][comp] - rows[lo][comp]);
    }

    static TrackCompiler.Result rebuildLoop(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int pass = 0; pass < 3; pass++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }
}
