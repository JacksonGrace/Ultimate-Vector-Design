package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoIntegrator.java (Phase 2, SECTIONS-DESIGN §3) — geometry for GEO
 * sections: the designer specifies BODY RATES directly, so there is no
 * force equation to invert. The frame simply rotates at the authored
 * rates while position advances at the design-speed profile.
 *
 * KEYFRAME SLOTS: geo lanes ride the same BezierBaker.bake output as
 * force lanes, so the FVDIntegrator.Keyframe fields are REUSED with geo
 * meanings — Gv slot = pitch rate, Gl slot = yaw rate, rollRateDeg =
 * roll rate (all deg/s). This file is the only place that mapping
 * exists; everything downstream sees ordinary Samples.
 *
 * Physics: angular velocity from the body rates and the current frame,
 *   Omega = yaw·U − pitch·R + roll·T          (rad/s; same axis
 * conventions as FVDIntegrator, where pitch = T'·U, yaw = T'·R,
 * roll = −U'·R, so +pitch pulls up, +yaw turns rider-right, +roll
 * matches the roll lane). Orientation is integrated NATIVELY AS A
 * QUATERNION (the SECTIONS-DESIGN §2 amendment: geo sections get the
 * quaternion path; the force integrator keeps its gated T/U vectors):
 *   q' = ½ · (0, Omega) ⊗ q,     pos' = T·v
 * with classic RK4 on (pos, q), q normalized after each step and inside
 * each stage. Speed follows the force integrator: energy vs the
 * section's entry height at v0 = vDesign, minus the FRICTION deficit
 * riding the RK4 state since Phase 3 (v17.0, D133; exactly 0.0 with
 * zero coefficients — bitwise-historic) — so a geo section's SHAPE
 * depends only on (entry pose, lanes, vDesign, the document friction),
 * preserving the §0 freeze. In TIME domain speed still shapes geometry
 * (more seconds = more meters); the DISTANCE domain (deg/m) removes
 * that too.
 *
 * Recorded Gv/Gl are the FELT forces at the design speed profile —
 * inverting the force integrator's equations (Gv = ω_pitch·v/g + U.y,
 * Gl = ω_yaw·v/g + R.y) — so rail coloring, stats and continuation
 * holds stay honest for geo track.
 */
public final class GeoIntegrator {

    private GeoIntegrator() {}

    /** Samples plus the section's exact exit orientation (the natively
     *  integrated quaternion — not re-derived from the frame). qEnd is
     *  the quaternion AT THE LAST RECORDED SAMPLE (v13.6, D80): the
     *  loop's post-exit state is one RK4 step PAST that sample, and
     *  handing it out rotated everything downstream by a PHANTOM STEP
     *  of end-rate × dt — the user's "roll jumps in value at geo→force
     *  seams" (0.44° at 15/10/40 °/s end rates; zero end rates showed
     *  none, which is why it was intermittent). Exit pose == last
     *  drawn sample is the contract force sections and ScIntegrator
     *  already keep. */
    public record Result(List<FVDIntegrator.Sample> samples, Quat qEnd) {}

    /** RK4 state: position + orientation quaternion + the friction
     *  deficit (v17.0, D133 — exactly 0.0 with zero coefficients,
     *  keeping the historic paths bitwise). */
    private record State(Vec3 pos, Quat q, double loss) {}

    private record Deriv(Vec3 dPos, Quat dQ, double dLoss) {}

    /** Ω from a keyframe's rate triple per the axis flags (v16.1,
     *  D107). LOCAL (both flags off — the bitwise path): the body
     *  frame, Ω = yaw·U − pitch·R + roll·T. GLOBAL yaw/pitch: yaw
     *  rotates about the WORLD vertical (heading rate), pitch about
     *  the heading's horizontal axis (elevation rate; +pitch pulls up
     *  in both conventions — the TrackPiece delta axis); vertical T
     *  has no heading and falls back to the body axes for the step.
     *  GLOBAL roll: the twist belongs to the post-step bank
     *  projection, so the roll term leaves Ω. */
    static Vec3 omegaFor(FVDIntegrator.Keyframe kf, Vec3 T, Vec3 U, Vec3 R,
                         boolean rollGlobal, boolean ypGlobal) {
        return omegaFor(kf, T, U, R, rollGlobal, ypGlobal, 1);
    }

