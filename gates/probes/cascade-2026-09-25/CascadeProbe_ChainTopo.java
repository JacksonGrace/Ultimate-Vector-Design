package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE PROBE — FAMILY C: chain topology around bridges (2026-09-25).
 *  Starts from the user's "UVD long bridge update time" file (P0 →b1→
 *  P1 →b2→ P2, both bridges optimize position+orientation, targets
 *  REAL_ONLY with pitch/roll locked) and mutates the chain topology:
 *  strict followers after / before a bridge (smoothed or not), three
 *  chained bridges (REAL_ONLY / LOCKSTEP, locked / free pitch, optLen),
 *  a multi-section run head before a bridge (the reconcile rewriting
 *  the upstream vDesign), a second coaster in the document and an
 *  orphan bridge. Per configuration it runs the shell's rebuild loop
 *  VERBATIM (12 fast + 40 exact reconcile/re-optimize passes) around
 *  three simulated bridge-window spinner clicks and prints pass counts,
 *  ms per click and — per pass — the signature slots that differ
 *  between the stamped optSig and the live capture, which bridges
 *  re-marched and which vDesign the reconcile rewrote, so a cascade's
 *  mechanism (and any k → k+1 → k feedback) is visible. */
public class CascadeProbe_ChainTopo {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "lastOptDist|0" };

    static int nCompile;
    static long tCompile, tOpt, tKnee;
    static int nKnee;

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(FILE))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    // ------------------------------------------------------------------
    // bridge bookkeeping
    // ------------------------------------------------------------------
    record Br(int piece, BridgeSection bs, boolean live) {}

    static List<Br> bridges(Coaster c) {
        List<Br> out = new ArrayList<>();
        for (int p = 0; p < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            if (secs.isEmpty()) continue;
            if (secs.get(secs.size() - 1) instanceof BridgeSection bs) {
                out.add(new Br(p, bs, p + 1 < c.pieces.size()));
            }
        }
        return out;
    }

    /** Signature slots where the live capture differs from the stamp. */
    static String sigDiff(BridgeSection bs) {
        if (bs.optSig == null) return "sig=null(first sight)";
        if (bs.optSig == BridgeSection.SIG_STALE) return "sig=STALE";
        double[] live = bs.optSignature();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < live.length; i++) {
            double d = live[i] - bs.optSig[i];
            if (Math.abs(d) > 1e-9) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(String.format(Locale.US, "%d:%s Δ%.3g (%.6g→%.6g)",
                    i, SLOT[i], d, bs.optSig[i], live[i]));
            }
        }
        return sb.length() == 0 ? "=" : sb.toString();
    }

    static double[] vDesigns(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) v.add(s.vDesign());
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static String vChanges(Coaster c, double[] before) {
        double[] after = vDesigns(c);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            for (int k = 0; k < c.pieces.get(p).sections.size(); k++, i++) {
                if (Double.doubleToLongBits(before[i]) != Double.doubleToLongBits(after[i])) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.format(Locale.US, "P%d.S%d %.9g→%.9g", p, k, before[i], after[i]));
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ------------------------------------------------------------------
    // the shell's rebuild loop, instrumented
    // ------------------------------------------------------------------
    static class Run {
        int fast, exact;
        boolean quiesced;
        double ms;
        int reMarches;
        int vWrites;
        List<String> log = new ArrayList<>();
        int passes() { return fast + exact; }
    }

    static Run rebuild(Coaster c) {
        Run out = new Run();
        long t0 = System.nanoTime();
        TrackCompiler.Result r = compile(c);
        for (int pass = 0; pass < 12; pass++) {
            out.fast++;
            String pre = passPre(c);
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r);
            String vch = vChanges(c, vb);
            if (!"none".equals(vch)) out.vWrites++;
            List<Object> stamps = stamps(c);
            boolean re = c.autoReoptimizeBridges(r);
            String rm = reMarched(c, stamps);
            out.reMarches += rm.split(",").length - (rm.isEmpty() ? 1 : 0);
            out.log.add(String.format(Locale.US, "    fast %2d | %s | reconcile: %s | re-marched: [%s]",
                pass, pre, vch, rm));
            if (!again && !re) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            out.exact++;
            String pre = passPre(c);
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r, true);
            String vch = vChanges(c, vb);
            if (!"none".equals(vch)) out.vWrites++;
            List<Object> stamps = stamps(c);
            boolean re = c.autoReoptimizeBridges(r);
            String rm = reMarched(c, stamps);
            out.reMarches += rm.split(",").length - (rm.isEmpty() ? 1 : 0);
            out.log.add(String.format(Locale.US, "    exact %2d | %s | reconcile: %s | re-marched: [%s]",
                pass, pre, vch, rm));
            if (!again && !re) { out.quiesced = true; break; }
            r = compile(c);
        }
        out.ms = (System.nanoTime() - t0) / 1e6;
        return out;
    }

    static String passPre(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (Br b : bridges(c)) {
            if (sb.length() > 0) sb.append(" ; ");
            sb.append("b@P").append(b.piece).append(b.live ? "" : "(orphan)")
              .append(": ").append(b.live ? sigDiff(b.bs) : "skipped");
        }
        return sb.toString();
    }

    static List<Object> stamps(Coaster c) {
        List<Object> s = new ArrayList<>();
        for (Br b : bridges(c)) s.add(b.bs.optSig);
        return s;
    }

    static String reMarched(Coaster c, List<Object> before) {
        List<Br> bs = bridges(c);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bs.size(); i++) {
            Object was = before.get(i);
            if (was == null || was == BridgeSection.SIG_STALE) {
                if (bs.get(i).bs.optSig != was) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append("b@P").append(bs.get(i).piece).append("(first-sight)");
                }
                continue;
            }
            if (bs.get(i).bs.optSig != was) {
                if (sb.length() > 0) sb.append(",");
                sb.append("b@P").append(bs.get(i).piece);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // one bridge-window spinner click (BridgeSlowProbe.click's path)
    // ------------------------------------------------------------------
    static Run click(Coaster c, int bp, int axis, double delta) {
        BridgeSection bs = (BridgeSection) c.pieces.get(bp).sections
            .get(c.pieces.get(bp).sections.size() - 1);
        TrackPiece t = c.pieces.get(bp + 1);
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t1 = System.nanoTime();
        if (bs.optLen) {
            long tk = System.nanoTime();
            double d = c.optimalBridgeLength(bs, py);
            tKnee += System.nanoTime() - tk; nKnee++;
            c.optimizeBridge(bs, bp + 1, d, py);
        } else {
            c.optimizeBridge(bs, bp + 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, py);
        }
        tOpt += System.nanoTime() - t1;
        Run r = rebuild(c);
        r.ms = (System.nanoTime() - t0) / 1e6;
        return r;
    }

    static void report(String cfg, String what, Run r) {
        boolean loud = r.passes() > 6 || r.ms > 500;
        System.out.printf(Locale.US,
            "  [%s] %s: %.0f ms | fast=%d exact=%d quiesced=%b | re-marches=%d vDesign-write-passes=%d | compiles=%d (%.0f ms) knee=%d× %.0f ms%s%n",
            cfg, what, r.ms, r.fast, r.exact, r.quiesced, r.reMarches, r.vWrites,
            nCompile, tCompile / 1e6, nKnee, tKnee / 1e6, loud ? "   <<< LOUD" : "");
        int show = loud ? r.log.size() : Math.min(r.log.size(), 3);
        for (int i = 0; i < show; i++) System.out.println(r.log.get(i));
        if (show < r.log.size()) System.out.println("    … (" + (r.log.size() - show) + " more passes)");
        nCompile = 0; tCompile = 0; tOpt = 0; tKnee = 0; nKnee = 0;
    }

    static void clicks(String cfg, Coaster c, int bp, int axis, int n) {
        for (int i = 0; i < n; i++) {
            report(cfg, "click " + (i + 1) + " on b@P" + bp + " target P" + (bp + 1)
                + " axis " + (axis == 1 ? "pitch" : axis == 2 ? "roll" : "yaw") + " +1°",
                click(c, bp, axis, 1.0));
        }
    }

    static void describe(String cfg, Coaster c) {
        System.out.println("== " + cfg + " ==");
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            StringBuilder secs = new StringBuilder();
            for (Section s : tp.sections) {
                if (secs.length() > 0) secs.append(",");
                secs.append(s instanceof BridgeSection b
                    ? String.format(Locale.US, "bridge(mode=%d optLen=%b d=%.2f locks=%b/%b/%b)",
                        b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll)
                    : s instanceof ForceSection ? String.format(Locale.US, "force(v=%.3f%s)", s.vDesign(), s.vPinned() ? " pin" : "")
                    : s.getClass().getSimpleName());
            }
            System.out.printf(Locale.US, "  P%d mode=%s affect=%b follow=%b smooth=%.1f locks=%b/%b/%b design=(%.2f,%.2f) true=(%.2f,%.2f) [%s]%n",
                p, tp.orientMode, tp.affectSubsequentSeams, tp.followPrev, tp.sSmoothLen,
                tp.sLockYaw, tp.sLockPitch, tp.sLockRoll,
                tp.designPitchDeg, tp.designRollDeg, tp.pitchDeg, tp.rollDeg, secs);
        }
    }

    // ------------------------------------------------------------------
    // fixture builders
    // ------------------------------------------------------------------
    /** Append a continuation hold (1 s of the end state) to piece p. */
    static LaneSection appendHold(Coaster c, int p, double len) {
        TrackPiece tp = c.pieces.get(p);
        int last = -1;
        for (int k = tp.sections.size() - 1; k >= 0; k--) {
            if (tp.sections.get(k) instanceof LaneSection) { last = k; break; }
        }
        LaneSection prev = (LaneSection) tp.sections.get(last);
        TrackCompiler.Result r = compile(c);
        int spanIdx = last;
        for (int q = 0; q < p; q++) spanIdx += c.pieces.get(q).sections.size();
        double vEnd = r.samples().get(r.spans().get(spanIdx).i1()).v();
        LaneSection hold = Coaster.continuation(prev, vEnd);
        // stretch the hold to len seconds
        for (List<BezierBaker.Node> lane : hold.lanes) {
            lane.get(lane.size() - 1).t = len;
        }
        tp.sections.add(last + 1, hold);
        return hold;
    }

    /** A new detached target piece: a deep copy of piece src placed
     *  (dx, dy, dz) past its origin at the same heading. */
    static TrackPiece newTarget(Coaster c, int src, double dx, double dy, double dz) {
        TrackPiece s = c.pieces.get(src);
        TrackPiece t = s.copy(0);
        t.followPrev = false;
        // drop a trailing bridge on the copy
        if (!t.sections.isEmpty()
                && t.sections.get(t.sections.size() - 1) instanceof BridgeSection) {
            t.sections.remove(t.sections.size() - 1);
        }
        t.x = s.x + dx;
        t.y = s.y + dy;
        t.z = s.z + dz;
        c.pieces.add(t);
        return t;
    }

    /** Bridge piece `from` to piece from+1 (the winShots recipe):
     *  compile, solve at the exit, append, compile once to capture the
     *  seams, then optimizeBridge at len. */
    static BridgeSection addBridge(Coaster c, int from, int optMode, boolean optLen,
                                   double len, boolean lockPitch, boolean lockRoll) {
        TrackPiece fp = c.pieces.get(from);
        TrackPiece t = c.pieces.get(from + 1);
        TrackCompiler.Result r = compile(c);
        int spanIdx = fp.sections.size() - 1;
        for (int q = 0; q < from; q++) spanIdx += c.pieces.get(q).sections.size();
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        BridgeSection bs = BridgeSection.solve(sp.exit(), t.entryPose(),
            r.samples().get(sp.i1()).v());
        bs.optMode = optMode;
        bs.optLen = optLen;
        bs.lockPitch = lockPitch;
        bs.lockRoll = lockRoll;
        fp.sections.add(bs);
        compile(c);   // capture the seam dynamics
        boolean py = optMode == BridgeSection.OPT_POS_YAW;
        double d = optLen ? c.optimalBridgeLength(bs, py) : len;
        c.optimizeBridge(bs, from + 1, d, py);
        return bs;
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return (BridgeSection) s.get(s.size() - 1);
    }

    static void settle(String cfg, Coaster c) {
        report(cfg, "settle rebuild", rebuild(c));
        report(cfg, "idle rebuild", rebuild(c));
    }

    // ------------------------------------------------------------------
    public static void main(String[] a) throws Exception {
        // JIT warm-up on a throwaway copy
        {
            Coaster w = load();
            rebuild(w); rebuild(w);
            for (int i = 0; i < 3; i++) { click(w, 0, 1, 1.0); click(w, 1, 1, 1.0); }
            nCompile = 0; tCompile = 0; tOpt = 0; tKnee = 0; nKnee = 0;
        }

        // ---------------- C0: the file as saved (baseline)
        {
            Coaster c = load();
            describe("C0 as saved", c);
            settle("C0", c);
            clicks("C0", c, 0, 1, 3);
            clicks("C0", c, 1, 1, 3);
            clicks("C0", c, 1, 2, 2);
        }

        // ---------------- C1a: bridge → target → strict follower (sharp)
        {
            Coaster c = load();
            appendHold(c, 2, 2.0);           // P2 = [T0, hold]
            rebuild(c);
            int f = c.makeSeamStrict(2, 1, DT);   // P3 = follower [hold]
            describe("C1a strict follower after the last target (sharp), follower P" + f, c);
            settle("C1a", c);
            clicks("C1a", c, 1, 1, 3);
            clicks("C1a", c, 0, 1, 3);
        }
        // ---------------- C1b: same, smoothed 10 m
        {
            Coaster c = load();
            appendHold(c, 2, 2.0);
            rebuild(c);
            int f = c.makeSeamStrict(2, 1, DT);
            c.pieces.get(f).sSmoothLen = 10;
            describe("C1b strict follower after the last target, smoothed 10 m", c);
            settle("C1b", c);
            clicks("C1b", c, 1, 1, 3);
            clicks("C1b", c, 0, 1, 3);
        }
        // ---------------- C1c/C3: the piece BEFORE b2 is a strict follower (sharp)
        // P1 = [T0, hold, b2] → makeSeamStrict(1,1) → P1=[T0], P2=[hold, b2] follower, P3 = old P2
        {
            Coaster c = load();
            appendHold(c, 1, 2.0);
            rebuild(c);
            int f = c.makeSeamStrict(1, 1, DT);
            describe("C1c/C3 follower P" + f + " ends in b2 (sharp); P1 is b1's target", c);
            settle("C1c", c);
            clicks("C1c", c, 0, 1, 3);     // b1 → target P1 (whose follower P2 carries b2)
            clicks("C1c", c, 2, 1, 3);     // b2 (on the follower) → target P3
        }
        // ---------------- C3s: same, smoothed 10 m
        {
            Coaster c = load();
            appendHold(c, 1, 2.0);
            rebuild(c);
            int f = c.makeSeamStrict(1, 1, DT);
            c.pieces.get(f).sSmoothLen = 10;
            describe("C3s follower P" + f + " ends in b2, seam smoothed 10 m", c);
            settle("C3s", c);
            clicks("C3s", c, 0, 1, 3);
            clicks("C3s", c, 2, 1, 3);
            clicks("C3s", c, 2, 2, 2);
        }

        // ---------------- C2a: three chained bridges, REAL_ONLY, default locks
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, false, 25, true, true);
            describe("C2a three chained bridges, REAL_ONLY targets, pitch/roll locked", c);
            settle("C2a", c);
            clicks("C2a", c, 0, 1, 3);
            clicks("C2a", c, 1, 1, 3);
            clicks("C2a", c, 2, 1, 3);
        }
        // ---------------- C2b: LOCKSTEP targets, default locks
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, false, 25, true, true);
            for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
            describe("C2b three chained bridges, LOCKSTEP targets, pitch/roll locked", c);
            settle("C2b", c);
            clicks("C2b", c, 0, 1, 3);
            clicks("C2b", c, 1, 1, 3);
            clicks("C2b", c, 2, 1, 3);
        }
        // ---------------- C2c: LOCKSTEP targets, PITCH FREE on all three (the inner settle path), click roll
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, false, 25, false, true);
            for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
            for (int p = 0; p < 2; p++) {
                BridgeSection b = bridgeOf(c, p);
                b.lockPitch = false;
                c.optimizeBridge(b, p + 1, b.lastOptDist, true);
            }
            describe("C2c three chained bridges, LOCKSTEP, pitch FREE (settle path)", c);
            settle("C2c", c);
            clicks("C2c", c, 0, 2, 3);
            clicks("C2c", c, 1, 2, 3);
            clicks("C2c", c, 2, 2, 3);
        }
        // ---------------- C2d: REAL_ONLY, pitch FREE on all three
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, false, 25, false, true);
            for (int p = 0; p < 2; p++) {
                BridgeSection b = bridgeOf(c, p);
                b.lockPitch = false;
                c.optimizeBridge(b, p + 1, b.lastOptDist, true);
            }
            describe("C2d three chained bridges, REAL_ONLY, pitch FREE", c);
            settle("C2d", c);
            clicks("C2d", c, 0, 2, 3);
            clicks("C2d", c, 1, 2, 3);
            clicks("C2d", c, 2, 2, 3);
        }
        // ---------------- C2e: REAL_ONLY, default locks, OPTIMIZE LENGTH on all three
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, true, 25, true, true);
            for (int p = 0; p < 2; p++) {
                BridgeSection b = bridgeOf(c, p);
                b.optLen = true;
                long tk = System.nanoTime();
                double d = c.optimalBridgeLength(b, true);
                tKnee += System.nanoTime() - tk; nKnee++;
                c.optimizeBridge(b, p + 1, d, true);
            }
            describe("C2e three chained bridges, REAL_ONLY, Optimize length ON", c);
            settle("C2e", c);
            clicks("C2e", c, 0, 1, 3);
            clicks("C2e", c, 1, 1, 3);
            clicks("C2e", c, 2, 1, 3);
        }
        // ---------------- C2f: LOCKSTEP, pitch FREE, Optimize length ON
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, true, 25, false, true);
            for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
            for (int p = 0; p < 2; p++) {
                BridgeSection b = bridgeOf(c, p);
                b.optLen = true; b.lockPitch = false;
                double d = c.optimalBridgeLength(b, true);
                c.optimizeBridge(b, p + 1, d, true);
            }
            describe("C2f three chained bridges, LOCKSTEP, pitch FREE, Optimize length ON", c);
            settle("C2f", c);
            clicks("C2f", c, 0, 2, 3);
            clicks("C2f", c, 1, 2, 3);
            clicks("C2f", c, 2, 2, 3);
        }

        // ---------------- C4a: run head P0 = [S0, hold 1 s, b1] (non-first upstream section)
        {
            Coaster c = load();
            appendHold(c, 0, 1.0);
            describe("C4a run head with a non-first section before b1", c);
            settle("C4a", c);
            clicks("C4a", c, 0, 1, 3);
            clicks("C4a", c, 1, 1, 3);
        }
        // ---------------- C4b: same, the hold's vDesign perturbed −5 m/s (the reconcile must walk it back)
        {
            Coaster c = load();
            LaneSection h = appendHold(c, 0, 1.0);
            rebuild(c);
            h.vDesign(h.vDesign() - 5);
            describe("C4b run head hold vDesign −5 m/s off (reconcile walks it)", c);
            settle("C4b", c);
            clicks("C4b", c, 0, 1, 3);
        }
        // ---------------- C4c: same, PINNED at −5 m/s (a deliberate ≠v before b1)
        {
            Coaster c = load();
            LaneSection h = appendHold(c, 0, 1.0);
            rebuild(c);
            h.vDesign(h.vDesign() - 5);
            h.vPinned = true;
            describe("C4c run head hold PINNED −5 m/s (deliberate ≠v before b1)", c);
            settle("C4c", c);
            clicks("C4c", c, 0, 1, 3);
            clicks("C4c", c, 1, 1, 3);
        }
        // ---------------- C4d: the middle TARGET has a non-first section before b2 (P1 = [T0, hold, b2])
        {
            Coaster c = load();
            appendHold(c, 1, 1.0);
            describe("C4d target P1 = [T0, hold, b2] (reconciled section before b2)", c);
            settle("C4d", c);
            clicks("C4d", c, 0, 1, 3);
            clicks("C4d", c, 1, 1, 3);
        }
        // ---------------- C4e: P1's hold TRUE-anchored (vDesign := true seam speed, the Set-Design-to-Real state)
        {
            Coaster c = load();
            LaneSection h = appendHold(c, 1, 1.0);
            rebuild(c);
            TrackCompiler.Result r = compile(c);
            int spanIdx = c.pieces.get(0).sections.size() + 0;   // T0's span
            int ip = r.spans().get(spanIdx).i1();
            double vTrue = r.trueV()[ip], vDes = r.samples().get(ip).v();
            System.out.printf(Locale.US, "  C4e: T0 end design %.6f true %.6f (gap %.4f)%n", vDes, vTrue, vTrue - vDes);
            h.vDesign(vTrue);
            describe("C4e target P1 hold anchored at the TRUE seam speed", c);
            settle("C4e", c);
            clicks("C4e", c, 0, 1, 3);
            clicks("C4e", c, 1, 1, 3);
        }

        // ---------------- C5: two coasters in the document
        {
            Coaster c = load();
            Coaster other = load();
            other.name = "Other";
            List<Coaster> doc = new ArrayList<>(List.of(c, other));
            describe("C5 active coaster with a second coaster (" + doc.size() + ") present", c);
            settle("C5", c);
            clicks("C5", c, 0, 1, 3);
            clicks("C5", c, 1, 1, 3);
            report("C5", "the OTHER coaster's idle rebuild", rebuild(other));
        }

        // ---------------- C6: orphan bridge (P2 deleted → b2 orphan) plus the live b1
        {
            Coaster c = load();
            rebuild(c);
            c.pieces.remove(2);
            describe("C6 b2 orphaned (P2 removed) + live b1", c);
            settle("C6", c);
            clicks("C6", c, 0, 1, 3);
            clicks("C6", c, 0, 2, 2);
        }
        // ---------------- C6b: orphan bridge FIRST, then a live one (P0 →b1 orphan? impossible — an orphan is always last).
        // Instead: three bridges with the LAST target removed: b3 orphan, b1/b2 live
        {
            Coaster c = load();
            newTarget(c, 2, 40, -6, 25);
            addBridge(c, 2, BridgeSection.OPT_POS_YAW, false, 25, true, true);
            rebuild(c);
            c.pieces.remove(3);
            describe("C6b b3 orphaned (P3 removed) + live b1, b2", c);
            settle("C6b", c);
            clicks("C6b", c, 0, 1, 3);
            clicks("C6b", c, 1, 1, 3);
        }
        System.out.println("done");
    }
}
