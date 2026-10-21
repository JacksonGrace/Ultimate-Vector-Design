package uvd;

import java.util.ArrayList;
import java.util.List;

/**
 * STRAIGHT/CURVE section (Phase 2, v11.0, D68; SECTIONS-DESIGN §2/§3;
 * the Phase 4 OVERHAUL from v20.0, D259 — docs/SC-OVERHAUL-DESIGN.md §1):
 * the first NON-LANE section mode. The designer authors an ELEMENT LIST
 * instead of three curves — straights, PLANAR curves (a turn in one
 * plane) and HELICAL curves (a turn about the vertical that climbs) —
 * the classic NL2 way to build stations, brake runs, lift approaches,
 * turns and helices. Always DISTANCE domain: curvature is authored per
 * meter, so the SHAPE is completely speed-independent (vDesign only maps
 * it onto ride TIME via the design-speed profile, same energy rule as
 * every other mode).
 *
 * ELEMENTS ARE CURVATURE-DEFINED since v20.0 (D259 §1.1): an element is
 * an arc length L plus a signed curvature k0 → k1 (constant while
 * kLink), a plane direction (PLANAR) or an end elevation (HELICAL). The
 * old radius/angle pair is DERIVED (radius 1/|k|, turn = L·k̄). Every
 * element rotates about a ROLL-FREE AXIS fixed at its ENTRY against the
 * level frame (D2; {@link ElementChain}) — the body roll never bends the
 * shape, so a banked turn stays the same circle (today's defect: a
 * banked R20 90° 30° turn dove 10.9 m).
 *
 * Elements are MUTABLE in the project's Node style — the editor edits
 * them in place; undo deep-copies via {@link Element#copy}.
 *
 * TRANSITION SMOOTHING: each element carries an ENTRY blend length (m,
 * {@code blendIn}). The curvature VECTOR blends from the previous
 * element's to this one's with a sine ease over that length, CENTERED
 * on the border (half eats into each neighbor). The ease is symmetric,
 * so the blend integrates to exactly the mean curvature — a turn's
 * TOTAL ANGLE is preserved exactly for straight↔arc transitions
 * (gated); only the position eases. ScIntegrator clamps blend halves
 * that would overlap inside a short element.
 *
 * STAGE-1a INTERIM (D259.1a): roll is still the v11.1 per-element
 * RATE / GLOBAL pair ({@link RollMode}, {@code rollMode}/{@code rollVal}),
 * acting about T only, and element 0's {@code blendIn} is still the
 * section-entry roll ease (D232/D233). Stage 1b replaces both with the
 * per-element roll LANE (D4 as revised 2026-10-21).
 */
public final class StraightCurveSection implements Section {

    /** STRAIGHT is the UI kind for κ ≡ 0 (k0 = k1 = 0 enforced by every
     *  setter — mathematically the κ = 0 planar). */
    public enum Kind { STRAIGHT, PLANAR, HELICAL }

    /** How a start → end pair (curvature, or a helix's elevation) runs
     *  over the TRACK arc u = s/L: LINEAR = a + (b − a)·u, SINUSOIDAL =
     *  a + (b − a)·(1 − cos πu)/2 (the ScIntegrator blend ease). */
    public enum Interp { LINEAR, SINUSOIDAL }

    /** STAGE-1a INTERIM roll (v11.1, D69; the 1b lane replaces it).
     *  RATE   — body roll at rollVal °/s about T, exactly like the roll
     *           lane (time-based: total twist depends on design speed).
     *  GLOBAL — HOLD rollVal as the bank angle measured against the
     *           HORIZON, eased in from the element's entry bank over
     *           max(blendIn, 2 m) so bank never jumps. */
    public enum RollMode { RATE, GLOBAL }

    // ---- the constants named ONCE here (D259 §1.1 — every other
    // class cites the name, never the number) ----

