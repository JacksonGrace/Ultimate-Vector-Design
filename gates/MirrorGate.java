import uvd.*;

import java.util.*;

/** D237 gate — MIRROR PIECE (v19.8): mirrorPiece is a pure graph
 *  negation whose compile must be BITWISE the z-negated track in the
 *  entry frame (entry at z=0, heading 0 ⇒ world z-negation):
 *  a) force lanes (Gl + roll swings, a speed control riding along);
 *  b) geo lanes (yaw + roll, incl. global axes);
 *  c) s/c elements (flat arc + tilted-plane arc + both roll modes);
 *  d) an INFLUENCED force section (Δyaw bump — delta lane 1 negates);
 *  e) a tilted entry (design roll 20° — the bank mirrors);
 *  f) mirror twice == the original, bitwise.
 *  EXTENDED batch 37 — MIRROR A WHOLE RUN (Coaster.mirrorRun, the
 *  Track Piece window's Mirror is run-scoped): the run reflects about
 *  the vertical plane through the ANCHOR's (head's) entry heading;
 *  g) a STRICT run [decoy run | head + 2 followers: held yaw/pitch/
 *     roll edits, mixed locks, a smoothed seam, a follower design
 *     tier, global/absolute geo lanes, an s/c follower], head yaw 0
 *     and 37°: compile == the reflection to 1e-9 (pos, T/U, Gv, −Gl,
 *     v, trueV), the decoy run bitwise untouched, save → load →
 *     rebuild bitwise, mirror twice back within 1e-9 m with every
 *     lane node / s-c element bitwise;
 *  h) a MIXED run [head, smoothed follower, bridge → target, follower
 *     of the target] under the three bridge modes × Optimize length:
 *     ≤ 1e-6 m vs the reflection, the target NOT moved by the rebuild
 *     after the mirror (optSig null = first sight, record only),
 *     lastOptDist unchanged — plus a BANKED-CREST bridge (both seam
 *     curvatures up, net pitch down, +19/−22 banks — the D155/D156
 *     family): after the mirror AND after one upstream edit applied
 *     to the mirrored run and to an unmirrored twin alike (the bridge
 *     re-marches in both), mirrored == reflection(twin) to 1e-6 m;
 *  i) refusals leave the document untouched: a non-head index, out of
 *     range, an orphan-bridge tail, an empty piece — and the three
 *     sentences verbatim;
 *  j) a run of ONE: mirrorRun(head) == mirrorPiece(head) field for
 *     field (the saved text) and compile-bitwise.
 */
