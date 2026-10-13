package uvd;

import java.util.*;

/** CASCADE PROBE — FAMILY E, part 2 (2026-09-25): the TWO-BRIDGE
 *  chain (the user's "both bridges" shape) with LOCKSTEP pitch/roll-
 *  FREE targets under OPT_POS_YAW at slow design speeds:
 *  hA (hill 26) → bridge0 → hB (turn vT, pitch p, LOCKSTEP) → bridge1
 *  → hC (turn vT, pitch p, LOCKSTEP). Bridge0's settle re-solves hB,
 *  which moves bridge1's A side — the probe counts how many rebuild
 *  passes that takes, whether the stamps equal the next captures, and
 *  the ms per click for: an upstream editPitch(+1) on hA (auto-pass
 *  only), Length+1 on bridge0, and — with bridge0's yaw HELD — the yaw
 *  spinner on hB (the closest analog of the user's report). Reuses
 *  CascadeProbe_E's helpers. */
public class CascadeProbe_E2 {
    static final double DT = Coaster.INTEGRATE_DT;

    record Cfg(double vT, double pitch, double gapX, boolean optLen, boolean lockYaw0) {
        String label() {
            return String.format(Locale.US, "F v%.0f %+.0f° gap%.0f optLen=%b b0.lockYaw=%b",
                vT, pitch, gapX, optLen, lockYaw0);
        }
    }

    static BridgeSection b(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return (BridgeSection) s.get(s.size() - 1);
    }

    static Coaster build(Cfg k) {
        Coaster c = new Coaster();
        TrackPiece hA = new TrackPiece(0, 21, 0, 0);
        hA.sections.add(new ForceSection(CascadeProbe_E.hillLanes(), 26));
        c.pieces.add(hA);
        TrackPiece hB = new TrackPiece(k.gapX, 14, 30, 20);
        hB.sections.add(new ForceSection(CascadeProbe_E.turnLanes(), k.vT));
        hB.orientMode = TrackPiece.OrientMode.LOCKSTEP;
        hB.editPitch(k.pitch);
        c.pieces.add(hB);
        TrackPiece hC = new TrackPiece(2 * k.gapX, 8, 60, 40);
        hC.sections.add(new ForceSection(CascadeProbe_E.turnLanes(), k.vT));
        hC.orientMode = TrackPiece.OrientMode.LOCKSTEP;
        hC.editPitch(k.pitch);
        c.pieces.add(hC);
        TrackCompiler.Result r0 = CascadeProbe_E.compile(c);
        BridgeSection b0 = BridgeSection.solve(r0.spans().get(0).exit(),
            hB.entryPose(), r0.samples().get(r0.spans().get(0).i1()).v());
        b0.optMode = BridgeSection.OPT_POS_YAW;
        b0.lockYaw = k.lockYaw0;
        b0.lockPitch = false;
        b0.lockRoll = false;
        b0.optLen = k.optLen;
        hA.sections.add(b0);
        TrackCompiler.Result r1 = CascadeProbe_E.compile(c);
        BridgeSection b1 = BridgeSection.solve(r1.spans().get(2).exit(),
            hC.entryPose(), r1.samples().get(r1.spans().get(2).i1()).v());
        b1.optMode = BridgeSection.OPT_POS_YAW;
        b1.lockYaw = false;
        b1.lockPitch = false;
        b1.lockRoll = false;
        b1.optLen = k.optLen;
        hB.sections.add(b1);
        CascadeProbe_E.compile(c);   // captures both; optSig null
        return c;
    }

    static class Loop {
        int compiles, fast, exact;
        boolean quiesced;
        double ms;
        List<String> perPass = new ArrayList<>();
    }

    static String shortDiff(BridgeSection bs) {
        String s = CascadeProbe_E.sigDiff(bs);
        if (s.length() > 260) s = s.substring(0, 260) + " …";
        return s;
    }

    static Loop rebuild(Coaster c) {
        Loop L = new Loop();
        long t0 = System.nanoTime();
        TrackCompiler.Result r = CascadeProbe_E.compile(c);
        L.compiles = 1;
        for (int pass = 0; pass < 12; pass++) {
            L.fast++;
            String d0 = shortDiff(b(c, 0)), d1 = shortDiff(b(c, 1));
            boolean recon = c.reconcileChainedSpeeds(r);
            boolean auto = c.autoReoptimizeBridges(r);
            L.perPass.add("fast" + pass + ": recon=" + recon + " auto=" + auto + " | b0 " + d0 + " | b1 " + d1);
            if (!(recon || auto)) break;
            r = CascadeProbe_E.compile(c);
            L.compiles++;
        }
        for (int pass = 0; pass < 40; pass++) {
            L.exact++;
            String d0 = shortDiff(b(c, 0)), d1 = shortDiff(b(c, 1));
            boolean recon = c.reconcileChainedSpeeds(r, true);
            boolean auto = c.autoReoptimizeBridges(r);
            L.perPass.add("exact" + pass + ": recon=" + recon + " auto=" + auto + " | b0 " + d0 + " | b1 " + d1);
            if (!(recon || auto)) { L.quiesced = true; break; }
            r = CascadeProbe_E.compile(c);
            L.compiles++;
        }
        L.ms = (System.nanoTime() - t0) / 1e6;
        return L;
    }

