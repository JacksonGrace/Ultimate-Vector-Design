package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE VERIFY (2026-09-25) — topology family: "Five chained
 *  bridges, REAL_ONLY, pitch/roll locked, Optimize length OFF".
 *  Independent rebuild of the reported configuration: the user's file
 *  ("UVD long bridge update time 9.25.26.uvd", 3 pieces / 2 PY bridges)
 *  + copies of its last piece bridged on (PY mode, yaw free, pitch/roll
 *  locked, 25 m, optLen off), settled; then ONE bridge-window pitch
 *  spinner click on bridge 1's target (setDispPitch +1 → optimizeBridge
 *  at lastOptDist → the shell's rebuild loop verbatim). Every fast/exact
 *  pass prints the reconcile writes and, per bridge, the signature
 *  slots that differ from its stamp (index:name old→new) so the
 *  mechanism is visible. Then the same click with the target head
 *  re-captured BEFORE the click's march (the candidate fix), on an
 *  independently built twin, and the settled documents are compared
 *  bitwise. */
public class CascadeVerify_topology_Five_chained_bridges__REAL_ONL {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len" };

    static long tCompile;
    static int nCompile;

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
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

    /** Coaster.sigEq, copied (it is private): 1e-9 per slot. */
    static boolean sigEq(double[] a, double[] b) {
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-9) return false;
        }
        return true;
    }

    record Br(int p, BridgeSection bs) {}

    /** Every live optimize-mode bridge (piece p's trailing bridge with a
     *  target p+1) — the set autoReoptimizeBridges acts on. */
    static List<Br> bridges(Coaster c) {
        List<Br> out = new ArrayList<>();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            List<Section> s = c.pieces.get(p).sections;
            if (!s.isEmpty() && s.get(s.size() - 1) instanceof BridgeSection bs
                    && bs.optMode != BridgeSection.OPT_NONE) {
                out.add(new Br(p, bs));
            }
        }
        return out;
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return (BridgeSection) s.get(s.size() - 1);
    }

    /** Per-bridge signature vs stamp as the auto pass will see it;
     *  fills `re` with the bridges the pass WILL re-march. */
    static String sigReport(Coaster c, List<Integer> re) {
        StringBuilder sb = new StringBuilder();
        for (Br b : bridges(c)) {
            double[] sig = b.bs.optSignature();
            double[] st = b.bs.optSig;
            String tag = "b" + (b.p + 1);
            if (st == null) { sb.append(tag).append("[first-sight] "); continue; }
            if (st.length == 0) { sb.append(tag).append("[SIG_STALE] "); re.add(b.p); continue; }
            if (sigEq(sig, st)) { sb.append(tag).append("= "); continue; }
            re.add(b.p);
            sb.append(tag).append("{");
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - st[i]) > 1e-9) {
                    sb.append(String.format(Locale.US, "%d:%s %.5g→%.5g ",
                        i, SLOT[i], st[i], sig[i]));
                }
            }
            sb.append("} ");
        }
        return sb.toString();
    }

    static double[] vDesigns(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) v.add(s.vDesign());
        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) out[i] = v.get(i);
        return out;
    }

    static String vWrites(Coaster c, double[] before) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++, i++) {
                double now = secs.get(k).vDesign();
                if (Double.doubleToLongBits(now) != Double.doubleToLongBits(before[i])) {
                    sb.append(String.format(Locale.US, "%d/%d %.6f→%.6f ", p, k, before[i], now));
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** Coaster.autoReoptimizeBridges' twin with the candidate fix on the
     *  AUTO side too: re-capture the target head before the march.
     *  Expected to be a no-op difference here (the auto pass runs right
     *  after a compile, so its capture is already fresh). */
    static boolean autoTwinFixed(Coaster c) {
        boolean changed = false;
        for (Br b : bridges(c)) {
            double[] sig = b.bs.optSignature();
            if (b.bs.optSig == null) { b.bs.optSig = sig; continue; }
            if (sigEq(sig, b.bs.optSig)) continue;
            boolean py = b.bs.optMode == BridgeSection.OPT_POS_YAW;
            TrackCompiler.captureTargetHead(b.bs, c.pieces.get(b.p + 1), DT, c.fric());
            double dist = b.bs.optLen ? c.optimalBridgeLength(b.bs, py)
                : b.bs.lastOptDist > 0 ? b.bs.lastOptDist : 1;
            c.optimizeBridge(b.bs, b.p + 1, dist, py);
            changed = true;
        }
        return changed;
    }

    static final class Loop {
        int fast, exact, compiles, remarches, vwrites;
        boolean quiesced;
        String trace = "";
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim in structure (at rest:
     *  12 fast passes with the fast reconcile, then 40 exact passes),
     *  instrumented per pass. */
    static Loop rebuild(Coaster c, boolean verbose, boolean fixAuto) {
        Loop L = new Loop();
        StringBuilder tr = new StringBuilder();
        TrackCompiler.Result r = compile(c);
        L.compiles++;
        for (int pass = 0; pass < 12; pass++) {
            L.fast++;
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r);
            String vw = vWrites(c, vb);
            if (!vw.equals("none")) L.vwrites++;
            List<Integer> re = new ArrayList<>();
            String sr = sigReport(c, re);
            boolean reopt = fixAuto ? autoTwinFixed(c) : c.autoReoptimizeBridges(r);
            L.remarches += re.size();
            tr.append(String.format(Locale.US, "    fast %d: vDesign writes=%s | remarch=%s | %s%n",
                pass + 1, vw, labels(re), sr));
            if (reopt) again = true;
            if (!again) break;
            r = compile(c);
            L.compiles++;
        }
        for (int pass = 0; pass < 40; pass++) {
            L.exact++;
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r, true);
            String vw = vWrites(c, vb);
            if (!vw.equals("none")) L.vwrites++;
            List<Integer> re = new ArrayList<>();
            String sr = sigReport(c, re);
            boolean reopt = fixAuto ? autoTwinFixed(c) : c.autoReoptimizeBridges(r);
            L.remarches += re.size();
            tr.append(String.format(Locale.US, "    exact %d: vDesign writes=%s | remarch=%s | %s%n",
                pass + 1, vw, labels(re), sr));
            if (reopt) again = true;
            if (!again) { L.quiesced = true; break; }
            r = compile(c);
            L.compiles++;
        }
        L.trace = tr.toString();
        if (verbose) System.out.print(L.trace);
        return L;
    }

    /** Piece indices → "b1,b2" (bridge n = piece n−1's trailing bridge). */
    static String labels(List<Integer> re) {
        if (re.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int p : re) sb.append(sb.length() == 0 ? "" : ",").append('b').append(p + 1);
        return sb.toString();
    }

    static String fmt(Loop L) {
        return String.format(Locale.US, "fast=%d exact=%d quiesced=%b compiles=%d remarches=%d vWritePasses=%d",
            L.fast, L.exact, L.quiesced, L.compiles, L.remarches, L.vwrites);
    }

    // ------------------------------------------------------------------
    // fixture construction
    // ------------------------------------------------------------------

    static BridgeSection addBridge(Coaster c, int p, double len) {
        TrackCompiler.Result r = compile(c);
        int spanIdx = 0;
        for (int i = 0; i < p; i++) spanIdx += c.pieces.get(i).sections.size();
        spanIdx += c.pieces.get(p).sections.size() - 1;
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        TrackPiece t = c.pieces.get(p + 1);
        BridgeSection bs = BridgeSection.solve(sp.exit(), t.entryPose(),
            r.samples().get(sp.i1()).v());
        bs.optMode = BridgeSection.OPT_POS_YAW;
        bs.lockYaw = false;
        bs.lockPitch = true;
        bs.lockRoll = true;
        bs.optLen = false;
        c.pieces.get(p).sections.add(bs);
        c.optimizeBridge(bs, p + 1, len, true);
        return bs;
    }

    /** The user's file + (n − 2) copied targets bridged on, each step
     *  settled with two rebuild loops. */
    static Coaster build(int nBridges) throws IOException {
        Coaster c = load();
        rebuild(c, false, false);
        rebuild(c, false, false);
        while (bridges(c).size() < nBridges) {
            int last = c.pieces.size() - 1;
            TrackPiece cp = c.pieces.get(last).copy(30);
            cp.followPrev = false;
            c.pieces.add(cp);
            addBridge(c, last, 25.0);
            rebuild(c, false, false);
            rebuild(c, false, false);
        }
        return c;
    }

    static String describe(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            Section last = tp.sections.get(tp.sections.size() - 1);
            sb.append(String.format(Locale.US,
                "    piece %d mode=%s secs=%d pos=(%.2f,%.2f,%.2f) yaw=%.2f true=(%.2f,%.2f) design=(%.2f,%.2f)%s%n",
                p, tp.orientMode, tp.sections.size(), tp.x, tp.y, tp.z, tp.yawDeg,
                tp.pitchDeg, tp.rollDeg, tp.designPitchDeg, tp.designRollDeg,
                last instanceof BridgeSection b ? String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks y/p/r=%b/%b/%b",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll) : ""));
        }
        return sb.toString();
    }

    /** Bitwise state: every piece's placement + both tiers + every
     *  bridge's lastOptDist/lenA/lenB/stamped signature. */
    static String stateOf(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (TrackPiece tp : c.pieces) {
            sb.append(tp.x).append(',').append(tp.y).append(',').append(tp.z).append(',')
              .append(tp.yawDeg).append(',').append(tp.pitchDeg).append(',').append(tp.rollDeg)
              .append(',').append(tp.designPitchDeg).append(',').append(tp.designRollDeg).append(';');
            for (Section s : tp.sections) {
                sb.append(s.vDesign()).append(',');
                if (s instanceof BridgeSection b) {
                    sb.append("B").append(b.lastOptDist).append(',').append(b.lenA).append(',').append(b.lenB);
                    if (b.optSig != null) for (double d : b.optSig) sb.append(',').append(d);
                }
                sb.append(';');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Max |Δ| over every piece's x/y/z/yaw/pitch/roll/design tiers and
     *  every bridge's lastOptDist between two same-shape documents. */
    static String maxPlacementDiff(Coaster a, Coaster b) {
        double mx = 0; String where = "-";
        for (int p = 0; p < a.pieces.size(); p++) {
            TrackPiece x = a.pieces.get(p), y = b.pieces.get(p);
            double[] va = { x.x, x.y, x.z, x.yawDeg, x.pitchDeg, x.rollDeg, x.designPitchDeg, x.designRollDeg };
            double[] vb = { y.x, y.y, y.z, y.yawDeg, y.pitchDeg, y.rollDeg, y.designPitchDeg, y.designRollDeg };
            String[] nm = { "x", "y", "z", "yaw", "pitch", "roll", "dPitch", "dRoll" };
            for (int i = 0; i < va.length; i++) {
                double d = Math.abs(va[i] - vb[i]);
                if (d > mx) { mx = d; where = "piece " + p + " " + nm[i]; }
            }
            Section la = x.sections.get(x.sections.size() - 1), lb = y.sections.get(y.sections.size() - 1);
            if (la instanceof BridgeSection ba && lb instanceof BridgeSection bb) {
                double d = Math.abs(ba.lastOptDist - bb.lastOptDist);
                if (d > mx) { mx = d; where = "bridge " + (p + 1) + " lastOptDist"; }
            }
        }
        return String.format(Locale.US, "max |Δ| = %.3g (%s)", mx, where);
    }

    static String firstDiff(String a, String b) {
        if (a.equals(b)) return "identical";
        String[] la = a.split("\n"), lb = b.split("\n");
        for (int i = 0; i < Math.min(la.length, lb.length); i++) {
            if (!la[i].equals(lb[i])) return "piece " + i + ":\n      A " + la[i] + "\n      B " + lb[i];
        }
        return "line counts differ";
    }

    // ------------------------------------------------------------------
    // the click
    // ------------------------------------------------------------------

    static final class Click {
        double ms, moved, compileMs;
        Loop loop;
    }

    /** One bridge-window PITCH spinner click on piece bp's trailing
     *  bridge's target (the shell path: setDispPitch(route=true) →
     *  optimizeBridge at lastOptDist (or the knee under optLen) →
     *  rebuild). fixClick = re-capture the target head before the
     *  march (the candidate fix). */
    static Click click(Coaster c, int bp, double dPitch, boolean fixClick,
                       boolean fixAuto, boolean verbose, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        tCompile = 0; nCompile = 0;
        long t0 = System.nanoTime();
        t.setDispPitch(t.dispPitch() + dPitch, true);
        if (fixClick) TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double d = bs.optLen ? c.optimalBridgeLength(bs, py)
            : bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        c.optimizeBridge(bs, bp + 1, d, py);
        double[] afterMarch = { t.x, t.y, t.z, t.yawDeg };
        Loop L = rebuild(c, verbose, fixAuto);
        Click ck = new Click();
        ck.ms = (System.nanoTime() - t0) / 1e6;
        ck.loop = L;
        ck.compileMs = tCompile / 1e6;
        ck.moved = Math.sqrt(Math.pow(t.x - afterMarch[0], 2) + Math.pow(t.y - afterMarch[1], 2)
            + Math.pow(t.z - afterMarch[2], 2));
        System.out.printf(Locale.US,
            "  [%s] click b%d pitch %+.0f: %.0f ms — %s — compiles %.0f ms total | target moved %.4f m (yaw %+.4f°) after the click's own march%n",
            label, bp + 1, dPitch, ck.ms, fmt(L), ck.compileMs, ck.moved, t.yawDeg - afterMarch[3]);
        return ck;
    }

    static Loop idle(Coaster c, String label) {
        tCompile = 0; nCompile = 0;
        long t0 = System.nanoTime();
        Loop L = rebuild(c, false, false);
        System.out.printf(Locale.US, "  [%s] idle rebuild: %.0f ms — %s%n", label,
            (System.nanoTime() - t0) / 1e6, fmt(L));
        return L;
    }

    public static void main(String[] a) throws Exception {
        for (int rep = 0; rep < 2; rep++) {
            System.out.println("=== rep " + rep + (rep == 0 ? " (cold JIT)" : " (warm)") + " ===");

            // ---- N = 2 : the user's file as saved
            Coaster c2 = build(2);
            System.out.println("N=2 (the file as saved):");
            System.out.print(describe(c2));
            idle(c2, "N=2");
            click(c2, 0, +1, false, false, rep == 1, "N=2 as shipped");
            click(c2, 0, -1, false, false, false, "N=2 as shipped");
            click(c2, 0, +1, false, false, false, "N=2 as shipped");

            // ---- N = 3, 4 : scaling
            for (int n = 3; n <= 4; n++) {
                Coaster c = build(n);
                idle(c, "N=" + n);
                click(c, 0, +1, false, false, false, "N=" + n + " as shipped");
                click(c, 0, -1, false, false, false, "N=" + n + " as shipped");
                click(c, 0, +1, false, false, false, "N=" + n + " as shipped");
            }

            // ---- N = 5 : the reported configuration, three independent builds
            Coaster A = build(5), B = build(5), C = build(5);
            System.out.println("N=5 (the reported configuration):");
            System.out.print(describe(A));
            System.out.println("  three independent builds bitwise equal: "
                + (stateOf(A).equals(stateOf(B)) && stateOf(B).equals(stateOf(C))));
            idle(A, "N=5");
            System.out.println("  --- A: as shipped (verbose) ---");
            click(A, 0, +1, false, false, true, "N=5 as shipped");
            System.out.println("  --- B: click-side fix — captureTargetHead before the click's march (verbose) ---");
            click(B, 0, +1, true, false, true, "N=5 click-fix");
            System.out.println("  --- C: click-side fix + auto-side twin ---");
            click(C, 0, +1, true, true, false, "N=5 click+auto-fix");
            System.out.println("  settled documents after the click — A vs B: " + firstDiff(stateOf(A), stateOf(B)));
            System.out.println("  settled placements after the click — A vs B: " + maxPlacementDiff(A, B));
            System.out.println("  settled documents after the click — B vs C: " + firstDiff(stateOf(B), stateOf(C)));
            idle(A, "N=5 A after click");
            idle(B, "N=5 B after click");
            // more clicks, warm, both flavors
            click(A, 0, -1, false, false, false, "N=5 as shipped");
            click(A, 0, +1, false, false, false, "N=5 as shipped");
            click(B, 0, -1, true, false, false, "N=5 click-fix");
            click(B, 0, +1, true, false, false, "N=5 click-fix");
            System.out.println("  settled documents after 3 clicks each — A vs B: " + firstDiff(stateOf(A), stateOf(B)));
            System.out.println("  settled placements after 3 clicks each — A vs B: " + maxPlacementDiff(A, B));
            // position in the chain: the LAST bridge, then a middle one
            click(A, 4, +1, false, false, false, "N=5 as shipped, last bridge");
            click(A, 4, -1, false, false, false, "N=5 as shipped, last bridge");
            click(A, 2, +1, false, false, false, "N=5 as shipped, bridge 3");
            click(A, 2, -1, false, false, false, "N=5 as shipped, bridge 3");
            // roll instead of pitch on b1
            {
                BridgeSection bs = bridgeOf(A, 0);
                TrackPiece t = A.pieces.get(1);
                tCompile = 0; nCompile = 0;
                long t0 = System.nanoTime();
                t.setDispRoll(t.dispRoll() + 1, true);
                A.optimizeBridge(bs, 1, bs.lastOptDist, true);
                Loop L = rebuild(A, false, false);
                System.out.printf(Locale.US, "  [N=5 as shipped] click b1 roll +1: %.0f ms — %s%n",
                    (System.nanoTime() - t0) / 1e6, fmt(L));
            }

            // ---- context: N = 5 with Optimize length ON everywhere
            Coaster D = build(5);
            for (Br b : bridges(D)) {
                b.bs.optLen = true;
                double d = D.optimalBridgeLength(b.bs, true);
                D.optimizeBridge(b.bs, b.p + 1, d, true);
                rebuild(D, false, false);
                rebuild(D, false, false);
            }
            idle(D, "N=5 optLen ON");
            {
                BridgeSection b1 = bridgeOf(D, 0);
                long t0 = System.nanoTime();
                double k1 = D.optimalBridgeLength(b1, true);
                long t1 = System.nanoTime();
                double k2 = D.optimalBridgeLength(b1, true);
                long t2 = System.nanoTime();
                System.out.printf(Locale.US, "  [N=5 optLen ON] one knee (optimalBridgeLength b1): %.2f m in %.0f ms, again %.0f ms; lastOptDist per bridge:",
                    k1, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
                for (Br b : bridges(D)) System.out.printf(Locale.US, " b%d=%.2f", b.p + 1, b.bs.lastOptDist);
                System.out.println();
            }
            click(D, 0, +1, false, false, false, "N=5 optLen ON as shipped");
            click(D, 0, -1, false, false, false, "N=5 optLen ON as shipped");
            click(D, 0, +1, true, false, false, "N=5 optLen ON click-fix");
        }
    }
}