public class MirrorGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name
            + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name
            + "  (" + detail + ")"); }
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** Bitwise mirror comparison: x/y equal, z value-negated, Gv
     *  equal, Gl negated. Returns a failure description or null. */
    static String mirrorCompare(TrackCompiler.Result a,
                                TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) {
            return "sample counts " + a.samples().size()
                + " vs " + b.samples().size();
        }
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample sa = a.samples().get(i);
            FVDIntegrator.Sample sb = b.samples().get(i);
            if (sa.pos().x() != sb.pos().x()
                    || sa.pos().y() != sb.pos().y()
                    || sb.pos().z() != -sa.pos().z()) {
                return String.format(Locale.US,
                    "pos mismatch at %d: (%.17g,%.17g,%.17g) vs"
                    + " (%.17g,%.17g,%.17g)", i,
                    sa.pos().x(), sa.pos().y(), sa.pos().z(),
                    sb.pos().x(), sb.pos().y(), sb.pos().z());
            }
            if (sa.Gv() != sb.Gv() || sb.Gl() != -sa.Gl()) {
                return String.format(Locale.US,
                    "felt mismatch at %d: Gv %.17g/%.17g Gl %.17g/%.17g",
                    i, sa.Gv(), sb.Gv(), sa.Gl(), sb.Gl());
            }
        }
        return null;
    }

    static String identicalCompare(TrackCompiler.Result a,
                                   TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return "counts";
        for (int i = 0; i < a.samples().size(); i++) {
            FVDIntegrator.Sample sa = a.samples().get(i);
            FVDIntegrator.Sample sb = b.samples().get(i);
            if (Double.doubleToLongBits(sa.pos().x())
                        != Double.doubleToLongBits(sb.pos().x())
                    || Double.doubleToLongBits(sa.pos().y())
                        != Double.doubleToLongBits(sb.pos().y())
                    || sa.pos().z() != sb.pos().z()
                    || sa.Gl() != sb.Gl()) {
                return "sample " + i;
            }
        }
        return null;
    }

    static Coaster forceFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 2.4}, {4.2, 0.4}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.8}, {3.5, -0.5},
            {6, 0.2} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 35}, {4.5, -20},
            {6, 0} }));
        ForceSection fs = new ForceSection(ls, 26);
        SpeedControl ctl = new SpeedControl(1.0, 1.2);
        ctl.speed = 22;
        fs.speedCtls.add(ctl);
        p.sections.add(fs);
        c.pieces.add(p);
        return c;
    }

    static Coaster geoFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 4}, {3, -10}, {6, 6} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 25}, {4.5, -14}, {6, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 40}, {6, -10} }));
        GeoSection gs = new GeoSection(ls, 24);
        p.sections.add(gs);
        c.pieces.add(p);
        return c;
    }

    static Coaster scFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        StraightCurveSection sc = StraightCurveSection.straight(8, 24);
        StraightCurveSection.Element arc =
            StraightCurveSection.Element.curve(20, 75, 0, 3);   // R20 75° left (v20.0 κ-form)
        arc.rollMode = StraightCurveSection.RollMode.GLOBAL;
        arc.rollVal = 28;
        sc.elements.add(arc);
        StraightCurveSection.Element arc2 =
            StraightCurveSection.Element.curve(30, -40, 45, 2);
        arc2.rollMode = StraightCurveSection.RollMode.RATE;
        arc2.rollVal = 15;
        sc.elements.add(arc2);
        p.sections.add(sc);
        c.pieces.add(p);
        return c;
    }

    static Coaster inflFixture() {
        Coaster c = forceFixture();
        ForceSection fs = (ForceSection) c.pieces.get(0).sections.get(0);
        fs.speedCtls.clear();
        Coaster.enterInfluence(fs, 6);
        fs.deltaLanes.set(1, lane(new double[][]{ {0, 0}, {2, 0},
            {3, 9}, {4, 0}, {6, 0} }));
        return c;
    }

    static void runCase(String name, Coaster c) throws Exception {
        TrackCompiler.Result r0 = rebuild(c);
        // deep-copy via IO round trip for the twice test
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        Coaster orig = DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
        check(c.mirrorPiece(0), name + "1 mirror applies", "");
        TrackCompiler.Result rm = rebuild(c);
        String cmp = mirrorCompare(r0, rm);
        check(cmp == null, name + "2 compile == BITWISE z-negation",
            cmp == null ? r0.samples().size() + " samples" : cmp);
        check(c.mirrorPiece(0), name + "3 mirror twice applies", "");
        TrackCompiler.Result r2 = rebuild(c);
        TrackCompiler.Result ro = rebuild(orig);
        String cmp2 = identicalCompare(ro, r2);
        check(cmp2 == null, name + "4 mirror² == identity bitwise",
            cmp2 == null ? "" : cmp2);
    }

    // ---------- batch 37: MIRROR A WHOLE RUN (g-j) ----------

    /** Reflect a point about the vertical plane through (x0, z0)
     *  along heading psiDeg — the heading direction is (cos, 0, −sin)
     *  (heading = atan2(−T.z, T.x)), so the plane's horizontal normal
     *  is (sin, 0, cos). */
    static Vec3 reflect(Vec3 p, double x0, double z0, double psiDeg) {
        double rad = Math.toRadians(psiDeg);
        double nx = Math.sin(rad), nz = Math.cos(rad);
        double d = (p.x() - x0) * nx + (p.z() - z0) * nz;
        return new Vec3(p.x() - 2 * d * nx, p.y(), p.z() - 2 * d * nz);
    }

    static Vec3 reflectDir(Vec3 v, double psiDeg) {
        return reflect(v, 0, 0, psiDeg);
    }

    /** Worst deviations of b from the REFLECTION of a over samples
     *  [i0..i1]: {pos m, T, U, Gv, Gl (negated), design v, trueV};
     *  null when the sample counts differ. */
    static double[] reflectDev(TrackCompiler.Result a,
            TrackCompiler.Result b, double x0, double z0, double psi,
            int i0, int i1) {
        if (a.samples().size() != b.samples().size()) return null;
        double[] d = new double[7];
        for (int i = i0; i <= i1; i++) {
            FVDIntegrator.Sample sa = a.samples().get(i);
            FVDIntegrator.Sample sb = b.samples().get(i);
            d[0] = Math.max(d[0], reflect(sa.pos(), x0, z0, psi)
                .sub(sb.pos()).length());
            d[1] = Math.max(d[1], reflectDir(sa.T(), psi)
                .sub(sb.T()).length());
            d[2] = Math.max(d[2], reflectDir(sa.U(), psi)
                .sub(sb.U()).length());
            d[3] = Math.max(d[3], Math.abs(sa.Gv() - sb.Gv()));
            d[4] = Math.max(d[4], Math.abs(sa.Gl() + sb.Gl()));
            d[5] = Math.max(d[5], Math.abs(sa.v() - sb.v()));
            d[6] = Math.max(d[6],
                Math.abs(a.trueV()[i] - b.trueV()[i]));
        }
        return d;
    }

    static boolean within(double[] d, double tol) {
        if (d == null) return false;
        for (double v : d) if (!(v <= tol)) return false;
        return true;
    }

    static String devTxt(double[] d) {
        return d == null ? "sample counts differ" : String.format(
            Locale.US, "pos %.2e m T %.2e U %.2e Gv %.2e Gl %.2e"
            + " v %.2e trueV %.2e", d[0], d[1], d[2], d[3], d[4],
            d[5], d[6]);
    }

    static boolean bitsEq(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /** Bitwise equality of two compiles over [i0..i1] (pos, T, U,
     *  felt, design v, trueV); null = equal. */
    static String bitwiseRange(TrackCompiler.Result a,
            TrackCompiler.Result b, int i0, int i1) {
        if (a.samples().size() != b.samples().size()) {
            return "sample counts " + a.samples().size() + " vs "
                + b.samples().size();
        }
        for (int i = i0; i <= i1; i++) {
            FVDIntegrator.Sample sa = a.samples().get(i);
            FVDIntegrator.Sample sb = b.samples().get(i);
            Vec3[] va = { sa.pos(), sa.T(), sa.U() };
            Vec3[] vb = { sb.pos(), sb.T(), sb.U() };
            for (int k = 0; k < 3; k++) {
                if (!bitsEq(va[k].x(), vb[k].x())
                        || !bitsEq(va[k].y(), vb[k].y())
                        || !bitsEq(va[k].z(), vb[k].z())) {
                    return "frame mismatch at sample " + i;
                }
            }
            if (!bitsEq(sa.Gv(), sb.Gv()) || !bitsEq(sa.Gl(), sb.Gl())
                    || !bitsEq(sa.v(), sb.v())
                    || !bitsEq(a.trueV()[i], b.trueV()[i])) {
                return "felt/speed mismatch at sample " + i;
            }
        }
        return null;
    }

    /** Sample range [i0, i1] of the run headed by `head`. */
    static int[] runRange(Coaster c, TrackCompiler.Result r, int head) {
        int re = c.runEndOf(head);
        int s0 = 0;
        for (int q = 0; q < head; q++) s0 += c.pieces.get(q).sections.size();
        int s1 = s0 - 1;
        for (int q = head; q <= re; q++) s1 += c.pieces.get(q).sections.size();
        return new int[]{ r.spans().get(s0).i0(),
            Math.min(r.spans().get(s1).i1(), r.samples().size() - 1) };
    }

    static String docText(Coaster c) {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        return sw.toString();
    }

    static Coaster roundTrip(Coaster c) throws Exception {
        return DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(docText(c)))).coasters().get(0);
    }

    /** Every lane node / s-c element number of the document, as bits
     *  (the mirror² "graphs return bitwise" check). */
    static long[] graphBits(Coaster c) {
        List<Long> out = new ArrayList<>();
        for (TrackPiece p : c.pieces) {
            for (Section s : p.sections) {
                if (s instanceof LaneSection ls) {
                    List<List<BezierBaker.Node>> all =
                        new ArrayList<>(ls.lanes);
                    if (ls.deltaLanes != null) all.addAll(ls.deltaLanes);
                    for (List<BezierBaker.Node> lane : all) {
                        for (BezierBaker.Node n : lane) {
                            for (double v : new double[]{ n.t, n.val,
                                    n.outFrac, n.outDv, n.inFrac,
                                    n.inDv }) {
                                out.add(Double.doubleToLongBits(v));
                            }
                        }
                    }
                } else if (s instanceof StraightCurveSection sc) {
                    for (StraightCurveSection.Element e : sc.elements) {
                        for (double v : new double[]{ e.k0, e.k1, e.L,
                                e.dirDeg, e.rollVal }) {
                            out.add(Double.doubleToLongBits(v));
                        }
                    }
                }
            }
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /** The plane heading of a run head: its entry's heading. */
    static double planePsi(TrackPiece h) {
        return Coaster.eulerOf(h.entryPose().orient())[0];
    }

    static List<List<BezierBaker.Node>> forceLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 2.4}, {4.2, 0.4}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.8}, {3.5, -0.5},
            {6, 0.2} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 35}, {4.5, -20},
            {6, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> shortLanes(double gv, double rr) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, gv}, {4, 1} }));
        ls.add(lane(new double[][]{ {0, 0.2}, {2, -0.3}, {4, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, rr}, {4, 0} }));
        return ls;
    }

    /** [decoy run | head + 2 strict followers]. Variants: 0 = held
     *  yaw/pitch/roll edits on the first follower (kinked seams);
     *  1 = + a live conflict on the head, a smoothed seam, a follower
     *  design tier, mixed locks; 2 = + the geo section global +
     *  absolute; 3 = + a fully continuous last follower. */
    static Coaster strictRun(double headYaw, int variant) {
        Coaster c = new Coaster();
        TrackPiece d = new TrackPiece(-80, 30, 60, 200);   // the decoy
        d.sections.add(new ForceSection(shortLanes(2.0, -15), 20));
        c.pieces.add(d);
        TrackPiece a = new TrackPiece(10, 21, -5, headYaw);
        a.sections.add(new ForceSection(forceLanes(), 26));
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 8}, {3, -4} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 12}, {3, 20} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 15}, {3, 0} }));
        GeoSection gs = new GeoSection(gl, 24);
        if (variant >= 2) {
            gs.yawPitchGlobal = true;
            gs.rollGlobal = true;
            gs.ypAbsolute = true;
            gs.rollAbsolute = true;
        }
        a.sections.add(gs);
        a.sections.add(new ForceSection(shortLanes(1.6, 10), 23));
        StraightCurveSection sc = StraightCurveSection.straight(8, 24);
        StraightCurveSection.Element arc =
            StraightCurveSection.Element.curve(20, 55, 20, 3);   // v20.0 κ-form
        arc.rollMode = StraightCurveSection.RollMode.GLOBAL;
        arc.rollVal = 18;
        sc.elements.add(arc);
        a.sections.add(sc);
        c.pieces.add(a);
        if (variant >= 1) {
            a.rollDeg = 12;          // a live conflict on the head
            a.designRollDeg = 5;
            a.pitchDeg = 4;
            a.designPitchDeg = 4;
        }
        int f2 = c.makeSeamStrict(1, 3, DT);      // the s/c piece follows
        int f1 = c.makeSeamStrict(1, 1, DT);      // geo + force follow
        if (f1 != 2 || f2 != 2) {
            throw new IllegalStateException("fixture: " + f1 + " " + f2);
        }
        TrackPiece p1 = c.pieces.get(2), p2 = c.pieces.get(3);
        p1.sYawDeg += 14;            // held-angle edits (kinked seams)
        p1.sRollDeg += 9;
        p1.sPitchDeg -= 3;
        if (variant >= 1) {
            p1.sSmoothLen = 4;
            p1.designPitchDeg = 2;
            p1.designRollDeg = -6;
            p2.sLockYaw = false;     // mixed locks
            p2.sLockRoll = false;
            p2.sPitchDeg += 5;
        }
        if (variant >= 3) {
            p2.sLockPitch = false;   // a fully continuous follower
        }
        return c;
    }

    /** [decoy | head, smoothed strict follower + bridge → target,
     *  strict follower of the target]. */
    static Coaster mixedRun(double headYaw, int mode, boolean optLen) {
        Coaster c = new Coaster();
        TrackPiece d = new TrackPiece(-80, 30, 60, 200);
        d.sections.add(new ForceSection(shortLanes(2.0, -15), 20));
        c.pieces.add(d);
        TrackPiece a = new TrackPiece(0, 21, 0, headYaw);
        a.sections.add(new ForceSection(forceLanes(), 26));
        a.sections.add(new ForceSection(shortLanes(1.6, 10), 23));
        c.pieces.add(a);
        int f = c.makeSeamStrict(1, 1, DT);
        TrackPiece a1 = c.pieces.get(f);
        a1.sPitchDeg += 3;
        a1.sSmoothLen = 3;
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        TrackCompiler.Span tail = r0.spans().get(2);
        TrackPose ex = tail.exit();
        Vec3 T = ex.orient().T();
        Vec3 side = new Vec3(-T.z(), 0, T.x());
        Vec3 tp = ex.pos().add(new Vec3(T.x(), 0, T.z()).scale(45))
            .add(side.scale(9)).add(new Vec3(0, -4, 0));
        double hd = Coaster.eulerOf(ex.orient())[0];
        TrackPiece b = new TrackPiece(tp.x(), tp.y(), tp.z(), hd + 20);
        b.sections.add(new ForceSection(shortLanes(1.4, 5), 24));
        b.sections.add(new ForceSection(shortLanes(1.2, -8), 22));
        b.rollDeg = 6;
        b.designRollDeg = 2;
        b.orientMode = TrackPiece.OrientMode.REAL_ONLY;
        c.pieces.add(b);
        BridgeSection bs = BridgeSection.solve(ex, b.entryPose(),
            r0.samples().get(tail.i1()).v());
        bs.optMode = mode;
        a1.sections.add(bs);
        if (mode != BridgeSection.OPT_NONE) {
            bs.optLen = optLen;
            boolean py = mode == BridgeSection.OPT_POS_YAW;
            c.optimizeBridge(bs, 3,
                optLen ? c.optimalBridgeLength(bs, py) : 50, py);
        }
        int f2 = c.makeSeamStrict(3, 1, DT);
        c.pieces.get(f2).sLockYaw = false;
        c.pieces.get(f2).sRollDeg += 7;
        return c;
    }

    /** The D156 BANKED CREST (Phase3Gate 54e's seam pair): the head
     *  ends pulling UP at +19° bank, the target enters pitched −5°
     *  with its own curvature pulling UP at −22° bank — net pitch
     *  DOWN. Optimize position + orientation at length d. The target
     *  sits at Phase3Gate's offset in the head's heading frame. */
    static Coaster crest(double headYaw, double d) {
        Coaster c = new Coaster();
        c.v0 = 26;
        double hx = 3, hz = -4;
        TrackPiece p1 = new TrackPiece(hx, 21, hz, headYaw);
        p1.rollDeg = 19;
        List<List<BezierBaker.Node>> l1 = new ArrayList<>();
        l1.add(lane(new double[][]{
            {0, 1}, {1, 1}, {1.56, 3.6}, {1.68, 3.6} }));
        l1.add(lane(new double[][]{ {0, 0}, {1, 0} }));
        l1.add(lane(new double[][]{ {0, 0}, {1, 0} }));
        p1.sections.add(new ForceSection(l1, 26));
        c.pieces.add(p1);
        double rad = Math.toRadians(headYaw);
        double dx = 60, dz = 14;
        TrackPiece p2 = new TrackPiece(
            hx + dx * Math.cos(rad) + dz * Math.sin(rad), 30,
            hz - dx * Math.sin(rad) + dz * Math.cos(rad), headYaw);
        p2.baseOrient = Quat.aboutAxis(new Vec3(0, 0, 1),
            Math.toRadians(-5));
        p2.rollDeg = -22;
        List<List<BezierBaker.Node>> l2 = new ArrayList<>();
        l2.add(lane(new double[][]{ {0, 2.2}, {1, 2.2}, {2, 1} }));
        l2.add(lane(new double[][]{ {0, 0}, {2, 0} }));
        l2.add(lane(new double[][]{ {0, 0}, {2, 0} }));
        p2.sections.add(new ForceSection(l2, 23));
        c.pieces.add(p2);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(
            r0.spans().get(0).exit(), p2.entryPose(), 25);
        bs.optMode = BridgeSection.OPT_POS_YAW;   // yaw free,
        p1.sections.add(bs);                      // pitch + roll held
        c.optimizeBridge(bs, 1, d, true);
        return c;
    }

    static BridgeSection trailingBridge(TrackPiece p) {
        return (BridgeSection) p.sections.get(p.sections.size() - 1);
    }

    /** Does the bridge render its MARCH spine (vs the septic
     *  fallback)? Coaster.marchSpine is package-private. */
    static boolean marchRenders(BridgeSection bs, boolean py) {
        try {
            java.lang.reflect.Method m = Coaster.class.getDeclaredMethod(
                "marchSpine", BridgeSection.class, boolean.class);
            m.setAccessible(true);
            return m.invoke(null, bs, py) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static void strictCase(String name, double headYaw, int variant)
            throws Exception {
        Coaster c = strictRun(headYaw, variant);
        TrackCompiler.Result r0 = rebuild(c);
        long[] g0 = graphBits(c);
        TrackPiece h = c.pieces.get(1);
        double x0 = h.x, z0 = h.z, psi0 = planePsi(h);
        int[] rg = runRange(c, r0, 1), rd = runRange(c, r0, 0);
        boolean topo = c.runStartOf(3) == 1 && c.runEndOf(1) == 3;
        check(topo && c.mirrorRunWhy(1) == null && c.mirrorRun(1),
            name + "1 mirrorRun applies (head + 2 followers)", "");
        TrackCompiler.Result rm = rebuild(c);
        double[] d = reflectDev(r0, rm, x0, z0, psi0, rg[0], rg[1]);
        check(within(d, 1e-9),
            name + "2 compile == the reflection about the anchor's"
            + " plane (1e-9)", devTxt(d));
        String dec = bitwiseRange(r0, rm, rd[0], rd[1]);
        check(dec == null, name + "3 the decoy run is BITWISE untouched",
            dec == null ? (rd[1] - rd[0] + 1) + " samples" : dec);
        TrackCompiler.Result rrt = rebuild(roundTrip(c));
        String io = bitwiseRange(rm, rrt, 0, rm.samples().size() - 1);
        check(io == null, name + "4 save → load → rebuild bitwise",
            io == null ? "" : io);
        boolean again = c.mirrorRun(1);
        TrackCompiler.Result r2 = rebuild(c);
        double worst = Double.NaN;
        if (r2.samples().size() == r0.samples().size()) {
            worst = 0;
            for (int i = 0; i < r0.samples().size(); i++) {
                worst = Math.max(worst, r0.samples().get(i).pos()
                    .sub(r2.samples().get(i).pos()).length());
            }
        }
        check(again && worst <= 1e-9 && Arrays.equals(g0, graphBits(c)),
            name + "5 mirror twice: back within 1e-9 m, every lane"
            + " node / element bitwise", String.format(Locale.US,
                "max %.2e m, %d graph numbers", worst, g0.length));
    }

    static void bridgeCase(String name, double headYaw, int mode,
            boolean optLen) {
        Coaster c = mixedRun(headYaw, mode, optLen);
        TrackCompiler.Result r0 = rebuild(c);
        TrackPiece h = c.pieces.get(1);
        double x0 = h.x, z0 = h.z, psi0 = planePsi(h);
        int[] rg = runRange(c, r0, 1);
        BridgeSection bs = trailingBridge(c.pieces.get(2));
        double len0 = bs.lastOptDist;
        boolean topo = c.runStartOf(4) == 1 && c.runEndOf(1) == 4;
        boolean ok = c.mirrorRun(1);
        TrackPiece t = c.pieces.get(3);
        double tx = t.x, ty = t.y, tz = t.z;
        TrackCompiler.Result rm = rebuild(c);
        double[] d = reflectDev(r0, rm, x0, z0, psi0, rg[0], rg[1]);
        check(topo && ok && within(d, 1e-6),
            name + "1 the mixed run == the reflection (1e-6)",
            devTxt(d));
        check(bitsEq(t.x, tx) && bitsEq(t.y, ty) && bitsEq(t.z, tz)
                && bitsEq(bs.lastOptDist, len0),
            name + "2 the rebuild after the mirror leaves the target"
            + " ON its reflected placement, lastOptDist unchanged",
            String.format(Locale.US, "moved %.2e m, length %.4f → %.4f",
                new Vec3(t.x - tx, t.y - ty, t.z - tz).length(), len0,
                bs.lastOptDist));
    }

    /** The manual-mode stash across a Mirror (review round, E5): an
     *  UNTOUCHED stash still reads untouched — bit for bit the
     *  mirrored target — and a HAND-MOVED one stays hand-moved by the
     *  same distance. GraphEditor.setBridgeOptMode's test, verbatim. */
    static boolean stashUntouched(BridgeSection bs, TrackPiece t) {
        double[] st = bs.noneStash;
        return st != null && Math.abs(t.x - st[1]) < 1e-9
            && Math.abs(t.y - st[2]) < 1e-9
            && Math.abs(t.z - st[3]) < 1e-9
            && Math.abs(t.yawDeg - st[4]) < 1e-9
            && Math.abs(t.pitchDeg - st[5]) < 1e-9
            && Math.abs(t.rollDeg - st[6]) < 1e-9;
    }

    static void stashCase(String name, double headYaw) {
        for (int moved = 0; moved < 2; moved++) {
            Coaster c = mixedRun(headYaw, BridgeSection.OPT_NONE, false);
            rebuild(c);
            BridgeSection bs = trailingBridge(c.pieces.get(2));
            TrackPiece t = c.pieces.get(3);
            // entering manual mode stashes {optLen, the placement}
            bs.noneStash = new double[]{ 0, t.x, t.y, t.z, t.yawDeg,
                t.pitchDeg, t.rollDeg };
            if (moved == 1) {   // a hand-move after the stash
                t.x += 1.5;
                t.yawDeg += 4;
            }
            double d0 = Math.hypot(t.x - bs.noneStash[1],
                t.z - bs.noneStash[3]);
            boolean ok = c.mirrorRun(1);
            double[] st = bs.noneStash;
            if (moved == 0) {
                check(ok && st != null && st[0] == 0 && bitsEq(st[1], t.x)
                        && bitsEq(st[2], t.y) && bitsEq(st[3], t.z)
                        && bitsEq(st[4], t.yawDeg)
                        && bitsEq(st[5], t.pitchDeg)
                        && bitsEq(st[6], t.rollDeg)
                        && stashUntouched(bs, t),
                    name + "1 an UNTOUCHED manual-mode stash reflects with"
                    + " its target: still untouched, bit for bit",
                    st == null ? "stash dropped" : "");
            } else {
                double d1 = st == null ? Double.NaN
                    : Math.hypot(t.x - st[1], t.z - st[3]);
                check(ok && st != null && !stashUntouched(bs, t)
                        && Math.abs(d1 - d0) < 1e-9
                        && Math.abs(Math.abs(t.yawDeg - st[4]) - 4) < 1e-9,
                    name + "2 a HAND-MOVED stash reflects too: still"
                    + " hand-moved, by the same distance and yaw",
                    String.format(Locale.US, "%.6f m -> %.6f m", d0, d1));
            }
        }
    }

    static void crestCase(String name, double headYaw, double len) {
        Coaster m = crest(headYaw, len), w = crest(headYaw, len);
        rebuild(m);
        TrackCompiler.Result rw0 = rebuild(w);
        TrackPiece h = m.pieces.get(0);
        double x0 = h.x, z0 = h.z, psi0 = planePsi(h);
        boolean march0 = marchRenders(trailingBridge(w.pieces.get(0)), true);
        boolean ok = m.mirrorRun(0);
        TrackCompiler.Result rm0 = rebuild(m);
        double[] d0 = reflectDev(rw0, rm0, x0, z0, psi0, 0,
            rw0.samples().size() - 1);
        check(ok && d0 != null && d0[0] <= 1e-6 && d0[1] <= 1e-6
                && d0[2] <= 1e-6,
            name + "1 banked crest: mirrored == the reflection (1e-6)",
            devTxt(d0) + (march0 ? ", march" : ", SEPTIC"));
        // ONE upstream edit, the same on both: the head's Gv lane's
        // last node moves 0.1 s out — the section lengthens, the seam
        // signature changes, the bridge RE-MARCHES in both documents
        TrackPiece tw = w.pieces.get(1);
        Vec3 before = new Vec3(tw.x, tw.y, tw.z);
        for (Coaster c : new Coaster[]{ m, w }) {
            List<BezierBaker.Node> gv = ((LaneSection) c.pieces.get(0)
                .sections.get(0)).lanes.get(0);
            gv.get(gv.size() - 1).t += 0.1;
        }
        TrackCompiler.Result rw1 = rebuild(w);
        TrackCompiler.Result rm1 = rebuild(m);
        double moved = new Vec3(tw.x, tw.y, tw.z).sub(before).length();
        boolean march1 = marchRenders(trailingBridge(w.pieces.get(0)), true)
            && marchRenders(trailingBridge(m.pieces.get(0)), true);
        double[] d1 = reflectDev(rw1, rm1, x0, z0, psi0, 0,
            Math.min(rw1.samples().size(), rm1.samples().size()) - 1);
        check(moved > 1e-3 && d1 != null && d1[0] <= 1e-6
                && d1[1] <= 1e-6 && d1[2] <= 1e-6,
            name + "2 after ONE upstream edit on both (the bridge"
            + " re-marches): mirrored == reflection(twin) (1e-6)",
            devTxt(d1) + String.format(Locale.US,
                ", twin target moved %.3f m", moved)
            + (march1 ? ", march" : ", SEPTIC"));
    }

    static void refusals() {
        Coaster c = strictRun(37, 1);
        rebuild(c);
        String t0 = docText(c);
        boolean r1 = c.mirrorRun(2), r2 = c.mirrorRun(3),
            r3 = c.mirrorRun(-1), r4 = c.mirrorRun(c.pieces.size());
        String head = "that piece is not the start of its track piece.";
        check(!r1 && !r2 && !r3 && !r4 && t0.equals(docText(c))
                && head.equals(c.mirrorRunWhy(2))
                && head.equals(c.mirrorRunWhy(-1))
                && head.equals(c.mirrorRunWhy(c.pieces.size())),
            "i1 refused: a follower index / out of range — the document"
            + " untouched, the sentence verbatim", "");
        // an ORPHAN bridge at the run's tail: the crest fixture with
        // its target deleted
        Coaster o = crest(0, 45);
        rebuild(o);
        o.pieces.remove(1);
        BridgeSection ob = trailingBridge(o.pieces.get(0));
        double[] sig = ob.optSig;
        String t1 = docText(o);
        boolean r5 = o.mirrorRun(0);
        check(!r5 && t1.equals(docText(o)) && ob.optSig == sig
                && "delete or convert the orphaned bridge at the end first."
                    .equals(o.mirrorRunWhy(0)),
            "i2 refused: an ORPHAN bridge at the tail — the document and"
            + " the bridge's signature untouched", "");
        Coaster e = new Coaster();
        e.pieces.add(new TrackPiece(0, 21, 0, 0));
        check(!e.mirrorRun(0) && "the track piece holds an empty piece."
                .equals(e.mirrorRunWhy(0)),
            "i3 refused: an empty piece", "");
    }

    /** A detached piece that carries the CANONICAL pair (a split). */
    static Coaster splitFixture() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(10, 21, -5, 25);
        a.sections.add(new ForceSection(forceLanes(), 26));
        a.sections.add(new ForceSection(shortLanes(1.6, 10), 23));
        c.pieces.add(a);
        TrackCompiler.Result r = rebuild(c);
        int np = c.splitPiece(0, 1, r.spans().get(0));
        if (np != 1 || c.pieces.get(1).canonOrient == null) {
            throw new IllegalStateException("fixture: split " + np);
        }
        c.pieces.get(1).yawDeg = 12;
        c.pieces.get(1).rollDeg = 8;
        c.pieces.get(1).designRollDeg = 3;
        return c;
    }

    static Coaster tiltedFixture() {
        Coaster c = forceFixture();
        TrackPiece p = c.pieces.get(0);
        p.x = 10;
        p.z = -5;
        p.yawDeg = 37;
        p.rollDeg = 20;
        p.designRollDeg = 5;
        p.pitchDeg = 4;
        return c;
    }

    static void singleCase(String name,
            java.util.function.Supplier<Coaster> fix, int head) {
        Coaster a = fix.get(), b = fix.get();
        rebuild(a);
        rebuild(b);
        boolean ok = a.mirrorPiece(head) && b.mirrorRun(head);
        boolean fields = docText(a).equals(docText(b));
        TrackCompiler.Result ra = rebuild(a), rb = rebuild(b);
        String cmp = bitwiseRange(ra, rb, 0, ra.samples().size() - 1);
        check(ok && fields && cmp == null,
            name + " a run of ONE: mirrorRun == mirrorPiece field for"
            + " field, compile BITWISE",
            cmp == null ? ra.samples().size() + " samples" : cmp);
    }

    public static void main(String[] args) throws Exception {
        runCase("a", forceFixture());
        runCase("b", geoFixture());
        runCase("c", scFixture());
        runCase("d", inflFixture());
        Coaster e = forceFixture();
        e.pieces.get(0).rollDeg = 20;
        e.pieces.get(0).designRollDeg = 20;
        runCase("e", e);

        // ---- batch 37: mirrorRun (g-j) ----
        for (double yaw : new double[]{ 0, 37 }) {
            for (int v = 0; v <= 3; v++) {
                strictCase(String.format(Locale.US, "g yaw%.0f v%d ",
                    yaw, v), yaw, v);
            }
        }
        int[] modes = { BridgeSection.OPT_NONE, BridgeSection.OPT_POS,
            BridgeSection.OPT_POS_YAW };
        String[] mn = { "NONE", "POS", "POS_YAW" };
        for (double yaw : new double[]{ 0, 37 }) {
            for (int m = 0; m < 3; m++) {
                for (boolean optLen : new boolean[]{ false, true }) {
                    if (m == 0 && optLen) continue;   // no length to optimize
                    bridgeCase(String.format(Locale.US, "h yaw%.0f %s%s ",
                        yaw, mn[m], optLen ? " optLen" : ""), yaw,
                        modes[m], optLen);
                }
            }
        }
        // 45 m = inside the damped fixed point's range, 58 m = past
        // the old ~45-51 m cliff (the seeded staged rescue, D156)
        for (double yaw : new double[]{ 0, 37 }) {
            for (double len : new double[]{ 45, 58 }) {
                crestCase(String.format(Locale.US,
                    "h crest yaw%.0f %.0f m ", yaw, len), yaw, len);
            }
        }
        stashCase("h-stash yaw0 ", 0);
        stashCase("h-stash yaw37 ", 37);
        refusals();
        singleCase("j1 force, tilted + yawed:", MirrorGate::tiltedFixture, 0);
        singleCase("j2 geo:", MirrorGate::geoFixture, 0);
        singleCase("j3 straight/curve:", MirrorGate::scFixture, 0);
        singleCase("j4 influenced:", MirrorGate::inflFixture, 0);
        singleCase("j5 a split piece (canonical pair):",
            MirrorGate::splitFixture, 1);

        System.out.println();
        System.out.println(fail == 0
            ? "ALL MIRROR GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
