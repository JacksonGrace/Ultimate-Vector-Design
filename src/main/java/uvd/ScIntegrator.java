package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * ScIntegrator.java (Phase 2, v11.0, D68; the Phase 4 overhaul from
 * v20.0, D259 §1.2-§1.4) — geometry for STRAIGHT/CURVE sections. Pure
 * Java, no JavaFX.
 *
 * The element list defines a ROTATION-RATE VECTOR PROFILE over arc
 * length, Ω/m(s) (rad per metre, world coordinates), built on each
 * element's ROLL-FREE AXIS (D2 — fixed at the element's ENTRY against
 * the level frame of the sharp chain, {@link ElementChain}):
 *
 *   PLANAR    Ω/m = −k(s)·a           a = cos d·ul + sin d·rl (world-fixed)
 *   HELICAL   Ω/m = −c_h(s)·cos β(s)·Y + β'(s)·h(T)
 *   STRAIGHT  Ω/m = 0
 *
 * (k, c_h signed, +κ at dir 0 = rider-LEFT, D187). Rotating about a
 * world-fixed axis leaves the axis invariant, so a constant-κ planar
 * element is an EXACT circle whatever the body roll does — the body
 * roll never bends the shape (today's law read the axis off the ROLLED
 * body frame per RK4 stage: a banked R20/90°/30° turn dove 10.9 m).
 * {@code -Dfvd.sc.legacyAxis=1} ({@link #LEGACY_BODY_AXIS}, Stage 1a only,
 * R2) restores that law verbatim for the gate that pins today's bits.
 *
 * TRANSITION SMOOTHING: at each internal border the two neighbours'
 * Ω/m VECTORS blend with a sine ease over the entry element's blendIn,
 * CENTERED on the border (half eats into each neighbour; the vector
 * lerp replaces the old body-component lerp — PLANAR↔HELICAL borders
 * included). The ease is symmetric, so the blend integrates to the
 * mean rotation and the TOTAL TURN of a straight↔arc pair is preserved
 * exactly (gated). Blend halves are clamped so no element's entry+exit
 * blends overlap.
 *
 * LEG BOUNDARIES (D259 §1.4.2): EVERY element border is a leg boundary
 * — the RK4 legs land on it exactly — EXCEPT a MERGED one (same kind,
 * no blend, both constant, equal κ across the border, equal axis
 * within AXIS_MERGE_EPS, equal interim roll); blend-window EDGES are
 * additional leg boundaries; the on-side lookup cap (sCap) applies at
 * KINKS only (stepping OVER a κ step costs ~0.1° per crossing). A merged
 * run uses its FIRST element's axis and one κ, and its end arc is
 * start_first + (L_1 + L_2 + …) — the ARC-SUM rule, so a split that keeps
 * L1 + L2 == L bitwise reproduces the parent's leg schedule and bits.
 *
 * Every border's state is RECORDED (the {@link Bound} list on the
 * Result — the ONE per-border record TrackCompiler carries onto the
 * Span as scBounds): landed states at leg boundaries, the closed-form
 * advance of the last landed state at merged borders, plus the SHARP
 * chain's pose from ElementChain.
 *
 * Integration: the shape is authored in DISTANCE but downstream code
 * indexes samples by uniform ride time, so we RK4 in TIME on state
 * (pos, q, s): q' = ½(0, v·Ω/m(s) + cT·T)⊗q, pos' = T·v, s' = v, with v
 * ALGEBRAIC from energy at the design speed. The last step is
 * Newton-sized to land s EXACTLY on the total length.
 *
 * Felt Gv/Gl are recorded at the design speed off the BODY components
 * of Ω/m (Gv = −(Ω·R)·v²/g + U.y, Gl = −((Ω·U)·v²/g + R.y)), so rail
 * colors, stats and '+' holds stay honest.
 *
 * STAGE-1a INTERIM ROLL (v11.1, D69; the 1b lane replaces it): RATE = a
 * body roll rate cT (rad/s) added about T, blended with the quintic
 * across borders; GLOBAL = the bank vs the horizon held by an algebraic
 * T-roll projection after every leg, eased from the element's entry
 * bank over max(blendIn, 2 m) (element 0: its blendIn verbatim — the
 * section-entry ease, D233). A merged run eases ONCE, at its leader.
 */
