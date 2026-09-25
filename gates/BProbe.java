package uvd;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/**
 * Repro probe for the user's "B/Shift+B doesn't line the real graphs up
 * at the force->geo seam" report (bshort.fvd). Replicates GraphEditor's
 * matchBoundary/boundaryState math verbatim for the geo section's FIRST
 * node (activeSection=1, startEdge, neighbor = force section 0 with a
 * launch control), applies it, recompiles, and measures the true-curve
 * discontinuity at the seam.
 */
public class BProbe {

    static final double DT = 0.01;
    static final double G0 = FVDIntegrator.G0;
    static final double DRIFT_EPS = 0.2;

    static Coaster c;
    static TrackCompiler.Result r;

    public static void main(String[] args) throws Exception {
        try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
            c = DesignIO.load(in).coaster();
        }
        recompile();

        GeoSection geo = (GeoSection) c.pieces.get(0).sections.get(1);
        LaneSection frc = (LaneSection) c.pieces.get(0).sections.get(0);
        System.out.printf(Locale.US,
            "flags geo: dom=%s ypG=%b rollG=%b rpm=%b abs=%b vDes=%.4f%n",
            geo.domain(), geo.yawPitchGlobal, geo.rollGlobal,
            geo.ratePerMeter, geo.rollAbsolute, geo.vDesign);
        System.out.printf(Locale.US,
            "flags frc: dom=%s ypG=%b rollG=%b rpm=%b vDes=%.4f%n",
            frc.domain(), frc.yawPitchGlobal, frc.rollGlobal,
            frc.ratePerMeter, frc.vDesign);

        seamReport("as loaded");
        detail();

