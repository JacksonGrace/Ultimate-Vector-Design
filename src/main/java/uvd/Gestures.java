package uvd;

import java.util.List;

/**
 * PURE gesture arithmetic (v19.19, D248) — the model half of the graph
 * editor's keyboard/strip/band gestures, factored out of GraphEditor
 * so GestureGate can pin it headlessly (no JavaFX):
 *
 *  - {@link #step}/{@link #nudge}: the arrow-key nudge — the D241
 *    spinner recipe on nodes (Shift = one tenth of the step, Ctrl =
 *    the NEXT round multiple from the current value, both combine),
 *    against the editor's per-axis snap REFERENCES;
 *  - {@link #continueOut}/{@link #matchInToOut}: the Append recipes —
 *    a smooth sloped last node's OUT handle continues its IN slope
 *    (C1, toggleSmooth's rule) instead of the historic flat sine;
 *  - {@link #resizeStart}: the speed-control START diamond as a
 *    resize from the left (end fixed, complex graphs rescaled);
 *  - {@link #resizeEndLen}: the END diamond's length clamp (section
 *    end / next control / minLen — the strip's historic arithmetic,
 *    factored so the viewport DISK write is provably the strip's,
 *    item S1);
 *  - {@link #edgeWindowLen}: the one-sided smoothing band edge drag;
 *  - {@link #clampInitSpeed}: the initial-design-speed dialog's
 *    admit-what-the-app-writes clamp (D198);
 *  - {@link #speedDiffers}/{@link #realSpeedText}: the same
 *    dialog's SPEED-CONFLICT test (half the box's %.2f quantum) and
 *    its grey "real 12.34" readout (batch 37);
 *  - {@link #panToInclude}/{@link #adoptWindow}/
 *    {@link #growRightCapped}: the graph VIEW window — Append's
 *    pan-only follow of a manual zoom window, the release-time
 *    adoption of a drag-grown window, the capped right-edge growth
 *    (batch 37);
 *  - {@link #raySphereFront}/{@link #rayCapsuleFront}/
 *    {@link #arrowFront}/{@link #ringFront}/{@link #pickFront}: the
 *    DEPTH-AWARE gizmo pick — among the parts drawn directly under
 *    the cursor the front-most wins, the D249 px metric
 *    ({@link #ballEdgeDist}) is the fallback (batch 37).
 */
public final class Gestures {

    private Gestures() {}

    // ---------- (0) gizmo pick metric (v19.20, D249, item U4) ----------

    /** A center ball's pick distance: to its projected SILHOUETTE EDGE
     *  — max(0, dCenter − rPx). A press inside the visible ball reads
     *  0 px: it wins the nearest-by-px FALLBACK (nothing drawn
     *  directly under the cursor); whether a ring/arrow drawn OVER
     *  the ball wins there is decided by depth — {@link #pickFront}
     *  (batch 37). */
    public static double ballEdgeDist(double dCenter, double rPx) {
        return Math.max(0, dCenter - rPx);
    }

    /** The ball's pick band measured from the edge: rPx·(mul − 1) +
     *  pad — the same OUTER reach as the D248 center-measured zone
     *  rPx·mul + pad (mul 1.5, pad 6 → rPx·0.5 + 6 px). */
    public static double ballEdgeBand(double rPx, double mul, double pad) {
        return rPx * (mul - 1) + pad;
    }

    // ---------- (0c) DEPTH-AWARE gizmo pick (batch 37) ----------
    // The D249 metric above has no depth: inside its silhouette a ball
    // reads 0 px, so a ring's near arc or an arrow's emerged half DRAWN
    // OVER the ball could at best tie — and the tie went to the ball.
    // Two tiers now: (1) among the parts DIRECTLY under the cursor (the
    // pick ray pierces the drawn sphere / shaft / cone / tube) the
    // front-most along the ray wins; (2) only when nothing is directly
    // under the cursor, the D249 nearest-by-px-within-band rule,
    // verbatim. Everything here is ray arithmetic in WORLD coordinates
    // (Vec3, Y up); the view builds the ray and the candidates.