public final class ScIntegrator {

    private ScIntegrator() {}

    /** Stage-1a reversal lever (D259 §1.2, R2): true = today's law —
     *  the curve axis read per RK4 stage as cos d·U + sin d·R of the
     *  CURRENT body frame, body-component blends, today's leg schedule
     *  (no merging). Chains holding a HELICAL element ignore it. */
    static final boolean LEGACY_BODY_AXIS = legacyFlag();

    private static boolean legacyFlag() {
        String p = System.getProperty("fvd.sc.legacyAxis");
        return p != null && (p.equals("1") || p.equalsIgnoreCase("true"));
    }

    /** One border record (D259 §1.4.3): idx 0 = the entry, i = the
     *  start of element i, n = the exit. pos/q/v/loss/t = the integrator's
     *  state at that arc — LANDED at every leg boundary (landed = true);
     *  at a MERGED border the closed-form advance of the run's last
     *  landed state by the sub-arc (landed = false; q there is the
     *  roll-free advance). sharpPos/sharpQ = ElementChain's pose. */
    public record Bound(int idx, double s, double t, Vec3 pos, Quat q,
                        double v, double loss, boolean landed,
                        Vec3 sharpPos, Quat sharpQ) {}

    /** Samples plus the exact exit orientation (natively integrated) and
     *  the per-border records. */
    public record Result(List<FVDIntegrator.Sample> samples, Quat qEnd,
                         List<Bound> bounds) {
        public Result(List<FVDIntegrator.Sample> samples, Quat qEnd) {
            this(samples, qEnd, null);
        }
    }

    // ---------- the profile ----------

    /** The rotation-rate profile. starts[i] = element i's start arc;
     *  half[i] = blend half-width of the border at starts[i] (0 for the
     *  section entry — package-visible for the strip's band edges);
     *  cT[i] = the interim RATE roll rate (rad/s); the GLOBAL-bank fields
     *  drive the interim projection. */
    static final class Profile {
        final int n;
        final double[] starts, len, half, cT, rollTarget, rollEase;
        final boolean[] rollGlobal;
        final StraightCurveSection.Kind[] kind;
        final double[] k0, k1, beta0, beta1;      // beta rad (HELICAL)
        final StraightCurveSection.Interp[] kInterp, bInterp;
        final Vec3[] axis;                        // PLANAR world axis, else null
        /** merged[b]: the border at starts[b] (b ≥ 1) is merged. */
        final boolean[] merged;
        /** runLeader[i] = the first element of i's merged run. */
        final int[] runLeader;
        final double totalLen;
        /** Interior leg boundaries (sorted) + whether each is a KINK
         *  (sharp border → the on-side cap) or a smooth landing. */
        final double[] breaks;
        final boolean[] kinkAt;
        /** The sharp chain's poses, index i = the entry of element i. */
        final List<ElementChain.Pose> poses;
        /** LEGACY (today's law) body components, built only under the
         *  switch for a chain without a helix. */
        final double[] cU, cR;
        final boolean legacy;

        /** The strip's convenience: the clamped blend halves do not
         *  depend on the entry pose. */
        Profile(StraightCurveSection sc) {
            this(sc, Vec3.ZERO, Quat.IDENTITY);
        }