        // ---- press Shift+B (all lanes) up to 3 times, report each ----
        for (int press = 1; press <= 3; press++) {
            refreezeSeamSpeed();
            for (int L = 0; L < 3; L++) {
                double[] vs = boundaryState(L);
                if (vs == null) {
                    System.out.println("lane " + L + ": TARGET NULL");
                    continue;
                }
                List<BezierBaker.Node> ln =
                    ((LaneSection) c.pieces.get(0).sections.get(1)).lanes.get(L);
                BezierBaker.Node n0 = ln.get(0);
                System.out.printf(Locale.US,
                    "press %d lane %d: target val=%.6f slope=%.6f "
                    + "(was val=%.6f slope=%.6f)%n",
                    press, L, vs[0], vs[1], n0.val,
                    n0.outDv / BezierBaker.effOutDt(n0, ln.get(1)));
                n0.val = vs[0];
                n0.outDv = vs[1] * BezierBaker.effOutDt(n0, ln.get(1));
            }
            recompile();
            seamReport("after press " + press);
        }
    }

    /** Per-sample truth around the seam: measured elevation/heading
     *  rates straight off the frames (central difference of
     *  asin(T.y) / atan2) vs the authored lane expectation val(s)·v. */
    static void detail() {
        TrackCompiler.Span sp1 = r.spans().get(1);
        LaneSection geo = (LaneSection) c.pieces.get(0).sections.get(1);
        List<FVDIntegrator.Sample> sm = r.samples();
        System.out.println("\n  i    t      sLoc     v      T.y    "
            + "elev'fd  head'fd  elev'auth  head'auth   elev'tR  head'tR");
        for (int i = sp1.i0() - 3; i < sp1.i0() + 18; i++) {
            FVDIntegrator.Sample a = sm.get(i - 1), b = sm.get(i + 1),
                s0 = sm.get(i);
            double dt2 = b.t() - a.t();
            double efd = Math.toDegrees(
                (Math.asin(clampU(b.T().y())) - Math.asin(clampU(a.T().y())))
                / dt2);
            double hA = Math.atan2(-a.T().z(), a.T().x());
            double hB = Math.atan2(-b.T().z(), b.T().x());
            double dh = hB - hA;
            if (dh > Math.PI) dh -= 2 * Math.PI;
            if (dh < -Math.PI) dh += 2 * Math.PI;
            double hfd = Math.toDegrees(dh / dt2);
            double sLoc = s0.s() - sp1.s0();
            double ea = Double.NaN, ha = Double.NaN;
            if (i >= sp1.i0()) {
                ea = authAt(geo.lanes.get(0), sLoc) * s0.v();
                ha = authAt(geo.lanes.get(1), sLoc) * s0.v();
            }
            double[] wr = Coaster.worldRates(s0, r.trueRates()[i][1],
                r.trueRates()[i][2], r.trueRates()[i][3]);
            System.out.printf(Locale.US,
                "%5d %6.2f %7.3f %6.2f %6.3f %8.3f %8.3f %9.3f %9.3f "
                + "%8.3f %8.3f%n",
                i, s0.t(), sLoc, s0.v(), s0.T().y(), efd, hfd, ea, ha,
                wr[0], wr[1]);
        }
    }

    static double clampU(double x) {
        return Math.max(-1, Math.min(1, x));
    }

    static double authAt(List<BezierBaker.Node> lane, double x) {
        List<double[]> poly = BezierBaker.polyline(lane);
        if (x <= poly.get(0)[0]) return poly.get(0)[1];
        for (int i = 1; i < poly.size(); i++) {
            if (poly.get(i)[0] >= x) {
                double[] p = poly.get(i - 1), q = poly.get(i);
                double u = q[0] - p[0] < 1e-12 ? 0
                    : (x - p[0]) / (q[0] - p[0]);
                return p[1] + u * (q[1] - p[1]);
            }
        }
        return poly.get(poly.size() - 1)[1];
    }

    static void recompile() {
        r = TrackCompiler.compile(c, DT);
        for (int pass = 0; pass < 3; pass++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
    }

    // ---------------- seam diagnostics ----------------

    static void seamReport(String tag) {
        TrackCompiler.Span sp0 = r.spans().get(0), sp1 = r.spans().get(1);
        List<FVDIntegrator.Sample> sm = r.samples();
        int ip = Math.min(sp0.i1(), sm.size() - 1);
        Section geo = c.pieces.get(0).sections.get(1);
        System.out.printf(Locale.US,
            "%n=== %s ===%nseam ip=%d t=%.4f vDes(seam)=%.4f vTrue=%.4f "
            + "geo.vDesign=%.4f%n",
            tag, ip, sm.get(ip).t(), sm.get(ip).v(), r.trueV()[ip],
            geo.vDesign());
        // extrapolate the TRUE rates from each side to the seam time
        // (3 samples clear, slope over 5 — the display's own trim scale)
        double tB = sp1.t0();
        for (int comp = 1; comp <= 3; comp++) {
            double right = extrap(r.trueRates(), idxAt(tB + 3 * DT),
                idxAt(tB + 3 * DT) + 5, tB, comp);
            double leftC = extrap(r.trueRates(), idxAt(tB - 3 * DT) - 5,
                idxAt(tB - 3 * DT), tB, comp);
            System.out.printf(Locale.US,
                "true %s: left=%.4f right=%.4f  JUMP=%.4f deg/s%n",
                comp == 1 ? "pitch" : comp == 2 ? "yaw  " : "roll ",
                leftC, right, right - leftC);
        }
        // felt
        double[] fl = feltExtrap(idxAt(tB - 3 * DT) - 5, idxAt(tB - 3 * DT), tB);
        double[] fr = feltExtrap(idxAt(tB + 3 * DT), idxAt(tB + 3 * DT) + 5, tB);
        System.out.printf(Locale.US,
            "true felt Gv: left=%.4f right=%.4f JUMP=%.4f | Gl: left=%.4f "
            + "right=%.4f JUMP=%.4f%n",
            fl[0], fr[0], fr[0] - fl[0], fl[1], fr[1], fr[1] - fl[1]);
        // world rates (what a global-axis geo lane displays), from TRUE rates
        for (int L = 0; L < 2; L++) {
            double wl = worldExtrap(idxAt(tB - 3 * DT) - 5,
                idxAt(tB - 3 * DT), tB, L);
            double wr = worldExtrap(idxAt(tB + 3 * DT),
                idxAt(tB + 3 * DT) + 5, tB, L);
            System.out.printf(Locale.US,
                "world %s: left=%.4f right=%.4f JUMP=%.4f deg/s%n",
                L == 0 ? "elev'" : "head'", wl, wr, wr - wl);
        }
    }

    static double extrap(double[][] rr, int i0, int i1, double tB, int comp) {
        i0 = Math.max(0, i0); i1 = Math.min(rr.length - 1, i1);
        double span = rr[i1][0] - rr[i0][0];
        double slope = span < 1e-9 ? 0 : (rr[i1][comp] - rr[i0][comp]) / span;
        return rr[i1][comp] + slope * (tB - rr[i1][0]);
    }

    static double[] feltExtrap(int i0, int i1, double tB) {
        double[][] tf = r.trueFelt();
        i0 = Math.max(0, i0); i1 = Math.min(tf.length - 1, i1);
        List<FVDIntegrator.Sample> sm = r.samples();
        double span = sm.get(i1).t() - sm.get(i0).t();
        double[] out = new double[2];
        for (int k = 0; k < 2; k++) {
            double slope = span < 1e-9 ? 0
                : (tf[i1][k] - tf[i0][k]) / span;
            out[k] = tf[i1][k] + slope * (tB - sm.get(i1).t());
        }
        return out;
    }

    static double worldAt(int i, int L) {
        double[] wr = Coaster.worldRates(r.samples().get(i),
            r.trueRates()[i][1], r.trueRates()[i][2], r.trueRates()[i][3]);
        return wr[L];
    }

    static double worldExtrap(int i0, int i1, double tB, int L) {
        i0 = Math.max(0, i0);
        i1 = Math.min(r.trueRates().length - 1, i1);
        double w0 = worldAt(i0, L), w1 = worldAt(i1, L);
        double span = r.trueRates()[i1][0] - r.trueRates()[i0][0];
        double slope = span < 1e-9 ? 0 : (w1 - w0) / span;
        return w1 + slope * (tB - r.trueRates()[i1][0]);
    }

    static int idxAt(double t) {
        double[][] rr = r.rates();
        int lo = 0, hi = rr.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (rr[mid][0] <= t) lo = mid; else hi = mid;
        }
        return t - rr[lo][0] <= rr[hi][0] - t ? lo : hi;
    }

    // ---------------- GraphEditor replicas (activeSection=1, startEdge) ----

    static void refreezeSeamSpeed() {
        TrackCompiler.Span upSpan = r.spans().get(0);
        Section down = c.pieces.get(0).sections.get(1);
        if (down.vPinned()) return;
        List<FVDIntegrator.Sample> sm = r.samples();
        int i1 = Math.min(upSpan.i1(), sm.size() - 1);
        double v = sm.get(i1).v();
        double vTrue = r.trueV()[i1];
        if (Math.abs(vTrue - v) > DRIFT_EPS
                && Math.abs(down.vDesign() - vTrue)
                   < Math.abs(down.vDesign() - v)) {
            v = vTrue;
        }
        v = Math.max(1, Math.min(100, v));
        if (Math.abs(v - down.vDesign()) > 1e-9) down.vDesign(v);
    }

    /** boundaryState(k=0, atStart=false, laneIdx) for the geo active
     *  section — wrapper + workhorse, verbatim. */
    static double[] boundaryState(int laneIdx) {
        double[] vs = boundaryStatePerSec(laneIdx);
        if (vs == null) return null;
        LaneSection act = (LaneSection) c.pieces.get(0).sections.get(1);
        // laneRatePM(geo, L): geo + ratePerMeter -> all three lanes PM
        if (act.ratePerMeter) {
            double v = Math.max(0.5, act.vDesign());   // seamDesignV(false)
            double aT = -G0 * seamTy();
            double vm = vs[0] / v;
            double sl = (vs[1] - vm * (aT / v)) / v;   // activeDistNative
            return new double[]{ vm, sl };
        }
        return vs;
    }

    static double seamTy() {
        TrackCompiler.Span act = r.spans().get(1);
        int ib = Math.min(act.i0(), r.samples().size() - 1);
        return r.samples().get(ib).T().y();
    }

    static double[] boundaryStatePerSec(int laneIdx) {
        LaneSection actLs = (LaneSection) c.pieces.get(0).sections.get(1);
        LaneSection nb = (LaneSection) c.pieces.get(0).sections.get(0);
        List<FVDIntegrator.Sample> sm = r.samples();

        // real-split detection (atStart=false)
        TrackCompiler.Span seamSp0 = r.spans().get(0);
        int ip = Math.min(seamSp0.i1(), sm.size() - 1);
        double vDesSeam = sm.get(ip).v();
        double vTru = Math.max(0.1, r.trueV()[ip]);
        double vOurs = Math.max(0.5, actLs.vDesign());
        double vTheirs = vDesSeam;
        boolean real = Math.abs(vTru - vOurs) > DRIFT_EPS
            || Math.abs(vTru - vTheirs) > DRIFT_EPS;
        double realScale = vTru / Math.max(0.1, vTheirs);
        double rReal = vOurs / vTru;
        System.out.printf(Locale.US,
            "  [lane %d] real=%b vTru=%.4f vOurs=%.4f vTheirs=%.4f "
            + "rReal=%.6f realScale=%.6f%n",
            laneIdx, real, vTru, vOurs, vTheirs, rReal, realScale);

        boolean wg = laneIdx == 2 ? actLs.rollGlobal
            : actLs.yawPitchGlobal;   // laneWorld(geo, L)

        // lane 2 FIXED path (v18.8): effective semantics differ (our
        // ypG geo roll = bank rate, force = body roll) -> rollAcrossSeam
        if (!real && laneIdx == 2) {
            TrackCompiler.Span act = r.spans().get(1);
            TrackCompiler.Span nsp = r.spans().get(0);
            int ib = act.i0();                     // atStart=false
            int ii = Math.max(0, Math.min(sm.size() - 1, ib - 5));
            double c0 = rollConverted(nb, nsp, ib, true);
            double c1 = rollConverted(nb, nsp, ii, true);
            if (Double.isNaN(c0) || Double.isNaN(c1)) return null;
            double dtSpan = sm.get(ii).t() - sm.get(ib).t();
            double slope = Math.abs(dtSpan) < 1e-9 ? 0 : (c1 - c0) / dtSpan;
            slope /= Math.max(0.5, seamDesignVStartFalse());  // domSlope ->dist
            return new double[]{ c0, slope };
        }

        // world-rate branch (wg)
        double tB = r.spans().get(1).t0();
        int i1 = idxAt(tB - 3 * DT);
        int i0 = Math.max(i1 - 5, 0);
        int at = i1;
        double w0 = worldAt(i0, laneIdx);
        double w1 = worldAt(i1, laneIdx);
        double wAt = worldAt(at, laneIdx);
        if (Double.isNaN(w0) || Double.isNaN(w1) || Double.isNaN(wAt)) {
            System.out.println("  NaN world rates at seam!");
            return null;
        }
        double spanW = r.rates()[i1][0] - r.rates()[i0][0];
        double slopeW = spanW < 1e-9 ? 0 : (w1 - w0) / spanW;
        double valW = wAt - slopeW * (r.rates()[at][0] - tB);
        valW *= rReal;
        slopeW *= realScale * rReal * rReal;
        // domSlope(false -> true dist) at seamDesignV
        slopeW /= Math.max(0.5, seamDesignVStartFalse());
        return new double[]{ valW, slopeW };
    }

    /** rollConverted replica: neighbor's roll at sample i converted to
     *  the requested EFFECTIVE semantics (°/s). */
    static double rollConverted(LaneSection nb, TrackCompiler.Span nsp,
                                int i, boolean wantGlobal) {
        FVDIntegrator.Sample smp = r.samples().get(i);
        double x = nb.domain() == Section.Domain.DISTANCE
            ? smp.s() - nsp.s0() : smp.t() - nsp.t0();
        double auth = authAt(nb.lanes.get(2), x);
        if (nb.ratePerMeter) auth *= Math.max(0.5, smp.v());
        boolean nbEff = nb.rollGlobal
            || (nb instanceof GeoSection && nb.yawPitchGlobal);
        if (nbEff == wantGlobal) return auth;
        double vSafe = Math.max(0.5, smp.v());
        double pit = Math.toDegrees(G0 * (smp.Gv() - smp.U().y()) / vSafe);
        // D184+D185: reported yaw; transport uses the INTERNAL psi'
        double yaw = Math.toDegrees(G0 * (smp.Gl() + smp.R().y()) / vSafe);
        double psi = Coaster.worldRates(smp, pit, yaw, 0)[1];
        if (Double.isNaN(psi)) return Double.NaN;
        double transport = -psi * smp.T().y();
        return nbEff ? auth + transport : auth - transport;
    }

    static double seamDesignVStartFalse() {
        return Math.max(0.5, c.pieces.get(0).sections.get(1).vDesign());
    }
}