    /** The rig's DRAWN geometry in rig units (Viewport3D.gizmoArrow /
     *  addRing build from these, so drawn == picked): the arrow's
     *  shaft radius and length, its cone's base radius, the arrow's
     *  whole length (shaft + cone), and a ring's tube radius. */
    public static final double ARROW_SHAFT_R = 0.05, ARROW_SHAFT_LEN = 1.0,
        ARROW_TIP_R = 0.13, ARROW_LEN = 1.32, RING_TUBE_R = 0.06;

    /** The cone is picked as this many stacked capsules (midpoint
     *  radii) — under a pixel off the drawn cone at normal sizes. */
    public static final int ARROW_TIP_N = 4;

    /** Front hit of the ray o + t·d (|d| = 1; t unclamped — a true
     *  ORTHO ray starts on the projection plane, so a part may sit at
     *  a negative t) on the sphere (c, r); NaN on a miss or r ≤ 0. */
    public static double raySphereFront(Vec3 o, Vec3 d, Vec3 c, double r) {
        if (!(r > 0)) return Double.NaN;
        Vec3 oc = c.sub(o);
        double tc = oc.dot(d);
        double h2 = r * r - (oc.dot(oc) - tc * tc);
        return h2 < 0 ? Double.NaN : tc - Math.sqrt(h2);
    }

    /** min over the non-NaN arguments (NaN = a miss). */
    private static double nanMin(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
    }

    /** Front hit of the ray on the CAPSULE of radius r about the
     *  segment a-b (the body, else the nearer end cap; a degenerate
     *  segment is the sphere; a ray parallel to the axis — an arrow
     *  aimed at the viewer — takes the nearer cap); NaN on a miss. */
    public static double rayCapsuleFront(Vec3 o, Vec3 d, Vec3 a, Vec3 b,
                                         double r) {
        if (!(r > 0)) return Double.NaN;
        Vec3 ba = b.sub(a), oa = o.sub(a);
        double baba = ba.dot(ba);
        if (baba < 1e-18) return raySphereFront(o, d, a, r);
        double bard = ba.dot(d), baoa = ba.dot(oa), rdoa = d.dot(oa),
               oaoa = oa.dot(oa);
        double A = baba - bard * bard;
        if (A > 1e-12 * baba) {
            double B = baba * rdoa - baoa * bard;
            double C = baba * oaoa - baoa * baoa - r * r * baba;
            double h = B * B - A * C;
            if (h < 0) return Double.NaN;
            double t = (-B - Math.sqrt(h)) / A;
            double y = baoa + t * bard;
            if (y > 0 && y < baba) return t;                    // the body
            return raySphereFront(o, d, y <= 0 ? a : b, r);     // an end cap
        }
        // the ray runs parallel to the axis
        return nanMin(raySphereFront(o, d, a, r),
                      raySphereFront(o, d, b, r));
    }

    /** The DRAWN arrow radius (rig units) at axial u: the shaft's
     *  0.05 on [0, 1.0), the cone's 0.13 → 0 over [1.0, 1.32]; 0
     *  outside. */
    public static double arrowRadius(double u) {
        if (u < 0 || u > ARROW_LEN) return 0;
        if (u < ARROW_SHAFT_LEN) return ARROW_SHAFT_R;
        return ARROW_TIP_R * (ARROW_LEN - u) / (ARROW_LEN - ARROW_SHAFT_LEN);
    }

    /** One half of an arrow: the shaft capsule + the cone's stack. */
    private static double arrowHalfFront(Vec3 o, Vec3 d, Vec3 c, Vec3 dir,
                                         double scale, double pad) {
        double t = rayCapsuleFront(o, d, c,
            c.add(dir.scale(ARROW_SHAFT_LEN * scale)),
            ARROW_SHAFT_R * scale + pad);
        double du = (ARROW_LEN - ARROW_SHAFT_LEN) / ARROW_TIP_N;
        for (int k = 0; k < ARROW_TIP_N; k++) {
            double u0 = ARROW_SHAFT_LEN + k * du, u1 = u0 + du;
            t = nanMin(t, rayCapsuleFront(o, d,
                c.add(dir.scale(u0 * scale)), c.add(dir.scale(u1 * scale)),
                arrowRadius(0.5 * (u0 + u1)) * scale + pad));
        }
        return t;
    }