        Profile(StraightCurveSection sc, Vec3 pos0, Quat q0) {
            n = sc.elements.size();
            starts = new double[n];
            len = new double[n];
            half = new double[n];
            cT = new double[n];
            rollGlobal = new boolean[n];
            rollTarget = new double[n];
            rollEase = new double[n];
            kind = new StraightCurveSection.Kind[n];
            k0 = new double[n];
            k1 = new double[n];
            beta0 = new double[n];
            beta1 = new double[n];
            kInterp = new StraightCurveSection.Interp[n];
            bInterp = new StraightCurveSection.Interp[n];
            axis = new Vec3[n];
            merged = new boolean[n];
            runLeader = new int[n];
            poses = ElementChain.poses(sc.elements, pos0, q0);
            boolean anyHelix = false;
            for (int i = 0; i < n; i++) {
                StraightCurveSection.Element e = sc.elements.get(i);
                ElementChain.Pose entry = poses.get(i);
                kind[i] = e.kind;
                len[i] = e.arcLen();
                k0[i] = e.kind == StraightCurveSection.Kind.STRAIGHT ? 0 : e.k0;
                k1[i] = e.kind == StraightCurveSection.Kind.STRAIGHT ? 0
                    : (e.kLink ? e.k0 : e.k1);
                kInterp[i] = e.kInterp;
                bInterp[i] = e.bInterp;
                axis[i] = ElementChain.axisOf(e, entry);
                if (e.kind == StraightCurveSection.Kind.HELICAL) {
                    anyHelix = true;
                    double b0 = Math.toRadians(ElementChain.elevDeg(entry));
                    beta0[i] = b0;
                    beta1[i] = e.bLink ? b0 : Math.toRadians(e.b1Deg);
                }
                half[i] = i == 0 ? 0 : Math.max(0, e.blendIn / 2);
                boolean glob = e.rollMode == StraightCurveSection.RollMode.GLOBAL;
                rollGlobal[i] = glob;
                rollTarget[i] = glob ? Math.toRadians(e.rollVal) : 0;
                cT[i] = glob ? 0 : Math.toRadians(e.rollVal);
                // ELEMENT 0's blendIn is the INTERIM section-entry ease,
                // verbatim (D233: 0 = an instant bank snap); interior
                // elements keep the historic max(blendIn, 2 m) floor
                double ease = i == 0 ? e.blendIn : Math.max(e.blendIn, ROLL_EASE_MIN);
                rollEase[i] = Math.max(1e-9, Math.min(e.arcLen(), ease));
            }
            legacy = LEGACY_BODY_AXIS && !anyHelix;
            // clamp: an element's entry+exit blend halves must fit inside
            // it (no overlapping eases); scale both down proportionally
            for (int i = 0; i < n; i++) {
                double hIn = half[i];
                double hOut = i + 1 < n ? half[i + 1] : 0;
                if (hIn + hOut > len[i] && hIn + hOut > 1e-12) {
                    double f = len[i] / (hIn + hOut);
                    half[i] = hIn * f;
                    if (i + 1 < n) half[i + 1] = hOut * f;
                }
            }
            // MERGE rule (§1.4.2) — never under the legacy law
            for (int b = 1; b < n; b++) {
                merged[b] = !legacy && mergeable(sc, b);
            }
            // ARC-SUM starts: a merged run's end = start + (L_1 + L_2 + …);
            // member starts are the running partial sums (lookups only)
            double s = 0;
            int i = 0;
            while (i < n) {
                int lead = i;
                double sum = 0;
                int j = i;
                do {
                    runLeader[j] = lead;
                    starts[j] = s + sum;
                    sum += len[j];
                    j++;
                } while (j < n && merged[j]);
                s += sum;
                i = j;
            }
            totalLen = s;
            // leg boundaries
            List<double[]> br = new ArrayList<>();   // {s, kink}
            for (int b = 1; b < n; b++) {
                if (merged[b]) continue;
                if (half[b] > 1e-12) {
                    // blend EDGES are smooth landings (no cap) under the new
                    // law; today's law capped every break — kept verbatim
                    double edgeKink = legacy ? 1 : 0;
                    br.add(new double[] { starts[b] - half[b], edgeKink });
                    if (!legacy) br.add(new double[] { starts[b], 0 });
                    br.add(new double[] { starts[b] + half[b], edgeKink });
                } else {
                    br.add(new double[] { starts[b], 1 });
                }
            }
            breaks = new double[br.size()];
            kinkAt = new boolean[br.size()];
            for (int b = 0; b < breaks.length; b++) {
                breaks[b] = br.get(b)[0];
                kinkAt[b] = br.get(b)[1] > 0;
            }
            if (legacy) {
                cU = new double[n];
                cR = new double[n];
                for (int e = 0; e < n; e++) {
                    double k = k0[e];
                    double d = Math.toRadians(sc.elements.get(e).dirDeg);
                    // yaw component NEGATED (v18.16, D187)
                    cU[e] = -k * Math.cos(d);
                    cR[e] = -k * Math.sin(d);
                }
            } else {
                cU = null;
                cR = null;
            }
        }

