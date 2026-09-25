import uvd.*;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** v16.0/v16.1 domain + axis gates (TESTING #40/#41, D103-D107).
 *  Scratch main against target/classes. */
public class DomainGate {

    static int fails = 0;
    static double dt = 0.01;

    static void check(String name, boolean ok, String detail) {
        System.out.printf("%-58s %s  %s%n", name, ok ? "PASS" : "FAIL", detail);
        if (!ok) fails++;
    }

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

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

    public static void main(String[] args) throws Exception {
        // ---- 40a: do-no-harm — the all-TIME default layout keeps its
        // RK4 baseline exactly ----
        Coaster base = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        TrackCompiler.Result r0 = TrackCompiler.compile(base, dt);
        FVDIntegrator.Sample e0 = r0.samples().get(r0.samples().size() - 1);
        double pitch0 = Math.toDegrees(Math.asin(e0.T().y()));
        // re-baselined v17.11 (D151: exact bake + 0.01 time grid)
        check("40a do-no-harm: samples/length/pitch baseline",
            r0.samples().size() == 1347
                && Math.abs(e0.s() - 406.931) < 0.01
                && Math.abs(pitch0 - 0.2131) < 0.001,
            String.format("n=%d s=%.3f pitch=%.4f",
                r0.samples().size(), e0.s(), pitch0));

        // ---- 40b: DISTANCE circle ----
        double v0 = 20;
        double rTh = v0 * v0 / (FVDIntegrator.G0 * 1.5);
        double circ = 2 * Math.PI * rTh;
        List<List<BezierBaker.Node>> cl = new ArrayList<>();
        cl.add(nodes(new double[][]{ {0,1},{circ,1} }));
        cl.add(nodes(new double[][]{ {0,1.5},{circ,1.5} }));
        cl.add(nodes(new double[][]{ {0,0},{circ,0} }));
        Coaster cc = Coaster.singleForce(cl, v0, 0, 21, 0);
        ((LaneSection) cc.soleSection()).domain(Section.Domain.DISTANCE);
        TrackCompiler.Result rc = TrackCompiler.compile(cc, dt);
        // +Gl = felt RIGHT since v18.13 (D184): the +1.5 lateral
        // circles the OTHER way — center on the −R side
        Vec3 c0 = rc.samples().get(0).pos()
            .sub(rc.samples().get(0).R().scale(rTh));
        double worst = 0;
        for (FVDIntegrator.Sample sm : rc.samples()) {
            worst = Math.max(worst,
                Math.abs(sm.pos().sub(c0).length() - rTh));
        }
        FVDIntegrator.Sample ce = rc.samples().get(rc.samples().size() - 1);
        check("40b distance circle: radial dev + exact length",
            worst < 1e-6 && Math.abs(ce.s() - circ) < 1e-9,
            String.format("dev=%.2e sEnd-circ=%.2e", worst, ce.s() - circ));

        // ---- 40d/e (v16.1 rework, D104): domain conversion is a
        // REFIT that CONVERGES with density ----
        // v16.5 (D111): the AREA-MATCHED fit dominated the residual
        // (coarse ~0.02 m) — by letting the matcher swing the curve
        // POINTWISE far off the data (5x the raw fit's miss at 2.0
        // spacing), the same trade that RANG stepped carpets with
        // alternating +-2 g nodes (the user's 2026-08-24 bake
        // oscillation). v19.6 (D235) guards the matcher pointwise:
        // coarse round trips re-baseline to the D104 "converges with
        // density" story (~3.2 m at 2.0 — nobody converts there),
        // FINE fits improved 0.24 -> ~0.05 m and never ring.
        double devCoarse = domainRoundDev(2.0, 0.1);
        double devFine = domainRoundDev(0.7, 0.05);
        check("40d area-matched refit: fine at cm scale, coarse converges",
            devCoarse < 4.0 && devFine < 0.1,
            String.format("coarse=%.3f fine=%.3f m", devCoarse, devFine));

        // ---- 40f: conversion carries vDesign + vPinned ----
        Coaster pc = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        LaneSection ps = (LaneSection) pc.soleSection();
        ps.vPinned = true;
        boolean okC = pc.convertDomain(0, 0, dt, 1.0, 0);
        Section after = pc.soleSection();
        check("40f convertDomain: DISTANCE out, v + pin carried",
            okC && after.domain() == Section.Domain.DISTANCE
                && after.vDesign() == 26 && after.vPinned(),
            String.format("v=%.1f pin=%b dom=%s", after.vDesign(),
                after.vPinned(), after.domain()));

        // ---- 40g: reversed sections refuse the domain refit ----
        Coaster rv = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        rv.soleSection().reversed(true);
        check("40g reversed refuses domain conversion",
            !rv.convertDomain(0, 0, dt, 1.0, 0), "");

        // ---- 40i (v16.1): influenced sections CONVERT — influence
        // captured, output plain ----
        Coaster in = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        LaneSection is = (LaneSection) in.soleSection();
        Coaster.enterInfluence(is, 13.46);
        boolean okI = in.convertDomain(0, 0, dt, 1.0, 0);
        Section conv = in.soleSection();
        boolean plain = conv instanceof LaneSection cl2 && !cl2.influenced();
        check("40i influenced converts: captured, output plain DIST",
            okI && plain && conv.domain() == Section.Domain.DISTANCE, "");

        // ---- 40j: FVDD3 round trip: distance + axis flags ----
        Coaster io = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        io.convertDomain(0, 0, dt, 1.0, 0);
        ((LaneSection) io.soleSection()).rollGlobal = true;
        StringWriter w1 = new StringWriter();
        DesignIO.save(io, DesignIO.Cosmetics.defaults(), new PrintWriter(w1));
        DesignIO.LoadResult lr = DesignIO.load(
            new BufferedReader(new StringReader(w1.toString())));
        LaneSection lsec = (LaneSection) lr.coaster().soleSection();
        StringWriter w2 = new StringWriter();
        DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));
        check("40j save→load→save: byte-identical, domain + flags",
            w1.toString().equals(w2.toString())
                && lsec.domain() == Section.Domain.DISTANCE
                && lsec.rollGlobal && !lsec.yawPitchGlobal, "");

        // ---- 40k: '+' continuation carries domain + axis flags ----
        LaneSection cont = Coaster.continuation(lsec, 26);
        check("40k continuation: 10 m DIST hold, flags carried",
            cont.domain() == Section.Domain.DISTANCE
                && Math.abs(BezierBaker.rideEnd(cont.lanes) - 10) < 1e-12
                && cont.rollGlobal,
            String.format("end=%.2f", BezierBaker.rideEnd(cont.lanes)));

        // ---- 40l: partial convert on a DISTANCE section ----
        Coaster pcv = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        pcv.convertDomain(0, 0, dt, 0.4, 0);
        TrackCompiler.Result pcvBefore = TrackCompiler.compile(pcv, dt);
        boolean did5 = pcv.partialConvertSection(0, 0, 0.4, false, true, dt,
            0.1, 0);
        TrackCompiler.Result pcvAfter = TrackCompiler.compile(pcv, dt);
        Section keep = pcv.pieces.get(0).sections.get(0);
        double devP = endDev(pcvBefore, pcvAfter);
        check("40l partial convert (right→geo) on distance lanes",
            did5 && keep.domain() == Section.Domain.DISTANCE
                && pcv.pieces.get(0).sections.size() == 2
                && devP < 0.5,
            String.format("kept=%s endDev=%.3f m", keep.domain(), devP));

        // ============ v16.1 gates (D105-D107) ============

        // ---- 41a: slope-preserving node move (D105) ----
        List<BezierBaker.Node> ln = nodes(new double[][]{ {0,0},{1,1},{3,0} });
        BezierBaker.Node mid = ln.get(1);
        mid.outDv = 0.5;
        mid.inDv = -0.4;
        double slopeOut = mid.outDv / BezierBaker.effOutDt(mid, ln.get(2));
        double slopeIn = mid.inDv / BezierBaker.effInDt(ln.get(0), mid);
        double[] gaps = BezierBaker.segGaps(ln);
        mid.t = 2.0;   // slide the node right
        BezierBaker.rescaleDvToGaps(ln, gaps);
        double slopeOut2 = mid.outDv / BezierBaker.effOutDt(mid, ln.get(2));
        double slopeIn2 = mid.inDv / BezierBaker.effInDt(ln.get(0), mid);
        check("41a node move keeps handle slopes (D105)",
            Math.abs(slopeOut2 - slopeOut) < 1e-12
                && Math.abs(slopeIn2 - slopeIn) < 1e-12,
            String.format("out %.4f→%.4f in %.4f→%.4f",
                slopeOut, slopeOut2, slopeIn, slopeIn2));

        // ---- 41b: refreeze BAKES the tilt — the whole split closes
        // (D106; the user's "refreeze isn't fully resolving") ----
        Coaster tb = new Coaster();
        tb.v0 = 26;
        TrackPiece p0 = new TrackPiece(0, 21, 0, 0);
        p0.sections.add(new ForceSection(nodes4(1, 0, 0, 4), 26));
        tb.pieces.add(p0);
        TrackPiece p1 = new TrackPiece(120, 23, 0, 0);
        p1.sections.add(new ForceSection(nodes4(1, 0, 0, 4), 24));
        tb.pieces.add(p1);
        TrackCompiler.Result rt0 = TrackCompiler.compile(tb, dt);
        TrackPose end0 = rt0.spans().get(0).exit();
        p0.sections.add(BridgeSection.solve(end0, p1.entryPose(),
            rt0.samples().get(rt0.spans().get(0).i1()).v()));
        p1.pitchDeg = 8;   // the user's scenario: pitch after a bridge
        TrackCompiler.Result rt1 = TrackCompiler.compile(tb, dt);
        double driftBefore = spanDrift(rt1, 2);
        double feltBefore = feltSplit(rt1, 2);
        Quat entryBefore = p1.entryPose().orient();
        boolean okR = tb.refreezeSpeed(1, 0, dt);
        TrackCompiler.Result rt2 = TrackCompiler.compile(tb, dt);
        double driftAfter = spanDrift(rt2, 2);
        double feltAfter = feltSplit(rt2, 2);
        Quat entryAfter = p1.entryPose().orient();
        double entryDev = Math.abs(entryBefore.w() - entryAfter.w())
            + Math.abs(entryBefore.x() - entryAfter.x())
            + Math.abs(entryBefore.y() - entryAfter.y())
            + Math.abs(entryBefore.z() - entryAfter.z());
        check("41b tilted refreeze: split fully closes, entry kept",
            okR && p1.pitchDeg == 0 && entryDev < 1e-9
                && driftAfter < 0.05 && feltAfter < 0.02
                && (driftBefore > 0.05 || feltBefore > 0.02),
            String.format("drift %.3f→%.4f m/s felt %.3f→%.4f g",
                driftBefore, driftAfter, feltBefore, feltAfter));

        // ---- 41c: GLOBAL axes — the upward spiral (D107) ----
        // pitched entry, global yaw 15°/s, zero pitch + roll rates:
        // elevation HOLDS, bank HOLDS level, heading advances — the
        // spiral, not the inclined loop
        double elev = Math.toRadians(15);
        Quat pq = Quat.aboutAxis(new Vec3(0, 0, 1), elev);   // pitch up
        // yaw -15 since D185 (+yaw = left): the SAME physical spiral
        // as the historic +15, so headTurn stays +120
        Coaster sp = geoCoaster(nodes4(0, -15, 0, 8), 24, pq);
        LaneSection gsec = (LaneSection) sp.soleSection();
        gsec.yawPitchGlobal = true;
        gsec.rollGlobal = true;
        TrackCompiler.Result rg = TrackCompiler.compile(sp, dt);
        double elevDev = 0, bankDev = 0;
        for (FVDIntegrator.Sample sm : rg.samples()) {
            elevDev = Math.max(elevDev,
                Math.abs(Math.asin(sm.T().y()) - elev));
            double b = bankOf(sm);
            if (!Double.isNaN(b)) bankDev = Math.max(bankDev, Math.abs(b));
        }
        FVDIntegrator.Sample gEnd = rg.samples().get(rg.samples().size() - 1);
        double headTurn = Math.toDegrees(
            Math.atan2(-gEnd.T().z(), gEnd.T().x()));
        // local axes for contrast: same lanes, elevation drifts
        Coaster spL = geoCoaster(nodes4(0, -15, 0, 8), 24, pq);
        TrackCompiler.Result rl = TrackCompiler.compile(spL, dt);
        double elevDevL = 0;
        for (FVDIntegrator.Sample sm : rl.samples()) {
            elevDevL = Math.max(elevDevL,
                Math.abs(Math.asin(sm.T().y()) - elev));
        }
        check("41c global yaw+roll: upward spiral (elev+bank hold)",
            elevDev < 0.005 && bankDev < 0.005
                && Math.abs(headTurn - 120) < 1.0
                && elevDevL > 0.05,
            String.format("elevDev=%.4f bankDev=%.4f turn=%.1f° localElevDev=%.3f",
                elevDev, bankDev, headTurn, elevDevL));

        // ---- 41d: global roll integrates the rate (level track) ----
        List<List<BezierBaker.Node>> rl4 = new ArrayList<>();
        rl4.add(nodes(new double[][]{ {0,1},{4,1} }));
        rl4.add(nodes(new double[][]{ {0,0},{4,0} }));
        rl4.add(nodes(new double[][]{ {0,20},{4,20} }));   // 20°/s roll
        Coaster gr = Coaster.singleForce(rl4, 24, 0, 21, 0);
        ((LaneSection) gr.soleSection()).rollGlobal = true;
        TrackCompiler.Result rr = TrackCompiler.compile(gr, dt);
        double bankEnd = Math.toDegrees(
            bankOf(rr.samples().get(rr.samples().size() - 1)));
        check("41d global roll: bank == ∫rate on level track",
            Math.abs(bankEnd - 80) < 1.0,
            String.format("bank=%.2f° (80 expected)", bankEnd));

        // ---- 41e: influence on a DISTANCE section (D107/v16.1) ----
        Coaster di = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        di.convertDomain(0, 0, dt, 0.4, 0);
        LaneSection dsec = (LaneSection) di.soleSection();
        TrackCompiler.Result pre = TrackCompiler.compile(di, dt);
        Coaster.enterInfluence(dsec, BezierBaker.rideEnd(dsec.lanes));
        TrackCompiler.Result zer = TrackCompiler.compile(di, dt);
        boolean bitwise = pre.samples().size() == zer.samples().size();
        if (bitwise) {
            for (int i = 0; i < pre.samples().size(); i++) {
                if (!pre.samples().get(i).pos().equals(
                        zer.samples().get(i).pos())) {
                    bitwise = false;
                    break;
                }
            }
        }
        // sculpt a yaw delta and verify resulting == base + delta at
        // mid-arc (the §5.4 identity, now over meters)
        double len = BezierBaker.rideEnd(dsec.lanes);
        for (BezierBaker.Node n : dsec.deltaLanes.get(1)) n.val = 6;
        TrackCompiler.Result inf = TrackCompiler.compile(di, dt);
        int midIdx = inf.spans().get(0).i0()
            + (inf.spans().get(0).i1() - inf.spans().get(0).i0()) / 2;
        double sMid = inf.samples().get(midIdx).s();
        double baseYaw = dsec.baseValAt(1, sMid);
        double resYaw = inf.rates()[midIdx][2];
        check("41e distance influence: zero bitwise, delta adds",
            bitwise && Math.abs(resYaw - (baseYaw + 6)) < 0.3,
            String.format("bitwise=%b res=%.2f base+Δ=%.2f",
                bitwise, resYaw, baseYaw + 6));

        // ============ v16.2 gates (D108-D111) ============

        // ---- 42a: bake LOCAL→GLOBAL axes keeps the shape ----
        List<List<BezierBaker.Node>> bl = new ArrayList<>();
        bl.add(nodes(new double[][]{ {0,0},{2,10},{5,-4},{8,0} }));
        bl.add(nodes(new double[][]{ {0,0},{3,16},{8,8} }));
        bl.add(nodes(new double[][]{ {0,0},{4,25},{6,-15},{8,0} }));
        Coaster bk = new Coaster();
        bk.v0 = 24;
        TrackPiece bkp = new TrackPiece(0, 21, 0, 0);
        bkp.sections.add(new GeoSection(bl, 24));
        bk.pieces.add(bkp);
        TrackCompiler.Result bkBefore = TrackCompiler.compile(bk, dt);
        boolean okB1 = bk.bakeAxes(0, 0, true, true, dt, 0.05, 0);
        LaneSection bkSec = (LaneSection) bk.soleSection();
        TrackCompiler.Result bkAfter = TrackCompiler.compile(bk, dt);
        double devB1 = trackDev(bkBefore, bkAfter);
        check("42a bake local→global (geo): shape kept, flags set",
            okB1 && bkSec.rollGlobal && bkSec.yawPitchGlobal
                && devB1 < 0.6,
            String.format("dev=%.3f m over %.0f m", devB1,
                bkBefore.samples().get(bkBefore.samples().size() - 1).s()));

        // ---- 42b: bake back GLOBAL→LOCAL — round trip stays close ----
        boolean okB2 = bk.bakeAxes(0, 0, false, false, dt, 0.05, 0);
        TrackCompiler.Result bkBack = TrackCompiler.compile(bk, dt);
        double devB2 = trackDev(bkBefore, bkBack);
        check("42b bake global→local round trip: shape kept",
            okB2 && !((LaneSection) bk.soleSection()).globalAxes()
                && devB2 < 1.2,
            String.format("dev=%.3f m", devB2));

        // ---- 42c: bake FORCE roll local→global keeps the shape ----
        List<List<BezierBaker.Node>> fb = new ArrayList<>();
        fb.add(nodes(new double[][]{ {0,1},{2,2.2},{5,0.4},{8,1} }));
        fb.add(nodes(new double[][]{ {0,0},{8,0} }));
        fb.add(nodes(new double[][]{ {0,0},{3,30},{5,-30},{8,0} }));
        Coaster fbk = Coaster.singleForce(fb, 24, 0, 21, 0);
        TrackCompiler.Result fbBefore = TrackCompiler.compile(fbk, dt);
        boolean okB3 = fbk.bakeAxes(0, 0, true, false, dt, 0.05, 0);
        TrackCompiler.Result fbAfter = TrackCompiler.compile(fbk, dt);
        double devB3 = trackDev(fbBefore, fbAfter);
        // v16.4 (D110): the sub-step feed-forward took this from
        // 0.408 m (projection lag) to 0.008 — hold the line
        check("42c bake force roll local→global: shape kept",
            okB3 && ((LaneSection) fbk.soleSection()).rollGlobal
                && devB3 < 0.05,
            String.format("dev=%.3f m", devB3));

        // ---- 42d: make-real RIGHT OF a conflicted section — the left
        // conflict and a pinned downstream conflict both survive ----
        // level chain (Gv=1 everywhere = constant speeds): A0 at 26
        // feeds pinned A/B/C designed at 15 — all three conflicted
        // (a run's FIRST section can never drift: Pass D references
        // its own vDesign, so the conflict needs an upstream cause)
        Coaster mr = new Coaster();
        mr.v0 = 26;
        TrackPiece mrp = new TrackPiece(0, 21, 0, 0);
        ForceSection A0 = new ForceSection(nodes4(1, 0, 0, 3), 26);
        ForceSection A = new ForceSection(nodes4(1, 0, 0, 3), 15);
        A.vPinned = true;        // deliberately kept conflict
        ForceSection B = new ForceSection(nodes4(1, 0, 0, 3), 15);
        B.vPinned = true;        // continues A's design profile, pinned
        ForceSection C = new ForceSection(nodes4(1, 0, 0, 3), 15);
        C.vPinned = true;
        mrp.sections.add(A0);
        mrp.sections.add(A);
        mrp.sections.add(B);
        mrp.sections.add(C);
        mr.pieces.add(mrp);
        TrackCompiler.Result mr0 = TrackCompiler.compile(mr, dt);
        double aDrift0 = spanDrift(mr0, 1);
        double cDrift0 = spanDrift(mr0, 3);
        boolean okMr = mr.makeGraphsReal(0, 2, dt, 0.1, 0);
        TrackCompiler.Result mr1 = TrackCompiler.compile(mr, dt);
        double aDrift1 = spanDrift(mr1, 1);
        double bDrift1 = spanDrift(mr1, 2);
        double cDrift1 = spanDrift(mr1, 3);
        boolean cStillPinned = mr.pieces.get(0).sections.get(3).vPinned();
        check("42d make-real right of a conflict: B real, A + C keep theirs",
            okMr && aDrift0 > 5 && aDrift1 > 5 && bDrift1 < 0.25
                && cStillPinned && cDrift0 > 5 && cDrift1 > 5,
            String.format("A %.2f→%.2f B→%.3f C %.2f→%.2f pinned=%b",
                aDrift0, aDrift1, bDrift1, cDrift0, cDrift1, cStillPinned));

        // ---- 42e: bake on a vertical-ish fixture must not blow up ----
        List<List<BezierBaker.Node>> vf = new ArrayList<>();
        vf.add(nodes(new double[][]{ {0,40},{3,40} }));   // hard pull-up
        vf.add(nodes(new double[][]{ {0,0},{3,0} }));
        vf.add(nodes(new double[][]{ {0,10},{3,10} }));
        Coaster vfk = new Coaster();
        vfk.v0 = 24;
        TrackPiece vfp = new TrackPiece(0, 60, 0, 0);
        vfp.sections.add(new GeoSection(vf, 24));
        vfk.pieces.add(vfp);
        boolean okV = vfk.bakeAxes(0, 0, true, true, dt, 0.05, 0);
        boolean finite = true;
        LaneSection vSec = (LaneSection) vfk.soleSection();
        for (List<BezierBaker.Node> lnv : vSec.lanes) {
            for (BezierBaker.Node nv : lnv) {
                if (!Double.isFinite(nv.val) || Math.abs(nv.val) > 3601) {
                    finite = false;
                }
            }
        }
        TrackCompiler.Result vr = TrackCompiler.compile(vfk, dt);
        finite &= Double.isFinite(
            vr.samples().get(vr.samples().size() - 1).s());
        check("42e vertical-loop bake: capped, finite, no crash",
            okV && finite, "");

        // ============ v16.5 gates (D112): influence orientation ====

        // ---- 45a: influence on a GLOBAL-ROLL force section - entry
        // is bitwise (fast path), discard restores bitwise ----
        List<List<BezierBaker.Node>> g5 = new ArrayList<>();
        g5.add(nodes(new double[][]{ {0,1},{2,1.8},{5,0.8},{8,1} }));
        g5.add(nodes(new double[][]{ {0,0},{3,0.5},{8,0.2} }));
        g5.add(nodes(new double[][]{ {0,0},{4,20},{6,-10},{8,0} }));
        Coaster i5 = Coaster.singleForce(g5, 24, 0, 21, 0);
        LaneSection i5s = (LaneSection) i5.soleSection();
        i5s.rollGlobal = true;
        TrackCompiler.Result i5b = TrackCompiler.compile(i5, dt);
        Coaster.enterInfluence(i5s, 8);
        TrackCompiler.Result i5a = TrackCompiler.compile(i5, dt);
        boolean bit5 = i5b.samples().size() == i5a.samples().size();
        for (int i = 0; bit5 && i < i5b.samples().size(); i++) {
            bit5 = i5b.samples().get(i).pos()
                .sub(i5a.samples().get(i).pos()).length() == 0;
        }
        boolean defMirror = i5s.inflRollGlobal && !i5s.inflYawPitchGlobal;
        Coaster.discardInfluence(i5s);
        TrackCompiler.Result i5d = TrackCompiler.compile(i5, dt);
        boolean bit5d = i5d.samples().size() == i5b.samples().size();
        for (int i = 0; bit5d && i < i5b.samples().size(); i++) {
            bit5d = i5b.samples().get(i).pos()
                .sub(i5d.samples().get(i).pos()).length() == 0;
        }
        check("45a global-roll influence: entry + discard bitwise",
            bit5 && bit5d && defMirror,
            String.format("entry=%b discard=%b defaults=%b",
                bit5, bit5d, defMirror));

        // ---- 45b: vAuthored INERT (v19.7, D236 — the one-pass
        // hybrid retired the two-pass ref frame): a hand-edited
        // divergent pair compiles at the OPERATING speed alone —
        // zero deltas at vDesign 28 == the plain section at 28 ----
        Coaster.enterInfluence(i5s, 8);
        i5s.vDesign = 28;   // FIELD write: hand-edited-file pair split
        TrackCompiler.Result i5v = TrackCompiler.compile(i5, dt);
        Coaster.discardInfluence(i5s);   // vDesign := vAuthored (24)
        i5s.vDesign = 28;
        TrackCompiler.Result i5p = TrackCompiler.compile(i5, dt);
        double dev5 = trackDev(i5p, i5v);
        check("45b divergent zero-delta pair == plain at vDesign (D236)",
            dev5 < 1e-12,
            String.format("dev=%.2e m over %.0f m", dev5,
                i5v.samples().get(i5v.samples().size() - 1).s()));
        i5s.vDesign = 24;

        // ---- 45c: GLOBAL yaw/pitch influence - a +10 deg/s heading
        // delta on a CLIMBING force section turns exactly 60 deg of
        // heading (body-yaw semantics turn less: the climb's
        // cos(elevation) projection) ----
        List<List<BezierBaker.Node>> c5 = new ArrayList<>();
        c5.add(nodes(new double[][]{ {0,1.35},{2,1.35},{2.5,1.0},{6,1.0} }));
        c5.add(nodes(new double[][]{ {0,0},{6,0} }));
        c5.add(nodes(new double[][]{ {0,0},{6,0} }));
        Coaster h5 = Coaster.singleForce(c5, 20, 0, 21, 0);
        LaneSection h5s = (LaneSection) h5.soleSection();
        Coaster.enterInfluence(h5s, 6);
        h5s.inflYawPitchGlobal = true;
        for (BezierBaker.Node nd : h5s.deltaLanes.get(1)) nd.val = 10;
        TrackCompiler.Result h5r = TrackCompiler.compile(h5, dt);
        FVDIntegrator.Sample hEnd = h5r.samples()
            .get(h5r.samples().size() - 1);
        double hTurn5 = Math.abs(Math.toDegrees(
            Math.atan2(-hEnd.T().z(), hEnd.T().x())));
        h5s.inflYawPitchGlobal = false;   // same delta about BODY yaw
        TrackCompiler.Result h5l = TrackCompiler.compile(h5, dt);
        FVDIntegrator.Sample hEndL = h5l.samples()
            .get(h5l.samples().size() - 1);
        double hTurn5L = Math.abs(Math.toDegrees(
            Math.atan2(-hEndL.T().z(), hEndL.T().x())));
        check("45c global-yp influence: heading delta lands exactly",
            Math.abs(hTurn5 - 60) < 0.6
                && Math.abs(hTurn5 - hTurn5L) > 1.5,
            String.format("global=%.2f deg local=%.2f deg (60 expected)",
                hTurn5, hTurn5L));

        // ---- 45d: FVDD3 round trip carries the influence flags ----
        h5s.inflYawPitchGlobal = true;
        StringWriter iw1 = new StringWriter();
        DesignIO.save(h5, DesignIO.Cosmetics.defaults(), new PrintWriter(iw1));
        DesignIO.LoadResult ilr = DesignIO.load(
            new BufferedReader(new StringReader(iw1.toString())));
        LaneSection ils = (LaneSection) ilr.coaster().soleSection();
        StringWriter iw2 = new StringWriter();
        DesignIO.save(ilr.coaster(), ilr.cosmetics(), new PrintWriter(iw2));
        check("45d FVDD3 round trip: influence axis flags",
            ils.influenced() && !ils.inflRollGlobal
                && ils.inflYawPitchGlobal
                && iw1.toString().equals(iw2.toString()),
            String.format("rollG=%b ypG=%b byteEq=%b",
                ils.inflRollGlobal, ils.inflYawPitchGlobal,
                iw1.toString().equals(iw2.toString())));

        // ---- 45e (D113): MID-CHAIN convert keeps the section's full
        // profile — a continuation span starts one dt after its entry
        // (the dropped boundary sample, D84) and span-only extraction
        // re-fit every mid-chain section one step SHORT (~v*dt of
        // arc: the user's standing "converges slightly off"). The
        // overbanked-turn repro fell 0.20 m -> 3-7 mm via withEntry.
        List<List<BezierBaker.Node>> lead6 = new ArrayList<>();
        lead6.add(nodes(new double[][]{ {0,1},{2,1} }));
        lead6.add(nodes(new double[][]{ {0,0},{2,0} }));
        lead6.add(nodes(new double[][]{ {0,0},{2,0} }));
        List<List<BezierBaker.Node>> ob6 = new ArrayList<>();
        ob6.add(nodes(new double[][]{ {0,1},{1.5,2.2},{5.5,2.2},{7.5,1} }));
        ob6.add(nodes(new double[][]{ {0,0},{7.5,0} }));
        ob6.add(nodes(new double[][]{
            {0,0},{1,35},{2.5,35},{3.5,0},{4,0},{5,-35},{6.5,-35},{7.5,0} }));
        Coaster m6 = new Coaster();
        m6.v0 = 20;
        TrackPiece m6p = new TrackPiece(0, 21, 0, 0);
        m6p.sections.add(new ForceSection(lead6, 20));
        m6p.sections.add(new ForceSection(ob6, 20));
        m6.pieces.add(m6p);
        TrackCompiler.Result m6b = TrackCompiler.compile(m6, dt);
        boolean ok6 = m6.convertWholeSection(0, 1, true, dt, 0.1, 0);
        TrackCompiler.Result m6a = TrackCompiler.compile(m6, dt);
        double dev6 = trackDev(m6b, m6a);
        double arc6 = Math.abs(
            m6a.samples().get(m6a.samples().size() - 1).s()
            - m6b.samples().get(m6b.samples().size() - 1).s());
        check("45e mid-chain convert keeps the entry step (D113)",
            ok6 && dev6 < 0.02 && arc6 < 0.02,
            String.format("dev=%.4f m arcDelta=%.4f m (was 0.20/0.20)",
                dev6, arc6));

        // ============ v16.6 gates (D115): per-meter rate units ======

        // ---- 46a: TIME-domain per-meter geo canary — a constant
        // pitch of c deg/m integrates to EXACTLY c x arc of elevation
        // whatever the speed profile does ----
        List<List<BezierBaker.Node>> pm1 = new ArrayList<>();
        pm1.add(nodes(new double[][]{ {0,0.28},{5,0.28} }));
        pm1.add(nodes(new double[][]{ {0,0},{5,0} }));
        pm1.add(nodes(new double[][]{ {0,0},{5,0} }));
        Coaster pmc = new Coaster();
        pmc.v0 = 26;
        TrackPiece pmp = new TrackPiece(0, 21, 0, 0);
        GeoSection pmg = new GeoSection(pm1, 26);
        pmg.ratePerMeter = true;
        pmp.sections.add(pmg);
        pmc.pieces.add(pmp);
        TrackCompiler.Result pmr = TrackCompiler.compile(pmc, dt);
        FVDIntegrator.Sample pmE = pmr.samples()
            .get(pmr.samples().size() - 1);
        double elevPm = Math.toDegrees(Math.asin(pmE.T().y()));
        double want = 0.28 * pmE.s();
        check("46a per-meter pitch: elevation == rate x arc",
            Math.abs(elevPm - want) < 0.02,
            String.format("elev=%.3f want=%.3f over %.1f m",
                elevPm, want, pmE.s()));

        // ---- 46b: DISTANCE-domain per-meter geo shape is design-
        // speed INVARIANT (the s/c property, now for lanes) ----
        List<List<BezierBaker.Node>> pm2 = new ArrayList<>();
        pm2.add(nodes(new double[][]{ {0,0.2},{40,0.3},{100,-0.2} }));
        pm2.add(nodes(new double[][]{ {0,0.4},{100,0.1} }));
        pm2.add(nodes(new double[][]{ {0,0.5},{60,-0.4},{100,0} }));
        Coaster pv1 = new Coaster();
        pv1.v0 = 18;
        TrackPiece pv1p = new TrackPiece(0, 21, 0, 0);
        GeoSection pv1g = new GeoSection(pm2, 18);
        pv1g.ratePerMeter = true;
        pv1g.domain(Section.Domain.DISTANCE);
        pv1p.sections.add(pv1g);
        pv1.pieces.add(pv1p);
        TrackCompiler.Result pv1r = TrackCompiler.compile(pv1, dt);
        pv1g.vDesign = 30;
        pv1.v0 = 30;
        TrackCompiler.Result pv2r = TrackCompiler.compile(pv1, dt);
        double pvDev = trackDev(pv1r, pv2r);
        check("46b dist+per-meter geo: shape speed-invariant",
            pvDev < 0.005,
            String.format("dev=%.5f m (18 vs 30 m/s)", pvDev));

        // ---- 46c: force per-meter roll on straight track — bank ==
        // rate x arc ----
        List<List<BezierBaker.Node>> pm3 = new ArrayList<>();
        pm3.add(nodes(new double[][]{ {0,1},{6,1} }));
        pm3.add(nodes(new double[][]{ {0,0},{6,0} }));
        pm3.add(nodes(new double[][]{ {0,0.5},{6,0.5} }));
        Coaster pf = Coaster.singleForce(pm3, 20, 0, 21, 0);
        ((LaneSection) pf.soleSection()).ratePerMeter = true;
        // GLOBAL roll: bank-vs-horizon == the lane's ARC integral by
        // construction (local roll would drift by the transport term
        // once Gv=1 pitches the banked track)
        ((LaneSection) pf.soleSection()).rollGlobal = true;
        TrackCompiler.Result pfr = TrackCompiler.compile(pf, dt);
        FVDIntegrator.Sample pfE = pfr.samples()
            .get(pfr.samples().size() - 1);
        double bank = Math.toDegrees(Math.atan2(
            -pfE.U().x() * pfE.T().z() + pfE.U().z() * pfE.T().x(),
            pfE.U().y()));
        double wantB = 0.5 * pfE.s();
        check("46c force per-meter GLOBAL roll: bank == rate x arc",
            Math.abs(Math.abs(bank) - wantB) < 0.05,
            String.format("bank=%.3f want=%.3f over %.1f m",
                Math.abs(bank), wantB, pfE.s()));

        // ---- 46d: convert TO per-meter keeps the shape; flag set;
        // FVDD3 round trip byte-identical ----
        Coaster obc6 = Coaster.singleForce(g5, 24, 0, 21, 0);
        TrackCompiler.Result ob6b = TrackCompiler.compile(obc6, dt);
        LaneSection pmConv = Coaster.convertToLanes(ob6b.samples(), 24,
            true, dt, 0.05, 0, Section.Domain.TIME, false, false, true);
        Coaster ob6c = new Coaster();
        ob6c.v0 = 24;
        TrackPiece ob6p = new TrackPiece(0, 21, 0, 0);
        ob6p.sections.add(pmConv);
        ob6c.pieces.add(ob6p);
        TrackCompiler.Result ob6a = TrackCompiler.compile(ob6c, dt);
        double pmDev = trackDev(ob6b, ob6a);
        StringWriter pw1 = new StringWriter();
        DesignIO.save(ob6c, DesignIO.Cosmetics.defaults(), new PrintWriter(pw1));
        DesignIO.LoadResult plr = DesignIO.load(
            new BufferedReader(new StringReader(pw1.toString())));
        StringWriter pw2 = new StringWriter();
        DesignIO.save(plr.coaster(), plr.cosmetics(), new PrintWriter(pw2));
        boolean pmFlag = ((LaneSection) plr.coaster().soleSection())
            .ratePerMeter;
        check("46d convert to per-meter: shape kept + IO round trip",
            pmConv.ratePerMeter && pmDev < 0.05 && pmFlag
                && pw1.toString().equals(pw2.toString()),
            String.format("dev=%.4f m flag=%b byteEq=%b", pmDev, pmFlag,
                pw1.toString().equals(pw2.toString())));

        // ---- 46e (D116): EVERY-SETTING convert in ONE call — a
        // plain force/time/local/°s section to geo/DISTANCE/global-
        // roll/per-meter, shape held. The fixture must stay clear of
        // vertical (g5 crests at T.y ±0.95 — global axes have no
        // heading/bank there, the documented cap since v16.2) ----
        List<List<BezierBaker.Node>> e6l = new ArrayList<>();
        e6l.add(nodes(new double[][]{ {0,1},{2,1.35},{5,0.85},{8,1} }));
        e6l.add(nodes(new double[][]{ {0,0},{3,0.4},{8,0.15} }));
        e6l.add(nodes(new double[][]{ {0,0},{4,14},{6,-8},{8,0} }));
        Coaster ec6 = Coaster.singleForce(e6l, 24, 0, 21, 0);
        TrackCompiler.Result ec6b = TrackCompiler.compile(ec6, dt);
        boolean okE6 = ec6.convertWholeSection(0, 0, true, dt, 0.05, 0,
            Section.Domain.DISTANCE, true, true, true);
        LaneSection ec6s = (LaneSection) ec6.soleSection();
        TrackCompiler.Result ec6a = TrackCompiler.compile(ec6, dt);
        double ec6d = trackDev(ec6b, ec6a);
        check("46e all-settings convert: one call, shape held",
            okE6 && ec6s instanceof GeoSection
                && ec6s.domain() == Section.Domain.DISTANCE
                && ec6s.rollGlobal && ec6s.yawPitchGlobal
                && ec6s.ratePerMeter && ec6d < 0.15,
            String.format("dev=%.4f m flags=%b/%b/%b dom=%s", ec6d,
                ec6s.rollGlobal, ec6s.yawPitchGlobal, ec6s.ratePerMeter,
                ec6s.domain()));

        // ---- 47a (D117): PER-METER influence — entry bitwise, a
        // °/m heading delta lands rate × arc of heading ----
        List<List<BezierBaker.Node>> i7 = new ArrayList<>();
        i7.add(nodes(new double[][]{ {0,1},{6,1} }));
        i7.add(nodes(new double[][]{ {0,0},{6,0} }));
        i7.add(nodes(new double[][]{ {0,0},{6,0} }));
        Coaster c7 = Coaster.singleForce(i7, 20, 0, 21, 0);
        LaneSection s7 = (LaneSection) c7.soleSection();
        s7.ratePerMeter = true;
        TrackCompiler.Result r7b = TrackCompiler.compile(c7, dt);
        Coaster.enterInfluence(s7, 6);
        TrackCompiler.Result r7a = TrackCompiler.compile(c7, dt);
        boolean bit7 = r7b.samples().size() == r7a.samples().size();
        for (int i = 0; bit7 && i < r7b.samples().size(); i++) {
            bit7 = r7b.samples().get(i).pos()
                .sub(r7a.samples().get(i).pos()).length() == 0;
        }
        for (BezierBaker.Node nd : s7.deltaLanes.get(1)) nd.val = 0.5;
        TrackCompiler.Result r7d = TrackCompiler.compile(c7, dt);
        FVDIntegrator.Sample e7 = r7d.samples()
            .get(r7d.samples().size() - 1);
        double head7 = Math.abs(Math.toDegrees(
            Math.atan2(-e7.T().z(), e7.T().x())));
        double want7 = 0.5 * e7.s();
        check("47a per-meter influence: bitwise entry, delta = rate x arc",
            bit7 && Math.abs(head7 - want7) < 0.25,
            String.format("bitwise=%b head=%.2f want=%.2f", bit7,
                head7, want7));

        // ---- 47b (D118): ABSOLUTE roll — the lane value IS the bank:
        // a ramp to 50 deg then hold reads exactly 50 deg of bank ----
        List<List<BezierBaker.Node>> ab7 = new ArrayList<>();
        ab7.add(nodes(new double[][]{ {0,1},{6,1} }));
        ab7.add(nodes(new double[][]{ {0,0},{6,0} }));
        ab7.add(nodes(new double[][]{ {0,0},{2,50},{6,50} }));
        Coaster ac7 = Coaster.singleForce(ab7, 20, 0, 21, 0);
        LaneSection as7 = (LaneSection) ac7.soleSection();
        as7.rollGlobal = true;
        as7.rollAbsolute = true;
        TrackCompiler.Result ar7 = TrackCompiler.compile(ac7, dt);
        FVDIntegrator.Sample ae7 = ar7.samples()
            .get(ar7.samples().size() - 1);
        double bank7 = Math.toDegrees(bankOf(ae7));
        int mid7 = (int) (4.0 / dt);
        FVDIntegrator.Sample am7 = ar7.samples().get(
            Math.min(mid7, ar7.samples().size() - 1));
        double bankM7 = Math.toDegrees(bankOf(am7));
        check("47b absolute roll: lane value == bank (ramp + hold)",
            Math.abs(bank7 - 50) < 0.05 && Math.abs(bankM7 - 50) < 0.05,
            String.format("end=%.3f mid=%.3f (50 expected)",
                bank7, bankM7));

        // ---- 47c (D118): convert a rate section TO absolute roll —
        // shape held, flags forced sane, FVDD3 round trip ----
        Coaster cc7 = Coaster.singleForce(e6l, 24, 0, 21, 0);
        TrackCompiler.Result cb7 = TrackCompiler.compile(cc7, dt);
        boolean ok7 = cc7.convertWholeSection(0, 0, false, dt, 0.05, 0,
            Section.Domain.TIME, false, false, false, true);
        LaneSection cs7 = (LaneSection) cc7.soleSection();
        TrackCompiler.Result ca7 = TrackCompiler.compile(cc7, dt);
        double cd7 = trackDev(cb7, ca7);
        StringWriter cw1 = new StringWriter();
        DesignIO.save(cc7, DesignIO.Cosmetics.defaults(), new PrintWriter(cw1));
        DesignIO.LoadResult clr7 = DesignIO.load(
            new BufferedReader(new StringReader(cw1.toString())));
        StringWriter cw2 = new StringWriter();
        DesignIO.save(clr7.coaster(), clr7.cosmetics(), new PrintWriter(cw2));
        boolean cf7 = ((LaneSection) clr7.coaster().soleSection())
            .rollAbsolute;
        check("47c convert to absolute roll: shape + flags + IO",
            ok7 && cs7.rollAbsolute && cs7.rollGlobal && cd7 < 0.05
                && cf7 && cw1.toString().equals(cw2.toString()),
            String.format("dev=%.4f m absFlag=%b globForced=%b byteEq=%b",
                cd7, cs7.rollAbsolute, cs7.rollGlobal,
                cw1.toString().equals(cw2.toString())));

        // ---- 47d (D121): ABSOLUTE roll consumption is SMOOTH — the
        // baked polyline's staircase derivative + the ~0.1 parameter
        // walk's linear grid fill made the resulting roll rate step/
        // wobble per bake cell (was max jump 3.56 deg/s geo, 2.59
        // force; C1 bankHermite + exactRoll knots: ~0.9 = the true
        // rate curvature x dt, same as an equivalent rate lane).
        // Also: bank tracks the analytic smoothstep to sub-0.02 deg.
        List<List<BezierBaker.Node>> d7g = new ArrayList<>();
        d7g.add(nodes(new double[][]{ {0,0},{6,0} }));
        d7g.add(nodes(new double[][]{ {0,0},{6,0} }));
        d7g.add(nodes(new double[][]{ {0,0},{4,200},{6,200} }));
        Coaster d7c = new Coaster();
        d7c.v0 = 20;
        TrackPiece d7p = new TrackPiece(0, 21, 0, 0);
        GeoSection d7s = new GeoSection(d7g, 20);
        d7s.rollGlobal = true;
        d7s.rollAbsolute = true;
        d7p.sections.add(d7s);
        d7c.pieces.add(d7p);
        TrackCompiler.Result d7r = TrackCompiler.compile(d7c, dt);
        double d7jump = 0, d7bank = 0;
        for (int i = 6; i < Math.min(d7r.rates().length - 10, 580); i++) {
            d7jump = Math.max(d7jump, Math.abs(
                d7r.rates()[i][3] - d7r.rates()[i - 1][3]));
            double t7 = d7r.samples().get(i).t();
            double u7 = Math.min(1, t7 / 4);
            double bt7 = 200 * u7 * u7 * (3 - 2 * u7);
            double bm7 = Math.toDegrees(bankOf(d7r.samples().get(i)));
            // compare mod 360: bankOf is principal, the ramp passes 180
            double be7 = bm7 - bt7;
            be7 -= 360 * Math.round(be7 / 360);
            d7bank = Math.max(d7bank, Math.abs(be7));
        }
        check("47d absolute roll consumption is C1 (no staircase)",
            d7jump < 1.5 && d7bank < 0.05,
            String.format("max rate jump=%.3f deg/s (was 3.56), "
                + "bank err=%.4f deg (was 0.107 on the 60-ramp)",
                d7jump, d7bank));

        System.out.println(fails == 0 ? "\nALL DOMAIN/AXIS GATES PASS"
            : "\n" + fails + " GATE(S) FAILED");
        if (fails > 0) System.exit(1);
    }

    /** Two-node lanes over [0,dur]: {Gv/pitch, Gl/yaw, roll}. */
    static List<List<BezierBaker.Node>> nodes4(double a, double b, double c,
                                               double dur) {
        List<List<BezierBaker.Node>> l = new ArrayList<>();
        l.add(nodes(new double[][]{ {0,a},{dur,a} }));
        l.add(nodes(new double[][]{ {0,b},{dur,b} }));
        l.add(nodes(new double[][]{ {0,c},{dur,c} }));
        return l;
    }

    static Coaster geoCoaster(List<List<BezierBaker.Node>> lanes, double v,
                              Quat baseOrient) {
        Coaster c = new Coaster();
        c.v0 = v;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.baseOrient = baseOrient;
        p.sections.add(new GeoSection(lanes, v));
        c.pieces.add(p);
        return c;
    }

    /** Signed bank vs horizon of a sample (rad), NaN when vertical. */
    static double bankOf(FVDIntegrator.Sample sm) {
        Vec3 T = sm.T();
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < 1e-6) return Double.NaN;
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.atan2(ul.cross(sm.U()).dot(T), sm.U().dot(ul));
    }

    /** Max |trueV − design v| over span k. */
    static double spanDrift(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        double worst = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    /** Max |trueFelt − design felt| (Gv) over span k. */
    static double feltSplit(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        double worst = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            worst = Math.max(worst,
                Math.abs(r.trueFelt()[i][0] - r.samples().get(i).Gv()));
        }
        return worst;
    }

    /** Round-trip domain refit at the given densities; max deviation
     *  from the pristine compile. */
    static double domainRoundDev(double spacingM, double spacingS)
            throws Exception {
        Coaster c = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        TrackCompiler.Result before = TrackCompiler.compile(c, dt);
        if (!c.convertDomain(0, 0, dt, spacingM, 0)) return 1e9;
        if (!c.convertDomain(0, 0, dt, spacingS, 0)) return 1e9;
        TrackCompiler.Result afterR = TrackCompiler.compile(c, dt);
        return trackDev(before, afterR);
    }

    static double trackDev(TrackCompiler.Result a, TrackCompiler.Result b) {
        double sEndA = a.samples().get(a.samples().size() - 1).s();
        double sEndB = b.samples().get(b.samples().size() - 1).s();
        double worst = Math.abs(sEndA - sEndB);
        for (int k = 0; k <= 200; k++) {
            double f = k / 200.0;
            worst = Math.max(worst, posAtArc(a.samples(), f * sEndA)
                .sub(posAtArc(b.samples(), f * sEndB)).length());
        }
        return worst;
    }

    static double endDev(TrackCompiler.Result a, TrackCompiler.Result b) {
        return a.samples().get(a.samples().size() - 1).pos()
            .sub(b.samples().get(b.samples().size() - 1).pos()).length();
    }

    static Vec3 posAtArc(List<FVDIntegrator.Sample> s, double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        double ds = s.get(hi).s() - s.get(lo).s();
        double u = ds < 1e-12 ? 0
            : Math.max(0, Math.min(1, (arc - s.get(lo).s()) / ds));
        return s.get(lo).pos().add(
            s.get(hi).pos().sub(s.get(lo).pos()).scale(u));
    }
}