    /** |k| cap (1/m) = the 0.1 m radius floor (DesignIO / the editor). */
    public static final double KAPPA_MAX = 10;
    /** A radius reads "∞" below this |k| (ρ > 100 km); the κ = 0
     *  handling of the solver (D259 §4.4/§4.6). */
    public static final double KAPPA_INF = 1e-5;
    /** The minimum element arc length (m). */
    public static final double MIN_ELEM_LEN = 0.5;
    /** D6's < 90°: the helix end-elevation cap of the model, the loader,
     *  the solver's clamp, the ghost's clamp and the window's refusal —
     *  kept BELOW the HELIX_VERT_EPS band (which begins at 89.943°), so
     *  a helix never hands the next element a vertical entry. */
    public static final double B1_MAX = 89.9;
    /** The vertical test on horiz2 = 1 − T.y² (the bankOf hold-off):
     *  below it no level frame exists. */
    public static final double HELIX_VERT_EPS = 1e-6;
    /** Two PLANAR axes closer than this (per component) merge into one
     *  leg run (D259 §1.4.2). */
    public static final double AXIS_MERGE_EPS = 1e-12;

    /** One element (D259 §1.1). Field names are the design doc's. */
    public static final class Element {
        // lock bits (D259 §1.1; semantics §4.7 — this class only stores,
        // copies, mirrors, saves and loads them)
        public static final int LOCK_LEN = 1, LOCK_K0 = 2, LOCK_K1 = 4,
            LOCK_TURN = 8, LOCK_DIR = 16, LOCK_HEAD = 32, LOCK_ELEV = 64,
            LOCK_X = 128, LOCK_Y = 256, LOCK_Z = 512, LOCK_B1 = 1024,
            LOCK_MASK = 2047;

        public Kind kind = Kind.STRAIGHT;
        /** Arc length over the TRACK (m), ≥ MIN_ELEM_LEN (loader floor 0.01). */
        public double L;
        /** Start / end curvature, 1/m, SIGNED — reported-LEFT (D185/D187):
         *  κ > 0 at dir 0 turns LEFT. PLANAR: track curvature; HELICAL:
         *  PLAN curvature c_h (per horizontal metre). |k| ≤ KAPPA_MAX. */
        public double k0, k1;
        /** While true every write of k0 copies into k1 (the writer emits
         *  k1 == k0); unlinking keeps both; re-linking sets k1 := k0. */
        public boolean kLink = true;
        public Interp kInterp = Interp.LINEAR;
        /** PLANAR: plane tilt about the entry tangent against the LEVEL
         *  frame (D259 §1.2): 0 flat (+κ = left), 90 pull-up, 180 flat
         *  (+κ = right), 270 drop. Stored verbatim (no normalization —
         *  the solver's branch continuity needs the raw value). */
        public double dirDeg;
        /** HELICAL: END elevation β1 (deg), |b1Deg| ≤ B1_MAX, NEVER NaN —
         *  every factory stores b1 := β0 (the entry elevation) resolved at
         *  creation; while bLink the compile evaluates β1 := the sharp
         *  chain's β0 and this field is inert (the last resolved β0). */
        public double b1Deg;
        public boolean bLink = false;
        public Interp bInterp = Interp.LINEAR;
        /** Interior entry blend (m): half = blendIn/2 each side of the
         *  border, clamped per ScIntegrator. Default 0 (D9). Element 0's
         *  value is the Stage-1a INTERIM section-entry roll ease (D233);
         *  from 1b it is always 0. */
        public double blendIn;
        /** The LOCK bit field (LOCK_* above). */
        public int locks;

        // Stage-1a INTERIM roll (v11.1): the 1b lane replaces both.
        public RollMode rollMode = RollMode.GLOBAL;
        public double rollVal = 0;   // °/s (RATE) or ° vs horizon (GLOBAL)

        // ---- factories (D259 §1.1 defaults: straight 10 m; planar R20
        // 90° left; helical plan R20 90°; blendIn 0, no locks, D9/D13) ----

