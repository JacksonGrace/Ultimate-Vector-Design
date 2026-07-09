package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * BRIDGE section (Phase 2, v13.0 D72; LIVE-FOLLOW + G² ends v13.1 D73;
 * SECTIONS-DESIGN §4): connects one piece's exit pose to the NEXT
 * piece's entry pose with a G²-continuous spline. Pure Java, no
 * JavaFX — the model AND its geometry live here (closed-form, no ODE).
 *
 * LIVE-FOLLOW (v13.1, user rule): the compiler RE-CAPTURES the solve
 * inputs from the live chain end + target piece entry on EVERY
 * compile — move the target piece (or edit the upstream track) and
 * the bridge follows. There is no feedback loop to fear here: the
 * bridge's own geometry never influences its endpoints (that concern
 * belongs to §5 influence mode). The stored fields below are a CACHE
 * of the last capture; they govern alone only when the target piece
 * is GONE (deleted/attached) — the bridge then stays frozen at its
 * last solve and the badge shows "!".
 *
 * GEOMETRY: quintic Hermite on position over u ∈ [0,1] —
 *   p(0)/p(1) = the two positions, p'(0)/p'(1) = T_A·lenA / T_B·lenB,
 *   p''(0)/p''(1) = accA·lenA² / accB·lenB²
 * where accA/accB are the neighbors' CURVATURE VECTORS at the seams
 * (dT/ds, 1/m — captured live each compile: measured off the chain's
 * tail samples on the entry side, computed analytically from the
 * target's first section on the exit side). The joins are therefore
 * G²: tangent AND curvature continuous (v13.0 was G¹ with zero end
 * curvature; acc = 0 reproduces it, and v13.0 files load that way).
 * lenA/lenB are the user-adjustable tangent lengths (m): bigger = the
 * bridge leaves/arrives straighter and sweeps wider. Collinear
 * endpoints with matched tangents and zero end curvature degenerate
 * to an EXACT straight line (gated).
 *
 * ORIENTATION: rotation-minimizing frame transport along the spline
 * (double-reflection-free small-step version: rotate each step by the
 * minimal rotation taking T_i to T_i+1), then the residual roll
 * mismatch against the target's U is distributed as a QUINTIC twist
 * about T whose end slopes are the neighbors' roll RATES (v13.2) and
 * end second derivatives their roll ACCELERATIONS (v13.4) — the roll
 * angle, the roll-rate graph AND its slope all flow through both
 * seams.
 *
 * SAMPLING: the spline is presampled geometrically (PRESAMPLE_STEP
 * arc length), then walked in TIME at dt with v from energy vs the
 * entry height at vDesign (the standard rule) — samples stay on the
 * uniform dt grid with a final partial step landing exactly on the
 * end. Felt Gv/Gl are recorded from the ANALYTIC spline curvature at
 * design speed (a = v²·κ⊥): rail colors and stats stay honest. The
 * exit pose handed to the chain is solvedTarget VERBATIM — the next
 * piece's entry seam is exact.
 */
public final class BridgeSection implements Section {

    public double vDesign;
    public boolean reversed = false;   // pieces with bridges refuse to
                                       // reverse (D72); field kept for
                                       // the Section contract
    public TrackPose solvedEntry;      // live-captured each compile;
    public TrackPose solvedTarget;     // frozen only when orphaned
    public Vec3 accA = Vec3.ZERO;      // seam curvature vectors (1/m,
    public Vec3 accB = Vec3.ZERO;      // perpendicular to the end T)
    public Vec3 jerkA = Vec3.ZERO;     // seam d²T/ds² (1/m² — v13.2:
    public Vec3 jerkB = Vec3.ZERO;     // rate-SLOPE continuity)
    public double rollA, rollB;        // seam roll rates (rad/s)
    public double rollAccA, rollAccB;  // seam d(roll rate)/dt (rad/s² —
                                       // v13.4: the roll-rate GRAPH's
                                       // slope flows through the seams)
    public double vB;                  // target's entry design speed
                                       // (≤ 0 = pure energy, no blend)
    public double lenA, lenB;          // tangent lengths, m

    /** Placement-optimization mode (v13.5): what the length/yaw
     *  controls re-run when edited. POS_YAW = the full continuation
     *  march (position + heading, the default); POS = position only,
     *  the user owns the target's yaw; NONE = fully manual — the
     *  target piece is placed by hand and the bridge just follows. */
    public static final int OPT_POS_YAW = 0;
    public static final int OPT_POS = 1;
    public static final int OPT_NONE = 2;
    public int optMode = OPT_POS_YAW;

    /** "Optimize length" (v13.8, D82): while set (and an optimize
     *  mode is active) the LENGTH is chosen by
     *  Coaster.optimalBridgeLength — the Length spinner and end-line
     *  drag disable. Switching INTO an optimize mode turns it on
     *  (user spec: the switch itself should land at the optimal
     *  distance). blen's 4th field; missing = off (old files). */
    public boolean optLen = false;

    public BridgeSection(TrackPose entry, TrackPose target,
                         double lenA, double lenB, double vDesign) {
        this.solvedEntry = new TrackPose(entry.pos(), entry.orient());
        this.solvedTarget = new TrackPose(target.pos(), target.orient());
        this.lenA = lenA;
        this.lenB = lenB;
        this.vDesign = vDesign;
    }

    /** Fresh bridge between two poses with the default tangent length
     *  of ONE FULL GAP (v13.3 — was gap/2): |p'| at the ends equals the
     *  tangent length, so short tangents pack the turning into the seam
     *  regions — rate profiles spiked at the ends and went flat in the
     *  middle (probed: end rates 12-55x the interior at gap/2, ~1x at
     *  the full gap, with max |dRate/dt| down 3-7x). Longer still and
     *  the interior bulge takes over. */
    public static BridgeSection solve(TrackPose entry, TrackPose target,
                                      double vDesign) {
        double gap = target.pos().sub(entry.pos()).length();
        double len = Math.max(1, gap);
        return new BridgeSection(entry, target, len, len, vDesign);
    }

    public BridgeSection copy() {
        BridgeSection b = new BridgeSection(solvedEntry, solvedTarget,
            lenA, lenB, vDesign);
        b.accA = accA;
        b.accB = accB;
        b.jerkA = jerkA;
        b.jerkB = jerkB;
        b.rollA = rollA;
        b.rollB = rollB;
        b.rollAccA = rollAccA;
        b.rollAccB = rollAccB;
        b.vB = vB;
        b.optMode = optMode;
        b.optLen = optLen;
        return b;
    }

    @Override public double vDesign() { return vDesign; }

    @Override public void vDesign(double v) { vDesign = v; }

    @Override public Domain domain() { return Domain.DISTANCE; }

    @Override public boolean reversed() { return reversed; }

    @Override public void reversed(boolean r) { reversed = r; }

    // =====================================================================
    // Geometry (closed-form; no integrator class needed)
    // =====================================================================

    static final double PRESAMPLE_STEP = 0.05;   // m, geometric table

    /** Samples plus the exact exit pose (= solvedTarget, the seam
     *  contract). */
    public record Result(List<FVDIntegrator.Sample> samples, TrackPose exit) {}

    /** One row of the geometric presample table. */
    private record Geo(double s, Vec3 pos, Vec3 T, Vec3 U, Vec3 kappa) {}

    public Result integrate(double dt) {
        Vec3 p0 = solvedEntry.pos(), p1 = solvedTarget.pos();
        double la = Math.max(0.01, lenA), lb = Math.max(0.01, lenB);
        Vec3 m0 = solvedEntry.T().scale(la);
        Vec3 m1 = solvedTarget.T().scale(lb);
        // arc-length seam derivatives map to u-parameter ends with
        // |p'| = len there: p'' = κ·len², p''' = (dκ/ds)·len³
        // (zero seam data = the v13.0/13.1 shapes — old files).
        // dκ/ds = the MEASURED ⊥ part (jerkA/B, D74) plus the ANALYTIC
        // tangential −|κ|²·T (v13.4): a constant turn's curvature
        // vector rotates, and dropping that rotation made the septic
        // sag off a continued circle (probed: 4.9 °/s of yaw ripple
        // over a 103° sweep with it zeroed). D74's ringing came from
        // MEASURING the tangential part — rebuilt from κ it is exact.
        Vec3 j0 = jerkA.sub(solvedEntry.T().scale(accA.dot(accA)));
        Vec3 j1 = jerkB.sub(solvedTarget.T().scale(accB.dot(accB)));
        Vec3[] cs = septic(p0, p1, m0, m1,
            accA.scale(la * la), accB.scale(lb * lb),
            j0.scale(la * la * la), j1.scale(lb * lb * lb));

        // --- geometric presample with rotation-minimizing frames ---
        double chord = Math.max(p1.sub(p0).length(), 1e-3);
        int n = Math.max(64, (int) Math.ceil(chord * 2.5 / PRESAMPLE_STEP));
        List<Geo> geo = new ArrayList<>(n + 1);
        Vec3 U = solvedEntry.U();
        Vec3 prevT = solvedEntry.T();
        double s = 0;
        Vec3 prevPos = p0;
        for (int i = 0; i <= n; i++) {
            double u = (double) i / n;
            Vec3 pos = poly(cs, u, 0);
            Vec3 dp = poly(cs, u, 1);
            Vec3 ddp = poly(cs, u, 2);
            double v2 = Math.max(dp.dot(dp), 1e-12);
            Vec3 T = dp.scale(1 / Math.sqrt(v2));
            // curvature vector: perpendicular part of ddp over |dp|²
            Vec3 kappa = ddp.sub(T.scale(ddp.dot(T))).scale(1 / v2);
            if (i > 0) {
                s += pos.sub(prevPos).length();
                // minimal rotation taking prevT to T transports U
                Vec3 axis = prevT.cross(T);
                double sin = axis.length();
                double cos = Math.max(-1, Math.min(1, prevT.dot(T)));
                if (sin > 1e-12) {
                    U = U.rotate(axis.scale(1 / sin), Math.atan2(sin, cos));
                }
            }
            U = U.sub(T.scale(U.dot(T))).normalize();   // keep exactly ⊥ T
            geo.add(new Geo(s, pos, T, U, kappa));
            prevT = T;
            prevPos = pos;
        }
        double total = Math.max(s, 1e-6);

        // --- speed profile: energy from the entry, sine-eased onto the
        // target's design entry speed (v13.2) so the far seam has no v
        // step — the ease has zero end slope, keeping dv/dt = -g·T.y
        // continuous at both seams. vB ≤ 0 = pure energy (old files).
        double y0 = p0.y();
        double vEnd = Math.max(0.5, FVDIntegrator.speedAt(p1, vDesign, y0));
        double dv = vB > 0 ? vB - vEnd : 0;

        // --- residual roll vs the target, distributed as a QUINTIC
        // twist whose end slopes are the neighbors' roll RATES (v13.2)
        // AND whose end second derivatives are their roll ACCELERATIONS
        // (v13.4: the roll-rate graph's SLOPE flows through the seams —
        // the hardcoded-zero q'' left it entering/leaving flat). The
        // quintic through these six conditions is the MINIMUM-JERK
        // interpolant: where the residual twist demands a fast rate
        // swing it still swings (that is the geometry's demand, D74),
        // but it now LEAVES each seam on the neighbor's slope instead
        // of a flat shelf. (A min-∫(q'')² septic was probed and
        // REJECTED: strain-optimal rushes q'' away from the pinned end
        // values — the near-seam slope got worse, −312 vs −179 °/s² on
        // the nasty fixture.) In x = s/total: roll rate = q'·v/total,
        // its slope = q''·v²/total² + q'·(dv/dt)/total with dv/dt =
        // −g·T.y at the ends (the speed blend has zero end slope).
        // Zero rates + accs on level ends reproduce the older ease. ---
        Geo end = geo.get(n);
        Vec3 wantU = solvedTarget.U()
            .sub(end.T().scale(solvedTarget.U().dot(end.T()))).normalize();
        double twist = Math.atan2(end.U().cross(wantU).dot(end.T()),
            end.U().dot(wantU));
        double v0b = blendedV(0, total, geo, dv, y0);
        double v1b = blendedV(total, total, geo, dv, y0);
        double tw0 = total * rollA / v0b;   // dtwist/dx at the ends
        double tw1 = total * rollB / v1b;
        double dvdt0 = -FVDIntegrator.G0 * solvedEntry.T().y();
        double dvdt1 = -FVDIntegrator.G0 * solvedTarget.T().y();
        double c0 = (rollAccA - tw0 * dvdt0 / total)
            * (total / v0b) * (total / v0b);
        double c1 = (rollAccB - tw1 * dvdt1 / total)
            * (total / v1b) * (total / v1b);
        double qA = twist - tw0 - c0 / 2;   // general quintic Hermite
        double qB = tw1 - tw0 - c0;
        double qC = c1 - c0;
        double[] tw = { tw0, c0 / 2,
            10 * qA - 4 * qB + qC / 2,
            -15 * qA + 7 * qB - qC,
            6 * qA - 3 * qB + qC / 2, 0, 0 };

        // --- time-domain resample (uniform dt, exact landing) ---
        List<FVDIntegrator.Sample> out = new ArrayList<>();
        double t = 0, sAt = 0;
        int guard = 0;
        while (guard++ < 500_000) {
            boolean last = sAt >= total - 1e-9;
            out.add(sampleAt(geo, Math.min(sAt, total), total, tw, dv, y0, t));
            if (last) break;
            double v = blendedV(Math.min(sAt, total), total, geo, dv, y0);
            sAt = Math.min(total, sAt + v * dt);
            t += sAt >= total - 1e-9
                ? (total - (sAt - v * dt)) / v   // partial landing step
                : dt;
        }
        return new Result(out, new TrackPose(solvedTarget.pos(),
            solvedTarget.orient()));
    }

    /** Degree-7 Hermite: position/tangent/2nd/3rd derivative at both
     *  ends. c0..c3 are direct; c4..c7 solve a fixed 4×4 system. */
    private static Vec3[] septic(Vec3 p0, Vec3 p1, Vec3 m0, Vec3 m1,
                                 Vec3 a0, Vec3 a1, Vec3 j0, Vec3 j1) {
        Vec3[] cs = new Vec3[8];
        cs[0] = p0;
        cs[1] = m0;
        cs[2] = a0.scale(0.5);
        cs[3] = j0.scale(1.0 / 6);
        double[][] m = {
            { 1, 1, 1, 1 },
            { 4, 5, 6, 7 },
            { 12, 20, 30, 42 },
            { 24, 60, 120, 210 },
        };
        Vec3[] b = {
            p1.sub(cs[0]).sub(cs[1]).sub(cs[2]).sub(cs[3]),
            m1.sub(cs[1]).sub(cs[2].scale(2)).sub(cs[3].scale(3)),
            a1.sub(cs[2].scale(2)).sub(cs[3].scale(6)),
            j1.sub(cs[3].scale(6)),
        };
        // Gaussian elimination (tiny fixed system, no pivoting needed:
        // the matrix is well-conditioned and constant)
        for (int col = 0; col < 4; col++) {
            for (int row = col + 1; row < 4; row++) {
                double f = m[row][col] / m[col][col];
                for (int k = col; k < 4; k++) m[row][k] -= f * m[col][k];
                b[row] = b[row].sub(b[col].scale(f));
            }
        }
        for (int row = 3; row >= 0; row--) {
            Vec3 acc = b[row];
            for (int k = row + 1; k < 4; k++) {
                acc = acc.sub(cs[4 + k].scale(m[row][k]));
            }
            cs[4 + row] = acc.scale(1 / m[row][row]);
        }
        return cs;
    }

    /** The septic (or its 1st/2nd u-derivative) at u. */
    private static Vec3 poly(Vec3[] cs, double u, int deriv) {
        Vec3 acc = Vec3.ZERO;
        double up = 1;
        for (int k = deriv; k < 8; k++) {
            double coef = 1;
            for (int d = 0; d < deriv; d++) coef *= k - d;
            acc = acc.add(cs[k].scale(coef * up));
            up *= u;
        }
        return acc;
    }

    /** Energy speed eased onto the target's entry speed (dv = the far-
     *  seam correction; the sine ease has zero end slopes). */
    private double blendedV(double s, double total, List<Geo> geo,
                            double dv, double y0) {
        double ve = Math.max(0.5,
            FVDIntegrator.speedAt(posAt(geo, s), vDesign, y0));
        if (dv == 0) return ve;
        return Math.max(0.5,
            ve + dv * (1 - Math.cos(Math.PI * s / total)) / 2);
    }

    private static Vec3 posAt(List<Geo> geo, double s) {
        int i = indexAt(geo, s);
        Geo a = geo.get(i), b = geo.get(Math.min(i + 1, geo.size() - 1));
        double ds = b.s() - a.s();
        double u = ds < 1e-12 ? 0 : (s - a.s()) / ds;
        return a.pos().add(b.pos().sub(a.pos()).scale(u));
    }

    private FVDIntegrator.Sample sampleAt(List<Geo> geo, double s, double total,
                                          double[] tw, double dv, double y0,
                                          double t) {
        int i = indexAt(geo, s);
        Geo a = geo.get(i), b = geo.get(Math.min(i + 1, geo.size() - 1));
        double ds = b.s() - a.s();
        double u = ds < 1e-12 ? 0 : Math.max(0, Math.min(1, (s - a.s()) / ds));
        Vec3 pos = a.pos().add(b.pos().sub(a.pos()).scale(u));
        Vec3 T = a.T().add(b.T().sub(a.T()).scale(u)).normalize();
        Vec3 U = a.U().add(b.U().sub(a.U()).scale(u));
        U = U.sub(T.scale(U.dot(T))).normalize();
        Vec3 kappa = a.kappa().add(b.kappa().sub(a.kappa()).scale(u));
        // septic twist: roll rate AND its slope match the neighbors at
        // both seams; interior strain-minimal (tw = coefficients a1..a7)
        double x = total < 1e-9 ? 0 : s / total;
        double w = 0, xp = x;
        for (int p = 1; p <= 7; p++) {
            w += tw[p - 1] * xp;
            xp *= x;
        }
        if (Math.abs(w) > 1e-15) U = U.rotate(T, w);
        Vec3 R = U.cross(T);
        double v = blendedV(s, total, geo, dv, y0);
        Vec3 accel = kappa.scale(v * v);   // centripetal at design speed
        double gv = accel.dot(U) / FVDIntegrator.G0 + U.y();
        double gl = accel.dot(R) / FVDIntegrator.G0 + R.y();
        return new FVDIntegrator.Sample(t, s, pos, T, U, R, Math.max(0.1, v), gv, gl);
    }

    private static int indexAt(List<Geo> geo, double s) {
        int lo = 0, hi = geo.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (geo.get(mid).s() <= s) lo = mid; else hi = mid;
        }
        return lo;
    }
}
