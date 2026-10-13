package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY E (2026-09-25): the D249 LOCKSTEP inner
 *  settle (Coaster.settleLockstepAlign) and its REST GUARANTEE.
 *  Fixture = OrientModeGate.slowChain: head (hill, 26 m/s) → an
 *  OPT_POS_YAW bridge → a SLOW pitched LOCKSTEP target (turn lanes at
 *  vT, editPitch(pitch)) at (gapX, 14, 30) yaw 20. Per configuration:
 *   - a REPLICA of the settle loop (same recipe: pre-align, then
 *     capture → align rounds) counts rounds + per-round deltas, then
 *     restores the placement + re-captures (state returns bitwise);
 *   - the real optimizeBridge (timed), then a FRESH compile and a
 *     slot-by-slot diff of bs.optSignature() against the stamp;
 *   - the shell's rebuild loop verbatim (fast/exact/quiesced/ms,
 *     per-pass signature diffs kept for the verbose cases);
 *   - an idle second rebuild (no-op?);
 *   - simulated clicks: Length +1 m (optLen off), yaw / roll / pitch
 *     spinner +1° on the HELD axes, and an upstream editPitch(+1)
 *     (auto-pass only), each = write → optimizeBridge (or the knee)
 *     → rebuild, timed.
 *  Under optLen: knee off the compile seams, applied length (re-kneed?)
 *  and the knee off the SETTLED seams. Verbose detail prints for any
 *  configuration with > 6 loop passes, > 500 ms, a stamp≠capture, a
 *  non-quiesced loop or a restless idle rebuild. */
public class CascadeProbe_E {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len" };

