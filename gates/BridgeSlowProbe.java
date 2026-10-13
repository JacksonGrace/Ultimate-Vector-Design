package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** BRIDGE UPDATE TIME PROBE (2026-09-25): times the shell's per-click
 *  path for a bridge-window orientation spinner on the user's file
 *  "UVD long bridge update time 9.25.26.uvd" — write → optimizeBridge →
 *  the rebuildTrack loop (12 fast + 40 exact reconcile/re-optimize
 *  passes, one compile each). Prints per-stage timings and pass counts
 *  so a 5 s click can be attributed. Runs the file as saved, then with
 *  the targets flipped to LOCKSTEP, then with Optimize length on. */
public class BridgeSlowProbe {
    static final double DT = Coaster.INTEGRATE_DT;
    static long tCompile, tRecon, tReopt, tOpt;
    static int nCompile, nRecon, nReopt, nReoptTrue;

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

    /** FvdDesigner.rebuildTrack's loop, verbatim (at rest: gestureHot = false). */
    static String rebuild(Coaster c) { return rebuild(c, false); }

    /** hot = a live gesture event (gestureHot): the fast loop only. */
    static String rebuild(Coaster c, boolean hot) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = hot;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0; nRecon++;
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r, hot && LIVE_KNEE);
            tReopt += System.nanoTime() - t0; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40 && !hot; pass++) {
            exact++;
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r, true);
            tRecon += System.nanoTime() - t0; nRecon++;
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tReopt += System.nanoTime() - t0; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (!again) { quiesced = true; break; }
            r = compile(c);
        }
        return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced
            + " samples=" + r.samples().size();
    }

    static void reset() {
        tCompile = tRecon = tReopt = tOpt = 0;
        nCompile = nRecon = nReopt = nReoptTrue = 0;
    }

    static String stats() {
        return String.format(Locale.US,
            "compile %d× %.0f ms | reconcile %d× %.0f ms | autoReopt %d× (%d true) %.0f ms | optimizeBridge %.0f ms",
            nCompile, tCompile / 1e6, nRecon, tRecon / 1e6, nReopt, nReoptTrue,
            tReopt / 1e6, tOpt / 1e6);
    }

    /** One bridge-window spinner click on piece bp's trailing bridge:
     *  GraphEditor.setBridgeTargetOrient → bridgeTargetWrite →
     *  placementEdit (no carry: REAL_ONLY + affect off / LOCKSTEP) →
     *  applyBridgeGap → optimizeBridge + rebuildTrack. */
    static void click(Coaster c, int bp, int axis, double delta, String label) {
        BridgeSection bs = (BridgeSection) c.pieces.get(bp).sections
            .get(c.pieces.get(bp).sections.size() - 1);
        TrackPiece t = c.pieces.get(bp + 1);
        reset();
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
        String loop = rebuild(c);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "  [%s] bridge %d axis %d: %.0f ms total — %s — %s%n",
            label, bp, axis, ms, loop, stats());
    }

    /** The 2026-09-25 fix check: a click through the OLD route (explicit
     *  optimizeBridge on the previous compile's capture) and through the
     *  NEW route (Coaster.captureBridgeTarget first) must land on the
     *  SAME fixed point bitwise, with fewer passes. */
    static String placement(Coaster c) {
        StringBuilder sb = new StringBuilder();
        for (TrackPiece p : c.pieces) {
            sb.append(Double.doubleToLongBits(p.x)).append(',')
              .append(Double.doubleToLongBits(p.y)).append(',')
              .append(Double.doubleToLongBits(p.z)).append(',')
              .append(Double.doubleToLongBits(p.yawDeg)).append(',')
              .append(Double.doubleToLongBits(p.pitchDeg)).append(',')
              .append(Double.doubleToLongBits(p.rollDeg)).append(';');
            for (Section s : p.sections) {
                sb.append(Double.doubleToLongBits(s.vDesign())).append(',');
                if (s instanceof BridgeSection b) {
                    sb.append(Double.doubleToLongBits(b.lastOptDist)).append(',')
                      .append(Double.doubleToLongBits(b.lenA)).append(',');
                }
            }
            sb.append('|');
        }
        return sb.toString();
    }

    static double[] placementNums(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece p : c.pieces) {
            v.add(p.x); v.add(p.y); v.add(p.z);
            v.add(p.yawDeg); v.add(p.pitchDeg); v.add(p.rollDeg);
            for (Section s : p.sections) {
                v.add(s.vDesign());
                if (s instanceof BridgeSection b) { v.add(b.lastOptDist); v.add(b.lenA); }
            }
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static void fixedPointCheck(String path, boolean optLen) throws Exception {
        int[][] cases = { {0, 1}, {0, 2}, {1, 1}, {1, 2}, {0, 0} };
        for (int[] cs : cases) {
            String[] fp = new String[2];
            String[] loops = new String[2];
            double[][] nums = new double[2][];
            Vec3[] lastPos = new Vec3[2];
            for (int route = 0; route < 2; route++) {
                Coaster c = load(path);
                if (optLen) {
                    for (int p = 0; p < 2; p++) {
                        BridgeSection bs = (BridgeSection) c.pieces.get(p).sections
                            .get(c.pieces.get(p).sections.size() - 1);
                        bs.optLen = true;
                    }
                }
                rebuild(c); rebuild(c);
                int bp = cs[0], axis = cs[1];
                BridgeSection bs = (BridgeSection) c.pieces.get(bp).sections
                    .get(c.pieces.get(bp).sections.size() - 1);
                TrackPiece t = c.pieces.get(bp + 1);
                if (axis == 1) t.setDispPitch(t.dispPitch() + 1, true);
                else if (axis == 2) t.setDispRoll(t.dispRoll() + 1, true);
                else t.setDispYaw(t.dispYaw() + 1);
                if (route == 1) c.captureBridgeTarget(bs, bp + 1);   // the fix
                boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
                double d = bs.optLen ? c.optimalBridgeLength(bs, py)
                    : (bs.lastOptDist > 0 ? bs.lastOptDist : 1);
                c.optimizeBridge(bs, bp + 1, d, py);
                reset();
                loops[route] = rebuild(c) + " (" + stats() + ")";
                fp[route] = placement(c);
                nums[route] = placementNums(c);
                TrackCompiler.Result r = TrackCompiler.compile(c, DT);
                lastPos[route] = r.samples().get(r.samples().size() - 1).pos();
            }
            double maxD = 0;
            int maxI = -1;
            for (int i = 0; i < nums[0].length; i++) {
                double d = Math.abs(nums[0][i] - nums[1][i]);
                if (d > maxD) { maxD = d; maxI = i; }
            }
            double endD = lastPos[0].sub(lastPos[1]).length();
            System.out.printf(Locale.US, "  fixed point bridge %d axis %d optLen=%b: %s (max |delta| %.3e at field %d, track end %.3e m)%n    old: %s%n    new: %s%n",
                cs[0], cs[1], optLen, fp[0].equals(fp[1]) ? "BITWISE EQUAL" : "DIFFERENT",
                maxD, maxI, endD, loops[0], loops[1]);
        }
    }

    /** A graph-node DRAG (the user's 2026-09-25 second file): N live
     *  events on one lane node — each = the fast loop only (gestureHot)
     *  — then the release settle; on the section between the bridges
     *  and on the section before the first bridge. */
    static boolean LIVE_KNEE = true;   // false = the pre-D253 per-event knee
    static double[][] settledOld = new double[2][], settledNew = new double[2][];

    static void dragCheck(String path) throws Exception {
        Coaster c = load(path);
        rebuild(c); rebuild(c);
        int[][] targets = { {1, 0, 0, 1}, {0, 0, 0, 5} };
        for (int[] tg : targets) {
            LaneSection ls = (LaneSection) c.pieces.get(tg[0]).sections.get(tg[1]);
            BezierBaker.Node nd = ls.lanes.get(tg[2]).get(tg[3]);
            double orig = nd.val;
            System.out.printf(Locale.US, "  drag piece %d sec %d lane %d node %d (val %.3f)%n",
                tg[0], tg[1], tg[2], tg[3], orig);
            for (int ev = 0; ev < 6; ev++) {
                nd.val = orig + 0.02 * (ev + 1);
                reset();
                long t0 = System.nanoTime();
                String loop = rebuild(c, true);
                System.out.printf(Locale.US, "    event %d: %.0f ms — %s — %s%n", ev,
                    (System.nanoTime() - t0) / 1e6, loop, stats());
            }
            reset();
            long t0 = System.nanoTime();
            String loop = rebuild(c);
            System.out.printf(Locale.US, "    release settle: %.0f ms — %s — %s%n",
                (System.nanoTime() - t0) / 1e6, loop, stats());
            System.out.printf(Locale.US, "    settled placement: %s%n", placement(c).hashCode());
            int ti = tg[0] == 1 ? 0 : 1;
            (LIVE_KNEE ? settledNew : settledOld)[ti] = placementNums(c);
            nd.val = orig;
            rebuild(c); rebuild(c);
        }
    }

    static void dragCompare(String path) throws Exception {
        LIVE_KNEE = false; dragCheck(path);
        LIVE_KNEE = true; dragCheck(path);
        for (int ti = 0; ti < 2; ti++) {
            double maxD = 0; int maxI = -1;
            for (int i = 0; i < settledOld[ti].length; i++) {
                double d = Math.abs(settledOld[ti][i] - settledNew[ti][i]);
                if (d > maxD) { maxD = d; maxI = i; }
            }
            System.out.printf(Locale.US, "  settled after release, target %d: old vs new max |delta| %.3e at field %d%n", ti, maxD, maxI);
        }
    }

    public static void main(String[] a) throws Exception {
        String path = a.length > 0 ? a[0]
            : "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
        if (a.length > 1 && a[1].equals("drag")) {
            dragCompare(path);
            return;
        }
        if (a.length > 1 && a[1].equals("fixed")) {
            fixedPointCheck(path, false);
            fixedPointCheck(path, true);
            return;
        }
        for (int rep = 0; rep < 2; rep++) {   // rep 0 = cold JIT, rep 1 = warm
            System.out.println("=== rep " + rep + (rep == 0 ? " (cold)" : " (warm)") + " ===");
            Coaster c = load(path);
            for (int p = 0; p < c.pieces.size(); p++) {
                TrackPiece tp = c.pieces.get(p);
                Section last = tp.sections.get(tp.sections.size() - 1);
                System.out.printf(Locale.US, "  piece %d mode=%s affect=%b design=(%.2f,%.2f) true=(%.2f,%.2f)%s%n",
                    p, tp.orientMode, tp.affectSubsequentSeams, tp.designPitchDeg, tp.designRollDeg,
                    tp.pitchDeg, tp.rollDeg,
                    last instanceof BridgeSection b ? String.format(Locale.US,
                        " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks=%b/%b/%b",
                        b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll) : "");
            }
            reset();
            long t0 = System.nanoTime();
            String first = rebuild(c);
            System.out.printf(Locale.US, "  open: %.0f ms — %s — %s%n", (System.nanoTime() - t0) / 1e6, first, stats());
            reset();
            t0 = System.nanoTime();
            String second = rebuild(c);
            System.out.printf(Locale.US, "  idle rebuild: %.0f ms — %s — %s%n", (System.nanoTime() - t0) / 1e6, second, stats());

            // AS SAVED: REAL_ONLY targets
            click(c, 0, 1, 1.0, "as saved");
            click(c, 0, 1, 1.0, "as saved");
            click(c, 0, 2, 1.0, "as saved");
            click(c, 1, 1, 1.0, "as saved");
            click(c, 1, 2, 1.0, "as saved");
            click(c, 1, 0, 1.0, "as saved");

            // LOCKSTEP targets (the D249 inner settle path)
            for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
            reset(); t0 = System.nanoTime();
            System.out.printf(Locale.US, "  flip→LOCKSTEP rebuild: %.0f ms — %s — %s%n", 0.0, rebuild(c), stats());
            click(c, 0, 1, 1.0, "lockstep");
            click(c, 0, 1, 1.0, "lockstep");
            click(c, 0, 2, 1.0, "lockstep");
            click(c, 1, 1, 1.0, "lockstep");
            click(c, 1, 2, 1.0, "lockstep");

            // Optimize length ON (both bridges), LOCKSTEP still
            for (int p = 0; p < 2; p++) {
                BridgeSection bs = (BridgeSection) c.pieces.get(p).sections.get(c.pieces.get(p).sections.size() - 1);
                bs.optLen = true;
                reset(); t0 = System.nanoTime();
                double d = c.optimalBridgeLength(bs, true);
                System.out.printf(Locale.US, "  optimalBridgeLength(bridge %d) = %.2f in %.0f ms%n", p, d, (System.nanoTime() - t0) / 1e6);
                c.optimizeBridge(bs, p + 1, d, true);
            }
            reset(); t0 = System.nanoTime();
            System.out.printf(Locale.US, "  optLen rebuild: %.0f ms — %s — %s%n", 0.0, rebuild(c), stats());
            click(c, 0, 1, 1.0, "lockstep+optLen");
            click(c, 1, 1, 1.0, "lockstep+optLen");
            // REAL_ONLY + optLen
            for (int p = 1; p < c.pieces.size(); p++) c.pieces.get(p).orientMode = TrackPiece.OrientMode.REAL_ONLY;
            click(c, 0, 1, 1.0, "realonly+optLen");
            click(c, 1, 1, 1.0, "realonly+optLen");
            // unlock pitch on bridge 0 (march aligns it) then click roll
            BridgeSection b0 = (BridgeSection) c.pieces.get(0).sections.get(c.pieces.get(0).sections.size() - 1);
            b0.optLen = false; b0.lockPitch = false;
            c.pieces.get(1).orientMode = TrackPiece.OrientMode.LOCKSTEP;
            c.optimizeBridge(b0, 1, b0.lastOptDist, true);
            reset(); t0 = System.nanoTime();
            System.out.printf(Locale.US, "  unlock-pitch lockstep rebuild: — %s — %s%n", rebuild(c), stats());
            click(c, 0, 2, 1.0, "lockstep pitch-free");
            click(c, 0, 2, 1.0, "lockstep pitch-free");
        }
    }
}
