package coaster;

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
 *    admit-what-the-app-writes clamp (D198).
 */
public final class Gestures {

    private Gestures() {}

    // ---------- (0) gizmo pick metric (v19.20, D249, item U4) ----------

    /** A center ball's pick distance: to its projected SILHOUETTE EDGE
     *  — max(0, dCenter − rPx). A press inside the visible ball reads
     *  0 px (it wins any nearest-by-px contest), a ring/arrow crossing
     *  the ball wins only outside the silhouette. */
    public static double ballEdgeDist(double dCenter, double rPx) {
        return Math.max(0, dCenter - rPx);
    }

    /** The ball's pick band measured from the edge: rPx·(mul − 1) +
     *  pad — the same OUTER reach as the D248 center-measured zone
     *  rPx·mul + pad (mul 1.5, pad 6 → rPx·0.5 + 6 px). */
    public static double ballEdgeBand(double rPx, double mul, double pad) {
        return rPx * (mul - 1) + pad;
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
}