        private boolean mergeable(StraightCurveSection sc, int b) {
            if (half[b] > 1e-12) return false;
            StraightCurveSection.Element p = sc.elements.get(b - 1);
            StraightCurveSection.Element e = sc.elements.get(b);
            if (p.kind != e.kind) return false;
            if (p.rollMode != e.rollMode || p.rollVal != e.rollVal) return false;
            if (k0[b - 1] != k1[b - 1] || k0[b] != k1[b]) return false;   // both constant
            if (k1[b - 1] != k0[b]) return false;                        // equal across, bitwise
            switch (e.kind) {
                case STRAIGHT: return true;
                case PLANAR: {
                    Vec3 a = axis[b - 1], c = axis[b];
                    return Math.abs(a.x() - c.x()) <= StraightCurveSection.AXIS_MERGE_EPS
                        && Math.abs(a.y() - c.y()) <= StraightCurveSection.AXIS_MERGE_EPS
                        && Math.abs(a.z() - c.z()) <= StraightCurveSection.AXIS_MERGE_EPS;
                }
                default: {
                    // HELICAL: both one CONSTANT pitch, equal across the
                    // border. A linked pitch IS constant by construction;
                    // the entry elevation β0 is re-derived by asin off the
                    // rebuilt tangent and can sit one ulp off the stored
                    // β1 (ScElemGate c found 1 ulp at a 20° entry), so the
                    // angle tests carry an ulp band — the κ tests above
                    // stay bitwise (stored values)
                    final double EPS = 1e-12;   // rad
                    boolean constP = p.bLink || Math.abs(beta1[b - 1] - beta0[b - 1]) <= EPS;
                    boolean constE = e.bLink || Math.abs(beta1[b] - beta0[b]) <= EPS;
                    return constP && constE
                        && Math.abs(beta1[b - 1] - beta0[b]) <= EPS;
                }
            }
        }

        /** Index of the smallest break strictly beyond s (+eps); −1 if none. */
        int nextBreakIdx(double s) {
            for (int b = 0; b < breaks.length; b++) {
                if (breaks[b] > s + 1e-9) return b;
            }
            return -1;
        }

        /** Element index owning arc length s (blend zones aside). */
        int elemAt(double s) {
            int i = starts.length - 1;
            while (i > 0 && s < starts[i]) i--;
            return i;
        }

        /** Border index whose start arc equals s bitwise (1..n−1), else −1. */
        int borderAt(double s) {
            for (int b = 1; b < n; b++) {
                if (starts[b] == s) return b;
            }
            return -1;
        }

        // ---- the NEW law: Ω/m as a world vector ----