    /** rateScale = v for PER-METER sections (v16.6, D115): the lanes
     *  hold deg/m and the consumed rates are value x v; 1 = the
     *  historic deg/s, bitwise (the local fast path below is the
     *  original expression untouched). */
    static Vec3 omegaFor(FVDIntegrator.Keyframe kf, Vec3 T, Vec3 U, Vec3 R,
                         boolean rollGlobal, boolean ypGlobal,
                         double rateScale) {
        return omegaFor(kf, T, U, R, rollGlobal, ypGlobal, rateScale,
            Double.NaN);
    }

    /** absPhi = the ABSOLUTE roll lane's bank rate (rad/s, from its
     *  SLOPE — v16.7 D118); NaN = the lane is a rate as usual. */
    static Vec3 omegaFor(FVDIntegrator.Keyframe kf, Vec3 T, Vec3 U, Vec3 R,
                         boolean rollGlobal, boolean ypGlobal,
                         double rateScale, double absPhi) {
        if (!rollGlobal && !ypGlobal && rateScale == 1
                && Double.isNaN(absPhi)) {
            // yaw slot NEGATED (v18.14, D185: +yaw = left turn)
            return U.scale(-Math.toRadians(kf.Gl()))
                .add(R.scale(-Math.toRadians(kf.Gv())))       // pitch slot
                .add(T.scale(Math.toRadians(kf.rollRateDeg())));
        }
        double gvR = kf.Gv() * rateScale;
        double glR = kf.Gl() * rateScale;
        if (!rollGlobal && !ypGlobal) {
            return U.scale(-Math.toRadians(glR))   // +yaw = left (D185)
                .add(R.scale(-Math.toRadians(gvR)))
                .add(T.scale(Math.toRadians(
                    kf.rollRateDeg() * rateScale)));
        }
        Vec3 base;
        if (ypGlobal) {
            Vec3 h = new Vec3(-T.z(), 0, T.x());   // horizontal pitch-up axis
            double len = h.length();
            // +heading' = LEFT turn (v18.14, D185 — the reported
            // sense, like the local yaw lane)
            base = new Vec3(0, -Math.toRadians(glR), 0);
            base = len > 1e-6
                ? base.add(h.scale(Math.toRadians(gvR) / len))
                : base.add(R.scale(-Math.toRadians(gvR)));
        } else {
            // the third local-base build (global ROLL with local
            // yaw/pitch) — +yaw = left here too (v18.14, D185; the
            // 51a gate caught exactly this one missing)
            base = U.scale(-Math.toRadians(glR))
                .add(R.scale(-Math.toRadians(gvR)));
        }
        // GLOBAL roll: the sub-step feed-forward body rate (v16.4,
        // D110 — FVDIntegrator.rollTerm; the projection corrects the
        // O(dt²) rest). Felt is untouched: a T-term contributes to
        // neither Ω·U nor Ω·R.
        return base.add(T.scale(!Double.isNaN(absPhi) && rollGlobal
            ? FVDIntegrator.rollTermPhi(absPhi, T, base)
            : FVDIntegrator.rollTerm(kf, T, base, rollGlobal, rateScale)));
    }

    private static Deriv deriv(State st, double t, List<FVDIntegrator.Keyframe> kfs,
                               double vDesign, double y0,
                               boolean rollGlobal, boolean ypGlobal,
                               boolean perMeter, boolean rollAbs,
                               FVDIntegrator.Friction fric) {
        return deriv(st, t, kfs, vDesign, y0, rollGlobal, ypGlobal,
            perMeter, rollAbs, fric, Double.NaN);
    }

