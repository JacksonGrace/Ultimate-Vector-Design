import coaster.*;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase-3 gate (friction v17.0, D133 + reverse-as-convert D134).
 * Run against target/classes:
 *   javac -cp target/classes -d <scratch> Phase3Gate.java
 *   java  -cp "target/classes;<scratch>" Phase3Gate
 *
 * Friction-OFF bitwise safety is carried by Phase2Gate + DomainGate
 * staying green — every historic fixture compiles with coefficients 0.
 * This file gates the NEW behavior:
 *   48a  rolling closed form   v² = v0² − 2·mu·g·s on flat track
 *   48b  air-drag closed form  v  = v0·e^(−k·s) on flat track
 *   48c  Pass D == design profile on an undrifted hilly section
 *   48d  chain reconcile settles design == true under friction
 *   48e  s/c spine is friction-invariant (RATE-0 exact; GLOBAL-0 to
 *        the projection-cadence bound)
 *   48f  REVERSE PIECE = a convert (D134): same section structure
 *        mirrored, all forward, entry speed = the recorded end
 *        speed, NO speed conflict, shape held at matched arc —
 *        friction on end to end; bridge pieces refuse
 *   48g  custom entry speed honored; shape independent of it
 *   48h  s/c piece reverses into force lanes, spine held
 *   48i  legacy reversed-flag piece reverses to plain forward;
 *        double reversal lands back on the original shape
 *   48j  FVDD3: friction line round-trips; legacy text = zero
 *        friction
 *   48k  converts stay honest under friction (whole force→geo)
 */
public class Phase3Gate {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** Flat force lanes: Gv = 1 (level track), Gl = 0, roll = 0. */
    static List<List<BezierBaker.Node>> flatLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{ {0, 1}, {dur, 1} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return lanes;
    }

