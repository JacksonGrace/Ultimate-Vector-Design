package uvd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * RollLane.java (Stage 1b, v20.1, D259 §2.1-§2.2 — D4 as REVISED
 * 2026-10-21): the roll lane of ONE straight/curve ELEMENT. Pure
 * Java, no JavaFX.
 *
 * Every element carries its OWN lane on its OWN axis x ∈ [0, L]
 * (meters of arc from the element's start) with its OWN roll
 * settings — exactly as a force/geo section carries its lane 2.
 * Element borders behave like plain section boundaries for roll:
 * "if changes to the roll graphs or lengths of elements outside of a
 * given element are made … the roll graphs within the element do not
 * shift or change". Node 0 stays at x = 0 (the lane rule); the
 * integrator bakes the lane over the element's own arc and the bank
 * re-binds at every border landing (ScIntegrator, §2.3).
 *
 * THE END NODE — the cut / shadow rule (§2.2, {@link #applyLength}):
 * a LONGER element leaves the last node where it is and holds its
 * value as the dotted continuation (E1); a SHORTER one CUTS the lane
 * at the new end with the insert-node math (Coaster.splitLanes' exact
 * de Casteljau split, the drawn curve over [0, L] unchanged bitwise)
 * and keeps the cut-off part as the SHADOW — the virtual lane — which
 * RETURNS bitwise when the element grows again (E2/E3), unless the
 * cut node's position, value, handle SLOPES or smooth flag changed
 * meanwhile (the {@link Cut} fingerprint; slopes, not handle lengths
 * — a neighbor's move rescales dv and keeps the slope, D105). A
 * changed SEGMENT INTO the cut node (the node before it edited, a
 * node inserted between) keeps the cut node as a real node on growth
 * and continues the old graph past it (the 2026-10-21 note 1 — the
 * drawn graph over [0, L] never changes on growth, see
 * {@link #applyLength}). The shadow persists in the file (`rsh` +
 * `rcut`, DesignIO §2.8); the compile never reads it.
 *
 * Settings: rollGlobal / ratePerMeter / rollAbsolute with the lane
 * sections' interlocks — absolute ⇒ global (the setter forces it),
 * local ⇒ not absolute; ratePerMeter is ignored while absolute
 * (D213). Default (D3): global rate 0 °/m — two nodes (0, 0), (L, 0)
 * with flat DEFAULT_FRAC handles ({@link BezierBaker#holdLane}).
 *
 * {@link #lanes()} is ONE object for the element's life — lanes 0/1
 * the shared unmodifiable {@link #EMPTY}, lane 2 the {@link #nodes}
 * list itself (mutated in place: the editor's identity guards and
 * BezierBaker.bake's three-lane contract both hold).
 */
public final class RollLane {

    /** The shared, unmodifiable empty lane in slots 0/1 of lanes(). */
    public static final List<BezierBaker.Node> EMPTY =
        Collections.unmodifiableList(new ArrayList<>());

    /** The on-node band of the cut = Coaster.splitLanes' (the UI
     *  snap): a node within it of the new end IS the cut node and
     *  nothing is inserted; a lane whose last node lies within it of
     *  (or before) the end is returned verbatim (E1). */
    public static final double CUT_EPS = 1e-9;

    /** The fingerprint's SLOPE band (relative): a D105 rescale keeps
     *  a slope to rounding, not bitwise, so the untouched test reads
     *  the in / out / previous-out slopes within this band (t, val
     *  and smooth compare bitwise). A user edit moves a slope by far
     *  more; a value re-typed to within the band keeps the shadow
     *  (the §2.2 E4 corner, harmless). */
    public static final double SLOPE_EPS = 1e-12;

    /** The fingerprint of the cut node c and of the previous node's
     *  out slope (§2.1): recorded when the lane was last cut; present
     *  iff a shadow exists. Slopes are the node editor's numbers —
     *  dv / effDt (BezierBaker.effInDt / effOutDt); c's out slope
     *  reads over the SHADOW's segment past it (its next node is
     *  virtual). */
    public record Cut(boolean inserted, double t, double val,
                      double inSlope, double outSlope, boolean smooth,
                      double prevOutSlope) {
        Cut negate() {
            return new Cut(inserted, t, -val, -inSlope, -outSlope,
                smooth, -prevOutSlope);
        }
    }

    /** The lane: x = the arc from the element's start, in [0, L].
     *  The list object is long-lived (mutated in place). */
    public final List<BezierBaker.Node> nodes;
    private final List<List<BezierBaker.Node>> lanes;

    private boolean rollGlobal = true;
    private boolean ratePerMeter = true;
    private boolean rollAbsolute = false;

    /** The VIRTUAL lane (null = uncut). */
    private List<BezierBaker.Node> shadow;
    /** The cut fingerprint (null iff shadow == null). */
    private Cut cut;

    /** The default lane of an element of length L (D3/§2.1 (c)). */
    public RollLane(double L) {
        this(BezierBaker.holdLane(0, L, 0));
    }

    private RollLane(List<BezierBaker.Node> nodes) {
        this.nodes = nodes;
        this.lanes = Collections.unmodifiableList(
            Arrays.asList(EMPTY, EMPTY, nodes));
    }

    // ---- settings ----

    public boolean rollGlobal() { return rollGlobal; }

    public boolean ratePerMeter() { return ratePerMeter; }

    public boolean rollAbsolute() { return rollAbsolute; }

    /** == rollGlobal (no yaw/pitch axis on an s/c; the lane
     *  sections' effRollGlobal twin). */
    public boolean effRollGlobal() { return rollGlobal; }

    /** Local deselects Absolute (→ a rate lane, the D241 precedent). */
    public void setRollGlobal(boolean global) {
        rollGlobal = global;
        if (!global) rollAbsolute = false;
    }

    public void setRatePerMeter(boolean perMeter) {
        ratePerMeter = perMeter;
    }

    /** Absolute forces Global. */
    public void setRollAbsolute(boolean absolute) {
        rollAbsolute = absolute;
        if (absolute) rollGlobal = true;
    }

    /** The three at once, normalized (absolute ⇒ global). */
    public void setFlags(boolean global, boolean perMeter, boolean absolute) {
        rollAbsolute = absolute;
        rollGlobal = global || absolute;
        ratePerMeter = perMeter;
    }

    // ---- the lane triple, shadow, fingerprint ----

    /** [EMPTY, EMPTY, nodes] — ONE object for the element's life. */
    public List<List<BezierBaker.Node>> lanes() { return lanes; }

    /** The virtual lane while cut, else null (read-only view). */
    public List<BezierBaker.Node> shadow() {
        return shadow == null ? null : Collections.unmodifiableList(shadow);
    }

    public Cut cut() { return cut; }

    public boolean isCut() { return shadow != null; }

    /** The lane's drawn value at x (holds outside its nodes). */
    public double valueAt(double x) {
        return BezierBaker.exactAt(nodes, x);
    }

    /** Replace the nodes (a bake / the loader / a split half): the
     *  shadow and fingerprint drop — a re-authored lane is uncut. */
    public void installNodes(List<BezierBaker.Node> fresh) {
        List<BezierBaker.Node> copy = deepCopy(fresh);
        nodes.clear();
        nodes.addAll(copy);
        shadow = null;
        cut = null;
    }

    /** The loader's shadow install (§2.8 ii): accepted only when the
     *  fingerprint's t is the lane's last node's t BITWISE and both
     *  halves are present; false = dropped (the caller counts it as
     *  damaged). */
    public boolean installShadow(List<BezierBaker.Node> sh, Cut c) {
        if (sh == null || c == null || sh.isEmpty() || nodes.isEmpty()) return false;
        if (!bits(nodes.get(nodes.size() - 1).t, c.t())) return false;
        shadow = deepCopy(sh);
        cut = c;
        return true;
    }

    public RollLane copy() {
        RollLane r = new RollLane(deepCopy(nodes));
        r.rollGlobal = rollGlobal;
        r.ratePerMeter = ratePerMeter;
        r.rollAbsolute = rollAbsolute;
        r.shadow = shadow == null ? null : deepCopy(shadow);
        r.cut = cut;
        return r;
    }

    /** Mirror's rule (§2.1): every node's val / inDv / outDv in the
     *  lane AND the shadow negate, the fingerprint's val and slopes
     *  negate, flags kept — a rate AND a bank negate alike. */
    public void negate() {
        negateLane(nodes);
        if (shadow != null) negateLane(shadow);
        if (cut != null) cut = cut.negate();
    }

    private static void negateLane(List<BezierBaker.Node> lane) {
        for (BezierBaker.Node n : lane) {
            n.val = -n.val;
            n.inDv = -n.inDv;
            n.outDv = -n.outDv;
        }
    }

    // ---- the cut / shadow rule (§2.2) ----

    /** The ONE length-change entry (Element.setLength calls it with
     *  the element's new L): laws E1-E3 verbatim.
     *  E1 uncut and L ≥ last.t: nothing moves (the hold).
     *  E2 uncut and L < last.t: shadow := the lane, nodes := its
     *     LEFT de Casteljau half at L, the fingerprint recorded.
     *  E3 cut: UNTOUCHED(c) ⇒ the virtual lane V = the user's prefix
     *     ++ (c iff not inserted) ++ the shadow's tail, the node before
     *     c taking the shadow's out handle over the restored segment
     *     iff its out slope still equals the recorded one (raw fields
     *     when the gap is unchanged — bitwise — else the shadow's
     *     slope over the new gap, rescaleDvToGaps' arithmetic); else
     *     V = the lane (the tail is GONE). Then L ≥ V.last.t ⇒ the
     *     lane is V, uncut again; else the lane is cut(V, L) with V
     *     as the shadow and a fresh fingerprint. */
    void applyLength(double L) {
        if (nodes.isEmpty()) return;
        if (shadow == null) {
            double lastT = nodes.get(nodes.size() - 1).t;
            if (lastT <= L + CUT_EPS) return;                 // E1
            List<BezierBaker.Node> V = deepCopy(nodes);       // E2
            cutTo(V, L);
            return;
        }
        List<BezierBaker.Node> V = untouched() ? rebuildVirtual()  // E3
                                               : deepCopy(nodes);
        if (V.get(V.size() - 1).t <= L + CUT_EPS) {
            nodes.clear();
            nodes.addAll(V);
            shadow = null;
            cut = null;
            return;
        }
        cutTo(V, L);
    }

    /** nodes := cut(V, L) (the left de Casteljau half), shadow := V,
     *  the fingerprint recorded. V must extend past L + CUT_EPS. */
    private void cutTo(List<BezierBaker.Node> V, double L) {
        List<BezierBaker.Node> left = cutLeft(V, L);
        nodes.clear();
        nodes.addAll(left);
        shadow = V;
        // the index of the last V node at or before the cut (the
        // splitLanes walk): one more node in the left half = inserted
        int i = 0;
        while (i < V.size() - 1 && V.get(i + 1).t <= L + CUT_EPS) i++;
        boolean inserted = nodes.size() == i + 2;
        BezierBaker.Node c = nodes.get(nodes.size() - 1);
        BezierBaker.Node prev = nodes.size() >= 2 ? nodes.get(nodes.size() - 2) : null;
        BezierBaker.Node next = firstAfter(V, c.t);
        double inSlope = prev == null ? 0 : slope(c.inDv, BezierBaker.effInDt(prev, c));
        double outSlope = next == null ? 0 : slope(c.outDv, BezierBaker.effOutDt(c, next));
        double prevOut = prev == null ? 0 : slope(prev.outDv, BezierBaker.effOutDt(prev, c));
        cut = new Cut(inserted, c.t, c.val, inSlope, outSlope, c.smooth, prevOut);
    }

    /** UNTOUCHED(c): c is still the lane's last node and its t, val,
     *  smooth (bitwise) and in / out slopes (SLOPE_EPS) equal the
     *  fingerprint. */
    boolean untouched() {
        if (cut == null || shadow == null || nodes.isEmpty()) return false;
        BezierBaker.Node c = nodes.get(nodes.size() - 1);
        if (!bits(c.t, cut.t()) || !bits(c.val, cut.val()) || c.smooth != cut.smooth()) {
            return false;
        }
        BezierBaker.Node prev = nodes.size() >= 2 ? nodes.get(nodes.size() - 2) : null;
        BezierBaker.Node next = firstAfter(shadow, cut.t());
        double inSlope = prev == null ? 0 : slope(c.inDv, BezierBaker.effInDt(prev, c));
        double outSlope = next == null ? 0 : slope(c.outDv, BezierBaker.effOutDt(c, next));
        return near(inSlope, cut.inSlope()) && near(outSlope, cut.outSlope());
    }

    /** E3's V for an untouched cut node (the 2026-10-21 hand-test
     *  note 1 — "the roll graph's shape must never change on growth"):
     *  <ul><li>the SEGMENT INTO c still the shadow's own left half
     *  (the node before c bitwise the split's — t, val, out handle —
     *  and c's in handle bitwise the split's): the BITWISE RETURN —
     *  the user's prefix ++ (c iff not inserted) ++ the shadow's tail,
     *  the node before c taking the shadow's raw out handle back
     *  (the drawn curve over [0, L] IS the shadow's there, by the de
     *  Casteljau identity, so dropping the inserted knot changes
     *  nothing);</li>
     *  <li>otherwise (the node before c moved, re-valued or
     *  re-handled, or a node inserted between it and c): c STAYS a
     *  real node exactly where the cut was, its out handle := the
     *  shadow's RIGHT half's node 0 out handle at the cut and the
     *  first tail node's in handle := that half's node 1 in handle —
     *  the drawn graph over [0, L] is kept bitwise (every node of the
     *  user's lane rides through untouched) and the old graph
     *  continues past c (the de Casteljau right half, ≤ 1e-12 of the
     *  shadow's curve).</li></ul>
     *  Before this rule a changed segment restored the previous
     *  node's handle at the shadow's slope over the new gap and
     *  dropped c — the drawn curve over [prev, c] changed on growth
     *  (his report). The fingerprint's prevOutSlope is INERT now
     *  (still recorded + persisted — `rcut` keeps its grammar). */
    private List<BezierBaker.Node> rebuildVirtual() {
        int size = nodes.size();
        boolean ins = cut.inserted();
        BezierBaker.Node c = nodes.get(size - 1);
        BezierBaker.Node prev = size >= 2 ? nodes.get(size - 2) : null;
        List<BezierBaker.Node> left = cutLeft(shadow, cut.t());
        BezierBaker.Node leftC = left.get(left.size() - 1);
        BezierBaker.Node leftPrev = left.size() >= 2 ? left.get(left.size() - 2) : null;
        boolean intact = prev == null ? leftPrev == null
            : leftPrev != null && bits(prev.t, leftPrev.t) && bits(prev.val, leftPrev.val)
                && bits(prev.outFrac, leftPrev.outFrac) && bits(prev.outDv, leftPrev.outDv)
                && bits(c.inFrac, leftC.inFrac) && bits(c.inDv, leftC.inDv);
        List<BezierBaker.Node> V = new ArrayList<>();
        if (intact) {
            int prefixEnd = ins ? size - 1 : size;   // c excluded iff inserted
            for (int k = 0; k < prefixEnd; k++) V.add(nodes.get(k).copy());
            BezierBaker.Node shadowPrev = lastBefore(shadow, cut.t());
            if (ins && shadowPrev != null && !V.isEmpty()) {
                BezierBaker.Node p = V.get(V.size() - 1);   // prev's copy
                p.outFrac = shadowPrev.outFrac;             // the split's
                p.outDv = shadowPrev.outDv;                 // raw, bitwise
            }
            for (BezierBaker.Node n : shadow) {
                if (n.t > cut.t()) V.add(n.copy());
            }
            return V;
        }
        for (int k = 0; k < size; k++) V.add(nodes.get(k).copy());
        List<BezierBaker.Node> right = cutRight(shadow, cut.t());
        BezierBaker.Node cv = V.get(V.size() - 1);
        cv.outFrac = right.get(0).outFrac;   // the curve past the cut
        cv.outDv = right.get(0).outDv;
        boolean first = true;
        for (BezierBaker.Node n : shadow) {
            if (n.t <= cut.t()) continue;
            BezierBaker.Node m = n.copy();
            if (first && right.size() >= 2) {
                m.inFrac = right.get(1).inFrac;
                m.inDv = right.get(1).inDv;
            }
            first = false;
            V.add(m);
        }
        return V;
    }

    /** RATE-UNIT CONVERSION of the lane's VALUES (the 2026-10-21
     *  hand-test note 6 — a Switch between °/s and °/m converts the
     *  graph literally, never reinterprets it): every node's value and
     *  handle offsets × f(t) (the editor passes v at the node's arc for
     *  °/m → °/s, 1/v the other way), the shadow's nodes the same, the
     *  fingerprint's value and slopes × f(cut.t) so untouched() keeps
     *  reading true (the same multiplication on both sides). */
    public void scaleValues(java.util.function.DoubleUnaryOperator f) {
        for (BezierBaker.Node n : nodes) {
            double k = f.applyAsDouble(n.t);
            n.val *= k;
            n.inDv *= k;
            n.outDv *= k;
        }
        if (shadow != null) {
            for (BezierBaker.Node n : shadow) {
                double k = f.applyAsDouble(n.t);
                n.val *= k;
                n.inDv *= k;
                n.outDv *= k;
            }
        }
        if (cut != null) {
            double k = f.applyAsDouble(cut.t());
            cut = new Cut(cut.inserted(), cut.t(), cut.val() * k,
                cut.inSlope() * k, cut.outSlope() * k, cut.smooth(),
                cut.prevOutSlope() * k);
        }
    }

    // ---- the halves of a split element (E5: shadow-free) ----

    /** Coaster.cutElement's lane halves at arc l1 from the element's
     *  start: the LEFT de Casteljau half and the RIGHT half re-based
     *  to x = 0 (Coaster.splitLanes verbatim), both shadow-free,
     *  flags copied; a lane ending before the cut hands the right
     *  half a single held node (a legal 1-node lane, D196). */
    public RollLane[] cutHalves(double l1) {
        RollLane a = new RollLane(cutLeft(nodes, l1));
        RollLane b = new RollLane(cutRight(nodes, l1));
        for (RollLane r : new RollLane[] { a, b }) {
            r.rollGlobal = rollGlobal;
            r.ratePerMeter = ratePerMeter;
            r.rollAbsolute = rollAbsolute;
        }
        return new RollLane[] { a, b };
    }

    /** cut(X, L): the LEFT half of Coaster.splitLanes' exact de
     *  Casteljau split of X at x = L (deep copies; X untouched). */
    static List<BezierBaker.Node> cutLeft(List<BezierBaker.Node> X, double L) {
        return Coaster.splitLanes(Collections.singletonList(X), L, false).get(0);
    }

    /** The RIGHT half, re-based so its node 0 sits at exactly 0. */
    static List<BezierBaker.Node> cutRight(List<BezierBaker.Node> X, double L) {
        return Coaster.splitLanes(Collections.singletonList(X), L, true).get(0);
    }

    // ---- helpers ----

    static List<BezierBaker.Node> deepCopy(List<BezierBaker.Node> lane) {
        List<BezierBaker.Node> out = new ArrayList<>(lane.size());
        for (BezierBaker.Node n : lane) out.add(n.copy());
        return out;
    }

    /** The first node with t > x, else null. */
    private static BezierBaker.Node firstAfter(List<BezierBaker.Node> lane, double x) {
        for (BezierBaker.Node n : lane) if (n.t > x) return n;
        return null;
    }

    /** The last node with t < x, else null. */
    private static BezierBaker.Node lastBefore(List<BezierBaker.Node> lane, double x) {
        BezierBaker.Node out = null;
        for (BezierBaker.Node n : lane) if (n.t < x) out = n;
        return out;
    }

    /** dv / effDt, 0 over a degenerate gap (removeBridging's rule). */
    static double slope(double dv, double effDt) {
        return Math.abs(effDt) < 1e-12 ? 0 : dv / effDt;
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) <= SLOPE_EPS * Math.max(1.0, Math.abs(b));
    }

    static boolean bits(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
}
