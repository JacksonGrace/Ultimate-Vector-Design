package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** CASCADE PROBE — FAMILY F (2026-09-25): replays the user's likely
 *  EDIT HISTORY on "UVD long bridge update time 9.25.26.uvd" through
 *  the same model ops the windows call, and measures a bridge-window
 *  spinner click after every state (the shell's rebuildTrack loop
 *  verbatim: 12 fast + 40 exact reconcile / re-optimize passes, one
 *  compile each). For any state with > 6 passes or > 500 ms the
 *  per-pass SIGNATURE SLOTS that differ from the stamp are printed,
 *  plus every reconcile vDesign write, so the mechanism is visible.
 *
 *  Families: (1) OPT_NONE → OPT_POS_YAW switches (stash / hand-moved /
 *  fresh-bridge knee) + PY ↔ POS; (2) lock toggles (REAL_ONLY and
 *  LOCKSTEP targets); (3) Design → Real (SIG_STALE) / Real → Design;
 *  (4) orientation-mode flips incl. the Affect-Subsequent carry and
 *  DESIGN_ONLY; (5) mirror / copyRun / reverse around the bridges;
 *  (6) split the target + re-bridge (three chained bridges); (7) far-
 *  off held values (pitch 0/±30/±60/±80, roll 0/±90/±150) at 27 / 5 /
 *  120 m on both bridges. */
public class CascadeProbe_F {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = { "pe.x", "pe.y", "pe.z", "te.x", "te.y",
        "te.z", "ue.x", "ue.y", "ue.z", "accA.x", "accA.y", "accA.z",
        "jerkA.x", "jerkA.y", "jerkA.z", "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len" };

