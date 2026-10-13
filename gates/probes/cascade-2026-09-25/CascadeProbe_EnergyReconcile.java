package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE PROBE — FAMILY D: energy / reconcile interplay (2026-09-25).
 *  Runs the shell's rebuildTrack loop VERBATIM (12 fast + 40 exact
 *  passes of reconcileChainedSpeeds + autoReoptimizeBridges, one
 *  compile each) after a simulated bridge-window spinner click, on
 *  the user's file "UVD long bridge update time 9.25.26.uvd" and on
 *  built variants: friction levels, speed controls before the bridge
 *  / on the target head, pinned sections, multi-section targets with
 *  real-anchored later sections, the fast pass's ±1e-3 tube, long
 *  upstream chains. Per pass it snapshots every bridge's
 *  optSignature() and every lane section's vDesign and prints the
 *  slots / sections that moved, so a cascade's mechanism is visible.
 *  Findings threshold: > 6 total passes or > 500 ms per click. */
public class CascadeProbe_EnergyReconcile {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign(A end)",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "lastOptDist|0" };

    static long tCompile, tOpt;
    static int nCompile, nOptTrue;
    static StringBuilder log = new StringBuilder();
    static boolean verbose = false;

    static Coaster load(String path) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(path))) {
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

    record Stat(int fast, int exact, boolean quiesced, double ms, int nOpt, String detail) {
        int passes() { return fast + exact; }
    }

    /** All bridges' signatures + all lane sections' vDesign (flattened). */
    static List<double[]> sigs(Coaster c) {
        List<double[]> out = new ArrayList<>();
        for (TrackPiece p : c.pieces) {
            for (Section s : p.sections) {
                if (s instanceof BridgeSection bs) out.add(bs.optSignature());
            }
        }
        return out;
    }

    static double[] vds(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) {
            for (Section s : p.sections) {
                if (s instanceof LaneSection ls) v.add(ls.vDesign());
            }
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static String diffSigs(List<double[]> a, List<double[]> b) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
            double[] x = a.get(k), y = b.get(k);
            for (int i = 0; i < Math.min(x.length, y.length); i++) {
                if (Math.abs(x[i] - y[i]) > 1e-9) {
                    sb.append(String.format(Locale.US, " br%d[%d %s] %.9g->%.9g (d=%.3g);",
                        k, i, SLOT[i], x[i], y[i], y[i] - x[i]));
                }
            }
        }
        return sb.length() == 0 ? " (no sig slot moved)" : sb.toString();
    }

    static String diffV(double[] a, double[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(b[i])) {
                sb.append(String.format(Locale.US, " sec%d vDesign %.12g->%.12g (d=%.3g);",
                    i, a[i], b[i], b[i] - a[i]));
            }
        }
        return sb.length() == 0 ? "" : " | vDesign writes:" + sb;
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest: gestureHot = false),
     *  instrumented per pass. */
    static Stat rebuild(Coaster c) {
        long t0 = System.nanoTime();
        int opt0 = nOptTrue;
        StringBuilder d = new StringBuilder();
        TrackCompiler.Result r = compile(c);
        List<double[]> sPrev = sigs(c);
        double[] vPrev = vds(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            boolean again = c.reconcileChainedSpeeds(r);
            long ta = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tOpt += System.nanoTime() - ta;
            if (re) { again = true; nOptTrue++; }
            if (!again) break;
            r = compile(c);
            List<double[]> sNow = sigs(c);
            double[] vNow = vds(c);
            d.append("    fast pass ").append(pass + 1).append(": reopt=").append(re)
             .append(diffSigs(sPrev, sNow)).append(diffV(vPrev, vNow)).append('\n');
            sPrev = sNow; vPrev = vNow;
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            boolean again = c.reconcileChainedSpeeds(r, true);
            long ta = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tOpt += System.nanoTime() - ta;
            if (re) { again = true; nOptTrue++; }
            if (!again) { quiesced = true; break; }
            r = compile(c);
            List<double[]> sNow = sigs(c);
            double[] vNow = vds(c);
            d.append("    exact pass ").append(pass + 1).append(": reopt=").append(re)
             .append(diffSigs(sPrev, sNow)).append(diffV(vPrev, vNow)).append('\n');
            sPrev = sNow; vPrev = vNow;
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Stat(fast, exact, quiesced, ms, nOptTrue - opt0, d.toString());
    }

    static BridgeSection bridgeOf(Coaster c, int bp) {
        TrackPiece p = c.pieces.get(bp);
        return (BridgeSection) p.sections.get(p.sections.size() - 1);
    }

    /** One bridge-window spinner click on piece bp's trailing bridge
     *  (BridgeSlowProbe.click's recipe): the write, optimizeBridge at
     *  lastOptDist (or the knee under optLen), then the rebuild loop. */
    static Stat click(Coaster c, int bp, int axis, double delta) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t1 = System.nanoTime();
        if (bs.optLen) {
            double d = c.optimalBridgeLength(bs, py);
            c.optimizeBridge(bs, bp + 1, d, py);
        } else {
            c.optimizeBridge(bs, bp + 1, bs.lastOptDist > 0 ? bs.lastOptDist : 1, py);
        }
        tOpt += System.nanoTime() - t1;
        Stat s = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        return new Stat(s.fast, s.exact, s.quiesced, ms, s.nOpt, s.detail);
    }

    static int worstPasses, worstMs;
    static String worstName = "";
    static int configs = 0;
    static double slowestNonCascade = 0;

    static String only = null;   // -only <prefix>: run just those batteries
    static int nClickDetail = 0;

    /** Per-section reconcile diagnostic: vDesign vs the DESIGN seam speed
     *  (the previous section's design end) vs the TRUE seam speed, and
     *  which one the D101/D237 anchor rule makes the reconcile chase. */
    static String anchorStates(Coaster c) {
        TrackCompiler.Result r = compile(c);
        StringBuilder sb = new StringBuilder("  anchor states:");
        int si = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            for (int k = 0; k < tp.sections.size(); k++, si++) {
                Section s = tp.sections.get(k);
                if (k == 0 || s instanceof BridgeSection || si >= r.spans().size()) continue;
                int ip = r.spans().get(si - 1).i1();
                double vIn = Math.max(0.1, r.samples().get(ip).v());
                double vTrue = Math.max(0.1, r.trueV()[ip]);
                TrackCompiler.Span own = r.spans().get(si);
                double vMin = vTrue;
                for (int i = own.i0(); i <= Math.min(own.i1(), r.trueV().length - 1); i++) vMin = Math.min(vMin, Math.max(0.1, r.trueV()[i]));
                boolean gate = Math.abs(vTrue - vIn) * (vTrue / vMin) > 0.2;
                boolean nearer = Math.abs(s.vDesign() - vTrue) < Math.abs(s.vDesign() - vIn);
                String st = s.vPinned() ? "PINNED" : (gate && nearer) ? "REAL-anchored" : "design";
                sb.append(String.format(Locale.US, " p%ds%d[vD %.4f dsgn %.4f true %.4f amp %.2f -> %s]",
                    p, k, s.vDesign(), vIn, vTrue, vTrue / vMin, st));
            }
        }
        return sb.toString();
    }

    /** Stretch every lane of piece p section k by f in x (handles keep
     *  their slopes, the D105 dv rescale — the strip's own recipe). */
    static void stretch(Coaster c, int p, int k, double f) {
        LaneSection ls = lane(c, p, k);
        for (List<BezierBaker.Node> ln : ls.lanes) {
            double[] gaps = BezierBaker.segGaps(ln);
            for (BezierBaker.Node nd : ln) nd.t *= f;
            BezierBaker.rescaleDvToGaps(ln, gaps);
        }
    }

    /** Run the click battery on a prepared coaster and report. */
    static void battery(String name, Coaster c) {
        if (only != null && !name.startsWith(only)) return;
        configs++;
        long c0 = tCompile, o0 = tOpt; int n0 = nCompile;
        // settle first (the open)
        Stat open = rebuild(c);
        Stat idle = rebuild(c);
        int[][] clicks = { {0, 1}, {0, 1}, {0, 2}, {1, 1}, {1, 2}, {0, 0}, {1, 0} };
        int maxP = 0; double maxMs = 0; boolean allQ = true; String worstDetail = "";
        StringBuilder row = new StringBuilder();
        for (int[] ck : clicks) {
            if (ck[0] + 1 >= c.pieces.size()) continue;
            Stat s = click(c, ck[0], ck[1], 1.0);
            row.append(String.format(Locale.US, " b%d/ax%d:%d+%d/%.0fms", ck[0], ck[1], s.fast, s.exact, s.ms));
            if (s.passes() > maxP || (s.passes() == maxP && s.ms > maxMs)) {
                maxP = Math.max(maxP, s.passes()); worstDetail = s.detail;
            }
            maxMs = Math.max(maxMs, s.ms);
            allQ &= s.quiesced;
        }
        System.out.printf(Locale.US,
            "[%s] open %d+%d/%.0fms idle %d+%d/%.0fms | clicks:%s | worst %d passes %.0f ms quiesced=%b | compiles %d avg %.1f ms, optimizeBridge total %.0f ms%n",
            name, open.fast, open.exact, open.ms, idle.fast, idle.exact, idle.ms, row, maxP, maxMs, allQ,
            nCompile - n0, (tCompile - c0) / 1e6 / Math.max(1, nCompile - n0), (tOpt - o0) / 1e6);
        if (!open.quiesced || !idle.quiesced) System.out.println("  !! NOT QUIESCED at open/idle — the document never settles (exact cap hit)");
        System.out.println(anchorStates(c));
        if (maxP > 6 || maxMs > 500 || verbose) {
            System.out.println("  mechanism (worst click):");
            System.out.print(worstDetail);
        } else if (!open.quiesced || open.passes() > 6) {
            System.out.println("  open-time mechanism:");
            System.out.print(open.detail);
        }
        if (maxP > 6 || maxMs > 500) {
            if (maxP > worstPasses) { worstPasses = maxP; worstName = name; }
        } else {
            slowestNonCascade = Math.max(slowestNonCascade, maxMs);
        }
        System.out.println("  (open-time detail: fast " + open.fast + " exact " + open.exact + ")");
        if (verbose) System.out.print(open.detail);
    }

    static SpeedControl ctl(int type, double x0, double len, double speed,
                            boolean min, boolean max) {
        SpeedControl sc = new SpeedControl(x0, len);
        sc.type = type; sc.speed = speed; sc.minLimit = min; sc.maxLimit = max;
        sc.accel = 0.5; sc.decel = 0.5;
        return sc;
    }

    static LaneSection lane(Coaster c, int p, int k) {
        return (LaneSection) c.pieces.get(p).sections.get(k);
    }

    /** Split piece p's section k into n equal parts (by authored x). */
    static void chain(Coaster c, int p, int k, int n) {
        for (int i = 1; i < n; i++) {
            LaneSection ls = lane(c, p, k + i - 1);
            double end = BezierBaker.rideEnd(ls.lanes);
            double xc = end / (n - i + 1);
            if (!c.splitSectionAt(p, k + i - 1, xc, DT)) {
                System.out.println("  !! split refused at piece " + p + " sec " + (k + i - 1) + " xc=" + xc);
            }
        }
    }

    /** Flattened span index of piece p section k. */
    static int spanIdx(Coaster c, int p, int k) {
        int s = k;
        for (int i = 0; i < p; i++) s += c.pieces.get(i).sections.size();
        return s;
    }

    /** Anchor piece p section k (k >= 1) to the TRUE seam speed. */
    static void realAnchor(Coaster c, int p, int k) {
        TrackCompiler.Result r = compile(c);
        int si = spanIdx(c, p, k);
        int ip = r.spans().get(si - 1).i1();
        double vTrue = Math.max(0.1, r.trueV()[ip]);
        double vDes = Math.max(0.1, r.samples().get(ip).v());
        System.out.printf(Locale.US, "  realAnchor p%d s%d: design seam %.4f true seam %.4f -> vDesign := true%n",
            p, k, vDes, vTrue);
        lane(c, p, k).vDesign(vTrue);
    }

    static String pieceInfo(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            sb.append("p").append(p).append('[').append(tp.orientMode).append(':');
            for (Section s : tp.sections) {
                if (s instanceof BridgeSection b) sb.append(String.format(Locale.US, " br(m%d L%b d%.1f l%b%b%b)", b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll));
                else sb.append(String.format(Locale.US, " %s%.2f%s", s instanceof GeoSection ? "g" : "f", s.vDesign(), s.vPinned() ? "P" : ""));
            }
            sb.append("] ");
        }
        return sb.toString();
    }

    public static void main(String[] a) throws Exception {
        String path = a.length > 0 ? a[0]
            : "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
        for (int i = 1; i < a.length; i++) {
            if (a[i].equals("-v")) verbose = true;
            else if (a[i].equals("-only") && i + 1 < a.length) only = a[++i];
        }
        // warm the JIT once
        { Coaster w = load(path); rebuild(w); click(w, 0, 1, 1); click(w, 1, 1, 1); }

        System.out.println("=== FAMILY D: energy / reconcile interplay ===");
        Coaster c;

        if (only != null && only.startsWith("D6")) { d6(path); return; }

        // ---- (1) friction levels on the file as saved
        c = load(path); System.out.println(pieceInfo(c));
        battery("D1a as saved (fric 0.03/1e-4)", c);
        c = load(path); c.fricRoll = 0; c.fricAir = 0;
        battery("D1b fric 0/0", c);
        c = load(path); c.fricRoll = 0.15; c.fricAir = 1e-3;
        battery("D1c fric 0.15/1e-3 (high)", c);
        {
            TrackCompiler.Result r = compile(c);
            double vmin = 1e9; for (double v : r.trueV()) vmin = Math.min(vmin, v);
            System.out.printf(Locale.US, "  (high friction: min true v over the ride %.3f m/s; bridge0 vDesign slot %.4f, bridge1 %.4f)%n",
                vmin, bridgeOf(c, 0).vDesign, bridgeOf(c, 1).vDesign);
        }
        c = load(path); c.fricRoll = 0.06; c.fricAir = 3e-4;
        battery("D1d fric 0.06/3e-4", c);

        // ---- (2) speed controls
        c = load(path); lane(c, 0, 0).speedCtls.add(ctl(SpeedControl.SPEED, 10, 3, 18, false, true));
        battery("D2a SPEED max(brake 18) on P0 sec0 before bridge0 [10..13 s]", c);
        c = load(path); lane(c, 0, 0).speedCtls.add(ctl(SpeedControl.SPEED, 10, 3, 32, true, false));
        battery("D2b SPEED min(lift 32) on P0 sec0 before bridge0", c);
        c = load(path); lane(c, 0, 0).speedCtls.add(ctl(SpeedControl.SPEED, 10, 3.46, 22, true, true));
        battery("D2c SPEED hold 22 to the very end of P0 sec0", c);
        c = load(path); lane(c, 0, 0).speedCtls.add(ctl(SpeedControl.ACCEL, 9, 4, 34, true, false));
        battery("D2d ACCEL launch->34 on P0 sec0", c);
        c = load(path); lane(c, 1, 0).speedCtls.add(ctl(SpeedControl.SPEED, 0.2, 0.6, 20, true, true));
        battery("D2e SPEED hold 20 on P1 sec0 (target head of br0 = A side of br1) [0.2..0.8 s]", c);
        c = load(path); lane(c, 1, 0).speedCtls.add(ctl(SpeedControl.SPEED, 0.0, 1.0, 20, true, true));
        battery("D2f SPEED hold 20 on P1 sec0 whole section (x0=0)", c);
        c = load(path); lane(c, 1, 0).speedCtls.add(ctl(SpeedControl.ACCEL, 0.0, 1.0, 30, true, false));
        battery("D2g ACCEL launch->30 on P1 sec0 whole section", c);
        c = load(path); lane(c, 2, 0).speedCtls.add(ctl(SpeedControl.SPEED, 0.5, 1.0, 12, false, true));
        battery("D2h SPEED brake 12 on P2 sec0 (target of br1)", c);
        c = load(path);
        lane(c, 0, 0).speedCtls.add(ctl(SpeedControl.SPEED, 10, 3.46, 22, true, true));
        lane(c, 1, 0).speedCtls.add(ctl(SpeedControl.SPEED, 0.0, 1.0, 20, true, true));
        lane(c, 2, 0).speedCtls.add(ctl(SpeedControl.SPEED, 0.5, 1.0, 12, false, true));
        c.fricRoll = 0.15; c.fricAir = 1e-3;
        battery("D2i all three controls + high friction", c);

        // ---- (3)/(4) multi-section targets, reconciled + real-anchored + pinned
        c = load(path); chain(c, 1, 0, 2); chain(c, 2, 0, 2);
        System.out.println(pieceInfo(c));
        battery("D4a P1 split in 2, P2 split in 2 (reconciled later sections)", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4);
        battery("D4b P1 split in 4, P2 split in 4", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        battery("D4c P1/P2 split in 4 + sec1 REAL-anchored", c);
        c = load(path); chain(c, 1, 0, 8); chain(c, 2, 0, 8); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        battery("D4d P1/P2 split in 8 + sec1 REAL-anchored", c);
        c = load(path); chain(c, 1, 0, 8); rebuild(c);
        for (int k = 1; k < 8; k++) realAnchor(c, 1, k);
        battery("D4e P1 split in 8, EVERY later section REAL-anchored", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        lane(c, 1, 1).vDesign(15); lane(c, 1, 1).vPinned(true);
        lane(c, 2, 2).vDesign(12); lane(c, 2, 2).vPinned(true);
        battery("D3a pinned conflicts: P1 sec1 pinned 15, P2 sec2 pinned 12", c);
        c = load(path); chain(c, 0, 0, 4); rebuild(c);
        lane(c, 0, 2).vDesign(20); lane(c, 0, 2).vPinned(true);
        battery("D3b P0 split in 4, sec2 pinned 20 (before bridge0)", c);

        // ---- (4) + LOCKSTEP / optLen interplay with reconciled targets
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        for (int p = 1; p < 3; p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
        battery("D4f split-4 real-anchored + LOCKSTEP targets", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        for (int p = 0; p < 2; p++) { BridgeSection b = bridgeOf(c, p); b.optLen = true; c.optimizeBridge(b, p + 1, c.optimalBridgeLength(b, true), true); }
        battery("D4g split-4 real-anchored + optLen ON (REAL_ONLY)", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        for (int p = 1; p < 3; p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
        for (int p = 0; p < 2; p++) { BridgeSection b = bridgeOf(c, p); b.optLen = true; c.optimizeBridge(b, p + 1, c.optimalBridgeLength(b, true), true); }
        battery("D4h split-4 real-anchored + LOCKSTEP + optLen", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        for (int p = 0; p < 2; p++) { BridgeSection b = bridgeOf(c, p); b.lockPitch = false; b.lockRoll = false; }
        battery("D4i split-4 real-anchored + pitch/roll UNLOCKED (aligned) REAL_ONLY", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        realAnchor(c, 1, 1); realAnchor(c, 2, 1);
        c.fricRoll = 0.15; c.fricAir = 1e-3;
        battery("D4j split-4 real-anchored + high friction", c);

        // ---- (5) the fast-pass ±1e-3 tube
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        { double v = lane(c, 1, 1).vDesign(); lane(c, 1, 1).vDesign(v + 5e-4); }
        battery("D5a P1 sec1 vDesign nudged +5e-4 (inside the tube)", c);
        c = load(path); chain(c, 1, 0, 4); chain(c, 2, 0, 4); rebuild(c);
        { double v = lane(c, 1, 1).vDesign(); lane(c, 1, 1).vDesign(v + 0.5); }
        battery("D5b P1 sec1 vDesign nudged +0.5", c);
        c = load(path); chain(c, 0, 0, 8); rebuild(c);
        System.out.println(pieceInfo(c));
        battery("D5c P0 split in 8 (long upstream chain, settled)", c);
        c = load(path); chain(c, 0, 0, 8); rebuild(c);
        { double v = lane(c, 0, 1).vDesign(); lane(c, 0, 1).vDesign(v + 0.5); }
        {
            Stat s = rebuild(c);
            System.out.printf(Locale.US, "[D5d P0 split in 8, sec1 +0.5 then ONE rebuild (an upstream edit, not a click)] %d+%d/%.0fms quiesced=%b nOpt=%d%n",
                s.fast, s.exact, s.ms, s.quiesced, s.nOpt);
            System.out.print(s.detail);
        }
        battery("D5d' same doc, clicks afterwards", c);
        c = load(path); chain(c, 0, 0, 16); rebuild(c);
        { double v = lane(c, 0, 1).vDesign(); lane(c, 0, 1).vDesign(v + 1e-3 * 0.9); }
        {
            Stat s = rebuild(c);
            System.out.printf(Locale.US, "[D5e P0 split in 16, sec1 +9e-4 (tube) then ONE rebuild] %d+%d/%.0fms quiesced=%b nOpt=%d%n",
                s.fast, s.exact, s.ms, s.quiesced, s.nOpt);
            System.out.print(s.detail);
        }
        // a tube case with a bridge-target chain: real-anchored P1 sec1 + tiny click
        c = load(path); chain(c, 1, 0, 8); rebuild(c);
        for (int k = 1; k < 8; k++) realAnchor(c, 1, k);
        rebuild(c);
        {
            Stat s = click(c, 0, 1, 0.01);
            System.out.printf(Locale.US, "[D5f P1 split-8 all real-anchored, a 0.01 deg pitch click on br0] %d+%d/%.0fms quiesced=%b nOpt=%d%n",
                s.fast, s.exact, s.ms, s.quiesced, s.nOpt);
            System.out.print(s.detail);
        }

        d6(path);
        System.out.printf(Locale.US, "%n=== SUMMARY: %d configs; worst cascade: %s (%d passes); slowest non-cascade click %.0f ms ===%n",
            configs, worstName.isEmpty() ? "none" : worstName, worstPasses, slowestNonCascade);
    }

    /** D6: how a target's later sections BECOME real-anchored without any
     *  resolve op, and anchored chains deeper than the exact loop's cap. */
    static void d6(String path) throws Exception {
        Coaster c;
        // D6a: P1 split in 4 (design-chained), then a plain GRAPH EDIT on
        // P1's head (sec 0's Gv lane raised to 3.5 g = a hard pull-up: its
        // design end speed drops) — does sec 1 flip to REAL-anchored?
        c = load(path); chain(c, 1, 0, 4); rebuild(c);
        System.out.println("[D6a before the head edit]" ); System.out.println(anchorStates(c));
        for (BezierBaker.Node nd : lane(c, 1, 0).lanes.get(0)) nd.val = 3.5;
        Stat s = rebuild(c);
        System.out.printf(Locale.US, "[D6a P1 split-4, then P1 sec0 Gv := 3.5 g (a graph edit on the target head) — ONE rebuild] %d+%d/%.0fms quiesced=%b nOpt=%d%n", s.fast, s.exact, s.ms, s.quiesced, s.nOpt);
        System.out.print(s.detail);
        battery("D6a' clicks after the head edit", c);
        // D6a2: the same with the pull-up on P1 sec 0 milder (2.0 g)
        c = load(path); chain(c, 1, 0, 4); rebuild(c);
        for (BezierBaker.Node nd : lane(c, 1, 0).lanes.get(0)) nd.val = 0.2;
        s = rebuild(c);
        System.out.printf(Locale.US, "[D6b P1 split-4, then P1 sec0 Gv := 0.2 g (float) — ONE rebuild] %d+%d/%.0fms quiesced=%b nOpt=%d%n", s.fast, s.exact, s.ms, s.quiesced, s.nOpt);
        battery("D6b' clicks after the float edit", c);
        // D6c: anchored chains DEEPER than the exact cap (40): stretch P1
        // and P2 to 10 s, split each into 24, real-anchor every later
        // section (23 + 23 = 46 reconciled sections downstream of br0)
        c = load(path); stretch(c, 1, 0, 10); stretch(c, 2, 0, 5); rebuild(c);
        chain(c, 1, 0, 24); chain(c, 2, 0, 24); rebuild(c);
        for (int k = 1; k < 24; k++) { realAnchor(c, 1, k); }
        for (int k = 1; k < 24; k++) { realAnchor(c, 2, k); }
        battery("D6c P1 (10 s) + P2 (10 s) split in 24 each, all later sections REAL-anchored (46 deep)", c);
        {
            Stat s2 = rebuild(c);
            System.out.printf(Locale.US, "  D6c second idle rebuild: %d+%d/%.0fms quiesced=%b nOpt=%d%n", s2.fast, s2.exact, s2.ms, s2.quiesced, s2.nOpt);
            s2 = click(c, 0, 1, 1.0);
            System.out.printf(Locale.US, "  D6c one more b0 pitch click: %d+%d/%.0fms quiesced=%b nOpt=%d%n", s2.fast, s2.exact, s2.ms, s2.quiesced, s2.nOpt);
            s2 = rebuild(c);
            System.out.printf(Locale.US, "  D6c idle rebuild after that click: %d+%d/%.0fms quiesced=%b nOpt=%d%n", s2.fast, s2.exact, s2.ms, s2.quiesced, s2.nOpt);
        }
        // D6f: the NATURAL user path — the target head's "Set Design to
        // Real" (Coaster.refreezeSpeedOnly on P1 sec 0, the init-speed
        // window's button / the badge's resolve op) on a target whose
        // later sections are design-chained. Do they flip to REAL-
        // anchored by the D237 rule, and what does a click cost after?
        for (int n : new int[] {4, 8, 16}) {
            c = load(path); if (n == 16) { stretch(c, 1, 0, 4); stretch(c, 2, 0, 4); }
            chain(c, 1, 0, n); chain(c, 2, 0, n); rebuild(c);
            System.out.println("[D6f-" + n + " before Set Design to Real on P1 sec0]"); System.out.println(anchorStates(c));
            boolean ok = c.refreezeSpeedOnly(1, 0, DT);
            Stat s3 = rebuild(c);
            System.out.printf(Locale.US, "[D6f-%d P1/P2 split-%d, Set Design to Real on P1 sec0 (ok=%b) — ONE rebuild] %d+%d/%.0fms quiesced=%b nOpt=%d%n", n, n, ok, s3.fast, s3.exact, s3.ms, s3.quiesced, s3.nOpt);
            battery("D6f-" + n + "' clicks after Set Design to Real on the P1 head", c);
            ok = c.refreezeSpeedOnly(2, 0, DT);
            rebuild(c);
            battery("D6g-" + n + " + Set Design to Real on the P2 head too (ok=" + ok + ")", c);
        }
        // D6d: the same depth but DESIGN-chained (no anchors) — control
        c = load(path); stretch(c, 1, 0, 10); stretch(c, 2, 0, 5); rebuild(c);
        chain(c, 1, 0, 24); chain(c, 2, 0, 24); rebuild(c);
        battery("D6d same 46-deep chains, design-chained (control)", c);
        // D6e: only P1 anchored (23 deep), P2 design-chained
        c = load(path); stretch(c, 1, 0, 10); stretch(c, 2, 0, 5); rebuild(c);
        chain(c, 1, 0, 24); chain(c, 2, 0, 24); rebuild(c);
        for (int k = 1; k < 24; k++) { realAnchor(c, 1, k); }
        battery("D6e P1 split-24 all anchored (23 deep), P2 design-chained", c);
    }
}