    static int nCompile;
    static long tCompile;

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.2, 1}, {2.4, 3.2}, {4, 0.4}, {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.4}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> turnLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {3, 1.3}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, 0.5}, {4.5, 0.5}, {6, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {6, 0} }));
        return ls;
    }

    record Cfg(String grid, double vT, double pitch, double gapX, boolean optLen,
               double confP, double confR, boolean lockYaw, boolean lockPitch,
               boolean lockRoll) {
        String label() {
            return String.format(Locale.US,
                "%s v%.0f %+.0f° gap%.0f optLen=%b conflict=(%.0f,%.0f) locks Y%d P%d R%d",
                grid, vT, pitch, gapX, optLen, confP, confR,
                lockYaw ? 1 : 0, lockPitch ? 1 : 0, lockRoll ? 1 : 0);
        }
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    static Coaster build(Cfg k) {
        Coaster c = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(hillLanes(), 26));
        c.pieces.add(hA);
        TrackPiece hB = new TrackPiece(k.gapX, 14, 30, 20);
        hB.sections.add(new ForceSection(turnLanes(), k.vT));
        hB.orientMode = TrackPiece.OrientMode.LOCKSTEP;
        hB.editPitch(k.pitch);
        if (k.confP != 0) hB.designPitchDeg = hB.pitchDeg - k.confP;
        if (k.confR != 0) hB.designRollDeg = TrackPiece.wrap360(hB.rollDeg - k.confR);
        c.pieces.add(hB);
        TrackCompiler.Result r0 = compile(c);
        BridgeSection bs = BridgeSection.solve(r0.spans().get(0).exit(),
            hB.entryPose(), r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_POS_YAW;
        bs.lockYaw = k.lockYaw;
        bs.lockPitch = k.lockPitch;
        bs.lockRoll = k.lockRoll;
        bs.optLen = k.optLen;
        hA.sections.add(bs);
        compile(c);   // captures the seams; optSig stays null (loads never move pieces)
        return c;
    }

    static BridgeSection bridgeOf(Coaster c) {
        return (BridgeSection) c.pieces.get(0).sections.get(1);
    }

    static double[] placement(TrackPiece t) {
        return new double[] { t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
            t.designPitchDeg, t.designRollDeg };
    }

    static void restore(TrackPiece t, double[] s) {
        t.x = s[0]; t.y = s[1]; t.z = s[2]; t.yawDeg = s[3]; t.pitchDeg = s[4];
        t.rollDeg = s[5]; t.designPitchDeg = s[6]; t.designRollDeg = s[7];
    }

    static long[] bits(double[] d) {
        long[] o = new long[d.length];
        for (int i = 0; i < d.length; i++) o[i] = Double.doubleToLongBits(d[i]);
        return o;
    }

    /** bs.optSignature() (off the CURRENT bs seam state) vs the stamp. */
    static String sigDiff(BridgeSection bs) {
        double[] sig = bs.optSignature();
        double[] st = bs.optSig;
        if (st == null) return "stamp=null(first sight)";
        if (st.length == 0) return "stamp=STALE";
        double max = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sig.length; i++) {
            double d = Math.abs(sig[i] - st[i]);
            max = Math.max(max, d);
            if (d > 1e-9) sb.append(String.format(Locale.US, " %s[%d](%.9g vs stamp %.9g Δ%.3g)",
                SLOT[i], i, sig[i], st[i], d));
        }
        return max <= 1e-9 ? String.format(Locale.US, "match(max %.2g)", max)
            : String.format(Locale.US, "DIFF max %.3g:%s", max, sb);
    }

    static class Loop {
        int compiles, fast, exact;
        boolean quiesced;
        double ms;
        List<String> perPass = new ArrayList<>();
        TrackCompiler.Result result;
    }

    /** FvdDesigner.rebuildTrack's loop verbatim (at rest), with the
     *  bridge's signature diff recorded BEFORE each pass's auto call. */
    static Loop rebuild(Coaster c) {
        BridgeSection bs = bridgeOf(c);
        Loop L = new Loop();
        long t0 = System.nanoTime();
        TrackCompiler.Result r = compile(c);
        L.compiles = 1;
        for (int pass = 0; pass < 12; pass++) {
            L.fast++;
            String sd = sigDiff(bs);
            boolean recon = c.reconcileChainedSpeeds(r);
            boolean auto = c.autoReoptimizeBridges(r);
            L.perPass.add("fast" + pass + ": recon=" + recon + " auto=" + auto + " sig " + sd);
            if (!(recon || auto)) break;
            r = compile(c);
            L.compiles++;
        }
        for (int pass = 0; pass < 40; pass++) {
            L.exact++;
            String sd = sigDiff(bs);
            boolean recon = c.reconcileChainedSpeeds(r, true);
            boolean auto = c.autoReoptimizeBridges(r);
            L.perPass.add("exact" + pass + ": recon=" + recon + " auto=" + auto + " sig " + sd);
            if (!(recon || auto)) { L.quiesced = true; break; }
            r = compile(c);
            L.compiles++;
        }
        L.ms = (System.nanoTime() - t0) / 1e6;
        L.result = r;
        return L;
    }

    /** Replica of optimizeBridgeBody's align + settleLockstepAlign, counting
     *  rounds and per-round deltas; restores the placement and re-captures
     *  so the coaster returns to its pre-replica state (checked bitwise). */
    static String settleReplica(Coaster c, int ti, double dist) {
        BridgeSection bs = bridgeOf(c);
        TrackPiece t = c.pieces.get(ti);
        boolean[] align = { !bs.lockYaw, !bs.lockPitch, !bs.lockRoll };
        double[] start = placement(t);
        double[] sig0 = bs.optSignature();
        long t0 = System.nanoTime();
        c.alignPieceForBridge(bs, ti, dist, true);   // the pre-settle align
        if (!align[1] && !align[2]) {
            restore(t, start);
            return "no settle (pitch+roll locked)";
        }
        StringBuilder d = new StringBuilder();
        int rounds = 0;
        boolean conv = false, clamp = false;
        double best = Double.POSITIVE_INFINITY;
        int bestR = -1;
        double firstDelta = Double.NaN, lastDelta = Double.NaN;
        for (int round = 0; round < 24; round++) {
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            double[] pre = placement(t);
            c.alignPieceForBridge(bs, ti, dist, true);
            double delta = Math.max(Math.abs(TrackPiece.wrap180(t.yawDeg - pre[3])),
                Math.max(Math.abs(t.pitchDeg - pre[4]),
                    Math.abs(TrackPiece.wrap180(t.rollDeg - pre[5]))));
            rounds++;
            if (round == 0) firstDelta = delta;
            lastDelta = delta;
            if (round < 5 || round >= 21) d.append(String.format(Locale.US, " %.1e", delta));
            else if (round == 5) d.append(" …");
            if (delta < best) { best = delta; bestR = round; }
            if (delta < 1e-9) { conv = true; break; }
            if (align[1] && Math.abs(t.pitchDeg) >= 85) { clamp = true; break; }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        restore(t, start);
        TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        double[] sig1 = bs.optSignature();
        boolean restored = Arrays.equals(bits(sig0), bits(sig1));
        // gain estimate: ratio of consecutive deltas over the last rounds
        return String.format(Locale.US,
            "rounds %d conv=%b clamp=%b bestδ %.2e@r%d first %.2e last %.2e (%.0f ms) deltas:%s restored=%b",
            rounds, conv, clamp, best, bestR, firstDelta, lastDelta, ms, d, restored);
    }

    static String landing(Coaster c, TrackCompiler.Result r) {
        BridgeSection bs = bridgeOf(c);
        TrackPiece t = c.pieces.get(1);
        List<double[]> spine = Coaster.marchSpine(bs, true);
        TrackPose ex = r.spans().get(1).exit();
        double miss = ex.pos().sub(t.entryPose().pos()).length();
        double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            ex.T().dot(bs.solvedTarget.T())))));
        return String.format(Locale.US, "%s miss %.2e m tAng %.3f°",
            spine != null ? "MARCH" : "SEPTIC", miss, ang);
    }

    /** One simulated click: write → optimizeBridge (knee under optLen,
     *  distOverride > 0 = the Length spinner, else lastOptDist) → rebuild. */
    static String click(Coaster c, String name, Runnable write, double distOverride,
                        boolean explicitOpt, List<String> verboseSink) {
        BridgeSection bs = bridgeOf(c);
        TrackPiece t = c.pieces.get(1);
        nCompile = 0; tCompile = 0;
        long t0 = System.nanoTime();
        write.run();
        double d = Double.NaN;
        double optMs = 0;
        if (explicitOpt) {
            long t1 = System.nanoTime();
            if (bs.optLen) d = c.optimalBridgeLength(bs, true);
            else d = distOverride > 0 ? distOverride : (bs.lastOptDist > 0 ? bs.lastOptDist : 1);
            c.optimizeBridge(bs, 1, d, true);
            optMs = (System.nanoTime() - t1) / 1e6;
        }
        Loop L = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        String s = String.format(Locale.US,
            "%s: %.0f ms (opt %.0f + loop %.0f; %d compiles %.0f ms) f%d/e%d q=%b applied %.3f pitch %.3f/%.3f",
            name, ms, optMs, L.ms, nCompile, tCompile / 1e6, L.fast, L.exact, L.quiesced,
            bs.lastOptDist, t.pitchDeg, t.designPitchDeg);
        if (L.compiles > 6 || ms > 500 || !L.quiesced) {
            verboseSink.add("    " + name + " per-pass:");
            for (String pp : L.perPass) verboseSink.add("      " + pp);
        }
        return s;
    }

    static int configs, findings;

    static void run(Cfg k) {
        configs++;
        Coaster c = build(k);
        BridgeSection bs = bridgeOf(c);
        TrackPiece t = c.pieces.get(1), hA = c.pieces.get(0);
        List<String> verbose = new ArrayList<>();
        double chord = new Vec3(t.x, t.y, t.z).sub(bs.solvedEntry.pos()).length();
        double dist0;
        double kneeMs = 0;
        if (k.optLen) {
            long t0 = System.nanoTime();
            dist0 = c.optimalBridgeLength(bs, true);
            kneeMs = (System.nanoTime() - t0) / 1e6;
        } else {
            dist0 = chord * 1.2;
        }
        String settle = settleReplica(c, 1, dist0);
        // the real optimize
        nCompile = 0; tCompile = 0;
        long t0 = System.nanoTime();
        c.optimizeBridge(bs, 1, dist0, true);
        double optMs = (System.nanoTime() - t0) / 1e6;
        double applied = bs.lastOptDist;
        double[] stamp = bs.optSig.clone();
        double[] pbOpt = placement(t);
        // FRESH compile: does the capture reproduce the stamp?
        TrackCompiler.Result rc = compile(c);
        String stampDiff = sigDiff(bs);
        boolean stampMatch = stampDiff.startsWith("match");
        String kneeInfo = "";
        if (k.optLen) {
            double k2 = c.optimalBridgeLength(bs, true);
            kneeInfo = String.format(Locale.US,
                " | knee0 %.3f (%.0f ms) applied %.3f rekneed=%b settledKnee %.3f Δ%.2e",
                dist0, kneeMs, applied, Math.abs(applied - dist0) > 1e-9, k2,
                Math.abs(k2 - applied));
        }
        String land = landing(c, rc);
        // rebuild 1 + idle rebuild
        Loop L1 = rebuild(c);
        double[] pb1 = placement(t);
        Loop L2 = rebuild(c);
        boolean noop = L2.compiles == 1 && Arrays.equals(bits(pb1), bits(placement(t)));
        boolean movedInLoop = !Arrays.equals(bits(pbOpt), bits(pb1));
        // clicks
        List<String> clicks = new ArrayList<>();
        if (!k.optLen) clicks.add(click(c, "Length+1", () -> {}, bs.lastOptDist + 1, true, verbose));
        if (k.lockYaw) clicks.add(click(c, "yaw+1", () -> t.setDispYaw(t.dispYaw() + 1), 0, true, verbose));
        if (k.lockPitch) clicks.add(click(c, "pitch+1", () -> t.setDispPitch(t.dispPitch() + 1, true), 0, true, verbose));
        if (k.lockRoll) clicks.add(click(c, "roll+1", () -> t.setDispRoll(t.dispRoll() + 1, true), 0, true, verbose));
        clicks.add(click(c, "upstream editPitch+1", () -> hA.editPitch(hA.pitchDeg + 1), 0, false, verbose));
        clicks.add(click(c, "idle", () -> {}, 0, false, verbose));

        boolean flag = !stampMatch || L1.compiles > 6 || L1.ms > 500 || !L1.quiesced || !noop
            || !L2.quiesced;
        for (String cl : clicks) {
            if (cl.contains("q=false")) flag = true;
        }
        if (!verbose.isEmpty()) flag = true;
        if (flag) findings++;
        System.out.printf(Locale.US, "%s[%s]%n", flag ? "!! " : "   ", k.label());
        System.out.printf(Locale.US, "    settle replica: %s%n", settle);
        System.out.printf(Locale.US,
            "    optimizeBridge %.0f ms (chord %.1f, dist0 %.3f, applied %.3f) → pitch %.4f design %.4f roll %.4f design %.4f yaw %.3f%s%n",
            optMs, chord, dist0, applied, t.pitchDeg, t.designPitchDeg, t.rollDeg,
            t.designRollDeg, t.yawDeg, kneeInfo);
        System.out.printf(Locale.US, "    stamp vs fresh capture: %s | render %s%n", stampDiff, land);
        System.out.printf(Locale.US,
            "    rebuild1: %.0f ms compiles %d f%d/e%d q=%b movedTarget=%b | idle rebuild: compiles %d f%d/e%d q=%b noop=%b%n",
            L1.ms, L1.compiles, L1.fast, L1.exact, L1.quiesced, movedInLoop,
            L2.compiles, L2.fast, L2.exact, L2.quiesced, noop);
        if (L1.compiles > 6 || L1.ms > 500 || !L1.quiesced || !noop) {
            System.out.println("    rebuild1 per-pass:");
            for (String pp : L1.perPass) System.out.println("      " + pp);
            if (!noop) {
                System.out.println("    idle rebuild per-pass:");
                for (String pp : L2.perPass) System.out.println("      " + pp);
            }
        }
        for (String cl : clicks) System.out.println("    click " + cl);
        for (String v : verbose) System.out.println(v);
    }

    public static void main(String[] a) {
        boolean quick = a.length > 0 && a[0].equals("quick");
        double[] vTs = { 2, 4, 6, 8 };
        double[] pitches = { 25, -25, 30, -30 };
        // warm-up (JIT), output discarded
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        for (int w = 0; w < 2; w++) {
            run(new Cfg("warm", 8, 30, 40, false, 0, 0, false, false, false));
            run(new Cfg("warm", 5, -25, 30, true, 0, 0, false, false, false));
        }
        System.setOut(out);
        configs = 0; findings = 0;

        System.out.println("=== GRID A: yaw/pitch/roll all FREE (the g7 fixture), conflict × optLen ===");
        double[][] conflicts = quick ? new double[][]{ {0, 0} } : new double[][]{ {0, 0}, {6, 0}, {0, 10} };
        for (double vT : vTs) for (double pitch : pitches) for (boolean optLen : new boolean[]{ false, true })
            for (double[] cf : conflicts)
                run(new Cfg("A", vT, pitch, 5 * vT, optLen, cf[0], cf[1], false, false, false));

        System.out.println("=== GRID B: yaw HELD (the yaw spinner is clickable), pitch/roll FREE ===");
        for (double vT : vTs) for (double pitch : pitches) for (boolean optLen : new boolean[]{ false, true })
            run(new Cfg("B", vT, pitch, 5 * vT, optLen, 0, 0, true, false, false));

        System.out.println("=== GRID C: roll HELD (the roll spinner is clickable), yaw/pitch FREE ===");
        for (double vT : vTs) for (double pitch : pitches) for (boolean optLen : new boolean[]{ false, true })
            run(new Cfg("C", vT, pitch, 5 * vT, optLen, 0, 0, false, false, true));

        System.out.println("=== GRID D: pitch HELD (pitch spinner clickable), roll FREE — roll-only settle ===");
        for (double vT : vTs) for (double pitch : new double[]{ 30, -30 }) for (boolean optLen : new boolean[]{ false, true })
            run(new Cfg("D", vT, pitch, 5 * vT, optLen, 0, 0, false, true, false));

        System.out.println("=== GRID E: pitch FREE at longer gaps (10×vT) + conflict, optLen both ===");
        for (double vT : vTs) for (double pitch : new double[]{ 30, -30 }) for (boolean optLen : new boolean[]{ false, true })
            for (double[] cf : new double[][]{ {0, 0}, {6, 10} })
                run(new Cfg("E", vT, pitch, 10 * vT, optLen, cf[0], cf[1], false, false, false));

        System.out.printf(Locale.US, "=== configs %d flagged %d ===%n", configs, findings);
    }
}