        /** Element i's own rotation-rate vector at arc s (clamped into
         *  the element — beyond its borders it holds the edge value),
         *  plus the run-leader substitution for merged members. */
        private Vec3 omegaOf(int i, double s, Vec3 T, Vec3 R) {
            int lead = runLeader[i];
            StraightCurveSection.Kind kd = kind[lead];
            if (kd == StraightCurveSection.Kind.STRAIGHT) return Vec3.ZERO;
            double u = len[i] <= 0 ? 0 : (s - starts[i]) / len[i];
            if (u < 0) u = 0;
            if (u > 1) u = 1;
            if (kd == StraightCurveSection.Kind.PLANAR) {
                double k = kappaAt(lead, u);
                return axis[lead].scale(-k);
            }
            // HELICAL
            double ch = kappaAt(lead, u);
            double b0 = beta0[lead], b1 = beta1[lead];
            double beta, dBeta;
            if (bInterp[lead] == StraightCurveSection.Interp.LINEAR) {
                beta = b0 + (b1 - b0) * u;
                dBeta = (b1 - b0) / len[i];
            } else {
                beta = b0 + (b1 - b0) * (1 - Math.cos(Math.PI * u)) / 2;
                dBeta = (b1 - b0) * (Math.PI / (2 * len[i])) * Math.sin(Math.PI * u);
            }
            // h(T) = (−T.z, 0, T.x)/|T_xz|; vertical: h → −R (GeoIntegrator's fallback)
            double lenH = Math.hypot(T.x(), T.z());
            Vec3 h = lenH <= 1e-6 ? R.scale(-1)
                : new Vec3(-T.z() / lenH, 0, T.x() / lenH);
            return new Vec3(0, -ch * Math.cos(beta), 0).add(h.scale(dBeta));
        }

        private double kappaAt(int i, double u) {
            double f = kInterp[i] == StraightCurveSection.Interp.LINEAR ? u
                : (1 - Math.cos(Math.PI * u)) / 2;
            return k0[i] + (k1[i] - k0[i]) * f;
        }

        /** Ω/m at arc s (world vector) with the sine-eased vector blend
         *  across borders, and the interim RATE roll rate (rad/s,
         *  quintic blend) in cTOut[0]. */
        Vec3 omegaAt(double s, Vec3 T, Vec3 R, double[] cTOut) {
            int i = elemAt(s);
            if (i > 0 && !merged[i] && half[i] > 1e-12 && s < starts[i] + half[i]) {
                return blendVec(i, s, T, R, cTOut);
            }
            if (i + 1 < n && !merged[i + 1] && half[i + 1] > 1e-12
                    && s > starts[i + 1] - half[i + 1]) {
                return blendVec(i + 1, s, T, R, cTOut);
            }
            cTOut[0] = cT[runLeader[i]];
            return omegaOf(i, s, T, R);
        }

        private Vec3 blendVec(int b, double s, Vec3 T, Vec3 R, double[] cTOut) {
            double u = (s - (starts[b] - half[b])) / (2 * half[b]);
            u = Math.max(0, Math.min(1, u));
            double f = (1 - Math.cos(Math.PI * u)) / 2;   // sine ease
            Vec3 a = omegaOf(b - 1, s, T, R);
            Vec3 c = omegaOf(b, s, T, R);
            // RATE roll blends with the QUINTIC (D233: the sine's nonzero
            // end curvature kinked the roll-rate graph)
            cTOut[0] = cT[b - 1] + (cT[b] - cT[b - 1]) * quintic(u);
            return a.add(c.sub(a).scale(f));
        }

        // ---- the LEGACY law (today's body components), verbatim ----

        void componentsAt(double s, double[] out) {
            int i = elemAt(s);
            if (i > 0 && half[i] > 1e-12 && s < starts[i] + half[i]) {
                blend(i, s, out);
                return;
            }
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
            out[2] = cT[b - 1] + (cT[b] - cT[b - 1]) * quintic(u);
        }
    }

    /** The quintic smoothstep 6u⁵−15u⁴+10u³ — zero value, slope AND
     *  curvature at both ends (the strict-seam smoothing weight). */
    static double quintic(double u) {
        return u * u * u * (u * (u * 6 - 15) + 10);
    }

    /** Minimum in-element ease for a GLOBAL bank change (m) — bank must
     *  never step, even with blendIn 0 (interim, Stage 1a). */
    static final double ROLL_EASE_MIN = 2;

