import uvd.*;

import java.util.*;

/** D248 gate — GRAPH-EDITOR GESTURES (v19.19, item G): the pure
 *  arithmetic behind the editor's gestures, factored into
 *  uvd.Gestures so it runs headlessly.
 *  (1) ARROW NUDGES — Gestures.step / nudge: plain = v ± q bitwise;
 *      Shift = a tenth; Ctrl = the NEXT round multiple from the
 *      current value (never skips a cell, on-grid float dust lands one
 *      cell over); Shift+Ctrl = the tenth-scaled snap; the per-axis
 *      REFERENCES (absolute time grid via −tOff, previous-node time /
 *      value, absolute value) on time, g, °/s and °/m steps.
 *  (2) APPEND — Gestures.continueOut / matchInToOut: a smooth sloped
 *      last node's out handle continues its in slope (C1 through the
 *      joint, the new node holds the value with a flat in); broken /
 *      locked / 1-node / flat-in nodes give the historic flat out
 *      bitwise; the distance-domain twin; the insert-hold recipe (the
 *      selected node's out continues its in, the hold node's in
 *      matches its inherited out) is C1 at BOTH joints.
 *  (4) START DIAMOND — Gestures.resizeStart: the end stays fixed, x0
 *      clamps against the previous control / end − minLen / 0, later
 *      controls untouched, complex graphs (both lanes) rescale exactly
 *      as the end-diamond stretch (SpeedControl.rescaleLanes).
 *  (5) BAND EDGES — Gestures.edgeWindowLen: one-sided; the symmetric
 *      form kept for the s/c interior diamond.
 *  (6) INIT SPEED — Gestures.clampInitSpeed: the D198 [0.1, 1e4] band;
 *      batch 37: Gestures.speedDiffers (the dialog's speed-conflict
 *      test, half the box's %.2f quantum) and realSpeedText (the grey
 *      "real 12.34" readout — z3 zero rule, Locale.US).
 *  (7e) BOUNDARY DISKS — ViewDisks.boundarySamples: PLAIN interior
 *      boundaries only since D249 (item U6): every section's exit
 *      except the last; the piece entry / last exit never; index +
 *      position dedupe, clamped interior i1, a bridge tail's ENTRY.
 *  (8) GIZMO PICK METRIC — Gestures.ballEdgeDist / ballEdgeBand (D249,
 *      item U4): a ball measures from its silhouette edge (inside = 0
 *      px), the edge band keeps the D248 zone's outer reach — the
 *      FALLBACK metric since batch 37 (nothing directly under the
 *      cursor; inside the silhouette depth decides, section 12).
 *  (9) WHOLE-DISK PICK — ViewDisks.rimPoints / pickRadiusPx /
 *      pickScore (D249, item S3): the ring's rim points lie in the
 *      disk plane at the ring radius; the pick radius is the projected
 *      rim's bounding radius floored at PICK_PX (NaN = behind-camera
 *      rim points skipped, none usable = the floor); the normalized
 *      score d²/pickR² ranks disks so a big near disk cannot steal a
 *      far one, the rim itself is a miss (score 1), pickR ≤ 0 never
 *      hits; the doubled thickness constants.
 *  (10) GIZMO SLOW TRAVEL — Gestures.slowDebt (batch 36): Shift's
 *      tenth, re-based per event; never slow = the historic travel
 *      bitwise.
 *  (11) GRAPH VIEW WINDOW (batch 37) — Gestures.panToInclude (Append's
 *      pan-only follow of a manual zoom window: width kept, minimum
 *      shift, the PAN_MARGIN 8 % margin, clamped into the run's
 *      domain, a node near the LEFT edge never pans), adoptWindow (a
 *      drag-grown window kept at release: intersected with the
 *      domain, domain-wide = auto-fit) and growRightCapped (right-edge
 *      growth capped at the domain end, never shrinking).
 *  (12) DEPTH-AWARE GIZMO PICK (batch 37) — Gestures.raySphereFront /
 *      rayCapsuleFront / arrowRadius / arrowFront / ringFront /
 *      pickFront: among the parts drawn directly under the cursor the
 *      front-most wins (the overlay rig over the world's track
 *      balls), else the D249 px rule verbatim; a ring's near arc and
 *      an arrow's emerged half beat the ball they are drawn over,
 *      the far arc / far half / the roots inside the ball lose.
 */
public class GestureGate {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static void check(boolean ok, String name, String detail) {
        check(ok, name + "  (" + detail + ")");
    }

    static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    static final double SINE = 1 - 2 / Math.PI;   // GraphEditor.SINE_FRAC

