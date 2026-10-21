import uvd.*;

import java.util.*;

/** D247 gate — COPY (v19.18, the Track Piece panel's "Copy"):
 *  Coaster.copyRun appends deep copies of a whole RUN at the list
 *  end, translated by COPY_DZ = 5 m along world Z. Because generation
 *  is canonical (D199) the copy's shape is BITWISE the source's:
 *  every copied sample has Gv/Gl/v/T/U and pos.x/pos.y bit-identical
 *  and pos.z = source + 5 (< 1e-9); bridge spans are world-anchored
 *  re-solves and hold to 1e-6. Two D199-accepted DUST corners: a
 *  TILTED piece (design/true conflict) or a follower with a HELD
 *  seam angle rigid-tilts its finished geometry about the entry
 *  POSITION in world coordinates, so (anchor+local)−anchor leaves
 *  ulp dust in pos (felt/frame/v stay bitwise — POS_DUST, 1e-9), and
 *  a SMOOTHED strict seam re-derives the blend window's frames from
 *  world poses (ALL_DUST, 1e-9). The ORIGINAL run never moves, the
 *  first rebuild after the copy writes nothing (a second rebuild is a
 *  bitwise no-op, every copied vDesign untouched), save→load→rebuild
 *  reproduces the copied document bitwise, and a run ending in an
 *  ORPHAN bridge refuses (−1) leaving the document untouched.
 *  Fixtures: a) force + speed control + a geo global/absolute
 *  section; b) s/c; c) an influenced force section; d) a design/true
 *  orientation conflict (POS_DUST); e) a strict-seam run — e pure
 *  link (bitwise), e' held pitch + a live design tier on the follower
 *  (POS_DUST), e" plus a smoothed seam (ALL_DUST); f) a bridged run under OPT_POS_YAW
 *  after an explicit optimize; g) the orphan-bridge refusal; h) the
 *  copy's object independence + session-flag reset. */