    /** Gentle hills: Gv wave (0.6..1.4), zero lateral/roll. */
    static List<List<BezierBaker.Node>> hillLanes(double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0, 1}, {dur * 0.2, 0.6}, {dur * 0.5, 1.4},
            {dur * 0.8, 0.6}, {dur, 1}}));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return lanes;
    }

    /** Constant-rate geo lanes {pitch, yaw, roll} °/s. */
    static List<List<BezierBaker.Node>> geoLanes(double p, double y,
                                                 double r, double dur) {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{ {0, p}, {dur, p} }));
        lanes.add(nodes(new double[][]{ {0, y}, {dur, y} }));
        lanes.add(nodes(new double[][]{ {0, r}, {dur, r} }));
        return lanes;
    }

    static Coaster oneForce(List<List<BezierBaker.Node>> lanes, double v0) {
        Coaster c = new Coaster();
        c.v0 = v0;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(lanes, v0));
        c.pieces.add(p);
        return c;
    }

    static TrackCompiler.Result settle(Coaster c, double dt) {
        TrackCompiler.Result r = TrackCompiler.compile(c, dt);
        for (int i = 0; i < 6 && c.reconcileChainedSpeeds(r); i++) {
            r = TrackCompiler.compile(c, dt);
        }
        return r;
    }

    /** Worst design-vs-true split over the whole ride. */
    static double worstSplit(TrackCompiler.Result r) {
        double worst = 0;
        for (int i = 0; i < r.samples().size(); i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    /** Worst distance between the post-reversal ride and the ORIGINAL
     *  track, compared at matched arc from opposite ends (post arc u
     *  ↔ pre arc S − u; both interpolated linearly). */
    static double mirrorShapeDev(List<FVDIntegrator.Sample> pre,
                                 List<FVDIntegrator.Sample> post) {
        double S = pre.get(pre.size() - 1).s() - pre.get(0).s();
        double s0Post = post.get(0).s();
        double worst = 0;
        int lo = pre.size() - 2;
        for (FVDIntegrator.Sample sm : post) {
            double want = pre.get(0).s() + S - (sm.s() - s0Post);
            while (lo > 0 && pre.get(lo).s() > want) lo--;
            while (lo + 2 < pre.size() && pre.get(lo + 1).s() < want) lo++;
            FVDIntegrator.Sample a = pre.get(lo), b = pre.get(lo + 1);
            double ds = b.s() - a.s();
            double f = ds < 1e-12 ? 0
                : Math.max(0, Math.min(1, (want - a.s()) / ds));
            Vec3 p = a.pos().add(b.pos().sub(a.pos()).scale(f));
            worst = Math.max(worst, sm.pos().sub(p).length());
        }
        return worst;
    }

    static double G = FVDIntegrator.G0;

    public static void main(String[] args) {
        closedForms();
        passDMatch();
        chainReconcile();
        scInvariance();
        reverseConvert();
        reverseCustomSpeed();
        reverseScPiece();
        reverseLegacyAndTwice();
        ioRoundTrip();
        convertUnderFriction();
        strictFreeze();
        strictRelock();
        strictUnstrict();
        strictBridgeConvert();
        strictIo();
        strictAttachForeign();
        strictResolve();
        entryCleanConvert();
        globalHoldContinuity();
        makeRealAfterStrict();
        smoothLocality();
        smoothCollapse();
        smoothIo();
        makeRealUnderSmoothing();
        endLanding();
        bridgeSeamSmooth();
        crestBridge();
        bankedCrestBridge();
        speedControlGates();
        rollOnlyConvert();

        System.out.println();
        System.out.println(failures == 0 ? "ALL PHASE-3 GATES PASS"
            : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }


    // ========== v17.10: END LANDING (D147) + BRIDGE SEAMS (D148) =========

    /** 52a-c: the closing integrator step lands EXACTLY on the authored
     *  end (D147 — the old loop refused it on float overshoot, losing
     *  up to one dt of profile per seam), so split-at-node == unsplit. */
    static void endLanding() {
        double dt = 0.01;
        Coaster a1 = oneForce(hillLanes(5), 24);
        TrackCompiler.Result ra1 = TrackCompiler.compile(a1, dt);
        check(ra1.samples().get(ra1.samples().size() - 1).t() == 5.0,
            "52a time-section last sample lands exactly on the authored end");

        Coaster a2 = oneForce(hillLanes(5), 24);
        boolean ok = a2.splitSectionAt(0, 0, 2.13, dt);
        TrackCompiler.Result ra2 = TrackCompiler.compile(a2, dt);
        double dHill = ra1.samples().get(ra1.samples().size() - 1).pos()
            .sub(ra2.samples().get(ra2.samples().size() - 1).pos()).length();
        check(ok && dHill < 0.02, String.format(
            "52b split-at-node vs unsplit end gap %.4f m (bake-grid noise "
            + "only; was 0.24 m on the ramp fixture pre-D147)", dHill));

        // the user's exact case: a node inserted on an ANGLED (linear)
        // stretch — bake is exact there, so the split must be exact
        Coaster b1 = oneForce(hillLanes(5), 24), b2 = oneForce(hillLanes(5), 24);
        List<BezierBaker.Node> l0a = b1.pieces.get(0).sections.get(0)
            instanceof LaneSection ls1 ? ls1.lanes.get(0) : null;
        // straighten lane 0 into one angled line 1 -> 1.4 over 5 s
        for (Coaster cc : new Coaster[]{ b1, b2 }) {
            LaneSection ls = (LaneSection) cc.pieces.get(0).sections.get(0);
            List<BezierBaker.Node> l = nodes(new double[][]{ {0,1},{5,1.4} });
            double slope = 0.4 / 5;
            l.get(0).outDv = slope * l.get(0).outFrac * 5;
            l.get(1).inDv = -slope * l.get(1).inFrac * 5;
            ls.lanes.set(0, l);
        }
        TrackCompiler.Result rb1 = TrackCompiler.compile(b1, dt);
        b2.splitSectionAt(0, 0, 1.618, dt);
        TrackCompiler.Result rb2 = TrackCompiler.compile(b2, dt);
        double dRamp = rb1.samples().get(rb1.samples().size() - 1).pos()
            .sub(rb2.samples().get(rb2.samples().size() - 1).pos()).length();
        check(dRamp < 1e-4, String.format(
            "52c split on an angled (linear) stretch is exact (%.2e m)",
            dRamp));
    }

    /** 53a-c (REWRITTEN v17.11, D150 — the v17.10 hump/gate drafts
     *  were REVERTED on user verdict): 53a/b re-pin the roll-free
     *  fixture at the SHIPPED parabola-hump behavior; 53c pins the
     *  REAL fix — on ROLLING seams with locked roll (the user's
     *  split-and-rebridge file), the residual twist now rides the
     *  march, so the far-seam felt is continuous and the spine lands
     *  on its own placement at every length (pre-fix: ~0.5-1.2 g
     *  far-seam Gv/Gl jumps, or a permanent septic fallback). */
    static void bridgeSeamSmooth() {
        double dt = 0.01;
        Coaster c = oneForce(hillLanes(5), 24);
        TrackPiece p2 = new TrackPiece(120, 16, -30, 40);
        p2.sections.add(new ForceSection(hillLanes(4), 21));
        c.pieces.add(p2);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, dt);
        BridgeSection bs = BridgeSection.solve(
            r0.spans().get(0).exit(), p2.entryPose(), 24);
        bs.optMode = BridgeSection.OPT_POS;
        c.pieces.get(0).sections.add(bs);

        double worstKink = 0, worstSlope = 0;
        for (double d = 8; d <= 56; d += 16) {
            c.optimizeBridge(bs, 1, d, false);
            TrackCompiler.Result r = TrackCompiler.compile(c, dt);
            for (int i = 0; i < 4; i++) {
                boolean a = c.autoReoptimizeBridges(r);
                boolean b = c.reconcileChainedSpeeds(r);
                if (!a && !b) break;
                r = TrackCompiler.compile(c, dt);
            }
            for (int ib : new int[]{ r.spans().get(1).i0(),
                                     r.spans().get(2).i0() }) {
                double[] g = seamThetaGap(r.samples(), ib);
                worstKink = Math.max(worstKink, g[0]);
                if (d > 8) worstSlope = Math.max(worstSlope, g[1]);
            }
        }
        check(worstKink < 0.1, String.format(
            "53a roll-free march bridge: no seam value kinks (worst "
            + "%.4f deg/m; zero-end-slope correction weight, D153)",
            worstKink));
        check(worstSlope < 0.6, String.format(
            "53b roll-free march bridge: seam slope breaks at the "
            + "septic floor (worst %.3f deg/m2, ref 0.50 — D153: the "
            + "corrections carry zero end slope)", worstSlope));

        // 53c: the USER'S bug class — rolling seams, roll LOCKED.
        // Piece 2's entry lanes continue piece 1's end state (split
        // twins), placed away; OPT_POS_YAW at several lengths.
        Coaster cr = new Coaster();
        cr.v0 = 26;
        TrackPiece q1 = new TrackPiece(0, 24, 0, 0);
        List<List<BezierBaker.Node>> l1 = new ArrayList<>();
        l1.add(nodes(new double[][]{ {0, 1}, {2, 2.0}, {4, 2.0} }));
        l1.add(nodes(new double[][]{ {0, 0}, {4, -0.2} }));
        l1.add(nodes(new double[][]{ {0, 0}, {2, -52}, {4, -52} }));
        q1.sections.add(new ForceSection(l1, 26));
        cr.pieces.add(q1);
        TrackPiece q2 = new TrackPiece(60, 15, 20, 30);
        List<List<BezierBaker.Node>> l2 = new ArrayList<>();
        l2.add(nodes(new double[][]{ {0, 2.0}, {2, 1} }));
        l2.add(nodes(new double[][]{ {0, -0.2}, {2, 0} }));
        l2.add(nodes(new double[][]{ {0, -52}, {2, 0} }));
        q2.sections.add(new ForceSection(l2, 26));
        cr.pieces.add(q2);
        TrackCompiler.Result rr = TrackCompiler.compile(cr, dt);
        BridgeSection bsr = BridgeSection.solve(
            rr.spans().get(0).exit(), q2.entryPose(), 26);
        bsr.optMode = BridgeSection.OPT_POS_YAW;   // yaw free,
        cr.pieces.get(0).sections.add(bsr);        // pitch+roll locked
        double worstFelt = 0, worstMiss = 0;
        boolean allMarch = true;
        for (double d = 16; d <= 32; d += 8) {
            cr.optimizeBridge(bsr, 1, d, true);
            TrackCompiler.Result r = TrackCompiler.compile(cr, dt);
            for (int i = 0; i < 4; i++) {
                boolean a = cr.autoReoptimizeBridges(r);
                boolean b = cr.reconcileChainedSpeeds(r);
                if (!a && !b) break;
                r = TrackCompiler.compile(cr, dt);
            }
            boolean march;
            try {
                java.lang.reflect.Method m = Coaster.class
                    .getDeclaredMethod("marchSpine",
                        BridgeSection.class, boolean.class);
                m.setAccessible(true);
                march = m.invoke(null, bsr, true) != null;
            } catch (Exception e) { march = false; }
            allMarch &= march;
            int ib = r.spans().get(2).i0();
            worstFelt = Math.max(worstFelt, Math.max(
                Math.abs(r.samples().get(ib).Gv()
                    - r.samples().get(ib - 1).Gv()),
                Math.abs(r.samples().get(ib).Gl()
                    - r.samples().get(ib - 1).Gl())));
            worstMiss = Math.max(worstMiss,
                r.samples().get(ib).pos().sub(
                    bsr.solvedTarget.pos()).length() > 1e9 ? 0 : 0);
        }
        check(allMarch && worstFelt < 0.05, String.format(
            "53c ROLLING seams, roll locked: march renders and the "
            + "far-seam felt is continuous at every length (worst "
            + "%.4f g; pre-D150 ~0.5-1.2 g or septic fallback)",
            worstFelt));
    }

    /** 54a-d (v17.14, D155 — the user's "Odd bridge behavior" crest
     *  files): completing the TOP of a hill — BOTH seam curvatures
     *  point UP while the net pitch change points DOWN, so the pure
     *  blend overshoots past vertical. Pre-fix, the asin/bank/heading
     *  measures were branch-blind there: the hump fixed-point
     *  converged onto reversed/looped branches, the twist walked to
     *  a full 360° barrel roll (minUy −0.99 at every mid length,
     *  the user's "it would make a roll"), and the knee ran to 88 m
     *  ("distance optimization way too big"). Post-fix: the march
     *  renders at every length, never crosses a pole, never rolls,
     *  places deterministically (yaw spread ~0), keeps the far seam
     *  felt-continuous, and the knee lands in a sane range. */
    static void crestBridge() {
        double dt = 0.01;
        Coaster c = new Coaster();
        c.v0 = 26;
        TrackPiece p1 = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> l1 = new ArrayList<>();
        l1.add(nodes(new double[][]{
            {0, 1}, {1, 1}, {1.56, 3.6}, {1.68, 3.6} }));
        l1.add(nodes(new double[][]{ {0, 0}, {1, 0} }));
        l1.add(nodes(new double[][]{ {0, 0}, {1, 0} }));
        p1.sections.add(new ForceSection(l1, 26));
        c.pieces.add(p1);
        // target: entry pitched −5° (already over the crest) while
        // its own first curvature PULLS UP — the file-1 seam pair
        TrackPiece p2 = new TrackPiece(60, 30, 14, 0);
        p2.baseOrient = Quat.aboutAxis(new Vec3(0, 0, 1),
            Math.toRadians(-5));
        List<List<BezierBaker.Node>> l2 = new ArrayList<>();
        l2.add(nodes(new double[][]{ {0, 2.2}, {1, 2.2}, {2, 1} }));
        l2.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        l2.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        p2.sections.add(new ForceSection(l2, 23));
        c.pieces.add(p2);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, dt);
        BridgeSection bs = BridgeSection.solve(
            r0.spans().get(0).exit(), p2.entryPose(), 25);
        bs.optMode = BridgeSection.OPT_POS_YAW;   // yaw free,
        c.pieces.get(0).sections.add(bs);         // pitch+roll locked
        boolean allMarch = true, allUp = true, noCross = true;
        double yawMin = 1e9, yawMax = -1e9, worstFelt = 0, rollDev = 0;
        for (double d : new double[]{ 15, 30, 45, 60, 80 }) {
            c.optimizeBridge(bs, 1, d, true);
            TrackCompiler.Result r = TrackCompiler.compile(c, dt);
            for (int i = 0; i < 4; i++) {
                boolean a = c.autoReoptimizeBridges(r);
                boolean b = c.reconcileChainedSpeeds(r);
                if (!a && !b) break;
                r = TrackCompiler.compile(c, dt);
            }
            try {
                java.lang.reflect.Method m = Coaster.class
                    .getDeclaredMethod("marchSpine",
                        BridgeSection.class, boolean.class);
                m.setAccessible(true);
                allMarch &= m.invoke(null, bs, true) != null;
            } catch (Exception e) { allMarch = false; }
            int iA = r.spans().get(1).i0(), iB = r.spans().get(2).i0();
            double hx = r.samples().get(iA).T().x(),
                   hz = r.samples().get(iA).T().z();
            for (int i = iA; i <= iB && i < r.samples().size(); i++) {
                Vec3 T = r.samples().get(i).T();
                double h2 = T.x() * T.x() + T.z() * T.z();
                if (h2 > 1e-18) {
                    if (hx * T.x() + hz * T.z() < 0) noCross = false;
                    hx = T.x();
                    hz = T.z();
                }
                allUp &= r.samples().get(i).U().y() > 0.5;
            }
            yawMin = Math.min(yawMin, c.pieces.get(1).yawDeg);
            yawMax = Math.max(yawMax, c.pieces.get(1).yawDeg);
            rollDev = Math.max(rollDev,
                Math.abs(c.pieces.get(1).rollDeg));
            worstFelt = Math.max(worstFelt, Math.max(
                Math.abs(r.samples().get(iB).Gv()
                    - r.samples().get(iB - 1).Gv()),
                Math.abs(r.samples().get(iB).Gl()
                    - r.samples().get(iB - 1).Gl())));
        }
        check(allMarch && noCross, String.format(
            "54a crest bridge: march renders at every length and "
            + "never pitches past vertical (march %b, pole-free %b)",
            allMarch, noCross));
        check(allUp && rollDev < 0.01, String.format(
            "54b crest bridge stays upright — no corkscrew (min U.y "
            + "> 0.5 everywhere, target rollDeg dev %.4f; pre-D155 "
            + "the twist walked to a 360° barrel roll)", rollDev));
        check(yawMax - yawMin < 3 && worstFelt < 0.05, String.format(
            "54c crest placement deterministic + far seam continuous "
            + "(yaw spread %.2f deg, worst felt step %.3f g)",
            yawMax - yawMin, worstFelt));
        double knee = c.optimalBridgeLength(bs, true);
        check(knee >= 4 && knee <= 64, String.format(
            "54d crest knee in a sane range: %.1f m (gap ~46 m; was "
            + "88 m when looped branches scored as smooth)", knee));
    }

    /** Bank vs horizon of a frame (rad) — the gate-local twin of the
     *  package-private FVDIntegrator.bankOfTU. */
    static double bankOf(Vec3 T, Vec3 U) {
        Vec3 u0 = new Vec3(-T.x() * T.y(), 1 - T.y() * T.y(),
            -T.z() * T.y());
        double len = u0.length();
        if (len < 1e-9) return Double.NaN;
        u0 = u0.scale(1 / len);
        return Math.atan2(u0.cross(U).dot(T), u0.dot(U));
    }

    /** 54e-g (v17.15, D156 — the user's report #4): the SAME crest
     *  with BANKED seams (entry piece rolled +19°, target −22°, the
     *  file-4 pair). Pre-fix the pitch↔twist coupling made the ≤6
     *  full-step fixed point diverge past ~45-51 m — clean below, an
     *  unconverged march + septic balloon immediately above (the
     *  cliff), and the twist-0-started rescue draft converged onto a
     *  wrong-winding corkscrew (end bank exact, mid-span inverted).
     *  Post-fix (damping + seeded staged rescue + bank render gate):
     *  the march renders with the bank CLOSED and the track upright
     *  through ~1.5× the gap, and nothing that renders ever carries
     *  an open bank. */
    static void bankedCrestBridge() {
        double dt = 0.01;
        Coaster c = new Coaster();
        c.v0 = 26;
        TrackPiece p1 = new TrackPiece(0, 21, 0, 0);
        p1.rollDeg = 19;
        List<List<BezierBaker.Node>> l1 = new ArrayList<>();
        l1.add(nodes(new double[][]{
            {0, 1}, {1, 1}, {1.56, 3.6}, {1.68, 3.6} }));
        l1.add(nodes(new double[][]{ {0, 0}, {1, 0} }));
        l1.add(nodes(new double[][]{ {0, 0}, {1, 0} }));
        p1.sections.add(new ForceSection(l1, 26));
        c.pieces.add(p1);
        TrackPiece p2 = new TrackPiece(60, 30, 14, 0);
        p2.baseOrient = Quat.aboutAxis(new Vec3(0, 0, 1),
            Math.toRadians(-5));
        p2.rollDeg = -22;
        List<List<BezierBaker.Node>> l2 = new ArrayList<>();
        l2.add(nodes(new double[][]{ {0, 2.2}, {1, 2.2}, {2, 1} }));
        l2.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        l2.add(nodes(new double[][]{ {0, 0}, {2, 0} }));
        p2.sections.add(new ForceSection(l2, 23));
        c.pieces.add(p2);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, dt);
        BridgeSection bs = BridgeSection.solve(
            r0.spans().get(0).exit(), p2.entryPose(), 25);
        bs.optMode = BridgeSection.OPT_POS_YAW;
        c.pieces.get(0).sections.add(bs);
        boolean allMarch = true, allUp = true, bankClosed = true;
        double worstFelt = 0;
        for (double d : new double[]{ 15, 30, 45, 52, 58, 64 }) {
            c.optimizeBridge(bs, 1, d, true);
            TrackCompiler.Result r = TrackCompiler.compile(c, dt);
            for (int i = 0; i < 4; i++) {
                boolean a = c.autoReoptimizeBridges(r);
                boolean b = c.reconcileChainedSpeeds(r);
                if (!a && !b) break;
                r = TrackCompiler.compile(c, dt);
            }
            try {
                java.lang.reflect.Method m = Coaster.class
                    .getDeclaredMethod("marchSpine",
                        BridgeSection.class, boolean.class);
                m.setAccessible(true);
                allMarch &= m.invoke(null, bs, true) != null;
            } catch (Exception e) { allMarch = false; }
            int iA = r.spans().get(1).i0(), iB = r.spans().get(2).i0();
            for (int i = iA; i <= iB && i < r.samples().size(); i++) {
                allUp &= r.samples().get(i).U().y() > 0.2;
            }
            double bEnd = bankOf(r.samples().get(iB - 1).T(),
                r.samples().get(iB - 1).U());
            double bTgt = bankOf(bs.solvedTarget.T(),
                bs.solvedTarget.U());
            if (!Double.isNaN(bEnd) && !Double.isNaN(bTgt)) {
                double g = Math.toDegrees(bTgt - bEnd);
                while (g > 180) g -= 360;
                while (g < -180) g += 360;
                bankClosed &= Math.abs(g) < 2;
            }
            worstFelt = Math.max(worstFelt, Math.max(
                Math.abs(r.samples().get(iB).Gv()
                    - r.samples().get(iB - 1).Gv()),
                Math.abs(r.samples().get(iB).Gl()
                    - r.samples().get(iB - 1).Gl())));
        }
        check(allMarch && bankClosed, String.format(
            "54e banked crest (+19/−22): march renders with the bank "
            + "CLOSED through 64 m (march %b, bank closed %b; pre-D156 "
            + "the cliff sat at ~45-51 m)", allMarch, bankClosed));
        check(allUp && worstFelt < 0.05, String.format(
            "54f banked crest stays upright, far seam continuous "
            + "(min U.y > 0.2, worst felt step %.3f g; the twist-0 "
            + "rescue once converged onto a mid-span-inverted "
            + "corkscrew)", worstFelt));
        // deep-extreme lengths: whatever renders must have its bank
        // closed — the render gate's job (never a corkscrew march)
        boolean deepHonest = true;
        for (double d : new double[]{ 80, 100 }) {
            c.optimizeBridge(bs, 1, d, true);
            TrackCompiler.Result r = TrackCompiler.compile(c, dt);
            for (int i = 0; i < 4; i++) {
                boolean a = c.autoReoptimizeBridges(r);
                boolean b = c.reconcileChainedSpeeds(r);
                if (!a && !b) break;
                r = TrackCompiler.compile(c, dt);
            }
            boolean march;
            try {
                java.lang.reflect.Method m = Coaster.class
                    .getDeclaredMethod("marchSpine",
                        BridgeSection.class, boolean.class);
                m.setAccessible(true);
                march = m.invoke(null, bs, true) != null;
            } catch (Exception e) { march = false; }
            if (march) {
                int iB = r.spans().get(2).i0();
                double bEnd = bankOf(r.samples().get(iB - 1).T(),
                    r.samples().get(iB - 1).U());
                double bTgt = bankOf(bs.solvedTarget.T(),
                    bs.solvedTarget.U());
                if (!Double.isNaN(bEnd) && !Double.isNaN(bTgt)) {
                    double g = Math.toDegrees(bTgt - bEnd);
                    while (g > 180) g -= 360;
                    while (g < -180) g += 360;
                    deepHonest &= Math.abs(g) < 2;
                }
            }
        }
        check(deepHonest,
            "54g deep-extreme lengths (80/100 m): anything that "
            + "renders as a march has its bank closed — the v17.15 "
            + "bank render gate");
    }

    /** Rotation-per-meter kink + slope break at boundary sample ib. */
    static double[] seamThetaGap(List<FVDIntegrator.Sample> s, int ib) {
        double thB2 = thetaPm(s, ib - 3), thB1 = thetaPm(s, ib - 2),
               thB0 = thetaPm(s, ib - 1);
        double thA0 = thetaPm(s, ib), thA1 = thetaPm(s, ib + 1),
               thA2 = thetaPm(s, ib + 2);
        double typ = Math.max(Math.abs(thB1 - thB2),
            Math.max(Math.abs(thB0 - thB1),
            Math.max(Math.abs(thA1 - thA0), Math.abs(thA2 - thA1))));
        double kink = Math.max(0, Math.abs(thA0 - thB0) - typ);
        double slL = (thB0 - thB1) / ((s.get(ib).s() - s.get(ib - 2).s()) / 2);
        double slR = (thA1 - thA0) / ((s.get(ib + 2).s() - s.get(ib).s()) / 2);
        return new double[]{ kink, Math.abs(slR - slL) };
    }

    static double thetaPm(List<FVDIntegrator.Sample> s, int i) {
        Quat qa = Quat.fromFrame(s.get(i).T(), s.get(i).U());
        Quat qb = Quat.fromFrame(s.get(i + 1).T(), s.get(i + 1).U());
        double dot = Math.abs(qa.w() * qb.w() + qa.x() * qb.x()
            + qa.y() * qb.y() + qa.z() * qb.z());
        double ds = Math.max(1e-9, s.get(i + 1).s() - s.get(i).s());
        return Math.toDegrees(2 * Math.acos(Math.min(1, dot))) / ds;
    }

    // ==================== STRICT SEAMS (v17.1, D135) ====================

    /** Local coordinates of a span's samples in its own entry frame —
     *  the shape signature the strict freeze must preserve. */
    static List<Vec3> localShape(TrackCompiler.Result r,
                                 int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i0 = sp.i0(), i1 = Math.min(sp.i1(), r.samples().size() - 1);
        FVDIntegrator.Sample e = r.samples().get(i0);
        List<Vec3> out = new ArrayList<>();
        for (int i = i0; i <= i1; i += 3) {
            Vec3 d = r.samples().get(i).pos().sub(e.pos());
            out.add(new Vec3(d.dot(e.T()), d.dot(e.U()), d.dot(e.R())));
        }
        return out;
    }

    static double shapeDelta(List<Vec3> a, List<Vec3> b) {
        double worst = 0;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, a.get(i).sub(b.get(i)).length());
        }
        return worst + Math.abs(a.size() - b.size()) * 1e9;
    }

    // ---- 49a: make-strict freezes the downstream shape -----------------

    static void strictFreeze() {
        Coaster c = oneForce(hillLanes(6), 24);
        c.pieces.get(0).sections.add(new ForceSection(hillLanes(5), 24));
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        settle(c, 0.01);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        List<Vec3> shape0 = localShape(r0, 1);
        FVDIntegrator.Sample seam0 =
            r0.samples().get(r0.spans().get(1).i0());

        int np = c.makeSeamStrict(0, 1, 0.01);
        check(np == 1 && c.pieces.size() == 2
                && c.pieces.get(1).followPrev,
            "49a make-seam-strict splits + marks the follower");
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        check(shapeDelta(shape0, localShape(r1, 1)) < 1e-6
                && r1.samples().get(r1.spans().get(1).i0()).pos()
                    .sub(seam0.pos()).length() < 1e-6,
            "49a creation moves nothing (shape + seam exact)");

        // EDIT UPSTREAM: raise the lead-in's mid Gv — the downstream
        // must keep its LOCAL shape exactly, only move/re-aim; its
        // design speed stays put, so an honest ≠v conflict opens
        // while the real ride flows through the seam continuously
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(2).val += 0.35;
        TrackCompiler.Result r2 = TrackCompiler.compile(c, 0.01);
        double dShape = shapeDelta(shape0, localShape(r2, 1));
        check(dShape < 1e-6, String.format(
            "49a upstream edit leaves the downstream LOCAL shape frozen "
            + "(delta %.2e m)", dShape));
        int i0 = r2.spans().get(1).i0();
        check(r2.samples().get(i0).pos().sub(
                r2.samples().get(i0 - 1).pos()).length() < 0.5
                && Math.abs(r2.trueV()[i0] - r2.trueV()[i0 - 1]) < 0.1,
            "49a position + REAL speed stay continuous at the seam");
        double split = Math.abs(r2.trueV()[r2.spans().get(1).i1() - 2]
            - r2.samples().get(r2.spans().get(1).i1() - 2).v());
        check(split > 0.05, String.format(
            "49a the follower keeps its design speed — honest conflict "
            + "opens (split %.3f m/s)", split));
        // yaw UNLOCKED: the follower's heading tracks the incoming
        // frame; pitch LOCKED: its entry pitch holds the original
        FVDIntegrator.Sample in = r2.samples().get(i0 - 1);
        FVDIntegrator.Sample fo = r2.samples().get(i0);
        double hIn = Math.atan2(-in.T().z(), in.T().x());
        double hFo = Math.atan2(-fo.T().z(), fo.T().x());
        check(Math.abs(Math.toDegrees(hIn - hFo)) < 0.2
                && Math.abs(Math.toDegrees(
                    Math.asin(fo.T().y())
                    - Math.asin(seam0.T().y()))) < 0.2,
            "49a unlocked yaw follows, locked pitch holds the original");
    }

    // ---- 49b: relocking returns to the ORIGINAL orientation ------------

    static void strictRelock() {
        Coaster c = oneForce(hillLanes(6), 24);
        c.pieces.get(0).sections.add(new ForceSection(hillLanes(5), 24));
        settle(c, 0.01);
        c.makeSeamStrict(0, 1, 0.01);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        int i0 = r0.spans().get(1).i0();
        Vec3 t0 = r0.samples().get(i0).T();

        // unlock everything + pitch the upstream: the follower aligns
        TrackPiece f = c.pieces.get(1);
        f.sLockYaw = false;
        f.sLockPitch = false;
        f.sLockRoll = false;
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(2).val += 0.4;
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        int j0 = r1.spans().get(1).i0();
        check(r1.samples().get(j0).T()
                .sub(r1.samples().get(j0 - 1).T()).length() < 5e-3,
            "49b all-unlocked: the follower aligns fully to the seam");

        // relock all three: the follower returns to its ORIGINAL
        // orientation exactly (the stored placement never moved)
        f.sLockYaw = true;
        f.sLockPitch = true;
        f.sLockRoll = true;
        TrackCompiler.Result r2 = TrackCompiler.compile(c, 0.01);
        Vec3 t2 = r2.samples().get(r2.spans().get(1).i0()).T();
        check(t2.sub(t0).length() < 1e-9, String.format(
            "49b relocking all axes returns the ORIGINAL orientation "
            + "(dev %.2e)", t2.sub(t0).length()));
    }

    // ---- 49c: un-strict = reorient continuous, KEEP the conflict --------

    static void strictUnstrict() {
        Coaster c = oneForce(hillLanes(6), 24);
        c.pieces.get(0).sections.add(new ForceSection(hillLanes(5), 24));
        settle(c, 0.01);
        c.makeSeamStrict(0, 1, 0.01);
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(2).val += 0.35;   // open a conflict + kink
        TrackCompiler.compile(c, 0.01);

        List<double[]> lanesBefore = new ArrayList<>();
        LaneSection fSec = (LaneSection) c.pieces.get(1).sections.get(0);
        for (List<BezierBaker.Node> lane : fSec.lanes) {
            for (BezierBaker.Node nd : lane) {
                lanesBefore.add(new double[]{ nd.t, nd.val });
            }
        }
        check(c.unstrictSeam(1) && c.pieces.size() == 1,
            "49c un-strict merges the follower back (one piece)");
        Section merged = c.pieces.get(0).sections.get(1);
        check(merged.vPinned(),
            "49c the speed conflict is deliberately KEPT (pinned)");
        int i = 0;
        boolean lanesKept = true;
        for (List<BezierBaker.Node> lane : ((LaneSection) merged).lanes) {
            for (BezierBaker.Node nd : lane) {
                lanesKept &= lanesBefore.get(i)[0] == nd.t
                    && lanesBefore.get(i)[1] == nd.val;
                i++;
            }
        }
        check(lanesKept, "49c the design graphs survive bit-exact");
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        int s1 = r.spans().get(1).i0();
        check(r.samples().get(s1).T()
                .sub(r.samples().get(s1 - 1).T()).length() < 5e-3,
            "49c the track reorients CONTINUOUS at the former seam");
    }

    // ---- 49d: bridge convert + strict, then un-strict == classic -------

    static Coaster bridgedPair() {
        Coaster c = oneForce(hillLanes(5), 24);
        TrackPiece p2 = new TrackPiece(120, 16, -30, 40);
        p2.sections.add(new ForceSection(hillLanes(4), 21));
        c.pieces.add(p2);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        c.pieces.get(0).sections.add(BridgeSection.solve(
            r.spans().get(0).exit(), p2.entryPose(), 24));
        return c;
    }

    static void strictBridgeConvert() {
        // classic path (strict OFF): attach + pin
        Coaster cc = bridgedPair();
        boolean didC = cc.convertWholeSection(0, 1, false, 0.01, 0.05, 0,
            Section.Domain.TIME, false, false, false, false, false);
        TrackCompiler.Result rc = TrackCompiler.compile(cc, 0.01);

        // strict path (ON, the default): follower kept
        Coaster cs = bridgedPair();
        boolean didS = cs.convertWholeSection(0, 1, false, 0.01, 0.05, 0,
            Section.Domain.TIME, false, false, false, false, true);
        check(didC && didS && cc.pieces.size() == 1
                && cs.pieces.size() == 2 && cs.pieces.get(1).followPrev
                && cs.pieces.get(1).sLockPitch   // the bridge's locks
                && cs.pieces.get(1).sLockRoll
                && !cs.pieces.get(1).sLockYaw,
            "49d strict bridge-convert keeps the follower (bridge locks "
            + "carried); classic attaches");
        TrackCompiler.Result rs = TrackCompiler.compile(cs, 0.01);
        double dev = 0;
        int n = Math.min(rc.samples().size(), rs.samples().size());
        for (int i = 0; i < n; i += 4) {
            dev = Math.max(dev, rc.samples().get(i).pos()
                .sub(rs.samples().get(i).pos()).length());
        }
        // the two paths absorb the lanes' FIT RESIDUAL differently:
        // classic re-chains the target fully continuous through it,
        // strict holds locked pitch/roll at the original — they agree
        // to the residual's lever arm, and EXACTLY once un-stricted
        check(dev < 0.6, String.format(
            "49d strict vs classic convert agree to the fit residual "
            + "at creation (dev %.3f m)", dev));

        // the user's identity: strict + UN-STRICT == classic exactly
        check(cs.unstrictSeam(1) && cs.pieces.size() == 1
                && cs.pieces.get(0).sections.get(2).vPinned()
                == cc.pieces.get(0).sections.get(2).vPinned(),
            "49d un-stricting reproduces the classic stitch (attach + "
            + "pin)");
        TrackCompiler.Result ru = TrackCompiler.compile(cs, 0.01);
        double dev2 = 0;
        n = Math.min(rc.samples().size(), ru.samples().size());
        for (int i = 0; i < n; i += 4) {
            dev2 = Math.max(dev2, rc.samples().get(i).pos()
                .sub(ru.samples().get(i).pos()).length());
        }
        check(dev2 < 1e-6, String.format(
            "49d convert+strict+unstrict == classic convert (dev %.2e m)",
            dev2));
    }

    // ---- 49e: IO round-trip + connect continuity -----------------------

    static void strictIo() {
        Coaster c = oneForce(hillLanes(6), 24);
        c.pieces.get(0).sections.add(new ForceSection(hillLanes(5), 24));
        c.fricRoll = 0.01;
        settle(c, 0.01);
        c.makeSeamStrict(0, 1, 0.01);
        c.pieces.get(1).sLockYaw = true;   // a non-default lock set

        StringWriter w1 = new StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(), new PrintWriter(w1));
        try {
            DesignIO.LoadResult lr = DesignIO.load(new BufferedReader(
                new StringReader(w1.toString())));
            StringWriter w2 = new StringWriter();
            DesignIO.save(lr.coaster(), lr.cosmetics(),
                new PrintWriter(w2));
            TrackPiece lf = lr.coaster().pieces.get(1);
            check(w1.toString().equals(w2.toString())
                    && lf.followPrev && lf.sLockYaw && lf.sLockPitch
                    && lf.sLockRoll,
                "49e strict seam round-trips byte-identically "
                + "(piece fields 11-14)");
        } catch (Exception e) {
            check(false, "49e round-trip threw: " + e);
        }

        // connectStrict on coincident pieces (the zero-distance /
        // attach flows): all-unlocked = seamless, nothing moves
        Coaster c2 = oneForce(hillLanes(6), 24);
        c2.pieces.get(0).sections.add(new ForceSection(hillLanes(5), 24));
        settle(c2, 0.01);
        TrackCompiler.Result q0 = TrackCompiler.compile(c2, 0.01);
        List<Vec3> before = new ArrayList<>();
        for (int i = 0; i < q0.samples().size(); i += 4) {
            before.add(q0.samples().get(i).pos());
        }
        c2.makeSeamStrict(0, 1, 0.01);   // split...
        c2.detachStrictSeam(1, 0.01);    // ...into a detached twin
        int np = c2.connectStrict(0, 1); // ...reconnected strictly
        check(np == 1 && c2.pieces.get(1).followPrev
                && !c2.pieces.get(1).sLockYaw
                && !c2.pieces.get(1).sLockPitch
                && !c2.pieces.get(1).sLockRoll
                && c2.pieces.get(1).sSmoothLen == 0,
            "49e connect-directly = strict seam, all axes unlocked, "
            + "smoothing OFF (D143 walk-back of D142's 10 m)");
        TrackCompiler.Result q1 = TrackCompiler.compile(c2, 0.01);
        double dev = 0;
        for (int i = 0, j = 0; i < q1.samples().size() && j < before.size();
                i += 4, j++) {
            dev = Math.max(dev,
                q1.samples().get(i).pos().sub(before.get(j)).length());
        }
        check(dev < 1e-6, String.format(
            "49e coincident connect moves nothing (dev %.2e m)", dev));
    }

    // ---- 49g: resolving a follower's conflict (the user's launch
    // report, D136): the strict bake must precede the ops — without
    // it make-real fit the follow-rotated samples and the compile
    // rotated them AGAIN. Post-fix: make-real keeps the on-screen
    // shape exactly, and refreeze matches the UN-STRICTED twin. ----

    static Coaster strictConflicted() {
        Coaster c = oneForce(hillLanes(6), 24);
        c.pieces.get(0).sections.add(new ForceSection(hillLanes(5), 24));
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        settle(c, 0.01);
        c.makeSeamStrict(0, 1, 0.01);
        TrackPiece f = c.pieces.get(1);
        f.sLockYaw = false;
        f.sLockPitch = false;
        f.sLockRoll = false;
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(1).val -= 1.2;   // violent: seam dives ~40°
        up.lanes.get(0).get(2).val += 1.5;
        up.lanes.get(2).get(1).val = 60;
        settle(c, 0.01);
        return c;
    }

    static void strictResolve() {
        // MAKE-REAL keeps the on-screen shape exactly
        Coaster c = strictConflicted();
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        TrackCompiler.Span sp0 = r0.spans().get(1);
        Vec3 end0 = r0.samples().get(
            Math.min(sp0.i1(), r0.samples().size() - 1)).pos();
        double arc0 = sp0.sLen();
        check(c.makeGraphsReal(1, 0, 0.01, 0.02, 0),
            "49g make-real on a follower applies");
        TrackCompiler.Result r1 = settle(c, 0.01);
        TrackCompiler.Span sp1 = r1.spans().get(1);
        Vec3 end1 = r1.samples().get(
            Math.min(sp1.i1(), r1.samples().size() - 1)).pos();
        check(end1.sub(end0).length() < 2.5
                && Math.abs(sp1.sLen() - arc0) < 2, String.format(
            "49g make-real KEEPS the shape to the fit residual on the "
            + "violent fixture (end dev %.3f m, arc %.1f vs %.1f m — "
            + "was ~190 m off before the bake fix)",
            end1.sub(end0).length(), sp1.sLen(), arc0));

        // REFREEZE == the un-stricted twin's refreeze (the user's own
        // comparison: removing the seam made it behave — now they
        // match by construction)
        Coaster cs = strictConflicted();
        Coaster cu = strictConflicted();
        check(cs.refreezeSpeed(1, 0, 0.01),
            "49g refreeze on a follower applies");
        TrackCompiler.Result rs = settle(cs, 0.01);
        check(cu.unstrictSeam(1) && cu.refreezeSpeed(0, 1, 0.01),
            "49g the un-stricted twin refreezes too");
        TrackCompiler.Result ru = settle(cu, 0.01);
        double dev = 0;
        int n = Math.min(rs.samples().size(), ru.samples().size());
        for (int i = 0; i < n; i += 4) {
            dev = Math.max(dev, rs.samples().get(i).pos()
                .sub(ru.samples().get(i).pos()).length());
        }
        check(Math.abs(rs.samples().size() - ru.samples().size()) <= 2
                && dev < 0.5, String.format(
            "49g strict refreeze == un-stricted refreeze (dev %.3f m "
            + "over the ~300 m reshape — the reshape happens from the "
            + "REAL frame either way; was frame-wrong before)", dev));
    }

    // ---- 49f: attaching a FOREIGN piece (the user's 2026-07-18
    // report contract): the attach must behave exactly like editing
    // the piece's pitch/roll does — a RIGID reposition + reorient.
    // The attached piece KEEPS its design speed (an honest ≠v
    // conflict opens against what arrives) and its design
    // orientation (the hidden pitch/roll conflict — felt
    // re-projects), and its SHAPE never re-solves. ----

    static void strictAttachForeign() {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(200, 35, 60, 90);
        b.pitchDeg = 10;                       // a tilted floater
        b.uiLockPitch = false;
        b.sections.add(new ForceSection(hillLanes(5), 20));
        c.pieces.add(b);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        TrackCompiler.Result r0 = settle(c, 0.01);
        List<Vec3> shapeB0 = localShape(r0, 1);

        int np = c.connectStrict(0, 1);        // the ⊕-attach path
        TrackCompiler.Result r1 = settle(c, 0.01);
        Section bFirst = c.pieces.get(np).sections.get(0);
        check(np == 1 && Math.abs(bFirst.vDesign() - 20) < 1e-9,
            "49f attach KEEPS the piece's own design speed (20, not "
            + "the seam's)");
        TrackCompiler.Span spB = r1.spans().get(1);
        double worstSplit = 0;
        for (int i = spB.i0(); i <= Math.min(spB.i1(),
                r1.samples().size() - 1); i++) {
            worstSplit = Math.max(worstSplit,
                Math.abs(r1.trueV()[i] - r1.samples().get(i).v()));
        }
        check(worstSplit > 3, String.format(
            "49f the speed conflict shows (design 20 vs ~28 arriving — "
            + "split %.1f m/s)", worstSplit));
        // rigid: local shape held (the ±one-sample stride misalignment
        // from the dropped continuation entry bounds the comparison)
        double dShape = shapeDelta(shapeB0, localShape(r1, 1));
        check(dShape < 0.1, String.format(
            "49f the attached shape is RIGID — no re-solve "
            + "(local delta %.3f m, one-sample alignment noise)", dShape));
        // fully continuous joint (all axes unlocked at attach) — the
        // hidden ORIENTATION conflict is the design-vs-real frame
        int i0 = spB.i0();
        check(r1.samples().get(i0).T()
                .sub(r1.samples().get(i0 - 1).T()).length() < 1e-3,
            "49f the joint reorients fully continuous (the pitch/roll "
            + "difference became the hidden design/real split)");
    }

    // ---- 48a/48b: closed forms on flat track --------------------------

    static void closedForms() {
        double v0 = 20, dur = 10;

        Coaster c = oneForce(flatLanes(dur), v0);
        c.fricRoll = 0.03;
        c.fricAir = 0;
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        double worst = 0;
        for (FVDIntegrator.Sample sm : r.samples()) {
            double want2 = v0 * v0 - 2 * 0.03 * G * sm.s();
            worst = Math.max(worst,
                Math.abs(sm.v() * sm.v() - want2));
        }
        check(worst < 1e-9, String.format(
            "48a rolling closed form v^2 = v0^2 - 2 mu g s (worst %.2e)",
            worst));

        c = oneForce(flatLanes(dur), v0);
        c.fricRoll = 0;
        c.fricAir = 0.002;
        r = TrackCompiler.compile(c, 0.01);
        worst = 0;
        for (FVDIntegrator.Sample sm : r.samples()) {
            double want = v0 * Math.exp(-0.002 * sm.s());
            worst = Math.max(worst, Math.abs(sm.v() - want));
        }
        check(worst < 1e-8, String.format(
            "48b air-drag closed form v = v0 e^(-ks) (worst %.2e)", worst));

        // sanity: friction actually bites (end speed clearly lower)
        check(r.samples().get(r.samples().size() - 1).v() < v0 - 0.5,
            "48b air drag sheds real speed on the fixture");
    }

    // ---- 48c: Pass D reproduces the design profile --------------------

    static void passDMatch() {
        Coaster c = oneForce(hillLanes(12), 24);
        c.fricRoll = 0.02;
        c.fricAir = 0.001;
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        check(worstSplit(r) < 1e-3, String.format(
            "48c passD == design profile, both frictions on (worst %.2e m/s)",
            worstSplit(r)));
    }

    // ---- 48d: chained sections reconcile under friction ----------------

    static void chainReconcile() {
        Coaster c = oneForce(hillLanes(8), 24);
        TrackPiece p = c.pieces.get(0);
        p.sections.add(new ForceSection(hillLanes(6), 24));
        c.fricRoll = 0.025;
        c.fricAir = 0.0008;
        TrackCompiler.Result r = settle(c, 0.01);
        check(worstSplit(r) < 5e-3, String.format(
            "48d two-section chain settles design == true (worst %.2e m/s)",
            worstSplit(r)));
    }

    // ---- 48e: s/c spine friction-invariance ----------------------------

    static void scInvariance() {
        for (boolean global : new boolean[]{ false, true }) {
            StraightCurveSection.Element e1 =
                StraightCurveSection.Element.straight(15, 0);
            StraightCurveSection.Element e2 =
                StraightCurveSection.Element.curve(20, 90, 30, 3);
            StraightCurveSection.Element e3 =
                StraightCurveSection.Element.straight(15, 2);
            for (StraightCurveSection.Element e
                    : new StraightCurveSection.Element[]{ e1, e2, e3 }) {
                e.rollMode = global ? StraightCurveSection.RollMode.GLOBAL
                                    : StraightCurveSection.RollMode.RATE;
                e.rollVal = 0;
            }
            Coaster ca = new Coaster();
            TrackPiece pa = new TrackPiece(0, 21, 0, 0);
            pa.sections.add(new StraightCurveSection(
                new ArrayList<>(List.of(e1, e2, e3)), 26));
            ca.pieces.add(pa);
            TrackCompiler.Result r0 = TrackCompiler.compile(ca, 0.01);
            ca.fricRoll = 0.04;
            ca.fricAir = 0.002;
            TrackCompiler.Result r1 = TrackCompiler.compile(ca, 0.01);
            TrackPose x0 = r0.spans().get(0).exit();
            TrackPose x1 = r1.spans().get(0).exit();
            double dev = x0.pos().sub(x1.pos()).length();
            double vDrop = r0.samples().get(r0.samples().size() - 1).v()
                - r1.samples().get(r1.samples().size() - 1).v();
            double bound = global ? 0.05 : 1e-6;
            check(dev < bound && vDrop > 0.3, String.format(
                "48e s/c spine friction-invariant, %s roll (exit dev %.2e"
                + " m < %.0e, design end speed drop %.2f m/s)",
                global ? "GLOBAL-0" : "RATE-0", dev, bound, vDrop));
        }
    }

    // ---- 48f: REVERSE PIECE = a convert (D134) -------------------------

    static Coaster twoSecPiece() {
        // force hills + constant-rate geo, GENTLE friction: friction
        // is paid in BOTH directions, so a reversal at the natural
        // end speed must have enough energy to climb home — heavy
        // coefficients on a long piece stall (checked separately)
        Coaster c = oneForce(hillLanes(5), 24);
        // yaw −12 since D185 (+yaw = left now): authors the SAME
        // physical shape as the historic +12, so the 48-series
        // speed/stall baselines carry over exactly
        c.pieces.get(0).sections.add(
            new GeoSection(geoLanes(6, -12, 15, 3), 24));
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        return c;
    }

    static void reverseConvert() {
        // a piece with a BRIDGE refuses
        Coaster cb = twoSecPiece();
        TrackPiece pb = cb.pieces.get(0);
        TrackCompiler.Result rb = TrackCompiler.compile(cb, 0.01);
        TrackPiece target = new TrackPiece(60, 21, 40, 90);
        target.sections.add(new ForceSection(flatLanes(4), 22));
        cb.pieces.add(target);
        pb.sections.add(BridgeSection.solve(
            rb.spans().get(1).exit(), target.entryPose(), 24));
        check(!cb.reversePiece(0, 0.01, 0.02, 0, Double.NaN),
            "48f a piece containing a bridge refuses to reverse");

        Coaster c = twoSecPiece();
        TrackCompiler.Result r0 = settle(c, 0.01);
        List<FVDIntegrator.Sample> pre = new ArrayList<>(r0.samples());
        double vEnd = r0.trueV()[r0.samples().size() - 1];

        boolean did = c.reversePiece(0, 0.01, 0.02, 0, Double.NaN);
        List<Section> out = c.pieces.get(0).sections;
        check(did && out.size() == 2
                && out.get(0) instanceof GeoSection
                && out.get(1) instanceof ForceSection
                && !out.get(0).reversed() && !out.get(1).reversed(),
            "48f reverse-convert: same structure mirrored (geo+force), "
            + "all FORWARD sections");
        check(Math.abs(out.get(0).vDesign() - vEnd) < 0.2, String.format(
            "48f entry speed = the recorded end speed (%.2f vs %.2f m/s)",
            out.get(0).vDesign(), vEnd));

        TrackCompiler.Result r1 = settle(c, 0.01);
        check(worstSplit(r1) < 5e-3, String.format(
            "48f reversed piece has NO speed conflict (worst split "
            + "%.2e m/s)", worstSplit(r1)));
        double dev = mirrorShapeDev(pre, r1.samples());
        // multi-section pieces accumulate the per-seam fit-residual
        // reorientation exactly like chained converts do (~0.4°/seam
        // from the measured-roll edge; interior quality is cm-scale)
        check(dev < 1.2, String.format(
            "48f reversed shape matches the original at matched arc "
            + "(worst %.3f m over %.0f m, friction on, 0.02 s carpet)",
            dev, pre.get(pre.size() - 1).s()));

        // HEAVY friction: the reversed flow genuinely STALLS (energy
        // for the climb home was already spent once) — the op must
        // stay finite and conflict-free; the UI's stall telltale is
        // the user's cue to unlock a higher entry speed
        Coaster cs = oneForce(hillLanes(6), 24);
        cs.pieces.get(0).sections.add(
            new GeoSection(geoLanes(6, 12, 15, 4), 24));
        cs.fricRoll = 0.03;
        cs.fricAir = 0.001;
        settle(cs, 0.01);
        boolean didS = cs.reversePiece(0, 0.01, 0.05, 0, Double.NaN);
        TrackCompiler.Result rs = settle(cs, 0.01);
        boolean stalled = false;
        for (double v : rs.trueV()) stalled |= v < 0.6;
        // 0.02 since v17.11 (D151): near the stall creep, v = sqrt of
        // near-zero energy amplifies mm/s of profile difference —
        // 8 mm/s of split is measurement scale there (badges fire at
        // 0.2 m/s), not a conflict. FLOORED at 0.1 since v18.6
        // (D175): the records are HONEST now (no 0.1 park), so the
        // creep zone's sqrt-amplified residual shows in the raw
        // arrays — this check always meant "no conflict ABOVE the
        // creep floor", which the floor restores as the comparison
        // the clamped records used to make implicitly.
        double worstF = 0;
        for (int i = 0; i < rs.samples().size(); i++) {
            worstF = Math.max(worstF,
                Math.abs(Math.max(0.1, rs.trueV()[i])
                    - Math.max(0.1, rs.samples().get(i).v())));
        }
        check(didS && stalled && worstF < 0.02,
            "48f a STALLING reversal stays finite and conflict-free "
            + "(creep floor; the preview's stall hint is the cue)");
    }

    // ---- 48g: custom entry speed ---------------------------------------

    static void reverseCustomSpeed() {
        Coaster c = twoSecPiece();
        TrackCompiler.Result r0 = settle(c, 0.01);
        List<FVDIntegrator.Sample> pre = new ArrayList<>(r0.samples());

        boolean did = c.reversePiece(0, 0.01, 0.02, 0, 15);
        check(did && Math.abs(
                c.pieces.get(0).sections.get(0).vDesign() - 15) < 1e-9,
            "48g custom (unlocked) entry speed honored");
        TrackCompiler.Result r1 = settle(c, 0.01);
        double dev = mirrorShapeDev(pre, r1.samples());
        check(worstSplit(r1) < 5e-3 && dev < 1.2, String.format(
            "48g custom speed: still conflict-free, shape independent "
            + "of the entry speed (dev %.3f m)", dev));
    }

    // ---- 48h: s/c piece reverses into force lanes ----------------------

    static void reverseScPiece() {
        StraightCurveSection.Element e1 =
            StraightCurveSection.Element.straight(12, 0);
        StraightCurveSection.Element e2 =
            StraightCurveSection.Element.curve(18, 75, 0, 3);
        StraightCurveSection.Element e3 =
            StraightCurveSection.Element.straight(12, 2);
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new StraightCurveSection(
            new ArrayList<>(List.of(e1, e2, e3)), 26));
        c.pieces.add(p);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        TrackCompiler.Result r0 = settle(c, 0.01);
        List<FVDIntegrator.Sample> pre = new ArrayList<>(r0.samples());

        boolean did = c.reversePiece(0, 0.01, 0.02, 0, Double.NaN);
        check(did && p.sections.size() == 1
                && p.sections.get(0) instanceof ForceSection
                && !p.sections.get(0).reversed(),
            "48h s/c piece reverses into forward FORCE lanes (element "
            + "lists have no mirrored form)");
        TrackCompiler.Result r1 = settle(c, 0.01);
        double dev = mirrorShapeDev(pre, r1.samples());
        check(worstSplit(r1) < 5e-3 && dev < 0.35, String.format(
            "48h reversed s/c: conflict-free, spine held (dev %.3f m)",
            dev));
    }

    // ---- 48i: legacy reversed flag + double reversal --------------------

    static void reverseLegacyAndTwice() {
        // a LEGACY reversed-flag section (old files): the new op just
        // converts the compiled geometry — the output is plain
        // forward. LEVEL seam here (a KINKED legacy seam — pitched
        // authored end, D67 matched heading only — cannot survive
        // any chained re-author: frame-continuous sections smooth
        // it, rotating the far side by the kink angle; documented,
        // not gated).
        Coaster c = oneForce(flatLanes(4), 24);
        ForceSection legacy = new ForceSection(flatLanes(6), 22);
        legacy.reversed = true;
        c.pieces.get(0).sections.add(legacy);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        TrackCompiler.Result r0 = settle(c, 0.01);
        List<FVDIntegrator.Sample> pre = new ArrayList<>(r0.samples());

        boolean did = c.reversePiece(0, 0.01, 0.02, 0, Double.NaN);
        boolean allFwd = true;
        for (Section s : c.pieces.get(0).sections) {
            allFwd &= !s.reversed();
        }
        TrackCompiler.Result r1 = settle(c, 0.01);
        double dev1 = mirrorShapeDev(pre, r1.samples());
        check(did && allFwd && dev1 < 0.25, String.format(
            "48i legacy reversed-flag piece reverses to ALL-forward "
            + "(dev %.3f m at a level seam)", dev1));

        // reversing AGAIN (the smooth two-section fixture) lands back
        // on the original shape — a fit of a fit, twice the budget
        Coaster c2 = twoSecPiece();
        TrackCompiler.Result q0 = settle(c2, 0.01);
        List<FVDIntegrator.Sample> pre2 = new ArrayList<>(q0.samples());
        boolean did2 = c2.reversePiece(0, 0.01, 0.02, 0, Double.NaN)
            && c2.reversePiece(0, 0.01, 0.02, 0, Double.NaN);
        TrackCompiler.Result q2 = settle(c2, 0.01);
        double dev2 = mirrorShapeDev(
            reversedOrder(pre2), q2.samples());
        check(did2 && worstSplit(q2) < 5e-3 && dev2 < 2.0, String.format(
            "48i double reversal lands back on the original shape "
            + "(dev %.3f m, still conflict-free)", dev2));
    }

    /** The pre list arc-reversed so mirrorShapeDev compares
     *  same-direction rides (double reversal restores direction). */
    static List<FVDIntegrator.Sample> reversedOrder(
            List<FVDIntegrator.Sample> pre) {
        double sEnd = pre.get(pre.size() - 1).s();
        List<FVDIntegrator.Sample> out = new ArrayList<>(pre.size());
        for (int i = pre.size() - 1; i >= 0; i--) {
            FVDIntegrator.Sample sm = pre.get(i);
            out.add(new FVDIntegrator.Sample(sm.t(), sEnd - sm.s(),
                sm.pos(), sm.T(), sm.U(), sm.R(), sm.v(),
                sm.Gv(), sm.Gl()));
        }
        return out;
    }

    // ---- 48j: FVDD3 round-trip ------------------------------------------

    static void ioRoundTrip() {
        Coaster c = oneForce(hillLanes(6), 24);
        ForceSection legacy = new ForceSection(flatLanes(6), 22);
        legacy.reversed = true;   // the reversed FLAG still round-trips
        c.pieces.get(0).sections.add(legacy);
        c.fricRoll = 0.03;
        c.fricAir = 0.001;

        StringWriter w1 = new StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(), new PrintWriter(w1));
        DesignIO.LoadResult lr;
        try {
            lr = DesignIO.load(new BufferedReader(
                new StringReader(w1.toString())));
        } catch (Exception e) {
            check(false, "48j round-trip load threw: " + e);
            return;
        }
        StringWriter w2 = new StringWriter();
        DesignIO.save(lr.coaster(), lr.cosmetics(), new PrintWriter(w2));
        check(w1.toString().equals(w2.toString()),
            "48j save -> load -> save is byte-identical");
        check(lr.coaster().fricRoll == c.fricRoll
                && lr.coaster().fricAir == c.fricAir,
            String.format("48j friction coefficients round-trip "
            + "(%.4f / %.6f)", lr.coaster().fricRoll, lr.coaster().fricAir));
        check(lr.coaster().pieces.get(0).sections.get(1).reversed(),
            "48j the legacy reversed flag still round-trips (field 4)");

        // legacy text (no friction line) loads with zero friction
        String legacyTxt = w1.toString()
            .replaceAll("(?m)^friction .*\\R", "");
        try {
            DesignIO.LoadResult old = DesignIO.load(new BufferedReader(
                new StringReader(legacyTxt)));
            check(old.coaster().fricRoll == 0 && old.coaster().fricAir == 0,
                "48j legacy text (no friction line): zero friction");
        } catch (Exception e) {
            check(false, "48j legacy load threw: " + e);
        }
    }

    // ---- 48k: converts stay honest under friction ----------------------

    static void convertUnderFriction() {
        Coaster c = oneForce(hillLanes(12), 24);
        c.fricRoll = 0.03;
        c.fricAir = 0.001;
        TrackCompiler.Result r0 = TrackCompiler.compile(c, 0.01);
        Vec3 end0 = r0.samples().get(r0.samples().size() - 1).pos();
        double len0 = r0.samples().get(r0.samples().size() - 1).s();

        boolean did = c.convertWholeSection(0, 0, true, 0.01, 0.02, 0);
        TrackCompiler.Result r1 = TrackCompiler.compile(c, 0.01);
        Vec3 end1 = r1.samples().get(r1.samples().size() - 1).pos();
        double dev = end1.sub(end0).length();
        check(did && dev < 0.05, String.format(
            "48k force->geo convert under friction lands the end "
            + "(dev %.3f m over %.0f m)", dev, len0));
    }

    // ============= D137-D139 (v17.2): seam-jump convert fixes + =========
    // ============= strict-seam smoothing                        =========

    static double spanDrift(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(), r.samples().size() - 1);
        double worst = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    /** 51: GLOBAL-axis holds are CONTINUOUS (v17.6, D143 — the
     *  user's both-global report: every lane held flat ZERO and the
     *  resulting graphs stepped; either flag alone half-worked).
     *  The add's hold values take the incoming WORLD rates for
     *  global lanes; measured: the TRUE rates cross the seam within
     *  a few °/s in every combo (ypG-only's LOCAL roll keeps its
     *  documented transport shift — excluded there). */
    static void globalHoldContinuity() {
        String[] names = { "rollG", "ypG", "BOTH" };
        boolean[][] flags = { {true, false}, {false, true}, {true, true} };
        for (int f = 0; f < 3; f++) {
            boolean rollG = flags[f][0], ypG = flags[f][1];
            Coaster c = new Coaster();
            c.v0 = 26;
            TrackPiece a = new TrackPiece(0, 21, 0, 0);
            List<List<BezierBaker.Node>> fl = new ArrayList<>();
            fl.add(nodes(new double[][]{ {0, 1}, {2, 0.8}, {4, 1.6}, {6, 1.4} }));
            fl.add(nodes(new double[][]{ {0, 0}, {3, 0.3}, {6, 0.4} }));
            fl.add(nodes(new double[][]{ {0, 0}, {3, 0}, {6, 25} }));
            a.sections.add(new ForceSection(fl, 26));
            c.pieces.add(a);
            TrackCompiler.Result r0 = settle(c, 0.01);
            int last = r0.samples().size() - 1;
            FVDIntegrator.Sample e = r0.samples().get(last);
            double[] er = r0.rates()[last];
            // the v17.6 hold formula (addLaneSection's world path);
            // er[2] is REPORTED yaw (+ = left, D185) — internal om
            // negates it, and the ypG heading hold reports back
            Vec3 om = e.U().scale(-Math.toRadians(er[2]))
                .add(e.R().scale(-Math.toRadians(er[1])))
                .add(e.T().scale(Math.toRadians(er[3])));
            Vec3 tp = om.cross(e.T());
            double h2 = e.T().x() * e.T().x() + e.T().z() * e.T().z();
            double ct = Math.sqrt(Math.max(h2, 0));
            double thp = Math.toDegrees(tp.y() / ct);
            double psp = Math.toDegrees(
                (e.T().z() * tp.x() - e.T().x() * tp.z()) / h2);
            double php = Math.toDegrees(om.dot(e.T())) - psp * e.T().y();
            double v0h = ypG ? thp : er[1];
            double v1h = ypG ? -psp : er[2];   // reported heading' (D185)
            double rr = rollG ? php : er[3];
            List<List<BezierBaker.Node>> gl = new ArrayList<>();
            gl.add(nodes(new double[][]{ {0, v0h}, {2, v0h} }));
            gl.add(nodes(new double[][]{ {0, v1h}, {2, v1h} }));
            gl.add(nodes(new double[][]{ {0, rr}, {2, rr} }));
            GeoSection g = new GeoSection(gl, e.v());
            g.rollGlobal = rollG;
            g.yawPitchGlobal = ypG;
            c.pieces.get(0).sections.add(g);
            TrackCompiler.Result r1 = settle(c, 0.01);
            TrackCompiler.Span sp = r1.spans().get(1);
            int ib = sp.i0();
            double worst = 0;
            for (int col = 1; col <= 3; col++) {
                if (col == 3 && !rollG && ypG) continue;   // transport
                worst = Math.max(worst, Math.abs(
                    r1.trueRates()[Math.min(ib + 4, sp.i1())][col]
                    - r1.trueRates()[ib - 4][col]));
            }
            check(worst < 2.5, String.format(
                "51%c global-axis hold continuity (%s): worst seam "
                + "step %.2f deg/s (was 19-25 flat-zero pre-D143)",
                (char) ('a' + f), names[f], worst));
        }
    }

    /** 50a/50b: converting a section whose targets JUMP at its entry
     *  seam must anchor node 0 on the SECTION'S OWN value — the
     *  boundary row's felt belongs to the previous section, and
     *  pre-D137 it dragged node 0 between the two values and rippled
     *  the fit at any density (the 2026-07-18 report). */
    static void entryCleanConvert() {
        for (double spacing : new double[]{ 0.1, 0.02 }) {
            Coaster c = new Coaster();
            c.v0 = 24;
            TrackPiece a = new TrackPiece(0, 21, 0, 0);
            List<List<BezierBaker.Node>> l1 = new ArrayList<>();
            l1.add(nodes(new double[][]{ {0, 1}, {6, 1} }));
            l1.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
            l1.add(nodes(new double[][]{ {0, 0}, {4, 0}, {6, 40} }));
            List<List<BezierBaker.Node>> l2 = new ArrayList<>();
            l2.add(nodes(new double[][]{ {0, 2.2}, {5, 2.2} }));
            l2.add(nodes(new double[][]{ {0, 0}, {5, 0} }));
            l2.add(nodes(new double[][]{ {0, 0}, {5, 0} }));
            a.sections.add(new ForceSection(l1, 24));
            a.sections.add(new ForceSection(l2, 24));
            c.pieces.add(a);
            settle(c, 0.01);
            boolean did = c.convertWholeSection(0, 1, false, 0.01,
                spacing, 0);
            TrackCompiler.Result r = settle(c, 0.01);
            LaneSection ls = (LaneSection) c.pieces.get(0).sections.get(1);
            double n0 = ls.lanes.get(0).get(0).val;
            double n0r = ls.lanes.get(2).get(0).val;
            TrackCompiler.Span sp = r.spans().get(1);
            double t0 = r.samples().get(sp.i0()).t();
            double dev = 0;
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                double dt0 = r.samples().get(i).t() - t0;
                if (dt0 < 0.05 || dt0 > 2.0) continue;
                dev = Math.max(dev,
                    Math.abs(r.samples().get(i).Gv() - 2.2));
            }
            check(did && Math.abs(n0 - 2.2) < 0.02 && Math.abs(n0r) < 0.5
                    && dev < 0.02, String.format(
                "50%s convert at a jump seam (spacing %.2f): node0 on "
                + "the section's own value (Gv %.3f vs 2.2, roll %.2f "
                + "vs 0), no ripple (dev %.3f g)",
                spacing > 0.05 ? "a" : "b", spacing, n0, n0r, dev));
        }
    }

    static Coaster strictDriftFixture(boolean locked) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        a.sections.add(new ForceSection(hillLanes(5), 24));
        c.pieces.add(a);
        c.fricRoll = 0.01;
        c.fricAir = 0.0003;
        settle(c, 0.01);
        c.makeSeamStrict(0, 1, 0.01);
        if (!locked) {
            c.pieces.get(1).sLockPitch = false;
            c.pieces.get(1).sLockRoll = false;
        }
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(1).val -= 1.2;
        up.lanes.get(0).get(2).val += 1.5;
        up.lanes.get(2).get(1).val = 60;
        settle(c, 0.01);
        return c;
    }

    /** 50c/50d: "Make graphs real" on a strict-seam follower CLEARS
     *  the ≠v drift and lands node 0 on the true felt (the corrupted
     *  entry was the visible leftover "conflict", 2026-07-18). */
    static void makeRealAfterStrict() {
        for (boolean locked : new boolean[]{ true, false }) {
            Coaster c = strictDriftFixture(locked);
            TrackCompiler.Result r0 = settle(c, 0.01);
            double pre = spanDrift(r0, 1);
            double expGv = r0.trueFelt()[r0.spans().get(1).i0()][0];
            boolean did = c.makeGraphsReal(1, 0, 0.01, 0.1, 0);
            TrackCompiler.Result r1 = settle(c, 0.01);
            double post = spanDrift(r1, 1);
            LaneSection ls = (LaneSection) c.pieces.get(1).sections.get(0);
            double n0 = ls.lanes.get(0).get(0).val;
            check(did && pre > 5 && post < 0.2
                    && Math.abs(n0 - expGv) < 0.05, String.format(
                "50%s make-real after a strict seam (%s): drift %.1f -> "
                + "%.3f m/s, node0 %.3f on trueFelt %.3f",
                locked ? "c" : "d", locked ? "locked p/r" : "unlocked",
                pre, post, n0, expGv));
        }
    }

    static Coaster smoothFixture(double len) {
        Coaster c = new Coaster();
        c.v0 = 24;
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(new ForceSection(hillLanes(6), 24));
        a.sections.add(new ForceSection(hillLanes(5), 24));
        a.sections.add(new ForceSection(hillLanes(4), 24));
        c.pieces.add(a);
        settle(c, 0.01);
        c.makeSeamStrict(0, 1, 0.01);   // locked p/r
        ForceSection up = (ForceSection) c.pieces.get(0).sections.get(0);
        up.lanes.get(0).get(1).val -= 0.4;   // moderate seam kink
        settle(c, 0.01);
        c.pieces.get(1).sSmoothLen = len;
        return c;
    }

    /** 50e: smoothing is WINDOW-LOCAL — upstream of the window
     *  bitwise, beyond it frames bitwise and positions offset by one
     *  constant vector, the follower's later sections carried the
     *  same way. */
    static void smoothLocality() {
        TrackCompiler.Result r0 =
            TrackCompiler.compile(smoothFixture(0), 0.01);
        TrackCompiler.Result r1 =
            TrackCompiler.compile(smoothFixture(8), 0.01);
        if (r0.samples().size() != r1.samples().size()) {
            check(false, "50e sample-count mismatch "
                + r0.samples().size() + " vs " + r1.samples().size());
            return;
        }
        double sSeam = r0.samples().get(r0.spans().get(0).i1()).s();
        boolean upBit = true;
        double frameDev = 0, dpVar = 0;
        Vec3 dpRef = null;
        for (int i = 0; i < r0.samples().size(); i++) {
            FVDIntegrator.Sample a = r0.samples().get(i);
            FVDIntegrator.Sample b = r1.samples().get(i);
            if (a.s() < sSeam - 4.2) {
                if (a.pos().sub(b.pos()).length() > 0
                        || a.U().sub(b.U()).length() > 0
                        || a.Gv() != b.Gv()) upBit = false;
            } else if (a.s() > sSeam + 4.2) {
                frameDev = Math.max(frameDev, Math.max(
                    a.T().sub(b.T()).length(),
                    a.U().sub(b.U()).length()));
                Vec3 dp = b.pos().sub(a.pos());
                if (dpRef == null) dpRef = dp;
                dpVar = Math.max(dpVar, dp.sub(dpRef).length());
            }
        }
        // v17.3 (D140): the closure bends + stretches the window so
        // the downstream lands back where it was — the translation
        // itself must now be sub-mm on a moderate seam
        check(upBit && frameDev == 0 && dpVar < 1e-9 && dpRef != null
                && dpRef.length() < 1e-3,
            String.format("50e smoothing locality + closure: upstream "
                + "bitwise %s, outside frames dev %.1g, translation "
                + "constant to %.1g m and CLOSED to |dp| %.2g m "
                + "(was 0.011 pre-closure)",
                upBit ? "yes" : "NO", frameDev, dpVar,
                dpRef == null ? 0 : dpRef.length()));
    }

    /** 50f: the seam's instant frame step COLLAPSES into a ramp.
     *  RE-BASELINED for v18.35 (D207 — rates difference per SPAN):
     *  the unsmoothed kink's old >300 °/s "spike" was the ±1 window
     *  DIFFERENCING ACROSS the seam — span-clean rates never smear
     *  it, so the sharp seam now shows NO spike at all (the instant
     *  jump lives between samples; the seam CARETS are its witness)
     *  while the U kink per sample still proves the discontinuity.
     *  A 30 m window turns the ~9° kink into a bounded ~20 °/s REAL
     *  transition with a SMOOTH true-felt excursion (κ·v² through
     *  the bend). Window ±15 m; measure ±16. */
    static void smoothCollapse() {
        TrackCompiler.Result r0 =
            TrackCompiler.compile(smoothFixture(0), 0.01);
        TrackCompiler.Result r1 =
            TrackCompiler.compile(smoothFixture(30), 0.01);
        double sSeam = r0.samples().get(r0.spans().get(0).i1()).s();
        double kink0 = 0, kink1 = 0, rr0 = 0, rr1 = 0, tf1 = 0;
        for (int i = 1; i < r0.samples().size(); i++) {
            if (Math.abs(r0.samples().get(i - 1).s() - sSeam) > 16) {
                continue;
            }
            double d0 = Math.max(-1, Math.min(1, r0.samples().get(i - 1)
                .U().dot(r0.samples().get(i).U())));
            kink0 = Math.max(kink0, Math.toDegrees(Math.acos(d0)));
            double d1 = Math.max(-1, Math.min(1, r1.samples().get(i - 1)
                .U().dot(r1.samples().get(i).U())));
            kink1 = Math.max(kink1, Math.toDegrees(Math.acos(d1)));
            for (int col = 0; col < 3; col++) {
                rr0 = Math.max(rr0, Math.abs(r0.trueRates()[i][col]));
                rr1 = Math.max(rr1, Math.abs(r1.trueRates()[i][col]));
            }
            tf1 = Math.max(tf1, Math.abs(r1.trueFelt()[i][0]
                - r1.trueFelt()[i - 1][0]));
        }
        check(kink0 > 2 && kink1 < kink0 / 8
                && rr0 < 60          // D207: no cross-seam smear spike
                && rr1 > 10 && rr1 < 60   // the window's real ramp
                && tf1 < 0.3,
            String.format("50f smoothing collapses the seam: U kink "
                + "%.2f -> %.2f deg/sample, true rates %.0f (clean "
                + "sharp seam) -> %.0f deg/s (real ramp), true-felt "
                + "steps %.3f g/sample",
                kink0, kink1, rr0, rr1, tf1));
    }

    /** 50g: field 18 round-trips; legacy lines load as 0. */
    static void smoothIo() {
        try {
            Coaster c = smoothFixture(8);
            StringWriter w1 = new StringWriter();
            DesignIO.save(c, DesignIO.Cosmetics.defaults(),
                new PrintWriter(w1));
            DesignIO.LoadResult lr = DesignIO.load(new BufferedReader(
                new StringReader(w1.toString())));
            StringWriter w2 = new StringWriter();
            DesignIO.save(lr.coaster(), lr.cosmetics(),
                new PrintWriter(w2));
            boolean canon = w1.toString().equals(w2.toString());
            boolean val = Math.abs(
                lr.coaster().pieces.get(1).sSmoothLen - 8) < 1e-9;
            // a v17.1 line (17 fields) must load as smoothing 0
            String legacy = w1.toString().replaceAll(
                "(piece( -?\\d+\\.\\d+){4}( -?\\d+\\.\\d+){4}"
                + "( -?\\d+\\.\\d+){2}( \\d){4}( -?\\d+\\.\\d+){3})"
                + " -?\\d+\\.\\d+", "$1");
            DesignIO.LoadResult l2 = DesignIO.load(new BufferedReader(
                new StringReader(legacy)));
            boolean zero = l2.coaster().pieces.get(1).sSmoothLen == 0;
            check(canon && val && zero, String.format(
                "50g smoothing IO: field 18 round-trips (%s, val %s), "
                + "legacy 17-field line = 0 (%s)",
                canon ? "canonical" : "NOT canonical",
                val ? "ok" : "bad", zero ? "ok" : "bad"));
        } catch (Exception e) {
            check(false, "50g smoothing IO threw: " + e);
        }
    }

    /** 50h: resolve ops read the FOLLOW CONTRACT pose, not the blended
     *  seam sample — make-real on a SMOOTHED follower still clears the
     *  drift and keeps the ride's end where it was (baking the blended
     *  half-kink instead would swing the tail meters off). */
    static void makeRealUnderSmoothing() {
        Coaster c = strictDriftFixture(true);
        c.pieces.get(1).sSmoothLen = 8;
        TrackCompiler.Result r0 = settle(c, 0.01);
        double pre = spanDrift(r0, 1);
        Vec3 end0 = r0.samples().get(r0.samples().size() - 1).pos();
        boolean did = c.makeGraphsReal(1, 0, 0.01, 0.05, 0);
        TrackCompiler.Result r1 = settle(c, 0.01);
        double post = spanDrift(r1, 1);
        Vec3 end1 = r1.samples().get(r1.samples().size() - 1).pos();
        double dev = end1.sub(end0).length();
        check(did && pre > 5 && post < 0.2 && dev < 3, String.format(
            "50h make-real under smoothing: drift %.1f -> %.3f m/s, "
            + "ride end moves %.2f m (fit residual, not a half-kink "
            + "swing)", pre, post, dev));
    }

    // ========== v18.0: SPEED CONTROLS (D160) ==========

    /** 55a-i: speed controls — const-speed snap, accel ramp, conflict
     *  closure, inert-control bitwise, split/merge partition, domain
     *  carry, geo + s/c clamps, and a passD lift rescuing a stall. */
    static void speedControlGates() {
        double dt = 0.01;
        TrackPose entry = new TrackPose(new Vec3(0, 21, 0), Quat.IDENTITY);

        // 55a: const-speed clamp on flat track — instant snap, exact
        // hold, energy shed persists past the control
        {
            ForceSection fs = new ForceSection(flatLanes(6), 26);
            SpeedControl c = new SpeedControl(1, 2);
            c.speed = 15;
            fs.speedCtls.add(c);
            List<FVDIntegrator.Sample> s =
                TrackCompiler.passG(fs, entry, dt).samples();
            double v105 = vAtT(s, 1.05), v2 = vAtT(s, 2),
                vEnd = s.get(s.size() - 1).v();
            check(Math.abs(v105 - 15) < 1e-9 && Math.abs(v2 - 15) < 1e-9
                && Math.abs(vEnd - 15) < 1e-9,
                String.format("55a const-speed clamp: v(1.05)=%.6f "
                    + "v(2)=%.6f v(end)=%.6f (all exactly 15)",
                    v105, v2, vEnd));
        }

        // 55b: const-accel launch (min only) — analytic felt-G ramp
        // dv/dt = g*A on flat track, exact landing on the target
        {
            ForceSection fs = new ForceSection(flatLanes(6), 26);
            SpeedControl c = new SpeedControl(1, 4);
            c.type = SpeedControl.ACCEL;
            c.speed = 40;
            c.accel = 1.0;
            c.maxLimit = false;
            fs.speedCtls.add(c);
            List<FVDIntegrator.Sample> s =
                TrackCompiler.passG(fs, entry, dt).samples();
            double an = 26 + FVDIntegrator.G0;   // v at t=2 (1 s of drive)
            double err = Math.abs(vAtT(s, 2) - an);
            double vEnd = s.get(s.size() - 1).v();
            check(err < 2e-3 && Math.abs(vEnd - 40) < 1e-9,
                String.format("55b accel launch: ramp err %.2e m/s vs "
                    + "analytic, lands exactly on 40 (end %.6f)",
                    err, vEnd));
        }

        // 55c: a control CLOSES a design/real conflict — pinned section
        // at 20 vs real 26, both exit the clamp at 15 with zero split
        {
            Coaster co = new Coaster();
            co.v0 = 26;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection a = new ForceSection(flatLanes(2), 26);
            ForceSection b = new ForceSection(flatLanes(4), 20);
            b.vPinned = true;
            SpeedControl c = new SpeedControl(1, 2);
            c.speed = 15;
            b.speedCtls.add(c);
            p.sections.add(a);
            p.sections.add(b);
            co.pieces.add(p);
            TrackCompiler.Result r = TrackCompiler.compile(co, dt);
            double worst = 0;
            for (int i = 0; i < r.samples().size(); i++) {
                if (r.samples().get(i).t() > 5.0) {
                    worst = Math.max(worst, Math.abs(
                        r.samples().get(i).v() - r.trueV()[i]));
                }
            }
            check(worst == 0.0,
                String.format("55c conflict closes through a control: "
                    + "post-control design/true split %.2e m/s "
                    + "(exactly 0)", worst));
        }

        // 55d: max-only brake under friction — snaps ONTO the target at
        // the crossing, then friction keeps shedding below it (a brake
        // is a ceiling, not a motor)
        {
            Coaster co = new Coaster();
            co.v0 = 30;
            co.fricRoll = 0.03;
            co.fricAir = 1e-4;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection a = new ForceSection(flatLanes(8), 30);
            SpeedControl c = new SpeedControl(3, 4);
            c.type = SpeedControl.ACCEL;
            c.speed = 5;
            c.accel = 1.2;
            c.minLimit = false;
            a.speedCtls.add(c);
            p.sections.add(a);
            co.pieces.add(p);
            TrackCompiler.Result r = TrackCompiler.compile(co, dt);
            List<FVDIntegrator.Sample> s = r.samples();
            boolean hit5 = false;
            for (FVDIntegrator.Sample sm : s) {
                if (Math.abs(sm.v() - 5) < 1e-9) { hit5 = true; break; }
            }
            double vEnd = s.get(s.size() - 1).v();
            int last = s.size() - 1;
            check(hit5 && vEnd < 5 && vEnd > 3
                && Math.abs(vEnd - r.trueV()[last]) < 1e-9,
                String.format("55d brake w/ friction: lands on 5 exactly, "
                    + "then friction sheds to %.3f m/s; design == true",
                    vEnd));
        }

        // 55e: an INERT control (past the lane end) is arithmetic-
        // identical to none — every sample bitwise
        {
            ForceSection f1 = new ForceSection(hillLanes(6), 24);
            ForceSection f2 = new ForceSection(hillLanes(6), 24);
            SpeedControl c = new SpeedControl(50, 2);   // never reached
            f2.speedCtls.add(c);
            List<FVDIntegrator.Sample> s1 =
                TrackCompiler.passG(f1, entry, dt).samples();
            List<FVDIntegrator.Sample> s2 =
                TrackCompiler.passG(f2, entry, dt).samples();
            boolean same = s1.size() == s2.size();
            if (same) {
                for (int i = 0; i < s1.size(); i++) {
                    FVDIntegrator.Sample x = s1.get(i), y = s2.get(i);
                    if (x.v() != y.v() || !x.pos().equals(y.pos())) {
                        same = false;
                        break;
                    }
                }
            }
            check(same, "55e inert control = bitwise no-op "
                + "(the zero-controls contract)");
        }

        // 55f: node split partitions controls exactly (straddler clips
        // in two), merge re-covers the same interval
        {
            Coaster co = new Coaster();
            co.v0 = 24;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection fs = new ForceSection(hillLanes(6), 24);
            SpeedControl c = new SpeedControl(1, 2);   // [1,3] over cut 2
            c.speed = 12;
            fs.speedCtls.add(c);
            fs.speedMarks.add(4.5);
            p.sections.add(fs);
            co.pieces.add(p);
            // a node at the cut (splitSectionAt is node-anchored)
            boolean ok = co.splitSectionAt(0, 0, 2.0, dt);
            LaneSection L = (LaneSection) p.sections.get(0);
            LaneSection R = (LaneSection) p.sections.get(1);
            boolean parts = ok && L.speedCtls.size() == 1
                && R.speedCtls.size() == 1
                && Math.abs(L.speedCtls.get(0).x0 - 1) < 1e-9
                && Math.abs(L.speedCtls.get(0).len - 1) < 1e-9
                && Math.abs(R.speedCtls.get(0).x0 - 0) < 1e-9
                && Math.abs(R.speedCtls.get(0).len - 1) < 1e-9
                && R.speedMarks.size() == 1
                && Math.abs(R.speedMarks.get(0) - 2.5) < 1e-9;
            boolean merged = co.mergeSections(0, 0);
            LaneSection M = (LaneSection) p.sections.get(0);
            boolean cover = merged && M.speedCtls.size() == 2
                && Math.abs(M.speedCtls.get(0).x0 - 1) < 1e-9
                && Math.abs(M.speedCtls.get(1).x0 + M.speedCtls.get(1).len
                    - 3) < 1e-9
                && Math.abs(M.speedMarks.get(0) - 4.5) < 1e-9;
            check(parts && cover,
                "55f split partitions controls exactly; merge re-covers "
                + "[1,3] + the 4.5 mark");
        }

        // 55g: domain conversion maps the control to its ARC position
        {
            Coaster co = new Coaster();
            co.v0 = 24;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection fs = new ForceSection(hillLanes(6), 24);
            SpeedControl c = new SpeedControl(2, 1);
            c.speed = 12;
            fs.speedCtls.add(c);
            p.sections.add(fs);
            co.pieces.add(p);
            TrackCompiler.Result r0 = TrackCompiler.compile(co, dt);
            // the control's arc position before the flip
            double sAt2 = -1, sAt3 = -1;
            for (FVDIntegrator.Sample sm : r0.samples()) {
                if (sAt2 < 0 && sm.t() >= 2 - 1e-9) sAt2 = sm.s();
                if (sAt3 < 0 && sm.t() >= 3 - 1e-9) sAt3 = sm.s();
            }
            boolean ok = co.convertDomain(0, 0, dt, 0.5, Double.NaN);
            LaneSection conv = (LaneSection) p.sections.get(0);
            boolean carried = ok && conv.domain() == Section.Domain.DISTANCE
                && conv.speedCtls.size() == 1;
            double e0 = carried
                ? Math.abs(conv.speedCtls.get(0).x0 - sAt2) : 99;
            double e1 = carried
                ? Math.abs(conv.speedCtls.get(0).x0
                    + conv.speedCtls.get(0).len - sAt3) : 99;
            check(carried && e0 < 0.5 && e1 < 0.5,
                String.format("55g domain convert carries the control at "
                    + "its arc position (ends off by %.3f / %.3f m)",
                    e0, e1));
        }

        // 55h: geo + s/c sections clamp too (all three integrators)
        {
            List<List<BezierBaker.Node>> zero = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                zero.add(nodes(new double[][]{ {0, 0}, {5, 0} }));
            }
            GeoSection gs = new GeoSection(zero, 26);
            SpeedControl cg = new SpeedControl(1, 2);
            cg.speed = 12;
            gs.speedCtls.add(cg);
            List<FVDIntegrator.Sample> sg =
                TrackCompiler.passG(gs, entry, dt).samples();
            StraightCurveSection sc = StraightCurveSection.straight(100, 26);
            SpeedControl cs = new SpeedControl(20, 40);
            cs.speed = 15;
            sc.speedCtls.add(cs);
            List<FVDIntegrator.Sample> ss =
                TrackCompiler.passG(sc, entry, dt).samples();
            double vg = sg.get(sg.size() - 1).v();
            double vs = ss.get(ss.size() - 1).v();
            check(Math.abs(vg - 12) < 1e-9 && Math.abs(vs - 15) < 1e-9,
                String.format("55h geo + s/c clamp: geo end %.6f "
                    + "(12), s/c end %.6f (15)", vg, vs));
        }

        // 55i: a min-flag LIFT rescues a stalling climb in BOTH passes
        {
            Coaster co = new Coaster();
            co.v0 = 10;
            TrackPiece p = new TrackPiece(0, 5, 0, 0);
            // pull up 45° in 1 s, then climb STRAIGHT (a geo section:
            // pitch rate 0 = no curl-over as it slows) — the bare
            // ride runs out of energy on the climb and stalls
            // (fixture re-baselined after the 2026-08-18 gate
            // recovery: the recovered force fixture looped instead)
            List<List<BezierBaker.Node>> lanes = new ArrayList<>();
            lanes.add(nodes(new double[][]{
                {0, 45}, {1, 45}, {1.2, 0}, {10, 0} }));
            lanes.add(nodes(new double[][]{ {0, 0}, {10, 0} }));
            lanes.add(nodes(new double[][]{ {0, 0}, {10, 0} }));
            GeoSection fs = new GeoSection(lanes, 10);
            p.sections.add(fs);
            co.pieces.add(p);
            TrackCompiler.Result r0 = TrackCompiler.compile(co, dt);
            boolean stalls = false;
            for (double tv : r0.trueV()) {
                if (tv <= 0.1 + 1e-6) { stalls = true; break; }
            }
            SpeedControl lift = new SpeedControl(1.8, 10.2);
            lift.speed = 8;
            lift.maxLimit = false;   // pure lift: a floor, never a brake
            fs.speedCtls.add(lift);
            TrackCompiler.Result r1 = TrackCompiler.compile(co, dt);
            double worstT = 99, worstD = 99;
            for (int i = 0; i < r1.samples().size(); i++) {
                if (r1.samples().get(i).t() < 2.5) continue;
                worstT = Math.min(worstT, r1.trueV()[i]);
                worstD = Math.min(worstD, r1.samples().get(i).v());
            }
            check(stalls && worstT > 7.9 && worstD > 7.9,
                String.format("55i lift rescues the stall: bare climb "
                    + "stalls, with the lift design/true never drop "
                    + "below %.2f / %.2f m/s (floor 8)", worstD, worstT));
        }
    }

    static double vAtT(List<FVDIntegrator.Sample> s, double t) {
        for (FVDIntegrator.Sample sm : s) {
            if (Math.abs(sm.t() - t) < 0.006) return sm.v();
        }
        return Double.NaN;
    }

    // ========== v18.2: ROLL-ONLY CONVERTS (D169) ==========

    /** 55j: a convert changing ONLY the roll axis keeps lanes 0/1
     *  NODE-EXACT (the Gv/Gl targets mean the same thing under any
     *  roll representation) and the shape holds to the roll lane's
     *  own fit residual. */
    static void rollOnlyConvert() {
        double dt = 0.01;
        Coaster co = new Coaster();
        co.v0 = 24;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0, 1}, {2, 2.2}, {4, 0.4}, {6, 1} }));
        lanes.add(nodes(new double[][]{ {0, 0}, {6, 0} }));
        lanes.add(nodes(new double[][]{
            {0, 0}, {1.5, 40}, {3, 0}, {4.5, -40}, {6, 0} }));
        ForceSection fs = new ForceSection(lanes, 24);
        p.sections.add(fs);
        co.pieces.add(p);
        TrackCompiler.Result r0 = TrackCompiler.compile(co, dt);
        Vec3 end0 = r0.samples().get(r0.samples().size() - 1).pos();
        // deep copies of the original lanes 0/1 for the comparison
        List<List<BezierBaker.Node>> keep = new ArrayList<>();
        for (int L = 0; L < 2; L++) {
            List<BezierBaker.Node> cp = new ArrayList<>();
            for (BezierBaker.Node n : fs.lanes.get(L)) cp.add(n.copy());
            keep.add(cp);
        }
        boolean ok = co.convertWholeSection(0, 0, false, dt, 0.1,
            Double.NaN, Section.Domain.TIME, true, false, false, false);
        LaneSection conv = (LaneSection) p.sections.get(0);
        boolean lanesExact = ok && conv.rollGlobal;
        for (int L = 0; L < 2 && lanesExact; L++) {
            List<BezierBaker.Node> a = keep.get(L);
            List<BezierBaker.Node> b = conv.lanes.get(L);
            lanesExact = a.size() == b.size();
            for (int i = 0; lanesExact && i < a.size(); i++) {
                lanesExact = a.get(i).t == b.get(i).t
                    && a.get(i).val == b.get(i).val
                    && a.get(i).outDv == b.get(i).outDv
                    && a.get(i).inDv == b.get(i).inDv;
            }
        }
        TrackCompiler.Result r1 = TrackCompiler.compile(co, dt);
        Vec3 end1 = r1.samples().get(r1.samples().size() - 1).pos();
        double dev = end1.sub(end0).length();
        check(lanesExact && dev < 1.0, String.format(
            "55j roll-only convert: lanes 0/1 BIT-EXACT, ride end "
            + "moves %.3f m (the roll lane's own fit residual only)",
            dev));
    }
}
