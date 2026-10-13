package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE PROBE — FAMILY A (2026-09-25): orientation modes × bridge
 *  locks × bridge modes × Optimize length × design/real conflict on the
 *  user's "UVD long bridge update time 9.25.26.uvd" (3 pieces, 2 bridges).
 *  For every configuration the two targets/bridges are set UNIFORMLY
 *  through the same writes the UI makes (mode flip = no re-march; lock
 *  flip / mode switch = re-march at lastOptDist under optimize-
 *  orientation; Optimize length = knee + re-march), then THREE
 *  successive +1° spinner clicks run on each bridge's first editable
 *  axis (pitch, else yaw, else roll; none = a Length click, or an idle
 *  rebuild under optLen). Each click mirrors GraphEditor.bridgeTargetWrite
 *  → placementEdit (no carry) → applyOptimalLength / applyBridgeGap →
 *  Coaster.optimizeBridge → FvdDesigner.rebuildTrack's 12 fast + 40
 *  exact reconcile/re-optimize loop VERBATIM. Per pass it records which
 *  section speeds the reconcile wrote and which optSignature slots
 *  differed from the stamped signature (why a bridge re-ran), and prints
 *  them for any click with > 6 total passes or > 500 ms. */
public class CascadeProbe_A {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = { "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z",
        "ue.x", "ue.y", "ue.z", "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y",
        "jerkA.z", "rollA", "rollAccA", "vDesignA", "accB.u", "accB.r", "jerkB.u",
        "jerkB.r", "rollB", "rollAccB", "vB", "tB.y", "uB.y", "len" };