    /** Front-most hit of the ray on an ARROW rooted at c along dir
     *  (unit) — both halves when twoSided; scale = world m per rig
     *  unit, pad = world m added to every radius (the view's 1 px
     *  slack); NaN = the arrow is not under the cursor. An arrow's
     *  root lies INSIDE the center ball, so its hit there is deeper
     *  than the ball's front: the ball keeps the rig center. */
    public static double arrowFront(Vec3 o, Vec3 d, Vec3 c, Vec3 dir,
                                    double scale, boolean twoSided,
                                    double pad) {
        double t = arrowHalfFront(o, d, c, dir, scale, pad);
        if (twoSided) {
            t = nanMin(t, arrowHalfFront(o, d, c, dir.scale(-1), scale, pad));
        }
        return t;
    }

    /** Front-most hit of the ray on a RING: the circle of WORLD radius
     *  R about c with normal ax (unit), as n capsules of WORLD radius
     *  tube — on the near arc the ring is in front of the ball, on
     *  the far arc the only tube under the cursor lies behind it;
     *  NaN = the ring is not under the cursor. */
    public static double ringFront(Vec3 o, Vec3 d, Vec3 c, Vec3 ax,
                                   double R, double tube, int n) {
        Vec3 h = Math.abs(ax.y()) < 0.9 ? new Vec3(0, 1, 0)
                                        : new Vec3(1, 0, 0);
        Vec3 u = ax.cross(h).normalize(), v = ax.cross(u);
        double t = Double.NaN;
        Vec3 prev = c.add(u.scale(R));
        for (int i = 1; i <= n; i++) {
            double th = 2 * Math.PI * i / n;
            Vec3 p = c.add(u.scale(R * Math.cos(th)))
                      .add(v.scale(R * Math.sin(th)));
            t = nanMin(t, rayCapsuleFront(o, d, prev, p, tube));
            prev = p;
        }
        return t;
    }

    /** One part at the cursor. kind 0 ball / 1 arrow / 2 ring; layer
     *  0 = the overlay rig, 1 = the world (the fixed track balls,
     *  drawn UNDER the overlay); px / band = the D249 screen metric;
     *  depth = the ray parameter of the part's front surface, NaN =
     *  not directly under the cursor. */
    public record PickCand(int kind, int layer, double px, double band,
                           double depth) {
        public boolean direct() { return !Double.isNaN(depth); }
    }

