import uvd.*;

import java.util.*;

/** D232 gate — MAKE SMOOTHING REAL + the s/c entry roll ease.
 *  a) a smoothed strict seam converts: structure (upstream piece
 *     gains the window section in ITS type/flags, follower's first
 *     section cut, follower re-anchored at the window end, smoothing
 *     off), whole-track shape held to the fit-residual class, the
 *     downstream tail rigid to the same class.
 *  b) refusals: s/c neighbor, speed control on a neighbor —
 *     WINDOW-SCOPED for controls (D233) and INFLUENCE deltas
 *     (D234): outside the window carries, inside refuses.
 *  c) elem-0 blendIn: 0 == the historic 2 m ease BITWISE; 5 m
 *     stretches the entry bank ease; 0.5 m shortens it below the
 *     old floor.
 *  d) THE SMOOTHING COMPILE'S REGRESSION PINS (batch 38, D256 — recorded
 *     while the short-lived translational mode existed; kept in D257,
 *     which removed that mode, as the smoothing's own regression belt;
 *     on the (a) fixture):
 *     d1 the settled compile's 64-bit sample checksum equals the value
 *        recorded from the frozen pre-D256 snapshot (8 m
 *        28808918c0612fbe / 20 m 0fc8182da8f4a418 over 1601 samples)
 *        — the D140 closure, BITWISE; re-record on any deliberate
 *        blend change;
 *     d2 make-real on the smoothed compile (the D256 d5 round trip in
 *        its closure form): applies, the structure of (a), the fresh
 *        window-end seam has smoothing off, B's anchor == the smoothed
 *        compile's window-end sample (1e-6), whole-track pathDev <
 *        0.08, tail rigid.
 */
