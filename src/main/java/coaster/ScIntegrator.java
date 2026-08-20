package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * ScIntegrator.java (Phase 2, v11.0, D68) — geometry for STRAIGHT/CURVE
 * sections. Pure Java, no JavaFX.
 *
 * The element list defines a CURVATURE-VECTOR PROFILE over arc length:
 * inside element e the per-meter rotation is
 *
 *   Omega/m = kappa · (cos(dir)·U − sin(dir)·R)        (rad/m)
 *
 * (kappa = sign(angle)/radius; straights are the zero vector). Rotating
 * about that axis leaves the axis itself invariant, so each element is
 * an EXACT circle/line — validated analytically in the gate. dir 0 =
 * flat turn (positive angle = rider-LEFT since v18.16 D187 — the
 * profile negates the U component, the D185 yaw boundary), dir 90 =
 * positive angle pulls up.
 *
 * TRANSITION SMOOTHING: at each internal element boundary the two
 * component pairs (cU, cR) blend with a sine ease over the entry
 * element's smoothIn length, CENTERED on the boundary. The ease is
 * symmetric, so the blend integrates to the mean curvature and the
 * TOTAL TURN ANGLE of a straight↔arc pair is preserved EXACTLY (single
 * fixed rotation axis throughout the blend — gated); arc↔arc blends
 * with different dir mix axes and preserve it only approximately.
 * Blend halves are clamped so no element's entry+exit blends overlap.
 * Element 0's entry and the section exit stay sharp (section seams
 * belong to boundary matching, like every other mode).
 *
 * Integration: the shape is authored in DISTANCE but downstream code
 * indexes samples by uniform ride time (POV camera, markers, rates),
 * so we RK4 in TIME on state (pos, q, s):
 *
 *   q' = ½ (0, v·(cU(s)·U + cR(s)·R)) ⊗ q,   pos' = T·v,   s' = v
 *
 * with v ALGEBRAIC from energy at the design speed (same rule as every
 * integrator: v² = vDesign² + 2g(y0 − y), floored). The curvature
 * profile is a function of s, so the SHAPE is independent of the speed
 * profile — vDesign only decides how many seconds of samples it takes.
 * The last step is Newton-sized to land s EXACTLY on the total length
 * (the exit pose is a section contract, not a sampling accident).
 *
 * Felt Gv/Gl are recorded at the design speed, inverting the force
 * equations like GeoIntegrator (Gv = ω_pitch·v/g + U.y with ω_pitch =
 * −v·cR, ω_yaw = v·cU), so rail colors, stats and '+' holds stay
 * honest over straight/curve track.
 *
 * ROLL (v11.1, D69) is per element, two modes:
 *  - RATE: a body roll rate (deg/s) joins Ω as ω_T = rate·T — a TIME
 *    rate exactly like the roll lane, NOT scaled by v (so a rate
 *    element's twist depends on the design speed; documented). Rates
 *    blend across boundaries with the same sine ease as curvature.
 *  - GLOBAL: hold rollVal as the BANK ANGLE VS THE HORIZON. Enforced
 *    as an algebraic projection after every integration leg: measure
 *    the bank (angle of U vs the level up at T, about T), roll the
 *    quaternion about T by the difference (position/T untouched — a
 *    pure T-roll never bends the spine). The target eases from the
 *    element's ENTRY bank over max(smoothIn, 2 m) (capped by the
 *    element), so bank never jumps; corrections under 1e-12 rad are
 *    skipped, which keeps planar zero-bank runs bit-identical to
 *    v11.0. Near-vertical track (|T.y| ≈ 1) has no defined bank —
 *    the projection holds off until the track leaves the pole.
 */
public final class ScIntegrator {

    private ScIntegrator() {}

    /** Samples plus the exact exit orientation (natively integrated). */
    public record Result(List<FVDIntegrator.Sample> samples, Quat qEnd) {}

    // ---------- the curvature profile ----------