        public static Element straight(double L) {
            Element e = new Element();
            e.kind = Kind.STRAIGHT;
            e.L = L;
            return e;
        }

        /** A straight with an entry blend (the v11 signature, kept). */
        public static Element straight(double L, double blendIn) {
            Element e = straight(L);
            e.blendIn = blendIn;
            return e;
        }

        public static Element planar(double L, double k, double dirDeg) {
            Element e = new Element();
            e.kind = Kind.PLANAR;
            e.L = L;
            e.k0 = k;
            e.k1 = k;
            e.kLink = true;
            e.dirDeg = dirDeg;
            return e;
        }

        public static Element planar(double L, double k, double dirDeg,
                                     double blendIn) {
            Element e = planar(L, k, dirDeg);
            e.blendIn = blendIn;
            return e;
        }

        /** The v11.0 radius/angle form (kept for every caller that
         *  thinks in R and degrees — gates, the migration, the dialog):
         *  k = sign(angle)/radius, L = |angle|·radius. A zero angle or a
         *  non-positive radius is the κ = 0 planar of length
         *  |angle|·radius. */
        public static Element curve(double radius, double angleDeg,
                                    double dirDeg, double blendIn) {
            double a = Math.toRadians(angleDeg);
            double k = radius > 0 ? Math.signum(angleDeg) / radius : 0;
            return planar(Math.abs(a) * Math.max(radius, 0), k, dirDeg, blendIn);
        }

        /** A helix of plan curvature c_h whose end elevation starts equal
         *  to the given ENTRY elevation (D13: the pitch pair unlinked with
         *  end := start). */
        public static Element helical(double L, double cH, double beta0Deg) {
            Element e = new Element();
            e.kind = Kind.HELICAL;
            e.L = L;
            e.k0 = cH;
            e.k1 = cH;
            e.kLink = true;
            e.b1Deg = clampB1(beta0Deg);
            e.bLink = false;
            return e;
        }

        /** The helix factory off the chain pose at its entry. */
        public static Element helical(double L, double cH, ElementChain.Pose entry) {
            return helical(L, cH, ElementChain.elevDeg(entry));
        }

        static double clampB1(double deg) {
            if (Double.isNaN(deg)) return 0;
            return Math.max(-B1_MAX, Math.min(B1_MAX, deg));
        }

        // ---- setters that keep the invariants ----

        public boolean isStraight() { return kind == Kind.STRAIGHT; }

        /** Writes k0 (and k1 while linked); STRAIGHT keeps κ ≡ 0. */
        public void setK0(double k) {
            if (kind == Kind.STRAIGHT) { k0 = 0; k1 = 0; return; }
            k0 = k;
            if (kLink) k1 = k;
        }

        public void setK1(double k) {
            if (kind == Kind.STRAIGHT) { k0 = 0; k1 = 0; return; }
            k1 = k;
            if (kLink) k0 = k;
        }

        /** Re-linking sets k1 := k0 (spec L51); unlinking keeps both. */
        public void setKLink(boolean link) {
            kLink = link;
            if (link) k1 = k0;
        }

        /** Linking sets b1 := β0 ONCE (the compile then keeps the pair
         *  equal); unlinking keeps the stored end value. */
        public void setBLink(boolean link, double beta0Deg) {
            bLink = link;
            if (link) b1Deg = clampB1(beta0Deg);
        }

        /** The dialog's radius + signed turn (deg) form of a PLANAR
         *  element: k = sign(turn)/radius (constant), L = |turn|·radius. */
        public void setRadiusAndTurn(double radius, double turnDeg) {
            double r = Math.max(1 / KAPPA_MAX, radius);
            double k = turnDeg == 0 ? 0 : Math.signum(turnDeg) / r;
            kind = Kind.PLANAR;
            k0 = k;
            k1 = k;
            kLink = true;
            L = Math.abs(Math.toRadians(turnDeg)) * r;
        }

        // ---- derived twins (read-only, never stored) ----

