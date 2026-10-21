package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * ElementChain (v20.0, D259 §1.3/§1.5) — the FORWARD KINEMATICS of a
 * straight/curve element list WITHOUT RK4: the chain is a product of
 * rigid transforms, one closed-form (or fixed-quadrature) rigid motion
 * per element, evaluated in whatever frame the entry pose is given in
 * (the compiler hands the section's CANONICAL entry, so piece x/y/z +
 * yaw moves never touch it, D199). Pure Java, no JavaFX.
 *
 * THE ONE EVALUATOR: {@link #advance} is the forward map for the
 * integrator's Profile (axes, entry elevations, the SHARP border poses
 * = the {@code Bound.sharpPos/sharpQ} records), for the solver, the
 * append ghost and the gates — no other class carries a second
 * quadrature.
 *
 * ROLL-FREE AXIS (D2, §1.2): every element rotates about an axis fixed
 * at its ENTRY against the LEVEL frame of the sharp chain — the body
 * roll never enters it. For the entry tangent T (unit), horiz2 = 1 −
 * T.y²: ul = (Y − (Y·T)T)/|…| (the level up), rl = ul × T (the project's
 * R = U × T). PLANAR axis a = cos d·ul + sin d·rl, Ω/m = −k(s)·a; the
 * initial turning direction N = T × a (d = 0: N = −rl = rider-LEFT, so
 * +κ turns LEFT — D187). HELICAL: Ω/m = −c_h(s)·cos β(s)·Y + β'(s)·h(T)
 * with h(T) = (−T.z, 0, T.x)/|T_xz| (GeoIntegrator.omegaFor's global
 * recipe) — a circle of plan radius 1/|c_h| whose elevation runs from
 * β0 (the entry's) to β1 over the TRACK arc. STRAIGHT: Ω/m = 0.
 *
 * VERTICAL ENTRY (horiz2 < HELIX_VERT_EPS): no level frame exists — the
 * pose's ul/rl are the previous element's ROLL-FREE end frame (element
 * 0: the entry orientation's own U, R), so a PLANAR element inherits
 * its plane; a helix cannot start there (the factories refuse upstream
 * with SectionNames.HELIX_VERTICAL_WHY; an existing helix whose entry is
 * made vertical by an upstream edit falls back to h := −R and compiles).
 *
 * Quaternion convention: q' = ½(0,Ω)⊗q, so a constant Ω over arc L is
 * {@code Quat.aboutAxis(â, |Ω|·L·sign).mul(q0)} — the PLANAR element's
 * roll-free frame is {@code aboutAxis(a, −Θ).mul(q0)}, Θ = ∫k.
 */
public final class ElementChain {

    private ElementChain() {}

    /** A chain pose: position, the ROLL-FREE orientation, the level
     *  frame (ul, rl) the next element's axis is built on, and the arc
     *  from the section start. */
    public record Pose(Vec3 pos, Quat q, Vec3 ul, Vec3 rl, double s) {
        public Vec3 T() { return q.T(); }
        public Vec3 U() { return q.U(); }
        public Vec3 R() { return q.U().cross(q.T()); }
    }

    // ---- the level frame ----

    /** The chain's entry pose from a position + orientation: the level
     *  frame off T, or the orientation's own U/R on vertical track. */
    public static Pose entry(Vec3 pos0, Quat q0) {
        Quat q = q0.normalize();
        Vec3 T = q.T();
        double horiz2 = 1 - T.y() * T.y();
        Vec3 ul, rl;
        if (horiz2 < StraightCurveSection.HELIX_VERT_EPS) {
            ul = q.U();
            rl = ul.cross(T);
        } else {
            ul = levelUp(T, horiz2);
            rl = ul.cross(T);
        }
        return new Pose(pos0, q, ul, rl, 0);
    }

    /** Y − (Y·T)T normalized (bankOf's `ul`); caller guarantees horiz2
     *  is not degenerate. */
    static Vec3 levelUp(Vec3 T, double horiz2) {
        return new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
    }

    /** The level frame at the END of an element from its roll-free
     *  exit (T1, U1): level if defined, else inherited (U1, U1 × T1). */
    private static Pose poseAt(Vec3 pos, Quat q, double s) {
        Vec3 T = q.T();
        double horiz2 = 1 - T.y() * T.y();
        Vec3 ul, rl;
        if (horiz2 < StraightCurveSection.HELIX_VERT_EPS) {
            ul = q.U();
        } else {
            ul = levelUp(T, horiz2);
        }
        rl = ul.cross(T);
        return new Pose(pos, q, ul, rl, s);
    }

    /** Elevation of the pose's tangent (deg; NaN never — clamped asin). */
    public static double elevDeg(Pose p) {
        double y = p.T().y();
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, y))));
    }

    /** The PLANAR axis a = cos d·ul + sin d·rl (unit, ⊥ T), world-fixed
     *  for the whole element; null for the other kinds. */
    public static Vec3 axisOf(StraightCurveSection.Element e, Pose entry) {
        if (e.kind != StraightCurveSection.Kind.PLANAR) return null;
        double d = Math.toRadians(e.dirDeg);
        return entry.ul().scale(Math.cos(d)).add(entry.rl().scale(Math.sin(d)));
    }

    // ---- sinc ----

    /** sin x / x with the series 1 − x²/6 + x⁴/120 below |x| < 1e-4. */
    static double sinc(double x) {
        if (Math.abs(x) < 1e-4) {
            double x2 = x * x;
            return 1 - x2 / 6 + x2 * x2 / 120;
        }
        return Math.sin(x) / x;
    }

    // ---- Gauss–Legendre 8 (the fixed quadrature, D259 §1.3) ----

    private static final double[] GL_X = {
        -0.9602898564975363, -0.7966664774136267, -0.5255324099163290,
        -0.1834346424956498, 0.1834346424956498, 0.5255324099163290,
        0.7966664774136267, 0.9602898564975363 };
    private static final double[] GL_W = {
        0.1012285362903763, 0.2223810344533745, 0.3137066458778873,
        0.3626837833783620, 0.3626837833783620, 0.3137066458778873,
        0.2223810344533745, 0.1012285362903763 };
    /** Panel width (m) of the composite rule. */
    static final double PANEL_M = 0.5;

    private static int panels(double L) {
        return Math.max(1, (int) Math.ceil(L / PANEL_M - 1e-9));
    }

    // ---- advance ----

    /** The element's exit pose from its entry pose — the ONE forward map. */
    public static Pose advance(Pose p, StraightCurveSection.Element e) {
        switch (e.kind) {
            case STRAIGHT: return straight(p, e);
            case PLANAR: return planar(p, e);
            case HELICAL: return helical(p, e);
            default: throw new IllegalStateException("kind " + e.kind);
        }
    }

    /** n+1 poses: index i = the entry of element i, n = the section exit. */
    public static List<Pose> poses(List<StraightCurveSection.Element> els,
                                   Vec3 pos0, Quat q0) {
        List<Pose> out = new ArrayList<>(els.size() + 1);
        Pose p = entry(pos0, q0);
        out.add(p);
        for (StraightCurveSection.Element e : els) {
            p = advance(p, e);
            out.add(p);
        }
        return out;
    }

    private static Pose straight(Pose p, StraightCurveSection.Element e) {
        Vec3 T = p.T();
        return new Pose(p.pos().add(T.scale(e.L)), p.q(), p.ul(), p.rl(),
            p.s() + e.L);
    }

    private static boolean constantK(StraightCurveSection.Element e) {
        return e.kLink || e.k0 == e.k1;
    }

    private static Pose planar(Pose p, StraightCurveSection.Element e) {
        Vec3 a = axisOf(e, p);
        Vec3 T = p.T();
        Vec3 N = T.cross(a);
        Vec3 pos;
        double theta;
        if (constantK(e)) {
            // the exact arc: p(L) = p0 + L·[T·sinc Θ + N·(Θ/2)·sinc²(Θ/2)]
            double k = e.k0;
            theta = k * e.L;
            double half = theta / 2;
            double sh = sinc(half);
            pos = p.pos().add(T.scale(e.L * sinc(theta)))
                .add(N.scale(e.L * half * sh * sh));
        } else {
            // ramped k: the axis is still fixed, so only the turn angle
            // θ(s) = ∫k varies — closed (Element.turnRadAt); the position
            // ∫(T cos θ + N sin θ) ds is the Fresnel/Anger class → the
            // fixed composite GL-8 on ceil(L/0.5) panels, deterministic
            int n = panels(e.L);
            double h = e.L / n;
            double ic = 0, is = 0;
            for (int pnl = 0; pnl < n; pnl++) {
                double s0 = pnl * h;
                for (int g = 0; g < 8; g++) {
                    double s = s0 + h * (GL_X[g] + 1) / 2;
                    double th = e.turnRadAt(s);
                    double w = GL_W[g] * h / 2;
                    ic += w * Math.cos(th);
                    is += w * Math.sin(th);
                }
            }
            theta = e.turnRadAt(e.L);
            pos = p.pos().add(T.scale(ic)).add(N.scale(is));
        }
        Quat q = Quat.aboutAxis(a, -theta).mul(p.q()).normalize();
        return poseAt(pos, q, p.s() + e.L);
    }

    /** The plan heading frame of a tangent: T_h = unit(T.x, 0, T.z),
     *  N_h = T_h × Y (the +c_h = LEFT turning direction); at a vertical
     *  tangent the body right stands in (h := −R, GeoIntegrator's
     *  fallback): T_h = R × Y. */
    private static Vec3[] planFrame(Quat q) {
        Vec3 T = q.T();
        double len = Math.hypot(T.x(), T.z());
        Vec3 Th;
        if (len <= 1e-9) {
            Vec3 R = q.U().cross(T);
            Th = R.cross(new Vec3(0, 1, 0)).normalize();
        } else {
            Th = new Vec3(T.x() / len, 0, T.z() / len);
        }
        Vec3 Nh = Th.cross(new Vec3(0, 1, 0));
        return new Vec3[] { Th, Nh };
    }

    private static Pose helical(Pose p, StraightCurveSection.Element e) {
        Vec3 Y = new Vec3(0, 1, 0);
        Vec3 T0 = p.T();
        Vec3 U0 = p.U();
        double beta0 = Math.asin(Math.max(-1, Math.min(1, T0.y())));
        double beta1 = e.bLink ? beta0 : Math.toRadians(e.b1Deg);
        Vec3[] pf = planFrame(p.q());
        Vec3 Th = pf[0], Nh = pf[1];
        // the bank vs the horizon is PRESERVED by both rotations (about Y
        // and about the horizontal-right) — measured once at the entry
        double bank0 = bankOf(T0, U0);
        double sH, dy, dpsi;
        Vec3 plan;
        boolean linearConst = constantK(e)
            && e.bInterp == StraightCurveSection.Interp.LINEAR;
        if (linearConst) {
            // closed forms: s_h = L·cos β̄·sinc(Δβ/2), Δy = L·sin β̄·sinc(Δβ/2)
            // (= L(sin β1 − sin β0)/Δβ and L(cos β0 − cos β1)/Δβ, pole-free)
            double db = beta1 - beta0;
            double bm = (beta0 + beta1) / 2;
            double sc = sinc(db / 2);
            sH = e.L * Math.cos(bm) * sc;
            dy = e.L * Math.sin(bm) * sc;
            dpsi = e.k0 * sH;
            double half = dpsi / 2;
            double sh = sinc(half);
            plan = Th.scale(sH * sinc(dpsi)).add(Nh.scale(sH * half * sh * sh));
        } else {
            // ramped plan curvature and/or a SINUSOIDAL pitch: the plan
            // heading ψ(s) = ∫c_h cos β is no longer linear in s_h, so the
            // plan path leaves the circle — fixed composite GL-8 panels,
            // ψ at every node by a GL-8 over [panel start, node]
            int n = panels(e.L);
            double h = e.L / n;
            double psi0 = 0, ix = 0, iz = 0;
            sH = 0; dy = 0;
            for (int pnl = 0; pnl < n; pnl++) {
                double s0 = pnl * h;
                for (int g = 0; g < 8; g++) {
                    double s = s0 + h * (GL_X[g] + 1) / 2;
                    double w = GL_W[g] * h / 2;
                    double beta = Math.toRadians(e.betaDegAt(s / e.L,
                        Math.toDegrees(beta0)));
                    // ψ(s) = ψ(s0) + ∫_{s0}^{s} c_h cos β
                    double dpsiNode = 0;
                    double hs = s - s0;
                    for (int g2 = 0; g2 < 8; g2++) {
                        double s2 = s0 + hs * (GL_X[g2] + 1) / 2;
                        double b2 = Math.toRadians(e.betaDegAt(s2 / e.L,
                            Math.toDegrees(beta0)));
                        dpsiNode += GL_W[g2] * hs / 2
                            * e.kappaAt(s2 / e.L) * Math.cos(b2);
                    }
                    double psi = psi0 + dpsiNode;
                    double cb = Math.cos(beta);
                    ix += w * cb * Math.cos(psi);
                    iz += w * cb * Math.sin(psi);
                    sH += w * cb;
                    dy += w * Math.sin(beta);
                }
                // advance ψ over the whole panel
                double dpsiPanel = 0;
                for (int g2 = 0; g2 < 8; g2++) {
                    double s2 = s0 + h * (GL_X[g2] + 1) / 2;
                    double b2 = Math.toRadians(e.betaDegAt(s2 / e.L,
                        Math.toDegrees(beta0)));
                    dpsiPanel += GL_W[g2] * h / 2 * e.kappaAt(s2 / e.L) * Math.cos(b2);
                }
                psi0 += dpsiPanel;
            }
            dpsi = psi0;
            plan = Th.scale(ix).add(Nh.scale(iz));
        }
        Vec3 pos = p.pos().add(plan).add(Y.scale(dy));
        Vec3 T1 = Th.scale(Math.cos(dpsi)).add(Nh.scale(Math.sin(dpsi)))
            .scale(Math.cos(beta1)).add(Y.scale(Math.sin(beta1))).normalize();
        double h2 = 1 - T1.y() * T1.y();
        Vec3 ul1 = h2 < StraightCurveSection.HELIX_VERT_EPS ? U0 : levelUp(T1, h2);
        Vec3 U1 = ul1.scale(Math.cos(bank0)).add(T1.cross(ul1).scale(Math.sin(bank0)))
            .normalize();
        Quat q = Quat.fromFrame(T1, U1);
        return poseAt(pos, q, p.s() + e.L);
    }

    /** Signed bank of U vs the level up at T (rad; the roll lane's sign)
     *  — 0 on vertical track. */
    static double bankOf(Vec3 T, Vec3 U) {
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < StraightCurveSection.HELIX_VERT_EPS) return 0;
        Vec3 ul = levelUp(T, horiz2);
        return Math.atan2(ul.cross(U).dot(T), U.dot(ul));
    }

    // ---- derived readouts ----

    /** The signed turn (deg, reported-left): PLANAR L·k̄; HELICAL the plan
     *  heading change Δψ (closed for a constant c_h + linear pitch, else
     *  the quadrature's); STRAIGHT 0. */
    public static double turnDeg(StraightCurveSection.Element e, Pose entry) {
        switch (e.kind) {
            case STRAIGHT: return 0;
            case PLANAR: return e.turnDeg();
            default: {
                Pose end = advance(entry, e);
                Vec3[] f0 = planFrame(entry.q());
                Vec3 T1 = end.T();
                double x = T1.dot(f0[0]), z = T1.dot(f0[1]);
                double dpsi = Math.atan2(z, x);
                // laps: the closed form's Δψ = c_h·s_h may exceed ±π
                if (constantK(e) && e.bInterp == StraightCurveSection.Interp.LINEAR) {
                    double beta0 = Math.toRadians(elevDeg(entry));
                    double beta1 = e.bLink ? beta0 : Math.toRadians(e.b1Deg);
                    double db = beta1 - beta0;
                    double sH = e.L * Math.cos((beta0 + beta1) / 2) * sinc(db / 2);
                    return Math.toDegrees(e.k0 * sH);
                }
                return Math.toDegrees(dpsi);
            }
        }
    }

    /** The chord |p(L) − p0|. */
    public static double chord(StraightCurveSection.Element e, Pose entry) {
        return advance(entry, e).pos().sub(entry.pos()).length();
    }

    /** The element's SHARP end elevation (deg). */
    public static double endElevDeg(StraightCurveSection.Element e, Pose entry) {
        return elevDeg(advance(entry, e));
    }

    /** HELICAL: the osculating TRACK radius at the end, 1/(|c_h(L)|·cos²β1)
     *  (D6's grey readout); +∞ when flat. */
    public static double trackRadiusEnd(StraightCurveSection.Element e, Pose entry) {
        if (e.kind != StraightCurveSection.Kind.HELICAL) return e.radius();
        double beta1 = Math.toRadians(e.bLink ? elevDeg(entry) : e.b1Deg);
        double c = Math.abs(e.k1) * Math.cos(beta1) * Math.cos(beta1);
        return c < StraightCurveSection.KAPPA_INF ? Double.POSITIVE_INFINITY : 1 / c;
    }
}