    /** Index of the picked candidate, or −1. (1) Among the DIRECT
     *  hits: the lower layer first (the overlay covers the world),
     *  then the smaller depth; an exact tie → a ball over a non-ball,
     *  else the first registered. (2) No direct hit: the D249 rule
     *  verbatim — the nearest px inside its own band, a px tie → a
     *  ball, else the first registered. */
    public static int pickFront(List<PickCand> c) {
        int best = -1;
        for (int i = 0; i < c.size(); i++) {
            PickCand p = c.get(i);
            if (!p.direct()) continue;
            if (best < 0) { best = i; continue; }
            PickCand q = c.get(best);
            if (p.layer() < q.layer()
                    || (p.layer() == q.layer() && (p.depth() < q.depth()
                        || (p.depth() == q.depth() && p.kind() == 0
                            && q.kind() != 0)))) {
                best = i;
            }
        }
        if (best >= 0) return best;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < c.size(); i++) {
            PickCand p = c.get(i);
            if (p.px() <= p.band() && (p.px() < bd
                    || (p.px() == bd && p.kind() == 0 && best >= 0
                        && c.get(best).kind() != 0))) {
                bd = p.px();
                best = i;
            }
        }
        return best;
    }

    // ---------- (0b) gizmo SLOW travel (Shift = one tenth, re-based) ----------

    /** Shift's slow factor for viewport gizmo/disk drags. */
    public static final double SLOW_K = 0.1;

    /** The slowed gesture's DEBT (raw − effective travel) after one
     *  event prevRaw → raw with Shift `slow`: Shift is RE-BASED at the
     *  moment it changes — the effective position never jumps (Shift
     *  mid-drag continues from where the part IS at a tenth of the
     *  speed; release resumes full speed from there) — and an
     *  un-shifted gesture keeps the debt at exactly 0.0, so
     *  raw − 0.0 reads the historic absolute-from-press travel
     *  BITWISE. */
    public static double slowDebt(double debt, double prevRaw, double raw,
                                  boolean slow) {
        return slow ? debt + (raw - prevRaw) * (1 - SLOW_K) : debt;
    }

    // ---------- (1) arrow nudges ----------

    /** One keyboard step of a value by q in direction dir (±1). PLAIN:
     *  v ± q exactly (bitwise the historic arrow arithmetic). SNAP:
     *  the NEXT round multiple of q from v — floor/ceil with a 1e-9
     *  guard so an on-grid value (float dust included) lands ONE cell
     *  over, never two, and an off-grid value never skips a cell —
     *  then float dust killed at q/1000 (the FvdDesigner.modStep
     *  recipe verbatim; the dust kill stays on the snap path only so
     *  a plain step keeps full precision). */
    public static double step(double v, double q, int dir, boolean snap) {
        if (dir == 0 || q <= 0) return v;
        if (!snap) return v + dir * q;
        double nv = dir > 0 ? (Math.floor(v / q + 1e-9) + 1) * q
                            : (Math.ceil(v / q - 1e-9) - 1) * q;
        return Math.round(nv / (q * 0.001)) * (q * 0.001);
    }

    /** The nudge against a REFERENCE: (cur − ref) is stepped and the
     *  reference re-added, so a Ctrl snap lands on ref + k·q — the
     *  editor passes −tOff for the absolute time grid, the previous
     *  node's x/value for a relative grid, 0 for an absolute value
     *  grid. slow scales q to a tenth BEFORE the snap (Shift+Ctrl =
     *  the tenth-scaled snap, the modStep order). A plain step
     *  ignores the reference (v ± q). */
    public static double nudge(double cur, double ref, double q, int dir,
                               boolean slow, boolean snap) {
        double qq = slow ? q / 10 : q;
        if (!snap) return step(cur, qq, dir, false);
        return ref + step(cur - ref, qq, dir, true);
    }

    // ---------- (2) append ----------

    /** Set n's OUT handle for a NEW segment n→next (next not yet in
     *  the lane, or just added after n): fraction `frac`, and the
     *  slope CONTINUES n's IN handle when n is smooth (C1 — the
     *  toggleSmooth re-smooth rule, D248) — a B-matched or hand-angled
     *  last node no longer leaves along a flat out handle while its
     *  circle knob claims smoothness. prev = the node before n (null
     *  for node 0 / a 1-node lane); a broken n, a straight-/level-
     *  locked n (enforceLocks owns that segment), a missing prev or a
     *  degenerate in-run give the historic flat out (outDv 0) bitwise.
     *  Returns true when the slope carried. */
    public static boolean continueOut(BezierBaker.Node prev,
                                      BezierBaker.Node n,
                                      BezierBaker.Node next, double frac) {
        n.outFrac = frac;
        n.outDv = 0;
        if (prev == null || next == null || !n.smooth || n.lockedOut()) {
            return false;
        }
        double inDt = BezierBaker.effInDt(prev, n);
        if (Math.abs(inDt) < 1e-12) return false;
        double outDt = BezierBaker.effOutDt(n, next);
        n.outDv = (n.inDv / inDt) * outDt;
        return n.outDv != 0;
    }

    /** Re-angle n's IN handle (fraction `frac`) to the slope of its
     *  OUT handle toward next, so a node that INHERITED an out handle
     *  (the hold node of insertHoldAfterSelected takes the selected
     *  node's old out) is truly smooth instead of a kinked node drawn
     *  with a circle knob (D248). next == null (n is the lane's last
     *  node) or a flat/degenerate out → flat in (historic). */
    public static void matchInToOut(BezierBaker.Node prev,
                                    BezierBaker.Node n,
                                    BezierBaker.Node next, double frac) {
        n.inFrac = frac;
        n.inDv = 0;
        if (prev == null || next == null || !n.smooth) return;
        double outDt = BezierBaker.effOutDt(n, next);
        if (Math.abs(outDt) < 1e-12 || n.outDv == 0) return;
        n.inDv = (n.outDv / outDt) * BezierBaker.effInDt(prev, n);
    }

    // ---------- (4) speed-control start diamond ----------

    /** Resize control c from the LEFT (D248 — the START diamond's
     *  drag): its END stays fixed, x0 moves to nx clamped into [the
     *  latest end of any other control ending at/before c.x0 (else 0),
     *  end − minLen]; complex graphs stretch with the extent by
     *  len/oldLen (the D217 fraction semantics the end diamond and the
     *  Length spinner share — node x is control-local, so scaling
     *  about x0 IS the stretch; nodes never fold). No ripple: nothing
     *  later moves. Returns the new x0. */
    public static double resizeStart(SpeedControl c, double nx,
                                     List<SpeedControl> ctls,
                                     double minLen) {
        double x1 = c.x0 + c.len;
        double lo = 0;
        if (ctls != null) {
            for (SpeedControl o : ctls) {
                if (o == c) continue;
                if (o.x0 + o.len <= c.x0 + 1e-9) {
                    lo = Math.max(lo, o.x0 + o.len);
                }
            }
        }
        double hi = x1 - minLen;
        double x0new = Math.max(lo, Math.min(nx, Math.max(lo, hi)));
        double oldLen = c.len;
        c.x0 = x0new;
        c.len = x1 - x0new;
        if (c.complex && oldLen > 1e-9) c.rescaleLanes(c.len / oldLen);
        return x0new;
    }

    /** The END diamond's new LENGTH for a cursor at native nx (D248,
     *  item S1 — factored bitwise out of GraphEditor.spDragDiamondTo
     *  so the viewport disk's write is provably the strip's): the
     *  length clamps into [minLen, end − x0], and — unless ripple-time
     *  is on — also below the next control's start (o.x0 − c.x0 for
     *  every other control starting at/after c's end). The caller
     *  applies the ripple limit, writes c.len, rescales complex
     *  graphs and ripples later controls exactly as before. A section
     *  shorter than minLen past x0 still returns minLen (clamp's
     *  lo-wins order — the historic behavior, spClampControls owns the
     *  fix-up). */
    public static double resizeEndLen(SpeedControl c, double nx,
                                      List<SpeedControl> ctls,
                                      double minLen, double end,
                                      boolean ripple) {
        double hi = end - c.x0;
        if (ctls != null) {
            for (SpeedControl o : ctls) {
                if (o == c) continue;
                if (o.x0 >= c.x0 + c.len - 1e-9 && !ripple) {
                    hi = Math.min(hi, o.x0 - c.x0);
                }
            }
        }
        // == FvdDesigner.clamp (Math.max(lo, Math.min(hi, v))) — spelled
        // out so this pure class never links the JavaFX shell
        return Math.max(minLen, Math.min(hi, nx - c.x0));
    }

    // ---------- (5) smoothing band edges ----------

    /** Window length for a band-EDGE drag (D248, one-sided): side =
     *  −1 for the left edge, +1 for the right; the window is twice
     *  the arc from the seam to the cursor ON THE GRABBED SIDE, and 0
     *  — smoothing OFF — once the cursor crosses the seam, staying 0
     *  however far it continues (the historic |·| re-grew the window
     *  on the far side). side 0 = the symmetric historic form (the
     *  s/c interior DIAMOND keeps it). Dragging back re-grows from 0
     *  continuously. */
    public static double edgeWindowLen(double sCursor, double sSeam, int side) {
        double d = sCursor - sSeam;
        if (side == 0) return 2 * Math.abs(d);
        double own = side * d;
        return own <= 0 ? 0 : 2 * own;
    }

    // ---------- (6) initial design speed ----------

    /** The initial-design-speed dialog's admissible range (D198: admit
     *  what the app itself writes — the reconcile's 0.1 stall floor
     *  and its uncapped top). */
    public static final double INIT_SPEED_MIN = 0.1, INIT_SPEED_MAX = 1e4;

    public static double clampInitSpeed(double v) {
        return Math.max(INIT_SPEED_MIN, Math.min(INIT_SPEED_MAX, v));
    }

    /** The dialog's SPEED-CONFLICT threshold (batch 37): half the
     *  box's %.2f display quantum (the speed popup's 0.05-at-%.1f
     *  precedent) — a difference the box cannot show is no conflict. */
    public static final double INIT_SPEED_SHOW_EPS = 0.005;

    /** The badge's SPEED-CONFLICT threshold (m/s) — the entry gap
     *  worth the ≠v glyph; HOME here since batch 38 (D256 — the
     *  "~v" three-state, SectionNames.speedLevel: INIT_SPEED_SHOW_EPS
     *  below, this above; GraphEditor.DRIFT_EPS stays the package
     *  alias). The '+' fork, B's real-split fallback and the POV bar's
     *  design-speed suffix keep reading it directly. */
    public static final double DRIFT_EPS = 0.2;

    /** "Set Design to Real"'s write floor (Coaster.refreezeSpeedOnly's
     *  max(0.5, …)): a real entry speed below it is a STALL. */
    public static final double INIT_SPEED_REAL_FLOOR = 0.5;

    /** Would setting the design speed to vTarget visibly change it —
     *  more than INIT_SPEED_SHOW_EPS apart? NaN on either side =
     *  false (nothing compiled is no conflict). */
    public static boolean speedDiffers(double vDesign, double vTarget) {
        return !Double.isNaN(vDesign) && !Double.isNaN(vTarget)
            && Math.abs(vDesign - vTarget) > INIT_SPEED_SHOW_EPS;
    }

    /** The grey readout beside the box: "real 12.34" (the unit lives
     *  in the row label), NaN → "real —". Locale.US; the z3 zero rule
     *  inlined (|v| < 1e-6 → +0.0, larger dust KEEPS its sign) — this
     *  pure class must never load FvdDesigner. */
    public static String realSpeedText(double vReal) {
        if (Double.isNaN(vReal)) return "real —";
        double v = Math.abs(vReal) < 1e-6 ? 0.0 : vReal;
        return String.format(java.util.Locale.US, "real %.2f", v);
    }

    // ---------- (7) the graph VIEW window (batch 37) ----------

    /** Margin of the pan-only view follow, as a fraction of the window
     *  width (feel-tunable; echoes auto-fit's 8 % headroom). */
    public static final double PAN_MARGIN = 0.08;

    /** PAN-ONLY follow: the new START of the window [w0, w1] — its
     *  width kept, never a zoom — after the smallest shift that
     *  brings x inside with `margin` to spare on the side it left
     *  by, clamped into [lo, hi − width]. Returns w0 itself (bitwise)
     *  when x already sits in [w0, w1 − margin]: a node merely near
     *  the LEFT edge never pans. The margin is cut to half the width;
     *  a NaN x or an empty window returns w0. */
    public static double panToInclude(double w0, double w1, double x,
                                      double margin, double lo, double hi) {
        double w = w1 - w0;
        if (!(w > 0) || Double.isNaN(x)) return w0;
        double m = Math.min(Math.max(0, margin), w / 2);
        double n0;
        if (x > w1 - m) n0 = x + m - w;
        else if (x < w0) n0 = x - m;
        else return w0;
        return Math.max(lo, Math.min(Math.max(lo, hi - w), n0));
    }

    /** Release-time ADOPTION of a drag-grown window: {t0, t1} of the
     *  manual window to keep — the grown window intersected with the
     *  run's domain [lo, hi] — or null = auto-fit (nothing left, or
     *  at least domain-wide: the D75 rule "a window at least run-wide
     *  IS auto-fit"). A window inside the domain returns its own two
     *  doubles bitwise. */
    public static double[] adoptWindow(double w0, double w1,
                                       double lo, double hi) {
        double a = Math.max(w0, lo), b = Math.min(w1, hi);
        if (!(b - a > 1e-9)) return null;
        if (b - a >= (hi - lo) - 1e-9) return null;
        return new double[]{ a, b };
    }

    /** Right-edge drag growth CAPPED at the run's domain end (the left
     *  edge's runT0 clamp mirrored): w1 + grow, never past domainEnd —
     *  and a window already past it (the run shrank under the drag)
     *  never shrinks. */
    public static double growRightCapped(double w1, double grow,
                                         double domainEnd) {
        return Math.min(Math.max(w1, domainEnd), w1 + grow);
    }
}
