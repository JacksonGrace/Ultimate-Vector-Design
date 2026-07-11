package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * STRAIGHT/CURVE section (Phase 2, v11.0, D68; SECTIONS-DESIGN §2/§3):
 * the first NON-LANE section mode. The designer authors an ELEMENT LIST
 * — straight lines and circular arcs — instead of three curves; the
 * classic NL2 way to build stations, brake runs and lift approaches.
 * Always DISTANCE domain: curvature is authored per meter, so the SHAPE
 * is completely speed-independent (vDesign only maps it onto ride TIME
 * via the design-speed profile, same energy rule as every other mode).
 *
 * Elements are MUTABLE in the project's Node style — the editor edits
 * them in place; undo deep-copies via {@link Element#copy}.
 *
 * TRANSITION SMOOTHING (user spec: in from day one): each element
 * carries an ENTRY smoothing length (m). The curvature VECTOR blends
 * from the previous element's to this one's with a sine ease over that
 * length, CENTERED on the boundary (half eats into each neighbor).
 * Because the ease is symmetric, the blend integrates to exactly the
 * mean curvature — an arc's TOTAL TURN ANGLE is preserved exactly for
 * straight↔arc transitions (gated); only the position eases. Element
 * 0's entry is the SECTION seam and stays sharp (smoothIn ignored
 * there); the section exit is likewise sharp. ScIntegrator clamps
 * blend halves that would overlap inside a short element.
 */
public final class StraightCurveSection implements Section {

    public enum Kind { STRAIGHT, CURVE }

    /** How an element treats ROLL (v11.1, D69).
     *  RATE   — body roll at rollVal °/s about T, exactly like the roll
     *           lane (time-based: total twist depends on design speed).
     *           rollVal 0 = v11.0 behavior — the bank vs the horizon
     *           DRIFTS through pitched turns (the frame just follows
     *           the curvature axes).
     *  GLOBAL — HOLD rollVal as the bank angle measured against the
     *           HORIZON (NL2-classic banking): 0 keeps the rails level
     *           through slopes and turns, the body frame twisting as
     *           needed. Eased in from the element's entry bank over
     *           max(smoothIn, 2 m) so bank never jumps. */
    public enum RollMode { RATE, GLOBAL }

    /** One element. STRAIGHT uses {@code length}; CURVE uses radius +
     *  signed angle (arc length derived) + direction:
     *    dirDeg 0 = flat turn (positive angle = rider-RIGHT),
     *    90 = positive angle pulls UP, 180 = left, 270 = drops.
     *  (The curve plane tilts about the track axis with dir; angle sign
     *  mirrors within that plane — same redundancy NL2 has.)
     *  smoothIn = entry-transition smoothing length, m (0 = sharp).
     *  rollMode/rollVal per {@link RollMode}; new elements default
     *  GLOBAL 0 (level rails, the station/brake norm) — the LOADER
     *  defaults short old-format lines to RATE 0 instead so v11.0
     *  saves keep their geometry. */
    public static final class Element {
        public Kind kind;
        public double length;     // m (STRAIGHT)
        public double radius;     // m (CURVE)
        public double angleDeg;   // signed total angle (CURVE)
        public double dirDeg;     // curve-plane direction (CURVE)
        public double smoothIn;   // m, entry transition blend
        public RollMode rollMode = RollMode.GLOBAL;
        public double rollVal = 0;   // °/s (RATE) or ° vs horizon (GLOBAL)

        public static Element straight(double length, double smoothIn) {
            Element e = new Element();
            e.kind = Kind.STRAIGHT;
            e.length = length;
            e.smoothIn = smoothIn;
            return e;
        }

        public static Element curve(double radius, double angleDeg,
                                    double dirDeg, double smoothIn) {
            Element e = new Element();
            e.kind = Kind.CURVE;
            e.radius = radius;
            e.angleDeg = angleDeg;
            e.dirDeg = dirDeg;
            e.smoothIn = smoothIn;
            return e;
        }

        /** Arc length along the track, whatever the kind. */
        public double arcLen() {
            return kind == Kind.STRAIGHT
                ? length
                : Math.abs(Math.toRadians(angleDeg)) * radius;
        }

        /** Signed curvature (1/m; sign = angle sign; 0 for straights). */
        public double kappa() {
            return kind == Kind.STRAIGHT || radius <= 0 ? 0
                : Math.signum(angleDeg) / radius;
        }

        public Element copy() {
            Element e = new Element();
            e.kind = kind;
            e.length = length;
            e.radius = radius;
            e.angleDeg = angleDeg;
            e.dirDeg = dirDeg;
            e.smoothIn = smoothIn;
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
    public String name = "";

    public StraightCurveSection(List<Element> elements, double vDesign) {
        this.elements = elements;
        this.vDesign = vDesign;
    }

    /** The '+' menu's default: one straight of the given length —
     *  continues the entry pose dead ahead, seamless after anything. */
    public static StraightCurveSection straight(double lengthM, double vDesign) {
        List<Element> els = new ArrayList<>();
        els.add(Element.straight(lengthM, 0));
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

    @Override public boolean reversed() { return reversed; }

    @Override public void reversed(boolean r) { reversed = r; }

    @Override public boolean vPinned() { return vPinned; }

    @Override public void vPinned(boolean p) { vPinned = p; }
}