    static TrackCompiler.Result lastResult;
    static int configs = 0;
    static double slowestNonCascadeMs = 0;
    static final List<String> findings = new ArrayList<>();

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        if (p < 0 || p >= c.pieces.size()) return null;
        List<Section> secs = c.pieces.get(p).sections;
        if (secs.isEmpty()) return null;
        return secs.get(secs.size() - 1) instanceof BridgeSection b ? b : null;
    }

    static int spanIdxOf(Coaster c, int p, int k) {
        int s = 0;
        for (int i = 0; i < p; i++) s += c.pieces.get(i).sections.size();
        return s + k;
    }

    /** Compiled arc of piece p's trailing bridge (GraphEditor.activeBridgeGap). */
    static double bridgeGap(Coaster c, int p) {
        BridgeSection bs = bridgeOf(c, p);
        if (bs == null || p + 1 >= c.pieces.size()) return 0;
        int si = spanIdxOf(c, p, c.pieces.get(p).sections.size() - 1);
        if (lastResult != null && si < lastResult.spans().size()
                && lastResult.spans().get(si).sLen() > 1e-6) {
            return lastResult.spans().get(si).sLen();
        }
        return c.pieces.get(p + 1).entryPose().pos().sub(bs.solvedEntry.pos()).length();
    }

    /** Signature slots differing from the stamp, per live optimize bridge. */
    static String sigDiff(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
            if (bs.optSig == null) { sb.append(" b").append(p).append(":null(first sight)"); continue; }
            if (bs.optSig == BridgeSection.SIG_STALE) { sb.append(" b").append(p).append(":STALE"); continue; }
            double[] sig = bs.optSignature();
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < sig.length; i++) {
                if (Math.abs(sig[i] - bs.optSig[i]) > 1e-9) {
                    d.append(String.format(Locale.US, " [%d]%s %.7g->%.7g",
                        i, SLOT[i], bs.optSig[i], sig[i]));
                }
            }
            if (d.length() > 0) sb.append(" b").append(p).append(":").append(d);
        }
        return sb.length() == 0 ? " (all sigs match)" : sb.toString();
    }

    static double[] vDesigns(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) for (Section s : p.sections) v.add(s.vDesign());
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static String vDesignDiff(Coaster c, double[] before) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (int p = 0; p < c.pieces.size(); p++) {
            for (int k = 0; k < c.pieces.get(p).sections.size(); k++, i++) {
                double now = c.pieces.get(p).sections.get(k).vDesign();
                if (i < before.length && Double.doubleToLongBits(now)
                        != Double.doubleToLongBits(before[i])) {
                    sb.append(String.format(Locale.US, " v[%d.%d] %.9g->%.9g", p, k, before[i], now));
                }
            }
        }
        return sb.toString();
    }

    record Stat(int fast, int exact, boolean quiesced, double ms, List<String> log) {
        int passes() { return fast + exact; }
    }

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest). */
    static Stat rebuild(Coaster c) {
        long t0 = System.nanoTime();
        List<String> log = new ArrayList<>();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            String sd = sigDiff(c);
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r);
            String vd = vDesignDiff(c, vb);
            boolean re = c.autoReoptimizeBridges(r);
            log.add("fast" + pass + ": sig" + sd + " | reconcile=" + again + vd + " | reopt=" + re);
            if (re) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            String sd = sigDiff(c);
            double[] vb = vDesigns(c);
            boolean again = c.reconcileChainedSpeeds(r, true);
            String vd = vDesignDiff(c, vb);
            boolean re = c.autoReoptimizeBridges(r);
            log.add("exact" + pass + ": sig" + sd + " | reconcile=" + again + vd + " | reopt=" + re);
            if (re) again = true;
            if (!again) { quiesced = true; break; }
            r = TrackCompiler.compile(c, DT);
        }
        lastResult = r;
        return new Stat(fast, exact, quiesced, (System.nanoTime() - t0) / 1e6, log);
    }

    static void report(String label, Stat s, double totalMs) {
        configs++;
        boolean cascade = s.passes() > 6 || totalMs > 500;
        System.out.printf(Locale.US, "  %-64s %6.0f ms | fast=%d exact=%d quiesced=%b%s%n",
            label, totalMs, s.fast, s.exact, s.quiesced, cascade ? "  <== CASCADE" : "");
        if (cascade) {
            findings.add(label + " — " + String.format(Locale.US, "%.0f ms, %d passes", totalMs, s.passes()));
            List<String> lg = s.log;
            int n = lg.size();
            for (int i = 0; i < n; i++) {
                if (i < 6 || i >= n - 3) System.out.println("      " + lg.get(i));
                else if (i == 6) System.out.println("      ... (" + (n - 9) + " more passes)");
            }
        } else {
            slowestNonCascadeMs = Math.max(slowestNonCascadeMs, totalMs);
        }
    }

    /** An op that then rebuilds (mode switches, lock toggles, ...). */
    static Stat op(Coaster c, String label, Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        Stat s = rebuild(c);
        report(label, s, (System.nanoTime() - t0) / 1e6);
        return s;
    }

    /** GraphEditor.applyBridgeGap: optimizeBridge at dist + rebuild. */
    static void applyGap(Coaster c, int bp, double dist) {
        BridgeSection bs = bridgeOf(c, bp);
        c.optimizeBridge(bs, bp + 1, dist, bs.optMode == BridgeSection.OPT_POS_YAW);
    }

    /** GraphEditor.applyOptimalLength minus the zero-distance prompt (= NO). */
    static String applyOptimal(Coaster c, int bp) {
        BridgeSection bs = bridgeOf(c, bp);
        double d = c.optimalBridgeLength(bs, bs.optMode == BridgeSection.OPT_POS_YAW);
        if (d <= 1 + 1e-9) {
            bs.optLen = false;
            return String.format(Locale.US, "knee=%.2f→zero-distance prompt (NO: optLen off)", d);
        }
        applyGap(c, bp, d);
        return String.format(Locale.US, "knee=%.2f", d);
    }

    /** One bridge-window spinner click: bridgeTargetWrite = placementEdit
     *  (with the D221 carry when carriesDownstream) + per-mode re-place +
     *  rebuild. axis 0 yaw / 1 pitch / 2 roll; abs = an absolute typed
     *  value on the displayed scale, else a delta. */
    static Stat click(Coaster c, int bp, int axis, double v, boolean abs, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        boolean dOnly = t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
        long t0 = System.nanoTime();
        TrackPose before = t.entryPose();
        if (axis == 1) {
            double base = dOnly ? t.dispDesignPitch() : t.dispPitch();
            t.setDispPitch(abs ? v : base + v, true);
        } else if (axis == 2) {
            double base = dOnly ? t.dispDesignRoll() : t.dispRoll();
            t.setDispRoll(abs ? v : base + v, true);
        } else {
            t.setDispYaw(abs ? v : t.dispYaw() + v);
        }
        TrackPose after = t.entryPose();
        String carry = "";
        if (Coaster.carriesDownstream(t) && lastResult != null) {
            c.carryAfterEdit(bp + 1, before, after, lastResult.spans());
            carry = " +carry";
        }
        String note = "";
        long t1 = System.nanoTime();
        if (bs.optMode != BridgeSection.OPT_NONE) {
            if (bs.optLen) note = " " + applyOptimal(c, bp);
            else applyGap(c, bp, bs.lastOptDist > 0 ? bs.lastOptDist : Math.max(1, bridgeGap(c, bp)));
        }
        double optMs = (System.nanoTime() - t1) / 1e6;
        Stat s = rebuild(c);
        double total = (System.nanoTime() - t0) / 1e6;
        String ax = axis == 1 ? "pitch" : axis == 2 ? "roll" : "yaw";
        report(String.format(Locale.US, "[%s] b%d %s %s%.1f%s%s (opt %.0f ms)", label, bp, ax,
            abs ? "=" : "+", v, carry, note, optMs), s, total);
        return s;
    }

    static String pieceLine(Coaster c, int p) {
        TrackPiece tp = c.pieces.get(p);
        BridgeSection b = bridgeOf(c, p);
        return String.format(Locale.US,
            "piece %d mode=%s affect=%b follow=%b design=(%.2f,%.2f) true=(%.2f,%.2f) disp=(%.2f,%.2f,%.2f)%s",
            p, tp.orientMode, tp.affectSubsequentSeams, tp.followPrev, tp.designPitchDeg,
            tp.designRollDeg, tp.pitchDeg, tp.rollDeg, tp.dispYaw(), tp.dispPitch(), tp.dispRoll(),
            b == null ? "" : String.format(Locale.US,
                " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks=%b/%b/%b",
                b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll));
    }

    static void dump(Coaster c) {
        for (int p = 0; p < c.pieces.size(); p++) System.out.println("    " + pieceLine(c, p));
    }

    // ---------------------------------------------------------------

    static void baseline(String tag) throws IOException {
        System.out.println("=== F0 baseline (" + tag + ") ===");
        Coaster c = load();
        dump(c);
        op(c, "[F0] open rebuild", () -> {});
        op(c, "[F0] idle rebuild", () -> {});
        click(c, 0, 1, 1, false, "F0 as saved");
        click(c, 0, 1, 1, false, "F0 as saved");
        click(c, 0, 2, 1, false, "F0 as saved");
        click(c, 1, 1, 1, false, "F0 as saved");
        click(c, 1, 2, 1, false, "F0 as saved");
        click(c, 1, 0, 1, false, "F0 as saved");
    }

    /** (1) GraphEditor.setBridgeOptMode replays. */
    static void modeSwitches() throws IOException {
        System.out.println("=== F1 mode switches (setBridgeOptMode) ===");
        for (int bp = 0; bp < 2; bp++) {
            f1a(bp);
            f1a2(bp);
            f1b(bp);
            f1c(bp);
            f1d(bp);
        }
    }

    static void f1a(final int b) throws IOException {
            // 1a: PY -> NONE (stash) -> PY untouched (restore stash)
            final Coaster c = load();
            rebuild(c);
            op(c, "[F1a] b" + b + " PY->NONE (stash)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                TrackPiece t = c.pieces.get(b + 1);
                bs.noneStash = new double[]{ bs.optLen ? 1 : 0, t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg };
                bs.optMode = BridgeSection.OPT_NONE;
            });
            click(c, b, 1, 1, false, "F1a NONE");
            click(c, b, 2, 1, false, "F1a NONE");
            op(c, "[F1a] b" + b + " NONE->PY (stash, moved by clicks)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                double[] st = bs.noneStash;
                bs.noneStash = null;
                TrackPiece t = c.pieces.get(b + 1);
                boolean untouched = Math.abs(t.x - st[1]) < 1e-9 && Math.abs(t.y - st[2]) < 1e-9
                    && Math.abs(t.z - st[3]) < 1e-9 && Math.abs(t.yawDeg - st[4]) < 1e-9
                    && Math.abs(t.pitchDeg - st[5]) < 1e-9 && Math.abs(t.rollDeg - st[6]) < 1e-9;
                bs.optMode = BridgeSection.OPT_POS_YAW;
                if (untouched) {
                    bs.optLen = st[0] == 1;
                    if (bs.optLen) System.out.println("      " + applyOptimal(c, b));
                    else applyGap(c, b, bs.lastOptDist > 0 ? bs.lastOptDist : Math.max(1, bridgeGap(c, b)));
                } else {
                    bs.optLen = false;
                    applyGap(c, b, Math.max(1, bridgeGap(c, b)));
                    System.out.printf(Locale.US, "      hand-moved branch: len := compiled arc %.2f%n", bs.lastOptDist);
                }
            });
            click(c, b, 1, 1, false, "F1a PY again");
            click(c, b, 2, 1, false, "F1a PY again");
    }

    static void f1a2(final int b) throws IOException {
            // 1a': the pure untouched round trip
            final Coaster c = load();
            rebuild(c);
            op(c, "[F1a'] b" + b + " PY->NONE->PY untouched", () -> {
                BridgeSection bs = bridgeOf(c, b);
                TrackPiece t = c.pieces.get(b + 1);
                bs.noneStash = new double[]{ bs.optLen ? 1 : 0, t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg };
                bs.optMode = BridgeSection.OPT_NONE;
                rebuild(c);
                double[] st = bs.noneStash;
                bs.noneStash = null;
                bs.optMode = BridgeSection.OPT_POS_YAW;
                bs.optLen = st[0] == 1;
                applyGap(c, b, bs.lastOptDist > 0 ? bs.lastOptDist : Math.max(1, bridgeGap(c, b)));
            });
            click(c, b, 1, 1, false, "F1a' PY untouched round trip");
    }

    static void f1b(final int b) throws IOException {
            // 1b: PY -> NONE -> hand-move target x/y -> NONE -> PY (hand-moved branch)
            final Coaster c = load();
            rebuild(c);
            op(c, "[F1b] b" + b + " PY->NONE, target moved +2x +1y", () -> {
                BridgeSection bs = bridgeOf(c, b);
                TrackPiece t = c.pieces.get(b + 1);
                bs.noneStash = new double[]{ bs.optLen ? 1 : 0, t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg };
                bs.optMode = BridgeSection.OPT_NONE;
                rebuild(c);
                t.x += 2; t.y += 1;
            });
            op(c, "[F1b] b" + b + " NONE->PY hand-moved (len := arc)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.noneStash = null;
                bs.optMode = BridgeSection.OPT_POS_YAW;
                bs.optLen = false;
                double g = Math.max(1, bridgeGap(c, b));
                System.out.printf(Locale.US, "      compiled arc = %.2f%n", g);
                applyGap(c, b, g);
            });
            click(c, b, 1, 1, false, "F1b PY after hand-move");
            click(c, b, 2, 1, false, "F1b PY after hand-move");
    }

    static void f1c(final int b) throws IOException {
            // 1c: FRESH bridge (delete + re-create via BridgeSection.solve, OPT_NONE) -> PY (knee + optLen on)
            final Coaster c = load();
            rebuild(c);
            op(c, "[F1c] b" + b + " deleted + fresh solve (NONE)", () -> {
                TrackPiece p = c.pieces.get(b);
                p.sections.remove(p.sections.size() - 1);
                TrackCompiler.Result r = TrackCompiler.compile(c, DT);
                int si = spanIdxOf(c, b, p.sections.size() - 1);
                TrackCompiler.Span sp = r.spans().get(si);
                double vEnd = r.samples().get(sp.i1()).v();
                BridgeSection br = BridgeSection.solve(sp.exit(), c.pieces.get(b + 1).entryPose(), vEnd);
                br.optMode = BridgeSection.OPT_NONE;
                p.sections.add(br);
            });
            op(c, "[F1c] b" + b + " NONE->PY fresh (optLen on, knee)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optMode = BridgeSection.OPT_POS_YAW;
                bs.optLen = true;
                System.out.println("      " + applyOptimal(c, b));
            });
            dump(c);
            click(c, b, 1, 1, false, "F1c fresh PY optLen");
            click(c, b, 1, 1, false, "F1c fresh PY optLen");
            click(c, b, 2, 1, false, "F1c fresh PY optLen");
            // then Optimize length OFF (the user's "adjust length" flow)
            op(c, "[F1c] b" + b + " optLen off + Length 27", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optLen = false;
                applyGap(c, b, 27);
            });
            click(c, b, 1, 1, false, "F1c fresh PY len27");
            op(c, "[F1c] b" + b + " optLen ON again (knee)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optLen = true;
                System.out.println("      " + applyOptimal(c, b));
            });
            click(c, b, 1, 1, false, "F1c fresh PY optLen re-on");
    }

    static void f1d(final int b) throws IOException {
            // 1d: PY <-> POS
            final Coaster c = load();
            rebuild(c);
            op(c, "[F1d] b" + b + " PY->POS (keep length)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optMode = BridgeSection.OPT_POS;
                applyGap(c, b, bs.lastOptDist > 0 ? bs.lastOptDist : Math.max(1, bridgeGap(c, b)));
            });
            click(c, b, 1, 1, false, "F1d POS");
            click(c, b, 0, 1, false, "F1d POS");
            op(c, "[F1d] b" + b + " POS->PY (keep length)", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optMode = BridgeSection.OPT_POS_YAW;
                applyGap(c, b, bs.lastOptDist > 0 ? bs.lastOptDist : Math.max(1, bridgeGap(c, b)));
            });
            click(c, b, 1, 1, false, "F1d PY back");
    }

    /** (2) setBridgeLock replays: toggle → applyBridgeGap(lastOptDist). */
    static void locks() throws IOException {
        System.out.println("=== F2 lock toggles (setBridgeLock) ===");
        for (TrackPiece.OrientMode mode : new TrackPiece.OrientMode[]{
                TrackPiece.OrientMode.REAL_ONLY, TrackPiece.OrientMode.LOCKSTEP }) {
            for (int bp = 0; bp < 2; bp++) {
                final int b = bp;
                Coaster c = load();
                for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = mode;
                rebuild(c);
                String m = mode == TrackPiece.OrientMode.REAL_ONLY ? "REAL" : "LOCK";
                op(c, "[F2 " + m + "] b" + b + " unlock pitch", () -> {
                    bridgeOf(c, b).lockPitch = false;
                    applyGap(c, b, bridgeOf(c, b).lastOptDist);
                });
                click(c, b, 2, 1, false, "F2 " + m + " pitch-free");
                click(c, b, 2, 1, false, "F2 " + m + " pitch-free");
                op(c, "[F2 " + m + "] b" + b + " unlock roll too", () -> {
                    bridgeOf(c, b).lockRoll = false;
                    applyGap(c, b, bridgeOf(c, b).lastOptDist);
                });
                op(c, "[F2 " + m + "] b" + b + " idle rebuild (all free)", () -> {});
                op(c, "[F2 " + m + "] b" + b + " lock yaw", () -> {
                    bridgeOf(c, b).lockYaw = true;
                    applyGap(c, b, bridgeOf(c, b).lastOptDist);
                });
                click(c, b, 0, 1, false, "F2 " + m + " yaw-held pitch/roll-free");
                click(c, b, 0, 1, false, "F2 " + m + " yaw-held pitch/roll-free");
                op(c, "[F2 " + m + "] b" + b + " relock pitch+roll, unlock yaw", () -> {
                    bridgeOf(c, b).lockPitch = true;
                    bridgeOf(c, b).lockRoll = true;
                    bridgeOf(c, b).lockYaw = false;
                    applyGap(c, b, bridgeOf(c, b).lastOptDist);
                });
                click(c, b, 1, 1, false, "F2 " + m + " relocked");
                dump(c);
            }
        }
    }

    /** (3) Design → Real (SIG_STALE) and Real → Design on the target. */
    static void designReal() throws IOException {
        System.out.println("=== F3 Design→Real / Real→Design on the target ===");
        for (int bp = 0; bp < 2; bp++) {
            f3a(bp);
            f3b(bp);
        }
    }

    static void f3a(final int b) throws IOException {
            final Coaster c = load();
            rebuild(c);
            op(c, "[F3] b" + b + " target Design→Real (+SIG_STALE)", () -> {
                TrackPiece t = c.pieces.get(b + 1);
                t.designPitchDeg = t.pitchDeg;
                t.designRollDeg = t.rollDeg;
                bridgeOf(c, b).optSig = BridgeSection.SIG_STALE;
            });
            dump(c);
            click(c, b, 1, 1, false, "F3 after D→R");
            click(c, b, 2, 1, false, "F3 after D→R");
            click(c, b, 1, -1, false, "F3 after D→R");
    }

    static void f3b(final int b) throws IOException {
            // Real → Design is grayed under PY: switch to POS first
            final Coaster c = load();
            rebuild(c);
            op(c, "[F3] b" + b + " PY->POS then target Real→Design", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optMode = BridgeSection.OPT_POS;
                applyGap(c, b, bs.lastOptDist);
                rebuild(c);
                TrackPiece t = c.pieces.get(b + 1);
                t.pitchDeg = t.designPitchDeg;
                t.rollDeg = t.designRollDeg;
                applyGap(c, b, bs.lastOptDist);
            });
            dump(c);
            click(c, b, 1, 1, false, "F3 POS after R→D");
            op(c, "[F3] b" + b + " POS->PY after R→D", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optMode = BridgeSection.OPT_POS_YAW;
                applyGap(c, b, bs.lastOptDist);
            });
            click(c, b, 1, 1, false, "F3 PY after R→D");
            click(c, b, 2, 1, false, "F3 PY after R→D");
    }

    /** (4) orientation-mode flips + Affect Subsequent Strict Seams. */
    static void modeRow() throws IOException {
        System.out.println("=== F4 orientation-mode row flips ===");
        for (int bp = 0; bp < 2; bp++) {
            final int b = bp;
            Coaster c = load();
            rebuild(c);
            op(c, "[F4] b" + b + " target -> LOCKSTEP (flip only)", () ->
                c.pieces.get(b + 1).orientMode = TrackPiece.OrientMode.LOCKSTEP);
            click(c, b, 1, 1, false, "F4 LOCKSTEP");
            click(c, b, 2, 1, false, "F4 LOCKSTEP");
            op(c, "[F4] b" + b + " target -> REAL_ONLY + affect ON", () -> {
                c.pieces.get(b + 1).orientMode = TrackPiece.OrientMode.REAL_ONLY;
                c.pieces.get(b + 1).affectSubsequentSeams = true;
            });
            click(c, b, 1, 1, false, "F4 REAL+affect");
            click(c, b, 2, 1, false, "F4 REAL+affect");
            click(c, b, 1, -1, false, "F4 REAL+affect");
            dump(c);
            // DESIGN_ONLY: the UI excludes PY; test POS (legal) and the PY combo (file-held)
            op(c, "[F4] b" + b + " target -> DESIGN_ONLY (bridge still PY, file-held combo)", () -> {
                c.pieces.get(b + 1).affectSubsequentSeams = false;
                c.pieces.get(b + 1).orientMode = TrackPiece.OrientMode.DESIGN_ONLY;
            });
            click(c, b, 1, 1, false, "F4 DESIGN_ONLY+PY");
            click(c, b, 2, 1, false, "F4 DESIGN_ONLY+PY");
            op(c, "[F4] b" + b + " PY->POS with DESIGN_ONLY target", () -> {
                BridgeSection bs = bridgeOf(c, b);
                bs.optMode = BridgeSection.OPT_POS;
                applyGap(c, b, bs.lastOptDist);
            });
            click(c, b, 1, 1, false, "F4 DESIGN_ONLY+POS");
            click(c, b, 2, 1, false, "F4 DESIGN_ONLY+POS");
            dump(c);
        }
    }

    /** (5) mirror / copyRun / reversePiece around the bridges. */
    static void pieceOps() throws IOException {
        System.out.println("=== F5 mirror / copy / reverse ===");
        f5mirror();
        f5copy();
        f5reverse();
    }

    static void f5mirror() throws IOException {
        final Coaster c = load();
        rebuild(c);
        op(c, "[F5] mirrorPiece(2)", () -> System.out.println("      mirrored=" + c.mirrorPiece(2)));
        dump(c);
        click(c, 1, 1, 1, false, "F5 after mirror(2)");
        click(c, 1, 2, 1, false, "F5 after mirror(2)");
        click(c, 0, 1, 1, false, "F5 after mirror(2)");
        System.out.println("      mirror refused on bridge pieces: 0->" + c.mirrorPiece(0) + " 1->" + c.mirrorPiece(1));
    }

    static void f5copy() throws IOException {
        final Coaster c = load();
        rebuild(c);
        op(c, "[F5] copyRun(0,2,+5z)", () -> System.out.println("      head=" + c.copyRun(0, 2, Coaster.COPY_DZ)));
        dump(c);
        click(c, 0, 1, 1, false, "F5 after copy (orig)");
        click(c, 1, 1, 1, false, "F5 after copy (orig)");
        click(c, 3, 1, 1, false, "F5 after copy (copy)");
        click(c, 4, 1, 1, false, "F5 after copy (copy)");
        click(c, 4, 2, 1, false, "F5 after copy (copy)");
    }

    static void f5reverse() throws IOException {
        final Coaster c = load();
        rebuild(c);
        op(c, "[F5] reversePiece(2) recorded speed", () ->
            System.out.println("      reversed=" + c.reversePiece(2, DT, Coaster.NODE_SPACING_DEFAULT, 0.01, Double.NaN)));
        dump(c);
        click(c, 1, 1, 1, false, "F5 after reverse(2)");
        click(c, 1, 2, 1, false, "F5 after reverse(2)");
        click(c, 1, 0, 1, false, "F5 after reverse(2)");
        click(c, 0, 1, 1, false, "F5 after reverse(2)");
    }

    /** (6) split the target mid-section, split the piece, re-bridge. */
    static void splitRebridge() throws IOException {
        System.out.println("=== F6 split target + re-bridge (3 chained bridges) ===");
        Coaster c = load();
        rebuild(c);
        op(c, "[F6] splitSectionAt(piece1, sec0, 0.5 s)", () ->
            System.out.println("      split=" + c.splitSectionAt(1, 0, 0.5, DT)));
        op(c, "[F6] splitPiece(1, 1) at the compiled boundary", () -> {
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            int si = spanIdxOf(c, 1, 0);
            System.out.println("      newPiece=" + c.splitPiece(1, 1, r.spans().get(si)));
        });
        dump(c);
        click(c, 0, 1, 1, false, "F6 after split, b0");
        op(c, "[F6] addBridgeTo: piece1 -> piece2 (fresh NONE)", () -> {
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackPiece p = c.pieces.get(1);
            int si = spanIdxOf(c, 1, p.sections.size() - 1);
            TrackCompiler.Span sp = r.spans().get(si);
            double vEnd = r.samples().get(sp.i1()).v();
            BridgeSection br = BridgeSection.solve(sp.exit(), c.pieces.get(2).entryPose(), vEnd);
            br.optMode = BridgeSection.OPT_NONE;
            p.sections.add(br);
        });
        op(c, "[F6] new bridge NONE->PY (fresh: optLen + knee)", () -> {
            BridgeSection bs = bridgeOf(c, 1);
            bs.optMode = BridgeSection.OPT_POS_YAW;
            bs.optLen = true;
            System.out.println("      " + applyOptimal(c, 1));
        });
        dump(c);
        click(c, 0, 1, 1, false, "F6 3 bridges b0");
        click(c, 1, 1, 1, false, "F6 3 bridges b1(new)");
        click(c, 2, 1, 1, false, "F6 3 bridges b2");
        click(c, 2, 2, 1, false, "F6 3 bridges b2");
        op(c, "[F6] new bridge optLen off, len 10", () -> {
            bridgeOf(c, 1).optLen = false;
            applyGap(c, 1, 10);
        });
        click(c, 1, 1, 1, false, "F6 3 bridges b1(new) len10");
        click(c, 0, 1, 1, false, "F6 3 bridges b0");
        // the new bridge with LOCKSTEP target + unlocked pitch (the settle path in a chain)
        op(c, "[F6] piece2 LOCKSTEP + new bridge pitch unlocked", () -> {
            c.pieces.get(2).orientMode = TrackPiece.OrientMode.LOCKSTEP;
            bridgeOf(c, 1).lockPitch = false;
            applyGap(c, 1, bridgeOf(c, 1).lastOptDist);
        });
        click(c, 1, 2, 1, false, "F6 b1(new) LOCKSTEP pitch-free");
        click(c, 0, 1, 1, false, "F6 b0 with LOCKSTEP chain");
        dump(c);
    }

    /** (7) far-off held values at 27 / 5 / 120 m. */
    static void extremes() throws IOException {
        System.out.println("=== F7 far-off held values (as saved: REAL_ONLY, pitch+roll held) ===");
        double[] lens = { 27, 5, 120 };
        double[] pitches = { 0, 30, -30, 60, -60, 80, -80 };
        double[] rolls = { 0, 90, -90, 150, -150 };
        for (int bp = 0; bp < 2; bp++) {
            for (double len : lens) {
                Coaster c = load();
                rebuild(c);
                final int b = bp;
                final double L = len;
                op(c, String.format(Locale.US, "[F7] b%d Length := %.0f", b, L), () -> applyGap(c, b, L));
                for (double pv : pitches) click(c, b, 1, pv, true, String.format(Locale.US, "F7 len%.0f", L));
                click(c, b, 1, c.pieces.get(b + 1).dispPitch() + 1, true, String.format(Locale.US, "F7 len%.0f +1 from last", L));
                for (double rv : rolls) click(c, b, 2, rv, true, String.format(Locale.US, "F7 len%.0f", L));
                click(c, b, 2, c.pieces.get(b + 1).dispRoll() + 1, true, String.format(Locale.US, "F7 len%.0f +1 from last", L));
                dump(c);
            }
        }
        // LOCKSTEP variant with pitch FREE (settle loop) and a far roll held
        System.out.println("--- F7' LOCKSTEP target, pitch free, roll held far off ---");
        for (int bp = 0; bp < 2; bp++) {
            final int b = bp;
            for (double len : new double[]{ 27, 120 }) {
                Coaster c = load();
                for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
                rebuild(c);
                final double L = len;
                op(c, String.format(Locale.US, "[F7'] b%d LOCKSTEP pitch-free Length := %.0f", b, L), () -> {
                    bridgeOf(c, b).lockPitch = false;
                    applyGap(c, b, L);
                });
                for (double rv : new double[]{ 0, 90, -90, 150, -150 }) {
                    click(c, b, 2, rv, true, String.format(Locale.US, "F7' LOCK len%.0f", L));
                }
                dump(c);
            }
        }
    }

    public static void main(String[] a) throws Exception {
        String only = a.length > 0 ? a[0] : "all";
        baseline("cold JIT");
        configs = 0; slowestNonCascadeMs = 0; findings.clear();
        baseline("warm");
        if (only.equals("all") || only.contains("1")) modeSwitches();
        if (only.equals("all") || only.contains("2")) locks();
        if (only.equals("all") || only.contains("3")) designReal();
        if (only.equals("all") || only.contains("4")) modeRow();
        if (only.equals("all") || only.contains("5")) pieceOps();
        if (only.equals("all") || only.contains("6")) splitRebridge();
        if (only.equals("all") || only.contains("7")) extremes();
        System.out.println("=== SUMMARY ===");
        System.out.printf(Locale.US, "configs measured: %d | slowest non-cascade: %.0f ms | cascades: %d%n",
            configs, slowestNonCascadeMs, findings.size());
        for (String f : findings) System.out.println("  CASCADE: " + f);
    }
}