    static double[] both(Coaster c) {
        double[] a = CascadeProbe_E.placement(c.pieces.get(1));
        double[] d = CascadeProbe_E.placement(c.pieces.get(2));
        double[] o = new double[a.length + d.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(d, 0, o, a.length, d.length);
        return o;
    }

    static String opt(Coaster c, int p, double dist) {
        BridgeSection bs = b(c, p);
        long t0 = System.nanoTime();
        double d = bs.optLen ? c.optimalBridgeLength(bs, true) : dist;
        c.optimizeBridge(bs, p + 1, d, true);
        return String.format(Locale.US, "b%d %.0f ms applied %.3f", p, (System.nanoTime() - t0) / 1e6, bs.lastOptDist);
    }

    static String click(Coaster c, String name, Runnable write, int optPiece, double distOverride, List<String> sink) {
        CascadeProbe_E.nCompile = 0; CascadeProbe_E.tCompile = 0;
        long t0 = System.nanoTime();
        write.run();
        double optMs = 0;
        if (optPiece >= 0) {
            BridgeSection bs = b(c, optPiece);
            long t1 = System.nanoTime();
            double d = bs.optLen ? c.optimalBridgeLength(bs, true)
                : distOverride > 0 ? distOverride : bs.lastOptDist;
            c.optimizeBridge(bs, optPiece + 1, d, true);
            optMs = (System.nanoTime() - t1) / 1e6;
        }
        Loop L = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        String s = String.format(Locale.US,
            "%s: %.0f ms (opt %.0f + loop %.0f; %d compiles %.0f ms) f%d/e%d q=%b b0.len %.3f b1.len %.3f",
            name, ms, optMs, L.ms, CascadeProbe_E.nCompile, CascadeProbe_E.tCompile / 1e6,
            L.fast, L.exact, L.quiesced, b(c, 0).lastOptDist, b(c, 1).lastOptDist);
        if (L.compiles > 3 || ms > 500 || !L.quiesced) {
            sink.add("    " + name + " per-pass:");
            for (String pp : L.perPass) sink.add("      " + pp);
        }
        return s;
    }

    public static void main(String[] a) {
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        run(new Cfg(8, 30, 40, false, false));
        System.setOut(out);
        for (double vT : new double[]{ 4, 8 })
            for (double pitch : new double[]{ 30, -30 })
                for (boolean optLen : new boolean[]{ false, true })
                    for (boolean ly : new boolean[]{ false, true })
                        run(new Cfg(vT, pitch, 5 * vT, optLen, ly));
    }

    static void run(Cfg k) {
        Coaster c = build(k);
        TrackPiece hA = c.pieces.get(0), hB = c.pieces.get(1);
        List<String> sink = new ArrayList<>();
        double chord0 = new Vec3(hB.x, hB.y, hB.z).sub(b(c, 0).solvedEntry.pos()).length();
        double chord1 = new Vec3(c.pieces.get(2).x, c.pieces.get(2).y, c.pieces.get(2).z)
            .sub(b(c, 1).solvedEntry.pos()).length();
        String o0 = opt(c, 0, chord0 * 1.2);
        String o1 = opt(c, 1, chord1 * 1.2);
        // fresh compile: stamps vs captures
        CascadeProbe_E.compile(c);
        String s0 = shortDiff(b(c, 0)), s1 = shortDiff(b(c, 1));
        Loop L1 = rebuild(c);
        double[] p1 = both(c);
        Loop L2 = rebuild(c);
        boolean noop = L2.compiles == 1 && Arrays.equals(CascadeProbe_E.bits(p1), CascadeProbe_E.bits(both(c)));
        List<String> clicks = new ArrayList<>();
        clicks.add(click(c, "upstream hA.editPitch+1", () -> hA.editPitch(hA.pitchDeg + 1), -1, 0, sink));
        if (!k.optLen) clicks.add(click(c, "b0 Length+1", () -> {}, 0, b(c, 0).lastOptDist + 1, sink));
        if (k.lockYaw0) clicks.add(click(c, "hB yaw spinner+1 (b0 target)", () -> hB.setDispYaw(hB.dispYaw() + 1), 0, 0, sink));
        clicks.add(click(c, "idle", () -> {}, -1, 0, sink));
        boolean flag = !s0.startsWith("match") || !s1.startsWith("match") || L1.compiles > 3 || L1.ms > 500
            || !L1.quiesced || !noop || !sink.isEmpty();
        System.out.printf(Locale.US, "%s[%s]%n", flag ? "!! " : "   ", k.label());
        System.out.printf(Locale.US, "    explicit optimize: %s | %s%n", o0, o1);
        System.out.printf(Locale.US, "    stamp vs fresh capture: b0 %s | b1 %s%n", s0, s1);
        System.out.printf(Locale.US, "    rebuild1: %.0f ms compiles %d f%d/e%d q=%b | idle: compiles %d q=%b noop=%b%n",
            L1.ms, L1.compiles, L1.fast, L1.exact, L1.quiesced, L2.compiles, L2.quiesced, noop);
        if (L1.compiles > 2 || !L1.quiesced) {
            System.out.println("    rebuild1 per-pass:");
            for (String pp : L1.perPass) System.out.println("      " + pp);
        }
        for (String cl : clicks) System.out.println("    click " + cl);
        for (String v : sink) System.out.println(v);
    }
}