    // ---------- RK4 on (pos, q, s) ----------

    private record State(Vec3 pos, Quat q, double s, double loss) {}

    private record Deriv(Vec3 dPos, Quat dQ, double dS, double dLoss) {}

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
        // sCap keeps every stage's lookup on THIS leg's side of a KINK
        // the leg lands on: the k4 stage evaluates AT the endpoint, and
        // reading the next element's kappa there costs two orders of
        // accuracy across a sharp border
        double sq = Math.min(st.s(), sCap);
        Vec3 omega;
        if (prof.legacy) {
            prof.componentsAt(sq, scratch);
            // curvature terms are per METER (scaled by v); the RATE roll
            // term is per SECOND, exactly like the roll lane — not scaled
            omega = U.scale(v * scratch[0]).add(R.scale(v * scratch[1]))
                .add(T.scale(scratch[2]));
        } else {
            Vec3 om = prof.omegaAt(sq, T, R, scratch);
            omega = om.scale(v).add(T.scale(scratch[0]));
        }
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

    /** GLOBAL-bank bookkeeping (interim): which run's ease is running
     *  and the bank it entered with (measured, so the ease is always
     *  continuous whatever the previous element did). */
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
        Profile prof = new Profile(sc, pos0, q0);
        double totalLen = Math.max(prof.totalLen, 1e-6);
        double[] scratch = new double[3];

        State st = new State(pos0, q0.normalize(), 0, 0);
        double y0 = pos0.y();
        double t = 0;
        List<FVDIntegrator.Sample> samples = new ArrayList<>();
        RollTracker roll = new RollTracker();
        int guard = 0;
        // the per-border records: landed states as the legs land there
        Bound[] bounds = new Bound[prof.n + 1];
        bounds[0] = bound(prof, 0, st, t, vDesign, y0, true);