        /** Arc length along the track, whatever the kind. */
        public double arcLen() { return L; }

        /** The START curvature (1/m; 0 for straights) — the v11 name. */
        public double kappa() { return kind == Kind.STRAIGHT ? 0 : k0; }

        /** Curvature at u = s/L ∈ [0, 1] (clamped) per kInterp. */
        public double kappaAt(double u) {
            if (kind == Kind.STRAIGHT) return 0;
            double uu = u < 0 ? 0 : u > 1 ? 1 : u;
            double f = kInterp == Interp.LINEAR ? uu
                : (1 - Math.cos(Math.PI * uu)) / 2;
            return k0 + (k1 - k0) * f;
        }

        /** ∫₀ˢ k over the TRACK arc (rad) — closed for both interps:
         *  LINEAR k0·s + (k1−k0)·s²/(2L); SINUSOIDAL (k0 + Δ/2)·s −
         *  (Δ/2)·(L/π)·sin(πs/L). */
        public double turnRadAt(double s) {
            if (kind == Kind.STRAIGHT || L <= 0) return 0;
            double ss = s < 0 ? 0 : s > L ? L : s;
            double dk = k1 - k0;
            if (kInterp == Interp.LINEAR) {
                return k0 * ss + dk * ss * ss / (2 * L);
            }
            return (k0 + dk / 2) * ss
                - (dk / 2) * (L / Math.PI) * Math.sin(Math.PI * ss / L);
        }

        /** PLANAR: the signed total turn (deg) = toDegrees(L·(k0+k1)/2)
         *  — both interps share the mean; HELICAL: the plan turn needs
         *  the entry elevation — {@link ElementChain#turnDeg}; STRAIGHT 0. */
        public double turnDeg() {
            if (kind != Kind.PLANAR) return 0;
            return Math.toDegrees(L * (k0 + k1) / 2);
        }

        /** The v11 "angle" twin: the signed turn (deg) of a PLANAR. */
        public double angleDeg() { return turnDeg(); }

        /** Radius 1/|k0| (m), +∞ when |k0| < KAPPA_INF — the "∞" twin. */
        public double radius() {
            return Math.abs(k0) < KAPPA_INF ? Double.POSITIVE_INFINITY
                : 1 / Math.abs(k0);
        }

        /** Elevation at u per bInterp (deg) from the given entry
         *  elevation; linked ⇒ the entry elevation throughout. */
        public double betaDegAt(double u, double beta0Deg) {
            if (kind != Kind.HELICAL || bLink) return beta0Deg;
            double uu = u < 0 ? 0 : u > 1 ? 1 : u;
            double f = bInterp == Interp.LINEAR ? uu
                : (1 - Math.cos(Math.PI * uu)) / 2;
            return beta0Deg + (b1Deg - beta0Deg) * f;
        }

        /** D259 §1.7's field mapping for a type change (the op, its lock
         *  carry, refusals and the absorb are Coaster's / the editor's):
         *  entryElevDeg / endElevDeg = the element's own SHARP entry and
         *  end elevations off the chain (ElementChain). Returns false,
         *  changing nothing, for a helix at a vertical entry
         *  (SectionNames.HELIX_VERTICAL_WHY) or an end elevation over
         *  B1_MAX (HELIX_ELEV_WHY) — the callers pre-check the same. */
        public boolean changeKind(Kind to, double entryElevDeg, double endElevDeg) {
            if (to == kind) return true;
            if (to == Kind.HELICAL) {
                if (Double.isNaN(entryElevDeg)
                        || Math.abs(entryElevDeg) >= B1_MAX) return false;
                double b1 = kind == Kind.PLANAR ? endElevDeg : entryElevDeg;
                if (Double.isNaN(b1) || Math.abs(b1) > B1_MAX) return false;
                b1Deg = b1;
                bLink = false;
                bInterp = Interp.LINEAR;
                if (kind == Kind.STRAIGHT) { k0 = 0; k1 = 0; kLink = true; kInterp = Interp.LINEAR; }
                dirDeg = 0;
                kind = Kind.HELICAL;
                return true;
            }
            if (to == Kind.PLANAR) {
                if (kind == Kind.STRAIGHT) { k0 = 0; k1 = 0; kLink = true; kInterp = Interp.LINEAR; }
                dirDeg = 0;
                b1Deg = 0; bLink = false; bInterp = Interp.LINEAR;
                kind = Kind.PLANAR;
                return true;
            }
            // → STRAIGHT: L and blend kept, everything else dropped
            k0 = 0; k1 = 0; kLink = true; kInterp = Interp.LINEAR;
            dirDeg = 0; b1Deg = 0; bLink = false; bInterp = Interp.LINEAR;
            kind = Kind.STRAIGHT;
            return true;
        }