public class CopyGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    /** The roll lanes agree bitwise: flags, nodes, shadow, fingerprint
     *  (v20.1, D259 §2.1). */
    static boolean laneSame(RollLane a, RollLane b) {
        if (a.rollGlobal() != b.rollGlobal() || a.ratePerMeter() != b.ratePerMeter()
                || a.rollAbsolute() != b.rollAbsolute()) return false;
        if (!nodesSame(a.nodes, b.nodes)) return false;
        if ((a.shadow() == null) != (b.shadow() == null)) return false;
        if (a.shadow() != null) {
            if (!nodesSame(a.shadow(), b.shadow())) return false;
            RollLane.Cut x = a.cut(), y = b.cut();
            if (x == null || y == null || x.inserted() != y.inserted() || !bits(x.t(), y.t())
                    || !bits(x.val(), y.val()) || !bits(x.inSlope(), y.inSlope())
                    || !bits(x.outSlope(), y.outSlope()) || x.smooth() != y.smooth()
                    || !bits(x.prevOutSlope(), y.prevOutSlope())) return false;
        }
        return true;
    }

    static boolean nodesSame(List<BezierBaker.Node> a, List<BezierBaker.Node> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            BezierBaker.Node x = a.get(i), y = b.get(i);
            if (!bits(x.t, y.t) || !bits(x.val, y.val) || !bits(x.outFrac, y.outFrac)
                    || !bits(x.outDv, y.outDv) || !bits(x.inFrac, y.inFrac)
                    || !bits(x.inDv, y.inDv) || x.smooth != y.smooth) return false;
        }
        return true;
    }

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

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static boolean bits(Vec3 a, Vec3 b) {
        return bits(a.x(), b.x()) && bits(a.y(), b.y()) && bits(a.z(), b.z());
    }

    /** Full bitwise sample comparison (pos/T/U/v/Gv/Gl) of a[i0..]
     *  against b[j0..] over n samples; a failure description or null. */
    static String identical(List<FVDIntegrator.Sample> a, int i0,
                            List<FVDIntegrator.Sample> b, int j0, int n) {
        if (i0 + n > a.size() || j0 + n > b.size()) {
            return "range " + i0 + "+" + n + " / " + j0 + "+" + n
                + " beyond " + a.size() + " / " + b.size();
        }
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sa = a.get(i0 + i), sb = b.get(j0 + i);
            if (!bits(sa.pos(), sb.pos()) || !bits(sa.T(), sb.T())
                    || !bits(sa.U(), sb.U()) || !bits(sa.v(), sb.v())
                    || !bits(sa.Gv(), sb.Gv()) || !bits(sa.Gl(), sb.Gl())) {
                return "sample " + i + " differs";
            }
        }
        return null;
    }

    /** Which spans (flattened section order) are bridges. */
    static boolean[] bridgeSpans(Coaster c) {
        List<Boolean> out = new ArrayList<>();
        for (TrackPiece p : c.pieces) {
            for (Section s : p.sections) out.add(s instanceof BridgeSection);
        }
        boolean[] b = new boolean[out.size()];
        for (int i = 0; i < b.length; i++) b[i] = out.get(i);
        return b;
    }

    /** The copy comparison: copy samples (the second half) vs the
     *  source samples (the first half). Non-bridge spans: Gv/Gl/v/T/U
     *  + pos.x/pos.y BITWISE, pos.z = src.z + dz within 1e-9. Bridge
     *  spans: everything within 1e-6 (world-anchored re-solve). */
    static final int BITWISE = 0;   // everything bitwise
    static final int POS_DUST = 1;  // pos within 1e-9, felt/frame/v bitwise
    static final int ALL_DUST = 2;  // everything within 1e-9

    static String copyCompare(TrackCompiler.Result r, int nSrcSpans,
                              boolean[] isBridge, double dz, int mode) {
        List<FVDIntegrator.Sample> s = r.samples();
        if (s.size() % 2 != 0) return "odd sample count " + s.size();
        int half = s.size() / 2;
        if (r.spans().size() != 2 * nSrcSpans) {
            return "span count " + r.spans().size() + " vs 2×" + nSrcSpans;
        }
        double worstZ = 0, worstB = 0;
        for (int k = 0; k < nSrcSpans; k++) {
            TrackCompiler.Span sp = r.spans().get(k);
            TrackCompiler.Span cp = r.spans().get(k + nSrcSpans);
            if (cp.i0() - sp.i0() != half || cp.i1() - sp.i1() != half) {
                return "span " + k + " layout: src [" + sp.i0() + "," + sp.i1()
                    + "] copy [" + cp.i0() + "," + cp.i1() + "] half " + half;
            }
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                FVDIntegrator.Sample a = s.get(i), b = s.get(i + half);
                if (isBridge[k]) {
                    double d = Math.max(
                        b.pos().sub(a.pos().add(new Vec3(0, 0, dz))).length(),
                        Math.max(b.T().sub(a.T()).length(),
                                 b.U().sub(a.U()).length()));
                    d = Math.max(d, Math.max(Math.abs(b.v() - a.v()),
                        Math.max(Math.abs(b.Gv() - a.Gv()),
                                 Math.abs(b.Gl() - a.Gl()))));
                    worstB = Math.max(worstB, d);
                    if (d > 1e-6) {
                        return String.format(Locale.US,
                            "bridge span %d sample %d off by %.3e", k, i, d);
                    }
                    continue;
                }
                if (mode == ALL_DUST) {
                    double d = Math.max(
                        b.pos().sub(a.pos().add(new Vec3(0, 0, dz))).length(),
                        Math.max(b.T().sub(a.T()).length(),
                                 b.U().sub(a.U()).length()));
                    d = Math.max(d, Math.max(Math.abs(b.v() - a.v()),
                        Math.max(Math.abs(b.Gv() - a.Gv()),
                                 Math.abs(b.Gl() - a.Gl()))));
                    if (d > 1e-9) {
                        return String.format(Locale.US,
                            "span %d sample %d off by %.3e (dust mode)", k, i, d);
                    }
                    continue;
                }
                if (!bits(a.Gv(), b.Gv()) || !bits(a.Gl(), b.Gl())
                        || !bits(a.v(), b.v()) || !bits(a.T(), b.T())
                        || !bits(a.U(), b.U())) {
                    return String.format(Locale.US,
                        "span %d sample %d: felt/frame not bitwise"
                        + " (Gv %.17g/%.17g Gl %.17g/%.17g v %.17g/%.17g)",
                        k, i, a.Gv(), b.Gv(), a.Gl(), b.Gl(), a.v(), b.v());
                }
                if (mode == POS_DUST) {
                    double d = b.pos().sub(a.pos().add(new Vec3(0, 0, dz))).length();
                    if (d > 1e-9) {
                        return String.format(Locale.US,
                            "span %d sample %d pos off by %.3e (pos-dust mode)",
                            k, i, d);
                    }
                    continue;
                }
                if (!bits(a.pos().x(), b.pos().x())
                        || !bits(a.pos().y(), b.pos().y())) {
                    return String.format(Locale.US,
                        "span %d sample %d: x/y not bitwise"
                        + " (%.17g,%.17g) vs (%.17g,%.17g)", k, i,
                        a.pos().x(), a.pos().y(), b.pos().x(), b.pos().y());
                }
                double ez = Math.abs(b.pos().z() - a.pos().z() - dz);
                worstZ = Math.max(worstZ, ez);
                if (ez > 1e-9) {
                    return String.format(Locale.US,
                        "span %d sample %d: z off by %.3e", k, i, ez);
                }
            }
        }
        return null;
    }

    static double[] vDesigns(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) v.add(s.vDesign());
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    static Coaster roundTrip(Coaster c) throws Exception {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        return DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(sw.toString()))).coasters().get(0);
    }

    // ---------------- fixtures ----------------

    static List<List<BezierBaker.Node>> forceLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 2.4}, {4.2, 0.4}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.8}, {3.5, -0.5},
            {6, 0.2} }));
        ls.add(lane(new double[][]{ {0, 0}, {2.5, 35}, {4.5, -20},
            {6, 0} }));
        return ls;
    }

    static Coaster forceFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection fs = new ForceSection(forceLanes(), 26);
        SpeedControl ctl = new SpeedControl(1.0, 1.2);
        ctl.speed = 22;
        fs.speedCtls.add(ctl);
        fs.speedMarks.add(3.5);
        p.sections.add(fs);
        // a geo section under GLOBAL axes with ABSOLUTE behaviors
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 8}, {3, -4} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 12}, {3, 20} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 15}, {3, 0} }));
        GeoSection gs = new GeoSection(gl, 24);
        gs.yawPitchGlobal = true;
        gs.rollGlobal = true;
        gs.ypAbsolute = true;
        gs.rollAbsolute = true;
        p.sections.add(gs);
        c.pieces.add(p);
        return c;
    }

    static Coaster scFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(3, 18, -2, 25);
        StraightCurveSection sc = StraightCurveSection.straight(8, 24);
        StraightCurveSection.Element arc =
            StraightCurveSection.Element.curve(20, 75, 0, 3);   // R20 75° left (v20.0 κ-form)
        arc.roll.installNodes(BezierBaker.holdLane(0, arc.L, 28));   // absolute bank 28° (v20.1 lane)
        arc.roll.setFlags(true, true, true);
        sc.elements.add(arc);
        StraightCurveSection.Element arc2 =
            StraightCurveSection.Element.curve(30, -40, 45, 2);
        arc2.roll.installNodes(BezierBaker.holdLane(0, arc2.L, 15));   // local °/s 15, CUT (a shadow)
        arc2.roll.setFlags(false, false, false);
        arc2.setLength(arc2.L * 0.6);   // the copy must carry the shadow + fingerprint
        sc.elements.add(arc2);
        p.sections.add(sc);
        c.pieces.add(p);
        return c;
    }

    static Coaster inflFixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection fs = new ForceSection(forceLanes(), 26);
        p.sections.add(fs);
        c.pieces.add(p);
        Coaster.enterInfluence(fs, 6);
        fs.deltaLanes.set(1, lane(new double[][]{ {0, 0}, {2, 0},
            {3, 9}, {4, 0}, {6, 0} }));
        return c;
    }

    static Coaster tiltFixture() {
        Coaster c = forceFixture();
        TrackPiece p = c.pieces.get(0);
        p.rollDeg = 20;          // true tier
        p.designRollDeg = 0;     // design tier ⇒ conflict tilt
        p.pitchDeg = 4;
        p.designPitchDeg = 4;
        return c;
    }

    /** Strict-seam run: variant 0 = pure link (nothing held, no
     *  smoothing), 1 = held pitch + a live design tier on the
     *  follower, 2 = variant 1 plus a 4 m smoothed seam. */
    static Coaster strictFixture(int variant) {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(forceLanes(), 26));
        List<List<BezierBaker.Node>> l2 = new ArrayList<>();
        l2.add(lane(new double[][]{ {0, 1}, {2, 1.6}, {4, 1} }));
        l2.add(lane(new double[][]{ {0, 0.2}, {2, -0.3}, {4, 0} }));
        l2.add(lane(new double[][]{ {0, 0}, {2, 10}, {4, 0} }));
        p.sections.add(new ForceSection(l2, 23));
        c.pieces.add(p);
        int f = c.makeSeamStrict(0, 1, DT);
        if (f != 1) throw new IllegalStateException("makeSeamStrict " + f);
        TrackPiece fol = c.pieces.get(1);
        if (variant >= 1) {
            fol.sPitchDeg += 6;         // held pitch tilts the follow contract
            fol.designPitchDeg = 2;     // a live design tier (D247)
        }
        if (variant >= 2) fol.sSmoothLen = 4;   // a smoothed seam copies too
        return c;
    }

    static Coaster bridgeFixture() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(forceLanes(), 26));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(150, 16, 20, 15);
        List<List<BezierBaker.Node>> lb = new ArrayList<>();
        lb.add(lane(new double[][]{ {0, 1}, {1.5, 1.4}, {3, 1} }));
        lb.add(lane(new double[][]{ {0, 0}, {1.5, 0.3}, {3, 0} }));
        lb.add(lane(new double[][]{ {0, 0}, {1.5, 5}, {3, 0} }));
        b.sections.add(new ForceSection(lb, 24));
        c.pieces.add(b);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(r0.spans().get(0).exit(),
            b.entryPose(), r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_POS_YAW;
        a.sections.add(bs);
        c.optimizeBridge(bs, 1, 40, true);
        return c;
    }

    static Coaster orphanFixture() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(forceLanes(), 26));
        c.pieces.add(a);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        TrackPose exit = r0.spans().get(0).exit();
        TrackPose tgt = new TrackPose(exit.pos().add(new Vec3(30, -4, 6)),
            exit.orient());
        BridgeSection bs = BridgeSection.solve(exit, tgt,
            r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_NONE;
        a.sections.add(bs);
        return c;
    }

    // ---------------- the per-fixture battery ----------------

    static void runCase(String name, Coaster c) throws Exception {
        runCase(name, c, BITWISE);
    }

    static void runCase(String name, Coaster c, int mode) throws Exception {
        TrackCompiler.Result r0 = rebuild(c);
        int nPieces = c.pieces.size();
        int nSpans = r0.spans().size();
        double[] vd0 = vDesigns(c);
        boolean[] isBridge = bridgeSpans(c);
        int nh = c.copyRun(0, nPieces - 1, Coaster.COPY_DZ);
        check(nh == nPieces && c.pieces.size() == 2 * nPieces,
            name + "1 copyRun appends the run at the list end",
            "head " + nh + ", " + c.pieces.size() + " pieces");
        TrackCompiler.Result r1 = rebuild(c);
        String orig = identical(r0.samples(), 0, r1.samples(), 0,
            r0.samples().size());
        check(orig == null, name + "2 original run unchanged bitwise",
            orig == null ? r0.samples().size() + " samples" : orig);
        boolean[] isBridge2 = bridgeSpans(c);
        String cmp = copyCompare(r1, nSpans, isBridge2, Coaster.COPY_DZ, mode);
        check(cmp == null, name + "3 copy == source "
            + (mode == BITWISE ? "bitwise, z + 5"
                : mode == POS_DUST ? "(felt/frame bitwise, pos to 1e-9)"
                : "(to 1e-9)"),
            cmp == null ? "" : cmp);
        // no reconcile / re-optimize writes: vDesign untouched on
        // both halves, and a second rebuild is a bitwise no-op
        double[] vd1 = vDesigns(c);
        boolean vOk = vd1.length == 2 * vd0.length;
        for (int i = 0; vOk && i < vd0.length; i++) {
            vOk = bits(vd0[i], vd1[i]) && bits(vd0[i], vd1[i + vd0.length]);
        }
        check(vOk, name + "4 every vDesign untouched (no reconcile writes)", "");
        TrackCompiler.Result r2 = rebuild(c);
        String again = identical(r1.samples(), 0, r2.samples(), 0,
            r1.samples().size());
        check(again == null, name + "5 second rebuild is a bitwise no-op",
            again == null ? "" : again);
        // save → load → rebuild reproduces the copied document bitwise
        Coaster rl = roundTrip(c);
        TrackCompiler.Result r3 = rebuild(rl);
        String io = identical(r1.samples(), 0, r3.samples(), 0,
            r1.samples().size());
        check(io == null && r3.samples().size() == r1.samples().size(),
            name + "6 save→load→rebuild bitwise", io == null ? "" : io);
        // the copy's links: head detached, interior links verbatim
        TrackPiece head = c.pieces.get(nh);
        boolean links = !head.followPrev;
        for (int p = 1; p < nPieces; p++) {
            links &= c.pieces.get(nh + p).followPrev
                == c.pieces.get(p).followPrev;
        }
        check(links, name + "7 head followPrev false, interior links copied", "");
        // placement fields: x/y/yaw/tiers bitwise, z + dz exactly
        boolean place = isBridge.length == nSpans;
        for (int p = 0; p < nPieces; p++) {
            TrackPiece s = c.pieces.get(p), t = c.pieces.get(nh + p);
            place &= bits(s.x, t.x) && bits(s.y, t.y) && bits(s.z + Coaster.COPY_DZ, t.z)
                && bits(s.yawDeg, t.yawDeg) && bits(s.pitchDeg, t.pitchDeg)
                && bits(s.rollDeg, t.rollDeg)
                && bits(s.designPitchDeg, t.designPitchDeg)
                && bits(s.designRollDeg, t.designRollDeg)
                && s.orientMode == t.orientMode                           // D249
                && s.affectSubsequentSeams == t.affectSubsequentSeams   // D248/D249
                && s.baseOrient == t.baseOrient;
        }
        check(place, name + "8 piece placement fields copied, z + 5 exact", "");
    }

    public static void main(String[] args) throws Exception {
        runCase("a", forceFixture());
        runCase("b", scFixture());
        runCase("c", inflFixture());
        runCase("d", tiltFixture(), POS_DUST);
        runCase("e", strictFixture(0));
        runCase("e'", strictFixture(1), POS_DUST);
        runCase("e\"", strictFixture(2), ALL_DUST);
        runCase("f", bridgeFixture());

        // ---------- (g) the orphan-bridge refusal ----------
        Coaster g = orphanFixture();
        TrackCompiler.Result rg0 = rebuild(g);
        int ng = g.copyRun(0, 0, Coaster.COPY_DZ);
        TrackCompiler.Result rg1 = rebuild(g);
        String gs = identical(rg0.samples(), 0, rg1.samples(), 0,
            rg0.samples().size());
        check(ng == -1 && g.pieces.size() == 1 && gs == null
                && rg1.samples().size() == rg0.samples().size(),
            "g1 orphan-bridge run refuses (−1), document untouched",
            "ret " + ng + ", " + g.pieces.size() + " pieces"
                + (gs == null ? "" : ", " + gs));
        check(g.copyRun(-1, 0, 5) == -1 && g.copyRun(1, 0, 5) == -1
                && g.copyRun(0, 1, 5) == -1,
            "g2 bad ranges refuse (−1)", "");
        // the LIST-end test (D247 review fix): [A detached, B ending in
        // an orphan bridge] — copying A's run would append A' right
        // after B, and B's bridge would seize A' as its target
        Coaster g3 = orphanFixture();
        TrackPiece det = new TrackPiece(80, 15, 40, 30);
        det.sections.add(new ForceSection(forceLanes(), 22));
        g3.pieces.add(0, det);   // [A detached, B + orphan bridge]
        TrackCompiler.Result rg30 = rebuild(g3);
        int ng3 = g3.copyRun(0, 0, Coaster.COPY_DZ);
        TrackCompiler.Result rg31 = rebuild(g3);
        String g3s = identical(rg30.samples(), 0, rg31.samples(), 0,
            rg30.samples().size());
        check(ng3 == -1 && g3.pieces.size() == 2 && g3s == null
                && rg31.samples().size() == rg30.samples().size()
                && g3.listEndsInOrphanBridge(),
            "g3 any run refuses (−1) while the LIST end is an orphan bridge, "
            + "document untouched",
            "ret " + ng3 + ", " + g3.pieces.size() + " pieces"
                + (g3s == null ? "" : ", " + g3s));
        check(!forceFixture().listEndsInOrphanBridge(),
            "g4 a plain force piece is no orphan-bridge list end", "");

        // ---------- (h) object independence + session flags ----------
        Coaster h = inflFixture();
        ForceSection src = (ForceSection) h.pieces.get(0).sections.get(0);
        src.editInfluence = true;
        src.viewOtherDomain = true;
        src.viewOtherRate = true;
        SpeedControl ctl = new SpeedControl(1.0, 1.2);
        ctl.speed = 22;
        src.speedCtls.add(ctl);
        src.speedMarks.add(4.0);
        rebuild(h);
        int nh = h.copyRun(0, 0, Coaster.COPY_DZ);
        ForceSection cp = (ForceSection) h.pieces.get(nh).sections.get(0);
        boolean distinct = cp != src && cp.lanes != src.lanes
            && cp.deltaLanes != src.deltaLanes
            && cp.speedCtls != src.speedCtls
            && cp.speedCtls.get(0) != src.speedCtls.get(0)
            && cp.speedMarks != src.speedMarks;
        for (int k = 0; k < 3; k++) {
            distinct &= cp.lanes.get(k) != src.lanes.get(k)
                && cp.deltaLanes.get(k) != src.deltaLanes.get(k);
            for (int i = 0; i < src.lanes.get(k).size(); i++) {
                distinct &= cp.lanes.get(k).get(i) != src.lanes.get(k).get(i);
            }
        }
        check(distinct, "h1 copied section shares no list/node/control object", "");
        check(!cp.editInfluence && !cp.viewOtherDomain && !cp.viewOtherRate
                && cp.baseCurves == null && cp.influenced()
                && bits(cp.vAuthored, src.vAuthored)
                && cp.speedMarks.get(0) == 4.0 && cp.speedCtls.get(0).speed == 22,
            "h2 session flags reset, influence + controls + marks carried", "");
        // s/c independence + the tilt/seam fields on a strict run
        Coaster hs = strictFixture(2);
        rebuild(hs);
        int nhs = hs.copyRun(0, 1, Coaster.COPY_DZ);
        TrackPiece f0 = hs.pieces.get(1), f1 = hs.pieces.get(nhs + 1);
        check(f1 != f0 && f1.followPrev && f1.sLockPitch && f1.sLockRoll
                && f1.sLockYaw && bits(f1.sPitchDeg, f0.sPitchDeg)
                && bits(f1.sYawDeg, f0.sYawDeg)
                && bits(f1.sRollDeg, f0.sRollDeg)
                && bits(f1.sSmoothLen, f0.sSmoothLen)
                && bits(f1.designPitchDeg, f0.designPitchDeg)
                && f1.canonOrient == f0.canonOrient
                && bits(f1.canonPsiDeg, f0.canonPsiDeg)
                && bits(f1.z, f0.z + Coaster.COPY_DZ),
            "h3 follower copy carries seam fields, tiers, canonical pair", "");
        Coaster hb = bridgeFixture();
        rebuild(hb);
        int nhb = hb.copyRun(0, 1, Coaster.COPY_DZ);
        BridgeSection b0 = (BridgeSection) hb.pieces.get(0).sections.get(1);
        BridgeSection b1 = (BridgeSection) hb.pieces.get(nhb).sections.get(1);
        check(b1 != b0 && b1.optSig == null && b1.noneStash == null
                && b0.optSig != null
                && b1.optMode == b0.optMode && bits(b1.lastOptDist, b0.lastOptDist)
                && b1.lockYaw == b0.lockYaw && b1.lockPitch == b0.lockPitch
                && b1.lockRoll == b0.lockRoll
                && Math.abs(b1.solvedEntry.pos().z()
                    - b0.solvedEntry.pos().z() - Coaster.COPY_DZ) < 1e-12,
            "h4 copied bridge: optSig null (first sight), poses +dz, request kept", "");
        Coaster hc = scFixture();
        rebuild(hc);
        int nhc = hc.copyRun(0, 0, Coaster.COPY_DZ);
        StraightCurveSection s0 = (StraightCurveSection) hc.pieces.get(0).sections.get(0);
        StraightCurveSection s1 = (StraightCurveSection) hc.pieces.get(nhc).sections.get(0);
        boolean scOk = s1 != s0 && s1.elements != s0.elements
            && s1.elements.size() == s0.elements.size();
        for (int i = 0; scOk && i < s0.elements.size(); i++) {
            StraightCurveSection.Element a = s0.elements.get(i), b = s1.elements.get(i);
            scOk = b != a && bits(b.L, a.L) && b.roll != a.roll && b.roll.nodes != a.roll.nodes
                && laneSame(a.roll, b.roll);
        }
        check(scOk, "h5 s/c copy: new element + lane objects, lane values, flags, shadow + fingerprint verbatim (element 1 in a cut state)", "");

        // ---------- (k) v20.1, D259 §3: copyRun carries the AUTOMATIC seam ----------
        {
            Coaster k = new Coaster();
            TrackPiece kp = new TrackPiece(0, 21, 0, 0);
            kp.sections.add(new ForceSection(forceLanes(), 22));
            k.pieces.add(kp);
            TrackCompiler.Result kr0 = rebuild(k);
            k.appendSectionWithInvariant(0, StraightCurveSection.straight(9,
                kr0.samples().get(kr0.spans().get(0).i1()).v()), Coaster.INTEGRATE_DT);
            TrackCompiler.Result kr = rebuild(k);
            int nk = k.copyRun(0, 1, Coaster.COPY_DZ, kr.spans());   // the orphan-guard overload
            TrackCompiler.Result kr1 = rebuild(k);
            int n = kr1.spans().get(1).i1() + 1;
            String same = identical(kr1.samples(), 0, kr1.samples(), kr1.spans().get(2).i0(), n);
            // identical() compares positions too — the copy sits 5 m along +z, so the
            // frames / speeds are compared bitwise here and the positions as a translation
            double worst = 0;
            boolean fr = true;
            for (int i = 0; i < n; i++) {
                FVDIntegrator.Sample a = kr1.samples().get(i);
                FVDIntegrator.Sample b = kr1.samples().get(kr1.spans().get(2).i0() + i);
                fr &= Double.doubleToLongBits(a.T().x()) == Double.doubleToLongBits(b.T().x())
                    && Double.doubleToLongBits(a.T().y()) == Double.doubleToLongBits(b.T().y())
                    && Double.doubleToLongBits(a.T().z()) == Double.doubleToLongBits(b.T().z())
                    && Double.doubleToLongBits(a.U().x()) == Double.doubleToLongBits(b.U().x())
                    && Double.doubleToLongBits(a.U().y()) == Double.doubleToLongBits(b.U().y())
                    && Double.doubleToLongBits(a.U().z()) == Double.doubleToLongBits(b.U().z())
                    && Double.doubleToLongBits(a.v()) == Double.doubleToLongBits(b.v())
                    && Double.doubleToLongBits(kr1.trueV()[i])
                        == Double.doubleToLongBits(kr1.trueV()[kr1.spans().get(2).i0() + i]);
                worst = Math.max(worst,
                    a.pos().add(new Vec3(0, 0, Coaster.COPY_DZ)).sub(b.pos()).length());
            }
            check(nk == 2 && k.pieces.size() == 4 && k.autoFollower(3) && k.pieces.get(3).sAuto
                    && !k.pieces.get(2).followPrev && k.scInvariantHolds() && fr && worst <= 1e-9,
                "k1 copyRun of [force] | automatic [sc] (the orphan-guard overload): the copied"
                + " follower is automatic (sAuto carried, the head normalized), frames / speeds /"
                + " true speeds BITWISE the source's, positions the +5 z translation ≤ 1e-9",
                String.format("worst %.2e m", worst) + (same == null ? " (positions equal too)" : ""));
        }

        System.out.println();
        System.out.println(fail == 0
            ? "ALL COPY GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