public class SmoothRealGate {

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
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 2.4},
            {len * 0.7, 0.4}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.4}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 30}, {len, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> turnLanes(double len) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.5, 1.8}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, -0.4}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        return ls;
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
        // ---- fixture: A (hill) | strict seam | B (turn + hill) ----
        Coaster c = new Coaster();
        TrackPiece A = new TrackPiece(0, 21, 0, 0);
        A.sections.add(new ForceSection(hillLanes(6), 26));
        A.sections.add(new ForceSection(turnLanes(5), 26));
        c.pieces.add(A);
        int np = c.makeSeamStrict(0, 1, DT);
        check(np == 1, "fixture: strict seam made");
        TrackPiece B = c.pieces.get(1);
        B.sections.add(new ForceSection(hillLanes(5), 24));
        // give the seam a real kink so the smoothing has work: tilt
        // the follower's held pitch a few degrees
        B.sPitchDeg += 6;
        B.sSmoothLen = 8;
        TrackCompiler.Result r0 = rebuild(c);
        int nA0 = A.sections.size(), nB0 = B.sections.size();

        // ---- (a) the conversion ----
        String why = c.makeSmoothingReal(1, DT, 0.02, 0);
        check(why == null, "a1 makeSmoothingReal applies", "why=" + why);
        check(A.sections.size() == nA0 + 1
                && A.sections.get(A.sections.size() - 1)
                    instanceof ForceSection
                && B.sections.size() == nB0,
            "a2 structure: upstream gains the FORCE window section, "
            + "follower keeps its count");
        check(B.followPrev && B.sSmoothLen == 0,
            "a3 follower still follows at the window end, smoothing off");
        TrackCompiler.Result r1 = rebuild(c);
        double dev = pathDev(r0.samples(), r1.samples());
        check(dev < 0.08,
            "a4 whole track holds through the conversion",
            String.format("worst dev %.4f m", dev));
        // the downstream tail (B's second section) stays put
        int tail0 = r0.spans().get(2).i0(), tail1 = r0.spans().get(2).i1();
        List<FVDIntegrator.Sample> t0 =
            r0.samples().subList(tail0, tail1 + 1);
        int nt0 = r1.spans().size();
        List<FVDIntegrator.Sample> t1 = r1.samples().subList(
            r1.spans().get(nt0 - 1).i0(), r1.spans().get(nt0 - 1).i1() + 1);
        double devT = pathDev(t0, t1);
        check(devT < 0.08, "a5 downstream tail rigid to the same class",
            String.format("worst dev %.4f m", devT));

        // ---- (b) refusals ----
        Coaster cs = new Coaster();
        TrackPiece sA = new TrackPiece(0, 21, 0, 0);
        sA.sections.add(new ForceSection(hillLanes(6), 26));
        sA.sections.add(StraightCurveSection.straight(30, 24));
        cs.pieces.add(sA);
        int np2 = cs.makeSeamStrict(0, 1, DT);
        cs.pieces.get(np2).sSmoothLen = 6;
        check(cs.makeSmoothingReal(np2, DT, 0.05, 0) != null,
            "b1 s/c neighbor refuses with a reason");
        Coaster cc = new Coaster();
        TrackPiece cA = new TrackPiece(0, 21, 0, 0);
        cA.sections.add(new ForceSection(hillLanes(6), 26));
        cA.sections.add(new ForceSection(turnLanes(5), 26));
        cc.pieces.add(cA);
        int np3 = cc.makeSeamStrict(0, 1, DT);
        cc.pieces.get(np3).sSmoothLen = 6;
        SpeedControl ctl = new SpeedControl(1, 2);
        ctl.speed = 12;
        cA.sections.get(0).speedControls().add(ctl);
        // OUTSIDE the window (D233): applies, the control rides onto
        // the kept left half
        check(cc.makeSmoothingReal(np3, DT, 0.05, 0) == null
                && cA.sections.get(0).speedControls().size() == 1,
            "b2 a control OUTSIDE the window applies and is carried");
        // INSIDE the window: refuses
        Coaster cd = new Coaster();
        TrackPiece dA = new TrackPiece(0, 21, 0, 0);
        dA.sections.add(new ForceSection(hillLanes(6), 26));
        dA.sections.add(new ForceSection(turnLanes(5), 26));
        cd.pieces.add(dA);
        int np4 = cd.makeSeamStrict(0, 1, DT);
        cd.pieces.get(np4).sSmoothLen = 6;
        SpeedControl ctl2 = new SpeedControl(5.7, 0.2);
        ctl2.speed = 12;
        dA.sections.get(0).speedControls().add(ctl2);
        check(cd.makeSmoothingReal(np4, DT, 0.05, 0) != null,
            "b3 a control INSIDE the window refuses with a reason");
        // INFLUENCE joins the window-scoped rule (v19.5, D234): zero
        // deltas across the window = applies, the kept halves STAY
        // influenced; non-zero deltas inside the window = refuse
        Coaster ce = new Coaster();
        TrackPiece eA = new TrackPiece(0, 21, 0, 0);
        eA.sections.add(new ForceSection(hillLanes(6), 26));
        eA.sections.add(new ForceSection(turnLanes(5), 26));
        ce.pieces.add(eA);
        int np5 = ce.makeSeamStrict(0, 1, DT);
        TrackPiece eB = ce.pieces.get(np5);
        eB.sPitchDeg += 6;
        eB.sSmoothLen = 6;
        LaneSection ePrev = (LaneSection) eA.sections.get(0);
        Coaster.enterInfluence(ePrev, 6);
        ePrev.deltaLanes.set(1, lane(new double[][]{ {0, 0}, {0.5, 0},
            {1.2, 4}, {2.0, 0}, {6, 0} }));   // early Δyaw bump only
        LaneSection eNext = (LaneSection) eB.sections.get(0);
        Coaster.enterInfluence(eNext, 5);     // all-zero deltas
        String whyE = ce.makeSmoothingReal(np5, DT, 0.05, 0);
        check(whyE == null,
            "b4 influence with zero deltas in the window applies",
            "why=" + whyE);
        LaneSection keptL = (LaneSection) eA.sections.get(0);
        LaneSection keptR = (LaneSection) eB.sections.get(0);
        check(keptL.influenced() && keptR.influenced()
                && keptL.vAuthored == keptL.vDesign
                && keptR.vAuthored == keptR.vDesign,
            "b5 kept halves stay influenced with mirrored pairs");
        Coaster cf = new Coaster();
        TrackPiece fA = new TrackPiece(0, 21, 0, 0);
        fA.sections.add(new ForceSection(hillLanes(6), 26));
        fA.sections.add(new ForceSection(turnLanes(5), 26));
        cf.pieces.add(fA);
        int np6 = cf.makeSeamStrict(0, 1, DT);
        cf.pieces.get(np6).sSmoothLen = 6;
        LaneSection fPrev = (LaneSection) fA.sections.get(0);
        Coaster.enterInfluence(fPrev, 6);
        fPrev.deltaLanes.set(1, lane(new double[][]{ {0, 0}, {5.5, 0},
            {5.9, 4}, {6, 0} }));             // bump INSIDE the window
        check(cf.makeSmoothingReal(np6, DT, 0.05, 0) != null,
            "b6 non-zero deltas INSIDE the window refuse");

        // ---- (c) s/c entry roll ease (elem 0 blendIn) ----
        // GLOBAL 40-degree bank on the first element: the entry ease
        // blends the bank in from the incoming (flat) frame
        java.util.function.DoubleFunction<Coaster> mk = ease -> {
            Coaster cx = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            StraightCurveSection sc = StraightCurveSection.straight(30, 24);
            sc.elements.get(0).rollMode = StraightCurveSection.RollMode.GLOBAL;
            sc.elements.get(0).rollVal = 40;
            sc.elements.get(0).blendIn = ease;
            p.sections.add(sc);
            cx.pieces.add(p);
            return cx;
        };
        java.util.function.DoubleFunction<double[]> bankAt = ease -> {
            TrackCompiler.Result rr =
                TrackCompiler.compile(mk.apply(ease), DT);
            double[] out = new double[3];   // bank at 1 m, 3 m, 6 m
            double[] at = { 1, 3, 6 };
            for (int k = 0; k < 3; k++) {
                for (FVDIntegrator.Sample sm : rr.samples()) {
                    if (sm.s() >= at[k]) {
                        double[] eu = Coaster.eulerOf(
                            Quat.fromFrame(sm.T(), sm.U()));
                        out[k] = eu[2];
                        break;
                    }
                }
            }
            return out;
        };
        // D233 semantics: the value is VERBATIM (0 = sharp snap); the
        // ease is the QUINTIC (rate tangent to zero at both ends)
        double[] b0v = bankAt.apply(0);
        check(Math.abs(Math.abs(b0v[0]) - 40) < 0.5,
            "c1 ease 0 = SHARP: full bank immediately (D233)",
            String.format("bank@1m %.1f", b0v[0]));
        double[] b2v = bankAt.apply(2);
        check(Math.abs(Math.abs(b2v[1]) - 40) < 0.5,
            "c2 2 m ease (the new-section default): done by 3 m",
            String.format("bank@3m %.1f", b2v[1]));
        // per-sample law check on a 5 m ease: bank(s) must be the
        // QUINTIC of u = s/5 at every sample's own arc (the projection
        // stamps it exactly; a sine ease misses by up to 2° mid-ramp)
        TrackCompiler.Result r5 = TrackCompiler.compile(mk.apply(5), DT);
        double worstQ = 0;
        for (FVDIntegrator.Sample sm : r5.samples()) {
            if (sm.s() <= 0.01 || sm.s() >= 5) continue;
            double u = sm.s() / 5;
            double want = 40 * u * u * u * (u * (u * 6 - 15) + 10);
            double[] eu = Coaster.eulerOf(Quat.fromFrame(sm.T(), sm.U()));
            worstQ = Math.max(worstQ, Math.abs(Math.abs(eu[2]) - want));
        }
        check(worstQ < 0.2,
            "c3 the ease IS the quintic (per-sample law, 5 m ease)",
            String.format("worst dev %.3f°", worstQ));

        sectionD();

        System.out.println();
        System.out.println(fail == 0
            ? "ALL SMOOTH-REAL GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }

    // =================================================================
    // (d) THE SMOOTHING COMPILE'S REGRESSION PINS (batch 38, D256;
    //     reduced in D257 — the translational mode is gone)
    // =================================================================

    /** The (a) fixture, fresh: A [hill 6 s] | strict | B [turn 5 s,
     *  hill 5 s], B's held pitch +6°, smoothing `len` m. */
    static Coaster fixtureD(double len) {
        Coaster c = new Coaster();
        TrackPiece A = new TrackPiece(0, 21, 0, 0);
        A.sections.add(new ForceSection(hillLanes(6), 26));
        A.sections.add(new ForceSection(turnLanes(5), 26));
        c.pieces.add(A);
        int np = c.makeSeamStrict(0, 1, DT);
        TrackPiece B = c.pieces.get(np);
        B.sections.add(new ForceSection(hillLanes(5), 24));
        B.sPitchDeg += 6;
        B.sSmoothLen = len;
        return c;
    }

    /** 64-bit checksum of every sample's pos/T/U/v/Gv/Gl/s/t bits —
     *  the D256 design probe's checksum() verbatim (the recorded
     *  reference values were produced by it on the frozen pre-D256
     *  snapshot). */
    static long checksum(List<FVDIntegrator.Sample> all) {
        long h = 1469598103934665603L;
        for (FVDIntegrator.Sample sm : all) {
            double[] v = { sm.pos().x(), sm.pos().y(), sm.pos().z(),
                sm.T().x(), sm.T().y(), sm.T().z(),
                sm.U().x(), sm.U().y(), sm.U().z(), sm.v(), sm.Gv(), sm.Gl(),
                sm.s(), sm.t() };
            for (double d : v) {
                h ^= Double.doubleToLongBits(d);
                h *= 1099511628211L;
            }
        }
        return h;
    }

    static void sectionD() throws Exception {
        // ---- d1: the recorded checksums (the D140 closure, bitwise) ----
        {
            Coaster c = fixtureD(8);
            TrackCompiler.Result r8 = rebuild(c);
            long want8 = Long.parseUnsignedLong("28808918c0612fbe", 16);
            check(r8.samples().size() == 1601 && checksum(r8.samples()) == want8,
                "d1 the smoothing compile at 8 m == the recorded pre-D256 snapshot BITWISE",
                String.format("%016x over %d samples (want 28808918c0612fbe / 1601)",
                    checksum(r8.samples()), r8.samples().size()));
            c.pieces.get(1).sSmoothLen = 20;
            TrackCompiler.Result r20 = rebuild(c);
            long want20 = Long.parseUnsignedLong("0fc8182da8f4a418", 16);
            check(r20.samples().size() == 1601
                    && checksum(r20.samples()) == want20,
                "d1 the smoothing compile at 20 m == the snapshot's compile BITWISE",
                String.format("%016x (want 0fc8182da8f4a418)",
                    checksum(r20.samples())));
            Coaster c2 = fixtureD(8);
            TrackCompiler.Result rA = rebuild(c2);
            check(checksum(rA.samples()) == want8,
                "d1 a fresh fixture's settled compile repeats the checksum",
                String.format("%016x", checksum(rA.samples())));
        }
        // ---- d2: make-real on the smoothed compile ----
        {
            Coaster c = fixtureD(8);
            TrackPiece A = c.pieces.get(0), B = c.pieces.get(1);
            TrackCompiler.Result r0 = rebuild(c);
            int nA0 = A.sections.size(), nB0 = B.sections.size();
            // the window-end arc the op cuts at (its own clamps); the
            // seam sits between spans 0 (A's hill) and 1 (B's turn)
            TrackCompiler.Span sa = r0.spans().get(0), sb = r0.spans().get(1);
            double sSeam = r0.samples().get(sa.i1()).s();
            double hDn = Math.min(B.sSmoothLen / 2, sb.sLen() * 0.5);
            double arcB = sSeam + hDn;
            java.util.function.BiFunction<List<FVDIntegrator.Sample>, Double, Vec3>
                lerpAt = (ss, arc) -> {
                    int lo = sb.i0(), hi = sb.i1();
                    while (hi - lo > 1) {
                        int mid = (lo + hi) >>> 1;
                        if (ss.get(mid).s() <= arc) lo = mid; else hi = mid;
                    }
                    double u = (arc - ss.get(lo).s())
                        / (ss.get(hi).s() - ss.get(lo).s());
                    u = Math.max(0, Math.min(1, u));
                    return ss.get(lo).pos().add(
                        ss.get(hi).pos().sub(ss.get(lo).pos()).scale(u));
                };
            Vec3 cutT = lerpAt.apply(r0.samples(), arcB);
            String why = c.makeSmoothingReal(1, DT, 0.02, 0);
            check(why == null, "d2 makeSmoothingReal applies on the smoothed compile",
                "why=" + why);
            check(A.sections.size() == nA0 + 1 && B.sections.size() == nB0
                    && A.sections.get(A.sections.size() - 1) instanceof ForceSection,
                "d2 structure: the upstream gains the window section, the follower"
                + " keeps its count", "");
            check(B.followPrev && B.sSmoothLen == 0,
                "d2 the fresh window-end seam: smoothing off", "");
            Vec3 bPos = new Vec3(B.x, B.y, B.z);
            check(bPos.sub(cutT).length() < 1e-6,
                "d2 B re-anchors at the smoothed compile's window-end sample",
                String.format("%.2e m", bPos.sub(cutT).length()));
            TrackCompiler.Result r1 = rebuild(c);
            double dev = pathDev(r0.samples(), r1.samples());
            check(dev < 0.08, "d2 whole track holds through the conversion",
                String.format("worst dev %.4f m", dev));
            int t0a = r0.spans().get(2).i0(), t0b = r0.spans().get(2).i1();
            int nsp = r1.spans().size();
            double devT = pathDev(r0.samples().subList(t0a, t0b + 1),
                r1.samples().subList(r1.spans().get(nsp - 1).i0(),
                    r1.spans().get(nsp - 1).i1() + 1));
            check(devT < 0.08, "d2 downstream tail rigid to the same class",
                String.format("worst dev %.4f m", devT));
        }
    }
}