    /** One pick candidate for the (12) resolver checks. */
    static Gestures.PickCand pc(int kind, int layer, double px, double band,
                                double depth) {
        return new Gestures.PickCand(kind, layer, px, band, depth);
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** A three-lane hill (the EndFollowGate fixture) for the (7)
     *  compile-backed disk checks. */
    static List<List<BezierBaker.Node>> hillLanes(double len, double amp) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 1 + amp},
            {len * 0.7, 1 - amp * 0.6}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.3}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 25}, {len, 0} }));
        return ls;
    }

    static List<BezierBaker.Node> copyLane(List<BezierBaker.Node> l) {
        List<BezierBaker.Node> c = new ArrayList<>();
        for (BezierBaker.Node n : l) c.add(n.copy());
        return c;
    }

    static boolean sameLane(List<BezierBaker.Node> a, List<BezierBaker.Node> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            BezierBaker.Node x = a.get(i), y = b.get(i);
            if (x.t != y.t || x.val != y.val || x.outFrac != y.outFrac
                    || x.outDv != y.outDv || x.inFrac != y.inFrac
                    || x.inDv != y.inDv || x.smooth != y.smooth) return false;
        }
        return true;
    }

    /** Slope just left / right of node i (the C1 test). */
    static double[] slopesAt(List<BezierBaker.Node> l, int i) {
        double t = l.get(i).t;
        return new double[]{ BezierBaker.slopeAt(l, t - 1e-7),
                             BezierBaker.slopeAt(l, t + 1e-7) };
    }

    public static void main(String[] args) {
        // ================= (1) nudges =================
        check(Gestures.step(0.25, 0.1, 1, false) == 0.25 + 0.1,
            "1a plain step = v + q bitwise");
        check(Gestures.step(0.25, 0.1, -1, false) == 0.25 - 0.1,
            "1a plain step down = v − q bitwise");
        check(near(Gestures.step(0.25, 0.1, 1, true), 0.3, 1e-12),
            "1b Ctrl from an OFF-grid value lands on the NEXT cell (0.25 → 0.3)");
        check(near(Gestures.step(0.25, 0.1, -1, true), 0.2, 1e-12),
            "1b Ctrl down from off-grid (0.25 → 0.2)");
        check(near(Gestures.step(0.3, 0.1, 1, true), 0.4, 1e-12),
            "1c Ctrl from an ON-grid value moves ONE cell (0.3 → 0.4)");
        check(near(Gestures.step(0.3, 0.1, -1, true), 0.2, 1e-12),
            "1c Ctrl down on-grid (0.3 → 0.2)");
        check(near(Gestures.step(0.1 + 0.2, 0.1, 1, true), 0.4, 1e-12),
            "1d float dust (0.1+0.2) still lands one cell over, dust killed",
            String.valueOf(Gestures.step(0.1 + 0.2, 0.1, 1, true)));
        check(near(Gestures.nudge(0.25, 0, 0.1, 1, true, false), 0.26, 1e-12),
            "1e Shift = a tenth (0.25 → 0.26)");
        check(near(Gestures.nudge(0.253, 0, 0.1, 1, true, true), 0.26, 1e-12)
            && near(Gestures.nudge(0.253, 0, 0.1, -1, true, true), 0.25, 1e-12),
            "1f Shift+Ctrl = the tenth-scaled snap (0.253 → 0.26 / 0.25)");
        // never skips a cell, always lands on the grid
        boolean okGrid = true;
        Random r = new Random(248);
        for (int i = 0; i < 2000; i++) {
            double v = r.nextDouble() * 10;
            double q = new double[]{ 0.1, 0.01, 5, 1 }[i % 4];
            for (int dir : new int[]{ 1, -1 }) {
                double s = Gestures.step(v, q, dir, true);
                double moved = dir * (s - v);
                if (moved <= 0 || moved > q + 1e-9) okGrid = false;
                double k = s / q;
                if (Math.abs(k - Math.rint(k)) > 1e-6) okGrid = false;
            }
        }
        check(okGrid, "1g 2000 random Ctrl snaps: 0 < move ≤ q, lands on the grid");
        check(Gestures.step(1.234, 0.1, 0, true) == 1.234
            && Gestures.nudge(1.234, 0.5, 0.1, 0, true, true) == 1.234,
            "1h dir 0 = untouched (the idle axis never moves)");
        // references
        double tOff = 3.27;
        double a = Gestures.nudge(0.05, -tOff, 0.1, 1, false, true);
        check(near(tOff + a, 3.4, 1e-12), "1i absolute time grid via ref = −tOff (3.32 → 3.4)",
            String.valueOf(tOff + a));
        double aRel = Gestures.nudge(1.30, 1.234, 0.1, 1, false, true);
        check(near(aRel, 1.334, 1e-12), "1j relative time grid (prev + k·q): 1.30 → 1.334");
        check(near(Gestures.nudge(1.234, 0, 0.1, 1, false, true), 1.3, 1e-12),
            "1k absolute g grid: 1.234 → 1.3");
        check(near(Gestures.nudge(1.234, 1.0, 0.1, 1, false, true), 1.3, 1e-12)
            && near(Gestures.nudge(1.26, 1.0, 0.1, 1, false, true), 1.3, 1e-12),
            "1l relative value grid (prev 1.0): 1.234 → 1.3, 1.26 → 1.3");
        check(near(Gestures.nudge(12, 0, 5, 1, false, true), 15, 1e-12)
            && near(Gestures.nudge(15, 0, 5, 1, false, true), 20, 1e-12),
            "1m roll °/s step 5: 12 → 15, 15 → 20");
        check(near(Gestures.nudge(2.7, 0, 1, 1, false, true), 3, 1e-12)
            && near(Gestures.nudge(2.7, 0, 1, 1, true, true), 2.8, 1e-12)
            && near(Gestures.nudge(2.7, 0, 1, 1, true, false), 2.8, 1e-12),
            "1n °/m lane step 1: Ctrl 2.7 → 3, Shift+Ctrl → 2.8, Shift → 2.8");
        check(near(Gestures.nudge(0.123, 0, 0.01, -1, false, true), 0.12, 1e-12),
            "1o time step 0.01 s down: 0.123 → 0.12");

        // ================= (2) append =================
        {
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {1, 1.5}, {2, 1.2} });
            BezierBaker.Node last = l.get(2);
            last.inDv = -0.3;   // in slope 0.9 per s (inDt = −1/3)
            double inSlope = last.inDv / BezierBaker.effInDt(l.get(1), last);
            BezierBaker.Node np = new BezierBaker.Node(3, last.val);
            boolean carried = Gestures.continueOut(l.get(1), last, np, SINE);
            np.inFrac = SINE; np.inDv = 0;
            l.add(np);
            double[] s = slopesAt(l, 2);
            check(carried && last.outFrac == SINE
                && near(last.outDv, inSlope * SINE * 1.0, 1e-12),
                "2a smooth sloped last node: out = in slope × outDt, sine frac",
                "outDv=" + last.outDv);
            check(near(s[0], inSlope, 2e-3) && near(s[1], inSlope, 2e-3),
                "2a C1 through the joint (slope left ≈ right ≈ 0.9)",
                s[0] + " / " + s[1]);
            check(np.inDv == 0 && np.inFrac == SINE
                && BezierBaker.exactAt(l, 3) == 1.2,
                "2a the new node holds the value with a flat sine in handle");
        }
        {
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {1, 1.5}, {2, 1.2} });
            BezierBaker.Node last = l.get(2);
            last.inDv = -0.3;
            last.smooth = false;
            BezierBaker.Node np = new BezierBaker.Node(3, last.val);
            boolean carried = Gestures.continueOut(l.get(1), last, np, SINE);
            check(!carried && last.outDv == 0.0 && last.outFrac == SINE,
                "2b BROKEN last node: historic flat out bitwise");
        }
        {
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {1, 1.5}, {2, 1.2} });
            BezierBaker.Node last = l.get(2);
            last.inDv = -0.3;
            last.straightOut = true;
            BezierBaker.Node np = new BezierBaker.Node(3, last.val);
            boolean c1 = Gestures.continueOut(l.get(1), last, np, SINE);
            last.straightOut = false; last.levelOut = true;
            boolean c2 = Gestures.continueOut(l.get(1), last, np, SINE);
            check(!c1 && !c2 && last.outDv == 0.0,
                "2c straight-/level-LOCKED last node: carry skipped (flat)");
        }
        {
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1} });
            BezierBaker.Node last = l.get(0);
            last.inDv = -0.3;
            BezierBaker.Node np = new BezierBaker.Node(1, last.val);
            boolean carried = Gestures.continueOut(null, last, np, SINE);
            check(!carried && last.outDv == 0.0,
                "2d 1-node lane (split hold half): flat out");
        }
        {
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {1, 1.5}, {2, 1.2} });
            BezierBaker.Node last = l.get(2);
            BezierBaker.Node np = new BezierBaker.Node(3, last.val);
            boolean carried = Gestures.continueOut(l.get(1), last, np, SINE);
            check(!carried && last.outDv == 0.0 && last.outFrac == SINE,
                "2e flat in handle: outDv exactly 0.0 (bitwise the flat recipe)");
        }
        {
            // distance domain: meters, the 10 m append step
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {20, 1.6}, {40, 1.3} });
            BezierBaker.Node last = l.get(2);
            last.inDv = -0.25;
            double inSlope = last.inDv / BezierBaker.effInDt(l.get(1), last);
            BezierBaker.Node np = new BezierBaker.Node(50, last.val);
            Gestures.continueOut(l.get(1), last, np, SINE);
            np.inFrac = SINE; np.inDv = 0;
            l.add(np);
            double[] s = slopesAt(l, 2);
            check(near(s[0], inSlope, 1e-4) && near(s[1], inSlope, 1e-4)
                && BezierBaker.exactAt(l, 50) == 1.3,
                "2f distance-domain append: C1 in per-meter slope, hold at 50 m",
                s[0] + " / " + s[1]);
        }
        {
            // insertHoldAfterSelected recipe: sel = idx 2 of a 4-node
            // lane, both handles sloped; next shifts by the step
            List<BezierBaker.Node> l = lane(new double[][]{
                {0, 1}, {1, 1.5}, {2, 1.2}, {3, 1.0} });
            BezierBaker.Node sel = l.get(2), next = l.get(3);
            sel.inDv = -0.3;
            sel.outDv = 0.4;                // out slope 1.2 per s
            next.inDv = -0.1;
            double selInSlope = sel.inDv / BezierBaker.effInDt(l.get(1), sel);
            double oldOutSlope = sel.outDv / BezierBaker.effOutDt(sel, next);
            next.t += 1;                    // the step shift
            BezierBaker.Node hold = new BezierBaker.Node(sel.t + 1, sel.val);
            hold.outFrac = sel.outFrac; hold.outDv = sel.outDv;
            Gestures.continueOut(l.get(1), sel, hold, SINE);
            Gestures.matchInToOut(sel, hold, next, SINE);
            l.add(3, hold);
            double[] sSel = slopesAt(l, 2), sHold = slopesAt(l, 3);
            check(near(sSel[0], selInSlope, 2e-3) && near(sSel[1], selInSlope, 2e-3),
                "2g insert-hold: the selected node stays C1 (out continues in)",
                sSel[0] + " / " + sSel[1]);
            check(near(sHold[0], oldOutSlope, 2e-3) && near(sHold[1], oldOutSlope, 2e-3),
                "2g insert-hold: the hold node is C1 (in re-angled to its inherited out)",
                sHold[0] + " / " + sHold[1]);
            check(near(BezierBaker.exactAt(l, 3), 1.2, 1e-9) && hold.inFrac == SINE
                && next.t == 4,
                "2g insert-hold: hold value kept, sine in frac, next shifted");
        }
        {
            // sel is the LAST node: no next → flat in on the hold
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {1, 1.5}, {2, 1.2} });
            BezierBaker.Node sel = l.get(2);
            sel.outDv = 0.4;   // stale (no segment)
            BezierBaker.Node hold = new BezierBaker.Node(3, sel.val);
            hold.outFrac = sel.outFrac; hold.outDv = sel.outDv;
            Gestures.matchInToOut(sel, hold, null, SINE);
            check(hold.inDv == 0 && hold.inFrac == SINE,
                "2h insert-hold after the LAST node: flat in (nothing to slope toward)");
        }
        {
            // sel is node 0: no prev → flat out
            List<BezierBaker.Node> l = lane(new double[][]{ {0, 1}, {1, 1.5} });
            BezierBaker.Node sel = l.get(0);
            sel.outDv = 0.4;
            BezierBaker.Node hold = new BezierBaker.Node(1, sel.val);
            hold.outFrac = sel.outFrac; hold.outDv = sel.outDv;
            boolean carried = Gestures.continueOut(null, sel, hold, SINE);
            check(!carried && sel.outDv == 0.0 && sel.outFrac == SINE,
                "2i insert-hold after node 0: flat out (no in handle)");
        }
        {
            // broken sel: out flat; the hold (a fresh smooth node) re-angles
            List<BezierBaker.Node> l = lane(new double[][]{
                {0, 1}, {1, 1.5}, {2, 1.2}, {3, 1.0} });
            BezierBaker.Node sel = l.get(2), next = l.get(3);
            sel.inDv = -0.3; sel.outDv = 0.4; sel.smooth = false;
            next.t += 1;
            BezierBaker.Node hold = new BezierBaker.Node(3, sel.val);
            hold.outFrac = sel.outFrac; hold.outDv = sel.outDv;
            boolean carried = Gestures.continueOut(l.get(1), sel, hold, SINE);
            Gestures.matchInToOut(sel, hold, next, SINE);
            check(!carried && sel.outDv == 0.0 && hold.inDv != 0,
                "2j broken selection: its out stays flat, the hold node is still re-angled");
        }

        // ================= (4) start diamond =================
        {
            SpeedControl c = new SpeedControl(2, 3);
            List<SpeedControl> ctls = new ArrayList<>(List.of(c));
            double x0 = Gestures.resizeStart(c, 3.0, ctls, 0.02);
            check(x0 == 3.0 && c.x0 == 3.0 && c.x0 + c.len == 5.0,
                "4a start resize: x0 → 3, end stays 5", "len=" + c.len);
            Gestures.resizeStart(c, 10, ctls, 0.02);
            check(near(c.x0, 4.98, 1e-12) && near(c.len, 0.02, 1e-12),
                "4b past the end: clamps at end − minLen");
            Gestures.resizeStart(c, -4, ctls, 0.02);
            check(c.x0 == 0 && c.x0 + c.len == 5.0, "4c before 0: clamps at 0, end kept");
        }
        {
            SpeedControl o = new SpeedControl(0, 1.5);
            SpeedControl c = new SpeedControl(2, 3);
            SpeedControl later = new SpeedControl(7, 1);
            List<SpeedControl> ctls = new ArrayList<>(List.of(o, c, later));
            Gestures.resizeStart(c, 1.0, ctls, 0.02);
            check(c.x0 == 1.5 && c.x0 + c.len == 5.0,
                "4d previous control's end is the floor (1.5)");
            check(o.x0 == 0 && o.len == 1.5 && later.x0 == 7 && later.len == 1,
                "4e other controls untouched (no ripple: the end is fixed)");
            Gestures.resizeStart(c, 4.0, ctls, 0.2);   // distance minLen
            check(c.x0 == 4.0 && near(c.len, 1.0, 1e-12), "4f distance minLen 0.2 admits x0 = 4");
            Gestures.resizeStart(c, 4.95, ctls, 0.2);
            check(near(c.x0, 4.8, 1e-12), "4g distance minLen 0.2 clamps at 4.8");
        }
        {
            // complex: both graphs rescale exactly like the end-diamond stretch
            SpeedControl c = new SpeedControl(2, 3);
            c.complex = true;
            c.lane = lane(new double[][]{ {0, 10}, {1.5, 12}, {3, 11} });
            c.lane.get(1).outDv = 0.3;
            c.lane.get(1).inDv = -0.2;
            c.decelLane = lane(new double[][]{ {0, 0.5}, {3, 0.6} });
            c.decelLane.get(0).outDv = 0.05;
            SpeedControl ref = new SpeedControl(2, 3);
            ref.complex = true;
            ref.lane = copyLane(c.lane);
            ref.decelLane = copyLane(c.decelLane);
            Gestures.resizeStart(c, 3.0, new ArrayList<>(List.of(c)), 0.02);
            ref.len = 2;                    // the end-diamond path's stretch
            ref.rescaleLanes(2.0 / 3.0);
            check(sameLane(c.lane, ref.lane) && sameLane(c.decelLane, ref.decelLane),
                "4h complex start resize == the end-diamond rescale (both graphs, bitwise)");
            check(near(c.lane.get(1).t, 1.0, 1e-12) && near(c.lane.get(2).t, 2.0, 1e-12),
                "4h the 50% node stays at 50% (fraction semantics), last node at len");
            check(near(c.lane.get(1).outDv, 0.3 * 2.0 / 3.0, 1e-12),
                "4h handle dv rescaled with the run (slopes kept, D105)");
        }
        {
            SpeedControl c = new SpeedControl(2, 3);
            c.lane = lane(new double[][]{ {0, 10}, {3, 11} });   // not complex
            List<BezierBaker.Node> before = copyLane(c.lane);
            Gestures.resizeStart(c, 3.0, new ArrayList<>(List.of(c)), 0.02);
            check(sameLane(c.lane, before), "4i a constant control's dormant graph is untouched");
        }

        // ================= (5) band edges =================
        check(Gestures.edgeWindowLen(12, 10, 1) == 4 && Gestures.edgeWindowLen(8, 10, -1) == 4,
            "5a edge on its own side: len = 2·arc");
        check(Gestures.edgeWindowLen(8, 10, 1) == 0 && Gestures.edgeWindowLen(13, 10, -1) == 0
            && Gestures.edgeWindowLen(10, 10, 1) == 0,
            "5b edge across the seam: 0 and stays 0 however far");
        check(near(Gestures.edgeWindowLen(10.2, 10, 1), 0.4, 1e-12)
            && near(Gestures.edgeWindowLen(9.9, 10, -1), 0.2, 1e-12),
            "5c dragging back re-grows continuously from 0");
        check(Gestures.edgeWindowLen(8, 10, 0) == 4 && Gestures.edgeWindowLen(12, 10, 0) == 4,
            "5d side 0 = the symmetric historic form (s/c interior diamond)");

        // ================= (6) init speed =================
        check(Gestures.INIT_SPEED_MIN == 0.1 && Gestures.INIT_SPEED_MAX == 1e4,
            "6a the D198 band [0.1, 1e4]");
        check(Gestures.clampInitSpeed(0.05) == 0.1 && Gestures.clampInitSpeed(5e4) == 1e4
            && Gestures.clampInitSpeed(12.34) == 12.34 && Gestures.clampInitSpeed(0.1) == 0.1,
            "6b clampInitSpeed: below → 0.1, above → 1e4, inside untouched");
        // batch 37: the dialog's speed-conflict test + the grey readout
        check(Gestures.INIT_SPEED_SHOW_EPS == 0.005
            && Gestures.INIT_SPEED_REAL_FLOOR == 0.5
            && !Gestures.speedDiffers(12.34, 12.3449)
            && Gestures.speedDiffers(12.34, 12.3451)
            && Gestures.speedDiffers(12.3451, 12.34)
            && !Gestures.speedDiffers(Double.NaN, 12.34)
            && !Gestures.speedDiffers(12.34, Double.NaN)
            && !Gestures.speedDiffers(12.34, 12.34),
            "6c speedDiffers: more than half the %.2f quantum (0.005) apart, either way; NaN on either side = no conflict");
        {
            Locale was = Locale.getDefault();
            Locale.setDefault(Locale.GERMANY);   // a comma-decimal system
            String de = Gestures.realSpeedText(12.345678);
            Locale.setDefault(was);
            check(Gestures.realSpeedText(12.345678).equals("real 12.35")
                && Gestures.realSpeedText(-1e-9).equals("real 0.00")
                && Gestures.realSpeedText(-0.004).equals("real -0.00")
                && Gestures.realSpeedText(Double.NaN).equals("real —")
                && Gestures.realSpeedText(1e4).equals("real 10000.00")
                && Gestures.realSpeedText(0).equals("real 0.00")
                && de.equals("real 12.35"),
                "6d realSpeedText: \"real 12.35\"; dust under 1e-6 → 0.00, larger dust keeps its sign (z3); NaN → \"real —\"; a decimal POINT under a comma-decimal default Locale",
                Gestures.realSpeedText(-0.004) + " / " + de);
        }

        // ================= (7) viewport DISKS (item S1) =================
        // A 3D disk hands the strip's own write path a native x read off
        // the track, so "disk write == strip write" is one function —
        // the pins are (a) the END clamp factored out of the strip
        // (Gestures.resizeEndLen) under every wall, bitwise the historic
        // inline arithmetic, (b) the native↔arc round trip that feeds
        // the disk (ViewDisks), (c) the two paths landing on the same
        // model under every clamp on a REAL compile, (d) the drag's
        // polyline mapping and (e) the boundary-disk sample set.
        {
            SpeedControl c = new SpeedControl(2, 3);        // [2, 5]
            SpeedControl nxt = new SpeedControl(6, 1);      // [6, 7]
            List<SpeedControl> ctls = new ArrayList<>(List.of(c, nxt));
            check(Gestures.resizeEndLen(c, 4.0, ctls, 0.02, 10, false) == 2.0,
                "7a end → 4: len 2 (free)");
            check(Gestures.resizeEndLen(c, 8.0, ctls, 0.02, 10, false) == 4.0,
                "7a the next control walls the end at 6 (len 4)");
            check(Gestures.resizeEndLen(c, 8.0, ctls, 0.02, 10, true) == 6.0,
                "7a ripple-time: the next control drops out of the clamp (len 6)");
            check(Gestures.resizeEndLen(c, 12, ctls, 0.02, 10, true) == 8.0,
                "7a the section end walls (len 8)");
            check(Gestures.resizeEndLen(c, 2.001, ctls, 0.02, 10, false) == 0.02,
                "7a under minLen → minLen");
            check(Gestures.resizeEndLen(c, 12, ctls, 0.02, 2.01, false) == 0.02,
                "7a section shorter than minLen past x0: minLen (the historic lo-wins clamp)");
            check(Gestures.resizeEndLen(c, 1.0, ctls, 0.2, 10, false) == 0.2,
                "7a distance minLen 0.2 floors a cursor left of x0");
            // bitwise the strip's historic inline arithmetic (v18.0 spDragDiamondTo)
            boolean okBits = true;
            Random r7 = new Random(2481);
            for (int i = 0; i < 500; i++) {
                double x0 = r7.nextDouble() * 5, len = 0.1 + r7.nextDouble() * 3;
                SpeedControl cc = new SpeedControl(x0, len);
                SpeedControl o1 = new SpeedControl(x0 + len + r7.nextDouble() * 3, 0.5);
                SpeedControl o2 = new SpeedControl(Math.max(0, x0 - 1 - r7.nextDouble()), 0.5);
                List<SpeedControl> cs = new ArrayList<>(List.of(o2, cc, o1));
                double end = x0 + len + r7.nextDouble() * 6;
                double nx = r7.nextDouble() * 12 - 1;
                double minLen = r7.nextBoolean() ? 0.02 : 0.2;
                boolean ripple = r7.nextBoolean();
                double hi = end - cc.x0;
                for (SpeedControl o : cs) {
                    if (o == cc) continue;
                    if (o.x0 >= cc.x0 + cc.len - 1e-9 && !ripple) hi = Math.min(hi, o.x0 - cc.x0);
                }
                double old = Math.max(minLen, Math.min(hi, nx - cc.x0));   // FvdDesigner.clamp
                if (old != Gestures.resizeEndLen(cc, nx, cs, minLen, end, ripple)) okBits = false;
            }
            check(okBits, "7b 500 random end clamps == the historic inline arithmetic bitwise");
        }
        {
            // a REAL compile: two TIME sections + a DISTANCE section
            Coaster co = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection fa = new ForceSection(hillLanes(4, 1.2), 26);
            p.sections.add(fa);
            ForceSection fb = new ForceSection(hillLanes(3, 0.8), 24);
            p.sections.add(fb);
            ForceSection fd = new ForceSection(hillLanes(60, 0.5), 22);
            fd.domain(Section.Domain.DISTANCE);
            p.sections.add(fd);
            co.pieces.add(p);
            TrackCompiler.Result res = TrackCompiler.compile(co, 0.01);
            List<FVDIntegrator.Sample> smp = res.samples();
            List<TrackCompiler.Span> spans = res.spans();
            check(spans.size() == 3 && smp.size() > 100, "7c fixture compiles (3 spans)",
                spans.size() + " spans, " + smp.size() + " samples");
            // (b) native ↔ arc round trip on every section, both domains
            boolean okRt = true;
            double worst = 0;
            for (int k = 0; k < 3; k++) {
                TrackCompiler.Span sp = spans.get(k);
                boolean nd = k == 2;
                double endN = nd ? sp.sLen() : sp.t1() - sp.t0();
                for (double x : new double[]{ 0, 0.37, 1.234, endN * 0.5, endN }) {
                    double arc = ViewDisks.arcAtNative(smp, sp, nd, x);
                    double back = ViewDisks.nativeAtArc(smp, sp, nd, arc);
                    worst = Math.max(worst, Math.abs(back - x));
                    if (Math.abs(back - x) > 1e-9) okRt = false;
                }
            }
            check(okRt, "7c native → arc → native round trip ≤ 1e-9 on TIME and DISTANCE spans",
                "worst " + worst);
            // arcAtNative == the plates' historic arcAtTime (TIME) and s0 + x (DISTANCE)
            TrackCompiler.Span s1 = spans.get(1), s2 = spans.get(2);
            check(ViewDisks.arcAtNative(smp, s2, true, 12.5) == s2.s0() + 12.5,
                "7c DISTANCE: arc = s0 + x bitwise");
            {
                double lt = 1.234, t = s1.t0() + lt;
                int lo = s1.i0(), hi = Math.min(s1.i1(), smp.size() - 1);
                while (hi - lo > 1) { int mid = (lo + hi) >>> 1;
                    if (smp.get(mid).t() <= t) lo = mid; else hi = mid; }
                double u = (t - smp.get(lo).t()) / Math.max(1e-12, smp.get(hi).t() - smp.get(lo).t());
                double hist = smp.get(lo).s() + u * (smp.get(hi).s() - smp.get(lo).s());
                check(ViewDisks.arcAtNative(smp, s1, false, lt) == hist,
                    "7c TIME: arc == the plates' historic arcAtTime lerp bitwise");
            }
            // the SEAM rule (spanLo): a continuation's first sample sits
            // one dt past its origin (the D84 drop) — x = 0 maps onto
            // the seam sample (= the previous span's exit), reads back
            // as exactly 0, and the bracket never reaches into a
            // detached piece (span 0 starts at 0)
            check(ViewDisks.spanLo(smp, s1) == spans.get(0).i1()
                && smp.get(s1.i0()).t() > s1.t0()
                && ViewDisks.spanLo(smp, spans.get(0)) == 0,
                "7c spanLo: the continuation's bracket starts at the seam sample; the head's at 0",
                ViewDisks.spanLo(smp, s1) + " vs exit " + spans.get(0).i1());
            check(ViewDisks.arcAtNative(smp, s1, false, 0) == smp.get(spans.get(0).i1()).s()
                && ViewDisks.nativeAtArc(smp, s1, false, smp.get(spans.get(0).i1()).s()) == 0.0,
                "7c x = 0 on a continuation lands ON the seam sample and reads back exactly 0");
            check(ViewDisks.arcAtNative(smp, s1, false, -5) == smp.get(spans.get(0).i1()).s()
                && ViewDisks.arcAtNative(smp, s1, false, 1e9) == smp.get(Math.min(s1.i1(), smp.size() - 1)).s(),
                "7c outside the span clamps to its seam/last sample");
            // (c) DISK write == STRIP write under every clamp, end-to-end:
            // the strip clamps a native x; the disk clamps the same x
            // after its track round trip — same model within lerp dust,
            // BITWISE wherever a wall wins (walls are exact values)
            for (int k = 0; k < 3; k++) {
                TrackCompiler.Span sp = spans.get(k);
                boolean nd = k == 2;
                double endN = nd ? sp.sLen() : sp.t1() - sp.t0();
                double minLen = nd ? 0.2 : 0.02;
                double x0 = endN * 0.3, len = endN * 0.2;
                double[] targets = { x0 + len * 0.5, x0 + len * 1.7, endN + 3, x0 + minLen * 0.3,
                                     x0 - 1, endN * 0.9, 0 };
                boolean okEnd = true, okStart = true, wallsBitwise = true;
                for (double tx : targets) {
                    // END disk vs end diamond
                    SpeedControl cs = new SpeedControl(x0, len), cd = new SpeedControl(x0, len);
                    SpeedControl nb = new SpeedControl(endN * 0.8, endN * 0.1);
                    List<SpeedControl> ls = new ArrayList<>(List.of(cs, new SpeedControl(nb.x0, nb.len)));
                    List<SpeedControl> ld = new ArrayList<>(List.of(cd, new SpeedControl(nb.x0, nb.len)));
                    double xd = ViewDisks.nativeAtArc(smp, sp, nd, ViewDisks.arcAtNative(smp, sp, nd, tx));
                    double lenS = Gestures.resizeEndLen(cs, tx, ls, minLen, endN, false);
                    double lenD = Gestures.resizeEndLen(cd, xd, ld, minLen, endN, false);
                    if (Math.abs(lenS - lenD) > 1e-9) okEnd = false;
                    boolean wall = lenS == minLen || lenS == nb.x0 - x0 || lenS == endN - x0;
                    if (wall && lenS != lenD) wallsBitwise = false;
                    // START disk vs start diamond
                    SpeedControl ss = new SpeedControl(x0, len), sd = new SpeedControl(x0, len);
                    SpeedControl pv = new SpeedControl(0, endN * 0.1);
                    Gestures.resizeStart(ss, tx, new ArrayList<>(List.of(pv, ss)), minLen);
                    Gestures.resizeStart(sd, xd, new ArrayList<>(List.of(new SpeedControl(0, endN * 0.1), sd)), minLen);
                    if (Math.abs(ss.x0 - sd.x0) > 1e-9 || Math.abs(ss.len - sd.len) > 1e-9) okStart = false;
                    boolean wallS = ss.x0 == pv.x0 + pv.len || ss.x0 == x0 + len - minLen;
                    if (wallS && (ss.x0 != sd.x0 || ss.len != sd.len)) wallsBitwise = false;
                }
                check(okEnd, "7d span " + k + (nd ? " (DISTANCE)" : " (TIME)")
                    + ": END disk write == end diamond write under every clamp (≤ 1e-9)");
                check(okStart, "7d span " + k + ": START disk write == start diamond write (≤ 1e-9)");
                check(wallsBitwise, "7d span " + k + ": every clamped write lands BITWISE on the wall");
            }
            // (e) boundary disks — PLAIN interior boundaries only since
            // D249 (item U6): every section's EXIT except the last; the
            // piece's entry and its last exit (run head/end, strict seam,
            // bridge exit — no line or a double line in the graph) get
            // none; never the next section's entry (one dt after the exit)
            List<Integer> bs = ViewDisks.boundarySamples(smp, spans, 0, 3);
            check(bs.size() == 2 && bs.get(0) == spans.get(0).i1()
                && bs.get(1) == spans.get(1).i1(),
                "7e boundary disks = the 2 interior exits of a 3-section piece, in order",
                String.valueOf(bs));
            check(!bs.contains(spans.get(0).i0())
                && !bs.contains(Math.min(spans.get(2).i1(), smp.size() - 1)),
                "7e the piece ENTRY and its LAST exit get NO disk (D249)");
            check(!bs.contains(spans.get(1).i0()) && spans.get(1).i0() == spans.get(0).i1() + 1,
                "7e the continuation's entry sample (exit + 1 dt) gets NO disk");
            check(ViewDisks.boundarySamples(smp, spans, 1, 2).size() == 1
                && ViewDisks.boundarySamples(smp, spans, 1, 2).get(0) == spans.get(1).i1()
                && ViewDisks.boundarySamples(smp, spans, 0, 1).isEmpty()
                && ViewDisks.boundarySamples(smp, spans, 0, 0).isEmpty()
                && ViewDisks.boundarySamples(smp, spans, 2, 5).isEmpty(),
                "7e sub-range (1 disk) / one-section piece (none) / empty / out-of-range spans");
        }
        {
            // (e) dedupe by index AND by position on a synthetic sample list
            List<FVDIntegrator.Sample> smp = new ArrayList<>();
            Vec3 T = new Vec3(1, 0, 0), U = new Vec3(0, 1, 0), R = new Vec3(0, 0, 1);
            for (int i = 0; i < 10; i++) {
                double x = i < 5 ? i : i - 1 + 5e-7;   // sample 5 sits ON sample 4 (a follower's i0 == the upstream i1)
                smp.add(new FVDIntegrator.Sample(i * 0.01, x, new Vec3(x, 0, 0), T, U, R, 10, 1, 0));
            }
            TrackPose pose = null;
            List<TrackCompiler.Span> sp = new ArrayList<>();
            sp.add(new TrackCompiler.Span(0, 4, 0, 0.04, 0, 4, pose));
            sp.add(new TrackCompiler.Span(5, 9, 0.05, 0.09, 4, 5, pose));
            List<Integer> bs = ViewDisks.boundarySamples(smp, sp, 0, 2);
            check(bs.equals(List.of(4)), "7e two sections: the one interior exit 4 (entry 0 / last exit 9 excluded, D249)",
                String.valueOf(bs));
            List<Integer> bs2 = ViewDisks.boundarySamples(smp, sp, 1, 1);
            check(bs2.isEmpty(), "7e piece 2 alone (one section): no disk", String.valueOf(bs2));
            List<TrackCompiler.Span> sp3 = new ArrayList<>();
            sp3.add(new TrackCompiler.Span(0, 4, 0, 0.04, 0, 4, pose));
            sp3.add(new TrackCompiler.Span(4, 4, 0.04, 0.04, 4, 4, pose));   // a zero-length twin: exit INDEX 4 again
            sp3.add(new TrackCompiler.Span(4, 9, 0.04, 0.09, 4, 5, pose));
            check(ViewDisks.boundarySamples(smp, sp3, 0, 3).equals(List.of(4)),
                "7e a repeated interior exit INDEX dedupes (the last exit 9 excluded)");
            List<TrackCompiler.Span> sp4 = new ArrayList<>();
            sp4.add(new TrackCompiler.Span(0, 4, 0, 0.04, 0, 4, pose));
            sp4.add(new TrackCompiler.Span(5, 9, 0.05, 0.09, 4, 5, pose));
            sp4.add(new TrackCompiler.Span(9, 40, 0.09, 0.4, 9, 30, pose));   // a bridge-like tail
            check(ViewDisks.boundarySamples(smp, sp4, 0, 3).equals(List.of(4, 9)),
                "7e a piece ending in a bridge: disks at both interior exits incl. the bridge ENTRY, none at its exit");
            List<TrackCompiler.Span> sp5 = new ArrayList<>();
            sp5.add(new TrackCompiler.Span(0, 4, 0, 0.04, 0, 4, pose));
            sp5.add(new TrackCompiler.Span(5, 40, 0.05, 0.4, 4, 30, pose));   // interior i1 past the list
            sp5.add(new TrackCompiler.Span(40, 50, 0.4, 0.5, 30, 40, pose));
            check(ViewDisks.boundarySamples(smp, sp5, 0, 3).equals(List.of(4, 9)),
                "7e an interior i1 past the sample list clamps to the last sample");
            List<TrackCompiler.Span> sp6 = new ArrayList<>();
            sp6.add(new TrackCompiler.Span(0, 4, 0, 0.04, 0, 4, pose));
            sp6.add(new TrackCompiler.Span(5, 5, 0.05, 0.05, 4, 4, pose));   // exit 5 sits ON sample 4 (position dedupe)
            sp6.add(new TrackCompiler.Span(6, 9, 0.06, 0.09, 4, 5, pose));
            check(ViewDisks.boundarySamples(smp, sp6, 0, 3).equals(List.of(4)),
                "7e a coincident interior exit POSITION dedupes");
        }
        {
            // (8) gizmo pick metric (D249, item U4): a center ball measures
            // from its projected silhouette EDGE — inside = 0 px (wins the
            // nearest-by-px FALLBACK; since batch 37 depth decides inside
            // the silhouette, section 12), outside = d − rPx; the edge
            // band keeps the D248 zone's OUTER reach rPx·1.5 + 6
            check(Gestures.ballEdgeDist(3, 5) == 0 && Gestures.ballEdgeDist(5, 5) == 0
                && Gestures.ballEdgeDist(8, 5) == 3 && Gestures.ballEdgeDist(0, 0) == 0,
                "8a ball edge distance: inside/on the silhouette 0, outside d − rPx");
            check(Gestures.ballEdgeBand(10, 1.5, 6) == 11
                && Gestures.ballEdgeBand(10, 1.5, 6) + 10 == 10 * 1.5 + 6
                && Gestures.ballEdgeBand(2, 1.5, 6) == 7,
                "8b edge band rPx·(mul−1)+pad == the D248 center zone minus the radius (tiny rigs stay grabbable)");
            // the FALLBACK contest (nothing directly under the cursor): a
            // press 1 px inside a 20 px ball reads 0 px; the same press 3 px
            // OUTSIDE the silhouette with a ring at 1 px → the ring, the
            // ball still in band. Inside the silhouette a ring DRAWN over
            // the ball is decided by depth (batch 37, section 12)
            double ballIn = Gestures.ballEdgeDist(19, 20), ballOut = Gestures.ballEdgeDist(23, 20);
            check(ballIn < 1 && ballOut > 1 && ballOut <= Gestures.ballEdgeBand(20, 1.5, 6),
                "8c with nothing directly under the cursor the ball competes from its edge: 3 px outside the silhouette a 1 px ring wins while the ball stays in band (inside the silhouette depth decides, section 12)");
        }
        {
            // (d) the drag's polyline mapping
            double[] xs = { 0, 10, 20, 30 }, ys = { 0, 0, 0, 10 };
            double[] n0 = ViewDisks.nearestOnPolyline(xs, ys, 10, 3);
            check(n0 != null && (n0[0] == 0 && n0[1] == 1 || n0[0] == 1 && n0[1] == 0) && near(n0[2], 9, 1e-12),
                "7f a point above vertex 1: segment end u = 1 (or u = 0 of the next), d² 9",
                n0[0] + " u=" + n0[1]);
            double[] n1 = ViewDisks.nearestOnPolyline(xs, ys, 15, -4);
            check(n1 != null && n1[0] == 1 && near(n1[1], 0.5, 1e-12) && near(n1[2], 16, 1e-12),
                "7f mid-segment: k 1, u 0.5");
            double[] n2 = ViewDisks.nearestOnPolyline(xs, ys, -50, 2);
            check(n2 != null && n2[0] == 0 && n2[1] == 0, "7f far left of the start clamps to k 0, u 0 (no radius cap)");
            double[] n3 = ViewDisks.nearestOnPolyline(xs, ys, 90, 90);
            check(n3 != null && n3[0] == 2 && n3[1] == 1, "7f far past the end clamps to the last segment's u 1");
            double[] xn = { 0, Double.NaN, 20, 30 }, yn = { 0, Double.NaN, 0, 10 };
            double[] n4 = ViewDisks.nearestOnPolyline(xn, yn, 10, 1);
            check(n4 != null && (n4[0] == 0 && n4[1] == 0 && near(n4[2], 101, 1e-12) || n4[0] == 2 && n4[1] == 0),
                "7f a behind-camera (NaN) vertex skips both its segments", n4[0] + " u=" + n4[1] + " d2=" + n4[2]);
            check(ViewDisks.nearestOnPolyline(new double[]{ Double.NaN }, new double[]{ Double.NaN }, 0, 0) == null,
                "7f nothing projected → null (the control stays put)");
            double[] n5 = ViewDisks.nearestOnPolyline(new double[]{ 3 }, new double[]{ 4 }, 0, 0);
            check(n5 != null && n5[0] == 0 && n5[1] == 0 && near(n5[2], 25, 1e-12), "7f a single vertex: k 0, u 0");
            // the strided drag polyline ALWAYS ends on the span's last
            // sample (the END wall stays reachable on long sections)
            int[] s1 = ViewDisks.strideIndices(10, 17, 2);
            check(java.util.Arrays.equals(s1, new int[]{ 10, 12, 14, 16, 17 }),
                "7f strideIndices: odd span under stride 2 appends hi as the last vertex", java.util.Arrays.toString(s1));
            int[] s2 = ViewDisks.strideIndices(10, 16, 2);
            check(java.util.Arrays.equals(s2, new int[]{ 10, 12, 14, 16 }),
                "7f strideIndices: even span lands on hi without a duplicate", java.util.Arrays.toString(s2));
            check(java.util.Arrays.equals(ViewDisks.strideIndices(5, 5, 2), new int[]{ 5 })
                && ViewDisks.strideIndices(6, 5, 1).length == 0
                && java.util.Arrays.equals(ViewDisks.strideIndices(3, 6, 1), new int[]{ 3, 4, 5, 6 }),
                "7f strideIndices: lo == hi → {lo}, hi < lo → empty, stride 1 = every index");
        }

        {
            // (9) whole-disk pick (D249, item S3)
            Vec3 c = new Vec3(3, 4, 5);
            Vec3 T = new Vec3(0, 0, 1), U = new Vec3(0, 1, 0), R = new Vec3(1, 0, 0);
            Vec3[] rim = ViewDisks.rimPoints(c, U, R, ViewDisks.CTL_RING_R, ViewDisks.RIM_N);
            boolean okRim = rim.length == ViewDisks.RIM_N;
            for (Vec3 p : rim) {
                if (!near(p.sub(c).length(), ViewDisks.CTL_RING_R, 1e-12)) okRim = false;
                if (Math.abs(p.sub(c).dot(T)) > 1e-12) okRim = false;
            }
            check(okRim && near(rim[0].sub(c).dot(U), ViewDisks.CTL_RING_R, 1e-12)
                && near(rim[2].sub(c).dot(R), ViewDisks.CTL_RING_R, 1e-12),
                "9a rimPoints: 8 points in the disk plane (⊥ T) at the ring radius, θ = 0 along U, 90° along R");
            check(ViewDisks.rimPoints(c, U, R, 1, 0).length == 0, "9a n = 0 → no rim points");
            // an ellipse projection: semi-axes 30 (x) and 10 (y) px about (100, 50)
            double[] rx = new double[8], ry = new double[8];
            for (int k = 0; k < 8; k++) {
                double th = 2 * Math.PI * k / 8;
                rx[k] = 100 + 30 * Math.cos(th);
                ry[k] = 50 + 10 * Math.sin(th);
            }
            check(near(ViewDisks.pickRadiusPx(100, 50, rx, ry, 12), 30, 1e-9),
                "9b pick radius = the projected rim's bounding radius (the ellipse's long semi-axis, 30 px)",
                String.valueOf(ViewDisks.pickRadiusPx(100, 50, rx, ry, 12)));
            double[] sx = new double[8], sy = new double[8];
            for (int k = 0; k < 8; k++) { sx[k] = 100 + 5 * Math.cos(2 * Math.PI * k / 8); sy[k] = 50 + 5 * Math.sin(2 * Math.PI * k / 8); }
            check(ViewDisks.pickRadiusPx(100, 50, sx, sy, 12) == 12,
                "9b a far disk (rim 5 px) keeps the 12 px floor — today's point pick");
            double[] nx = rx.clone(), ny = ry.clone();
            nx[0] = Double.NaN; ny[0] = Double.NaN;   // the far-right rim point behind the camera
            nx[4] = Double.NaN; ny[4] = Double.NaN;   // and the far-left one
            double pr = ViewDisks.pickRadiusPx(100, 50, nx, ny, 12);
            check(pr < 30 && near(pr, Math.hypot(30 * Math.cos(Math.PI / 4), 10 * Math.sin(Math.PI / 4)), 1e-9),
                "9b behind-camera (NaN) rim points are skipped (the 45° points bound it)", String.valueOf(pr));
            double[] allNan = new double[8];
            java.util.Arrays.fill(allNan, Double.NaN);
            check(ViewDisks.pickRadiusPx(100, 50, allNan, allNan, 12) == 12
                && ViewDisks.pickRadiusPx(100, 50, new double[0], new double[0], 12) == 12,
                "9b no usable rim point → the floor");
            // the normalized contest
            double big = ViewDisks.pickScore(30 * 30, 40);    // 30 px into a 40 px disk → 0.5625
            double far = ViewDisks.pickScore(6 * 6, 12);      // 6 px into a 12 px point pick → 0.25
            check(far < big && far < 1 && big < 1,
                "9c normalized score: the small far disk (6/12) beats the big near one (30/40) — no stealing",
                big + " vs " + far);
            check(ViewDisks.pickScore(12 * 12, 12) == 1.0 && !(ViewDisks.pickScore(12 * 12, 12) < 1.0)
                && ViewDisks.pickScore(11.9 * 11.9, 12) < 1.0,
                "9c on the rim = exactly 1 (a miss under the strict < 1 rule), just inside hits");
            check(ViewDisks.pickScore(0, 12) == 0.0
                && Double.isInfinite(ViewDisks.pickScore(1, 0)) && Double.isInfinite(ViewDisks.pickScore(1, -3)),
                "9c a press on the center scores 0; pickR ≤ 0 never hits");
            // ties keep build order: equal scores are not '<' each other
            check(!(ViewDisks.pickScore(9, 12) < ViewDisks.pickScore(9, 12)),
                "9c equal scores keep the EARLIER disk (strict nearest in build order)");
            check(ViewDisks.CTL_DISK_HT == 0.07 && ViewDisks.CTL_RING_HT == 0.03
                && ViewDisks.CTL_DISK_R == 0.26 && ViewDisks.CTL_RING_R == 0.30,
                "9d thickness doubled along T (0.07 / 0.03), radii unchanged (0.26 / 0.30)");
            check(ViewDisks.CTL_SEL_RING_LIGHTEN > 1.0 && ViewDisks.CTL_SEL_RING_LIGHTEN < 1.5
                && ViewDisks.PICK_PX == 12 && ViewDisks.RIM_N == 8,
                "9d selected-ring lighten factor > 1 (brighter, not white); PICK_PX 12 floor; 8 rim points");
        }
        {
            // (10) gizmo SLOW travel (batch 36): Gestures.slowDebt — the
            // Shift-held tenth, RE-BASED per event. The sinks read
            // eff = raw − px − debt; the press seeds prev = px, debt 0.
            check(Gestures.SLOW_K == 0.1, "10 SLOW_K is one tenth");
            // (a) never slow → the debt stays EXACTLY 0.0, so eff is the
            // historic raw travel bitwise (the pickprobe identity)
            double px = 100.25, prev = px, debt = 0;
            double[] raws = { 103.5, 97.125, 140.0, 140.0, 61.75, 212.333, 100.25 };
            boolean zero = true, bitwise = true;
            for (double rw : raws) {
                debt = Gestures.slowDebt(debt, prev, rw, false);
                prev = rw;
                zero &= Double.doubleToRawLongBits(debt) == 0L;
                bitwise &= Double.doubleToRawLongBits(rw - px - debt)
                        == Double.doubleToRawLongBits(rw - px);
            }
            check(zero && bitwise,
                "10a never slow: debt == +0.0 bitwise over any sequence, raw − px − debt == raw − px bitwise");
            // (b) slow from the very first event → eff == 0.1·raw travel
            prev = px; debt = 0;
            boolean tenth = true;
            for (double rw : raws) {
                debt = Gestures.slowDebt(debt, prev, rw, true);
                prev = rw;
                tenth &= near(rw - px - debt, 0.1 * (rw - px), 1e-12);
            }
            check(tenth, "10b slow from the start: eff == 0.1·raw (1e-12) at every event");
            // (c) Shift pressed MID-drag at raw1: the part continues from
            // where it IS — eff = raw1 + 0.1·(raw2 − raw1), no jump
            double raw1 = 130, raw2 = 180, raw3 = 200;
            prev = px; debt = 0;
            debt = Gestures.slowDebt(debt, prev, raw1, false); prev = raw1;
            double eff1 = raw1 - px - debt;
            debt = Gestures.slowDebt(debt, prev, raw2, true); prev = raw2;
            double eff2 = raw2 - px - debt;
            check(near(eff1, raw1 - px, 0) && near(eff2, eff1 + 0.1 * (raw2 - raw1), 1e-12),
                "10c Shift at raw1: eff == (raw1 − px) + 0.1·(raw2 − raw1) — continuous, no 90 % snap-back",
                eff1 + " -> " + eff2);
            // (d) released at raw2: full speed resumes FROM the effective
            // position — eff3 = eff2 + (raw3 − raw2), no leap forward
            debt = Gestures.slowDebt(debt, prev, raw3, false); prev = raw3;
            double eff3 = raw3 - px - debt;
            check(near(eff3, eff2 + (raw3 - raw2), 1e-12),
                "10d release at raw2: eff == eff2 + (raw3 − raw2) — full speed from the effective spot",
                String.valueOf(eff3));
            // a stationary event under Shift changes nothing; backwards
            // travel under Shift is slowed too (the debt shrinks with it)
            double d0 = Gestures.slowDebt(debt, prev, raw3, true);
            double d1 = Gestures.slowDebt(debt, prev, raw3 - 50, true);
            check(d0 == debt && near(d1, debt - 45, 1e-12),
                "10e a zero-move event leaves the debt; backwards Shift travel slows symmetrically");
            // (f) the seam of a toggle sequence composes: eff after
            // [plain 30, slow 50, plain 20, slow 100] = 30 + 5 + 20 + 10
            prev = px; debt = 0;
            double rx = px;
            boolean[] slow = { false, true, false, true };
            double[] moves = { 30, 50, 20, 100 };
            for (int i = 0; i < 4; i++) {
                rx += moves[i];
                debt = Gestures.slowDebt(debt, prev, rx, slow[i]);
                prev = rx;
            }
            check(near(rx - px - debt, 30 + 5 + 20 + 10, 1e-12),
                "10f toggles compose: plain 30 + slow 50 + plain 20 + slow 100 → 65 effective");
        }
        {
            // (11) the graph VIEW window (batch 37). panToInclude = the
            // pan-only follow Append runs on a MANUAL zoom window: the
            // width is kept, the shift is the smallest that brings x
            // inside with the margin to spare on the side it left by.
            check(Gestures.PAN_MARGIN == 0.08, "11 PAN_MARGIN is 8 % of the window width");
            double lo = 0, hi = 21 * 1.08;   // a 21 s run + auto-fit's 8 %
            boolean bit = true;
            double[][] wins = { {5, 15}, {0.3, 2.7}, {12.125, 12.225}, {1e-3, 20} };
            for (double[] w : wins) {
                double m = Gestures.PAN_MARGIN * (w[1] - w[0]);
                for (double f : new double[]{ 0, 0.25, 0.5, 0.9 }) {
                    double x = w[0] + f * (w[1] - w[0]);
                    if (x > w[1] - m) continue;
                    bit &= Double.doubleToRawLongBits(Gestures.panToInclude(w[0], w[1], x, m, lo, hi))
                        == Double.doubleToRawLongBits(w[0]);
                }
                bit &= Gestures.panToInclude(w[0], w[1], w[1] - m, m, lo, hi) == w[0];
            }
            check(bit, "11a x inside [w0, w1 − m] (both ends included): w0 back BITWISE — no pan");
            double n0 = Gestures.panToInclude(5, 15, 21, 0.8, lo, hi);
            check(near(n0, 21 + 0.8 - 10, 1e-12) && near((n0 + 10) - n0, 10, 1e-12)
                && near((n0 + 10) - 21, 0.8, 1e-12),
                "11b x right of the window: start = x + m − w, width kept, x sits m inside the new right edge",
                "[" + n0 + ", " + (n0 + 10) + "]");
            double n1 = Gestures.panToInclude(5, 15, 14.5, 0.8, lo, hi);
            check(near(n1 - 5, 14.5 - (15 - 0.8), 1e-12) && n1 > 5,
                "11c x inside the right margin zone: shifted by x − (w1 − m), the margin restored",
                String.valueOf(n1));
            double n2 = Gestures.panToInclude(15, 20, 6, 0.4, lo, hi);
            check(near(n2, 6 - 0.4, 1e-12),
                "11d x left of the window: start = x − m (x lands m inside the left edge)",
                String.valueOf(n2));
            check(Gestures.panToInclude(15, 20, 15, 0.4, lo, hi) == 15
                && Gestures.panToInclude(15, 20, 15.2, 0.4, lo, hi) == 15
                && Gestures.panToInclude(15, 20, 15.399, 0.4, lo, hi) == 15,
                "11e x in [w0, w0 + m): NO pan — only a node fully out on the left pans (the asymmetry)");
            double n3 = Gestures.panToInclude(0.5, 21.5, 21, Gestures.PAN_MARGIN * 21, lo, hi);
            check(near(n3, hi - 21, 1e-12) && 21 <= n3 + 21 && n3 >= lo,
                "11f upper clamp: start = hi − w, the node still inside (the margin = what the headroom leaves)",
                n3 + " right " + (n3 + 21) + " hi " + hi);
            check(Gestures.panToInclude(2, 6, 0.1, 0.32, lo, hi) == lo
                && Gestures.panToInclude(2, 6, -3, 0.32, 1.5, hi) == 1.5,
                "11g lower clamp: start = lo");
            check(Gestures.panToInclude(0, 30, 40, 2.4, lo, hi) == lo
                && Gestures.panToInclude(3, 33, 1, 2.4, lo, hi) == lo,
                "11h a window wider than the domain: start = lo (lo wins)");
            check(Gestures.panToInclude(5, 15, Double.NaN, 0.8, lo, hi) == 5
                && Gestures.panToInclude(5, 5, 21, 0.8, lo, hi) == 5
                && Gestures.panToInclude(15, 5, 21, 0.8, lo, hi) == 15
                && Gestures.panToInclude(5, Double.NaN, 21, 0.8, lo, hi) == 5,
                "11i a NaN x, an empty / negative / NaN-wide window: w0 untouched");
            double n4 = Gestures.panToInclude(5, 15, 18, 9, lo, 100);
            double n5 = Gestures.panToInclude(5, 15, 18, -2, lo, 100);
            check(near(n4, 18 + 5 - 10, 1e-12) && near(n5, 18 - 10, 1e-12),
                "11j the margin is cut to half the width (9 → 5: x lands mid-window); a negative one is 0",
                n4 + " / " + n5);
            double w0 = 5, w1 = 15, end = 14, drift = 0, lastGap = 0;
            boolean follows = true;
            for (int i = 0; i < 10; i++) {   // held A: ten 1 s appends
                end += 1;
                double n = Gestures.panToInclude(w0, w1, end,
                    Gestures.PAN_MARGIN * (w1 - w0), 0, end * 1.08);
                w1 = n + (w1 - w0);
                w0 = n;
                drift = Math.max(drift, Math.abs((w1 - w0) - 10));
                lastGap = w1 - end;
                follows &= end >= w0 && end <= w1;
            }
            check(drift <= 1e-12 && follows && near(lastGap, 0.8, 1e-9),
                "11k ten successive appends: the width never drifts (no zoom), the node stays in view 8 % inside the right edge",
                "drift " + drift + ", window [" + w0 + ", " + w1 + "]");

            // adoptWindow = the release of a drag that GREW the window
            double[] k0 = Gestures.adoptWindow(5, 18.5, 0, 21.6);
            check(k0 != null && Double.doubleToRawLongBits(k0[0]) == Double.doubleToRawLongBits(5.0)
                && Double.doubleToRawLongBits(k0[1]) == Double.doubleToRawLongBits(18.5),
                "11l a grown window inside the domain is kept BITWISE (nothing moves at release)");
            double[] k1 = Gestures.adoptWindow(5, 30, 0, 21.6);
            check(k1 != null && k1[0] == 5 && k1[1] == 21.6,
                "11m a right end past the domain clips to its end, the left end kept",
                Arrays.toString(k1));
            double[] k2 = Gestures.adoptWindow(-4, 9, 2, 21.6);
            check(k2 != null && k2[0] == 2 && k2[1] == 9,
                "11n a left end before the run clips to its start", Arrays.toString(k2));
            check(Gestures.adoptWindow(0, 21.6, 0, 21.6) == null
                && Gestures.adoptWindow(0, 40, 0, 21.6) == null
                && Gestures.adoptWindow(-3, 21.6 - 1e-10, 0, 21.6) == null,
                "11o a window at least domain-wide = auto-fit (null — the D75 rule)");
            check(Gestures.adoptWindow(30, 40, 0, 21.6) == null
                && Gestures.adoptWindow(-9, -1, 0, 21.6) == null
                && Gestures.adoptWindow(7, 7, 0, 21.6) == null
                && Gestures.adoptWindow(Double.NaN, 9, 0, 21.6) == null,
                "11p nothing left inside the domain (or an empty / NaN window) = auto-fit (null)");
            // an adopted window already satisfies the per-redraw sanitize:
            // inside the domain and narrower than it
            boolean sane = true;
            Random rv = new Random(37);
            for (int i = 0; i < 500; i++) {
                double a0 = rv.nextDouble() * 40 - 10, b0 = a0 + rv.nextDouble() * 40;
                double dl = rv.nextDouble() * 5, dh = dl + 1 + rv.nextDouble() * 30;
                double[] k = Gestures.adoptWindow(a0, b0, dl, dh);
                if (k == null) continue;
                if (k[0] < dl || k[1] > dh || !(k[1] - k[0] > 0)
                        || k[1] - k[0] >= dh - dl - 1e-9) sane = false;
            }
            check(sane, "11p' 500 random adoptions: every kept window lies inside the domain and is narrower than it");

            // growRightCapped = the right-edge growth under an overshoot
            check(Gestures.growRightCapped(15, 0.11, 16.2) == 15 + 0.11,
                "11q below the cap: w1 + grow bitwise");
            check(Gestures.growRightCapped(21.6, 0.5, 21.6) == 21.6
                && Gestures.growRightCapped(21.5, 0.5, 21.6) == 21.6,
                "11r at / reaching the cap: the window stops ON the domain end");
            check(Gestures.growRightCapped(30, 0.5, 21.6) == 30
                && Gestures.growRightCapped(30, 0, 21.6) == 30,
                "11s a window already past the cap (the run shrank under the drag) never shrinks");
        }
        {
            // (12) DEPTH-AWARE gizmo pick (batch 37). Rays are o + t·d,
            // |d| = 1; fixtures look down +z like an ortho view, a part
            // in FRONT has the smaller t.
            final int RING_PICK_N = 48;   // Viewport3D's ring polyline
            Vec3 o = new Vec3(0, 0, 0), z = new Vec3(0, 0, 1);
            double N = Double.NaN;
            // 12a sphere
            check(Gestures.raySphereFront(o, z, new Vec3(0, 0, 10), 2) == 8,
                "12a center ray: front hit t = 10 − r");
            check(near(Gestures.raySphereFront(new Vec3(1, 0, 0), z, new Vec3(0, 0, 10), 2), 10 - Math.sqrt(3), 1e-12),
                "12a off-center 1: 10 − sqrt(3)");
            check(Gestures.raySphereFront(new Vec3(2, 0, 0), z, new Vec3(0, 0, 10), 2) == 10,
                "12a tangent ray: t = 10",
                String.valueOf(Gestures.raySphereFront(new Vec3(2, 0, 0), z, new Vec3(0, 0, 10), 2)));
            check(Double.isNaN(Gestures.raySphereFront(new Vec3(2.5, 0, 0), z, new Vec3(0, 0, 10), 2)),
                "12a miss: NaN");
            check(Double.isNaN(Gestures.raySphereFront(o, z, new Vec3(0, 0, 10), 0))
                && Double.isNaN(Gestures.raySphereFront(o, z, new Vec3(0, 0, 10), -1)),
                "12a r ≤ 0 never hits");
            check(Gestures.raySphereFront(o, z, new Vec3(0, 0, -10), 2) == -12,
                "12a a sphere BEHIND the origin reads a negative t (ortho rays start on the projection plane)");
            // 12b capsule
            Vec3 A = new Vec3(-5, 0, 10), B = new Vec3(5, 0, 10);
            check(near(Gestures.rayCapsuleFront(o, z, A, B, 1), 9, 1e-12),
                "12b body, on-axis: 10 − r", String.valueOf(Gestures.rayCapsuleFront(o, z, A, B, 1)));
            check(near(Gestures.rayCapsuleFront(new Vec3(0, 0.6, 0), z, A, B, 1), 9.2, 1e-12),
                "12b body, 0.6 off the axis: 10 − 0.8",
                String.valueOf(Gestures.rayCapsuleFront(new Vec3(0, 0.6, 0), z, A, B, 1)));
            check(Double.isNaN(Gestures.rayCapsuleFront(new Vec3(0, 1.5, 0), z, A, B, 1)),
                "12b 1.5 off the axis: miss");
            check(near(Gestures.rayCapsuleFront(new Vec3(5.5, 0, 0), z, A, B, 1), 10 - Math.sqrt(0.75), 1e-12),
                "12b end cap 0.5 past b",
                String.valueOf(Gestures.rayCapsuleFront(new Vec3(5.5, 0, 0), z, A, B, 1)));
            check(Double.isNaN(Gestures.rayCapsuleFront(new Vec3(6.5, 0, 0), z, A, B, 1)),
                "12b 1.5 past b: miss");
            check(Gestures.rayCapsuleFront(o, z, new Vec3(0, 0, 10), new Vec3(0, 0, 10), 2) == 8,
                "12b degenerate segment = the sphere");
            double par = Gestures.rayCapsuleFront(new Vec3(0.5, 0, 0), z, new Vec3(0, 0, 10), new Vec3(0, 0, 20), 1);
            check(near(par, 10 - Math.sqrt(0.75), 1e-12),
                "12b ray PARALLEL to the axis (an arrow aimed at the viewer): the near cap", String.valueOf(par));
            double par2 = Gestures.rayCapsuleFront(new Vec3(0.5, 0, 0), z, new Vec3(0, 0, 20), new Vec3(0, 0, 10), 1);
            check(near(par2, par, 1e-12), "12b … whichever end is nearer", String.valueOf(par2));
            double skew = Gestures.rayCapsuleFront(o, z, new Vec3(-3, 0, 13), new Vec3(3, 0, 7), 1);
            check(near(skew, 10 - Math.sqrt(2), 1e-12),
                "12b a 45-degree shaft through (0,0,10): 10 − r·sqrt(2)", String.valueOf(skew));
            // 12c the drawn arrow radius
            check(Gestures.arrowRadius(0) == 0.05 && Gestures.arrowRadius(0.99) == 0.05
                && near(Gestures.arrowRadius(1.0), 0.13, 1e-15)
                && near(Gestures.arrowRadius(1.16), 0.065, 1e-12) && Gestures.arrowRadius(1.32) == 0
                && Gestures.arrowRadius(1.4) == 0 && Gestures.arrowRadius(-0.1) == 0
                && Gestures.ARROW_SHAFT_R == 0.05 && Gestures.ARROW_SHAFT_LEN == 1.0
                && Gestures.ARROW_TIP_R == 0.13 && Gestures.ARROW_LEN == 1.32
                && Gestures.RING_TUBE_R == 0.06 && Gestures.ARROW_TIP_N == 4,
                "12c arrow radius: shaft 0.05, cone 0.13 → 0 over 1.0..1.32; the drawn constants",
                String.valueOf(Gestures.arrowRadius(1.16)));
            // 12d the resolver, tier 1: DIRECT hits
            check(Gestures.pickFront(List.of(pc(2, 0, 1, 6, 9), pc(0, 0, 0, 12, 10))) == 0,
                "12d a ring IN FRONT of the ball under the cursor wins (near half)");
            check(Gestures.pickFront(List.of(pc(2, 0, 1, 6, 11), pc(0, 0, 0, 12, 10))) == 1,
                "12d a ring BEHIND the ball loses (far half)");
            check(Gestures.pickFront(List.of(pc(1, 0, 0, 9, 10), pc(0, 0, 0, 12, 10))) == 1
                && Gestures.pickFront(List.of(pc(0, 0, 0, 12, 10), pc(1, 0, 0, 9, 10))) == 0,
                "12d equal depth: the ball, whatever the registration order");
            check(Gestures.pickFront(List.of(pc(1, 0, 0, 9, 9.5), pc(2, 0, 0, 6, 9), pc(0, 0, 0, 12, 10))) == 1,
                "12d front-most among arrow/ring/ball");
            check(Gestures.pickFront(List.of(pc(1, 0, 0, 9, 9), pc(2, 0, 0, 6, 9))) == 0,
                "12d equal depth, no ball: first registered");
            check(Gestures.pickFront(List.of(pc(1, 0, 1.5, 9, 9), pc(0, 0, 1, 12, N))) == 0,
                "12d a DIRECT hit beats a nearer-by-px part that is only in its band");
            check(Gestures.pickFront(List.of(pc(1, 0, 12, 9, 8))) == 0,
                "12d a direct hit OUTSIDE its px band still picks (the cone on a tall pane)");
            check(Gestures.pickFront(List.of(pc(0, 1, 0, 8, 5), pc(1, 0, 1, 9, 9))) == 1,
                "12d the overlay rig covers a world track ball even when the ball is nearer");
            check(Gestures.pickFront(List.of(pc(0, 1, 0, 8, 7), pc(0, 1, 0, 8, 5))) == 1,
                "12d two track balls: the nearer");
            check(Gestures.pickFront(List.of(pc(0, 1, 0, 8, 7), pc(1, 0, 4, 9, N))) == 0,
                "12d a track ball under the cursor beats a rig part merely in its band");
            // 12e tier 2: the band fallback = D249 verbatim
            check(Gestures.pickFront(List.of(pc(0, 0, 3, 16, N), pc(2, 0, 1, 6, N))) == 1,
                "12e nothing direct: nearest by px (ring 1 < ball edge 3)");
            check(Gestures.pickFront(List.of(pc(1, 0, 0, 9, N), pc(0, 0, 0, 12, N))) == 1,
                "12e nothing direct, px tie: the ball");
            check(Gestures.pickFront(List.of(pc(1, 0, 4, 9, N), pc(2, 0, 4, 6, N))) == 0,
                "12e px tie without a ball: first registered");
            check(Gestures.pickFront(List.of(pc(1, 0, 9.5, 9, N), pc(2, 0, 6.5, 6, N), pc(0, 0, 13, 12, N))) == -1
                && Gestures.pickFront(List.of()) == -1,
                "12e everything outside its band / no parts: −1");
            check(Gestures.pickFront(List.of(pc(1, 0, 9, 9, N))) == 0,
                "12e the band edge itself is inside");
            // 12f geometry contests: the ball at (0,0,10), r 0.272 (the
            // rig's CENTER_BALL_R), the viewer looking +z (ortho rays)
            Vec3 C = new Vec3(0, 0, 10);
            double rB = 0.272, pad = 0.02;
            // an edge-on ring (axis +x → it lies in the y-z plane), the
            // cursor 0.1 above the center
            Vec3 o1 = new Vec3(0, 0.1, 0);
            double bF = Gestures.raySphereFront(o1, z, C, rB);
            double rF = Gestures.ringFront(o1, z, C, new Vec3(1, 0, 0), 0.7, Gestures.RING_TUBE_R + pad, RING_PICK_N);
            check(near(bF, 10 - Math.sqrt(rB * rB - 0.01), 1e-12) && rF < bF
                && near(rF, 10 - Math.sqrt(0.49 - 0.01) - 0.08, 0.01),
                "12f edge-on ring through the silhouette: its NEAR half is in front of the ball",
                "ball " + bF + " ring " + rF);
            // a tilted ring: the near arc passes BELOW the center, the far
            // arc above it
            double al = Math.toRadians(15);
            Vec3 axT = new Vec3(0, Math.cos(al), -Math.sin(al));
            double yArc = 0.7 * Math.sin(al);
            double nearArc = Gestures.ringFront(new Vec3(0, -yArc, 0), z, C, axT, 0.7, Gestures.RING_TUBE_R + pad, RING_PICK_N);
            double farArc = Gestures.ringFront(new Vec3(0, yArc, 0), z, C, axT, 0.7, Gestures.RING_TUBE_R + pad, RING_PICK_N);
            double bNear = Gestures.raySphereFront(new Vec3(0, -yArc, 0), z, C, rB),
                   bFar = Gestures.raySphereFront(new Vec3(0, yArc, 0), z, C, rB);
            check(nearArc < bNear && farArc > bFar,
                "12f tilted ring: near arc in front of the ball, far arc behind it",
                "near " + nearArc + " vs ball " + bNear + " | far " + farArc + " vs ball " + bFar);
            check(Gestures.pickFront(List.of(pc(2, 0, 0, 6, nearArc), pc(0, 0, 0, 12, bNear))) == 0
                && Gestures.pickFront(List.of(pc(2, 0, 0, 6, farArc), pc(0, 0, 0, 12, bFar))) == 1,
                "12f … so the press takes the ring on the near arc and the ball on the far arc");
            check(Double.isNaN(Gestures.ringFront(o, z, C, axT, 0.7, Gestures.RING_TUBE_R + pad, RING_PICK_N)),
                "12f the ring's tube is not under the center ray");
            // 12g a two-sided arrow 45° toward the viewer, up on screen
            double th = Math.toRadians(45);
            Vec3 dir = new Vec3(0, Math.sin(th), -Math.cos(th));
            Vec3 in = new Vec3(0, 0.10, 0), out = new Vec3(0, 0.20, 0), back = new Vec3(0, -0.20, 0);
            check(Gestures.arrowFront(in, z, C, dir, 1.0, true, 0) > Gestures.raySphereFront(in, z, C, rB),
                "12g the shaft INSIDE the ball is hidden: the ball is in front (cursor 0.10 off the center)");
            check(Gestures.arrowFront(out, z, C, dir, 1.0, true, 0) < Gestures.raySphereFront(out, z, C, rB),
                "12g past the shaft's exit the NEAR half is in front (cursor 0.20, still inside the 0.272 silhouette)");
            check(Gestures.arrowFront(back, z, C, dir, 1.0, true, 0) > Gestures.raySphereFront(back, z, C, rB),
                "12g the FAR half behind the ball loses (cursor −0.20)");
            double ctr = Gestures.arrowFront(o, z, C, dir, 1.0, true, 0);
            check(ctr > Gestures.raySphereFront(o, z, C, rB),
                "12g the rig center: arrow roots are inside the ball → the ball",
                ctr + " vs " + Gestures.raySphereFront(o, z, C, rB));
            // an arrow aimed AT the viewer: its cone covers the center
            Vec3 at = new Vec3(0, 0, -1);
            check(Gestures.arrowFront(o, z, C, at, 1.0, true, 0) < Gestures.raySphereFront(o, z, C, rB)
                && near(Gestures.arrowFront(o, z, C, at, 1.0, true, 0),
                    10 - Gestures.ARROW_LEN - Gestures.arrowRadius(1.28), 1e-12),
                "12g an arrow aimed at the viewer covers the center (its tip is the front-most thing)",
                String.valueOf(Gestures.arrowFront(o, z, C, at, 1.0, true, 0)));
            Vec3 rim = new Vec3(0.2, 0, 0);
            check(Double.isNaN(Gestures.arrowFront(rim, z, C, at, 1.0, true, 0)),
                "12g … but 0.2 off the axis only the ball is under the cursor (cone radius 0.13)");
            check(Double.isNaN(Gestures.arrowFront(new Vec3(0, 0.3, 0), z, C, new Vec3(1, 0, 0), 1.0, false, 0))
                && !Double.isNaN(Gestures.arrowFront(new Vec3(-0.5, 0.03, 0), z, C, new Vec3(1, 0, 0), 1.0, true, 0))
                && Double.isNaN(Gestures.arrowFront(new Vec3(-0.5, 0.03, 0), z, C, new Vec3(1, 0, 0), 1.0, false, 0)),
                "12g one-sided arrows have no mirrored half");
        }

        System.out.println("GestureGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
