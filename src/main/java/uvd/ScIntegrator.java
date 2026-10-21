package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * ScIntegrator.java (Phase 2, v11.0, D68; the Phase 4 overhaul from
 * v20.0, D259 §1.2-§1.4; the per-element ROLL LANE since v20.1, D259
 * §2.3) — geometry for STRAIGHT/CURVE sections. Pure Java, no JavaFX.
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
 * roll never bends the shape (the pre-v20 law read the axis off the
 * ROLLED body frame per RK4 stage: a banked R20/90°/30° turn dove
 * 10.9 m; its one-stage reversal switch {@code -Dfvd.sc.legacyAxis}
 * is GONE since v20.1 — gates/probes/legacy-axis-2026-10-21 is the
 * record).
 *
 * TRANSITION SMOOTHING: at each internal border the two neighbours'
 * Ω/m VECTORS blend with a sine ease over the entry element's blendIn,
 * CENTERED on the border (half eats into each neighbour). The ease is
 * symmetric, so the blend integrates to the mean rotation and the
 * TOTAL TURN of a straight↔arc pair is preserved exactly (gated).
 * Blend halves are clamped so no element's entry+exit blends overlap.
 *
 * ROLL (v20.1, D259 §2.3 — the lane semantics verbatim, per element):
 * every element's own roll lane bakes over ITS arc [0, L_i] on the
 * element-anchored 0.05 m grid (an upstream resize never changes
 * element i's keyframes — {@link #laneKeyframes}; ABSOLUTE lanes take
 * TrackCompiler.exactRoll's knot-exact resample). Per RK4 stage the
 * T-term comes from the element whose arc the stage reads (the leg's
 * side at a landing): absolute → rollTermPhi(slope·v), else
 * FVDIntegrator.rollTerm (local = the rate verbatim, global = the
 * D110 feed-forward p = φ' + ψ'·T.y; °/m lanes scale by v) — the
 * TWIST RATE, integrated by the same RK4 stages and applied about T
 * after each leg: T' = Ω⊥ × T never sees it, so the spine is the
 * roll-free arithmetic itself (a term inside the quaternion RK4
 * perturbed positions by the RK4 truncation, ~1e-7 m on a tight
 * pitched turn — not float dust) and a zero local lane is bitwise.
 * Per LEG (the sub-stepped unit) the quaternion post-step
 * blocks of GeoIntegrator's roll branch run with FVDIntegrator's ds
 * trapezoid: absolute = the bank snapped onto the lane's C1 target;
 * global rate = cumRoll += the lane's trapezoid over ds (°/m) or the
 * leg's time (°/s), the bank projected onto bank0 + cumRoll (bank0
 * bound at the border, lazily through vertical track); local =
 * nothing. At EVERY landing border bank0 and cumRoll RE-BIND to what
 * the previous element left — continuous by construction; an absolute
 * element whose value differs from the arriving bank SNAPS there (the
 * D118/D120 lane precedent); a MERGED border has no landing and the
 * twist simply continues. The section ENTRY is element 0's border: a
 * global rate 0 °/m holds the arriving bank from the first leg — the
 * v11.1/D233 entry ease is GONE, seam smoothing is the one entry
 * control. Vertical track: bankOfTU is NaN → the projection holds,
 * rollTermPhi → 0, a local rate continues.
 *
 * LEG BOUNDARIES (D259 §1.4.2 + §2.3.2): EVERY element border is a
 * leg boundary — the RK4 legs land on it exactly — EXCEPT a MERGED one
 * (same kind, no blend, both constant, equal κ across the border,
 * equal axis within AXIS_MERGE_EPS, EQUAL ROLL FLAGS and a value-
 * continuous lane — left end value == right node-0 value bitwise);
 * blend-window EDGES are additional leg boundaries; the on-side lookup
 * cap (sCap) applies at KINKS only (stepping OVER a κ step costs ~0.1°
 * per crossing). A merged run uses its FIRST element's axis and one
 * κ, and its end arc is start_first + (L_1 + L_2 + …) — the ARC-SUM
 * rule, so a split that keeps L1 + L2 == L bitwise reproduces the
 * parent's leg schedule and bits (a flat lane; a sloped lane's split
 * reproduces the parent to grid-phase dust, documented).
 *
 * Every border's state is RECORDED (the {@link Bound} list on the
 * Result — the ONE per-border record TrackCompiler carries onto the
 * Span as scBounds): landed states at leg boundaries, the closed-form
 * advance of the last landed state at merged borders, plus the SHARP
 * chain's pose from ElementChain.
 *
 * Integration: the shape is authored in DISTANCE but downstream code
 * indexes samples by uniform ride time, so we RK4 in TIME on state
 * (pos, q, s): q' = ½(0, v·Ω/m(s) + term·T)⊗q, pos' = T·v, s' = v,
 * with v ALGEBRAIC from energy at the design speed. The last step is
 * Newton-sized to land s EXACTLY on the total length.
 *
 * Felt Gv/Gl are recorded at the design speed off the BODY components
 * of Ω/m (Gv = −(Ω·R)·v²/g + U.y, Gl = −((Ω·U)·v²/g + R.y)), so rail
 * colors, stats and '+' holds stay honest.
 */