    /** Piecewise curvature components with sine blends at boundaries.
     *  starts[i] = element i's start s; (cU,cR)[i] = its components;
     *  cT[i] = RATE-mode roll rate (rad/s, 0 for GLOBAL elements);
     *  half[i] = blend half-width of the boundary at starts[i] (0 for
     *  the section entry). rollGlobal/rollTarget/rollEase drive the
     *  GLOBAL-bank projection. */
    static final class Profile {
        final double[] starts, cU, cR, cT, half;
        final boolean[] rollGlobal;
        final double[] rollTarget;   // rad vs horizon
        final double[] rollEase;     // m, in-element ease length
        final double totalLen;
        /** Interior s where kappa(s) stops being smooth — element
         *  boundaries (sharp) or blend-zone edges. The integrator
         *  splits its steps here: RK4 loses two orders stepping OVER a
         *  curvature step (measured ~0.1° heading per sharp crossing at
         *  dt = 0.01), and landing on them exactly restores the
         *  analytic gates. Sorted ascending. */
        final double[] breaks;

        Profile(StraightCurveSection sc) {
            int n = sc.elements.size();
            starts = new double[n];
            cU = new double[n];
            cR = new double[n];
            cT = new double[n];
            half = new double[n];
            rollGlobal = new boolean[n];
            rollTarget = new double[n];
            rollEase = new double[n];
            double s = 0;
            for (int i = 0; i < n; i++) {
                StraightCurveSection.Element e = sc.elements.get(i);
                starts[i] = s;
                s += e.arcLen();
                double k = e.kappa();
                double d = Math.toRadians(e.dirDeg);
                // yaw component NEGATED (v18.16, D187 — the D185
                // boundary applied to s/c: dir 0 + positive angle =
                // rider-LEFT now; dir 90 pull-up untouched)
                cU[i] = -k * Math.cos(d);
                cR[i] = -k * Math.sin(d);
                half[i] = i == 0 ? 0 : Math.max(0, e.smoothIn / 2);
                boolean glob = e.rollMode == StraightCurveSection.RollMode.GLOBAL;
                rollGlobal[i] = glob;
                rollTarget[i] = glob ? Math.toRadians(e.rollVal) : 0;
                cT[i] = glob ? 0 : Math.toRadians(e.rollVal);
                // ELEMENT 0's smoothIn is EDITABLE since v19.3 (D232)
                // and taken VERBATIM since v19.4 (D233, user spec —
                // "the entry ease can go all the way to zero"): 0 = an
                // instant bank snap at the section entry. Old files
                // stored 0 there and now snap instead of the historic
                // 2 m ease — accepted (his "not attached to old
                // files"); NEW sections create element 0 with 2 m, the
                // comfortable default. Interior elements keep the
                // historic max(smoothIn, 2 m) floor.
                double ease = i == 0 ? e.smoothIn
                    : Math.max(e.smoothIn, ROLL_EASE_MIN);
                rollEase[i] = Math.max(1e-9, Math.min(e.arcLen(), ease));
            }
            totalLen = s;
            // clamp: an element's entry+exit blend halves must fit inside
            // it (no overlapping eases); scale both down proportionally
            for (int i = 0; i < n; i++) {
                double hIn = half[i];
                double hOut = i + 1 < n ? half[i + 1] : 0;
                double len = sc.elements.get(i).arcLen();
                if (hIn + hOut > len && hIn + hOut > 1e-12) {
                    double f = len / (hIn + hOut);
                    half[i] = hIn * f;
                    if (i + 1 < n) half[i + 1] = hOut * f;
                }
            }
            List<Double> br = new ArrayList<>();
            for (int b = 1; b < n; b++) {
                if (half[b] > 1e-12) {
                    br.add(starts[b] - half[b]);
                    br.add(starts[b] + half[b]);
                } else {
                    br.add(starts[b]);
                }
            }
            breaks = new double[br.size()];
            for (int i = 0; i < breaks.length; i++) breaks[i] = br.get(i);
        }

        /** Smallest breakpoint strictly beyond s (+eps); +inf if none. */
        double nextBreak(double s) {
            for (double b : breaks) {
                if (b > s + 1e-9) return b;
            }
            return Double.POSITIVE_INFINITY;
        }

        /** Element index owning arc length s (blend zones aside). */
        int elemAt(double s) {
            int i = starts.length - 1;
            while (i > 0 && s < starts[i]) i--;
            return i;
        }

