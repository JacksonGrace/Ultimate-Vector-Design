package uvd;

import java.util.*;

/** FAMILY F — TIME BREAKDOWN of the slow (but quiescing) states found by
 *  CascadeProbe_F: per phase — the explicit optimizeBridge, each compile,
 *  each auto re-optimize pass (with the bridges it re-marched), the
 *  reconcile — plus standalone timings of optimalBridgeLength, the
 *  bridge's own integrate(dt) (the march spine) and captureTargetHead,
 *  so the cost is attributable. Reuses CascadeProbe_F's helpers. */
public class CascadeProbe_Fbd {
    static final double DT = Coaster.INTEGRATE_DT;

    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

    /** The rebuild loop with per-phase timing; prints one line per pass. */
    static void rebuildTimed(Coaster c, String label) {
        long tAll = System.nanoTime();
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "    compile0 %.0f ms", ms(t0)));
        int passes = 0;
        for (int phase = 0; phase < 2; phase++) {
            int cap = phase == 0 ? 12 : 40;
            for (int pass = 0; pass < cap; pass++) {
                passes++;
                t0 = System.nanoTime();
                boolean again = c.reconcileChainedSpeeds(r, phase == 1);
                double recMs = ms(t0);
                // which bridges will re-march: sig mismatch before the pass
                List<String> re = new ArrayList<>();
                for (int p = 0; p + 1 < c.pieces.size(); p++) {
                    BridgeSection bs = CascadeProbe_F.bridgeOf(c, p);
                    if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
                    double[] old = bs.optSig;
                    if (old == null) { re.add("b" + p + ":first"); continue; }
                    if (old == BridgeSection.SIG_STALE) { re.add("b" + p + ":stale"); continue; }
                    double[] sig = bs.optSignature();
                    for (int i = 0; i < sig.length; i++) {
                        if (Math.abs(sig[i] - old[i]) > 1e-9) { re.add("b" + p + (bs.optLen ? "(knee)" : "")); break; }
                    }
                }
                t0 = System.nanoTime();
                boolean reopt = c.autoReoptimizeBridges(r);
                double reMs = ms(t0);
                sb.append(String.format(Locale.US, " | %s%d: reconcile %.0f ms%s, autoReopt %.0f ms%s",
                    phase == 0 ? "fast" : "exact", pass, recMs, again ? "(wrote)" : "",
                    reMs, reopt ? " re-marched " + re : ""));
                if (reopt) again = true;
                if (!again) break;
                t0 = System.nanoTime();
                r = TrackCompiler.compile(c, DT);
                sb.append(String.format(Locale.US, ", compile %.0f ms", ms(t0)));
            }
        }
        CascadeProbe_F.lastResult = r;
        System.out.printf(Locale.US, "  %s: rebuild %.0f ms, %d passes%n%s%n", label, ms(tAll), passes, sb);
    }

    static void standalone(Coaster c, int bp, String label) {
        BridgeSection bs = CascadeProbe_F.bridgeOf(c, bp);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t0 = System.nanoTime();
        TrackCompiler.compile(c, DT);
        double cMs = ms(t0);
        t0 = System.nanoTime();
        int n = bs.integrate(DT).samples().size();
        double iMs = ms(t0);
        t0 = System.nanoTime();
        TrackCompiler.captureTargetHead(bs, c.pieces.get(bp + 1), DT, c.fric());
        double hMs = ms(t0);
        t0 = System.nanoTime();
        double knee = c.optimalBridgeLength(bs, py);
        double kMs = ms(t0);
        // a re-run of the optimization at the current length (stamps the sig; no placement change expected)
        t0 = System.nanoTime();
        c.optimizeBridge(bs, bp + 1, bs.lastOptDist, py);
        double oMs = ms(t0);
        System.out.printf(Locale.US,
            "  %s: standalone — compile %.0f ms | b%d.integrate(dt) %.0f ms (%d samples, len %.1f) | captureTargetHead %.0f ms | optimalBridgeLength %.0f ms (knee %.2f) | optimizeBridge@%.1f %.0f ms%n",
            label, cMs, bp, iMs, n, bs.lastOptDist, hMs, kMs, knee, bs.lastOptDist, oMs);
    }

    /** One spinner click with the breakdown. */
    static void clickBd(Coaster c, int bp, int axis, double v, boolean abs, String label) {
        BridgeSection bs = CascadeProbe_F.bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        long tAll = System.nanoTime();
        if (axis == 1) t.setDispPitch(abs ? v : t.dispPitch() + v, true);
        else if (axis == 2) t.setDispRoll(abs ? v : t.dispRoll() + v, true);
        else t.setDispYaw(abs ? v : t.dispYaw() + v);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        long t0 = System.nanoTime();
        String note;
        if (bs.optLen) {
            double d = c.optimalBridgeLength(bs, py);
            double kMs = ms(t0);
            long t1 = System.nanoTime();
            c.optimizeBridge(bs, bp + 1, d, py);
            note = String.format(Locale.US, "knee %.0f ms (=%.2f) + optimizeBridge %.0f ms", kMs, d, ms(t1));
        } else {
            c.optimizeBridge(bs, bp + 1, bs.lastOptDist, py);
            note = String.format(Locale.US, "optimizeBridge@%.0f %.0f ms", bs.lastOptDist, ms(t0));
        }
        System.out.printf(Locale.US, "  [%s] b%d axis%d %s%.1f — explicit: %s%n", label, bp, axis, abs ? "=" : "+", v, note);
        rebuildTimed(c, "    click total");
        System.out.printf(Locale.US, "    => click %.0f ms%n", ms(tAll));
    }

    public static void main(String[] a) throws Exception {
        // warm-up
        Coaster w = CascadeProbe_F.load();
        for (int i = 0; i < 3; i++) { CascadeProbe_F.rebuild(w); CascadeProbe_F.click(w, 0, 1, 1, false, "warm"); }

        System.out.println("=== BD1: as saved (REAL_ONLY, 27 m / 22 m) ===");
        Coaster c = CascadeProbe_F.load();
        rebuildTimed(c, "open");
        standalone(c, 0, "as saved b0");
        standalone(c, 1, "as saved b1");
        clickBd(c, 0, 1, 1, false, "as saved");
        clickBd(c, 0, 1, 1, false, "as saved");
        clickBd(c, 1, 1, 1, false, "as saved");

        System.out.println("=== BD2: fresh b0 -> PY with Optimize length (knee ~60 m) ===");
        c = CascadeProbe_F.load();
        CascadeProbe_F.rebuild(c);
        {
            TrackPiece p = c.pieces.get(0);
            p.sections.remove(p.sections.size() - 1);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(CascadeProbe_F.spanIdxOf(c, 0, p.sections.size() - 1));
            BridgeSection br = BridgeSection.solve(sp.exit(), c.pieces.get(1).entryPose(), r.samples().get(sp.i1()).v());
            br.optMode = BridgeSection.OPT_NONE;
            p.sections.add(br);
            CascadeProbe_F.rebuild(c);
            br.optMode = BridgeSection.OPT_POS_YAW;
            br.optLen = true;
            long t0 = System.nanoTime();
            double d = c.optimalBridgeLength(br, true);
            System.out.printf(Locale.US, "  knee %.2f in %.0f ms%n", d, ms(t0));
            c.optimizeBridge(br, 1, d, true);
            rebuildTimed(c, "switch NONE->PY (optLen)");
        }
        standalone(c, 0, "fresh b0 optLen");
        clickBd(c, 0, 1, 1, false, "fresh optLen");
        clickBd(c, 0, 2, 1, false, "fresh optLen");
        // the same bridge with optLen OFF at the same length
        CascadeProbe_F.bridgeOf(c, 0).optLen = false;
        c.optimizeBridge(CascadeProbe_F.bridgeOf(c, 0), 1, CascadeProbe_F.bridgeOf(c, 0).lastOptDist, true);
        rebuildTimed(c, "optLen off, same length");
        clickBd(c, 0, 1, 1, false, "fresh len~56 optLen OFF");
        clickBd(c, 0, 2, 1, false, "fresh len~56 optLen OFF");

        System.out.println("=== BD3: as saved, b0 Length 120, pitch held 80 (REAL_ONLY) ===");
        c = CascadeProbe_F.load();
        CascadeProbe_F.rebuild(c);
        c.optimizeBridge(CascadeProbe_F.bridgeOf(c, 0), 1, 120, true);
        rebuildTimed(c, "len 120");
        standalone(c, 0, "len120 pitch as saved");
        clickBd(c, 0, 1, 80, true, "len120");
        standalone(c, 0, "len120 pitch 80");
        clickBd(c, 0, 1, 81, true, "len120");
        clickBd(c, 0, 1, -80, true, "len120");
        clickBd(c, 0, 1, 0, true, "len120");
        clickBd(c, 0, 1, 1, true, "len120");

        System.out.println("=== BD4: LOCKSTEP target, b0 pitch FREE, Length 120, roll held 0 ===");
        c = CascadeProbe_F.load();
        for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
        CascadeProbe_F.rebuild(c);
        CascadeProbe_F.bridgeOf(c, 0).lockPitch = false;
        c.optimizeBridge(CascadeProbe_F.bridgeOf(c, 0), 1, 120, true);
        rebuildTimed(c, "LOCKSTEP pitch-free len 120");
        standalone(c, 0, "LOCKSTEP pitch-free len120");
        clickBd(c, 0, 2, 0, true, "LOCK len120");
        clickBd(c, 0, 2, 1, true, "LOCK len120");
        clickBd(c, 0, 2, -90, true, "LOCK len120");
        // same at 27 m
        c.optimizeBridge(CascadeProbe_F.bridgeOf(c, 0), 1, 27, true);
        rebuildTimed(c, "LOCKSTEP pitch-free len 27");
        clickBd(c, 0, 2, 0, true, "LOCK len27");
        clickBd(c, 0, 2, 1, true, "LOCK len27");

        System.out.println("=== BD5: length ladder on b0 as saved (REAL_ONLY), pitch +1 per click ===");
        for (double L : new double[]{ 5, 10, 20, 27, 40, 60, 80, 120 }) {
            Coaster cl = CascadeProbe_F.load();
            CascadeProbe_F.rebuild(cl);
            cl.optimizeBridge(CascadeProbe_F.bridgeOf(cl, 0), 1, L, true);
            CascadeProbe_F.rebuild(cl);
            long t0 = System.nanoTime();
            CascadeProbe_F.click(cl, 0, 1, 1, false, String.format(Locale.US, "ladder L=%.0f", L));
            long t1 = System.nanoTime();
            int n = CascadeProbe_F.bridgeOf(cl, 0).integrate(DT).samples().size();
            System.out.printf(Locale.US, "      L=%.0f: b0.integrate(dt) %.0f ms (%d samples)%n", L, ms(t1), n);
        }
    }
}