public final class ScIntegrator {

    private ScIntegrator() {}

    /** The DISTANCE bake grid (m) — the lane sections' 0.05 m
     *  (v17.11, D151), element-anchored here. */
    static final double LANE_GRID = 0.05;

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

    /** One element's roll lane baked over ITS OWN arc [0, L] on the
     *  element-anchored grid (D259 §2.3.1): lanes 0/1 are the shared
     *  EMPTY lists (keyframe slots 0/1 read 0); an ABSOLUTE lane's
     *  knots are re-read exactly off the Bezier (the D121 rule). A
     *  lane whose nodes end before L holds (interpAt / bankHermite
     *  clamp beyond the last keyframe); nodes beyond L cannot exist
     *  (the cut rule) — a hand-edited file's are not reached. */
    static List<FVDIntegrator.Keyframe> laneKeyframes(StraightCurveSection.Element e) {
        List<FVDIntegrator.Keyframe> k = BezierBaker.bake(e.roll.lanes(), LANE_GRID);
        if (e.roll.rollAbsolute()) k = TrackCompiler.exactRoll(k, e.roll.nodes);
        return k;
    }

    // ---------- the profile ----------

    /** The rotation-rate profile. starts[i] = element i's start arc;
     *  half[i] = blend half-width of the border at starts[i] (0 for the
     *  section entry — package-visible for the strip's band edges);
     *  kfs[i] / rollG[i] / pm[i] / abs[i] = element i's baked roll lane
     *  and its settings (§2.3). */
    static final class Profile {
        final int n;
        final double[] starts, len, half;
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
        /** The per-element roll lanes (§2.3.1) + settings. */
        final List<List<FVDIntegrator.Keyframe>> kfs;
        final boolean[] rollG, pm, abs;

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
            kfs = new ArrayList<>(n);
            rollG = new boolean[n];
            pm = new boolean[n];
            abs = new boolean[n];
            poses = ElementChain.poses(sc.elements, pos0, q0);
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
                    double b0 = Math.toRadians(ElementChain.elevDeg(entry));
                    beta0[i] = b0;
                    beta1[i] = e.bLink ? b0 : Math.toRadians(e.b1Deg);
                }
                // element 0's blendIn is INERT (the entry is the
                // section seam — D9/§1.1); interior borders blend
                half[i] = i == 0 ? 0 : Math.max(0, e.blendIn / 2);
                kfs.add(laneKeyframes(e));
                rollG[i] = e.roll.rollGlobal();
                pm[i] = e.roll.ratePerMeter();
                abs[i] = e.roll.rollAbsolute();
            }
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
            // MERGE rule (§1.4.2 + the §2.3.2 lane clause)
            for (int b = 1; b < n; b++) {
                merged[b] = mergeable(sc, b);
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
            // leg boundaries: every non-merged border lands; a blended
            // border lands on its two EDGES (smooth, no cap) and its
            // CENTRE, a sharp one is a KINK (the on-side cap)
            List<double[]> br = new ArrayList<>();   // {s, kink}
            for (int b = 1; b < n; b++) {
                if (merged[b]) continue;
                if (half[b] > 1e-12) {
                    br.add(new double[] { starts[b] - half[b], 0 });
                    br.add(new double[] { starts[b], 0 });
                    br.add(new double[] { starts[b] + half[b], 0 });
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
        }

        private boolean mergeable(StraightCurveSection sc, int b) {
            if (half[b] > 1e-12) return false;
            StraightCurveSection.Element p = sc.elements.get(b - 1);
            StraightCurveSection.Element e = sc.elements.get(b);
            if (p.kind != e.kind) return false;
            // the LANE clause (§2.3.2): equal roll flags AND a value-
            // continuous lane at the border — left end value == right
            // node-0 value bitwise (a value step or differing flags is
            // a landing: the bank re-binds / snaps there)
            if (p.roll.rollGlobal() != e.roll.rollGlobal()
                    || p.roll.ratePerMeter() != e.roll.ratePerMeter()
                    || p.roll.rollAbsolute() != e.roll.rollAbsolute()) return false;
            double endP = BezierBaker.exactAt(p.roll.nodes, p.L);
            double startE = BezierBaker.exactAt(e.roll.nodes, 0);
            if (Double.doubleToRawLongBits(endP) != Double.doubleToRawLongBits(startE)) {
                return false;
            }
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

        // ---- Ω/m as a world vector ----

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
         *  across borders. */
        Vec3 omegaAt(double s, Vec3 T, Vec3 R) {
            int i = elemAt(s);
            if (i > 0 && !merged[i] && half[i] > 1e-12 && s < starts[i] + half[i]) {
                return blendVec(i, s, T, R);
            }
            if (i + 1 < n && !merged[i + 1] && half[i + 1] > 1e-12
                    && s > starts[i + 1] - half[i + 1]) {
                return blendVec(i + 1, s, T, R);
            }
            return omegaOf(i, s, T, R);
        }

        private Vec3 blendVec(int b, double s, Vec3 T, Vec3 R) {
            double u = (s - (starts[b] - half[b])) / (2 * half[b]);
            u = Math.max(0, Math.min(1, u));
            double f = (1 - Math.cos(Math.PI * u)) / 2;   // sine ease
            Vec3 a = omegaOf(b - 1, s, T, R);
            Vec3 c = omegaOf(b, s, T, R);
            return a.add(c.sub(a).scale(f));
        }

        // ---- the lane's T-term (§2.3.3) ----

        /** The roll term (rad/s) for a stage reading arc sq (already on
         *  the leg's side of a kink) from the element whose arc holds
         *  it: absolute → the lane's C1 slope (°/m) × v through the
         *  global feed-forward; else FVDIntegrator.rollTerm — local =
         *  the rate verbatim (bitwise the roll-free arithmetic at 0),
         *  global = p = φ' + ψ'·T.y, a °/m lane scaled by v. omegaPerp
         *  = the roll-free Ω in rad/s (Ω/m·v). */
        double rollTerm(double sq, double v, Vec3 T, Vec3 omegaPerp) {
            int i = elemAt(sq);
            double x = sq - starts[i];
            List<FVDIntegrator.Keyframe> k = kfs.get(i);
            if (abs[i]) {
                return FVDIntegrator.rollTermPhi(
                    Math.toRadians(FVDIntegrator.rollSlopeAt(k, x)) * v, T, omegaPerp);
            }
            return FVDIntegrator.rollTerm(FVDIntegrator.interpAt(k, x), T, omegaPerp,
                rollG[i], pm[i] ? v : 1);
        }
    }

    // ---------- RK4 on (pos, q, s) ----------

    /** phi = the lane's TWIST ANGLE (rad) integrated alongside — the
     *  T-term's integral; applied as a post-leg rotation about T. */
    private record State(Vec3 pos, Quat q, double s, double loss, double phi) {}

    private record Deriv(Vec3 dPos, Quat dQ, double dS, double dLoss, double dPhi) {}

    /** drive = a speed control's d(loss)/ds for this leg (replaces
     *  friction); NaN = natural (v18.0, D160). */
    private static Deriv deriv(State st, Profile prof, double vDesign, double y0,
                               double sCap, FVDIntegrator.Friction fric,
                               double drive) {
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
        Vec3 omegaPerp = prof.omegaAt(sq, T, R).scale(v);
        // the lane's T-term (§2.3.3) is the TWIST RATE phi' — integrated
        // by the same RK4 stages and applied about T after the leg
        // (exact: T' = Ω⊥ × T never sees it, and U = twist(phi)·U_rf
        // solves the coupled equation exactly), so the SPINE is the
        // roll-free arithmetic itself — a term inside the quaternion
        // RK4 perturbed positions by the RK4 truncation (~1e-7 m on a
        // tight pitched turn), not float dust. The historic `+ T·0.0`
        // keeps the Stage-1a bits incl. signed zeros.
        double term = prof.rollTerm(sq, v, T, omegaPerp);
        Vec3 omega = omegaPerp.add(T.scale(0.0));
        Quat dQ = new Quat(0, omega.x(), omega.y(), omega.z()).mul(q).scale(0.5);
        return new Deriv(T.scale(v), dQ, v,
            (Double.isNaN(drive) ? fric.dLossDs(v) : drive) * v, term);
    }

    private static State advance(State y, Deriv k, double h) {
        return new State(y.pos().add(k.dPos().scale(h)),
            y.q().add(k.dQ().scale(h)), y.s() + k.dS() * h,
            y.loss() + k.dLoss() * h, y.phi() + k.dPhi() * h);
    }

    private static State rk4(State y, Profile prof, double vDesign, double y0,
                             double h, double sCap,
                             FVDIntegrator.Friction fric, double drive) {
        Deriv k1 = deriv(y, prof, vDesign, y0, sCap, fric, drive);
        Deriv k2 = deriv(advance(y, k1, h / 2), prof, vDesign, y0, sCap, fric, drive);
        Deriv k3 = deriv(advance(y, k2, h / 2), prof, vDesign, y0, sCap, fric, drive);
        Deriv k4 = deriv(advance(y, k3, h), prof, vDesign, y0, sCap, fric, drive);
        Vec3 pos = y.pos().add(
            k1.dPos().add(k2.dPos().scale(2)).add(k3.dPos().scale(2)).add(k4.dPos())
                .scale(h / 6));
        Quat q = y.q().add(
            k1.dQ().add(k2.dQ().scale(2)).add(k3.dQ().scale(2)).add(k4.dQ())
                .scale(h / 6)).normalize();
        double s = y.s() + (k1.dS() + 2 * k2.dS() + 2 * k3.dS() + k4.dS()) * h / 6;
        double loss = y.loss() + (k1.dLoss() + 2 * k2.dLoss()
            + 2 * k3.dLoss() + k4.dLoss()) * h / 6;
        double phi = y.phi() + (k1.dPhi() + 2 * k2.dPhi()
            + 2 * k3.dPhi() + k4.dPhi()) * h / 6;
        return new State(pos, q, s, loss, phi);
    }

    public static Result integrate(StraightCurveSection sc, double dt,
                                   double vDesign, Vec3 pos0, Quat q0) {
        return integrate(sc, dt, vDesign, pos0, q0,
            FVDIntegrator.Friction.NONE);
    }

    /** FRICTION variant (Phase 3, v17.0 D133): the loss deficit rides
     *  the RK4 state. The SPINE is speed-independent as ever (pos' and
     *  q' scale by the same v) — friction moves only the clock, the
     *  felt records and a °/s lane's twist (per-second by design). */
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

        State st = new State(pos0, q0.normalize(), 0, 0, 0);
        double y0 = pos0.y();
        double t = 0;
        List<FVDIntegrator.Sample> samples = new ArrayList<>();
        int guard = 0;
        // the global-rate bookkeeping (§2.3.4-5): bank0 = the bank the
        // current element's run holds against, bound at its border
        // (NaN on vertical track → the lazy bind on the first finite
        // bank), cumRoll = the lane's integral since that border
        double bank0 = FVDIntegrator.bankOfTU(st.q().T(), st.q().U());
        double cumRoll = 0;
        // the per-border records: landed states as the legs land there
        Bound[] bounds = new Bound[prof.n + 1];
        bounds[0] = bound(prof, 0, st, t, vDesign, y0, true);

        // samples stay on the uniform dt grid (downstream indexes ride
        // time), but WITHIN a step the integration sub-steps so each
        // RK4 leg sees a smooth Ω(s): legs land EXACTLY on the profile's
        // leg boundaries and on the section end (Newton-sized — v barely
        // changes inside one step, so two refinements reach float noise)
        while (st.s() < totalLen - 1e-9 && guard++ < 500_000) {
            addSample(samples, st, t, prof, vDesign, y0);
            double remaining = dt;
            while (remaining > 1e-12 && st.s() < totalLen - 1e-9) {
                double v = Math.max(0.5,
                    FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss()));
                double sLeg = st.s();
                double phiLeg = st.phi();
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
                double hLeg;
                if (st.s() + v * remaining < target - 1e-12) {
                    st = rk4(st, prof, vDesign, y0, remaining, sCap, fric, drv);
                    t += remaining;
                    hLeg = remaining;
                    remaining = 0;
                    landed = false;
                } else {
                    double h = (target - st.s()) / v;
                    State end = st;
                    for (int it = 0; it < 3; it++) {
                        end = rk4(st, prof, vDesign, y0, h, sCap, fric, drv);
                        double vEnd = Math.max(0.5,
                            FVDIntegrator.speedAt(end.pos(), vDesign, y0,
                                end.loss()));
                        h += (target - end.s()) / vEnd;
                    }
                    st = new State(end.pos(), end.q(), target, end.loss(),
                        end.phi());
                    t += h;
                    remaining = Math.max(0, remaining - h);
                    hLeg = h;
                    landed = true;
                }
                if (ctl != null) {
                    double vA = FVDIntegrator.speedAt(st.pos(), vDesign,
                        y0, st.loss());
                    double dl = ctl.snap(sLeg, v, vA, !Double.isNaN(drv));
                    if (dl != 0) {
                        st = new State(st.pos(), st.q(), st.s(),
                            st.loss() + dl, st.phi());
                    }
                }
                // ---- the per-LEG roll block (§2.3.4) ----
                // first the leg's TWIST — the T-term's RK4 integral —
                // about T (a zero lane: no rotation, bitwise)
                double dphi = st.phi() - phiLeg;
                if (dphi != 0) {
                    Quat q = st.q();
                    st = new State(st.pos(),
                        Quat.aboutAxis(q.T(), dphi).mul(q).normalize(),
                        st.s(), st.loss(), st.phi());
                }
                // the leg's element = the one holding its start; its END
                // read stays on the leg's own element at a landing border
                // and follows the member only across MERGED borders
                int ie = prof.elemAt(sLeg);
                double s1 = st.s();
                int i1 = ie;
                while (i1 + 1 < prof.n && prof.merged[i1 + 1]
                        && s1 > prof.starts[i1 + 1]) i1++;
                double x0 = sLeg - prof.starts[ie];
                double x1 = s1 - prof.starts[i1];
                if (prof.abs[ie]) {
                    // ABSOLUTE (D118): the bank snaps onto the lane's C1
                    // target at the just-reached arc; NaN (vertical) holds
                    double tb = Math.toRadians(FVDIntegrator.bankAt(prof.kfs.get(i1), x1));
                    Quat q = st.q();
                    Vec3 T = q.T();
                    double b = FVDIntegrator.bankOfTU(T, q.U());
                    if (!Double.isNaN(b) && Math.abs(tb - b) >= 1e-12) {
                        st = new State(st.pos(),
                            Quat.aboutAxis(T, tb - b).mul(q).normalize(),
                            st.s(), st.loss(), st.phi());
                    }
                } else if (prof.rollG[ie]) {
                    // GLOBAL rate: the bank target = bank0 + the lane's
                    // trapezoid — over the leg's ARC for a °/m lane
                    // (exact: ds is the leg's own advance, D115), over its
                    // TIME for a °/s lane
                    double r0 = FVDIntegrator.interpAt(prof.kfs.get(ie), x0).rollRateDeg();
                    double r1 = FVDIntegrator.interpAt(prof.kfs.get(i1), x1).rollRateDeg();
                    cumRoll += Math.toRadians(r0 + r1) / 2
                        * (prof.pm[ie] ? (s1 - sLeg) : hLeg);
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
                                st = new State(st.pos(),
                                    Quat.aboutAxis(q.T(), d).mul(q).normalize(),
                                    st.s(), st.loss(), st.phi());
                            }
                        }
                    }
                }
                // (local: nothing — the rate rides Ω verbatim)
                if (landed && st.s() < totalLen) {
                    int b = prof.borderAt(st.s());
                    if (b > 0) {
                        bounds[b] = bound(prof, b, st, t, vDesign, y0, true);
                        // the bank RE-BINDS at every landing border
                        // (§2.3.5): the next element holds against what
                        // this one left (NaN on vertical → lazy)
                        cumRoll = 0;
                        bank0 = FVDIntegrator.bankOfTU(st.q().T(), st.q().U());
                    }
                }
            }
        }
        addSample(samples, st, t, prof, vDesign, y0);
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

    /** Signed bank of U vs the level up at T (rad), about T; positive
     *  matches the roll lane's positive direction. Caller guarantees
     *  horiz2 = 1 − T.y² is not degenerate. RETAINED for Coaster's
     *  bridge march (two call sites that hand it a raw tangent plus a
     *  precomputed horiz2 — switching them to FVDIntegrator.bankOfTU
     *  would read the quaternion's own T bits and move the march by
     *  dust); the integrator itself reads bankOfTU since v20.1. */
    static double bankOf(Quat q, Vec3 T, double horiz2) {
        Vec3 U = q.U();
        // level up = Y − (Y·T)T, normalized
        Vec3 ul = new Vec3(-T.y() * T.x(), horiz2, -T.y() * T.z()).normalize();
        return Math.atan2(ul.cross(U).dot(T), U.dot(ul));
    }

    private static void addSample(List<FVDIntegrator.Sample> samples, State st,
                                  double t, Profile prof, double vDesign, double y0) {
        Quat q = st.q();
        Vec3 T = q.T();
        Vec3 U = q.U();
        Vec3 R = U.cross(T);
        double v = FVDIntegrator.speedAt(st.pos(), vDesign, y0, st.loss());
        double sq = Math.min(st.s(), prof.totalLen);
        Vec3 om = prof.omegaAt(sq, T, R);
        double cU = om.dot(U);   // BODY components of Ω/m
        double cR = om.dot(R);
        // felt forces at design speed (readout, not a division)
        double gv = -cR * v * v / FVDIntegrator.G0 + U.y();
        double gl = -(cU * v * v / FVDIntegrator.G0 + R.y());   // +Gl = right (D184)
        samples.add(new FVDIntegrator.Sample(t, st.s(), st.pos(), T, U, R,
            v, gv, gl));   // honest record (v18.6, D175)
    }
}