        /** Curvature components (cU, cR) + RATE roll (cT) at arc
         *  length s — all three share the boundary blends. */
        void componentsAt(double s, double[] out) {
            int i = elemAt(s);
            // inside the blend zone around boundary i (entry of elem i)?
            if (i > 0 && half[i] > 1e-12 && s < starts[i] + half[i]) {
                blend(i, s, out);
                return;
            }
            // ...or around boundary i+1 (exit into the next element)?
            if (i + 1 < starts.length && half[i + 1] > 1e-12
                    && s > starts[i + 1] - half[i + 1]) {
                blend(i + 1, s, out);
                return;
            }
            out[0] = cU[i];
            out[1] = cR[i];
            out[2] = cT[i];
        }

        private void blend(int b, double s, double[] out) {
            double u = (s - (starts[b] - half[b])) / (2 * half[b]);
            u = Math.max(0, Math.min(1, u));
            double f = (1 - Math.cos(Math.PI * u)) / 2;   // sine ease
            out[0] = cU[b - 1] + (cU[b] - cU[b - 1]) * f;
            out[1] = cR[b - 1] + (cR[b] - cR[b - 1]) * f;
            // RATE roll blends with the QUINTIC since v19.4 (D233,
            // user report: "a jolt on either end of the smoothing" —
            // cT is a roll RATE, and the sine's nonzero end curvature
            // put a corner in the roll-rate graph; the quintic's
            // first AND second derivatives vanish at the ends, the
            // strict-seam smoothing's own weight). Curvature (cU/cR
            // → pitch/yaw) keeps the historic sine — "the pitch/yaw
            // is okay" — so roll-less sections stay bitwise.
            out[2] = cT[b - 1] + (cT[b] - cT[b - 1]) * quintic(u);
        }
    }

    /** The quintic smoothstep 6u⁵−15u⁴+10u³ — zero value, slope AND
     *  curvature at both ends (the strict-seam smoothing weight). */
    static double quintic(double u) {
        return u * u * u * (u * (u * 6 - 15) + 10);
    }

    /** Minimum in-element ease for a GLOBAL bank change (m) — bank must
     *  never step, even with smoothIn 0. */
    static final double ROLL_EASE_MIN = 2;

    // ---------- RK4 on (pos, q, s) ----------

    private record State(Vec3 pos, Quat q, double s, double loss) {}

    private record Deriv(Vec3 dPos, Quat dQ, double dS, double dLoss) {}

    private static Deriv deriv(State st, Profile prof, double vDesign, double y0,
                               double[] scratch, double sCap,
                               FVDIntegrator.Friction fric) {
        return deriv(st, prof, vDesign, y0, scratch, sCap, fric,
            Double.NaN);
    }

