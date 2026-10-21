package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY B (2026-09-25): what the bridge TARGET's
 *  FIRST section is. Builds a two-piece fixture headlessly (piece 0 =
 *  a curving force section + an OPT_POS_YAW bridge; piece 1 = the
 *  variant first section + a plain 3 s force hold), optimizes the
 *  bridge at 30 m, then simulates bridge-window spinner clicks
 *  exactly like BridgeSlowProbe.click (target orientation write →
 *  optimizeBridge at lastOptDist / the knee → the shell's rebuild
 *  loop verbatim). Per configuration prints fast/exact pass counts,
 *  quiesced, ms per click; for > 6 passes or > 500 ms it prints the
 *  signature slots that differed between the stamp and each pass's
 *  fresh capture (BridgeSection.optSignature index + values). */
public class CascadeProbe_TargetHead {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign(A end)",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len" };

    // ---- per-rebuild bookkeeping
    static List<String> passDiffs = new ArrayList<>();
    static int nCompile;
    static long tCompile, tReopt, tOpt;

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    /** Which signature slots differ (stamp vs fresh) for every live
     *  optimize-mode bridge — the mechanism readout. */
    static String sigDiffs(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            if (secs.isEmpty()) continue;
            if (!(secs.get(secs.size() - 1) instanceof BridgeSection bs)) continue;
            if (bs.optMode == BridgeSection.OPT_NONE || p + 1 >= c.pieces.size()) continue;
            if (bs.optSig == null) { sb.append(" b").append(p).append(":[first sight]"); continue; }
            if (bs.optSig.length == 0) { sb.append(" b").append(p).append(":[SIG_STALE]"); continue; }
            double[] fresh = bs.optSignature();
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < fresh.length; i++) {
                if (Math.abs(fresh[i] - bs.optSig[i]) > 1e-9) {
                    d.append(String.format(Locale.US, " %d:%s %.6g->%.6g", i, SLOT[i],
                        bs.optSig[i], fresh[i]));
                }
            }
            if (d.length() > 0) sb.append(" b").append(p).append(":[").append(d).append(" ]");
        }
        return sb.toString();
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). */
    static int[] rebuild(Coaster c) {
        passDiffs.clear();
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            boolean again = c.reconcileChainedSpeeds(r);
            String d = sigDiffs(c);
            long t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tReopt += System.nanoTime() - t0;
            if (re) { again = true; passDiffs.add("fast" + pass + ":" + d); }
            else if (again) passDiffs.add("fast" + pass + ": reconcile only");
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            boolean again = c.reconcileChainedSpeeds(r, true);
            String d = sigDiffs(c);
            long t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tReopt += System.nanoTime() - t0;
            if (re) { again = true; passDiffs.add("exact" + pass + ":" + d); }
            else if (again) passDiffs.add("exact" + pass + ": reconcile only");
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return new int[]{ fast, exact, quiesced ? 1 : 0, r.samples().size() };
    }

    // ---- fixture builders --------------------------------------------

    static List<List<BezierBaker.Node>> lanes3(double[][]... perLane) {
        List<List<BezierBaker.Node>> l = new ArrayList<>();
        for (double[][] ln : perLane) {
            List<BezierBaker.Node> nodes = new ArrayList<>();
            for (double[] n : ln) nodes.add(new BezierBaker.Node(n[0], n[1]));
            BezierBaker.applyDefaultHandles(nodes);
            l.add(nodes);
        }
        return l;
    }

    static List<List<BezierBaker.Node>> hold(double a, double b, double cc, double len) {
        return lanes3(new double[][]{{0, a}, {len, a}}, new double[][]{{0, b}, {len, b}},
            new double[][]{{0, cc}, {len, cc}});
    }

    /** The variant first section for the target piece. */
    static Section variant(String name, double v) {
        switch (name) {
            case "force-time": return new ForceSection(hold(1.2, 0.2, 0, 3), v);
            case "force-curving": return new ForceSection(lanes3(
                new double[][]{{0, 1.0}, {1.5, 1.8}, {3, 1.0}},
                new double[][]{{0, 0.0}, {1.5, 0.4}, {3, 0.0}},
                new double[][]{{0, 0}, {3, 0}}), v);
            case "geo-local": return new GeoSection(hold(6, 10, 0, 3), v);
            case "geo-global": { GeoSection g = new GeoSection(hold(6, 10, 0, 3), v);
                g.yawPitchGlobal = true; g.rollGlobal = true; return g; }
            case "geo-perM": { GeoSection g = new GeoSection(hold(0.3, 0.5, 0, 3), v);
                g.ratePerMeter = true; return g; }
            case "geo-ypAbs": { GeoSection g = new GeoSection(lanes3(
                new double[][]{{0, 0}, {3, 10}}, new double[][]{{0, 0}, {3, 20}},
                new double[][]{{0, 0}, {3, 0}}), v);
                g.yawPitchGlobal = true; g.rollGlobal = true; g.ypAbsolute = true; return g; }
            case "geo-ypAbs-offset": { GeoSection g = new GeoSection(lanes3(
                new double[][]{{0, 5}, {3, 10}}, new double[][]{{0, 0}, {3, 20}},
                new double[][]{{0, 0}, {3, 0}}), v);
                g.yawPitchGlobal = true; g.rollGlobal = true; g.ypAbsolute = true; return g; }
            case "sc-rate": { List<StraightCurveSection.Element> els = new ArrayList<>();
                StraightCurveSection.Element e0 = StraightCurveSection.Element.curve(30, 45, 0, 2);
                e0.rollMode = StraightCurveSection.RollMode.RATE; e0.rollVal = 20;
                els.add(e0);
                StraightCurveSection.Element e1 = StraightCurveSection.Element.straight(10, 2);
                e1.rollMode = StraightCurveSection.RollMode.RATE; e1.rollVal = 0;
                els.add(e1);
                return new StraightCurveSection(els, v); }
            case "sc-global": { List<StraightCurveSection.Element> els = new ArrayList<>();
                els.add(StraightCurveSection.Element.curve(30, 45, 0, 2));   // GLOBAL 0
                els.add(StraightCurveSection.Element.straight(10, 2));
                return new StraightCurveSection(els, v); }
            case "sc-global-bank": { List<StraightCurveSection.Element> els = new ArrayList<>();
                StraightCurveSection.Element e0 = StraightCurveSection.Element.curve(30, 45, 0, 2);
                e0.rollVal = 25;   // GLOBAL bank 25°
                els.add(e0);
                els.add(StraightCurveSection.Element.straight(10, 2));
                return new StraightCurveSection(els, v); }
            case "sc-global-sharp": { List<StraightCurveSection.Element> els = new ArrayList<>();
                StraightCurveSection.Element e0 = StraightCurveSection.Element.curve(30, 45, 0, 0);
                els.add(e0);   // blendIn 0 = instant bank snap at entry
                els.add(StraightCurveSection.Element.straight(10, 2));
                return new StraightCurveSection(els, v); }
            case "force-dist": { ForceSection f = new ForceSection(lanes3(
                new double[][]{{0, 1.0}, {20, 1.6}, {50, 1.0}},
                new double[][]{{0, 0.2}, {50, 0.2}},
                new double[][]{{0, 0}, {50, 0}}), v);
                f.domain = Section.Domain.DISTANCE; return f; }
            case "force-rollGlobal": { ForceSection f = new ForceSection(hold(1.2, 0.2, 10, 3), v);
                f.rollGlobal = true; return f; }
            case "force-rollAbs": { ForceSection f = new ForceSection(lanes3(
                new double[][]{{0, 1.2}, {3, 1.2}}, new double[][]{{0, 0.2}, {3, 0.2}},
                new double[][]{{0, 0}, {3, 30}}), v);
                f.rollGlobal = true; f.rollAbsolute = true; return f; }
            case "force-rollAbs-offset": { ForceSection f = new ForceSection(lanes3(
                new double[][]{{0, 1.2}, {3, 1.2}}, new double[][]{{0, 0.2}, {3, 0.2}},
                new double[][]{{0, 15}, {3, 30}}), v);
                f.rollGlobal = true; f.rollAbsolute = true; return f; }
            case "influenced": { ForceSection f = new ForceSection(hold(1.2, 0.2, 0, 3), v);
                Coaster.enterInfluence(f, 3);
                for (BezierBaker.Node n : f.deltaLanes.get(0)) n.val = 8;   // Δpitch 8 °/s
                for (BezierBaker.Node n : f.deltaLanes.get(1)) n.val = -5;  // Δyaw −5 °/s
                f.editInfluence = false; return f; }
            case "ctl-launch": { ForceSection f = new ForceSection(hold(1.2, 0.2, 0, 3), v);
                SpeedControl sc = new SpeedControl(0.2, 2.0);
                sc.type = SpeedControl.SPEED; sc.speed = 30; sc.minLimit = true; sc.maxLimit = false;
                f.speedCtls.add(sc); return f; }
            case "ctl-brake": { ForceSection f = new ForceSection(hold(1.2, 0.2, 0, 3), v);
                SpeedControl sc = new SpeedControl(0.2, 2.0);
                sc.type = SpeedControl.SPEED; sc.speed = 8; sc.minLimit = false; sc.maxLimit = true;
                f.speedCtls.add(sc); return f; }
            case "ctl-launch-at0": { ForceSection f = new ForceSection(hold(1.2, 0.2, 0, 3), v);
                SpeedControl sc = new SpeedControl(0.0, 2.0);
                sc.type = SpeedControl.SPEED; sc.speed = 30; sc.minLimit = true; sc.maxLimit = false;
                f.speedCtls.add(sc); return f; }
            case "ctl-accel-at0": { ForceSection f = new ForceSection(hold(1.2, 0.2, 0, 3), v);
                SpeedControl sc = new SpeedControl(0.0, 2.0);
                sc.type = SpeedControl.ACCEL; sc.speed = 30; sc.accel = 1.0; sc.decel = 1.0;
                sc.minLimit = true; sc.maxLimit = false;
                f.speedCtls.add(sc); return f; }
            case "stall": return new ForceSection(hold(1.0, 0.0, 0, 3), 0.6);   // climb from baseOrient
            case "stall-1ms": return new ForceSection(hold(1.0, 0.0, 0, 3), 1.0);
            case "slow-level": return new ForceSection(hold(1.0, 0.0, 0, 3), 0.6);   // no climb
            case "slow-level-2ms": return new ForceSection(hold(1.0, 0.0, 0, 3), 2.0);
            case "slow-level-5ms": return new ForceSection(hold(1.0, 0.0, 0, 3), 5.0);
            case "short-6samples": return new ForceSection(hold(1.2, 0.2, 0, 0.05), v);
            case "short-9samples": return new ForceSection(hold(1.2, 0.2, 0, 0.08), v);
            case "one-node-lane0": return new ForceSection(lanes3(
                new double[][]{{0, 1.2}}, new double[][]{{0, 0.2}, {3, 0.2}},
                new double[][]{{0, 0}, {3, 0}}), v);
            case "all-one-node": return new ForceSection(lanes3(
                new double[][]{{0, 1.2}}, new double[][]{{0, 0.2}}, new double[][]{{0, 0}}), v);
            case "reversed-legacy": { ForceSection f = new ForceSection(lanes3(
                new double[][]{{0, 1.0}, {3, 1.6}}, new double[][]{{0, 0.0}, {3, 0.3}},
                new double[][]{{0, 0}, {3, 0}}), v);
                f.reversed = true; return f; }
            default: throw new IllegalArgumentException(name);
        }
    }

    /** Build the fixture: piece 0 (curving force + bridge) → piece 1
     *  (variant + 3 s hold). Returns the coaster with the bridge
     *  optimized at 30 m and the rebuild loop settled. */
    static Coaster build(String variant, TrackPiece.OrientMode mode, boolean pitchFree,
                         boolean optLen, boolean climb, boolean friction) {
        Coaster c = new Coaster();
        c.v0 = 20;
        if (friction) { c.fricRoll = 0.03; c.fricAir = 1.0e-4; }
        TrackPiece p0 = new TrackPiece(0, 21, 0, 0);
        p0.sections.add(new ForceSection(lanes3(
            new double[][]{{0, 1.0}, {3, 1.0}, {4, 1.6}},
            new double[][]{{0, 0.0}, {3, 0.0}, {4, 0.3}},
            new double[][]{{0, 0}, {4, 0}}), 20));
        c.pieces.add(p0);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        TrackPose ex = r0.spans().get(0).exit();
        double vEnd = r0.samples().get(r0.spans().get(0).i1()).v();
        Vec3 ahead = ex.pos().add(ex.T().scale(30)).add(new Vec3(0, -4, 8));
        double head = Math.toDegrees(Math.atan2(-ex.T().z(), ex.T().x()));
        TrackPiece t = new TrackPiece(ahead.x(), ahead.y(), ahead.z(), head + 25);
        if (climb) {
            // pitch the piece's natural orientation UP 20° (base, not a delta)
            t.baseOrient = Quat.aboutAxis(new Vec3(0, 0, 1), Math.toRadians(20));
        }
        t.orientMode = mode;
        t.sections.add(variant(variant, 18));
        t.sections.add(new ForceSection(hold(1.0, 0.0, 0, 3), 18));
        c.pieces.add(t);
        BridgeSection bs = BridgeSection.solve(ex, t.entryPose(), vEnd);
        bs.optMode = BridgeSection.OPT_POS_YAW;
        bs.lockYaw = false;
        bs.lockPitch = !pitchFree;
        bs.lockRoll = true;
        bs.optLen = optLen;
        p0.sections.add(bs);
        c.optimizeBridge(bs, 1, 30, true);
        rebuild(c);
        if (optLen) {
            double d = c.optimalBridgeLength(bs, true);
            c.optimizeBridge(bs, 1, d, true);
            rebuild(c);
        }
        rebuild(c);   // settle to rest
        return c;
    }

    static double[] click(Coaster c, int axis, double delta) {
        BridgeSection bs = (BridgeSection) c.pieces.get(0).sections
            .get(c.pieces.get(0).sections.size() - 1);
        TrackPiece t = c.pieces.get(1);
        nCompile = 0; tCompile = tReopt = tOpt = 0;
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        long t1 = System.nanoTime();
        if (bs.optLen) {
            double d = c.optimalBridgeLength(bs, true);
            c.optimizeBridge(bs, 1, d, true);
        } else {
            c.optimizeBridge(bs, 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, true);
        }
        tOpt += System.nanoTime() - t1;
        int[] loop = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        return new double[]{ loop[0], loop[1], loop[2], ms, loop[3] };
    }

    record Row(String cfg, int fast, int exact, boolean quiesced, double ms, String diffs,
               String extra) {}

    static List<Row> rows = new ArrayList<>();

    static void runConfig(String variant, String modeLabel, TrackPiece.OrientMode mode,
                          boolean pitchFree, boolean optLen, boolean climb, boolean friction,
                          boolean print) {
        Coaster c;
        try {
            c = build(variant, mode, pitchFree, optLen, climb, friction);
        } catch (Exception ex) {
            if (print) System.out.printf(Locale.US, "  %-22s %-12s BUILD FAILED: %s%n", variant, modeLabel, ex);
            rows.add(new Row(variant + " / " + modeLabel, -1, -1, false, -1, "", "build failed: " + ex));
            return;
        }
        BridgeSection bs = (BridgeSection) c.pieces.get(0).sections
            .get(c.pieces.get(0).sections.size() - 1);
        TrackPiece t = c.pieces.get(1);
        int[] axes = pitchFree ? new int[]{ 2, 2, 2 } : new int[]{ 1, 1, 2 };
        double[] deltas = { 1, 1, -1 };
        StringBuilder line = new StringBuilder();
        int worstPasses = 0; double worstMs = 0; String worstDiffs = ""; boolean allQ = true;
        for (int k = 0; k < 3; k++) {
            double[] res = click(c, axes[k], deltas[k]);
            int passes = (int) res[0] + (int) res[1];
            boolean q = res[2] == 1;
            allQ &= q;
            line.append(String.format(Locale.US, " | click%d(%s): %d+%d %s %.0f ms (opt %.0f, reopt %.0f, compile %d×%.0f)",
                k + 1, axes[k] == 1 ? "pitch" : axes[k] == 2 ? "roll" : "yaw",
                (int) res[0], (int) res[1], q ? "Q" : "NOT-QUIESCED", res[3],
                tOpt / 1e6, tReopt / 1e6, nCompile, tCompile / 1e6));
            if (passes > worstPasses || res[3] > worstMs) {
                worstPasses = Math.max(worstPasses, passes);
                worstMs = Math.max(worstMs, res[3]);
                worstDiffs = String.join("\n        ", passDiffs);
            }
        }
        // direct cost attribution at rest: one marchSpine (what EVERY
        // compile pays per bridge), one knee (what optLen pays per
        // click), the bridge span's arc/samples, the septic tangents
        long m0 = System.nanoTime();
        boolean lands = Coaster.marchSpine(bs, true) != null;
        double msMarch = (System.nanoTime() - m0) / 1e6;
        m0 = System.nanoTime();
        double knee = c.optimalBridgeLength(bs, true);
        double msKnee = (System.nanoTime() - m0) / 1e6;
        TrackCompiler.Result rr = TrackCompiler.compile(c, DT);
        TrackCompiler.Span bsp = rr.spans().get(c.pieces.get(0).sections.size() - 1);
        TrackCompiler.Span hsp = rr.spans().get(c.pieces.get(0).sections.size());
        double chord = new Vec3(t.x, t.y, t.z).sub(bs.solvedEntry.pos()).length();
        String extra = String.format(Locale.US,
            "march lands=%b (marchSpine %.0f ms) knee=%.2f m (%.0f ms) lastOptDist=%.2f | bridge arc=%.1f m samples=%d chord=%.1f lenA/B=%.1f | head arc=%.1f m samples=%d | tgt true=(%.3f,%.3f) design=(%.3f,%.3f) tB.y=%.4f vB=%.2f accB=|%.4g| rollB=%.4g | total samples=%d",
            lands, msMarch, knee, msKnee, bs.lastOptDist,
            bsp.sLen(), bsp.i1() - bsp.i0() + 1, chord, bs.lenA,
            hsp.sLen(), hsp.i1() - hsp.i0() + 1,
            t.pitchDeg, t.rollDeg, t.designPitchDeg, t.designRollDeg,
            bs.solvedTarget.T().y(), bs.vB, bs.accB.length(), bs.rollB,
            rr.samples().size());
        if (print) {
            System.out.printf(Locale.US, "  %-22s %-14s%s%n      %s%n", variant, modeLabel, line, extra);
            if (worstPasses > 6 || worstMs > 500) {
                System.out.println("      >>> PASS-BY-PASS SIGNATURE DIFFS (stamp -> fresh):\n        " + worstDiffs);
            }
        }
        rows.add(new Row(variant + " / " + modeLabel, worstPasses, 0, allQ, worstMs,
            worstPasses > 6 || worstMs > 500 ? worstDiffs : "", extra));
    }

    public static void main(String[] a) {
        String[] variants = {
            "force-time", "force-curving", "geo-local", "geo-global", "geo-perM",
            "geo-ypAbs", "geo-ypAbs-offset", "sc-rate", "sc-global", "sc-global-bank",
            "sc-global-sharp", "force-dist", "force-rollGlobal", "force-rollAbs",
            "force-rollAbs-offset", "influenced", "ctl-launch", "ctl-brake",
            "ctl-launch-at0", "ctl-accel-at0", "stall", "stall-1ms", "short-6samples",
            "short-9samples", "one-node-lane0", "all-one-node", "reversed-legacy" };
        boolean friction = !"0".equals(System.getProperty("fric", "1"));
        String subset = System.getProperty("subset");
        if (subset != null) variants = subset.split(",");
        for (int rep = 0; rep < 2; rep++) {
            boolean print = rep == 1;
            rows.clear();
            if (print) System.out.println("=== FAMILY B: target first section (warm run; friction=" + friction + ") ===");
            for (String v : variants) {
                boolean climb = v.startsWith("stall");
                runConfig(v, "REAL_ONLY P/R🔒", TrackPiece.OrientMode.REAL_ONLY, false, false, climb, friction, print);
                runConfig(v, "LOCKSTEP pitch🔓", TrackPiece.OrientMode.LOCKSTEP, true, false, climb, friction, print);
                runConfig(v, "LOCKSTEP 🔓+optLen", TrackPiece.OrientMode.LOCKSTEP, true, true, climb, friction, print);
                runConfig(v, "REAL_ONLY +optLen", TrackPiece.OrientMode.REAL_ONLY, false, true, climb, friction, print);
            }
        }
        System.out.println("\n=== SUMMARY (worst click per config) ===");
        double slowestNon = 0; String slowestNonCfg = "";
        for (Row r : rows) {
            boolean finding = r.fast > 6 || r.ms > 500 || !r.quiesced;
            System.out.printf(Locale.US, "  %-42s passes(max)=%2d quiesced=%b worst=%.0f ms%s%n",
                r.cfg, r.fast, r.quiesced, r.ms, finding ? "   <== FINDING" : "");
            if (!finding && r.ms > slowestNon) { slowestNon = r.ms; slowestNonCfg = r.cfg; }
        }
        System.out.printf(Locale.US, "  slowest non-finding: %.0f ms (%s)%n", slowestNon, slowestNonCfg);
    }
}