        public Element copy() {
            Element e = new Element();
            e.kind = kind;
            e.L = L;
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
            e.rollMode = rollMode;
            e.rollVal = rollVal;
            return e;
        }
    }

    /** The list object is long-lived — the editor holds the reference. */
    public final List<Element> elements;

    public double vDesign;
    public boolean reversed = false;
    public boolean vPinned = false;   // v14.5 D97: reconcile-exempt
    /** v19.27 D256: the reconcile lands vDesign on the REAL seam speed
     *  (see Section.vRealAnchored). */
    public boolean vRealAnchored = false;
    public String name = "";

    /** Cross-domain view (v16.0, D103): true = the strip lays out on
     *  the TIME axis (the pre-v16.0 rendering — element bands warped
     *  through the design-speed profile); false (default) = the
     *  native METERS axis, where elements are linear. */
    public boolean viewOtherDomain = false;

    /** SPEED CONTROLS (Phase 3, v18.0 D160) over the section's arc +
     *  the plain speed-line diamonds. */
    public final List<SpeedControl> speedCtls = new ArrayList<>();
    public final List<Double> speedMarks = new ArrayList<>();

    public StraightCurveSection(List<Element> elements, double vDesign) {
        this.elements = elements;
        this.vDesign = vDesign;
    }

    /** The '+' menu's default: one straight of the given length —
     *  continues the entry pose dead ahead, seamless after anything. */
    public static StraightCurveSection straight(double lengthM, double vDesign) {
        List<Element> els = new ArrayList<>();
        // element 0's blendIn = the Stage-1a INTERIM section-entry roll
        // ease, taken verbatim since v19.4 (D233): new sections keep the
        // comfortable 2 m default explicitly (the 1b lane retires it)
        els.add(Element.straight(lengthM, 2));
        return new StraightCurveSection(els, vDesign);
    }

    public double totalLen() {
        double s = 0;
        for (Element e : elements) s += e.arcLen();
        return s;
    }

    /** Rough duration estimate (constant vDesign) — layout fallback for
     *  the one redraw that can run before the first compile lands. */
    public double estDuration() {
        return totalLen() / Math.max(0.5, vDesign);
    }

    @Override public double vDesign() { return vDesign; }

    @Override public void vDesign(double v) { vDesign = v; }

    @Override public Domain domain() { return Domain.DISTANCE; }

    @Override public boolean viewOtherDomain() { return viewOtherDomain; }

    @Override public void viewOtherDomain(boolean v) { viewOtherDomain = v; }

    @Override public boolean reversed() { return reversed; }

    @Override public void reversed(boolean r) { reversed = r; }

    @Override public boolean vPinned() { return vPinned; }

    @Override public void vPinned(boolean p) { vPinned = p; }

    @Override public boolean vRealAnchored() { return vRealAnchored; }

    @Override public void vRealAnchored(boolean a) { vRealAnchored = a; }

    @Override public List<SpeedControl> speedControls() { return speedCtls; }

    @Override public List<Double> speedMarks() { return speedMarks; }

    @Override public boolean speedControllable() { return !reversed; }
}
