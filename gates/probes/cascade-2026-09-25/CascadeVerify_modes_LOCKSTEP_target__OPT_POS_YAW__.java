package uvd;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** INDEPENDENT VERIFY (2026-09-25) of the family-modes report
 *  "LOCKSTEP target, OPT_POS_YAW, roll FREE (yaw+pitch locked),
 *  Optimize length ON — click on pitch": both bridge targets LOCKSTEP,
 *  locks yaw=locked pitch=locked roll=FREE, optLen=true on both
 *  bridges of the user's file; a bridge-window pitch click (+1°) on
 *  bridge 0 then bridge 1, repeated. Built from the setup description
 *  only (the hunter's probe was NOT read). Per click: the shell's
 *  rebuild loop verbatim with the signature slots that differ from
 *  the stamp printed per pass, the requested vs re-kneed length, the
 *  knee off the RESTED seams vs lastOptDist, the target's placement
 *  walk, the march landing test, and an idle-rebuild bitwise check.
 *  Then two isolation experiments: (A) re-apply the rested knee with
 *  NO click (the loop the auto pass would run if it re-kneed) and
 *  (B) the inner settle trace — captureTargetHead + align per round
 *  — to see whether the roll alignment contracts or repels. */
public class CascadeVerify_modes_LOCKSTEP_target__OPT_POS_YAW__ {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String PATH =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "len(0 if optLen)" };

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(PATH))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> secs = c.pieces.get(p).sections;
        return secs.get(secs.size() - 1) instanceof BridgeSection b ? b : null;
    }

    record Loop(int fast, int exact, boolean quiesced, int reopt) {
        public String toString() {
            return "fast=" + fast + " exact=" + exact + " quiesced=" + quiesced
                + " reoptCalls=" + reopt;
        }
    }

    /** FvdDesigner.rebuildTrack's loop verbatim (at rest), with the
     *  signature slots that differ from the stamp printed before every
     *  autoReoptimizeBridges call (verbose). */
    static Loop rebuild(Coaster c, boolean verbose) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        int fast = 0, exact = 0, reopt = 0;
        boolean q = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            if (verbose) sigDiff(c, "fast#" + pass);
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) { again = true; reopt++; }
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            if (verbose) sigDiff(c, "exact#" + pass);
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) { again = true; reopt++; }
            if (!again) { q = true; break; }
            r = TrackCompiler.compile(c, DT);
        }
        return new Loop(fast, exact, q, reopt);
    }

    static void sigDiff(Coaster c, String tag) {
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null || bs.optMode == BridgeSection.OPT_NONE) continue;
            double[] sig = bs.optSignature();
            if (bs.optSig == null) {
                System.out.println("      " + tag + " bridge " + p + ": first sight (record only)");
                continue;
            }
            if (bs.optSig.length != sig.length) {
                System.out.println("      " + tag + " bridge " + p + ": SIG_STALE (forced re-march)");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < sig.length; i++) {
                double d = sig[i] - bs.optSig[i];
                if (Math.abs(d) > 1e-9) {
                    n++;
                    sb.append(String.format(Locale.US, "[%d %s: stamped %.6g now %.6g d=%.3g] ",
                        i, SLOT[i], bs.optSig[i], sig[i], d));
                }
            }
            if (n > 0) {
                System.out.println("      " + tag + " bridge " + p + " signature differs in "
                    + n + " slot(s) -> RE-MARCH: " + sb);
            } else {
                System.out.println("      " + tag + " bridge " + p + " signature == stamp (no re-march)");
            }
        }
    }

    static double[] snap(Coaster c) {
        List<Double> v = new ArrayList<>();
        for (TrackPiece t : c.pieces) {
            v.add(t.x); v.add(t.y); v.add(t.z); v.add(t.yawDeg);
            v.add(t.pitchDeg); v.add(t.rollDeg);
            v.add(t.designPitchDeg); v.add(t.designRollDeg);
            BridgeSection b = c.pieces.indexOf(t) + 1 < c.pieces.size()
                ? bridgeOf(c, c.pieces.indexOf(t)) : null;
            v.add(b == null ? 0 : b.lastOptDist);
            v.add(b == null ? 0 : b.lenA);
            for (Section s : t.sections) v.add(s.vDesign());
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    static boolean bitwise(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(b[i])) return false;
        }
        return true;
    }

    static String place(TrackPiece t) {
        return String.format(Locale.US,
            "pos=(%.2f, %.2f, %.2f) yaw=%.2f pitch=%.2f roll=%.2f design=(%.2f, %.2f) disp(p,r)=(%.2f, %.2f)",
            t.x, t.y, t.z, t.yawDeg, t.pitchDeg, t.rollDeg,
            t.designPitchDeg, t.designRollDeg, t.dispPitch(), t.dispRoll());
    }

    static void state(Coaster c, String tag) {
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null) continue;
            boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
            double knee = c.optimalBridgeLength(bs, py);
            boolean landed = Coaster.marchSpine(bs, py) != null;
            System.out.printf(Locale.US,
                "    %s bridge %d: lastOptDist=%.2f  knee(rested seams)=%.2f  %s  landed=%b  vB=%.2f accB=(u %.4f, r %.4f) rollB=%.4f | target %d %s%n",
                tag, p, bs.lastOptDist, knee,
                Math.abs(knee - bs.lastOptDist) > 1e-6 ? "MISMATCH" : "match",
                landed, bs.vB,
                bs.accB.dot(bs.solvedTarget.U()),
                bs.accB.dot(bs.solvedTarget.U().cross(bs.solvedTarget.T())),
                bs.rollB, p + 1, place(c.pieces.get(p + 1)));
        }
    }

    /** The reported configuration, built from its description. */
    static Coaster setup(boolean verbose) throws IOException {
        Coaster c = load();
        System.out.println("  as saved:");
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece t = c.pieces.get(p);
            BridgeSection b = bridgeOf(c, p);
            System.out.printf(Locale.US, "    piece %d mode=%s affect=%b %s%s%n", p, t.orientMode,
                t.affectSubsequentSeams, place(t),
                b == null ? "" : String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks y/p/r=%b/%b/%b",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch, b.lockRoll));
        }
        Loop open = rebuild(c, false);
        System.out.println("  open rebuild: " + open);
        // the configuration: LOCKSTEP targets, yaw+pitch locked, roll free, optLen on
        for (int p = 1; p < c.pieces.size(); p++) {
            c.pieces.get(p).orientMode = TrackPiece.OrientMode.LOCKSTEP;
        }
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            BridgeSection bs = bridgeOf(c, p);
            if (bs == null) continue;
            bs.optMode = BridgeSection.OPT_POS_YAW;
            bs.lockYaw = true;
            bs.lockPitch = true;
            bs.lockRoll = false;
            bs.optLen = true;
            double d = c.optimalBridgeLength(bs, true);
            System.out.printf(Locale.US, "  apply optLen on bridge %d: knee=%.2f%n", p, d);
            c.optimizeBridge(bs, p + 1, d, true);
            System.out.printf(Locale.US, "    after optimizeBridge: lastOptDist=%.2f (re-kneed=%b)%n",
                bs.lastOptDist, Math.abs(bs.lastOptDist - d) > 1e-9);
        }
        Loop L = rebuild(c, verbose);
        System.out.println("  setup rebuild: " + L);
        state(c, "setup");
        double[] s1 = snap(c);
        Loop L2 = rebuild(c, verbose);
        double[] s2 = snap(c);
        System.out.println("  setup idle rebuild: " + L2 + " bitwise no-op=" + bitwise(s1, s2));
        return c;
    }

    /** One bridge-window spinner click on piece bp's trailing bridge
     *  (GraphEditor.setBridgeTargetOrient → bridgeTargetWrite →
     *  placementEdit [no carry under LOCKSTEP] → applyOptimalLength →
     *  applyBridgeGap → optimizeBridge + rebuildTrack). */
    static void click(Coaster c, int bp, int axis, double delta, boolean verbose) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        Vec3 posBefore = new Vec3(t.x, t.y, t.z);
        double rollBefore = t.rollDeg, dRollBefore = t.designRollDeg;
        double lastBefore = bs.lastOptDist;
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(t.dispPitch() + delta, true);
        else if (axis == 2) t.setDispRoll(t.dispRoll() + delta, true);
        else t.setDispYaw(t.dispYaw() + delta);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double dReq;
        if (bs.optLen) {
            dReq = c.optimalBridgeLength(bs, py);
        } else {
            dReq = bs.lastOptDist > 0 ? bs.lastOptDist : 1;
        }
        c.optimizeBridge(bs, bp + 1, dReq, py);
        double optMs = (System.nanoTime() - t0) / 1e6;
        double dStamped = bs.lastOptDist;
        Loop L = rebuild(c, verbose);
        double ms = (System.nanoTime() - t0) / 1e6;
        Vec3 posAfter = new Vec3(t.x, t.y, t.z);
        System.out.printf(Locale.US,
            "  CLICK bridge %d axis %d %+.0f: %.0f ms (optimizeBridge %.0f ms) | %s | requested d=%.2f -> stamped lastOptDist=%.2f (prev %.2f) | target %d moved %.2f m, roll %.2f -> %.2f (design roll %.2f -> %.2f)%n",
            bp, axis, delta, ms, optMs, L, dReq, dStamped, lastBefore,
            bp + 1, posAfter.sub(posBefore).length(), rollBefore, t.rollDeg,
            dRollBefore, t.designRollDeg);
        double[] s1 = snap(c);
        Loop L2 = rebuild(c, false);
        double[] s2 = snap(c);
        System.out.println("    idle rebuild after click: " + L2 + " bitwise no-op=" + bitwise(s1, s2));
        state(c, "  at rest");
    }

    /** Experiment A: re-apply the rested knee with NO click — the
     *  loop the auto pass WOULD run if the signature carried the
     *  knee. Shows whether (knee, roll alignment) has a fixed point. */
    static void rekneeWalk(Coaster c, int bp, int iters) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        System.out.println("  EXPERIMENT A — re-knee at rest, no click, bridge " + bp + ":");
        for (int i = 0; i < iters; i++) {
            double knee = c.optimalBridgeLength(bs, true);
            double before = bs.lastOptDist;
            Vec3 p0 = new Vec3(t.x, t.y, t.z);
            double r0 = t.rollDeg;
            c.optimizeBridge(bs, bp + 1, knee, true);
            Loop L = rebuild(c, false);
            System.out.printf(Locale.US,
                "    iter %d: rested lastOptDist=%.2f knee=%.2f -> stamped %.2f | %s | target moved %.2f m roll %.2f -> %.2f | landed=%b%n",
                i, before, knee, bs.lastOptDist, L,
                new Vec3(t.x, t.y, t.z).sub(p0).length(), r0, t.rollDeg,
                Coaster.marchSpine(bs, true) != null);
        }
    }

    /** Experiment B: the inner settle trace at a FIXED length —
     *  captureTargetHead + alignPieceForBridge per round (what
     *  settleLockstepAlign does), printing the per-round delta. */
    static void settleTrace(Coaster c, int bp, double dist, int rounds) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        System.out.printf(Locale.US, "  EXPERIMENT B — settle trace bridge %d at d=%.2f (yaw+pitch locked, roll free):%n", bp, dist);
        for (int round = 0; round < rounds; round++) {
            TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
            double accU = bs.accB.dot(bs.solvedTarget.U());
            double accR = bs.accB.dot(bs.solvedTarget.U().cross(bs.solvedTarget.T()));
            double preRoll = t.rollDeg, preDRoll = t.designRollDeg;
            Vec3 p0 = new Vec3(t.x, t.y, t.z);
            c.alignPieceForBridge(bs, bp + 1, dist, true);
            double delta = Math.abs(TrackPiece.wrap180(t.rollDeg - preRoll));
            System.out.printf(Locale.US,
                "    round %2d: head accB=(u %.5f, r %.5f) rollB=%.4f vB=%.3f | roll %.3f -> %.3f (design %.3f -> %.3f) |delta|=%.4g | pos moved %.3f m%n",
                round, accU, accR, bs.rollB, bs.vB, preRoll, t.rollDeg, preDRoll,
                t.designRollDeg, delta, new Vec3(t.x, t.y, t.z).sub(p0).length());
            if (delta < 1e-9) { System.out.println("    converged"); return; }
        }
        System.out.println("    NOT converged in " + rounds + " rounds");
    }

    public static void main(String[] a) throws Exception {
        for (int rep = 0; rep < 2; rep++) {
            System.out.println("=== rep " + rep + (rep == 0 ? " (cold JIT)" : " (warm)") + " ===");
            Coaster c = setup(rep == 1);
            // the reported click sequence: pitch +1 on bridge 0, then bridge 1, repeated
            for (int k = 0; k < 4; k++) {
                click(c, 0, 1, 1.0, rep == 1);
                click(c, 1, 1, 1.0, rep == 1);
            }
        }
        System.out.println("=== EXPERIMENT A (fresh setup) ===");
        Coaster c = setup(false);
        rekneeWalk(c, 1, 8);
        rekneeWalk(c, 0, 4);
        System.out.println("=== EXPERIMENT B (fresh setup) ===");
        c = setup(false);
        BridgeSection b1 = bridgeOf(c, 1);
        settleTrace(c, 1, b1.lastOptDist, 24);
        c = setup(false);
        b1 = bridgeOf(c, 1);
        settleTrace(c, 1, c.optimalBridgeLength(b1, true), 24);
        c = setup(false);
        settleTrace(c, 1, 60, 24);
        c = setup(false);
        settleTrace(c, 1, 120, 24);
        c = setup(false);
        BridgeSection b0 = bridgeOf(c, 0);
        settleTrace(c, 0, b0.lastOptDist, 24);
    }
}