    static TrackCompiler.Result lastResult;
    static int nCompile;

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return s.get(s.size() - 1) instanceof BridgeSection b ? b : null;
    }

    // ------------------------------------------------------------------
    // the shell's rebuild loop, instrumented
    // ------------------------------------------------------------------

    record Pass(String phase, int idx, List<String> reconWrites,
                List<String> reoptWhy, double ms) {}

    static class Rebuild {
        int fast, exact, compiles;
        boolean quiesced;
        double ms, compileMs;
        List<Pass> passes = new ArrayList<>();
        int total() { return fast + exact; }
        String brief() {
            return String.format(Locale.US, "f%d/e%d%s %.0fms(c%.0f)", fast, exact,
                quiesced ? "" : "/NOTQ", ms, compileMs);
        }
    }

    static double tCompileMs;   // accumulated compile time (all rebuilds)

    static TrackCompiler.Result compile(Coaster c) {
        nCompile++;
        long t0 = System.nanoTime();
        try { return TrackCompiler.compile(c, DT); }
        finally { tCompileMs += (System.nanoTime() - t0) / 1e6; }
    }

    static double[] speeds(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) v.add(s.vDesign());
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static List<String> speedDiffs(Coaster c, double[] before) {
        List<String> out = new ArrayList<>();
        int i = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            for (int k = 0; k < c.pieces.get(p).sections.size(); k++, i++) {
                double v = c.pieces.get(p).sections.get(k).vDesign();
                if (Double.doubleToLongBits(v) != Double.doubleToLongBits(before[i])) {
                    out.add(String.format(Locale.US, "p%ds%d v %.9g→%.9g", p, k, before[i], v));
                }
            }
        }
        return out;
    }

    /** Why each live optimize-mode bridge WILL re-run this pass (mirrors
     *  autoReoptimizeBridges' test, read before it runs). */
    static List<String> sigDiffs(Coaster c) {
        List<String> out = new ArrayList<>();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
            double[] sig = bs.optSignature();
            if (bs.optSig == null) { out.add("b" + p + ": first sight (record only)"); continue; }
            if (bs.optSig == BridgeSection.SIG_STALE) { out.add("b" + p + ": SIG_STALE"); continue; }
            if (sig.length != bs.optSig.length) { out.add("b" + p + ": length mismatch"); continue; }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sig.length; i++) {
                double d = sig[i] - bs.optSig[i];
                if (Math.abs(d) > 1e-9) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(String.format(Locale.US, "%s[%d] %.9g→%.9g (Δ%.3g)",
                        SLOT[i], i, bs.optSig[i], sig[i], d));
                }
            }
            if (sb.length() > 0) out.add("b" + p + " re-opt: " + sb);
        }
        return out;
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). */
    static Rebuild rebuild(Coaster c) {
        Rebuild rr = new Rebuild();
        long t0 = System.nanoTime();
        double c0 = tCompileMs;
        TrackCompiler.Result r = compile(c);
        rr.compiles++;
        for (int pass = 0; pass < 12; pass++) {
            rr.fast++;
            long tp = System.nanoTime();
            double[] vb = speeds(c);
            boolean again = c.reconcileChainedSpeeds(r);
            List<String> rw = speedDiffs(c, vb);
            List<String> why = sigDiffs(c);
            if (c.autoReoptimizeBridges(r)) again = true;
            rr.passes.add(new Pass("fast", pass, rw, why, (System.nanoTime() - tp) / 1e6));
            if (!again) break;
            r = compile(c);
            rr.compiles++;
        }
        for (int pass = 0; pass < 40; pass++) {
            rr.exact++;
            long tp = System.nanoTime();
            double[] vb = speeds(c);
            boolean again = c.reconcileChainedSpeeds(r, true);
            List<String> rw = speedDiffs(c, vb);
            List<String> why = sigDiffs(c);
            if (c.autoReoptimizeBridges(r)) again = true;
            rr.passes.add(new Pass("exact", pass, rw, why, (System.nanoTime() - tp) / 1e6));
            if (!again) { rr.quiesced = true; break; }
            r = compile(c);
            rr.compiles++;
        }
        rr.ms = (System.nanoTime() - t0) / 1e6;
        rr.compileMs = tCompileMs - c0;
        lastResult = r;
        return rr;
    }

    static void printPasses(String label, Rebuild rr) {
        System.out.println("      >> " + label + ": " + rr.brief() + " compiles=" + rr.compiles);
        for (Pass p : rr.passes) {
            if (p.reconWrites().isEmpty() && p.reoptWhy().isEmpty()) {
                System.out.printf(Locale.US, "         %s#%d (%.0f ms): settled%n", p.phase(), p.idx(), p.ms());
                continue;
            }
            System.out.printf(Locale.US, "         %s#%d (%.0f ms):%n", p.phase(), p.idx(), p.ms());
            for (String s : p.reconWrites()) System.out.println("            reconcile " + s);
            for (String s : p.reoptWhy()) System.out.println("            " + s);
        }
    }

    // ------------------------------------------------------------------
    // the UI's write paths
    // ------------------------------------------------------------------

    /** GraphEditor.applyBridgeGap: optimizeBridge at dist + rebuild. */
    static Rebuild applyGap(Coaster c, int bp, double dist) {
        BridgeSection bs = bridgeOf(c, bp);
        c.optimizeBridge(bs, bp + 1, dist, bs.optMode == BridgeSection.OPT_POS_YAW);
        return rebuild(c);
    }

    /** GraphEditor.applyOptimalLength: knee then applyGap; a knee at the
     *  1 m floor = the zero-distance prompt (answered NO: optLen off). */
    static Rebuild applyOptLen(Coaster c, int bp, StringBuilder note) {
        BridgeSection bs = bridgeOf(c, bp);
        double d = c.optimalBridgeLength(bs, bs.optMode == BridgeSection.OPT_POS_YAW);
        if (d <= 1 + 1e-9) {
            note.append(" [b").append(bp).append(" knee≤1m → zero-distance prompt, NO: optLen off]");
            bs.optLen = false;
            return rebuild(c);
        }
        return applyGap(c, bp, d);
    }

    /** The bridge window's Confirm-less spinner click on the target's
     *  axis (0 yaw / 1 pitch / 2 roll), GraphEditor.setBridgeTargetOrient
     *  → bridgeTargetWrite (placementEdit without carry: affect is off). */
    record Click(String axis, double optMs, Rebuild rr, double totalMs) {
        String brief() {
            return String.format(Locale.US, "%s opt%.0f+loop %s", axis, optMs, rr.brief());
        }
    }

    static Click click(Coaster c, int bp, int axis, double delta, StringBuilder note) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        boolean design = t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        long t0 = System.nanoTime();
        String ax;
        if (axis == 1) {
            double v = (design ? t.dispDesignPitch() : t.dispPitch()) + delta;
            t.setDispPitch(v, true);
            ax = "pitch";
        } else if (axis == 2) {
            double v = (design ? t.dispDesignRoll() : t.dispRoll()) + delta;
            t.setDispRoll(v, true);
            ax = "roll";
        } else {
            t.setDispYaw(t.dispYaw() + delta);
            ax = "yaw";
        }
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t1 = System.nanoTime();
        Rebuild rr;
        if (bs.optLen) {
            double d = c.optimalBridgeLength(bs, py);
            if (d <= 1 + 1e-9) {
                note.append(" [b").append(bp).append(" click knee≤1m → prompt NO, optLen off]");
                bs.optLen = false;
                double optMs = (System.nanoTime() - t1) / 1e6;
                rr = rebuild(c);
                return new Click(ax, optMs, rr, (System.nanoTime() - t0) / 1e6);
            }
            c.optimizeBridge(bs, bp + 1, d, py);
        } else {
            c.optimizeBridge(bs, bp + 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, py);
        }
        double optMs = (System.nanoTime() - t1) / 1e6;
        rr = rebuild(c);
        return new Click(ax, optMs, rr, (System.nanoTime() - t0) / 1e6);
    }

    /** A Length spinner click (+1 m) for configurations with no editable
     *  orientation axis. */
    static Click lengthClick(Coaster c, int bp) {
        BridgeSection bs = bridgeOf(c, bp);
        long t0 = System.nanoTime();
        double d = (bs.lastOptDist > 0 ? bs.lastOptDist : 1) + 1;
        c.optimizeBridge(bs, bp + 1, d, bs.optMode == BridgeSection.OPT_POS_YAW);
        double optMs = (System.nanoTime() - t0) / 1e6;
        Rebuild rr = rebuild(c);
        return new Click("length", optMs, rr, (System.nanoTime() - t0) / 1e6);
    }

    // ------------------------------------------------------------------
    // configurations
    // ------------------------------------------------------------------

    enum Locks {
        YAW_FREE(false, true, true), ALL_LOCKED(true, true, true),
        PITCH_FREE(true, false, true), ROLL_FREE(true, true, false),
        ALL_FREE(false, false, false);
        final boolean ly, lp, lr;
        Locks(boolean ly, boolean lp, boolean lr) { this.ly = ly; this.lp = lp; this.lr = lr; }
    }

    record Cfg(TrackPiece.OrientMode mode, int optMode, Locks locks, boolean optLen,
               boolean conflict) {
        String name() {
            return String.format("%-11s %-7s %-11s optLen=%-5b conflict=%b",
                mode, optMode == BridgeSection.OPT_POS_YAW ? "PY" : "POS",
                locks, optLen, conflict);
        }
    }

    record Applied(Coaster c, StringBuilder note, List<Rebuild> setupRebuilds) {}

    /** Load the file and walk it into the configuration through the
     *  UI's own write order: open → conflict choice → mode flip (no
     *  re-march) → bridge mode (re-march at lastOptDist) → locks (each
     *  flip re-marches under PY) → Optimize length (knee + re-march). */
    static Applied apply(Cfg cfg) throws IOException {
        Coaster c = load();
        StringBuilder note = new StringBuilder();
        List<Rebuild> setup = new ArrayList<>();
        setup.add(rebuild(c));   // open (first sight: record only)
        if (!cfg.conflict) {
            // Design → Real Orientation on both targets: design := true
            for (int p = 1; p < c.pieces.size(); p++) {
                TrackPiece t = c.pieces.get(p);
                t.designPitchDeg = t.pitchDeg;
                t.designRollDeg = t.rollDeg;
            }
            setup.add(rebuild(c));
        }
        for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = cfg.mode;
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            if (bs.optMode != cfg.optMode) {
                bs.optMode = cfg.optMode;   // between optimize modes: keep length
                setup.add(applyGap(c, bp, bs.lastOptDist > 0 ? bs.lastOptDist : 1));
            }
        }
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            boolean[] want = { cfg.locks.ly, cfg.locks.lp, cfg.locks.lr };
            for (int ax = 0; ax < 3; ax++) {
                if (bs.axisLocked(ax) == want[ax]) continue;
                if (ax == 0) bs.lockYaw = want[ax];
                else if (ax == 1) bs.lockPitch = want[ax];
                else bs.lockRoll = want[ax];
                if (bs.optMode == BridgeSection.OPT_POS_YAW) {
                    setup.add(applyGap(c, bp, bs.lastOptDist > 0 ? bs.lastOptDist : 1));
                }
            }
        }
        if (cfg.optLen) {
            for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
                BridgeSection bs = bridgeOf(c, bp);
                bs.optLen = true;
                setup.add(applyOptLen(c, bp, note));
            }
        }
        return new Applied(c, note, setup);
    }

    static int pickAxis(BridgeSection bs) {
        if (bs.axisEditable(1)) return 1;
        if (bs.axisEditable(0)) return 0;
        if (bs.axisEditable(2)) return 2;
        return -1;
    }

    static final int SLOW_PASSES = 6;
    static final double SLOW_MS = 500;

    static class Summary {
        int configs, findings;
        double slowestNonCascadeMs;
        List<String> findingLines = new ArrayList<>();
    }

    static void runCfg(Cfg cfg, Summary sum, boolean print) throws IOException {
        Applied ap = apply(cfg);
        Coaster c = ap.c();
        StringBuilder line = new StringBuilder();
        line.append(cfg.name());
        boolean slow = false;
        List<String> details = new ArrayList<>();
        // setup rebuilds that cascaded are findings too (a lock flip /
        // mode switch / optLen apply is a click of its own kind)
        for (int i = 0; i < ap.setupRebuilds().size(); i++) {
            Rebuild rr = ap.setupRebuilds().get(i);
            if (rr.total() > SLOW_PASSES || rr.ms > SLOW_MS) {
                slow = true;
                details.add("SETUP rebuild #" + i + " " + rr.brief());
                if (print) printPasses("setup #" + i, rr);
            }
        }
        for (int bp = 0; bp + 1 < c.pieces.size(); bp++) {
            BridgeSection bs = bridgeOf(c, bp);
            int axis = pickAxis(bs);
            line.append(" | b").append(bp).append(":");
            for (int k = 0; k < 3; k++) {
                Click ck;
                if (axis >= 0) ck = click(c, bp, axis, 1.0, ap.note());
                else if (!bs.optLen) ck = lengthClick(c, bp);
                else {
                    long t0 = System.nanoTime();
                    Rebuild rr = rebuild(c);
                    ck = new Click("idle", 0, rr, (System.nanoTime() - t0) / 1e6);
                }
                line.append(" ").append(ck.brief());
                Rebuild rr = ck.rr();
                boolean bad = rr.total() > SLOW_PASSES || ck.totalMs() > SLOW_MS || !rr.quiesced;
                if (bad) {
                    slow = true;
                    details.add("b" + bp + " click#" + k + " " + ck.brief()
                        + String.format(Locale.US, " total %.0f ms", ck.totalMs()));
                    if (print) printPasses("b" + bp + " click#" + k + " (" + ck.axis() + ")", rr);
                } else {
                    sum.slowestNonCascadeMs = Math.max(sum.slowestNonCascadeMs, ck.totalMs());
                }
            }
        }
        line.append(ap.note());
        System.out.println((slow ? "!! " : "   ") + line);
        sum.configs++;
        if (slow) {
            sum.findings++;
            sum.findingLines.add(cfg.name() + " :: " + String.join("; ", details) + ap.note());
        }
    }

    public static void main(String[] a) throws Exception {
        // warm the JIT on the as-saved configuration (discarded)
        System.out.println("=== warm-up ===");
        runCfg(new Cfg(TrackPiece.OrientMode.REAL_ONLY, BridgeSection.OPT_POS_YAW,
            Locks.YAW_FREE, false, true), new Summary(), false);
        runCfg(new Cfg(TrackPiece.OrientMode.LOCKSTEP, BridgeSection.OPT_POS_YAW,
            Locks.PITCH_FREE, true, true), new Summary(), false);

        Summary sum = new Summary();
        System.out.println("=== FAMILY A sweep: 3 clicks per bridge per configuration ===");
        System.out.println("    click = <axis> opt<optimizeBridge ms>+loop f<fast>/e<exact>[/NOTQ] <loop ms>");
        boolean[] conflicts = { true, false };
        boolean[] optLens = { false, true };
        for (boolean conflict : conflicts) {
            for (TrackPiece.OrientMode mode : TrackPiece.OrientMode.values()) {
                for (int optMode : new int[]{ BridgeSection.OPT_POS_YAW, BridgeSection.OPT_POS }) {
                    for (Locks locks : Locks.values()) {
                        for (boolean optLen : optLens) {
                            runCfg(new Cfg(mode, optMode, locks, optLen, conflict), sum, true);
                        }
                    }
                }
            }
        }
        System.out.println();
        System.out.printf(Locale.US, "=== %d configurations, %d flagged; slowest non-flagged click %.0f ms; compiles %d ===%n",
            sum.configs, sum.findings, sum.slowestNonCascadeMs, nCompile);
        for (String s : sum.findingLines) System.out.println("  " + s);
    }
}
