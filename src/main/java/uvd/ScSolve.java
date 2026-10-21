package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * ScSolve (Stage 2a, v20.2, D259 §4) — THE KINEMATIC SOLVER of the
 * straight/curve overhaul: the ONE pure-Java solver every s/c surface
 * will call (ball, rings, arrows, typed boxes, the append ghost, insert /
 * change type / delete, the tail aligns and the loop closure). No JavaFX,
 * no consumer yet (Stage 3a wires the element window and the rigs).
 *
 * WHAT IT READS / WRITES. It reads the SHARP element chain (the element
 * fields as {@link ElemParams}) and the section's CANONICAL entry frame
 * ({@link SectionFrame} builds it from the compile already in hand —
 * {@code Span.exitPsi/exitCanon/exitTilt} and the per-border
 * {@code Bound} records are the only compile outputs that enter, once
 * per event; never a compiled SAMPLE). It returns new element parameters
 * ({@link Solution}); the consumer applies them (the lengths through
 * {@code Element.setLength}, §2.2's cut / shadow choke — {@link #apply}).
 * It runs ONCE per event and never inside compile / rebuild.
 *
 * CONVENTIONS (§4's — internal sign throughout, the UI negates heading
 * at its boundary, D186): heading = atan2(−T.z, T.x); +κ at dir 0 turns
 * rider-LEFT (D187); the planar axis a = cos d·U + sin d·R built on the
 * LEVEL frame of the element's entry (D2 — U = the level up, R = U × T);
 * N = T × a is the initial turning direction (d = 0 ⇒ N = −R = left).
 * A rotation about a by −Θ (Θ = κ·L signed) is ElementChain's planar
 * law, so the exit tangent is T cos Θ + N sin Θ. Two parameter triples
 * draw the SAME arc — (κ, d, Θ) and (−κ, d + 180°, −Θ) — and every pick
 * between them is nearest-to-hint on the stored dir (branch continuity:
 * a target crossing the forward ray keeps dir and flips κ's sign — the
 * pass-through-straight of §1.3). Laps ride in L (Θ = φ0 + 2πk nearest
 * the hint's |Θ|, D10 no cap).
 *
 * THE ONE EVALUATOR: every forward map goes through
 * {@link ElementChain#advance} on a scratch element ({@link #forwardEnd}
 * / {@link #frames}) — the closed forms in this class are INVERSES only
 * (I1, I2, I4-I6, I9, the biarc, the single snap). Deterministic,
 * bracketed, capped (D1): the Newton of §4.11 ≤ 20 steps, the golden
 * section ≤ 40 evaluations, the biarc bisection ≤ 60, the predictor's
 * corrector ≤ 3 rounds — each failure has its sentence (§4.14's
 * register in SectionNames, LabelGate section 21).
 *
 * IDEMPOTENCE: every node solve first asks whether the CURRENT params
 * already land on the target (≤ {@link #AT_TARGET} m) and returns them
 * UNCHANGED then — so re-solving a landed point is a bitwise no-op
 * whatever row produced it (ScSolveGate e / n), and projections carry
 * "already on the set" guards (relative 1e-12) so projecting a landed
 * point moves nothing.
 *
 * Gated by {@code uvd.ScSolveGate} (rows a-r; s waits for the Stage-3a
 * rig).
 */
public final class ScSolve {

    private ScSolve() {}

    // =================================================================
    // §4.1 — the API types
    // =================================================================

    /** The chart the window shows (D16/D20) — a SOLVE ARGUMENT, never an
     *  element field: it picks the free parameter of §4.11's golden
     *  section (LOCAL = the turn angle, GLOBAL = the arc length when the
     *  turn is locked) and which rings exist. */
    public enum Chart { LOCAL, GLOBAL }

    public enum Status { EXACT, PROJECTED, REFUSED }

    /** The typed boxes of §4.6 (X/Y/Z in the CANONICAL frame — the
     *  window converts its world box through SectionFrame first). */
    public enum Box { X, Y, Z, KAPPA, TURN, DIR, HEADING, ELEVATION, LEN,
        K1, B1 }

    /** The rings of §4.4 (TURN / DIR local, HEADING / ELEVATION global). */
    public enum Ring { TURN, DIR, HEADING, ELEVATION }

    /** The arrows of §4.4 / D19: the tangent arrow drags the ARC LENGTH,
     *  the radial arrow the END radius. */
    public enum Arrow { TANGENT, RADIAL }

    /** §4.10's tail modes. SNAP_HEADING / SNAP_ELEVATION = SNAP_ONE(axis). */
    public enum TailMode { ALIGN_PREV, CLOSE_POS, CLOSE_POSE, SNAP_HEADING,
        SNAP_ELEVATION }

    /** An immutable VIEW of §1.1's element record with its names. */
    public record ElemParams(StraightCurveSection.Kind kind, double L,
                             double k0, double k1, boolean kLink,
                             StraightCurveSection.Interp kInterp,
                             double dirDeg, double b1Deg, boolean bLink,
                             StraightCurveSection.Interp bInterp,
                             double blendIn, int locks) {

        public static ElemParams of(StraightCurveSection.Element e) {
            return new ElemParams(e.kind, e.L, e.k0, e.k1, e.kLink, e.kInterp,
                e.dirDeg, e.b1Deg, e.bLink, e.bInterp, e.blendIn, e.locks);
        }

        public static List<ElemParams> of(List<StraightCurveSection.Element> els) {
            List<ElemParams> out = new ArrayList<>(els.size());
            for (StraightCurveSection.Element e : els) out.add(of(e));
            return out;
        }

        public static List<ElemParams> of(StraightCurveSection sc) {
            return of(sc.elements);
        }

        /** §1.1's defaults (D9 / D13): straight 10 m; planar R20 90° left,
         *  dir 0; helical plan R20 90° with b1 := β0 (the entry elevation,
         *  deg), pitch pair unlinked. */
        public static ElemParams defaultOf(StraightCurveSection.Kind kind,
                                           double beta0Deg) {
            switch (kind) {
                case STRAIGHT: return new ElemParams(kind, 10, 0, 0, true, LIN,
                    0, 0, false, LIN, 0, 0);
                case PLANAR: return new ElemParams(kind, 20 * Math.PI / 2,
                    1 / 20.0, 1 / 20.0, true, LIN, 0, 0, false, LIN, 0, 0);
                default: {
                    double b0 = Math.toRadians(beta0Deg);
                    double L = 10 * Math.PI / Math.cos(b0);
                    return new ElemParams(kind, L, 1 / 20.0, 1 / 20.0, true,
                        LIN, 0, beta0Deg, false, LIN, 0, 0);
                }
            }
        }

        public boolean isStraight() { return kind == StraightCurveSection.Kind.STRAIGHT; }
        public boolean isPlanar() { return kind == StraightCurveSection.Kind.PLANAR; }
        public boolean isHelical() { return kind == StraightCurveSection.Kind.HELICAL; }
        /** Constant curvature (the closed-form rows apply). */
        public boolean constantK() { return kLink || k0 == k1; }
        /** The closed rows need a constant κ AND (helix) a linear pitch. */
        public boolean closedForm() {
            return !isHelical() ? constantK()
                : constantK() && (bLink || bInterp == LIN);
        }
        public boolean has(int bit) { return (locks & bit) != 0; }
        /** Signed turn (rad) — PLANAR κ̄·L; HELICAL the plan turn needs
         *  the entry (ElementChain.turnDeg); STRAIGHT 0. */
        public double turnRad() {
            return isPlanar() ? L * (k0 + k1) / 2 : 0;
        }

        public ElemParams withKind(StraightCurveSection.Kind v) {
            return new ElemParams(v, L, k0, k1, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withL(double v) {
            return new ElemParams(kind, v, k0, k1, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        /** Writes k0 (and k1 while linked). */
        public ElemParams withK0(double v) {
            return new ElemParams(kind, L, v, kLink ? v : k1, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withK1(double v) {
            return new ElemParams(kind, L, kLink ? v : k0, v, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        /** Both ends at once (a constant κ). */
        public ElemParams withK(double v) {
            return new ElemParams(kind, L, v, v, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withKLink(boolean v) {
            return new ElemParams(kind, L, k0, v ? k0 : k1, v, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withKInterp(StraightCurveSection.Interp v) {
            return new ElemParams(kind, L, k0, k1, kLink, v, dirDeg, b1Deg, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withDir(double v) {
            return new ElemParams(kind, L, k0, k1, kLink, kInterp, v, b1Deg, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withB1(double v) {
            return new ElemParams(kind, L, k0, k1, kLink, kInterp, dirDeg, v, bLink, bInterp, blendIn, locks);
        }
        public ElemParams withBLink(boolean v) {
            return new ElemParams(kind, L, k0, k1, kLink, kInterp, dirDeg, b1Deg, v, bInterp, blendIn, locks);
        }
        public ElemParams withBInterp(StraightCurveSection.Interp v) {
            return new ElemParams(kind, L, k0, k1, kLink, kInterp, dirDeg, b1Deg, bLink, v, blendIn, locks);
        }
        public ElemParams withBlend(double v) {
            return new ElemParams(kind, L, k0, k1, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, v, locks);
        }
        public ElemParams withLocks(int v) {
            return new ElemParams(kind, L, k0, k1, kLink, kInterp, dirDeg, b1Deg, bLink, bInterp, blendIn, v);
        }

        /** The geometry fields onto an existing element — the length
         *  through {@code setLength} (§2.2's one choke), every other
         *  field verbatim; the roll lane and blend are the element's. */
        public void applyTo(StraightCurveSection.Element e) {
            e.kind = kind;
            e.k0 = k0;
            e.k1 = k1;
            e.kLink = kLink;
            e.kInterp = kInterp;
            e.dirDeg = dirDeg;
            e.b1Deg = b1Deg;
            e.bLink = bLink;
            e.bInterp = bInterp;
            e.locks = locks;
            e.setLength(L);
        }

        /** A fresh element (with §2.1's default lane) carrying these
         *  fields — the list ops' new element and every scratch forward. */
        public StraightCurveSection.Element toElement() {
            StraightCurveSection.Element e;
            switch (kind) {
                case STRAIGHT: e = StraightCurveSection.Element.straight(L); break;
                case PLANAR: e = StraightCurveSection.Element.planar(L, k0, dirDeg); break;
                default: e = StraightCurveSection.Element.helical(L, k0, b1Deg); break;
            }
            e.k0 = k0;
            e.k1 = k1;
            e.kLink = kLink;
            e.kInterp = kInterp;
            e.dirDeg = dirDeg;
            e.b1Deg = b1Deg;
            e.bLink = bLink;
            e.bInterp = bInterp;
            e.blendIn = blendIn;
            e.locks = locks;
            return e;
        }
    }

    /** A frame at an element START (or end) in the section's canonical
     *  coordinates: p, the tangent T, the LEVEL up U (ElementChain's ul —
     *  at the pole the inherited roll-free up) and R = U × T. */
    public record Frame(Vec3 p, Vec3 T, Vec3 U, Vec3 R) {

        public static Frame of(ElementChain.Pose pose) {
            return new Frame(pose.pos(), pose.T(), pose.ul(), pose.rl());
        }

        /** A frame from a position + tangent with the level up (§1.2),
         *  or an inherited up at the pole. */
        public static Frame level(Vec3 p, Vec3 T, Vec3 upIfVertical) {
            Vec3 t = T.normalize();
            double horiz2 = 1 - t.y() * t.y();
            Vec3 ul = horiz2 < StraightCurveSection.HELIX_VERT_EPS
                ? upIfVertical.sub(t.scale(upIfVertical.dot(t))).normalize()
                : new Vec3(-t.y() * t.x(), horiz2, -t.y() * t.z()).normalize();
            return new Frame(p, t, ul, ul.cross(t));
        }

        /** The ElementChain pose (roll-free orientation) of this frame. */
        public ElementChain.Pose pose() {
            return new ElementChain.Pose(p, Quat.fromFrame(T, U), U, R, 0);
        }

        /** The planar axis a = cos d·U + sin d·R. */
        public Vec3 axis(double dirDeg) {
            double d = Math.toRadians(dirDeg);
            return U.scale(Math.cos(d)).add(R.scale(Math.sin(d)));
        }

        /** The initial turning direction N = T × a for dir d. */
        public Vec3 N(double dirDeg) { return T.cross(axis(dirDeg)); }

        /** dir (deg, (−180, 180]) of a unit axis ⊥ T: atan2(a·R, a·U). */
        public double dirOf(Vec3 a) {
            return Math.toDegrees(Math.atan2(a.dot(R), a.dot(U)));
        }

        public boolean vertical() {
            return 1 - T.y() * T.y() < StraightCurveSection.HELIX_VERT_EPS;
        }

        /** Internal heading (deg) atan2(−T.z, T.x); NaN on vertical track. */
        public double headingDeg() {
            return vertical() ? Double.NaN
                : Math.toDegrees(Math.atan2(-T.z(), T.x()));
        }

        public double elevationDeg() {
            return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, T.y()))));
        }
    }

    /** The branch hint — the element's CURRENT (Θ, dir, sign κ, lap k);
     *  every branch pick is nearest to it (TrackPiece.nearestTurn's idiom).
     *  thetaDeg is SIGNED (κ̄·L for a planar, c_h·L_h for a helix). */
    public record Hint(double thetaDeg, double dirDeg, double kSign, int lap) {

        public static Hint of(ElemParams e) {
            return of(e, 0);
        }

        /** With the entry elevation (deg) a helix's plan turn needs. */
        public static Hint of(ElemParams e, double beta0Deg) {
            double th;
            if (e.isHelical()) {
                double b0 = Math.toRadians(beta0Deg);
                double b1 = e.bLink() ? b0 : Math.toRadians(e.b1Deg());
                double sc = sinc((b1 - b0) / 2);
                double Lh = e.L() * Math.cos((b0 + b1) / 2) * sc;
                th = Math.toDegrees(e.k0() * Lh);
            } else {
                th = Math.toDegrees(e.turnRad());
            }
            double ks = e.isStraight() ? 1 : Math.signum(e.k0());
            if (ks == 0) ks = 1;
            return new Hint(th, e.dirDeg(), ks, (int) Math.floor(Math.abs(th) / 360));
        }
    }

    /** A target: a canonical POINT (3 constraints), a TANGENT (heading
     *  and / or elevation, deg, NaN = free) or a POSE (5). Never a ray
     *  (D14). */
    public sealed interface Target permits Target.Point, Target.Tangent, Target.Pose {
        record Point(Vec3 p) implements Target {}
        record Tangent(double headingDeg, double elevationDeg) implements Target {}
        record Pose(Vec3 p, double headingDeg, double elevationDeg) implements Target {}
    }

    /** The answer: the WHOLE section's params by element index (the list
     *  ops change the count — insertedAt / deletedAt say where), the
     *  status, the sentence (non-null iff REFUSED, or PROJECTED with a
     *  notice), the absorb's untaken rotation (deg), the landed end
     *  position of the edited node (canonical) and the bounded solver's
     *  step count (0 for a closed row). */
    public record Solution(List<ElemParams> params, Status status, String why,
                           double residualRot, Vec3 landedPos, int iterations,
                           int insertedAt, int deletedAt) {

        public Solution(List<ElemParams> params, Status status, String why,
                        double residualRot, Vec3 landedPos, int iterations) {
            this(params, status, why, residualRot, landedPos, iterations, -1, -1);
        }

        public boolean refused() { return status == Status.REFUSED; }

        static Solution refuse(List<ElemParams> params, String why) {
            return new Solution(params, Status.REFUSED, why, 0, null, 0);
        }
    }

    /** The compile's LANDED border position for a parameter set — the
     *  predictor's corrector (§4.2) asks the consumer (a compile) for it;
     *  the gates run ScIntegrator directly. */
    public interface LandedProbe {
        Vec3 landed(List<ElemParams> params, int border);
    }

    // =================================================================
    // constants
    // =================================================================

    static final StraightCurveSection.Interp LIN = StraightCurveSection.Interp.LINEAR;
    static final double KAPPA_MAX = StraightCurveSection.KAPPA_MAX;
    static final double KAPPA_INF = StraightCurveSection.KAPPA_INF;
    static final double MIN_LEN = StraightCurveSection.MIN_ELEM_LEN;
    static final double B1_MAX = StraightCurveSection.B1_MAX;
    static final double VERT_EPS = StraightCurveSection.HELIX_VERT_EPS;
    static final int L_LEN = StraightCurveSection.Element.LOCK_LEN,
        L_K0 = StraightCurveSection.Element.LOCK_K0,
        L_K1 = StraightCurveSection.Element.LOCK_K1,
        L_TURN = StraightCurveSection.Element.LOCK_TURN,
        L_DIR = StraightCurveSection.Element.LOCK_DIR,
        L_HEAD = StraightCurveSection.Element.LOCK_HEAD,
        L_ELEV = StraightCurveSection.Element.LOCK_ELEV,
        L_X = StraightCurveSection.Element.LOCK_X,
        L_Y = StraightCurveSection.Element.LOCK_Y,
        L_Z = StraightCurveSection.Element.LOCK_Z,
        L_B1 = StraightCurveSection.Element.LOCK_B1,
        L_XYZ = L_X | L_Y | L_Z;
    /** A target this close (m) to the current end is "already landed":
     *  the solve returns the params unchanged (idempotence). */
    public static final double AT_TARGET = 1e-9;
    /** "Already on the set" relative guard of every projection. */
    static final double ON_SET = 1e-12;
    /** The bounded solvers' caps (D1, §4.11). */
    public static final int NEWTON_MAX = 20, GOLDEN_MAX = 40, BISECT_MAX = 60,
        CORRECTOR_MAX = 3;
    public static final double NEWTON_TOL = 1e-9, BISECT_TOL = 1e-12,
        PREDICTOR_OK_M = 1e-3;
    static final double TWO_PI = 2 * Math.PI;

    // =================================================================
    // small math
    // =================================================================

    static double sinc(double x) { return ElementChain.sinc(x); }

    /** angle + 360·k nearest the hint (deg) — TrackPiece.nearestTurn. */
    static double nearest360(double deg, double hintDeg) {
        return deg + 360 * Math.rint((hintDeg - deg) / 360);
    }

    /** φ0 ∈ [0, 2π) + 2πk nearest |hint| (rad), k ≥ 0 — the lap rule. */
    static double lapTurn(double phi0, double hintAbsRad) {
        double k = Math.rint((hintAbsRad - phi0) / TWO_PI);
        if (k < 0) k = 0;
        return phi0 + TWO_PI * k;
    }

    static double sq(double x) { return x * x; }

    static double clampK(double k) {
        return Math.max(-KAPPA_MAX, Math.min(KAPPA_MAX, k));
    }

    /** J0(x) by its power series (|x| ≤ π/2 + a margin here — the
     *  SINUSOIDAL-pitch helix's seed, §4.3; the forward map stays
     *  ElementChain's quadrature). */
    static double j0(double x) {
        double q = -x * x / 4;
        double term = 1, sum = 1;
        for (int m = 1; m < 40; m++) {
            term *= q / ((double) m * m);
            sum += term;
            if (Math.abs(term) < 1e-18) break;
        }
        return sum;
    }

    /** The unit tangent of (heading, elevation) in internal sense:
     *  T = (cos e cos ψ, sin e, −cos e sin ψ). */
    public static Vec3 tangentOf(double headingDeg, double elevationDeg) {
        double h = Math.toRadians(headingDeg), e = Math.toRadians(elevationDeg);
        return new Vec3(Math.cos(e) * Math.cos(h), Math.sin(e),
            -Math.cos(e) * Math.sin(h));
    }

    // =================================================================
    // §4.3 — forward maps through the ONE evaluator
    // =================================================================

    /** The element's exit frame from its entry frame (ElementChain). */
    public static Frame forwardEnd(Frame f, ElemParams e) {
        return Frame.of(ElementChain.advance(f.pose(), e.toElement()));
    }

    /** The sharp chain: n + 1 frames (index i = the entry of element i). */
    public static Frame[] frames(Frame entry, List<ElemParams> els) {
        Frame[] out = new Frame[els.size() + 1];
        ElementChain.Pose p = entry.pose();
        out[0] = Frame.of(p);
        for (int i = 0; i < els.size(); i++) {
            p = ElementChain.advance(p, els.get(i).toElement());
            out[i + 1] = Frame.of(p);
        }
        return out;
    }

    static List<StraightCurveSection.Element> scratch(List<ElemParams> els) {
        List<StraightCurveSection.Element> out = new ArrayList<>(els.size());
        for (ElemParams e : els) out.add(e.toElement());
        return out;
    }

    // =================================================================
    // the in-plane arc core — every planar row and the helix plan use it
    // =================================================================

    /** One in-plane answer: the arc of SIGNED curvature kappa (toward +N)
     *  and turn theta = kappa·L from the origin with entry tangent (1,0)
     *  ends at (dT, dN) = (sin θ/κ, (1 − cos θ)/κ) — the point actually
     *  landed on (after any projection); exact = no projection ran. */
    static final class Arc2 {
        double kappa, theta, L, dT, dN;
        boolean exact = true;
        /** The row refused outright (null = fine). */
        String why;

        Arc2 set(double kappa, double theta, double L) {
            this.kappa = kappa;
            this.theta = theta;
            this.L = L;
            if (Math.abs(kappa) < KAPPA_INF) {
                dT = L;
                dN = 0;
            } else {
                dT = Math.sin(theta) / kappa;
                dN = (1 - Math.cos(theta)) / kappa;
            }
            return this;
        }
    }

    /** I1 in the plane: free κ, free θ (lap-kept), the target (dT, dN)
     *  with dN SIGNED (the arc bends toward +N for dN > 0). Projections:
     *  the horn-torus core (|κ| > κ_max → the κ_max circle), the backward
     *  ray (dN = 0, dT ≤ 0 → the κ_max circle on the hint's side), the
     *  MIN_ELEM_LEN floor (the point scaled out along its own direction —
     *  φ kept, κ shrinks). */
    static Arc2 inv1(double dT, double dN, double hintAbsTheta, double hintSign) {
        Arc2 a = new Arc2();
        double norm = Math.hypot(dT, dN);
        if (Math.abs(dN) <= ON_SET * norm) {
            if (dT > 0) {
                // the straight-shaped planar: κ = 0 exactly, no laps possible
                a.set(0, 0, dT);
                if (dT < MIN_LEN) {
                    a.set(0, 0, MIN_LEN);
                    a.exact = false;
                }
                return a;
            }
            // the backward ray: project onto the κ_max circle on the
            // hint's side (REACH_RAY_WHY on a typed box)
            double s = hintSign < 0 ? -1 : 1;
            double rho = 1 / KAPPA_MAX;
            double vx = dT, vy = -s * rho;
            double vl = Math.hypot(vx, vy);
            dT = rho * vx / vl;
            dN = s * rho + rho * vy / vl;
            a.exact = false;
            a.why = SectionNames.REACH_RAY_WHY;
            norm = Math.hypot(dT, dN);
        }
        double s = dN < 0 ? -1 : 1;
        double kap = 2 * dN / (dT * dT + dN * dN);
        if (Math.abs(kap) > KAPPA_MAX * (1 + ON_SET)) {
            // the horn-torus core: nearest point on the κ_max circle
            double rho = 1 / KAPPA_MAX;
            double vx = dT, vy = dN - s * rho;
            double vl = Math.hypot(vx, vy);
            if (vl < 1e-300) { vx = 1; vy = 0; vl = 1; }
            dT = rho * vx / vl;
            dN = s * rho + rho * vy / vl;
            kap = s * KAPPA_MAX;
            a.exact = false;
        }
        double phi0 = 2 * Math.atan2(Math.abs(dN), dT);
        double phi = lapTurn(phi0, hintAbsTheta);
        double L = phi / Math.abs(kap);
        if (L < MIN_LEN) {
            // the floor: scale the point out along its own direction —
            // φ stays, κ shrinks by the same factor (closed, continuous)
            double f = MIN_LEN / L;
            dT *= f;
            dN *= f;
            kap /= f;
            L = MIN_LEN;
            a.exact = false;
        }
        a.kappa = kap;
        a.theta = s * phi;
        a.L = L;
        a.dT = dT;
        a.dN = dN;
        return a;
    }

    /** κ LOCKED (signed toward +N), free θ: the circle of radius ρ about
     *  (0, 1/κ) — nearest point, θ lap-continuous. */
    static Arc2 invK(double dT, double dN, double kappa, double hintAbsTheta) {
        Arc2 a = new Arc2();
        double s = kappa < 0 ? -1 : 1;
        double rho = 1 / Math.abs(kappa);
        double cy = s * rho;
        double vx = dT, vy = dN - cy;
        double vl = Math.hypot(vx, vy);
        double qx, qy;
        if (vl <= 1e-300) {
            // the centre: every point is equidistant — the hint's turn
            double phi = hintAbsTheta <= 0 ? Math.PI : hintAbsTheta;
            return a.set(kappa, s * phi, phi * rho);
        }
        if (Math.abs(vl - rho) <= ON_SET * rho) {
            qx = dT;
            qy = dN;
        } else {
            qx = rho * vx / vl;
            qy = cy + rho * vy / vl;
            a.exact = false;
        }
        // q − C = ρ(sin φ, −s cos φ)
        double phi0 = Math.atan2(qx / rho, -s * (qy - cy) / rho);
        if (phi0 < 0) phi0 += TWO_PI;
        double phi = lapTurn(phi0, hintAbsTheta);
        double L = phi * rho;
        if (L < MIN_LEN) {
            // κ locked AND the floor: the point sits at φ = MIN·|κ| — the
            // nearest reachable turn (the landing moves along the circle)
            L = MIN_LEN;
            phi = MIN_LEN / rho;
            a.exact = false;
        }
        a.kappa = kappa;
        a.theta = s * phi;
        a.L = L;
        a.dT = Math.sin(phi) * rho;
        a.dN = s * (1 - Math.cos(phi)) * rho;
        if (a.exact) { a.dT = dT; a.dN = dN; }
        return a;
    }

    /** θ LOCKED (signed), free κ: the cone's in-plane generator at θ/2 —
     *  the target projected onto it, κ = 2 sin(φ_red/2)/chord, clamped
     *  to [floor, κ_max]. */
    static Arc2 invTheta(double dT, double dN, double theta, double kappaHint) {
        Arc2 a = new Arc2();
        double s = theta < 0 ? -1 : theta > 0 ? 1 : (kappaHint < 0 ? -1 : 1);
        double phi = Math.abs(theta);
        double red = phi - TWO_PI * Math.floor(phi / TWO_PI);
        double half = red / 2;
        double sh = Math.sin(half);
        if (phi < 1e-15) {
            // θ = 0: the straight-shaped planar along T; κ = 0, L = dT
            double L = Math.max(MIN_LEN, dT);
            a.set(0, 0, L);
            a.exact = Math.abs(dN) <= ON_SET * Math.hypot(dT, dN) && dT >= MIN_LEN;
            return a;
        }
        if (Math.abs(sh) < 1e-12) {
            // whole laps: the end sits ON the start whatever κ — keep κ
            double k = Math.abs(kappaHint) < KAPPA_INF ? Math.min(KAPPA_MAX, phi / MIN_LEN) : Math.abs(kappaHint);
            a.set(s * k, s * phi, phi / k);
            a.exact = false;
            return a;
        }
        double gx = Math.cos(half), gy = s * sh;
        double chord = dT * gx + dN * gy;
        double chordMin = Math.max(2 * Math.abs(sh) / KAPPA_MAX,
            2 * Math.abs(sh) * MIN_LEN / phi);
        boolean onGen = Math.abs(dT - chord * gx) <= ON_SET * Math.hypot(dT, dN)
            && Math.abs(dN - chord * gy) <= ON_SET * Math.hypot(dT, dN);
        if (chord < chordMin) {
            chord = chordMin;
            a.exact = false;
        } else if (!onGen) {
            a.exact = false;
        }
        double k = 2 * Math.abs(sh) / chord;
        a.kappa = s * k;
        a.theta = s * phi;
        a.L = phi / k;
        if (a.exact) { a.dT = dT; a.dN = dN; }
        else { a.dT = chord * gx; a.dN = chord * gy; }
        return a;
    }

    /** L LOCKED, free κ and θ: the bearing surface — θ = 2·angle(T, Δ)
     *  (lap-kept), κ = θ/L, the node lands on the start → cursor ray at
     *  the chord 2|sin(θ/2)|/|κ|. */
    static Arc2 invL(double dT, double dN, double L, double hintAbsTheta) {
        Arc2 a = new Arc2();
        double norm = Math.hypot(dT, dN);
        if (norm < 1e-300) {
            // the start itself: the full lap nearest the hint
            double phi = lapTurn(0, Math.max(hintAbsTheta, TWO_PI));
            if (phi == 0) phi = TWO_PI;
            double k = Math.min(KAPPA_MAX, phi / L);
            a.set(k, k * L, L);
            a.exact = false;
            return a;
        }
        double s = dN < 0 ? -1 : 1;
        double thetaHalf = Math.atan2(Math.abs(dN), dT);
        double phi = lapTurn(2 * thetaHalf, hintAbsTheta);
        double k = phi / L;
        if (k > KAPPA_MAX) { k = KAPPA_MAX; phi = k * L; }
        if (phi < 1e-15 || k < KAPPA_INF) {
            // dead ahead: the straight of length L
            a.set(0, 0, L);
            a.exact = Math.abs(dN) <= ON_SET * norm && Math.abs(dT - L) <= ON_SET * norm;
            return a;
        }
        double red = phi - TWO_PI * Math.floor(phi / TWO_PI);
        double chord = 2 * Math.abs(Math.sin(red / 2)) / k;
        double ux = dT / norm, uy = dN / norm;
        a.kappa = s * k;
        a.theta = s * phi;
        a.L = L;
        a.exact = Math.abs(norm - chord) <= ON_SET * Math.max(norm, chord)
            && Math.abs(red / 2 - thetaHalf) <= 1e-12;
        if (a.exact) { a.dT = dT; a.dN = dN; }
        else { a.dT = chord * ux; a.dN = chord * uy; }
        return a;
    }

    /** κ AND θ both fixed (any two of κ, θ, L): the in-plane point is
     *  fixed — only the azimuth (3-D) can follow the target. */
    static Arc2 invFixed(double kappa, double theta, double dT, double dN) {
        Arc2 a = new Arc2();
        double L = Math.abs(kappa) < KAPPA_INF ? Math.max(MIN_LEN, Math.abs(theta) < 1e-15 ? dT : MIN_LEN)
            : Math.abs(theta / kappa);
        a.set(kappa, theta, L);
        a.exact = Math.abs(a.dT - dT) <= ON_SET * Math.max(1, Math.hypot(dT, dN))
            && Math.abs(a.dN - dN) <= ON_SET * Math.max(1, Math.hypot(dT, dN));
        return a;
    }

    // =================================================================
    // the lock model (§4.7) — DOF budget
    // =================================================================

    /** The lock set's RANK against the element's DOF: PLANAR (κ, θ, d) —
     *  K0 / TURN / LEN count at most 2 together (θ = κ·L), DIR 1, HEAD /
     *  ELEV 1 each, X / Y / Z 1 each; HELICAL (c_h, Δψ, β1): K0 / TURN /
     *  LEN at most 2, HEAD ≡ TURN (one plan heading), ELEV ≡ B1, X / Y /
     *  Z; STRAIGHT (ℓ): LEN, X / Y / Z (HEAD / ELEV constrain the curve
     *  before it, §4.5 — counted there). */
    public static int lockRank(ElemParams e) {
        int l = e.locks();
        int xyz = bit(l, L_X) + bit(l, L_Y) + bit(l, L_Z);
        if (e.isStraight()) return bit(l, L_LEN) + xyz;
        if (e.isHelical()) {
            int turn = Math.min(1, bit(l, L_TURN) + bit(l, L_HEAD));
            int local = Math.min(2, bit(l, L_K0) + turn + bit(l, L_LEN));
            int elev = Math.min(1, bit(l, L_B1) + bit(l, L_ELEV));
            return local + elev + xyz;
        }
        int local = Math.min(2, bit(l, L_K0) + bit(l, L_TURN) + bit(l, L_LEN));
        return local + bit(l, L_DIR) + bit(l, L_HEAD) + bit(l, L_ELEV) + xyz;
    }

    static int bit(int locks, int b) { return (locks & b) != 0 ? 1 : 0; }

    /** The DOF of the element's end node (PLANAR / HELICAL 3, STRAIGHT 1). */
    public static int dof(ElemParams e) { return e.isStraight() ? 1 : 3; }

    /** May this padlock go ON? null = yes; FULLY_CONSTRAINED_WHY when it
     *  would raise the rank past the DOF (a consistent dependent lock —
     *  K0 + TURN + LEN — is allowed). */
    public static String padlockWhy(ElemParams e, int bitToLock) {
        if (e.has(bitToLock)) return null;
        ElemParams with = e.withLocks(e.locks() | bitToLock);
        return lockRank(with) > dof(e) ? SectionNames.FULLY_CONSTRAINED_WHY : null;
    }

    // =================================================================
    // the curve node (§4.4) — the PLANAR rows
    // =================================================================

    /** The node solve's context: the chain, the element, its entry and
     *  current end frames, the hint. */
    static final class Ctx {
        final List<ElemParams> els;
        final Frame[] fr;
        final int k;
        final ElemParams e;
        final Frame f0, end;
        final Hint hint;
        final Chart chart;

        Ctx(List<ElemParams> els, Frame entry, int k, Chart chart) {
            this.els = els;
            this.fr = frames(entry, els);
            this.k = k;
            this.e = els.get(k);
            this.f0 = fr[k];
            this.end = fr[k + 1];
            this.hint = Hint.of(e, f0.elevationDeg());
            this.chart = chart == null ? Chart.LOCAL : chart;
        }

        double hintAbs() { return Math.toRadians(Math.abs(hint.thetaDeg())); }

        List<ElemParams> with(ElemParams ne) {
            List<ElemParams> out = new ArrayList<>(els);
            out.set(k, ne);
            return out;
        }

        Solution done(ElemParams ne, boolean exact, String notice, int iters) {
            Frame nend = forwardEnd(f0, ne);
            return new Solution(with(ne), exact ? Status.EXACT : Status.PROJECTED,
                exact ? null : notice, 0, nend.p(), iters);
        }

        Solution unchanged() {
            return new Solution(new ArrayList<>(els), Status.EXACT, null, 0,
                end.p(), 0);
        }

        Solution refuse(String why) { return Solution.refuse(new ArrayList<>(els), why); }
    }

    /** For a free dir: the turning direction n̂ = unit(Δ⊥) and the
     *  stored-dir REPRESENTATION nearest the hint — {dirDeg, side}: side
     *  +1 = N is n̂ (κ > 0 bends toward the target), −1 = N is −n̂ (κ < 0
     *  does — the pass-through-straight branch, dir kept). */
    static double[] pickRep(Frame f, Vec3 nhat, Hint h) {
        Vec3 aA = nhat.cross(f.T());
        double raw = f.dirOf(aA);
        double dA = nearest360(raw, h.dirDeg());
        double dB = nearest360(raw + 180, h.dirDeg());
        return Math.abs(dB - h.dirDeg()) < Math.abs(dA - h.dirDeg())
            ? new double[]{ dB, -1 } : new double[]{ dA, 1 };
    }

    /** Δ split against the entry frame: {dT, |Δ⊥|} and n̂ (null when Δ⊥
     *  vanishes). */
    static Vec3 perp(Frame f, Vec3 d) {
        return d.sub(f.T().scale(d.dot(f.T())));
    }

    static ElemParams planarOf(ElemParams e, Arc2 a, double dirDeg) {
        return e.withK(a.kappa).withDir(dirDeg).withL(a.L);
    }

    /** THE PLANAR CURVE-NODE SOLVE for a point target (§4.4's table). */
    static Solution solvePlanar(Ctx c, Vec3 target) {
        ElemParams e = c.e;
        Frame f = c.f0;
        if (c.end.p().sub(target).length() <= AT_TARGET) return c.unchanged();
        int locks = e.locks();
        boolean K = e.has(L_K0) || (e.has(L_TURN) && e.has(L_LEN));
        boolean T = e.has(L_TURN) || (e.has(L_K0) && e.has(L_LEN));
        boolean Ln = e.has(L_LEN);
        boolean D = e.has(L_DIR);
        boolean H = e.has(L_HEAD), E = e.has(L_ELEV);
        int xyz = locks & L_XYZ;
        int rank = lockRank(e);
        if (rank >= 3) {
            return c.refuse(xyz == L_XYZ ? SectionNames.POS_LOCKED_WHY
                : SectionNames.ALL_LOCKED_WHY);
        }
        if (!e.constantK()) return solveRampedPlanar(c, target);
        // a planar at a vertical entry inherits its plane (§1.2): the
        // plane is fixed whether or not DIR is locked — the local rows
        // below run in it like a DIR lock (the `D || f.vertical()` arm)
        double theta = e.turnRad();
        double kappa = e.k0();
        Vec3 delta = target.sub(f.p());
        boolean projected = false;
        String notice = null;

        // ---- x/y/z locks only: the locked affine set, then I1 ----
        if (xyz != 0 && !K && !T && !Ln && !D && !H && !E) {
            Vec3 held = c.end.p();
            Vec3 tp = new Vec3((xyz & L_X) != 0 ? held.x() : target.x(),
                               (xyz & L_Y) != 0 ? held.y() : target.y(),
                               (xyz & L_Z) != 0 ? held.z() : target.z());
            projected = tp.sub(target).length() > ON_SET * Math.max(1, target.length());
            Solution s = i1Free(c, tp);
            return projected && s.status() == Status.EXACT
                ? new Solution(s.params(), Status.PROJECTED, null, 0, s.landedPos(), 0) : s;
        }
        // ---- both global angles: the tangent is fixed → θ, d by I2,
        //      κ free along the ray (closed) ----
        if (H && E && xyz == 0) {
            Vec3 T1 = c.end.T();
            double[] td = inv2(f, T1, c.hint, e);
            if (td == null) return c.refuse(SectionNames.PARALLEL_WHY);
            return rayRow(c, delta, td[0], td[1], false);
        }
        // ---- the mixed row: a global + a local lock, or κ/Θ + x/y/z,
        //      or a lone global (κ held — D14's "κ last") ----
        if (H || E || xyz != 0) {
            if (D && (H || E) && xyz == 0) {
                // dir fixed + a tangent angle: θ from the tangent equation
                // in the fixed plane, then κ free along the Θ+dir ray
                double th = thetaForAngle(f, e.dirDeg(), H ? c.end.headingDeg() : Double.NaN,
                    E ? c.end.elevationDeg() : Double.NaN, Math.toRadians(c.hint.thetaDeg()));
                if (Double.isNaN(th)) return c.refuse(SectionNames.MIXED_LOCK_WHY);
                return rayRow(c, delta, th, e.dirDeg(), false);
            }
            return goldenMixed(c, target);
        }
        // ---- the local rows ----
        if (D || f.vertical()) {
            // the plane (T, N_d): project in, then the 2-D rows
            double dir = e.dirDeg();
            Vec3 a = f.axis(dir), N = f.T().cross(a);
            double dT = delta.dot(f.T()), dN = delta.dot(N), w = delta.dot(a);
            boolean inPlane = Math.abs(w) <= ON_SET * Math.max(1, delta.length());
            Arc2 r;
            if (K && T) return c.refuse(SectionNames.ALL_LOCKED_WHY);
            else if (K) r = invK(dT, dN, kappa, c.hintAbs());
            else if (T) r = invTheta(dT, dN, theta, kappa);
            else if (Ln) r = invL(dT, dN, e.L(), c.hintAbs());
            else r = inv1(dT, dN, c.hintAbs(), c.hint.kSign());
            if (r.why != null) notice = r.why;
            return c.done(planarOf(e, r, dir), inPlane && r.exact, notice, 0);
        }
        if (K && T) {
            // the chord end swept about T: the in-plane point is fixed,
            // the azimuth follows the target
            Vec3 pp = perp(f, delta);
            double dn = pp.length();
            if (dn <= ON_SET * Math.max(1, delta.length())) return c.done(e, false, null, 0);
            double[] rep = pickRep(f, pp.scale(1 / dn), c.hint);
            // the stored κ sign must match the representation's side
            double side = rep[1];
            double kk = Math.abs(kappa) * side;
            double th = Math.abs(theta) * side;
            Arc2 r = invFixed(kk, th, delta.dot(f.T()), dn * side);
            return c.done(planarOf(e, r, rep[0]), false, null, 0);
        }
        if (K || T || Ln) {
            Vec3 pp = perp(f, delta);
            double dn = pp.length();
            double dT = delta.dot(f.T());
            double dir;
            double side;
            if (dn <= ON_SET * Math.max(1, delta.length())) {
                dir = e.dirDeg();
                side = c.hint.kSign();
                dn = 0;
            } else {
                double[] rep = pickRep(f, pp.scale(1 / dn), c.hint);
                dir = rep[0];
                side = rep[1];
            }
            Arc2 r;
            if (K) {
                // κ locked: its sign is the stored one — the side of the
                // circle follows κ, the azimuth the target
                double kk = Math.abs(kappa) * side;
                r = invK(dT, dn * side, kk, c.hintAbs());
                if (Math.signum(kappa) != Math.signum(kk)) {
                    // the stored sign disagrees with the nearest rep: use
                    // the rep that keeps κ's sign (dir + 180)
                    dir = nearest360(dir + 180, c.hint.dirDeg());
                    r = invK(dT, -dn * side, kappa, c.hintAbs());
                }
            } else if (T) {
                double th = Math.abs(theta) * side;
                r = invTheta(dT, dn * side, th, kappa);
            } else {
                r = invL(dT, dn * side, e.L(), c.hintAbs());
            }
            return c.done(planarOf(e, r, dir), r.exact, null, 0);
        }
        return i1Free(c, target);
    }

    /** I1 with a free dir (nothing locked): the azimuth of Δ⊥ picks the
     *  plane, the representation nearest the hint picks (dir, sign κ). */
    static Solution i1Free(Ctx c, Vec3 target) {
        Frame f = c.f0;
        Vec3 delta = target.sub(f.p());
        Vec3 pp = perp(f, delta);
        double dn = pp.length();
        double dT = delta.dot(f.T());
        if (dn <= ON_SET * Math.max(1, delta.length())) {
            // on the axis: straight ahead (κ = 0, dir kept) or the backward
            // ray (projected onto the κ_max torus in the hint's plane)
            Arc2 r = inv1(dT, 0, c.hintAbs(), c.hint.kSign());
            if (dT > 0) {
                return c.done(planarOf(c.e, r, c.e.dirDeg()), r.exact, null, 0);
            }
            // behind: the hint's own plane
            double dir = c.e.dirDeg();
            return c.done(planarOf(c.e, r, dir), false, r.why, 0);
        }
        double[] rep = pickRep(f, pp.scale(1 / dn), c.hint);
        Arc2 r = inv1(dT, dn * rep[1], c.hintAbs(), rep[1]);
        return c.done(planarOf(c.e, r, rep[0]), r.exact, r.why, 0);
    }

    /** θ and dir fixed, κ free: the ray p0 + C/κ with C = T sin θ +
     *  N (1 − cos θ) — orthogonal projection, κ clamped to (0, κ_max] on
     *  θ's sign. */
    static Solution rayRow(Ctx c, Vec3 delta, double theta, double dirDeg,
                           boolean exactIfOnRay) {
        Frame f = c.f0;
        Vec3 N = f.N(dirDeg);
        if (Math.abs(theta) < 1e-15) {
            // a straight-shaped planar: only the length moves
            double L = Math.max(MIN_LEN, delta.dot(f.T()));
            boolean on = perp(f, delta).length() <= ON_SET * Math.max(1, delta.length())
                && delta.dot(f.T()) >= MIN_LEN;
            return c.done(c.e.withK(0).withDir(dirDeg).withL(L), on, null, 0);
        }
        Vec3 C = f.T().scale(Math.sin(theta)).add(N.scale(1 - Math.cos(theta)));
        double c2 = C.dot(C);
        double t = delta.dot(C) / c2;   // = 1/κ
        boolean exact = delta.sub(C.scale(t)).length() <= ON_SET * Math.max(1, delta.length());
        double s = theta < 0 ? -1 : 1;
        double kap;
        if (t * s <= 0) { kap = s * KAPPA_MAX; exact = false; }
        else {
            kap = 1 / t;
            if (Math.abs(kap) > KAPPA_MAX) { kap = s * KAPPA_MAX; exact = false; }
        }
        double L = Math.abs(theta / kap);
        if (L < MIN_LEN) { L = MIN_LEN; kap = theta / MIN_LEN; exact = false; }
        return c.done(c.e.withK(kap).withDir(dirDeg).withL(L), exact, null, 0);
    }

    /** §4.4 I2 — the (θ, dir) pair that turns T onto T1 with κ KEPT:
     *  {thetaRad, dirDeg} in the representation nearest the hint; null
     *  when T1 == T and the nearest branch is the zero turn
     *  (PARALLEL_WHY). Antiparallel: dir kept, θ ≡ π. */
    static double[] inv2(Frame f, Vec3 T1, Hint h, ElemParams e) {
        Vec3 T = f.T();
        Vec3 cr = T1.cross(T);
        double s = cr.length(), cs = T.dot(T1);
        double hintTh = h.thetaDeg();
        if (cs < -1 + 1e-12) {
            double th = nearest360(180, hintTh);
            return new double[]{ Math.toRadians(th), e.dirDeg() };
        }
        if (s < 1e-15) {
            double th = nearest360(0, hintTh);
            if (th == 0) return null;
            return new double[]{ Math.toRadians(th), e.dirDeg() };
        }
        double phi0 = Math.toDegrees(Math.atan2(s, cs));
        double raw = f.dirOf(cr.scale(1 / s));
        double dA = nearest360(raw, h.dirDeg());
        double dB = nearest360(raw + 180, h.dirDeg());
        if (Math.abs(dB - h.dirDeg()) < Math.abs(dA - h.dirDeg())) {
            return new double[]{ Math.toRadians(nearest360(-phi0, hintTh)), dB };
        }
        return new double[]{ Math.toRadians(nearest360(phi0, hintTh)), dA };
    }

    /** θ (rad) from a tangent angle in a FIXED plane: the end tangent
     *  T cos θ + N sin θ has heading ψ (heading equation) or elevation
     *  (elevation equation); the root nearest the hint; NaN = none. */
    static double thetaForAngle(Frame f, double dirDeg, double headingDeg,
                                double elevationDeg, double hintTheta) {
        Vec3 T = f.T(), N = f.N(dirDeg);
        double A, B, C;
        if (!Double.isNaN(headingDeg)) {
            double psi = Math.toRadians(headingDeg);
            double sp = Math.sin(psi), cp = Math.cos(psi);
            A = T.x() * sp + T.z() * cp;
            B = N.x() * sp + N.z() * cp;
            // A cos θ + B sin θ = 0
            double th0 = Math.atan2(-A, B);
            double best = Double.NaN, bd = Double.MAX_VALUE;
            for (int k = -3; k <= 3; k++) {
                for (double cand : new double[]{ th0 + k * TWO_PI, th0 + Math.PI + k * TWO_PI }) {
                    Vec3 T1 = T.scale(Math.cos(cand)).add(N.scale(Math.sin(cand)));
                    if (T1.x() * cp - T1.z() * sp <= 0) continue;   // the forward branch
                    double d = Math.abs(cand - hintTheta);
                    if (d < bd) { bd = d; best = cand; }
                }
            }
            return best;
        }
        double se = Math.sin(Math.toRadians(elevationDeg));
        A = T.y();
        B = N.y();
        C = se;
        double R = Math.hypot(A, B);
        if (R < 1e-15 || Math.abs(C) > R * (1 + 1e-12)) return Double.NaN;
        double base = Math.atan2(B, A), off = Math.acos(Math.max(-1, Math.min(1, C / R)));
        double best = Double.NaN, bd = Double.MAX_VALUE;
        for (int k = -3; k <= 3; k++) {
            for (double cand : new double[]{ base + off + k * TWO_PI, base - off + k * TWO_PI }) {
                double d = Math.abs(cand - hintTheta);
                if (d < bd) { bd = d; best = cand; }
            }
        }
        return best;
    }

    // ---- the mixed-lock row: §4.11's golden section ----

    /** One constraint of the mixed row: a tangent angle (HEAD / ELEV)
     *  or a held canonical coordinate (X / Y / Z). */
    record Constraint(int bit, double value) {}

    /** The free chart parameter u, the inner closed solves, the golden
     *  section (≤ GOLDEN_MAX evaluations) on |p(u) − target|². */
    static Solution goldenMixed(Ctx c, Vec3 target) {
        ElemParams e = c.e;
        Frame f = c.f0;
        boolean K = e.has(L_K0) || (e.has(L_TURN) && e.has(L_LEN));
        boolean T = e.has(L_TURN) || (e.has(L_K0) && e.has(L_LEN));
        boolean Ln = e.has(L_LEN);
        boolean D = e.has(L_DIR);
        List<Constraint> cons = new ArrayList<>();
        Vec3 held = c.end.p();
        if (e.has(L_HEAD)) cons.add(new Constraint(L_HEAD, c.end.headingDeg()));
        if (e.has(L_ELEV)) cons.add(new Constraint(L_ELEV, c.end.elevationDeg()));
        if (e.has(L_X)) cons.add(new Constraint(L_X, held.x()));
        if (e.has(L_Y)) cons.add(new Constraint(L_Y, held.y()));
        if (e.has(L_Z)) cons.add(new Constraint(L_Z, held.z()));
        if (e.has(L_HEAD) && Double.isNaN(c.end.headingDeg())) return c.refuse(SectionNames.VERTICAL_WHY);
        Constraint dcons = cons.size() == 1 && !D ? degenerateCons(f, cons) : null;
        if (dcons != null) return degenerateMixed(c, target, dcons);
        boolean lengthParam = T;   // θ fixed: u = L (κ = θ/u)
        double theta0 = e.turnRad(), kappa0 = e.k0(), L0 = e.L();
        // κ held unless a constraint can set it (D locked + a coordinate)
        boolean kappaFromCoord = D && !K && !Ln && hasCoord(cons);
        double lo, hi;
        if (lengthParam) { lo = MIN_LEN; hi = Math.max(4 * L0, MIN_LEN + 1); }
        else { lo = theta0 - Math.PI / 2; hi = theta0 + Math.PI / 2; }
        final double[] best = { Double.MAX_VALUE, 0, 0, 0 };   // f, κ, θ, dir
        final int[] evals = { 0 };
        java.util.function.DoubleUnaryOperator fu = u -> {
            evals[0]++;
            double th = lengthParam ? theta0 : u;
            double kap = lengthParam ? theta0 / u : K ? kappa0 : Ln ? th / L0 : kappa0;
            double bestF = Double.MAX_VALUE, bk = kap, bd = e.dirDeg();
            double[] dirs;
            if (D) dirs = new double[]{ e.dirDeg() };
            else {
                Constraint tc = tangentCons(cons);
                if (tc != null) dirs = dirsForAngle(f, th, tc);
                else {
                    Constraint cc = coordCons(cons);
                    if (cc != null && !kappaFromCoord) dirs = dirsForCoord(f, th, kap, cc);
                    else dirs = new double[]{ azimuthDir(f, target, c.hint) };
                }
            }
            for (double dd : dirs) {
                if (Double.isNaN(dd)) continue;
                double kk = kap;
                Constraint cc = coordCons(cons);
                if (cc != null && (D || tangentCons(cons) != null) && !K && !Ln && !lengthParam) {
                    kk = kappaForCoord(f, th, dd, cc);
                    if (Double.isNaN(kk)) continue;
                }
                Vec3 p = planarEnd(f, kk, th, dd);
                double ff = p.sub(target).dot(p.sub(target));
                if (ff < bestF) { bestF = ff; bk = kk; bd = dd; }
            }
            if (bestF < best[0]) { best[0] = bestF; best[1] = bk; best[2] = th; best[3] = bd; }
            return bestF;
        };
        golden(fu, lo, hi, GOLDEN_MAX);
        if (best[0] == Double.MAX_VALUE) return c.refuse(SectionNames.MIXED_LOCK_WHY);
        double kap = best[1], th = best[2], dir = best[3];
        if (Math.abs(kap) > KAPPA_MAX || (Math.abs(kap) < KAPPA_INF && Math.abs(th) > 1e-12)) {
            return c.refuse(SectionNames.MIXED_LOCK_WHY);
        }
        double L = Math.abs(kap) < KAPPA_INF ? L0 : Math.abs(th / kap);
        if (L < MIN_LEN) return c.refuse(SectionNames.MIXED_LOCK_WHY);
        ElemParams ne = e.withK(Math.abs(kap) < KAPPA_INF ? 0 : kap).withDir(nearest360(dir, c.hint.dirDeg())).withL(L);
        Frame nend = forwardEnd(f, ne);
        boolean exact = nend.p().sub(target).length() <= 1e-9;
        return new Solution(c.with(ne), exact ? Status.EXACT : Status.PROJECTED, null,
            0, nend.p(), evals[0]);
    }

    static boolean hasCoord(List<Constraint> cons) { return coordCons(cons) != null; }

    /** A constraint that does not depend on dir: a coordinate whose axis
     *  is parallel to T, or a heading whose plan direction is parallel to
     *  T (a quarter turn away) — θ is then set by the constraint alone
     *  and dir runs free on the end-point circle about p0 + α·T. */
    static Constraint degenerateCons(Frame f, List<Constraint> cons) {
        for (Constraint x : cons) {
            if (x.bit == L_X || x.bit == L_Y || x.bit == L_Z) {
                if (Math.abs(comp(f.T(), x.bit)) > 1 - 1e-12) return x;
            } else if (x.bit == L_HEAD) {
                double psi = Math.toRadians(x.value);
                Vec3 w = new Vec3(Math.sin(psi), 0, Math.cos(psi));
                if (Math.abs(f.T().dot(w)) > 1 - 1e-12) return x;
            }
        }
        return null;
    }

    /** The degenerate mixed row: θ from the constraint (closed per lock
     *  set), κ per the locks (held when free — D14), dir = the azimuth of
     *  the target about the circle's centre; the heading's forward branch
     *  mirrors the azimuth across the branch plane when it falls behind. */
    static Solution degenerateMixed(Ctx c, Vec3 target, Constraint x) {
        ElemParams e = c.e;
        Frame f = c.f0;
        boolean K = e.has(L_K0) || (e.has(L_TURN) && e.has(L_LEN));
        boolean T = e.has(L_TURN) || (e.has(L_K0) && e.has(L_LEN));
        boolean Ln = e.has(L_LEN);
        double theta0 = e.turnRad(), kappa0 = e.k0(), L0 = e.L();
        double hintTh = c.hint.thetaDeg();
        double theta, kap;
        if (x.bit == L_HEAD) {
            if (T) {
                if (Math.abs(Math.cos(theta0)) > 1e-9) return c.refuse(SectionNames.MIXED_LOCK_WHY);
                theta = theta0;
            } else {
                double c1 = nearest360(90, hintTh), c2 = nearest360(-90, hintTh);
                theta = Math.toRadians(Math.abs(c1 - hintTh) <= Math.abs(c2 - hintTh) ? c1 : c2);
            }
            kap = Ln && !K ? theta / L0 : kappa0;
        } else {
            double tx = comp(f.T(), x.bit);
            double rhs = (x.value - comp(f.p(), x.bit)) / tx;   // = sin θ/κ
            if (T) {
                theta = theta0;
                if (Math.abs(Math.sin(theta)) < 1e-15 || Math.abs(rhs) < 1e-15) return c.refuse(SectionNames.MIXED_LOCK_WHY);
                kap = Math.sin(theta) / rhs;
            } else if (Ln && !K) {
                final double R = rhs;
                java.util.function.DoubleUnaryOperator g = th -> (Math.abs(th) < 1e-15 ? L0 : Math.sin(th) * L0 / th) - R;
                double root = bisect(g, theta0 - Math.PI / 2, theta0 + Math.PI / 2);
                if (Double.isNaN(root)) return c.refuse(SectionNames.MIXED_LOCK_WHY);
                theta = root;
                kap = theta / L0;
            } else {
                double sv = kappa0 * rhs;
                if (Math.abs(kappa0) < KAPPA_INF || Math.abs(sv) > 1) return c.refuse(SectionNames.MIXED_LOCK_WHY);
                double a1 = Math.toDegrees(Math.asin(sv));
                double c1 = nearest360(a1, hintTh), c2 = nearest360(180 - a1, hintTh);
                theta = Math.toRadians(Math.abs(c1 - hintTh) <= Math.abs(c2 - hintTh) ? c1 : c2);
                kap = kappa0;
            }
        }
        if (Math.abs(kap) > KAPPA_MAX || Math.abs(kap) < KAPPA_INF) return c.refuse(SectionNames.MIXED_LOCK_WHY);
        double L = Math.abs(theta / kap);
        if (L < MIN_LEN) return c.refuse(SectionNames.MIXED_LOCK_WHY);
        double alpha = Math.sin(theta) / kap, beta = (1 - Math.cos(theta)) / kap;
        Vec3 centre = f.p().add(f.T().scale(alpha));
        Vec3 v = perp(f, target.sub(centre));
        double vl = v.length();
        double dir;
        if (vl < 1e-15) {
            dir = e.dirDeg();
        } else {
            Vec3 want = v.scale(1 / vl);
            if (beta < 0) want = want.scale(-1);
            dir = nearest360(f.dirOf(want.cross(f.T())), c.hint.dirDeg());
            if (x.bit == L_HEAD) {
                double psi = Math.toRadians(x.value);
                Vec3 wp = new Vec3(Math.cos(psi), 0, -Math.sin(psi));
                Vec3 N = f.N(dir);
                if (Math.sin(theta) * N.dot(wp) <= 0) {
                    Vec3 Nm = N.sub(wp.scale(2 * N.dot(wp))).normalize();
                    dir = nearest360(f.dirOf(Nm.cross(f.T())), c.hint.dirDeg());
                }
            }
        }
        ElemParams ne = e.withK(kap).withDir(dir).withL(L);
        Frame nend = forwardEnd(f, ne);
        boolean exact = nend.p().sub(target).length() <= 1e-9;
        return new Solution(c.with(ne), exact ? Status.EXACT : Status.PROJECTED, null, 0, nend.p(), 0);
    }

    static Constraint tangentCons(List<Constraint> cons) {
        for (Constraint x : cons) if (x.bit == L_HEAD || x.bit == L_ELEV) return x;
        return null;
    }

    static Constraint coordCons(List<Constraint> cons) {
        for (Constraint x : cons) if (x.bit == L_X || x.bit == L_Y || x.bit == L_Z) return x;
        return null;
    }

    /** The closed planar end point p0 + (sin θ/κ) T + ((1 − cos θ)/κ) N_d
     *  (κ → 0: the straight of length L = θ/κ … held via sinc forms). */
    static Vec3 planarEnd(Frame f, double kappa, double theta, double dirDeg) {
        Vec3 N = f.N(dirDeg);
        if (Math.abs(kappa) < KAPPA_INF) {
            return f.p().add(f.T().scale(Math.abs(theta) < 1e-15 ? 0 : theta / kappa));
        }
        return f.p().add(f.T().scale(Math.sin(theta) / kappa))
            .add(N.scale((1 - Math.cos(theta)) / kappa));
    }

    /** N_d = −cos d·R + sin d·U; its components are linear in (cos d,
     *  sin d): A cos d + B sin d = C → the two roots (NaN = none). */
    static double[] solveTrig(double A, double B, double C) {
        double R = Math.hypot(A, B);
        if (R < 1e-15) return new double[]{ Double.NaN, Double.NaN };
        double ratio = C / R;
        if (Math.abs(ratio) > 1 + 1e-12) return new double[]{ Double.NaN, Double.NaN };
        ratio = Math.max(-1, Math.min(1, ratio));
        double base = Math.atan2(B, A), off = Math.acos(ratio);
        return new double[]{ Math.toDegrees(base + off), Math.toDegrees(base - off) };
    }

    /** dir (deg) roots making the end tangent's heading / elevation equal
     *  the constraint at turn θ. */
    static double[] dirsForAngle(Frame f, double theta, Constraint tc) {
        Vec3 T = f.T(), U = f.U(), R = f.R();
        double st = Math.sin(theta), ct = Math.cos(theta);
        if (tc.bit == L_HEAD) {
            double psi = Math.toRadians(tc.value);
            double sp = Math.sin(psi), cp = Math.cos(psi);
            double Rpsi = R.x() * sp + R.z() * cp, Upsi = U.x() * sp + U.z() * cp;
            double A = -st * Rpsi, B = st * Upsi, C = -ct * (T.x() * sp + T.z() * cp);
            double[] r = solveTrig(A, B, C);
            // keep the forward branch only
            for (int i = 0; i < 2; i++) {
                if (Double.isNaN(r[i])) continue;
                Vec3 N = f.N(r[i]);
                Vec3 T1 = T.scale(ct).add(N.scale(st));
                if (T1.x() * cp - T1.z() * sp <= 0) r[i] = Double.NaN;
            }
            return r;
        }
        double se = Math.sin(Math.toRadians(tc.value));
        double A = -st * R.y(), B = st * U.y(), C = se - T.y() * ct;
        return solveTrig(A, B, C);
    }

    /** dir (deg) roots putting the end point's coordinate on the held
     *  value at (κ, θ). */
    static double[] dirsForCoord(Frame f, double theta, double kappa, Constraint cc) {
        double alpha, beta;
        if (Math.abs(kappa) < KAPPA_INF) { alpha = Math.abs(theta) < 1e-15 ? 0 : theta / kappa; beta = 0; }
        else { alpha = Math.sin(theta) / kappa; beta = (1 - Math.cos(theta)) / kappa; }
        double Tx = comp(f.T(), cc.bit), Ux = comp(f.U(), cc.bit), Rx = comp(f.R(), cc.bit),
            px = comp(f.p(), cc.bit);
        double A = -beta * Rx, B = beta * Ux, C = cc.value - px - alpha * Tx;
        return solveTrig(A, B, C);
    }

    /** κ from a coordinate constraint at fixed (θ, d): linear in 1/κ. */
    static double kappaForCoord(Frame f, double theta, double dirDeg, Constraint cc) {
        Vec3 N = f.N(dirDeg);
        double g = comp(f.T(), cc.bit) * Math.sin(theta) + comp(N, cc.bit) * (1 - Math.cos(theta));
        double rhs = cc.value - comp(f.p(), cc.bit);
        if (Math.abs(g) < 1e-15) return Double.NaN;
        double inv = rhs / g;
        if (inv == 0) return Double.NaN;
        return 1 / inv;
    }

    static double comp(Vec3 v, int bit) {
        return bit == L_X ? v.x() : bit == L_Y ? v.y() : v.z();
    }

    static double azimuthDir(Frame f, Vec3 target, Hint h) {
        Vec3 pp = perp(f, target.sub(f.p()));
        double dn = pp.length();
        if (dn < 1e-15) return h.dirDeg();
        return pickRep(f, pp.scale(1 / dn), h)[0];
    }

    /** Golden-section minimisation on [lo, hi] with exactly maxEvals
     *  function evaluations (deterministic); returns the best u seen. */
    static double golden(java.util.function.DoubleUnaryOperator fu, double lo, double hi,
                         int maxEvals) {
        final double g = (Math.sqrt(5) - 1) / 2;
        double a = lo, b = hi;
        double x1 = b - g * (b - a), x2 = a + g * (b - a);
        double f1 = fu.applyAsDouble(x1), f2 = fu.applyAsDouble(x2);
        int evals = 2;
        while (evals < maxEvals) {
            if (f1 < f2) {
                b = x2; x2 = x1; f2 = f1;
                x1 = b - g * (b - a);
                f1 = fu.applyAsDouble(x1);
            } else {
                a = x1; x1 = x2; f1 = f2;
                x2 = a + g * (b - a);
                f2 = fu.applyAsDouble(x2);
            }
            evals++;
        }
        return f1 < f2 ? x1 : x2;
    }

    // ---- the ramped planar: §4.11's Newton on (k1, L) ----

    /** dir closed from Δ⊥ (the representation nearest the hint), k0 held
     *  (continuity with the element before), unknowns (k1, L); residual =
     *  the in-plane end position; seed = the constant-κ closed form at
     *  κ̄ = (k0 + k1)/2. Failure → PROJECTED to the seed + RAMP_SOLVE_WHY. */
    static Solution solveRampedPlanar(Ctx c, Vec3 target) {
        ElemParams e = c.e;
        Frame f = c.f0;
        Vec3 delta = target.sub(f.p());
        Vec3 pp = perp(f, delta);
        double dn = pp.length();
        double dir;
        double side;
        if (e.has(L_DIR) || f.vertical() || dn <= ON_SET * Math.max(1, delta.length())) {
            dir = e.dirDeg();
            side = 1;
        } else {
            double[] rep = pickRep(f, pp.scale(1 / dn), c.hint);
            dir = rep[0];
            side = rep[1];
        }
        Vec3 a = f.axis(dir), N = f.T().cross(a);
        double dT = delta.dot(f.T()), dN = delta.dot(N);
        boolean k1Free = !e.has(L_K1);
        boolean LFree = !e.has(L_LEN);
        if (!k1Free && !LFree) return c.refuse(SectionNames.ALL_LOCKED_WHY);
        Arc2 seedArc = inv1(dT, dN, c.hintAbs(), side);
        double k0 = e.k0();
        double k1Seed = k1Free ? 2 * seedArc.kappa - k0 : e.k1();
        double LSeed = LFree ? seedArc.L : e.L();
        ElemParams seed = e.withK1(clampK(k1Seed)).withDir(dir).withL(Math.max(MIN_LEN, LSeed));
        final ElemParams base = seed;
        int n = (k1Free ? 1 : 0) + (LFree ? 1 : 0);
        double[] x = new double[n];
        int i = 0;
        if (k1Free) x[i++] = seed.k1();
        if (LFree) x[i] = seed.L();
        java.util.function.Function<double[], ElemParams> mk = v -> {
            int j = 0;
            ElemParams p = base;
            if (k1Free) p = p.withK1(v[j++]);
            if (LFree) p = p.withL(Math.max(MIN_LEN, v[j]));
            return p;
        };
        java.util.function.Function<double[], double[]> res = v -> {
            Frame nd = forwardEnd(f, mk.apply(v));
            Vec3 d2 = nd.p().sub(f.p());
            if (n == 2) return new double[]{ d2.dot(f.T()) - dT, d2.dot(N) - dN };
            // one unknown: the in-plane distance along the free direction
            Vec3 tgt = f.p().add(f.T().scale(dT)).add(N.scale(dN));
            return new double[]{ nd.p().sub(tgt).length() };
        };
        int[] steps = { 0 };
        boolean ok;
        if (n == 2) {
            // the element's CURRENT (k1, L) first — a drag's hint is near
            // the answer; the constant-κ closed form when that fails
            double[] xh = { e.k1(), e.L() };
            int[] sh = { 0 };
            ok = newton(res, xh, sh);
            if (ok) { x = xh; steps[0] = sh[0]; }
            else ok = newton(res, x, steps);
        } else {
            ok = golden1(res, x, k1Free ? -KAPPA_MAX : MIN_LEN,
                k1Free ? KAPPA_MAX : 4 * Math.max(e.L(), MIN_LEN), steps);
        }
        if (!ok) {
            Frame se = forwardEnd(f, seed);
            return new Solution(c.with(seed), Status.PROJECTED, SectionNames.RAMP_SOLVE_WHY, 0,
                se.p(), steps[0]);
        }
        ElemParams ne = mk.apply(x);
        if (Math.abs(ne.k1()) > KAPPA_MAX || ne.L() < MIN_LEN) {
            Frame se = forwardEnd(f, seed);
            return new Solution(c.with(seed), Status.PROJECTED, SectionNames.RAMP_SOLVE_WHY, 0,
                se.p(), steps[0]);
        }
        Frame nend = forwardEnd(f, ne);
        boolean exact = n == 2 && nend.p().sub(target).length() <= 1e-9;
        return new Solution(c.with(ne), exact ? Status.EXACT : Status.PROJECTED,
            null, 0, nend.p(), steps[0]);
    }

    /** Damped Newton, n unknowns = n residuals, central differences
     *  h = 1e-6, halving ≤ 6, ≤ NEWTON_MAX steps to |r| < NEWTON_TOL. */
    static boolean newton(java.util.function.Function<double[], double[]> res, double[] x,
                          int[] steps) {
        int n = x.length;
        double[] r = res.apply(x);
        double nr = norm(r);
        for (int it = 0; it < NEWTON_MAX; it++) {
            if (nr < NEWTON_TOL) { steps[0] = it; return true; }
            double[][] J = new double[n][n];
            for (int j = 0; j < n; j++) {
                double h = 1e-6 * Math.max(1, Math.abs(x[j]));
                double[] xp = x.clone(), xm = x.clone();
                xp[j] += h;
                xm[j] -= h;
                double[] rp = res.apply(xp), rm = res.apply(xm);
                for (int i = 0; i < n; i++) J[i][j] = (rp[i] - rm[i]) / (2 * h);
            }
            double[] dx = solveLinear(J, r);
            if (dx == null) { steps[0] = it + 1; return false; }
            double lam = 1;
            boolean improved = false;
            for (int hv = 0; hv <= 6; hv++) {
                double[] xn = new double[n];
                for (int i = 0; i < n; i++) xn[i] = x[i] - lam * dx[i];
                double[] rn = res.apply(xn);
                double nn = norm(rn);
                if (nn < nr) {
                    System.arraycopy(xn, 0, x, 0, n);
                    r = rn;
                    nr = nn;
                    improved = true;
                    break;
                }
                lam /= 2;
            }
            if (!improved) { steps[0] = it + 1; return false; }
            steps[0] = it + 1;
        }
        return nr < NEWTON_TOL;
    }

    /** A one-unknown bounded minimisation (golden, ≤ GOLDEN_MAX) of the
     *  residual's norm — the under-determined ramped rows. */
    static boolean golden1(java.util.function.Function<double[], double[]> res, double[] x,
                           double lo, double hi, int[] steps) {
        final double[] best = { Double.MAX_VALUE, x[0] };
        golden(u -> {
            double v = norm(res.apply(new double[]{ u }));
            if (v < best[0]) { best[0] = v; best[1] = u; }
            return v;
        }, lo, hi, GOLDEN_MAX);
        x[0] = best[1];
        steps[0] = GOLDEN_MAX;
        return best[0] < Double.MAX_VALUE;
    }

    static double norm(double[] r) {
        double s = 0;
        for (double v : r) s += v * v;
        return Math.sqrt(s);
    }

    /** Gaussian elimination with partial pivoting (n ≤ 3); null when
     *  singular. */
    static double[] solveLinear(double[][] A, double[] b) {
        int n = b.length;
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int i = col + 1; i < n; i++) if (Math.abs(M[i][col]) > Math.abs(M[piv][col])) piv = i;
            if (Math.abs(M[piv][col]) < 1e-300) return null;
            double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
            for (int i = col + 1; i < n; i++) {
                double fct = M[i][col] / M[col][col];
                for (int j = col; j <= n; j++) M[i][j] -= fct * M[col][j];
            }
        }
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double s = M[i][n];
            for (int j = i + 1; j < n; j++) s -= M[i][j] * x[j];
            x[i] = s / M[i][i];
        }
        return x;
    }

    // =================================================================
    // rings and arrows (§4.4's last rows)
    // =================================================================

    /** A ring write on the end node of element k: TURN (deg, signed —
     *  κ, dir kept, L = |Θ|/|κ|; κ = 0: L kept, κ := Θ/L), DIR (deg —
     *  κ, Θ kept, the node orbits T), HEADING / ELEVATION (deg — I2: κ
     *  kept, Θ and dir from the new tangent, the other angle kept). A
     *  STRAIGHT's ring acts on the nearest curve before it (§4.5);
     *  none before: NO_CURVE_BEFORE_WHY. */
    public static Solution solveRing(List<ElemParams> els, Frame entry, int k,
                                     Ring ring, double valueDeg, Chart chart) {
        int j = k;
        if (els.get(k).isStraight()) {
            j = prevCurve(els, k);
            if (j < 0) return Solution.refuse(new ArrayList<>(els), SectionNames.NO_CURVE_BEFORE_WHY);
            if (ring == Ring.TURN || ring == Ring.DIR) {
                // local rings belong to the curve itself
            }
        }
        Ctx c = new Ctx(els, entry, j, chart);
        ElemParams e = c.e;
        if (e.isHelical()) return solveHelixRing(c, ring, valueDeg);
        if (!e.constantK()) return c.refuse(SectionNames.RAMP_SOLVE_WHY);
        switch (ring) {
            case TURN: {
                if (e.has(L_TURN)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                double th = Math.toRadians(valueDeg);
                double kap = e.k0();
                ElemParams ne;
                if (Math.abs(kap) < KAPPA_INF) {
                    ne = e.withK(th / e.L());
                } else {
                    if (e.has(L_LEN)) ne = e.withK(th / e.L());
                    else {
                        double kk = Math.signum(th) == 0 ? kap : Math.signum(th) * Math.abs(kap);
                        double L = Math.abs(th) < 1e-15 ? e.L() : Math.abs(th / kk);
                        if (L < MIN_LEN) { L = MIN_LEN; kk = th / MIN_LEN; }
                        ne = e.withK(kk).withL(L);
                    }
                }
                return c.done(ne, true, null, 0);
            }
            case DIR: {
                if (e.has(L_DIR)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (c.f0.vertical()) return c.refuse(SectionNames.DIR_INHERITED_WHY);
                return c.done(e.withDir(valueDeg), true, null, 0);
            }
            default: {
                boolean head = ring == Ring.HEADING;
                if (head && c.end.vertical()) return c.refuse(SectionNames.VERTICAL_WHY);
                if (e.has(head ? L_HEAD : L_ELEV)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                double h = head ? valueDeg : c.end.headingDeg();
                double el = head ? c.end.elevationDeg() : Math.max(-90, Math.min(90, valueDeg));
                if (Double.isNaN(h)) h = 0;
                Vec3 T1 = tangentOf(h, el);
                return tangentRow(c, T1);
            }
        }
    }

    /** I2 applied: κ kept (LEN locked: L kept, κ moves), θ + dir from T1. */
    static Solution tangentRow(Ctx c, Vec3 T1) {
        ElemParams e = c.e;
        double[] td = inv2(c.f0, T1, c.hint, e);
        if (td == null) return c.refuse(SectionNames.PARALLEL_WHY);
        double th = td[0];
        double kap = e.k0();
        ElemParams ne;
        if (Math.abs(kap) < KAPPA_INF || e.has(L_LEN)) {
            if (e.has(L_K0) && Math.abs(kap) < KAPPA_INF) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
            if (e.has(L_K0) && e.has(L_LEN)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
            ne = e.withK(th / e.L()).withDir(td[1]);
        } else {
            double kk = Math.signum(th) * Math.abs(kap);
            double L = Math.abs(th / kk);
            if (L < MIN_LEN) { L = MIN_LEN; kk = th / MIN_LEN; }
            ne = e.withK(kk).withDir(td[1]).withL(L);
        }
        return c.done(ne, true, null, 0);
    }

    /** The arrows: TANGENT = the arc length (κ, dir kept; a straight: ℓ
     *  only); RADIAL = the END radius (linked: κ := 1/r with Θ, dir kept
     *  — the Θ+dir ray; unlinked: k1 alone). */
    public static Solution solveArrow(List<ElemParams> els, Frame entry, int k,
                                      Arrow arrow, double value, Chart chart) {
        Ctx c = new Ctx(els, entry, k, chart);
        ElemParams e = c.e;
        if (arrow == Arrow.TANGENT) {
            if (e.has(L_LEN)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
            double L = Math.max(MIN_LEN, value);
            if (e.isStraight()) return c.done(e.withL(L), L == value, null, 0);
            if (e.has(L_TURN)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
            return c.done(e.withL(L), L == value, null, 0);
        }
        if (e.isStraight()) return c.refuse(SectionNames.NO_CURVE_BEFORE_WHY);
        double r = value;
        double kNew = Double.isInfinite(r) || r <= 0 ? 0 : Math.min(KAPPA_MAX, 1 / r);
        if (e.kLink()) {
            if (e.has(L_K0)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
            double th = e.turnRad();
            double kk = Math.signum(e.k0()) == 0 ? kNew : Math.signum(e.k0()) * kNew;
            if (Math.abs(kk) < KAPPA_INF) return c.done(e.withK(0), true, null, 0);
            double L = Math.abs(th) < 1e-15 ? e.L() : Math.abs(th / kk);
            boolean exact = L >= MIN_LEN;
            if (!exact) { L = MIN_LEN; kk = th / MIN_LEN; }
            return c.done(e.withK(kk).withL(L), exact, null, 0);
        }
        if (e.has(L_K1)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
        double kk = Math.signum(e.k1()) == 0 ? kNew : Math.signum(e.k1()) * kNew;
        return c.done(e.withK1(kk), true, null, 0);
    }

    /** The nearest previous non-straight element, or −1. */
    static int prevCurve(List<ElemParams> els, int k) {
        for (int j = k - 1; j >= 0; j--) if (!els.get(j).isStraight()) return j;
        return -1;
    }

    // =================================================================
    // the HELICAL node (§4.4 I9 + its lock variants)
    // =================================================================

    /** The plan frame of a tangent: {T_h, N_h} with T_h = unit(T.x, 0,
     *  T.z), N_h = T_h × Y (+c_h = LEFT); null on vertical track. */
    static Vec3[] planFrame(Vec3 T) {
        double len = Math.hypot(T.x(), T.z());
        if (len <= 1e-9) return null;
        Vec3 Th = new Vec3(T.x() / len, 0, T.z() / len);
        return new Vec3[]{ Th, Th.cross(new Vec3(0, 1, 0)) };
    }

    /** The plan-length factor L_h / L of a helix from β0 to β1 (rad):
     *  cos β̄·sinc(Δβ/2) (LINEAR pitch) or cos β̄·J0(Δβ/2) (SINUSOIDAL). */
    static double planFactor(double b0, double b1, boolean sinusoidal) {
        double bm = (b0 + b1) / 2, db = (b1 - b0) / 2;
        return Math.cos(bm) * (sinusoidal ? j0(db) : sinc(db));
    }

    static Solution solveHelix(Ctx c, Vec3 target) {
        ElemParams e = c.e;
        Frame f = c.f0;
        if (c.end.p().sub(target).length() <= AT_TARGET) return c.unchanged();
        if (f.vertical()) return c.refuse(SectionNames.HELIX_VERTICAL_WHY);
        int locks = e.locks();
        int xyz = locks & L_XYZ;
        if (lockRank(e) >= 3) {
            return c.refuse(xyz == L_XYZ ? SectionNames.POS_LOCKED_WHY : SectionNames.ALL_LOCKED_WHY);
        }
        boolean K = e.has(L_K0) || (e.has(L_TURN) && e.has(L_LEN));
        boolean TURN = e.has(L_TURN) || e.has(L_HEAD) || (e.has(L_K0) && e.has(L_LEN));
        boolean Ln = e.has(L_LEN);
        boolean betaFixed = e.bLink() || e.has(L_B1) || e.has(L_ELEV);
        boolean sinus = !e.bLink() && e.bInterp() == StraightCurveSection.Interp.SINUSOIDAL;
        if (!e.constantK()) return solveRampedHelix(c, target);
        double b0 = Math.toRadians(f.elevationDeg());
        double b1cur = e.bLink() ? b0 : Math.toRadians(e.b1Deg());
        Vec3 tgt = target;
        boolean projected = false;
        if (xyz != 0) {
            Vec3 held = c.end.p();
            tgt = new Vec3((xyz & L_X) != 0 ? held.x() : target.x(),
                           (xyz & L_Y) != 0 ? held.y() : target.y(),
                           (xyz & L_Z) != 0 ? held.z() : target.z());
            projected = tgt.sub(target).length() > ON_SET * Math.max(1, target.length());
        }
        Vec3[] pf = planFrame(f.T());
        Vec3 delta = tgt.sub(f.p());
        double dT = delta.dot(pf[0]), dN = delta.dot(pf[1]), dy = delta.y();
        double ch = e.k0();
        double hintAbs = c.hintAbs();
        // ---- the plan (§4.4 I9's plan I1, or its locked rows) ----
        Arc2 plan;
        String notice = null;
        if (K && TURN) {
            double Lh = Math.abs(ch) < KAPPA_INF ? e.L() * planFactor(b0, b1cur, sinus) : Math.abs(e.k0() * e.L() * planFactor(b0, b1cur, sinus) / ch);
            plan = invFixed(ch, ch * Lh, dT, dN);
        } else if (K) {
            plan = invK(dT, dN, ch, hintAbs);
        } else if (TURN) {
            double dpsi = ch * e.L() * planFactor(b0, b1cur, sinus);
            plan = invTheta(dT, dN, dpsi, ch);
        } else if (Ln && betaFixed) {
            plan = invL(dT, dN, e.L() * planFactor(b0, b1cur, sinus), hintAbs);
        } else {
            plan = inv1(dT, dN, hintAbs, c.hint.kSign());
            if (plan.why != null) notice = plan.why;
        }
        if (Math.abs(plan.kappa) < KAPPA_INF) {
            // a straight climb in plan: c_h = 0 (the κ = 0 helix)
            plan.kappa = 0;
        }
        double Lh = plan.L;
        double chNew = plan.kappa;
        // ---- the height: β1 ----
        double b1;
        boolean heightExact;
        if (betaFixed) {
            b1 = b1cur;
            double dyModel = Lh * Math.tan((b0 + b1) / 2);
            heightExact = Math.abs(dy - dyModel) <= 1e-9;
        } else if (Ln) {
            // L locked, β1 free: the plan length fixes β1 — the root of
            // L·f(β1) = L_h nearest the current β1 (bisection ≤ 60)
            final double LL = e.L(), LhT = Lh, bb0 = b0;
            final boolean sn = sinus;
            java.util.function.DoubleUnaryOperator g = b -> LL * planFactor(bb0, b, sn) - LhT;
            double bm = -b0;   // the plan length's maximum
            double lo1 = Math.toRadians(-B1_MAX), hi1 = Math.toRadians(B1_MAX);
            double r1 = bisect(g, lo1, Math.max(lo1, Math.min(hi1, bm)));
            double r2 = bisect(g, Math.max(lo1, Math.min(hi1, bm)), hi1);
            if (Double.isNaN(r1) && Double.isNaN(r2)) {
                // unreachable plan length at this L: clamp β1 at the
                // extreme nearest the target height (PROJECTED)
                b1 = dy > Lh * Math.tan(b0) ? hi1 : lo1;
                heightExact = false;
            } else {
                b1 = Double.isNaN(r1) ? r2 : Double.isNaN(r2) ? r1
                    : Math.abs(r1 - b1cur) <= Math.abs(r2 - b1cur) ? r1 : r2;
                heightExact = Math.abs(dy - Lh * Math.tan((b0 + b1) / 2)) <= 1e-9;
            }
        } else {
            double bm = Math.atan2(dy, Lh);
            b1 = 2 * bm - b0;
            heightExact = true;
            double cap = Math.toRadians(B1_MAX);
            if (Math.abs(b1) > cap) {
                b1 = Math.copySign(cap, b1);
                heightExact = false;
                notice = SectionNames.HELIX_ELEV_WHY;
            }
        }
        double L = Ln ? e.L() : Lh / planFactor(b0, b1, sinus);
        if (L < MIN_LEN) { L = MIN_LEN; heightExact = false; }
        ElemParams ne = e.withK(chNew).withB1(Math.toDegrees(b1)).withL(L);
        if (e.bLink()) ne = ne.withB1(Math.toDegrees(b0));
        int iters = 0;
        if (sinus) {
            // SINUSOIDAL pitch: the J0 closed form seeds §4.11's Newton
            // against the ONE evaluator (ElementChain's quadrature)
            Solution pol = newtonHelix(c, ne, tgt, !betaFixed && !Ln, !Ln);
            if (pol != null) {
                ne = pol.params().get(c.k);
                iters = pol.iterations();
                if (pol.status() == Status.REFUSED) notice = SectionNames.RAMP_SOLVE_WHY;
            }
        }
        Frame nend = forwardEnd(f, ne);
        boolean exact = !projected && plan.exact && heightExact
            && nend.p().sub(target).length() <= 1e-9;
        return new Solution(c.with(ne), exact ? Status.EXACT : Status.PROJECTED,
            exact ? null : notice, 0, nend.p(), iters);
    }

    /** Bisection of a continuous g on [lo, hi] to BISECT_TOL (≤
     *  BISECT_MAX steps); NaN without a sign change. */
    static double bisect(java.util.function.DoubleUnaryOperator g, double lo, double hi) {
        if (hi <= lo) return Double.NaN;
        double glo = g.applyAsDouble(lo), ghi = g.applyAsDouble(hi);
        if (glo == 0) return lo;
        if (ghi == 0) return hi;
        if (Math.signum(glo) == Math.signum(ghi)) return Double.NaN;
        for (int i = 0; i < BISECT_MAX; i++) {
            double mid = (lo + hi) / 2;
            double gm = g.applyAsDouble(mid);
            if (gm == 0 || hi - lo < BISECT_TOL) return mid;
            if (Math.signum(gm) == Math.signum(glo)) { lo = mid; glo = gm; }
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** Newton polish of a helix's (β1, L) (or (L) alone) against
     *  ElementChain for the SINUSOIDAL pitch; null = nothing to do. */
    static Solution newtonHelix(Ctx c, ElemParams seed, Vec3 target, boolean b1Free, boolean LFree) {
        Frame f = c.f0;
        int n = (b1Free ? 1 : 0) + (LFree ? 1 : 0);
        if (n == 0) return null;
        Vec3[] pf = planFrame(f.T());
        Vec3 delta = target.sub(f.p());
        final double dT = delta.dot(pf[0]), dN = delta.dot(pf[1]), dy = delta.y();
        final ElemParams base = seed;
        java.util.function.Function<double[], ElemParams> mk = v -> {
            int j = 0;
            ElemParams p = base;
            if (b1Free) p = p.withB1(Math.max(-B1_MAX, Math.min(B1_MAX, v[j++])));
            if (LFree) p = p.withL(Math.max(MIN_LEN, v[j]));
            return p;
        };
        java.util.function.Function<double[], double[]> res = v -> {
            Frame nd = forwardEnd(f, mk.apply(v));
            Vec3 d2 = nd.p().sub(f.p());
            if (n == 2) {
                // the plan arc and the height — the plan arc via the plan
                // chord's own I1 (c_h is held, so the plan is a circle)
                double pT = d2.dot(pf[0]), pN = d2.dot(pf[1]);
                double tT = dT, tN = dN;
                return new double[]{ Math.hypot(pT - tT, pN - tN), d2.y() - dy };
            }
            return new double[]{ nd.p().sub(target).length() };
        };
        double[] x = new double[n];
        int j = 0;
        if (b1Free) x[j++] = seed.b1Deg();
        if (LFree) x[j] = seed.L();
        int[] steps = { 0 };
        boolean ok;
        if (n == 2) {
            // the residual's first entry is a distance (≥ 0) — use the
            // signed plan components instead for a square Newton
            java.util.function.Function<double[], double[]> res2 = v -> {
                Frame nd = forwardEnd(f, mk.apply(v));
                Vec3 d2 = nd.p().sub(f.p());
                double pT = d2.dot(pf[0]), pN = d2.dot(pf[1]);
                // the plan point of the SAME circle at the target's plan
                // arc differs from the target's only along the circle
                double arcGot = planArc(pT, pN, base.k0());
                double arcWant = planArc(dT, dN, base.k0());
                return new double[]{ arcGot - arcWant, d2.y() - dy };
            };
            ok = newton(res2, x, steps);
        } else {
            ok = newton(res, x, steps);
        }
        ElemParams ne = mk.apply(x);
        Frame nend = forwardEnd(f, ne);
        return new Solution(c.with(ne), ok ? Status.EXACT : Status.REFUSED, null, 0, nend.p(), steps[0]);
    }

    /** The plan arc of a point on the plan circle of curvature c_h from
     *  the origin (entry tangent (1, 0)): L_h = φ/|c_h|, φ = 2·atan2. */
    static double planArc(double pT, double pN, double ch) {
        if (Math.abs(ch) < KAPPA_INF) return pT;
        double phi = 2 * Math.atan2(Math.abs(pN), pT);
        return phi / Math.abs(ch);
    }

    /** A ramped-c_h helix: Newton on (k1 [, β1], L) from the constant
     *  seed (§4.11); failure → PROJECTED to the seed + RAMP_SOLVE_WHY. */
    static Solution solveRampedHelix(Ctx c, Vec3 target) {
        ElemParams e = c.e;
        Frame f = c.f0;
        boolean betaFixed = e.bLink() || e.has(L_B1) || e.has(L_ELEV);
        boolean k1Free = !e.has(L_K1);
        boolean LFree = !e.has(L_LEN);
        if (!k1Free && !LFree && betaFixed) return c.refuse(SectionNames.ALL_LOCKED_WHY);
        // seed: the constant-κ helix inverse at κ̄
        ElemParams constE = e.withKLink(true).withK((e.k0() + e.k1()) / 2).withKLink(false)
            .withK1((e.k0() + e.k1()) / 2);
        Ctx cc = new Ctx(c.with(constE), c.fr[0], c.k, c.chart);
        Solution s0 = solveHelix(cc, target);
        if (s0.refused()) return s0;
        ElemParams s = s0.params().get(c.k);
        double k1Seed = k1Free ? 2 * s.k0() - e.k0() : e.k1();
        final ElemParams base = e.withK1(clampK(k1Seed)).withB1(betaFixed ? e.b1Deg() : s.b1Deg())
            .withL(LFree ? Math.max(MIN_LEN, s.L()) : e.L());
        int n = (k1Free ? 1 : 0) + (betaFixed ? 0 : 1) + (LFree ? 1 : 0);
        double[] x = new double[n];
        int i = 0;
        if (k1Free) x[i++] = base.k1();
        if (!betaFixed) x[i++] = base.b1Deg();
        if (LFree) x[i] = base.L();
        java.util.function.Function<double[], ElemParams> mk = v -> {
            int j = 0;
            ElemParams p = base;
            if (k1Free) p = p.withK1(v[j++]);
            if (!betaFixed) p = p.withB1(Math.max(-B1_MAX, Math.min(B1_MAX, v[j++])));
            if (LFree) p = p.withL(Math.max(MIN_LEN, v[j]));
            return p;
        };
        java.util.function.Function<double[], double[]> res = v -> {
            Vec3 d = forwardEnd(f, mk.apply(v)).p().sub(target);
            if (n == 3) return new double[]{ d.x(), d.y(), d.z() };
            if (n == 2) {
                Vec3[] pf = planFrame(f.T());
                return new double[]{ d.dot(pf[0]) + d.dot(pf[1]), d.y() };
            }
            return new double[]{ d.length() };
        };
        int[] steps = { 0 };
        boolean ok = n >= 2 ? newton(res, x, steps) : golden1(res, x,
            k1Free ? -KAPPA_MAX : LFree ? MIN_LEN : -B1_MAX,
            k1Free ? KAPPA_MAX : LFree ? 4 * Math.max(e.L(), MIN_LEN) : B1_MAX, steps);
        if (!ok) {
            Frame se = forwardEnd(f, base);
            return new Solution(c.with(base), Status.PROJECTED, SectionNames.RAMP_SOLVE_WHY, 0, se.p(), steps[0]);
        }
        ElemParams ne = mk.apply(x);
        Frame nend = forwardEnd(f, ne);
        boolean exact = n == 3 && nend.p().sub(target).length() <= 1e-9;
        return new Solution(c.with(ne), exact ? Status.EXACT : Status.PROJECTED, null, 0, nend.p(), steps[0]);
    }

    /** The helix rings: TURN / HEADING = the plan turn Δψ (c_h kept, L_h
     *  = |Δψ|/|c_h|, β1 kept → L); ELEVATION = β1 (c_h and Δψ kept → L);
     *  no DIR ring on a helix. */
    static Solution solveHelixRing(Ctx c, Ring ring, double valueDeg) {
        ElemParams e = c.e;
        Frame f = c.f0;
        if (f.vertical()) return c.refuse(SectionNames.HELIX_VERTICAL_WHY);
        if (!e.constantK()) return c.refuse(SectionNames.RAMP_SOLVE_WHY);
        double b0 = Math.toRadians(f.elevationDeg());
        boolean sinus = !e.bLink() && e.bInterp() == StraightCurveSection.Interp.SINUSOIDAL;
        double b1 = e.bLink() ? b0 : Math.toRadians(e.b1Deg());
        double fac = planFactor(b0, b1, sinus);
        double Lh = e.L() * fac;
        switch (ring) {
            case DIR: return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
            case ELEVATION: {
                if (e.bLink() || e.has(L_B1) || e.has(L_ELEV)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                double nb1 = Math.toRadians(Math.max(-B1_MAX, Math.min(B1_MAX, valueDeg)));
                double L = Lh / planFactor(b0, nb1, sinus);
                boolean exact = Math.abs(valueDeg) <= B1_MAX;
                return c.done(e.withB1(Math.toDegrees(nb1)).withL(Math.max(MIN_LEN, L)), exact,
                    exact ? null : SectionNames.HELIX_ELEV_WHY, 0);
            }
            default: {
                if (e.has(L_TURN) || e.has(L_HEAD)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                double dpsi;
                if (ring == Ring.TURN) dpsi = Math.toRadians(valueDeg);
                else {
                    double h0 = f.headingDeg();
                    double cur = Math.toDegrees(e.k0() * Lh);
                    dpsi = Math.toRadians(nearest360(valueDeg - h0, cur));
                }
                double ch = e.k0();
                if (Math.abs(ch) < KAPPA_INF) {
                    if (Math.abs(dpsi) < 1e-15) return c.unchanged();
                    ch = dpsi / Math.max(Lh, 1e-9);
                    return c.done(e.withK(clampK(ch)), true, null, 0);
                }
                double chNew = Math.signum(dpsi) == 0 ? ch : Math.signum(dpsi) * Math.abs(ch);
                double LhNew = Math.abs(dpsi / chNew);
                double L = LhNew / fac;
                boolean exact = L >= MIN_LEN;
                return c.done(e.withK(chNew).withL(Math.max(MIN_LEN, L)), exact, null, 0);
            }
        }
    }

    // =================================================================
    // the STRAIGHT node (§4.5)
    // =================================================================

    /** The straights strictly after j up to (excluding) k, their lengths
     *  summed: Λ. */
    static double lambdaBetween(List<ElemParams> els, int j, int k) {
        double s = 0;
        for (int i = j + 1; i < k; i++) s += els.get(i).L();
        return s;
    }

    static Solution solveStraight(Ctx c, Vec3 target) {
        List<ElemParams> els = c.els;
        int k = c.k;
        ElemParams ek = c.e;
        if (c.end.p().sub(target).length() <= AT_TARGET) return c.unchanged();
        int kxyz = ek.locks() & L_XYZ;
        if (kxyz == L_XYZ) return c.refuse(SectionNames.POS_LOCKED_WHY);
        boolean projected = false;
        Vec3 tgt = target;
        if (kxyz != 0) {
            Vec3 held = c.end.p();
            tgt = new Vec3((kxyz & L_X) != 0 ? held.x() : target.x(),
                           (kxyz & L_Y) != 0 ? held.y() : target.y(),
                           (kxyz & L_Z) != 0 ? held.z() : target.z());
            projected = tgt.sub(target).length() > ON_SET * Math.max(1, target.length());
        }
        int j = prevCurve(els, k);
        if (j < 0) {
            // I16: the ray along T from the seam (element 0 / straights
            // back to the seam) — drag PROJECTED, the length alone moves
            if (ek.has(L_LEN)) return c.refuse(SectionNames.STRAIGHT_ALL_LOCKED_WHY);
            Frame f = c.fr[0];
            double lam0 = lambdaBetween(els, -1, k);
            Vec3 d = tgt.sub(f.p());
            double ell = d.dot(f.T());
            double Lk = Math.max(MIN_LEN, ell - lam0);
            boolean on = perp(f, d).length() <= ON_SET * Math.max(1, d.length()) && Lk == ell - lam0;
            return c.done(ek.withL(Lk), on && !projected, null, 0);
        }
        ElemParams ej = els.get(j);
        Frame fj = c.fr[j];
        double lam = lambdaBetween(els, j, k);
        boolean Lk = ek.has(L_LEN);
        if (ej.isHelical()) return straightAfterHelix(c, j, lam, tgt, projected);
        if (!ej.constantK()) return straightAfterRamped(c, j, lam, tgt, projected);
        boolean Kj = ej.has(L_K0) || (ej.has(L_TURN) && ej.has(L_LEN));
        boolean Tj = ej.has(L_TURN) || (ej.has(L_K0) && ej.has(L_LEN));
        boolean Dj = ej.has(L_DIR) || fj.vertical();
        boolean tangentFixed = (Tj && Dj) || ej.has(L_HEAD) || ej.has(L_ELEV)
            || ek.has(L_HEAD) || ek.has(L_ELEV);
        boolean jEndFixed = (ej.locks() & L_XYZ) != 0;
        Frame fe = c.fr[j + 1];
        double hintAbs = Math.toRadians(Math.abs(Hint.of(ej).thetaDeg()));
        double thetaJ = ej.turnRad(), kappaJ = ej.k0();
        double lamMin = lam + MIN_LEN;
        if (tangentFixed || jEndFixed || (Kj && Tj && Dj)) {
            if (Lk) return c.refuse(SectionNames.STRAIGHT_ALL_LOCKED_WHY);
            // the ray from j's end along its exit tangent: ℓ alone
            Vec3 d = tgt.sub(fe.p());
            double ellTot = d.dot(fe.T());
            boolean on = perp(fe, d).length() <= ON_SET * Math.max(1, d.length());
            if (ellTot >= lamMin || Kj || jEndFixed || ej.has(L_HEAD) || ej.has(L_ELEV)
                    || ek.has(L_HEAD) || ek.has(L_ELEV)) {
                double LkNew = Math.max(MIN_LEN, ellTot - lam);
                return c.done(ek.withL(LkNew), on && !projected && ellTot - lam >= MIN_LEN, null, 0);
            }
            // Θ + dir locked, κ free: ℓ held at its floor and 1/κ along
            // the Θ+dir ray p0 + C/κ + ℓ·T1
            Vec3 N = fj.N(ej.dirDeg());
            Vec3 C = fj.T().scale(Math.sin(thetaJ)).add(N.scale(1 - Math.cos(thetaJ)));
            Vec3 T1 = fe.T();
            Vec3 dd = tgt.sub(fj.p()).sub(T1.scale(lamMin));
            double t = dd.dot(C) / C.dot(C);
            double s = thetaJ < 0 ? -1 : 1;
            double kap = t * s <= 0 ? s * KAPPA_MAX : 1 / t;
            if (Math.abs(kap) > KAPPA_MAX) kap = s * KAPPA_MAX;
            double Lj = Math.abs(thetaJ / kap);
            if (Lj < MIN_LEN) { Lj = MIN_LEN; kap = thetaJ / MIN_LEN; }
            return done2(c, j, ej.withK(kap).withL(Lj), ek.withL(MIN_LEN), false, null);
        }
        // the plane: fixed (DIR locked / vertical entry) or the azimuth
        Vec3 delta = tgt.sub(fj.p());
        Vec3 pp = perp(fj, delta);
        double dn = pp.length();
        double dirJ;
        double side;
        if (Dj || dn <= ON_SET * Math.max(1, delta.length())) {
            dirJ = ej.dirDeg();
            side = 1;
        } else {
            double[] rep = pickRep(fj, pp.scale(1 / dn), Hint.of(ej));
            dirJ = rep[0];
            side = rep[1];
        }
        Vec3 a = fj.axis(dirJ), N = fj.T().cross(a);
        double dT = delta.dot(fj.T()), dN = delta.dot(N), w = delta.dot(a);
        boolean inPlane = Math.abs(w) <= ON_SET * Math.max(1, delta.length());
        // κ of j SIGNED against this plane's N
        double kappaP = Dj ? kappaJ : Math.abs(kappaJ) * side;
        if (Dj) side = kappaJ < 0 ? -1 : 1;
        double thetaP = Math.abs(thetaJ) * (kappaP < 0 ? -1 : 1);
        if (Math.abs(kappaJ) < KAPPA_INF) {
            // a κ = 0 curve before the straight: I5 from the sign of dN
            kappaP = 0;
        }
        double[] r;   // {theta (signed), kappa (signed), Lj, Lk, exactFlag}
        if (!Lk) {
            if (Tj) {
                r = inv6(dT, dN, thetaP, lamMin, lam);
            } else {
                r = Math.abs(kappaP) < KAPPA_INF ? null : inv4(dT, dN, kappaP, lamMin, lam, hintAbs);
                if (r == null) {
                    if (Kj && Math.abs(kappaP) >= KAPPA_INF) {
                        r = inv4Floor(dT, dN, kappaP, lamMin, lam, hintAbs);
                    } else {
                        // the hand-off: the straight at its floor first,
                        // then κ tightens (I5 with ℓ = Λ + MIN)
                        r = inv5(dT, dN, lamMin, lam, hintAbs, side);
                    }
                }
            }
        } else {
            double ell = lam + ek.L();
            if (Kj && Tj) {
                // the in-plane end point is fixed: the azimuth alone moved
                r = new double[]{ thetaP, kappaP, ej.L(), ek.L(), 0 };
            } else if (Tj) {
                r = inv6Held(dT, dN, thetaP, ell, lam, kappaP);
            } else if (Kj && Math.abs(kappaP) >= KAPPA_INF) {
                r = inv4Held(dT, dN, kappaP, ell, lam, hintAbs);
            } else {
                r = inv5(dT, dN, ell, lam, hintAbs, side);
            }
        }
        if (r == null) return c.refuse(SectionNames.MIXED_LOCK_WHY);
        boolean exact = inPlane && !projected && r[4] == 1;
        ElemParams nej = ej.withK(r[1]).withDir(dirJ).withL(r[2]);
        ElemParams nek = ek.withL(r[3]);
        return done2(c, j, nej, nek, exact, null);
    }

    /** Two elements changed (the curve before and the straight): the
     *  solution with the landed end of node k. */
    static Solution done2(Ctx c, int j, ElemParams nej, ElemParams nek, boolean exact, String notice) {
        List<ElemParams> out = new ArrayList<>(c.els);
        out.set(j, nej);
        out.set(c.k, nek);
        Frame[] fr = frames(c.fr[0], out);
        return new Solution(out, exact ? Status.EXACT : Status.PROJECTED, exact ? null : notice,
            0, fr[c.k + 1].p(), 0);
    }

    /** I4 (κ kept): the tangent from p to the circle of SIGNED curvature
     *  kappaP; null when p lies inside the circle or the straight would
     *  fall under its floor (the caller hands off). {θ, κ, L_j, L_k, 1}. */
    static double[] inv4(double dT, double dN, double kappaP, double lamMin, double lam, double hintAbs) {
        double s = kappaP < 0 ? -1 : 1, rho = 1 / Math.abs(kappaP);
        double vx = dT, vy = dN - s * rho;
        double D = Math.hypot(vx, vy);
        if (D < rho * (1 + 1e-15)) return null;
        double ell = Math.sqrt(Math.max(0, D * D - rho * rho));
        if (ell < lamMin) return null;
        double alpha = Math.atan2(s * vy, vx) + Math.PI / 2;
        double phi0 = alpha - Math.atan2(ell, rho);
        phi0 -= TWO_PI * Math.floor(phi0 / TWO_PI);
        double phi = lapTurn(phi0, hintAbs);
        double Lj = phi * rho;
        if (Lj < MIN_LEN) return null;
        return new double[]{ s * phi, kappaP, Lj, ell - lam, 1 };
    }

    /** I4 with κ LOCKED and the point inside the circle / the straight at
     *  its floor: project to the circle of radius √(ρ² + ℓ²) about the
     *  centre with ℓ = Λ + MIN held — the angle alone follows. */
    static double[] inv4Floor(double dT, double dN, double kappaP, double lamMin, double lam, double hintAbs) {
        return inv4Held(dT, dN, kappaP, lamMin, lam, hintAbs);
    }

    /** I4 with ℓ HELD (κ kept): the end runs on the circle of radius
     *  √(ρ² + ℓ²) about C — the nearest angle. */
    static double[] inv4Held(double dT, double dN, double kappaP, double ell, double lam, double hintAbs) {
        double s = kappaP < 0 ? -1 : 1, rho = 1 / Math.abs(kappaP);
        double vx = dT, vy = dN - s * rho;
        double D = Math.hypot(vx, vy);
        double alpha;
        if (D < 1e-300) alpha = hintAbs + Math.atan2(ell, rho);
        else alpha = Math.atan2(s * vy, vx) + Math.PI / 2;
        double phi0 = alpha - Math.atan2(ell, rho);
        phi0 -= TWO_PI * Math.floor(phi0 / TWO_PI);
        double phi = lapTurn(phi0, hintAbs);
        double Lj = Math.max(MIN_LEN, phi * rho);
        boolean exact = D > 0 && Math.abs(D - Math.hypot(rho, ell)) <= ON_SET * D && phi * rho >= MIN_LEN;
        return new double[]{ s * phi, kappaP, Lj, ell - lam, exact ? 1 : 0 };
    }

    /** I5 (ℓ fixed, κ free): θ = 2·atan2(dN, dT + ℓ), κ = 2dN/(|Δ|² − ℓ²);
     *  |Δ| ≤ ℓ = the SPHERE singular set → the point scaled out to the
     *  κ_max boundary (PROJECTED; SPHERE_WHY on a typed box). */
    static double[] inv5(double dT, double dN, double ell, double lam, double hintAbs, double side) {
        double norm2 = dT * dT + dN * dN;
        boolean exact = true;
        if (Math.abs(dN) <= ON_SET * Math.sqrt(norm2)) {
            // on the axis: κ = 0, the lengths split along T
            double Lj = dT - ell;
            if (Lj < MIN_LEN) { Lj = MIN_LEN; exact = false; }
            return new double[]{ 0, 0, Lj, ell - lam, exact ? 1 : 0 };
        }
        double denom = norm2 - ell * ell;
        double kap = denom > 0 ? 2 * dN / denom : Double.POSITIVE_INFINITY;
        if (denom <= 0 || Math.abs(kap) > KAPPA_MAX) {
            double sc = (Math.abs(dN) + Math.sqrt(dN * dN + KAPPA_MAX * KAPPA_MAX * norm2 * ell * ell))
                / (KAPPA_MAX * norm2);
            if (sc > 1) {
                dT *= sc;
                dN *= sc;
                norm2 = dT * dT + dN * dN;
                denom = norm2 - ell * ell;
                kap = 2 * dN / denom;
                exact = false;
            }
        }
        double theta0 = 2 * Math.atan2(dN, dT + ell);
        double phi = lapTurn(Math.abs(theta0), hintAbs);
        double s = dN < 0 ? -1 : 1;
        double Lj = phi / Math.abs(kap);
        if (Lj < MIN_LEN) {
            // the curve at its own floor: κ := φ/MIN (the arc shortens)
            Lj = MIN_LEN;
            kap = s * phi / MIN_LEN;
            exact = false;
        }
        return new double[]{ s * phi, kap, Lj, ell - lam, exact ? 1 : 0 };
    }

    /** I6 (θ fixed, κ and ℓ free): the linear 2×2 in (1/κ, ℓ); out of
     *  range → PROJECTED (ℓ held at its floor → 1/κ along the ray; κ
     *  clamped → ℓ by projection onto T1). */
    static double[] inv6(double dT, double dN, double thetaP, double lamMin, double lam) {
        double st = Math.sin(thetaP), ct = Math.cos(thetaP);
        double det = 1 - ct;
        if (Math.abs(det) < 1e-15) {
            // θ ≡ 0: a straight-shaped planar — a single line in T
            double Lj = Math.max(MIN_LEN, dT - lamMin);
            return new double[]{ 0, 0, Lj, lamMin - lam, 0 };
        }
        double invK = (dT * st - dN * ct) / det;
        double ell = (dN * st - dT * det) / det;
        double s = thetaP < 0 ? -1 : 1;
        boolean exact = true;
        if (invK * s <= 0 || Math.abs(1 / invK) > KAPPA_MAX || ell < lamMin
                || Math.abs(thetaP * invK) < MIN_LEN) {
            exact = false;
            if (ell < lamMin || invK * s <= 0 && ell >= lamMin) {
                return inv6Held(dT, dN, thetaP, lamMin, lam, s * KAPPA_MAX);
            }
            double kap = s * Math.min(KAPPA_MAX, Math.max(MIN_LEN > 0 ? Math.abs(thetaP) / (4 * Math.max(1, Math.abs(thetaP))) : 0, Math.abs(1 / invK)));
            kap = s * Math.min(KAPPA_MAX, Math.abs(1 / invK));
            if (Math.abs(thetaP / kap) < MIN_LEN) kap = thetaP / MIN_LEN;
            // ℓ by projection onto T1 with κ fixed
            double cx = st / kap, cy = det / kap;
            double t1x = ct, t1y = st;
            double e2 = (dT - cx) * t1x + (dN - cy) * t1y;
            ell = Math.max(lamMin, e2);
            return new double[]{ thetaP, kap, Math.abs(thetaP / kap), ell - lam, 0 };
        }
        double kap = 1 / invK;
        return new double[]{ thetaP, kap, Math.abs(thetaP / kap), ell - lam, 1 };
    }

    /** The Θ+dir ray with ℓ HELD: 1/κ by orthogonal projection of
     *  (Δ − ℓ·T1) onto C. */
    static double[] inv6Held(double dT, double dN, double thetaP, double ell, double lam, double kappaHint) {
        double st = Math.sin(thetaP), ct = Math.cos(thetaP);
        double s = thetaP < 0 ? -1 : thetaP > 0 ? 1 : (kappaHint < 0 ? -1 : 1);
        if (Math.abs(1 - ct) < 1e-15) {
            double Lj = Math.max(MIN_LEN, dT - ell);
            return new double[]{ 0, 0, Lj, ell - lam, Math.abs(dN) <= ON_SET && dT - ell >= MIN_LEN ? 1 : 0 };
        }
        double cx = st, cy = 1 - ct;
        double ddx = dT - ell * ct, ddy = dN - ell * st;
        double t = (ddx * cx + ddy * cy) / (cx * cx + cy * cy);
        boolean exact = Math.hypot(ddx - t * cx, ddy - t * cy) <= ON_SET * Math.max(1, Math.hypot(dT, dN));
        double kap;
        if (t * s <= 0) { kap = s * KAPPA_MAX; exact = false; }
        else {
            kap = 1 / t;
            if (Math.abs(kap) > KAPPA_MAX) { kap = s * KAPPA_MAX; exact = false; }
        }
        double Lj = Math.abs(thetaP / kap);
        if (Lj < MIN_LEN) { Lj = MIN_LEN; kap = thetaP / MIN_LEN; exact = false; }
        return new double[]{ thetaP, kap, Lj, ell - lam, exact ? 1 : 0 };
    }

    /** A straight after a HELIX j: the plan I4 (closed — the plan circle
     *  of radius 1/|c_h| and the plan straight ℓ_h), then the height: a
     *  FIXED β1 (linked / locked) implies it (PROJECTED unless met,
     *  HELIX_STRAIGHT_WHY on a typed box); a free β1 comes from the one
     *  monotone height equation Δy = L_h·tan β̄ + ℓ_h·tan β1 (bisection ≤
     *  60) — EXACT. */
    static Solution straightAfterHelix(Ctx c, int j, double lam, Vec3 tgt, boolean projected) {
        List<ElemParams> els = c.els;
        ElemParams ej = els.get(j), ek = c.e;
        Frame fj = c.fr[j];
        if (fj.vertical()) return c.refuse(SectionNames.HELIX_VERTICAL_WHY);
        boolean betaFixed = ej.bLink() || ej.has(L_B1) || ej.has(L_ELEV);
        boolean sinus = !ej.bLink() && ej.bInterp() == StraightCurveSection.Interp.SINUSOIDAL;
        double b0 = Math.toRadians(fj.elevationDeg());
        double b1cur = ej.bLink() ? b0 : Math.toRadians(ej.b1Deg());
        double ch = ej.k0();
        if (!ej.constantK()) return c.refuse(SectionNames.RAMP_SOLVE_WHY);
        Vec3[] pf = planFrame(fj.T());
        Vec3 delta = tgt.sub(fj.p());
        double dT = delta.dot(pf[0]), dN = delta.dot(pf[1]), dy = delta.y();
        boolean Lk = ek.has(L_LEN);
        boolean Kj = ej.has(L_K0);
        boolean Tj = ej.has(L_TURN) || ej.has(L_HEAD) || ek.has(L_HEAD);
        double hintAbs = Math.toRadians(Math.abs(Hint.of(ej, fj.elevationDeg()).thetaDeg()));
        double ellH;
        double dpsi, chNew;
        boolean planExact;
        double cosB1 = Math.cos(b1cur);
        double lamMinH = (lam + MIN_LEN) * cosB1;
        if (Tj && Kj) {
            // the plan point is fixed: nothing but the azimuth — refused
            if (Lk) return c.refuse(SectionNames.STRAIGHT_ALL_LOCKED_WHY);
            double LhCur = ej.L() * planFactor(b0, b1cur, sinus);
            double dpsiCur = ch * LhCur;
            // the ray from the helix end in plan
            Vec3 fe = c.fr[j + 1].p();
            Vec3 d = tgt.sub(fe);
            double ell = Math.max(lam + MIN_LEN, d.dot(c.fr[j + 1].T()));
            return done2(c, j, ej, ek.withL(ell - lam), false, null);
        }
        if (Math.abs(ch) < KAPPA_INF) {
            // the plan is a line: ℓ_h along T_h from the helix's end
            Vec3 fe = c.fr[j + 1].p();
            Vec3 d = tgt.sub(fe);
            double ell = Math.max(lam + MIN_LEN, d.dot(c.fr[j + 1].T()));
            return done2(c, j, ej, ek.withL(ell - lam), false, null);
        }
        double[] r;
        if (!Lk) {
            if (Tj) {
                double dpsiCur = ch * ej.L() * planFactor(b0, b1cur, sinus);
                r = inv6(dT, dN, dpsiCur, lamMinH, 0);
            } else {
                r = inv4(dT, dN, ch, lamMinH, 0, hintAbs);
                if (r == null) {
                    r = Kj ? inv4Held(dT, dN, ch, lamMinH, 0, hintAbs)
                        : inv5(dT, dN, lamMinH, 0, hintAbs, 1);
                }
            }
        } else {
            double ell = (lam + ek.L()) * cosB1;
            if (Tj) r = inv6Held(dT, dN, ch * ej.L() * planFactor(b0, b1cur, sinus), ell, 0, ch);
            else if (Kj) r = inv4Held(dT, dN, ch, ell, 0, hintAbs);
            else r = inv5(dT, dN, ell, 0, hintAbs, 1);
        }
        if (r == null) return c.refuse(SectionNames.MIXED_LOCK_WHY);
        dpsi = r[0];
        chNew = r[1];
        double Lh = r[2];
        ellH = r[3];
        planExact = r[4] == 1;
        double b1;
        boolean heightExact;
        if (betaFixed) {
            b1 = b1cur;
            double dyModel = Lh * Math.tan((b0 + b1) / 2) + ellH * Math.tan(b1);
            heightExact = Math.abs(dy - dyModel) <= 1e-9;
        } else {
            final double LhF = Lh, eH = ellH, bb0 = b0, dyT = dy;
            java.util.function.DoubleUnaryOperator g = b -> LhF * Math.tan((bb0 + b) / 2) + eH * Math.tan(b) - dyT;
            double lo = Math.toRadians(-B1_MAX), hi = Math.toRadians(B1_MAX);
            double root = bisect(g, lo, hi);
            if (Double.isNaN(root)) {
                b1 = g.applyAsDouble(lo) > 0 ? lo : hi;
                heightExact = false;
            } else {
                b1 = root;
                heightExact = true;
            }
        }
        double Lj = Lh / planFactor(b0, b1, sinus);
        double ell = ellH / Math.cos(b1);
        double LkNew = ell - lam;
        boolean exact = planExact && heightExact && !projected && LkNew >= MIN_LEN && Lj >= MIN_LEN;
        if (LkNew < MIN_LEN) LkNew = MIN_LEN;
        if (Lj < MIN_LEN) Lj = MIN_LEN;
        ElemParams nej = ej.withK(chNew).withB1(ej.bLink() ? Math.toDegrees(b0) : Math.toDegrees(b1)).withL(Lj);
        return done2(c, j, nej, ek.withL(LkNew), exact, exact ? null : SectionNames.HELIX_STRAIGHT_WHY);
    }

    /** A straight after a RAMPED planar j: §4.11's Newton on (L_j, ℓ) in
     *  the azimuth's plane with k0, k1 held, seeded by I4 at κ̄. */
    static Solution straightAfterRamped(Ctx c, int j, double lam, Vec3 tgt, boolean projected) {
        List<ElemParams> els = c.els;
        ElemParams ej = els.get(j), ek = c.e;
        Frame fj = c.fr[j];
        Vec3 delta = tgt.sub(fj.p());
        Vec3 pp = perp(fj, delta);
        double dn = pp.length();
        double dirJ;
        double side = 1;
        if (ej.has(L_DIR) || fj.vertical() || dn <= ON_SET * Math.max(1, delta.length())) dirJ = ej.dirDeg();
        else {
            double[] rep = pickRep(fj, pp.scale(1 / dn), Hint.of(ej));
            dirJ = rep[0];
            side = rep[1];
        }
        Vec3 a = fj.axis(dirJ), N = fj.T().cross(a);
        final double dT = delta.dot(fj.T()), dN = delta.dot(N);
        double kbar = (ej.k0() + ej.k1()) / 2;
        double[] seed = Math.abs(kbar) < KAPPA_INF ? null
            : inv4(dT, dN, Math.abs(kbar) * (ej.has(L_DIR) ? Math.signum(kbar) : side), lam + MIN_LEN, lam,
                Math.toRadians(Math.abs(Hint.of(ej).thetaDeg())));
        double LjSeed = seed == null ? ej.L() : seed[2];
        double LkSeed = seed == null ? Math.max(MIN_LEN, dT - ej.L() - lam) : seed[3];
        final ElemParams baseJ = ej.withDir(dirJ);
        java.util.function.Function<double[], double[]> res = v -> {
            List<ElemParams> trial = new ArrayList<>(els);
            trial.set(j, baseJ.withL(Math.max(MIN_LEN, v[0])));
            trial.set(c.k, ek.withL(Math.max(MIN_LEN, v[1])));
            Frame[] fr = frames(c.fr[0], trial);
            Vec3 d2 = fr[c.k + 1].p().sub(fj.p());
            return new double[]{ d2.dot(fj.T()) - dT, d2.dot(N) - dN };
        };
        double[] x = { ej.L(), ek.L() };
        int[] steps = { 0 };
        boolean ok = newton(res, x, steps);
        if (!ok) { x = new double[]{ LjSeed, LkSeed }; ok = newton(res, x, steps); }
        ElemParams nej = baseJ.withL(Math.max(MIN_LEN, x[0]));
        ElemParams nek = ek.withL(Math.max(MIN_LEN, x[1]));
        if (!ok) {
            nej = baseJ.withL(Math.max(MIN_LEN, LjSeed));
            nek = ek.withL(Math.max(MIN_LEN, LkSeed));
            Solution s = done2(c, j, nej, nek, false, SectionNames.RAMP_SOLVE_WHY);
            return new Solution(s.params(), Status.PROJECTED, SectionNames.RAMP_SOLVE_WHY, 0, s.landedPos(), steps[0]);
        }
        Solution s = done2(c, j, nej, nek, !projected && x[0] >= MIN_LEN && x[1] >= MIN_LEN, null);
        return new Solution(s.params(), s.status(), s.why(), 0, s.landedPos(), steps[0]);
    }

    // =================================================================
    // §4.1's entry points for a point target
    // =================================================================

    /** The §4.4 / §4.5 rows + §4.11 WITHOUT the absorb: where node k
     *  lands for a point target (D15 drags restrict — PROJECTED on the
     *  reachable set), the new params of the elements the row moves. */
    public static Solution project(List<ElemParams> els, Frame entry, int k, Vec3 target,
                                   Chart chart) {
        if (els == null || k < 0 || k >= els.size()) {
            return Solution.refuse(els == null ? new ArrayList<>() : new ArrayList<>(els),
                SectionNames.ALL_LOCKED_WHY);
        }
        Ctx c = new Ctx(els, entry, k, chart);
        ElemParams e = els.get(k);
        if (e.isStraight()) return solveStraight(c, target);
        if (e.isHelical()) return solveHelix(c, target);
        return solvePlanar(c, target);
    }

    /** A ball drag of node k to the canonical point target: the row
     *  solve, then the AFTER rule (§4.8). A downstream hard lock refuses
     *  the whole drag (the original params come back). */
    public static Solution solveNodeDrag(List<ElemParams> els, Frame entry, int k, Vec3 target,
                                         Chart chart) {
        Solution s = project(els, entry, k, target, chart);
        if (s.refused()) return s;
        Frame[] fr0 = frames(entry, els);
        return absorbInto(s, els, entry, k, fr0[k + 1]);
    }

    // =================================================================
    // §4.8 — the AFTER rule: absorbAfter
    // =================================================================

    /** The first curve after k whose TURN or DIR is unlocked (a helix
     *  counts; a fully-locked curve is skipped), or −1. */
    static int absorberAfter(List<ElemParams> els, int k) {
        for (int i = k + 1; i < els.size(); i++) {
            ElemParams e = els.get(i);
            if (e.isStraight()) continue;
            if (e.isHelical()) {
                boolean headFree = !e.has(L_TURN) && !e.has(L_HEAD);
                boolean elevFree = !e.bLink() && !e.has(L_B1) && !e.has(L_ELEV);
                if (headFree || elevFree) return i;
                continue;
            }
            if (!e.has(L_TURN) || !e.has(L_DIR)) return i;
        }
        return -1;
    }

    static double angleDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.atan2(a.cross(b).length(), a.dot(b)));
    }

    /** ONE absorbing curve, orientation only, κ kept (§4.8). k = the
     *  element whose exit changed (−1 = the section entry); exitOld =
     *  the frame that stood at that chain position before the edit.
     *  REFUSED with downstreamLockWhy for a locked X/Y/Z beyond k other
     *  than the absorber's own end; a residual (locked absorber) posts
     *  absorbResidualWhy(c) as a PROJECTED notice; no curve after k =
     *  nothing absorbs, no sentence. */
    public static Solution absorbAfter(List<ElemParams> els, Frame entry, int k, Frame exitOld) {
        List<ElemParams> out = new ArrayList<>(els);
        int n = els.size();
        Frame[] fr = frames(entry, els);
        Frame exitNew = fr[k + 1];
        boolean moved = exitNew.p().sub(exitOld.p()).length() > 1e-12
            || exitNew.T().sub(exitOld.T()).length() > 1e-12
            || exitNew.U().sub(exitOld.U()).length() > 1e-12;
        if (!moved || k + 1 >= n) {
            return new Solution(out, Status.EXACT, null, 0, exitNew.p(), 0);
        }
        int c = absorberAfter(els, k);
        // downstream HARD locks
        for (int m = k + 1; m < n; m++) {
            if (m == c) continue;
            if ((els.get(m).locks() & L_XYZ) != 0) {
                return Solution.refuse(new ArrayList<>(els),
                    SectionNames.downstreamLockWhy(SectionNames.elementName(m)));
            }
        }
        boolean tKept = exitNew.T().sub(exitOld.T()).length() <= 1e-12;
        if (c < 0) {
            // nothing absorbs: the section exit rotates, downstream
            // sections follow by contract (no sentence)
            return new Solution(out, Status.EXACT, null, 0, exitNew.p(), 0);
        }
        // the OLD chain from exitOld through the unchanged downstream
        List<ElemParams> tail = els.subList(k + 1, n);
        Frame[] oldFr = frames(exitOld, tail);   // oldFr[i − (k+1)] = old node i−1's… index: oldFr[m − k] = old frame at node m
        ElemParams ec = els.get(c);
        Frame T2f = oldFr[c + 1 - (k + 1)];        // c's OLD exit frame
        Vec3 T2 = T2f.T();
        if ((ec.locks() & L_XYZ) != 0) {
            // c's own end locked: c solves I1 (κ free) for the position,
            // its exit-T change is the residual
            Vec3 pLock = T2f.p();
            Ctx cc = new Ctx(out, entry, c, Chart.LOCAL);
            Solution s = i1Free(cc, pLock);
            if (s.refused()) return s;
            out = s.params();
            Frame[] nf = frames(entry, out);
            double rot = angleDeg(nf[c + 1].T(), T2);
            return new Solution(out, rot > 1e-9 ? Status.PROJECTED : Status.EXACT,
                rot > 1e-9 ? SectionNames.absorbResidualWhy(SectionNames.elementName(c)) : null,
                rot, nf[k + 1].p(), 0);
        }
        if (tKept) {
            // a pure translation: nothing to absorb
            return new Solution(out, Status.EXACT, null, 0, exitNew.p(), 0);
        }
        Frame fin = fr[c];   // c's NEW entry (straights between re-aimed)
        ElemParams nc;
        double rot;
        if (ec.isHelical()) {
            nc = absorbHelix(ec, fin, T2);
            Frame nend = forwardEnd(fin, nc);
            rot = angleDeg(nend.T(), T2);
        } else if (!ec.constantK()) {
            // a ramped absorber: its turn Θ = (k0 + k1)L/2 is still
            // closed — the restore sets L for the needed turn, dir from
            // the axis; the ramp's position changes ride the translation
            nc = absorbPlanar(ec, fin, T2, true);
            Frame nend = forwardEnd(fin, nc);
            rot = angleDeg(nend.T(), T2);
        } else {
            nc = absorbPlanar(ec, fin, T2, false);
            Frame nend = forwardEnd(fin, nc);
            rot = angleDeg(nend.T(), T2);
        }
        out.set(c, nc);
        Frame[] nf = frames(entry, out);
        boolean residual = rot > 1e-9;
        return new Solution(out, residual ? Status.PROJECTED : Status.EXACT,
            residual ? SectionNames.absorbResidualWhy(SectionNames.elementName(c)) : null,
            rot, nf[k + 1].p(), 0);
    }

    /** The planar absorber's restore of its OLD exit tangent T2 from its
     *  NEW entry: full (TURN + DIR free), DIR-locked (θ in the fixed
     *  plane), TURN-locked (the cone point nearest T2). κ KEPT; the
     *  floor is the one place κ changes (L := MIN, κ := Θ/MIN). */
    static ElemParams absorbPlanar(ElemParams ec, Frame fin, Vec3 T2, boolean ramped) {
        Vec3 T = fin.T();
        Hint h = Hint.of(ec);
        double kapAbs = Math.abs(ramped ? (ec.k0() + ec.k1()) / 2 : ec.k0());
        double thetaCur = ec.turnRad();
        int lapOld = (int) Math.floor(Math.abs(thetaCur) / TWO_PI);
        boolean turnFree = !ec.has(L_TURN), dirFree = !ec.has(L_DIR) && !fin.vertical();
        double theta, dir;
        if (turnFree && dirFree) {
            double cs = T.dot(T2);
            Vec3 cr = T2.cross(T);
            double s = cr.length();
            if (cs < -1 + 1e-12) {
                dir = ec.dirDeg();
                theta = Math.toRadians(nearest360(180, h.thetaDeg()));
            } else if (s < 1e-15) {
                // T2 already parallel: the nearest whole turn — 0 means the
                // floor below (a 0.5 m straight-shaped planar, κ := 0/MIN)
                dir = ec.dirDeg();
                theta = Math.toRadians(nearest360(0, h.thetaDeg()));
            } else {
                double phi0 = Math.atan2(s, cs) + TWO_PI * lapOld;
                double raw = fin.dirOf(cr.scale(1 / s));
                double dA = nearest360(raw, h.dirDeg()), dB = nearest360(raw + 180, h.dirDeg());
                if (Math.abs(dB - h.dirDeg()) < Math.abs(dA - h.dirDeg())) { dir = dB; theta = -phi0; }
                else { dir = dA; theta = phi0; }
            }
        } else if (dirFree) {
            // TURN locked: the cone about T of half-angle |θ|; the point
            // nearest T2 has T2's own azimuth
            theta = thetaCur;
            Vec3 pp = T2.sub(T.scale(T2.dot(T)));
            double pl = pp.length();
            if (pl < 1e-15) return ec;
            Vec3 N = pp.scale(1 / pl);
            double red = Math.abs(theta) - TWO_PI * Math.floor(Math.abs(theta) / TWO_PI);
            if (Math.sin(red) < 0) N = N.scale(-1);   // the cone opens backwards past π
            double[] rep = pickRep(fin, N, h);
            dir = rep[0];
            if (rep[1] < 0) theta = -Math.abs(theta); else theta = Math.abs(theta);
            if (ec.has(L_K0) || kapAbs < KAPPA_INF) {
                return ec.withDir(dir).withK(ramped ? ec.k0() : theta == 0 ? 0 : Math.signum(theta) * Math.max(kapAbs, KAPPA_INF));
            }
        } else {
            // DIR locked: θ in the fixed plane ⊥ a toward T2's projection
            dir = ec.dirDeg();
            Vec3 a = fin.axis(dir), N = T.cross(a);
            Vec3 T2p = T2.sub(a.scale(T2.dot(a)));
            double pl = T2p.length();
            if (pl < 1e-15) return ec;
            T2p = T2p.scale(1 / pl);
            double th0 = Math.atan2(T2p.dot(N), T2p.dot(T));
            theta = Math.toRadians(nearest360(Math.toDegrees(th0), h.thetaDeg()));
        }
        if (ramped) {
            // Θ = (k0 + k1)·L/2 held by L alone (κ's kept)
            double kb = (ec.k0() + ec.k1()) / 2;
            if (Math.abs(kb) < KAPPA_INF) return ec.withDir(dir);
            double L = Math.max(MIN_LEN, Math.abs(theta / kb));
            if (Math.signum(theta) != Math.signum(kb)) {
                return ec.withK0(-ec.k0()).withK1(-ec.k1()).withDir(dir).withL(L);
            }
            return ec.withDir(dir).withL(L);
        }
        double kap;
        double L;
        if (kapAbs < KAPPA_INF) {
            L = ec.L();
            kap = theta / L;
        } else {
            kap = Math.signum(theta) == 0 ? kapAbs : Math.signum(theta) * kapAbs;
            L = Math.abs(theta / kap);
            if (L < MIN_LEN) { L = MIN_LEN; kap = theta / MIN_LEN; }
        }
        return ec.withK(kap).withDir(dir).withL(L);
    }

    /** A helix absorber: pitch-linked absorbs the HEADING only (Δψ := the
     *  plan heading gap, L_h = |Δψ|/|c_h| → L); unlinked takes the
     *  elevation too (b1 := elevation(T2)). */
    static ElemParams absorbHelix(ElemParams ec, Frame fin, Vec3 T2) {
        if (fin.vertical()) return ec;
        Vec3[] pf = planFrame(fin.T());
        double b0 = Math.toRadians(fin.elevationDeg());
        boolean sinus = !ec.bLink() && ec.bInterp() == StraightCurveSection.Interp.SINUSOIDAL;
        boolean headFree = !ec.has(L_TURN) && !ec.has(L_HEAD);
        boolean elevFree = !ec.bLink() && !ec.has(L_B1) && !ec.has(L_ELEV);
        double b1 = ec.bLink() ? b0 : Math.toRadians(ec.b1Deg());
        double fac = planFactor(b0, b1, sinus);
        double Lh = ec.L() * fac;
        double ch = ec.k0();
        ElemParams ne = ec;
        if (elevFree) {
            double e2 = Math.asin(Math.max(-1, Math.min(1, T2.y())));
            double cap = Math.toRadians(B1_MAX);
            b1 = Math.max(-cap, Math.min(cap, e2));
            ne = ne.withB1(Math.toDegrees(b1));
        }
        if (headFree) {
            double hx = T2.dot(pf[0]), hz = T2.dot(pf[1]);
            if (Math.hypot(hx, hz) > 1e-12) {
                double dpsiCur = ch * Lh;
                double dpsi0 = Math.atan2(hz, hx);
                double dpsi = Math.toRadians(nearest360(Math.toDegrees(dpsi0), Math.toDegrees(dpsiCur)));
                if (Math.abs(ch) < KAPPA_INF) {
                    if (Math.abs(dpsi) > 1e-15) ne = ne.withK(clampK(dpsi / Math.max(Lh, 1e-9)));
                } else {
                    double chNew = Math.signum(dpsi) == 0 ? ch : Math.signum(dpsi) * Math.abs(ch);
                    Lh = Math.abs(dpsi / chNew);
                    ne = ne.withK(chNew);
                }
            }
        }
        double L = Math.max(MIN_LEN, Lh / planFactor(b0, b1, sinus));
        return ne.withL(L);
    }

    /** The node solve's result threaded through the absorb. */
    static Solution absorbInto(Solution s, List<ElemParams> original, Frame entry, int k, Frame exitOld) {
        Solution a = absorbAfter(s.params(), entry, k, exitOld);
        if (a.refused()) return Solution.refuse(new ArrayList<>(original), a.why());
        Status st = s.status() == Status.PROJECTED || a.status() == Status.PROJECTED
            ? Status.PROJECTED : Status.EXACT;
        String why = s.why() != null ? s.why() : a.why();
        return new Solution(a.params(), st, why, a.residualRot(), s.landedPos(), s.iterations());
    }

    // =================================================================
    // §4.6 — the typed boxes (REFUSE, never a locked box, κ last)
    // =================================================================

    static int lockBitOf(Box box) {
        switch (box) {
            case X: return L_X;
            case Y: return L_Y;
            case Z: return L_Z;
            case KAPPA: return L_K0;
            case TURN: return L_TURN;
            case DIR: return L_DIR;
            case HEADING: return L_HEAD;
            case ELEVATION: return L_ELEV;
            case LEN: return L_LEN;
            case K1: return L_K1;
            default: return L_B1;
        }
    }

    /** The typed value with its unit for the refusal sentence. */
    public static String boxValueText(Box box, double value) {
        switch (box) {
            case X: case Y: case Z: case LEN:
                return String.format(java.util.Locale.US, "%.2f m", value);
            case KAPPA: case K1:
                return String.format(java.util.Locale.US, "%.4f /m", value);
            default:
                return String.format(java.util.Locale.US, "%.1f°", value);
        }
    }

    /** A typed box on node k (§4.6): the listed siblings move, a locked
     *  box never writes (FULLY_CONSTRAINED_WHY), an unreachable value
     *  REFUSES with unreachableWhy; then the absorb. hasUpstream = the
     *  section follows a seam (SEAM_OWNS_WHY) vs a run head
     *  (ANCHOR_OWNS_WHY) for a straight with no curve before it. */
    public static Solution solveTypedBox(List<ElemParams> els, Frame entry, int k, Box box,
                                         double value, Chart chart, boolean hasUpstream) {
        if (els == null || k < 0 || k >= els.size()) {
            return Solution.refuse(els == null ? new ArrayList<>() : new ArrayList<>(els),
                SectionNames.ALL_LOCKED_WHY);
        }
        ElemParams e = els.get(k);
        if (e.has(lockBitOf(box))) return Solution.refuse(new ArrayList<>(els), SectionNames.FULLY_CONSTRAINED_WHY);
        Ctx c = new Ctx(els, entry, k, chart);
        Frame[] fr0 = c.fr;
        Solution s;
        String unreachable = SectionNames.unreachableWhy(SectionNames.elementName(k),
            SectionNames.boxLabel(box.ordinal()), boxValueText(box, value));
        switch (box) {
            case X: case Y: case Z: {
                Vec3 cur = c.end.p();
                Vec3 tgt = new Vec3(box == Box.X ? value : cur.x(), box == Box.Y ? value : cur.y(),
                    box == Box.Z ? value : cur.z());
                s = project(els, entry, k, tgt, chart);
                if (s.refused()) {
                    if (e.isStraight() && prevCurve(els, k) < 0 && !s.why().equals(SectionNames.STRAIGHT_ALL_LOCKED_WHY)) {
                        return s;
                    }
                    return s;
                }
                if (s.status() == Status.PROJECTED) {
                    String w = s.why();
                    if (w == null || w.equals(SectionNames.HELIX_ELEV_WHY)) w = unreachable;
                    if (e.isStraight()) {
                        int j = prevCurve(els, k);
                        if (j >= 0 && els.get(j).isHelical()) w = SectionNames.HELIX_STRAIGHT_WHY;
                        else if (j >= 0 && els.get(j).has(L_K0) && s.why() == null) w = SectionNames.INSIDE_CIRCLE_WHY;
                        else if (j >= 0 && e.has(L_LEN) && s.why() == null) w = SectionNames.SPHERE_WHY;
                    } else if (s.why() == null && !e.isHelical() && isBackward(c, tgt)) {
                        w = SectionNames.REACH_RAY_WHY;
                    }
                    return Solution.refuse(new ArrayList<>(els), w);
                }
                break;
            }
            case KAPPA: {
                if (e.isStraight()) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (Math.abs(value) > KAPPA_MAX) return c.refuse(unreachable);
                if (e.isHelical()) {
                    double b0 = Math.toRadians(c.f0.elevationDeg());
                    double b1 = e.bLink() ? b0 : Math.toRadians(e.b1Deg());
                    boolean sn = !e.bLink() && e.bInterp() == StraightCurveSection.Interp.SINUSOIDAL;
                    double Lh = e.L() * planFactor(b0, b1, sn);
                    double dpsi = e.k0() * Lh;
                    if (Math.abs(value) < KAPPA_INF) { s = c.done(e.withK(0), true, null, 0); break; }
                    if (e.has(L_LEN)) { s = c.done(e.withK(value), true, null, 0); break; }
                    double LhNew = Math.abs(dpsi) < 1e-15 ? Lh : Math.abs(dpsi / value);
                    double L = LhNew / planFactor(b0, b1, sn);
                    if (L < MIN_LEN) return c.refuse(unreachable);
                    s = c.done(e.withK(value).withL(L), true, null, 0);
                    break;
                }
                if (!e.constantK() && !e.kLink()) {
                    // an unlinked element: k0 alone, Θ = (k0 + k1)L/2 held → L
                    double sum = value + e.k1();
                    double th = e.turnRad();
                    if (Math.abs(sum) < 1e-15) return c.refuse(unreachable);
                    double L = e.has(L_LEN) ? e.L() : Math.abs(2 * th / sum);
                    if (L < MIN_LEN) return c.refuse(unreachable);
                    s = c.done(e.withK0(value).withL(L), true, null, 0);
                    break;
                }
                if (Math.abs(value) < KAPPA_INF) {
                    // a typed 0: L held, Θ := 0 (the straight-shaped planar)
                    s = c.done(e.withK(0), true, null, 0);
                    break;
                }
                if (e.has(L_TURN) && e.has(L_LEN)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (e.has(L_LEN)) { s = c.done(e.withK(value), true, null, 0); break; }
                double th = e.turnRad();
                double L = Math.abs(th) < 1e-15 ? e.L() : Math.abs(th / value);
                if (L < MIN_LEN) return c.refuse(unreachable);
                s = c.done(e.withK(value).withL(L), true, null, 0);
                break;
            }
            case TURN: {
                if (e.isStraight()) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                double th = Math.toRadians(value);
                if (e.isHelical()) {
                    s = solveHelixRing(c, Ring.TURN, value);
                    break;
                }
                double kap = e.constantK() ? e.k0() : (e.k0() + e.k1()) / 2;
                if (Math.abs(kap) < KAPPA_INF || e.has(L_LEN)) {
                    if (e.has(L_K0) && (Math.abs(kap) < KAPPA_INF || e.has(L_LEN))) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                    s = c.done(e.withK(th / e.L()), true, null, 0);
                    break;
                }
                double kk = Math.signum(th) == 0 ? kap : Math.signum(th) * Math.abs(kap);
                double L = Math.abs(th) < 1e-15 ? e.L() : Math.abs(th / kk);
                if (L < MIN_LEN) return c.refuse(unreachable);
                s = c.done((e.constantK() ? e.withK(kk) : e.withK0(Math.signum(th) * Math.abs(e.k0())).withK1(Math.signum(th) * Math.abs(e.k1()))).withL(L), true, null, 0);
                break;
            }
            case DIR: {
                if (e.isHelical()) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (e.isStraight()) {
                    int j = prevCurve(els, k);
                    if (j < 0) return c.refuse(hasUpstream ? SectionNames.SEAM_OWNS_WHY : SectionNames.ANCHOR_OWNS_WHY);
                    return solveTypedBox(els, entry, j, Box.DIR, value, chart, hasUpstream);
                }
                if (c.f0.vertical()) return c.refuse(SectionNames.DIR_INHERITED_WHY);
                s = c.done(e.withDir(value), true, null, 0);
                break;
            }
            case HEADING: case ELEVATION: {
                boolean head = box == Box.HEADING;
                if (e.isStraight()) {
                    int j = prevCurve(els, k);
                    if (j < 0) return c.refuse(hasUpstream ? SectionNames.SEAM_OWNS_WHY : SectionNames.ANCHOR_OWNS_WHY);
                    if (lockRank(els.get(j)) >= 3) return c.refuse(SectionNames.PREV_CURVE_LOCKED_WHY);
                    Solution sj = solveTypedBox(els, entry, j, box, value, chart, hasUpstream);
                    return sj;
                }
                if (!head && Math.abs(value) >= 90) return c.refuse(SectionNames.VERTICAL_WHY);
                if (head && c.end.vertical()) return c.refuse(SectionNames.VERTICAL_WHY);
                if (e.isHelical()) {
                    s = solveHelixRing(c, head ? Ring.HEADING : Ring.ELEVATION, value);
                    break;
                }
                if (!e.constantK()) return c.refuse(SectionNames.RAMP_SOLVE_WHY);
                double h = head ? value : c.end.headingDeg();
                double el = head ? c.end.elevationDeg() : value;
                if (Double.isNaN(h)) h = 0;
                s = tangentRow(c, tangentOf(h, el));
                break;
            }
            case LEN: {
                if (value < MIN_LEN) return c.refuse(unreachable);
                if (e.isStraight()) { s = c.done(e.withL(value), true, null, 0); break; }
                if (e.isPlanar() && e.has(L_K0) && e.has(L_TURN)) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (e.isPlanar() && e.has(L_TURN)) {
                    double th = e.turnRad();
                    s = c.done(e.withK(th / value), true, null, 0);
                    break;
                }
                s = c.done(e.withL(value), true, null, 0);
                break;
            }
            case K1: {
                if (e.isStraight()) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (Math.abs(value) > KAPPA_MAX) return c.refuse(unreachable);
                if (e.kLink()) return solveTypedBox(els, entry, k, Box.KAPPA, value, chart, hasUpstream);
                if (e.isHelical()) { s = c.done(e.withK1(value), true, null, 0); break; }
                double th = e.turnRad();
                double sum = e.k0() + value;
                if (Math.abs(value) < KAPPA_INF && Math.abs(e.k0()) < KAPPA_INF) {
                    s = c.done(e.withK1(0), true, null, 0);
                    break;
                }
                if (Math.abs(sum) < 1e-15) return c.refuse(unreachable);
                double L = e.has(L_LEN) ? e.L() : Math.abs(2 * th / sum);
                if (L < MIN_LEN) return c.refuse(unreachable);
                s = c.done(e.withK1(value).withL(L), true, null, 0);
                break;
            }
            default: {   // B1
                if (!e.isHelical()) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                if (Math.abs(value) > B1_MAX) return c.refuse(SectionNames.HELIX_ELEV_WHY);
                if (e.bLink()) return c.refuse(SectionNames.FULLY_CONSTRAINED_WHY);
                s = solveHelixRing(c, Ring.ELEVATION, value);
                break;
            }
        }
        if (s.refused()) return s;
        if (s.status() == Status.PROJECTED) return Solution.refuse(new ArrayList<>(els),
            s.why() != null ? s.why() : unreachable);
        return absorbInto(s, els, entry, k, fr0[k + 1]);
    }

    static boolean isBackward(Ctx c, Vec3 target) {
        Vec3 d = target.sub(c.f0.p());
        return perp(c.f0, d).length() <= ON_SET * Math.max(1, d.length()) && d.dot(c.f0.T()) <= 0;
    }

    // =================================================================
    // §4.9 — insert at a border · change type · delete
    // =================================================================

    /** Insert §1.1's default element of the kind at border b (between
     *  elements b−1 and b; b = 0 the section entry, b = n the end), then
     *  the absorb — or §4.9's two solves against a LOCKED end of element
     *  b. The result's insertedAt = b. */
    public static Solution insertAt(List<ElemParams> els, Frame entry, int b,
                                    StraightCurveSection.Kind kind) {
        int n = els.size();
        if (b < 0 || b > n) return Solution.refuse(new ArrayList<>(els), SectionNames.ALL_LOCKED_WHY);
        Frame[] fr = frames(entry, els);
        Frame fb = fr[b];
        if (kind == StraightCurveSection.Kind.HELICAL && fb.vertical()) {
            return Solution.refuse(new ArrayList<>(els), SectionNames.HELIX_VERTICAL_WHY);
        }
        ElemParams ne = ElemParams.defaultOf(kind, fb.elevationDeg());
        List<ElemParams> out = new ArrayList<>(els);
        out.add(b, ne);
        // a second locked node beyond element b
        for (int m = b + 1; m < n; m++) {
            if ((els.get(m).locks() & L_XYZ) != 0) {
                return Solution.refuse(new ArrayList<>(els),
                    SectionNames.insertBlockedWhy(SectionNames.elementName(m), SectionNames.LOCK_POS_QUANTITY));
            }
        }
        if (b < n && (els.get(b).locks() & L_XYZ) != 0) {
            ElemParams eb = els.get(b);
            Vec3 pLock = fr[b + 1].p();
            if (eb.isStraight()) {
                return Solution.refuse(new ArrayList<>(els),
                    SectionNames.insertLockWhy(SectionNames.elementName(b)));
            }
            if (kind == StraightCurveSection.Kind.STRAIGHT) {
                // element b keeps κ and re-solves Θ / dir for the new
                // straight's length: L = Δ_T ∓ √(2Δ_N/κ − Δ_N²)
                if (!eb.isPlanar() || !eb.constantK() || eb.has(L_TURN) || eb.has(L_DIR)) {
                    return Solution.refuse(new ArrayList<>(els),
                        SectionNames.insertLockWhy(SectionNames.elementName(b)));
                }
                Vec3 delta = pLock.sub(fb.p());
                double dT = delta.dot(fb.T());
                Vec3 pp = perp(fb, delta);
                double dN = pp.length();
                double kap = Math.abs(eb.k0());
                if (kap < KAPPA_INF) {
                    return Solution.refuse(new ArrayList<>(els),
                        SectionNames.insertLockWhy(SectionNames.elementName(b)));
                }
                double disc = 2 * dN / kap - dN * dN;
                if (disc < 0) {
                    return Solution.refuse(new ArrayList<>(els),
                        SectionNames.insertLockWhy(SectionNames.elementName(b)));
                }
                double rt = Math.sqrt(disc);
                double best = Double.NaN;
                for (double Ls : new double[]{ dT - rt, dT + rt }) {
                    if (Ls < MIN_LEN) continue;
                    if (Double.isNaN(best) || Math.abs(Ls - 10) < Math.abs(best - 10)) best = Ls;
                }
                if (Double.isNaN(best)) {
                    return Solution.refuse(new ArrayList<>(els),
                        SectionNames.insertLockWhy(SectionNames.elementName(b)));
                }
                out.set(b, ne.withL(best));
                // b's own (Θ, dir) from its new entry to the locked point,
                // κ fixed — the circle's exact point (the position lock is
                // what the solve honours, so it is lifted for the solve)
                out.set(b + 1, eb.withLocks((eb.locks() & ~L_XYZ) | L_K0));
                Ctx cc = new Ctx(out, entry, b + 1, Chart.LOCAL);
                Solution sb = solvePlanar(cc, pLock);
                if (sb.refused()) return Solution.refuse(new ArrayList<>(els), SectionNames.insertLockWhy(SectionNames.elementName(b)));
                out = sb.params();
                out.set(b + 1, out.get(b + 1).withLocks(eb.locks()));
                Frame[] nf = frames(entry, out);
                double rot = angleDeg(nf[b + 2].T(), fr[b + 1].T());
                return new Solution(out, Status.EXACT, null, rot, nf[b + 1].p(), 0, b, -1);
            }
            // a CURVE insert: the new element takes its default shape; the
            // next curve (element b) re-solves by I1 (κ free) from the
            // default's exit, its exit-T change the residual
            if (eb.isStraight() || eb.has(L_TURN) || eb.has(L_DIR)) {
                return Solution.refuse(new ArrayList<>(els),
                    SectionNames.insertLockWhy(SectionNames.elementName(b)));
            }
            out.set(b + 1, eb.withLocks(eb.locks() & ~L_XYZ));
            Ctx cc = new Ctx(out, entry, b + 1, Chart.LOCAL);
            Solution sb = eb.isHelical() ? solveHelix(cc, pLock) : i1Free(cc, pLock);
            if (sb.refused() || sb.status() != Status.EXACT) {
                // the default's exit cannot reach the locked point exactly
                return Solution.refuse(new ArrayList<>(els), SectionNames.insertLockWhy(SectionNames.elementName(b)));
            }
            out = sb.params();
            out.set(b + 1, out.get(b + 1).withLocks(eb.locks()));
            Frame[] nf = frames(entry, out);
            double rot = angleDeg(nf[b + 2].T(), fr[b + 1].T());
            return new Solution(out, rot > 1e-9 ? Status.PROJECTED : Status.EXACT,
                rot > 1e-9 ? SectionNames.absorbResidualWhy(SectionNames.elementName(b + 1)) : null,
                rot, nf[b + 1].p(), 0, b, -1);
        }
        Solution a = absorbAfter(out, entry, b, fb);
        if (a.refused()) return Solution.refuse(new ArrayList<>(els), a.why());
        return new Solution(a.params(), a.status(), a.why(), a.residualRot(), a.landedPos(), 0, b, -1);
    }

    /** §1.7's field mapping for a type change (replace in place keeping
     *  arc length, blend and the compatible locks — D26), then the
     *  absorb. Refuses a helix at a vertical entry (HELIX_VERTICAL_WHY)
     *  or past B1_MAX (HELIX_ELEV_WHY). */
    public static Solution changeType(List<ElemParams> els, Frame entry, int k,
                                      StraightCurveSection.Kind to) {
        if (k < 0 || k >= els.size()) return Solution.refuse(new ArrayList<>(els), SectionNames.ALL_LOCKED_WHY);
        ElemParams e = els.get(k);
        Frame[] fr = frames(entry, els);
        if (to == e.kind()) return new Solution(new ArrayList<>(els), Status.EXACT, null, 0, fr[k + 1].p(), 0);
        ElemParams ne;
        int keep = e.locks() & (L_LEN | L_XYZ | L_HEAD | L_ELEV);
        switch (to) {
            case STRAIGHT:
                ne = new ElemParams(to, e.L(), 0, 0, true, LIN, 0, 0, false, LIN, e.blendIn(), keep);
                break;
            case PLANAR:
                if (e.isStraight()) {
                    ne = new ElemParams(to, e.L(), 0, 0, true, LIN, 0, 0, false, LIN, e.blendIn(), keep);
                } else {
                    ne = new ElemParams(to, e.L(), e.k0(), e.k1(), e.kLink(), e.kInterp(), 0, 0, false, LIN,
                        e.blendIn(), keep | (e.locks() & (L_K0 | L_K1 | L_TURN)));
                }
                break;
            default: {
                if (fr[k].vertical()) return Solution.refuse(new ArrayList<>(els), SectionNames.HELIX_VERTICAL_WHY);
                double b0 = fr[k].elevationDeg();
                double b1 = e.isPlanar() ? fr[k + 1].elevationDeg() : b0;
                if (Math.abs(b1) > B1_MAX || Math.abs(b0) >= B1_MAX) {
                    return Solution.refuse(new ArrayList<>(els), SectionNames.HELIX_ELEV_WHY);
                }
                if (e.isStraight()) {
                    ne = new ElemParams(to, e.L(), 0, 0, true, LIN, 0, b1, false, LIN, e.blendIn(), keep);
                } else {
                    ne = new ElemParams(to, e.L(), e.k0(), e.k1(), e.kLink(), e.kInterp(), 0, b1, false, LIN,
                        e.blendIn(), keep | (e.locks() & (L_K0 | L_K1 | L_TURN)));
                }
            }
        }
        List<ElemParams> out = new ArrayList<>(els);
        out.set(k, ne);
        Solution a = absorbAfter(out, entry, k, fr[k + 1]);
        if (a.refused()) return Solution.refuse(new ArrayList<>(els), a.why());
        return a;
    }

    /** Delete element k: absorbAfter(k − 1, exitOld = the deleted
     *  element's old exit); a downstream X/Y/Z lock refuses with
     *  deleteLockWhy. The result's deletedAt = k. */
    public static Solution deleteAt(List<ElemParams> els, Frame entry, int k) {
        int n = els.size();
        if (k < 0 || k >= n || n <= 1) return Solution.refuse(new ArrayList<>(els), SectionNames.ALL_LOCKED_WHY);
        Frame[] fr = frames(entry, els);
        for (int m = k + 1; m < n; m++) {
            if ((els.get(m).locks() & L_XYZ) != 0) {
                int c = absorberAfter(els, k);
                if (m == c) continue;
                return Solution.refuse(new ArrayList<>(els), SectionNames.deleteLockWhy(
                    SectionNames.elementName(k), SectionNames.LOCK_POS_QUANTITY,
                    SectionNames.elementName(m)));
            }
        }
        List<ElemParams> out = new ArrayList<>(els);
        out.remove(k);
        Solution a = absorbAfter(out, entry, k - 1, fr[k + 1]);
        if (a.refused()) return Solution.refuse(new ArrayList<>(els), a.why());
        return new Solution(a.params(), a.status(), a.why(), a.residualRot(), a.landedPos(), 0, -1, k);
    }

    /** §8.7's closed-tail rule for the node before a trailing straight:
     *  the connected end leaves it nothing to move (NOTHING_TO_MOVE_WHY);
     *  null otherwise. */
    public static String closedTailEditWhy(List<ElemParams> els, int k) {
        int n = els.size();
        if (n >= 2 && k == n - 2 && els.get(n - 1).isStraight()) return SectionNames.NOTHING_TO_MOVE_WHY;
        return null;
    }

    /** A legacy reversed section is read-only for every s/c op. */
    public static String reversedWhy(StraightCurveSection sc) {
        return sc != null && sc.reversed() ? SectionNames.REVERSED_WHY : null;
    }

    // =================================================================
    // applying a solution to the model
    // =================================================================

    /** Write a solution's params onto the section's elements — the list
     *  ops add / remove the element object (a new element carries §2.1's
     *  default lane), every length through Element.setLength. False when
     *  the solution was refused or its shape does not match. */
    public static boolean apply(StraightCurveSection sc, Solution s) {
        if (s == null || s.refused() || sc == null) return false;
        List<StraightCurveSection.Element> els = sc.elements;
        List<ElemParams> ps = s.params();
        if (s.insertedAt() >= 0) {
            if (ps.size() != els.size() + 1) return false;
            els.add(s.insertedAt(), ps.get(s.insertedAt()).toElement());
        } else if (s.deletedAt() >= 0) {
            if (ps.size() != els.size() - 1) return false;
            els.remove(s.deletedAt());
        } else if (ps.size() != els.size()) {
            return false;
        }
        for (int i = 0; i < els.size(); i++) ps.get(i).applyTo(els.get(i));
        return true;
    }

    // =================================================================
    // §4.10 — the tail solves
    // =================================================================

    /** The ONE tail-solve name (§4.10): ALIGN_PREV / CLOSE_POSE take a
     *  Pose target (position + heading + elevation, canonical — the
     *  caller converted the seam's or the head's pose through
     *  SectionFrame); CLOSE_POS a Point (a Pose's position is used);
     *  SNAP_HEADING / SNAP_ELEVATION a Pose whose OTHER angle is ignored
     *  (the current exit's is kept — D22's re-measure). */
    public static Solution solveTail(List<ElemParams> els, Frame entry, Target target, TailMode mode) {
        if (els == null || els.isEmpty()) {
            return Solution.refuse(new ArrayList<>(), SectionNames.ALIGN_NO_CURVE_WHY);
        }
        Vec3 p;
        double head = Double.NaN, elev = Double.NaN;
        if (target instanceof Target.Point pt) p = pt.p();
        else if (target instanceof Target.Pose ps) { p = ps.p(); head = ps.headingDeg(); elev = ps.elevationDeg(); }
        else return Solution.refuse(new ArrayList<>(els), SectionNames.SNAP_ONE_WHY);
        switch (mode) {
            case CLOSE_POS: return positionOnly(els, entry, p);
            case ALIGN_PREV: case CLOSE_POSE: {
                if (Double.isNaN(head) || Double.isNaN(elev) || Math.abs(elev) >= 90 - 1e-9) {
                    return Solution.refuse(new ArrayList<>(els), SectionNames.HEAD_VERTICAL_WHY);
                }
                return poseSolve(els, entry, p, tangentOf(head, elev), false);
            }
            default: {
                Frame[] fr = frames(entry, els);
                Frame ex = fr[els.size()];
                boolean snapHead = mode == TailMode.SNAP_HEADING;
                if (snapHead && (Double.isNaN(head) || ex.vertical())) {
                    return Solution.refuse(new ArrayList<>(els), SectionNames.HEAD_VERTICAL_WHY);
                }
                if (!snapHead && (Double.isNaN(elev) || Math.abs(elev) >= 90 - 1e-9)) {
                    return Solution.refuse(new ArrayList<>(els), SectionNames.HEAD_VERTICAL_WHY);
                }
                double h = snapHead ? head : ex.headingDeg();
                double el = snapHead ? ex.elevationDeg() : elev;
                if (Double.isNaN(h)) h = 0;
                return snapOne(els, entry, p, h, el, snapHead);
            }
        }
    }

    /** Position only: the last curve takes I1 (straights after it of
     *  total ℓ: I5 — the straight-node solve with the trailing lengths
     *  held; straights before it translate). */
    static Solution positionOnly(List<ElemParams> els, Frame entry, Vec3 p) {
        int n = els.size();
        int c = prevCurve(els, n);
        if (c < 0) return Solution.refuse(new ArrayList<>(els), SectionNames.LONE_STRAIGHT_WHY);
        Frame[] fr = frames(entry, els);
        ElemParams ec = els.get(c);
        if (c == n - 1) {
            if (p.sub(fr[c].p()).length() <= 1e-12 && ec.isPlanar()) {
                // the head sits at the curve's start: keep κ and dir, the
                // whole lap nearest the hint
                double kap = ec.k0();
                if (Math.abs(kap) < KAPPA_INF) return Solution.refuse(new ArrayList<>(els), SectionNames.REACH_RAY_WHY);
                double hintAbs = Math.abs(ec.turnRad());
                double phi = lapTurn(0, Math.max(hintAbs, TWO_PI));
                if (phi == 0) phi = TWO_PI;
                ElemParams ne = ec.withL(phi / Math.abs(kap));
                List<ElemParams> out = new ArrayList<>(els);
                out.set(c, ne);
                Frame[] nf = frames(entry, out);
                return new Solution(out, Status.EXACT, null, 0, nf[n].p(), 0);
            }
            return project(els, entry, c, p, Chart.LOCAL);
        }
        // trailing straights: hold their lengths (the last one's LEN bit
        // on for the solve, restored after)
        List<ElemParams> held = new ArrayList<>(els);
        ElemParams last = els.get(n - 1);
        held.set(n - 1, last.withLocks(last.locks() | L_LEN));
        Solution s = project(held, entry, n - 1, p, Chart.LOCAL);
        if (s.refused()) {
            if (SectionNames.STRAIGHT_ALL_LOCKED_WHY.equals(s.why())) {
                return Solution.refuse(new ArrayList<>(els), SectionNames.ALIGN_LOCKED_WHY);
            }
            return s;
        }
        List<ElemParams> out = new ArrayList<>(s.params());
        out.set(n - 1, out.get(n - 1).withLocks(last.locks()));
        return new Solution(out, s.status(), s.why(), 0, s.landedPos(), s.iterations());
    }

    /** A tail curve the biarc may re-solve: PLANAR, constant κ, TURN and
     *  DIR unlocked (a helical / ramped curve is TAIL_KIND_WHY's case). */
    static boolean tailFree(ElemParams e) {
        return e.isPlanar() && e.constantK() && !e.has(L_TURN) && !e.has(L_DIR);
    }

    /** The 5-constraint pose solve: the biarc on the last two free
     *  curves A < B with the fixed tie-break (§4.10). */
    static Solution poseSolve(List<ElemParams> els, Frame entry, Vec3 target, Vec3 T1, boolean snap) {
        int n = els.size();
        List<Integer> curves = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!els.get(i).isStraight()) curves.add(i);
        if (curves.isEmpty()) return Solution.refuse(new ArrayList<>(els), SectionNames.ALIGN_NO_CURVE_WHY);
        int B = curves.get(curves.size() - 1);
        int A = curves.size() >= 2 ? curves.get(curves.size() - 2) : -1;
        ElemParams eB = els.get(B);
        if (!eB.isPlanar() || !eB.constantK()) return Solution.refuse(new ArrayList<>(els), SectionNames.TAIL_KIND_WHY);
        if (A >= 0) {
            ElemParams eA = els.get(A);
            if (!eA.isPlanar() || !eA.constantK()) return Solution.refuse(new ArrayList<>(els), SectionNames.TAIL_KIND_WHY);
            boolean lockedA = eA.has(L_TURN) || eA.has(L_DIR), lockedB = eB.has(L_TURN) || eB.has(L_DIR);
            if (lockedA || lockedB) {
                // one locked leaves ONE free curve: the s-c-s route if a
                // trailing straight is free, else ALIGN_LOCKED_WHY
                if (!lockedB && B < n - 1 && !els.get(n - 1).has(L_LEN)) {
                    return scsSolve(els, entry, B, target, T1, snap);
                }
                return Solution.refuse(new ArrayList<>(els), SectionNames.ALIGN_LOCKED_WHY);
            }
            return biarcSolve(els, entry, A, B, target, T1, snap);
        }
        // a single curve
        if (eB.has(L_TURN) || eB.has(L_DIR)) return Solution.refuse(new ArrayList<>(els), SectionNames.ALIGN_LOCKED_WHY);
        if (B < n - 1 && !els.get(n - 1).has(L_LEN)) return scsSolve(els, entry, B, target, T1, snap);
        return Solution.refuse(new ArrayList<>(els), snap ? SectionNames.SNAP_ONE_WHY : SectionNames.TWO_CURVES_WHY);
    }

    /** One biarc family member: {κ_A, θ_A, dir_A, L_A, κ_B, θ_B, dir_B,
     *  L_B} or null (no positive a). */
    static double[] biarcAt(double t, Frame fA, Hint hA, Hint hB, Vec3 P1, Vec3 T1, double lam, Vec3 upB) {
        Vec3 T0 = fA.T(), d = P1.sub(fA.p());
        double r = (1 - t) / t;
        Vec3 v = T0.add(T1.scale(r));
        double alpha = v.dot(v) - sq(1 + r);
        double bq = d.dot(v) + lam * (1 + r);
        double cq = d.dot(d) - lam * lam;
        double a;
        if (Math.abs(alpha) < 1e-12) {
            if (Math.abs(bq) < 1e-300) return null;
            a = cq / (2 * bq);
        } else {
            double disc = bq * bq - alpha * cq;
            if (disc < 0) return null;
            double sd = Math.sqrt(disc);
            double a1 = (bq - sd) / alpha, a2 = (bq + sd) / alpha;
            a = Double.NaN;
            for (double cand : new double[]{ Math.min(a1, a2), Math.max(a1, a2) }) {
                if (cand > 1e-12) { a = cand; break; }
            }
            if (Double.isNaN(a)) return null;
        }
        if (!(a > 1e-12)) return null;
        double b = r * a;
        Vec3 MA = fA.p().add(T0.scale(a));
        Vec3 MB = P1.sub(T1.scale(b));
        Vec3 D = MB.sub(MA);
        double dl = D.length();
        if (dl < 1e-12) return null;
        Vec3 u = D.scale(1 / dl);
        Vec3 JA = MA.add(u.scale(a));
        Vec3 JB = JA.add(u.scale(lam));
        // arc A: I1 from fA to JA
        double[] arcA = arcI1(fA, JA, hA);
        if (arcA == null) return null;
        Frame fB = Frame.level(JB, u, upB);
        double[] arcB = arcI1(fB, P1, hB);
        if (arcB == null) return null;
        return new double[]{ arcA[0], arcA[1], arcA[2], arcA[3], arcB[0], arcB[1], arcB[2], arcB[3] };
    }

    /** I1 (nothing locked, lap 0) from a frame to a point: {κ, θ, dir,
     *  L} in the representation nearest the hint; null on the axis. */
    static double[] arcI1(Frame f, Vec3 p, Hint h) {
        Vec3 delta = p.sub(f.p());
        Vec3 pp = perp(f, delta);
        double dn = pp.length();
        double dT = delta.dot(f.T());
        if (dn <= ON_SET * Math.max(1, delta.length())) {
            if (dT <= 0) return null;
            return new double[]{ 0, 0, h.dirDeg(), dT };
        }
        double[] rep = pickRep(f, pp.scale(1 / dn), h);
        double dN = dn * rep[1];
        double kap = 2 * dN / (dT * dT + dN * dN);
        double phi = 2 * Math.atan2(Math.abs(dN), dT);
        double L = Math.abs(kap) < 1e-300 ? dT : phi / Math.abs(kap);
        return new double[]{ kap, Math.signum(dN) * phi, rep[0], L };
    }

    static Solution biarcSolve(List<ElemParams> els, Frame entry, int A, int B, Vec3 target, Vec3 T1, boolean snap) {
        int n = els.size();
        ElemParams eA = els.get(A), eB = els.get(B);
        boolean kA = eA.has(L_K0), kB = eB.has(L_K0);
        if (kA && kB) return Solution.refuse(new ArrayList<>(els), SectionNames.TWO_CURVES_WHY);
        Frame[] fr = frames(entry, els);
        double lam = lambdaBetween(els, A, B);
        double ell = 0;
        for (int i = B + 1; i < n; i++) ell += els.get(i).L();
        Vec3 P1 = target.sub(T1.scale(ell));
        Frame fA = fr[A];
        Hint hA = Hint.of(eA), hB = Hint.of(eB);
        Vec3 upB = fr[B].U();
        final double kOldA = Math.abs(eA.k0()), kOldB = Math.abs(eB.k0());
        java.util.function.DoubleUnaryOperator g = t -> {
            double[] bi = biarcAt(t, fA, hA, hB, P1, T1, lam, upB);
            if (bi == null) return Double.NaN;
            return kB && !kA ? Math.abs(bi[4]) - kOldB : Math.abs(bi[0]) - kOldA;
        };
        double lo = 1e-3, hi = 1 - 1e-3;
        double glo = g.applyAsDouble(lo), ghi = g.applyAsDouble(hi);
        double t;
        String notice = null;
        boolean fallback = false;
        if (Double.isNaN(glo) || Double.isNaN(ghi) || Math.signum(glo) == Math.signum(ghi)) {
            if (kA || kB) return Solution.refuse(new ArrayList<>(els), SectionNames.TWO_CURVES_WHY);
            t = 0.5;
            fallback = true;
            notice = SectionNames.BIARC_FALLBACK_WHY;
        } else {
            t = bisect(g, lo, hi);
            if (Double.isNaN(t)) { t = 0.5; fallback = true; notice = SectionNames.BIARC_FALLBACK_WHY; }
        }
        double[] bi = biarcAt(t, fA, hA, hB, P1, T1, lam, upB);
        if ((bi == null || !biarcOk(bi)) && !kA && !kB) {
            // the kept-curvature member is outside the model (or none at
            // this t): the gentlest admissible member of the family by a
            // bounded golden section over t (≤ GOLDEN_MAX) — an honest
            // landing beats a refusal when a solution exists
            final double[] bestT = { Double.MAX_VALUE, 0.5 };
            golden(u -> {
                double[] b2 = biarcAt(u, fA, hA, hB, P1, T1, lam, upB);
                double cost = b2 == null ? Double.MAX_VALUE : !biarcOk(b2) ? 1e6 + Math.abs(b2[0]) + Math.abs(b2[4])
                    : Math.max(Math.abs(b2[0]), Math.abs(b2[4]));
                if (cost < bestT[0]) { bestT[0] = cost; bestT[1] = u; }
                return cost;
            }, lo, hi, GOLDEN_MAX);
            t = bestT[1];
            bi = biarcAt(t, fA, hA, hB, P1, T1, lam, upB);
            fallback = true;
            notice = SectionNames.BIARC_FALLBACK_WHY;
        }
        if (bi == null || !biarcOk(bi)) {
            return Solution.refuse(new ArrayList<>(els), SectionNames.TWO_CURVES_WHY);
        }
        List<ElemParams> out = new ArrayList<>(els);
        out.set(A, eA.withK(bi[0]).withDir(bi[2]).withL(bi[3]));
        out.set(B, eB.withK(bi[4]).withDir(bi[6]).withL(bi[7]));
        Frame[] nf = frames(entry, out);
        Frame ex = nf[n];
        boolean met = ex.p().sub(target).length() <= 1e-9 && angleDeg(ex.T(), T1) <= 1e-9 * 180 / Math.PI;
        return new Solution(out, fallback || !met ? Status.PROJECTED : Status.EXACT, notice, 0, ex.p(),
            fallback ? 0 : BISECT_MAX);
    }

    static boolean biarcOk(double[] bi) {
        return Math.abs(bi[0]) <= KAPPA_MAX && Math.abs(bi[4]) <= KAPPA_MAX && bi[3] >= MIN_LEN && bi[7] >= MIN_LEN;
    }

    /** A straight–curve–straight tail (one free curve C + a trailing
     *  straight of free length): θ and dir from the tangent pair, then
     *  I6 for (1/κ, ℓ); solvable only within the seam's plane. For the
     *  single snap the plane test is replaced by the azimuth rule. */
    static Solution scsSolve(List<ElemParams> els, Frame entry, int C, Vec3 target, Vec3 T1, boolean snap) {
        int n = els.size();
        Frame[] fr = frames(entry, els);
        ElemParams eC = els.get(C);
        Frame fC = fr[C];
        Vec3 T0 = fC.T();
        double lamFixed = 0;
        for (int i = C + 1; i < n - 1; i++) lamFixed += els.get(i).L();
        Vec3 d = target.sub(fC.p());
        Vec3 nrm = T0.cross(T1);
        double nl = nrm.length();
        double theta, dir;
        if (nl < 1e-12) {
            // parallel tangents: a straight line (θ = 0) — the target must
            // sit ahead on T0
            if (perp(fC, d).length() > 1e-9) return Solution.refuse(new ArrayList<>(els), SectionNames.SCS_PLANE_WHY);
            double tot = d.dot(T0);
            double LC = eC.L();
            double ellLast = tot - LC - lamFixed;
            if (ellLast < MIN_LEN) return Solution.refuse(new ArrayList<>(els), SectionNames.TWO_CURVES_WHY);
            List<ElemParams> out = new ArrayList<>(els);
            out.set(C, eC.withK(0));
            out.set(n - 1, els.get(n - 1).withL(ellLast));
            Frame[] nf = frames(entry, out);
            return new Solution(out, Status.EXACT, null, 0, nf[n].p(), 0);
        }
        if (Math.abs(d.dot(nrm.scale(1 / nl))) > 1e-9) {
            return Solution.refuse(new ArrayList<>(els), SectionNames.SCS_PLANE_WHY);
        }
        double[] td = inv2(fC, T1, Hint.of(eC), eC);
        if (td == null) return Solution.refuse(new ArrayList<>(els), SectionNames.SCS_PLANE_WHY);
        theta = td[0];
        dir = td[1];
        Vec3 N = fC.N(dir);
        double dT = d.dot(T0), dN = d.dot(N);
        double[] r = inv6(dT, dN, theta, lamFixed + MIN_LEN, lamFixed);
        if (r == null || r[4] != 1) return Solution.refuse(new ArrayList<>(els), SectionNames.TWO_CURVES_WHY);
        List<ElemParams> out = new ArrayList<>(els);
        out.set(C, eC.withK(r[1]).withDir(dir).withL(r[2]));
        out.set(n - 1, els.get(n - 1).withL(r[3]));
        Frame[] nf = frames(entry, out);
        Frame ex = nf[n];
        boolean met = ex.p().sub(target).length() <= 1e-9 && angleDeg(ex.T(), T1) <= 1e-9 * 180 / Math.PI;
        return new Solution(out, met ? Status.EXACT : Status.PROJECTED, null, 0, ex.p(), 0);
    }

    /** The single snap (4 constraints): two free curves → the biarc with
     *  T1 := (the snapped angle, the current other angle); one free
     *  curve + a trailing straight of free length → the square system
     *  of §4.10 (dir := the azimuth of Δ⊥, Θ from the angle equation,
     *  then I6); a lone curve → SNAP_ONE_WHY. */
    static Solution snapOne(List<ElemParams> els, Frame entry, Vec3 target, double headDeg, double elevDeg,
                            boolean snapHead) {
        int n = els.size();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < n; i++) if (tailFree(els.get(i))) free.add(i);
        List<Integer> curves = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!els.get(i).isStraight()) curves.add(i);
        if (curves.isEmpty()) return Solution.refuse(new ArrayList<>(els), SectionNames.ALIGN_NO_CURVE_WHY);
        int B = curves.get(curves.size() - 1);
        if (!tailFree(els.get(B))) {
            ElemParams eB = els.get(B);
            if (!eB.isPlanar() || !eB.constantK()) return Solution.refuse(new ArrayList<>(els), SectionNames.TAIL_KIND_WHY);
            return Solution.refuse(new ArrayList<>(els), SectionNames.ALIGN_LOCKED_WHY);
        }
        Vec3 T1 = tangentOf(headDeg, elevDeg);
        if (curves.size() >= 2 && tailFree(els.get(curves.get(curves.size() - 2)))) {
            return biarcSolve(els, entry, curves.get(curves.size() - 2), B, target, T1, true);
        }
        if (B == n - 1 || els.get(n - 1).has(L_LEN)) {
            return Solution.refuse(new ArrayList<>(els), SectionNames.SNAP_ONE_WHY);
        }
        Frame[] fr = frames(entry, els);
        ElemParams eC = els.get(B);
        Frame fC = fr[B];
        Vec3 T = fC.T();
        Vec3 d = target.sub(fC.p());
        Vec3 pp = perp(fC, d);
        double dn = pp.length();
        if (dn <= 1e-12) return Solution.refuse(new ArrayList<>(els), SectionNames.SNAP_ONE_WHY);
        Vec3 N = pp.scale(1 / dn);
        double[] rep = pickRep(fC, N, Hint.of(eC));
        double dir = rep[0];
        Vec3 Nd = fC.N(dir);   // = ±N
        double side = Nd.dot(N) < 0 ? -1 : 1;
        double hintTh = Math.toRadians(Hint.of(eC).thetaDeg());
        double theta = Double.NaN;
        if (snapHead) {
            double psi = Math.toRadians(headDeg);
            double sp = Math.sin(psi), cp = Math.cos(psi);
            double Aq = T.x() * sp + T.z() * cp, Bq = N.x() * sp + N.z() * cp;
            double th0 = Math.atan2(-Aq, Bq);
            double best = Double.NaN, bd = Double.MAX_VALUE;
            for (double cand : new double[]{ th0, th0 + Math.PI, th0 - Math.PI }) {
                if (cand <= 0 || cand >= TWO_PI) continue;
                Vec3 Tout = T.scale(Math.cos(cand)).add(N.scale(Math.sin(cand)));
                if (Tout.x() * cp - Tout.z() * sp <= 0) continue;
                double dd = Math.abs(cand * side - hintTh);
                if (dd < bd) { bd = dd; best = cand; }
            }
            theta = best;
        } else {
            double se = Math.sin(Math.toRadians(elevDeg));
            double Ap = T.y(), Bp = N.y();
            double R = Math.hypot(Ap, Bp);
            if (R > 1e-15 && Math.abs(se) <= R * (1 + 1e-12)) {
                double base = Math.atan2(Bp, Ap), off = Math.acos(Math.max(-1, Math.min(1, se / R)));
                double best = Double.NaN, bd = Double.MAX_VALUE;
                for (double cand : new double[]{ base + off, base - off, base + off + TWO_PI, base - off + TWO_PI }) {
                    if (cand <= 0 || cand >= TWO_PI) continue;
                    double dd = Math.abs(cand * side - hintTh);
                    if (dd < bd) { bd = dd; best = cand; }
                }
                theta = best;
            }
        }
        if (Double.isNaN(theta)) return Solution.refuse(new ArrayList<>(els), SectionNames.SNAP_ONE_WHY);
        double lamFixed = 0;
        for (int i = B + 1; i < n - 1; i++) lamFixed += els.get(i).L();
        double dT = d.dot(T), dN = dn;   // in the (T, N) plane: N toward the target
        double[] r = inv6(dT, dN, theta, lamFixed + MIN_LEN, lamFixed);
        if (r == null || r[4] != 1 || r[1] <= 0) return Solution.refuse(new ArrayList<>(els), SectionNames.SNAP_ONE_WHY);
        // express in the stored representation (dir, side)
        double kap = r[1] * side, th = r[0] * side;
        List<ElemParams> out = new ArrayList<>(els);
        out.set(B, eC.withK(kap).withDir(dir).withL(r[2]));
        out.set(n - 1, els.get(n - 1).withL(r[3]));
        Frame[] nf = frames(entry, out);
        Frame ex = nf[n];
        boolean posOk = ex.p().sub(target).length() <= 1e-9;
        boolean angOk = snapHead ? Math.abs(nearest360(ex.headingDeg() - headDeg, 0)) <= 1e-9
            : Math.abs(ex.elevationDeg() - elevDeg) <= 1e-9;
        return new Solution(out, posOk && angOk ? Status.EXACT : Status.PROJECTED, null, 0, ex.p(), 0);
    }

    // =================================================================
    // §4.2 — the predictor's corrector (typed boxes under a blend)
    // =================================================================

    /** A typed POINT target for node k (the three coordinates — a typed
     *  X/Y/Z box with the other two held at the LANDED position) under
     *  the blend predictor: δ_k = landed − sharp, target_sharp = target −
     *  δ_k, re-measured per round (≤ CORRECTOR_MAX); still > 1 mm off →
     *  predictorOffWhy(mm) as a notice (lands, informs). probe = null →
     *  the plain sharp solve (δ = 0 — bitwise that path). */
    public static Solution solveTypedPoint(List<ElemParams> els, Frame entry, int k, Vec3 target,
                                           Chart chart, boolean hasUpstream, LandedProbe probe) {
        Frame[] fr = frames(entry, els);
        Vec3 sharp = fr[k + 1].p();
        boolean blended = false;
        for (ElemParams e : els) if (e.blendIn() > 0) blended = true;
        if (!blended) probe = null;   // D9: no blend → δ = 0 and the path is bitwise the plain one
        Vec3 delta = probe == null ? Vec3.ZERO : probe.landed(els, k + 1).sub(sharp);
        Solution best = null;
        List<ElemParams> cur = els;
        int rounds = 0;
        double miss = Double.MAX_VALUE;
        for (int round = 0; round < CORRECTOR_MAX; round++) {
            Vec3 tgtSharp = target.sub(delta);
            Solution s = project(cur, entry, k, tgtSharp, chart);
            if (s.refused()) return s;
            if (s.status() == Status.PROJECTED) {
                return Solution.refuse(new ArrayList<>(els), s.why() != null ? s.why()
                    : SectionNames.unreachableWhy(SectionNames.elementName(k), SectionNames.boxLabel(0),
                        boxValueText(Box.X, target.x())));
            }
            Solution a = absorbInto(s, els, entry, k, fr[k + 1]);
            if (a.refused()) return a;
            best = a;
            rounds = round + 1;
            if (probe == null) { miss = 0; break; }
            Vec3 landed = probe.landed(a.params(), k + 1);
            miss = landed.sub(target).length();
            if (miss <= PREDICTOR_OK_M) break;
            Frame[] nf = frames(entry, a.params());
            delta = landed.sub(nf[k + 1].p());
            cur = a.params();
        }
        if (miss > PREDICTOR_OK_M) {
            int mm = (int) Math.rint(miss * 1000);
            return new Solution(best.params(), Status.PROJECTED, SectionNames.predictorOffWhy(mm),
                best.residualRot(), best.landedPos(), rounds);
        }
        return new Solution(best.params(), best.status(), best.why(), best.residualRot(),
            best.landedPos(), rounds);
    }

    // =================================================================
    // §4.12 — the ortho plane rule (the math; the view is Stage 3b's)
    // =================================================================

    /** The in-plane target: the cursor's point with the node's OWN depth
     *  along the view normal — the node never leaves its plane. */
    public static Vec3 orthoTarget(Vec3 currentEnd, Vec3 cursorPoint, Vec3 viewNormal) {
        Vec3 nrm = viewNormal.normalize();
        return cursorPoint.add(nrm.scale(currentEnd.sub(cursorPoint).dot(nrm)));
    }

    /** A ball drag in a fixed ortho view: nothing locked → the in-plane
     *  target through the ordinary rows (EXACT, κ free); κ or Θ locked →
     *  dir locked to the view plane when the entry lies in the node's
     *  plane and T in the view plane (EXACT), else §4.11's bounded
     *  projection over dir (≤ 40) — REFUSED ORTHO_LOCK_WHY when the
     *  landing leaves the plane by more than 1e-6 m. A helix: Top keeps
     *  β1 (Δy follows), a side view keeps c_h (β1 from y). */
    public static Solution solveOrthoDrag(List<ElemParams> els, Frame entry, int k, Vec3 cursorPoint,
                                          Vec3 viewNormal, Chart chart) {
        Frame[] fr = frames(entry, els);
        Vec3 nrm = viewNormal.normalize();
        Vec3 target = orthoTarget(fr[k + 1].p(), cursorPoint, nrm);
        ElemParams e = els.get(k);
        if (e.isHelical()) {
            boolean top = Math.abs(nrm.y()) > 1 - 1e-9;
            List<ElemParams> held = new ArrayList<>(els);
            held.set(k, top ? e.withLocks(e.locks() | L_B1) : e.withLocks(e.locks() | L_K0));
            Solution s = solveNodeDrag(held, entry, k, target, chart);
            if (s.refused()) return s;
            List<ElemParams> out = new ArrayList<>(s.params());
            out.set(k, out.get(k).withLocks(e.locks()));
            return new Solution(out, s.status(), s.why(), s.residualRot(), s.landedPos(), s.iterations());
        }
        boolean K = e.has(L_K0), T = e.has(L_TURN);
        if (!e.isPlanar() || (!K && !T) || e.has(L_DIR)) return solveNodeDrag(els, entry, k, target, chart);
        Frame f = fr[k];
        boolean entryInPlane = Math.abs(f.p().sub(target).dot(nrm)) <= 1e-9;
        boolean tInPlane = Math.abs(f.T().dot(nrm)) <= 1e-9;
        Ctx c = new Ctx(els, entry, k, chart);
        if (entryInPlane && tInPlane) {
            double raw = f.dirOf(nrm);
            double dA = nearest360(raw, c.hint.dirDeg()), dB = nearest360(raw + 180, c.hint.dirDeg());
            double dir = Math.abs(dB - c.hint.dirDeg()) < Math.abs(dA - c.hint.dirDeg()) ? dB : dA;
            List<ElemParams> held = new ArrayList<>(els);
            held.set(k, e.withDir(dir).withLocks(e.locks() | L_DIR));
            Solution s = solveNodeDrag(held, entry, k, target, chart);
            if (s.refused()) return s;
            List<ElemParams> out = new ArrayList<>(s.params());
            out.set(k, out.get(k).withLocks(e.locks()));
            return new Solution(out, s.status(), s.why(), s.residualRot(), s.landedPos(), s.iterations());
        }
        // the bounded in-plane projection: for each dir u the locked curve's
        // in-plane set is its intersection with the node plane — κ locked:
        // the circle ∩ plane (0-2 points), Θ locked: the generator ∩ plane
        // (≤ 1) — golden over u (≤ GOLDEN_MAX evaluations); no candidate
        // within the model's limits anywhere in the bracket → ORTHO_LOCK_WHY
        final double dir0 = e.dirDeg();
        final double kap0 = e.k0(), th0 = e.turnRad();
        final double hintAbs = c.hintAbs();
        final double[] best = { Double.MAX_VALUE, dir0, 0, 0 };   // f, dir, κ, θ
        final Vec3 fp = f.p(), fT = f.T();
        final double cn = target.sub(fp).dot(nrm);
        golden(u -> {
            Vec3 Nu = f.N(u);
            double bf = Double.MAX_VALUE, bk = 0, bt = 0;
            if (K) {
                double s = kap0 < 0 ? -1 : 1, rho = 1 / Math.abs(kap0);
                double A = rho * fT.dot(nrm), B = s * rho * Nu.dot(nrm);
                double[] roots = solveTrigRad(A, -B, cn - B);
                for (double th : roots) {
                    if (Double.isNaN(th)) continue;
                    double phi = th - TWO_PI * Math.floor(th / TWO_PI);
                    phi = lapTurn(phi, hintAbs);
                    double L = phi * rho;
                    if (L < MIN_LEN) continue;
                    Vec3 pt = fp.add(fT.scale(rho * Math.sin(phi))).add(Nu.scale(s * rho * (1 - Math.cos(phi))));
                    double d = pt.sub(target).length();
                    if (d < bf) { bf = d; bk = kap0; bt = s * phi; }
                }
            } else {
                double s = th0 < 0 ? -1 : 1, phi = Math.abs(th0);
                double red = phi - TWO_PI * Math.floor(phi / TWO_PI);
                double sh = Math.sin(red / 2);
                Vec3 g = fT.scale(Math.cos(red / 2)).add(Nu.scale(s * sh));
                double gn = g.dot(nrm);
                if (Math.abs(gn) > 1e-15 && Math.abs(sh) > 1e-12) {
                    double chord = cn / gn;
                    if (chord > 0) {
                        double kk = 2 * Math.abs(sh) / chord;
                        double L = phi / kk;
                        if (kk <= KAPPA_MAX && L >= MIN_LEN) {
                            Vec3 pt = fp.add(g.scale(chord));
                            double d = pt.sub(target).length();
                            if (d < bf) { bf = d; bk = s * kk; bt = s * phi; }
                        }
                    }
                }
            }
            if (bf < best[0]) { best[0] = bf; best[1] = u; best[2] = bk; best[3] = bt; }
            return bf;
        }, dir0 - 90, dir0 + 90, GOLDEN_MAX);
        if (best[0] == Double.MAX_VALUE) return Solution.refuse(new ArrayList<>(els), SectionNames.ORTHO_LOCK_WHY);
        double kk = best[2], th = best[3];
        ElemParams ne = e.withDir(nearest360(best[1], c.hint.dirDeg())).withK(kk).withL(Math.abs(th / kk));
        List<ElemParams> out = new ArrayList<>(els);
        out.set(k, ne);
        Frame[] nf = frames(entry, out);
        if (Math.abs(nf[k + 1].p().sub(target).dot(nrm)) > 1e-6) {
            return Solution.refuse(new ArrayList<>(els), SectionNames.ORTHO_LOCK_WHY);
        }
        Solution s = absorbInto(new Solution(out, Status.PROJECTED, null, 0, nf[k + 1].p(), GOLDEN_MAX),
            els, entry, k, fr[k + 1]);
        return s;
    }

    /** a sin θ + b cos θ = c → the two roots (rad, NaN = none). */
    static double[] solveTrigRad(double a, double b, double c) {
        double R = Math.hypot(a, b);
        if (R < 1e-15 || Math.abs(c) > R * (1 + 1e-12)) return new double[]{ Double.NaN, Double.NaN };
        double base = Math.atan2(a, b), off = Math.acos(Math.max(-1, Math.min(1, c / R)));
        return new double[]{ base + off, base - off };
    }
}