    /** drive = a speed control's d(loss)/ds for this leg (replaces
     *  friction); NaN = natural (v18.0, D160). */
    private static Deriv deriv(State st, Profile prof, double vDesign, double y0,
                               double[] scratch, double sCap,
                               FVDIntegrator.Friction fric, double drive) {
        Quat q = st.q().normalize();
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T);
        // vSafe floor: pos' and q' scale by the SAME v, so dq/ds — the
        // shape — is exact regardless; the floor only keeps the
        // distance-terminated loop finite through a stall
        double v = Math.max(0.5,
            FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss()));
        // sCap keeps every stage's lookup on THIS leg's side of the
        // breakpoint the leg lands on: the k4 stage evaluates AT the
        // endpoint, and reading the next element's kappa there costs
        // two orders of accuracy across a sharp boundary
        prof.componentsAt(Math.min(st.s(), sCap), scratch);
        // curvature terms are per METER (scaled by v); the RATE roll
        // term is per SECOND, exactly like the roll lane — not scaled
        Vec3 omega = U.scale(v * scratch[0]).add(R.scale(v * scratch[1]))
            .add(T.scale(scratch[2]));
        Quat dQ = new Quat(0, omega.x(), omega.y(), omega.z()).mul(q).scale(0.5);
        return new Deriv(T.scale(v), dQ, v,
            (Double.isNaN(drive) ? fric.dLossDs(v) : drive) * v);
    }

    private static State advance(State y, Deriv k, double h) {
        return new State(y.pos().add(k.dPos().scale(h)),
            y.q().add(k.dQ().scale(h)), y.s() + k.dS() * h,
            y.loss() + k.dLoss() * h);
    }

    private static State rk4(State y, Profile prof, double vDesign, double y0,
                             double h, double[] scratch, double sCap,
                             FVDIntegrator.Friction fric) {
        return rk4(y, prof, vDesign, y0, h, scratch, sCap, fric,
            Double.NaN);
    }

    private static State rk4(State y, Profile prof, double vDesign, double y0,
                             double h, double[] scratch, double sCap,
                             FVDIntegrator.Friction fric, double drive) {
        Deriv k1 = deriv(y, prof, vDesign, y0, scratch, sCap, fric, drive);
        Deriv k2 = deriv(advance(y, k1, h / 2), prof, vDesign, y0, scratch, sCap, fric, drive);
        Deriv k3 = deriv(advance(y, k2, h / 2), prof, vDesign, y0, scratch, sCap, fric, drive);
        Deriv k4 = deriv(advance(y, k3, h), prof, vDesign, y0, scratch, sCap, fric, drive);
        Vec3 pos = y.pos().add(
            k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                .scale(h / 6));
        Quat q = y.q().add(
            k1.dQ().add(k2.dQ().scale(2)).add(k3.dQ().scale(2)).add(k4.dQ())
                .scale(h / 6)).normalize();
        double s = y.s() + (k1.dS() + 2 * k2.dS() + 2 * k3.dS() + k4.dS()) * h / 6;
        double loss = y.loss() + (k1.dLoss() + 2 * k2.dLoss()
            + 2 * k3.dLoss() + k4.dLoss()) * h / 6;
        return new State(pos, q, s, loss);
    }

    /** GLOBAL-bank bookkeeping: which element's ease is running and the
     *  bank it entered with (measured, so the ease is always continuous
     *  whatever the previous element did). */
    private static final class RollTracker {
        int elem = -1;
        double from;
    }

    public static Result integrate(StraightCurveSection sc, double dt,
                                   double vDesign, Vec3 pos0, Quat q0) {
        return integrate(sc, dt, vDesign, pos0, q0,
            FVDIntegrator.Friction.NONE);
    }

    /** FRICTION variant (Phase 3, v17.0 D133): the loss deficit rides
     *  the RK4 state. The SPINE is speed-independent as ever (pos' and
     *  q' scale by the same v) — friction moves only the clock, the
     *  felt records and the RATE-roll twist (per-second by design). */
    public static Result integrate(StraightCurveSection sc, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   FVDIntegrator.Friction fric) {
        return integrate(sc, dt, vDesign, pos0, q0, fric, null);
    }

    /** SPEED-CONTROL variant (Phase 3, v18.0 D160): intervals keyed by
     *  the section's ARC; drives/snaps per LEG (the sub-stepped units,
     *  so control boundaries stay sharp). Null = none, bitwise. */
    public static Result integrate(StraightCurveSection sc, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   FVDIntegrator.Friction fric,
                                   SpeedControl.Ctl ctl) {
        Profile prof = new Profile(sc);
        double totalLen = Math.max(prof.totalLen, 1e-6);
        double[] scratch = new double[3];

        State st = new State(pos0, q0.normalize(), 0, 0);
        double y0 = pos0.y();
        double t = 0;
        List<FVDIntegrator.Sample> samples = new ArrayList<>();
        RollTracker roll = new RollTracker();
        int guard = 0;

        // samples stay on the uniform dt grid (downstream indexes ride
        // time), but WITHIN a step the integration sub-steps so each
        // RK4 leg sees a smooth kappa(s): legs land EXACTLY on profile
        // breakpoints and on the section end (Newton-sized — v barely
        // changes inside one step, so two refinements reach float noise)
        while (st.s() < totalLen - 1e-9 && guard++ < 500_000) {
            addSample(samples, st, t, prof, vDesign, y0, scratch);
            double remaining = dt;
            while (remaining > 1e-12 && st.s() < totalLen - 1e-9) {
                double v = Math.max(0.5,
                    FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss()));
                double sLeg = st.s();
                // speed control (v18.0): drive per LEG at its start arc
                double drv = ctl == null ? Double.NaN
                    : ctl.drive(sLeg, v);
                double target = Math.min(prof.nextBreak(st.s()), totalLen);
                double sCap = target - 1e-9;   // stay on THIS leg's side
                if (st.s() + v * remaining < target - 1e-12) {
                    st = rk4(st, prof, vDesign, y0, remaining, scratch, sCap, fric, drv);
                    t += remaining;
                    remaining = 0;
                } else {
                    double h = (target - st.s()) / v;
                    State end = st;
                    for (int it = 0; it < 3; it++) {
                        end = rk4(st, prof, vDesign, y0, h, scratch, sCap, fric, drv);
                        double vEnd = Math.max(0.5,
                            FVDIntegrator.speedAt(end.pos(), vDesign, y0,
                                end.loss()));
                        h += (target - end.s()) / vEnd;
                    }
                    st = new State(end.pos(), end.q(), target, end.loss());
                    t += h;
                    remaining = Math.max(0, remaining - h);
                }
                if (ctl != null) {
                    double vA = FVDIntegrator.speedAt(st.pos(), vDesign,
                        y0, st.loss());
                    double dl = ctl.snap(sLeg, v, vA, !Double.isNaN(drv));
                    if (dl != 0) {
                        st = new State(st.pos(), st.q(), st.s(),
                            st.loss() + dl);
                    }
                }
                st = globalRoll(st, prof, roll);
            }
        }
        addSample(samples, st, t, prof, vDesign, y0, scratch);
        return new Result(samples, st.q());
    }

    /** The GLOBAL-bank projection, applied after every leg: roll the
     *  quaternion about T so the bank vs the horizon equals the eased
     *  target. A pure T-roll never moves pos or T — the spine is
     *  untouched, only the twist. Sub-1e-12 corrections are skipped
     *  (planar zero-bank runs stay bit-identical); near-vertical track
     *  has no defined bank and is left alone. */
    private static State globalRoll(State st, Profile prof, RollTracker roll) {
        int i = prof.elemAt(Math.min(st.s(), prof.totalLen));
        if (!prof.rollGlobal[i]) {
            roll.elem = -1;   // re-measure on the next global element
            return st;
        }
        Quat q = st.q();
        Vec3 T = q.T();
        double horiz2 = 1 - T.y() * T.y();
        if (horiz2 < 1e-6) return st;   // vertical: hold until it leaves
        double bank = bankOf(q, T, horiz2);
        if (roll.elem != i) {
            roll.elem = i;
            roll.from = bank;
        }
        double u = Math.min(1, (st.s() - prof.starts[i]) / prof.rollEase[i]);
        // QUINTIC since v19.4 (D233): the sine's nonzero end curvature
        // kinked the roll-RATE graph at both ease ends (the "jolt");
        // the quintic's rate leaves and lands tangent to zero — the
        // strict-seam smoothing's own smoothness class
        double f = quintic(u);
        double d = roll.from + (prof.rollTarget[i] - roll.from) * f - bank;
        if (Math.abs(d) < 1e-12) return st;
        return new State(st.pos(),
            Quat.aboutAxis(T, d).mul(q).normalize(), st.s(), st.loss());
    }

    /** Signed bank of U vs the level up at T (rad), about T; positive
     *  matches the roll lane's positive direction. Caller guarantees
     *  horiz2 = 1 − T.y² is not degenerate. */
    static double bankOf(Quat q, Vec3 T, double horiz2) {
        Vec3 U = q.U();
        // level up = Y − (Y·T)T, normalized
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.atan2(ul.cross(U).dot(T), U.dot(ul));
    }

    private static void addSample(List<FVDIntegrator.Sample> samples, State st,
                                  double t, Profile prof, double vDesign, double y0,
                                  double[] scratch) {
        Quat q = st.q();
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T);
        double v = FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss());
        prof.componentsAt(Math.min(st.s(), prof.totalLen), scratch);
        // felt forces at design speed (readout, not a division)
        double gv = -scratch[1] * v * v / FVDIntegrator.G0 + U.y();
        double gl = -(scratch[0] * v * v / FVDIntegrator.G0 + R.y());   // +Gl = right (D184)
        samples.add(new FVDIntegrator.Sample(t, st.s(), st.pos(), T, U, R,
            v, gv, gl));   // honest record (v18.6, D175)
    }
}