        // samples stay on the uniform dt grid (downstream indexes ride
        // time), but WITHIN a step the integration sub-steps so each
        // RK4 leg sees a smooth Ω(s): legs land EXACTLY on the profile's
        // leg boundaries and on the section end (Newton-sized — v barely
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
                int bi = prof.nextBreakIdx(st.s());
                boolean kink = bi < 0 || prof.breaks[bi] >= totalLen
                    ? true                       // the section END caps (today)
                    : prof.kinkAt[bi];
                double target = bi < 0 ? totalLen : Math.min(prof.breaks[bi], totalLen);
                double sCap = kink ? target - 1e-9 : target;   // stay on THIS leg's side at a kink
                boolean landed;
                if (st.s() + v * remaining < target - 1e-12) {
                    st = rk4(st, prof, vDesign, y0, remaining, scratch, sCap, fric, drv);
                    t += remaining;
                    remaining = 0;
                    landed = false;
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
                    landed = true;
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
                if (landed && st.s() < totalLen) {
                    int b = prof.borderAt(st.s());
                    if (b > 0) bounds[b] = bound(prof, b, st, t, vDesign, y0, true);
                }
            }
        }
        addSample(samples, st, t, prof, vDesign, y0, scratch);
        bounds[prof.n] = bound(prof, prof.n, st, t, vDesign, y0, true);
        fillMergedBounds(prof, bounds, vDesign, y0);
        List<Bound> bl = new ArrayList<>(bounds.length);
        for (Bound b : bounds) bl.add(b);
        return new Result(samples, st.q(), bl);
    }

    private static Bound bound(Profile prof, int idx, State st, double t,
                               double vDesign, double y0, boolean landed) {
        ElementChain.Pose sharp = prof.poses.get(idx);
        double v = FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss());
        return new Bound(idx, st.s(), t, st.pos(), st.q(), v, st.loss(), landed,
            sharp.pos(), sharp.q());
    }

    /** MERGED borders (no leg landed there): the closed-form advance of
     *  the run's last landed state by the sub-arc — the exact spine
     *  (the run's one constant Ω about its world axis); q is the
     *  roll-free advance; t/v/loss ride the landed state (informational,
     *  landed = false). */
    private static void fillMergedBounds(Profile prof, Bound[] bounds,
                                         double vDesign, double y0) {
        for (int b = 1; b < prof.n; b++) {
            if (bounds[b] != null) continue;
            // the run's leader for a merged border; under the legacy law a
            // blended border has no centre landing either — advance from
            // the nearest earlier record with that run's constants
            int lead = prof.runLeader[b];
            int src = prof.merged[b] ? lead : b - 1;
            while (src > 0 && bounds[src] == null) src--;
            lead = prof.merged[b] ? lead : prof.runLeader[src];
            Bound from = bounds[src];
            double ds = prof.starts[b] - from.s();
            Vec3 pos;
            Quat q;
            switch (prof.kind[lead]) {
                case STRAIGHT: {
                    pos = from.pos().add(from.q().T().scale(ds));
                    q = from.q();
                    break;
                }
                case PLANAR: {
                    Vec3 a = prof.axis[lead];
                    Vec3 T = from.q().T();
                    Vec3 N = T.cross(a);
                    double theta = prof.k0[lead] * ds;
                    double hlf = theta / 2;
                    double sh = ElementChain.sinc(hlf);
                    pos = from.pos().add(T.scale(ds * ElementChain.sinc(theta)))
                        .add(N.scale(ds * hlf * sh * sh));
                    q = Quat.aboutAxis(a, -theta).mul(from.q()).normalize();
                    break;
                }
                default: {
                    StraightCurveSection.Element tmp = StraightCurveSection.Element
                        .helical(ds, prof.k0[lead], Math.toDegrees(prof.beta0[lead]));
                    tmp.bLink = true;
                    ElementChain.Pose p = ElementChain.advance(
                        ElementChain.entry(from.pos(), from.q()), tmp);
                    pos = p.pos();
                    q = p.q();
                }
            }
            ElementChain.Pose sharp = prof.poses.get(b);
            double v = FVDIntegrator.speedAt(pos, vDesign, y0, from.loss());
            double vm = Math.max(0.5, (from.v() + v) / 2);
            bounds[b] = new Bound(b, prof.starts[b], from.t() + ds / vm, pos, q, v,
                from.loss(), false, sharp.pos(), sharp.q());
        }
    }

    /** The interim GLOBAL-bank projection, applied after every leg:
     *  roll the quaternion about T so the bank vs the horizon equals
     *  the eased target. A pure T-roll never moves pos or T — the spine
     *  is untouched, only the twist. Sub-1e-12 corrections are skipped
     *  (planar zero-bank runs stay bit-identical); near-vertical track
     *  has no defined bank and is left alone. A merged run eases once,
     *  at its leader. */
    private static State globalRoll(State st, Profile prof, RollTracker roll) {
        int i = prof.runLeader[prof.elemAt(Math.min(st.s(), prof.totalLen))];
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
        // QUINTIC since v19.4 (D233): the rate leaves and lands tangent
        // to zero — the strict-seam smoothing's own smoothness class
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
        double sq = Math.min(st.s(), prof.totalLen);
        double cU, cR;   // BODY components of Ω/m
        if (prof.legacy) {
            prof.componentsAt(sq, scratch);
            cU = scratch[0];
            cR = scratch[1];
        } else {
            Vec3 om = prof.omegaAt(sq, T, R, scratch);
            cU = om.dot(U);
            cR = om.dot(R);
        }
        // felt forces at design speed (readout, not a division)
        double gv = -cR * v * v / FVDIntegrator.G0 + U.y();
        double gl = -(cU * v * v / FVDIntegrator.G0 + R.y());   // +Gl = right (D184)
        samples.add(new FVDIntegrator.Sample(t, st.s(), st.pos(), T, U, R,
            v, gv, gl));   // honest record (v18.6, D175)
    }
}