    /** drive = a speed control's d(loss)/ds for this step (replaces
     *  friction); NaN = natural (v18.0, D160). */
    private static Deriv deriv(State st, double t, List<FVDIntegrator.Keyframe> kfs,
                               double vDesign, double y0,
                               boolean rollGlobal, boolean ypGlobal,
                               boolean perMeter, boolean rollAbs,
                               FVDIntegrator.Friction fric, double drive) {
        Quat q = st.q().normalize();   // stages hand us slightly non-unit q
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T); // frame convention: T x R = U, R x U = T, U x T = R

        FVDIntegrator.Keyframe kf = FVDIntegrator.interpAt(kfs, t);
        double v = FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss());

        Vec3 omega = omegaFor(kf, T, U, R, rollGlobal, ypGlobal,
            perMeter ? v : 1,
            rollAbs && rollGlobal
                ? Math.toRadians(FVDIntegrator.rollSlopeAt(kfs, t))
                : Double.NaN);
        Quat dQ = new Quat(0, omega.x(), omega.y(), omega.z()).mul(q).scale(0.5);
        return new Deriv(T.scale(v), dQ,
            (Double.isNaN(drive) ? fric.dLossDs(v) : drive) * v);
    }

    private static State advance(State y, Deriv k, double h) {
        return new State(y.pos().add(k.dPos().scale(h)),
            y.q().add(k.dQ().scale(h)), y.loss() + k.dLoss() * h);
    }

    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0) {
        return integrate(keyframes, dt, vDesign, pos0, q0, false, false);
    }

    /** Axis-flag variant (v16.1, D107): rollGlobal = the roll lane
     *  integrates a bank-vs-horizon target enforced by projection;
     *  ypGlobal = lanes 0/1 are heading/elevation rates. Both false =
     *  the historic call, bitwise. */
    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   boolean rollGlobal, boolean ypGlobal) {
        return integrate(keyframes, dt, vDesign, pos0, q0,
            rollGlobal, ypGlobal, false);
    }

    /** PER-METER variant (v16.6, D115): the lanes hold deg/m —
     *  consumed rates scale by v at every stage, the global-roll bank
     *  target integrates over ARC. false = historic deg/s, bitwise. */
    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   boolean rollGlobal, boolean ypGlobal,
                                   boolean perMeter) {
        return integrate(keyframes, dt, vDesign, pos0, q0,
            rollGlobal, ypGlobal, perMeter, false);
    }

    /** ABSOLUTE-roll variant (v16.7, D118): the roll lane holds the
     *  bank angle (deg) — the projection targets the lane VALUE, the
     *  feed-forward its slope. Requires rollGlobal. */
    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   boolean rollGlobal, boolean ypGlobal,
                                   boolean perMeter, boolean rollAbs) {
        return integrate(keyframes, dt, vDesign, pos0, q0,
            rollGlobal, ypGlobal, perMeter, rollAbs,
            FVDIntegrator.Friction.NONE);
    }

    /** FRICTION variant (Phase 3, v17.0 D133): the loss deficit joins
     *  the RK4 state. NONE = the historic profile, bitwise. */
    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   boolean rollGlobal, boolean ypGlobal,
                                   boolean perMeter, boolean rollAbs,
                                   FVDIntegrator.Friction fric) {
        return integrate(keyframes, dt, vDesign, pos0, q0, rollGlobal,
            ypGlobal, perMeter, rollAbs, fric, null);
    }

    /** SPEED-CONTROL variant (Phase 3, v18.0 D160): lift/brake/launch
     *  intervals keyed by section-local time. Null = none, bitwise. */
    public static Result integrate(List<FVDIntegrator.Keyframe> keyframes, double dt,
                                   double vDesign, Vec3 pos0, Quat q0,
                                   boolean rollGlobal, boolean ypGlobal,
                                   boolean perMeter, boolean rollAbs,
                                   FVDIntegrator.Friction fric,
                                   SpeedControl.Ctl ctl) {
        double totalTime = keyframes.get(keyframes.size() - 1).t();

        Vec3 pos = pos0;
        Quat q = q0.normalize();
        double y0 = pos.y();
        double s = 0;
        double loss = 0;
        List<FVDIntegrator.Sample> samples = new ArrayList<>();
        boolean anyGlobal = rollGlobal || ypGlobal;
        double bank0 = rollGlobal
            ? FVDIntegrator.bankOfTU(q.T(), q.U()) : 0;
        double cumRoll = 0;

        double t = 0;
        Quat qEnd = q;
        while (true) {   // same sampling cadence as the force path
            Vec3 T = q.T();
            Vec3 U = q.U();
            Vec3 R = U.cross(T);
            FVDIntegrator.Keyframe kf = FVDIntegrator.interpAt(keyframes, t);
            double v = FVDIntegrator.speedAt(pos, vDesign, y0, loss);
            // felt forces at design speed (see class comment); v not vSafe:
            // this is a readout, not a division. GLOBAL-axis lanes are
            // not body rates — the felt reads the BODY components of Ω
            // (identical arithmetic to the lane values when local).
            double rsF = perMeter ? v : 1;
            double gv, gl;
            if (anyGlobal) {
                Vec3 om = omegaFor(kf, T, U, R, rollGlobal, ypGlobal, rsF,
                    rollAbs && rollGlobal
                        ? Math.toRadians(FVDIntegrator.rollSlopeAt(keyframes, t))
                        : Double.NaN);
                gv = -om.dot(R) * v / FVDIntegrator.G0 + U.y();
                gl = -(om.dot(U) * v / FVDIntegrator.G0 + R.y());   // +Gl = right (D184)
            } else {
                gv = Math.toRadians(kf.Gv() * rsF) * v / FVDIntegrator.G0 + U.y();
                // internal omega_U = the NEGATED yaw lane (D185): the
                // D184 record -(omega*v/g + R.y) unfolds to lane*v/g - R.y
                gl = Math.toRadians(kf.Gl() * rsF) * v / FVDIntegrator.G0 - R.y();
            }
            samples.add(new FVDIntegrator.Sample(t, s, pos, T, U, R,
                v, gv, gl));   // honest record (v18.6, D175)
            qEnd = q;   // the exit contract: the LAST SAMPLE's orientation
                        // (the loop's own q ends one step PAST it, D80)

            // END LANDING (v17.10, D147): same closing-step rule as
            // integrateTrack — the last sample lands EXACTLY on the
            // authored end (the old loop refused the final step on
            // float overshoot; every seam lost up to one dt).
            double rem = totalTime - t;
            if (rem <= dt * 1e-9) break;
            double h = (rem <= dt * 1.5) ? rem : dt;

            // speed control (v18.0): per-step drive at the step-start
            // time; post-step snap below
            double drv = ctl == null ? Double.NaN : ctl.drive(t, v);
            State y = new State(pos, q, loss);
            Deriv k1 = deriv(y, t, keyframes, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drv);
            Deriv k2 = deriv(advance(y, k1, h / 2), t + h / 2, keyframes, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drv);
            Deriv k3 = deriv(advance(y, k2, h / 2), t + h / 2, keyframes, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drv);
            Deriv k4 = deriv(advance(y, k3, h), t + h, keyframes, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drv);

            Vec3 newPos = pos.add(
                k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                    .scale(h / 6));
            q = q.add(
                k1.dQ().add(k2.dQ().scale(2)).add(k3.dQ().scale(2)).add(k4.dQ())
                    .scale(h / 6)).normalize();
            loss += (k1.dLoss() + 2 * k2.dLoss() + 2 * k3.dLoss()
                + k4.dLoss()) * h / 6;
            if (ctl != null) {
                double vA = FVDIntegrator.speedAt(newPos, vDesign, y0, loss);
                double dl = ctl.snap(t, v, vA, !Double.isNaN(drv));
                if (dl != 0) loss += dl;
            }
            if (rollGlobal && rollAbs) {
                // ABSOLUTE roll (D118): target = the lane value at
                // the just-reached time (C1 read, v16.9 D121)
                double tb = Math.toRadians(FVDIntegrator
                    .bankAt(keyframes, t + h));
                double b = FVDIntegrator.bankOfTU(q.T(), q.U());
                if (!Double.isNaN(b) && Math.abs(tb - b) >= 1e-12) {
                    q = Quat.aboutAxis(q.T(), tb - b).mul(q).normalize();
                }
            } else if (rollGlobal) {
                if (perMeter) {
                    // bank target = the lane's ARC integral (D115)
                    double v1 = FVDIntegrator.speedAt(newPos, vDesign, y0, loss);
                    cumRoll += Math.toRadians(kf.rollRateDeg() * v
                        + FVDIntegrator.interpAt(keyframes, t + h)
                            .rollRateDeg() * v1) / 2 * h;
                } else {
                    cumRoll += Math.toRadians(kf.rollRateDeg()
                        + FVDIntegrator.interpAt(keyframes, t + h).rollRateDeg())
                        / 2 * h;
                }
                if (Double.isNaN(bank0)) {
                    double b = FVDIntegrator.bankOfTU(q.T(), q.U());
                    if (!Double.isNaN(b)) bank0 = b - cumRoll;
                }
                if (!Double.isNaN(bank0)) {
                    double b = FVDIntegrator.bankOfTU(q.T(), q.U());
                    if (!Double.isNaN(b)) {
                        double d = bank0 + cumRoll - b;
                        if (Math.abs(d) >= 1e-12) {
                            q = Quat.aboutAxis(q.T(), d).mul(q).normalize();
                        }
                    }
                }
            }

            s += newPos.sub(pos).length();
            pos = newPos;
            t = (h == dt) ? t + dt : totalTime;
        }

        return new Result(samples, qEnd);
    }

    // ---------- DISTANCE domain (v16.0, D103) ----------
    // The keyframes' t-slots hold ARC LENGTH; rates stay °/s (a domain
    // is a re-parameterization of the AUTHORING axis, not a unit change
    // — the exact node map between domains rides on values transferring
    // untouched). RK4 on (pos, q, s) with the ScIntegrator landing:
    // the final step Newton-sizes onto the lane end. The advance speed
    // is floored at 0.5 so a stall still terminates; rates are per
    // second, so unlike s/c curvature the floored creep DOES shape a
    // genuinely stalled section — the same caveat s/c RATE roll carries.

    private record DState(Vec3 pos, Quat q, double s, double loss) {}

    private record DDeriv(Vec3 dPos, Quat dQ, double dS, double dLoss) {}

    private static DDeriv derivDist(DState st, List<FVDIntegrator.Keyframe> kfs,
                                    double vDesign, double y0,
                                    boolean rollGlobal, boolean ypGlobal,
                                    boolean perMeter, boolean rollAbs,
                                    FVDIntegrator.Friction fric) {
        return derivDist(st, kfs, vDesign, y0, rollGlobal, ypGlobal,
            perMeter, rollAbs, fric, Double.NaN);
    }

    private static DDeriv derivDist(DState st, List<FVDIntegrator.Keyframe> kfs,
                                    double vDesign, double y0,
                                    boolean rollGlobal, boolean ypGlobal,
                                    boolean perMeter, boolean rollAbs,
                                    FVDIntegrator.Friction fric,
                                    double drive) {
        Quat q = st.q().normalize();
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T);

        FVDIntegrator.Keyframe kf = FVDIntegrator.interpAt(kfs, st.s());
        double v = Math.max(0.5,
            FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss()));

        Vec3 omega = omegaFor(kf, T, U, R, rollGlobal, ypGlobal,
            perMeter ? v : 1,
            rollAbs && rollGlobal
                // slope per METER on an arc axis: phi' = slope x v
                ? Math.toRadians(
                    FVDIntegrator.rollSlopeAt(kfs, st.s())) * v
                : Double.NaN);
        Quat dQ = new Quat(0, omega.x(), omega.y(), omega.z()).mul(q).scale(0.5);
        return new DDeriv(T.scale(v), dQ, v,
            (Double.isNaN(drive) ? fric.dLossDs(v) : drive) * v);
    }

    private static DState advanceDist(DState y, DDeriv k, double h) {
        return new DState(y.pos().add(k.dPos().scale(h)),
            y.q().add(k.dQ().scale(h)), y.s() + k.dS() * h,
            y.loss() + k.dLoss() * h);
    }

    private static DState rk4Dist(DState y, List<FVDIntegrator.Keyframe> kfs,
                                  double vDesign, double y0, double h,
                                  boolean rollGlobal, boolean ypGlobal,
                                  boolean perMeter, boolean rollAbs,
                                  FVDIntegrator.Friction fric) {
        return rk4Dist(y, kfs, vDesign, y0, h, rollGlobal, ypGlobal,
            perMeter, rollAbs, fric, Double.NaN);
    }

    private static DState rk4Dist(DState y, List<FVDIntegrator.Keyframe> kfs,
                                  double vDesign, double y0, double h,
                                  boolean rollGlobal, boolean ypGlobal,
                                  boolean perMeter, boolean rollAbs,
                                  FVDIntegrator.Friction fric,
                                  double drive) {
        DDeriv k1 = derivDist(y, kfs, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drive);
        DDeriv k2 = derivDist(advanceDist(y, k1, h / 2), kfs, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drive);
        DDeriv k3 = derivDist(advanceDist(y, k2, h / 2), kfs, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drive);
        DDeriv k4 = derivDist(advanceDist(y, k3, h), kfs, vDesign, y0, rollGlobal, ypGlobal, perMeter, rollAbs, fric, drive);
        Vec3 pos = y.pos().add(
            k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                .scale(h / 6));
        Quat q = y.q().add(
            k1.dQ().add(k2.dQ().scale(2)).add(k3.dQ().scale(2)).add(k4.dQ())
                .scale(h / 6)).normalize();
        double s = y.s() + (k1.dS() + 2 * k2.dS() + 2 * k3.dS() + k4.dS()) * h / 6;
        double loss = y.loss() + (k1.dLoss() + 2 * k2.dLoss()
            + 2 * k3.dLoss() + k4.dLoss()) * h / 6;
        return new DState(pos, q, s, loss);
    }

    private static void addSampleDist(List<FVDIntegrator.Sample> samples,
                                      DState st, double t,
                                      List<FVDIntegrator.Keyframe> kfs,
                                      double vDesign, double y0,
                                      boolean rollGlobal, boolean ypGlobal,
                                      boolean perMeter, boolean rollAbs) {
        Quat q = st.q();
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T);
        FVDIntegrator.Keyframe kf = FVDIntegrator.interpAt(kfs, st.s());
        double v = FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss());
        double rsF = perMeter ? v : 1;
        double gv, gl;
        if (rollGlobal || ypGlobal) {   // body components of Ω (v16.1)
            Vec3 om = omegaFor(kf, T, U, R, rollGlobal, ypGlobal, rsF,
                rollAbs && rollGlobal
                    ? Math.toRadians(
                        FVDIntegrator.rollSlopeAt(kfs, st.s())) * v
                    : Double.NaN);
            gv = -om.dot(R) * v / FVDIntegrator.G0 + U.y();
            gl = -(om.dot(U) * v / FVDIntegrator.G0 + R.y());   // +Gl = right (D184)
        } else {
            gv = Math.toRadians(kf.Gv() * rsF) * v / FVDIntegrator.G0 + U.y();
            // internal omega_U = the NEGATED yaw lane (D185): the
            // D184 record -(omega*v/g + R.y) unfolds to lane*v/g - R.y
            gl = Math.toRadians(kf.Gl() * rsF) * v / FVDIntegrator.G0 - R.y();
        }
        samples.add(new FVDIntegrator.Sample(t, st.s(), st.pos(), T, U, R,
            v, gv, gl));   // honest record (v18.6, D175)
    }

    /** DISTANCE-domain run: rate keyframes over arc length. qEnd = the
     *  landing state = the last sample's orientation (the D80 contract
     *  holds by construction here — the loop ends ON the sample). */
    public static Result integrateDist(List<FVDIntegrator.Keyframe> keyframes,
                                       double dt, double vDesign,
                                       Vec3 pos0, Quat q0) {
        return integrateDist(keyframes, dt, vDesign, pos0, q0, false, false);
    }

    public static Result integrateDist(List<FVDIntegrator.Keyframe> keyframes,
                                       double dt, double vDesign,
                                       Vec3 pos0, Quat q0,
                                       boolean rollGlobal, boolean ypGlobal) {
        return integrateDist(keyframes, dt, vDesign, pos0, q0,
            rollGlobal, ypGlobal, false);
    }

    /** PER-METER variant over the DISTANCE axis (v16.6, D115). */
    public static Result integrateDist(List<FVDIntegrator.Keyframe> keyframes,
                                       double dt, double vDesign,
                                       Vec3 pos0, Quat q0,
                                       boolean rollGlobal, boolean ypGlobal,
                                       boolean perMeter) {
        return integrateDist(keyframes, dt, vDesign, pos0, q0,
            rollGlobal, ypGlobal, perMeter, false);
    }

    public static Result integrateDist(List<FVDIntegrator.Keyframe> keyframes,
                                       double dt, double vDesign,
                                       Vec3 pos0, Quat q0,
                                       boolean rollGlobal, boolean ypGlobal,
                                       boolean perMeter, boolean rollAbs) {
        return integrateDist(keyframes, dt, vDesign, pos0, q0,
            rollGlobal, ypGlobal, perMeter, rollAbs,
            FVDIntegrator.Friction.NONE);
    }

    /** FRICTION variant (Phase 3, v17.0 D133). */
    public static Result integrateDist(List<FVDIntegrator.Keyframe> keyframes,
                                       double dt, double vDesign,
                                       Vec3 pos0, Quat q0,
                                       boolean rollGlobal, boolean ypGlobal,
                                       boolean perMeter, boolean rollAbs,
                                       FVDIntegrator.Friction fric) {
        return integrateDist(keyframes, dt, vDesign, pos0, q0, rollGlobal,
            ypGlobal, perMeter, rollAbs, fric, null);
    }

    /** SPEED-CONTROL variant (Phase 3, v18.0 D160): intervals keyed by
     *  section-local ARC. Null = none, bitwise. */
    public static Result integrateDist(List<FVDIntegrator.Keyframe> keyframes,
                                       double dt, double vDesign,
                                       Vec3 pos0, Quat q0,
                                       boolean rollGlobal, boolean ypGlobal,
                                       boolean perMeter, boolean rollAbs,
                                       FVDIntegrator.Friction fric,
                                       SpeedControl.Ctl ctl) {
        double totalLen = Math.max(keyframes.get(keyframes.size() - 1).t(), 1e-6);

        DState st = new DState(pos0, q0.normalize(), 0, 0);
        double y0 = pos0.y();
        double t = 0;
        List<FVDIntegrator.Sample> samples = new ArrayList<>();
        int guard = 0;
        double bank0 = rollGlobal
            ? FVDIntegrator.bankOfTU(st.q().T(), st.q().U()) : 0;
        double cumRoll = 0;

        while (st.s() < totalLen - 1e-9 && guard++ < 500_000) {
            addSampleDist(samples, st, t, keyframes, vDesign, y0,
                rollGlobal, ypGlobal, perMeter, rollAbs);
            double v = Math.max(0.5,
                FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss()));
            double sPrev = st.s();
            // speed control (v18.0): drive at the step-start arc
            double drv = ctl == null ? Double.NaN : ctl.drive(sPrev, v);
            double hUsed;
            if (st.s() + v * dt < totalLen - 1e-12) {
                st = rk4Dist(st, keyframes, vDesign, y0, dt,
                    rollGlobal, ypGlobal, perMeter, rollAbs, fric, drv);
                t += dt;
                hUsed = dt;
            } else {
                double h = (totalLen - st.s()) / v;
                DState end = st;
                for (int it = 0; it < 3; it++) {
                    end = rk4Dist(st, keyframes, vDesign, y0, h,
                        rollGlobal, ypGlobal, perMeter, rollAbs, fric,
                        drv);
                    double vEnd = Math.max(0.5,
                        FVDIntegrator.speedAt(end.pos(), vDesign, y0,
                            end.loss()));
                    h += (totalLen - end.s()) / vEnd;
                }
                st = new DState(end.pos(), end.q(), totalLen, end.loss());
                t += h;
                hUsed = h;
            }
            if (ctl != null) {
                double vA = FVDIntegrator.speedAt(st.pos(), vDesign, y0,
                    st.loss());
                double dl = ctl.snap(sPrev, v, vA, !Double.isNaN(drv));
                if (dl != 0) {
                    st = new DState(st.pos(), st.q(), st.s(),
                        st.loss() + dl);
                }
            }
            if (rollGlobal && rollAbs) {
                // ABSOLUTE roll (D118): target = the lane value at
                // the just-reached arc (C1 read, v16.9 D121)
                Quat q = st.q();
                double tb = Math.toRadians(FVDIntegrator
                    .bankAt(keyframes, st.s()));
                double b = FVDIntegrator.bankOfTU(q.T(), q.U());
                if (!Double.isNaN(b) && Math.abs(tb - b) >= 1e-12) {
                    st = new DState(st.pos(), Quat.aboutAxis(q.T(), tb - b)
                        .mul(q).normalize(), st.s(), st.loss());
                }
            } else if (rollGlobal) {
                // per-meter: the bank target is the lane's ARC
                // integral — ds is the step's own advance (D115)
                cumRoll += perMeter
                    ? Math.toRadians(
                        FVDIntegrator.interpAt(keyframes, sPrev).rollRateDeg()
                        + FVDIntegrator.interpAt(keyframes, st.s()).rollRateDeg())
                        / 2 * (st.s() - sPrev)
                    : Math.toRadians(
                        FVDIntegrator.interpAt(keyframes, sPrev).rollRateDeg()
                        + FVDIntegrator.interpAt(keyframes, st.s()).rollRateDeg())
                        / 2 * hUsed;
                Quat q = st.q();
                if (Double.isNaN(bank0)) {
                    double b = FVDIntegrator.bankOfTU(q.T(), q.U());
                    if (!Double.isNaN(b)) bank0 = b - cumRoll;
                }
                if (!Double.isNaN(bank0)) {
                    double b = FVDIntegrator.bankOfTU(q.T(), q.U());
                    if (!Double.isNaN(b)) {
                        double d = bank0 + cumRoll - b;
                        if (Math.abs(d) >= 1e-12) {
                            st = new DState(st.pos(), Quat.aboutAxis(q.T(), d)
                                .mul(q).normalize(), st.s(), st.loss());
                        }
                    }
                }
            }
        }
        addSampleDist(samples, st, t, keyframes, vDesign, y0,
            rollGlobal, ypGlobal, perMeter, rollAbs);
        return new Result(samples, st.q());
    }
}
