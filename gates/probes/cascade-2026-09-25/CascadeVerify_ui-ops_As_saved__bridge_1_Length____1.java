package uvd;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** CASCADE VERIFY (2026-09-25) — family ui-ops, configuration "As saved,
 *  bridge 1 Length := 120 m, far-off held pitch/roll on its target
 *  (piece 2)". INDEPENDENT re-measurement: rebuilds the configuration
 *  from the setup description alone, runs the shell's click path
 *  (target write → Coaster.optimizeBridge at the requested length → the
 *  rebuildTrack loop verbatim: 12 fast + 40 exact reconcile/re-optimize
 *  passes, one compile each), prints the signature slots that differ
 *  between the stamped optSig and each pass's fresh capture, isolates
 *  the march cost (Coaster.marchSpine alone, one raw march through
 *  reflection to infer the iteration count) and tests a candidate fix
 *  headlessly WITHOUT touching src/ (a target-head re-capture before the
 *  explicit march — the D249 rest guarantee generalized).
 *
 *  Build: javac -cp target/classes -d gates/out "gates/CascadeVerify_ui-ops_As_saved__bridge_1_Length____1.java"
 *  Run:   java -cp "target/classes;gates/out" uvd.CascadeVerify_ui_ops_As_saved__bridge_1_Length____1
 *  (the class is package-private so the hyphenated file name is legal) */
class CascadeVerify_ui_ops_As_saved__bridge_1_Length____1 {
    static final double DT = Coaster.INTEGRATE_DT;
    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/UVD long bridge update time 9.25.26.uvd";

    /** BridgeSection.optSignature slot names, in order. */
    static final String[] SLOT = {
        "pe.x", "pe.y", "pe.z", "te.x", "te.y", "te.z", "ue.x", "ue.y", "ue.z",
        "accA.x", "accA.y", "accA.z", "jerkA.x", "jerkA.y", "jerkA.z",
        "rollA", "rollAccA", "vDesign",
        "accB.u", "accB.r", "jerkB.u", "jerkB.r", "rollB", "rollAccB", "vB",
        "tB.y", "uB.y", "lastOptDist|0" };

    static long tCompile, tRecon, tReopt;
    static int nCompile, nRecon, nReopt, nReoptTrue;

    static void reset() {
        tCompile = tRecon = tReopt = 0;
        nCompile = nRecon = nReopt = nReoptTrue = 0;
    }

    static Coaster load() throws IOException {
        try (BufferedReader in = Files.newBufferedReader(Paths.get(FILE))) {
            return DesignIO.load(in).coasters().get(0);
        }
    }

    static BridgeSection bridgeOf(Coaster c, int p) {
        List<Section> secs = c.pieces.get(p).sections;
        return (BridgeSection) secs.get(secs.size() - 1);
    }

    static TrackCompiler.Result compile(Coaster c) {
        long t0 = System.nanoTime();
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        tCompile += System.nanoTime() - t0;
        nCompile++;
        return r;
    }

    static String diff(double[] stamped, double[] now) {
        if (stamped == null) return "(optSig null — first sight, record only)";
        if (stamped.length == 0) return "(SIG_STALE)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < now.length; i++) {
            if (Math.abs(now[i] - stamped[i]) > 1e-9) {
                sb.append(String.format(Locale.US, "[%d] %s %.9g→%.9g (Δ%.3g)  ",
                    i, SLOT[i], stamped[i], now[i], now[i] - stamped[i]));
            }
        }
        return sb.length() == 0 ? "match" : sb.toString();
    }

    /** Print every live optimize-mode bridge's stamp-vs-capture diff
     *  (what autoReoptimizeBridges is about to compare). */
    static void printSigs(Coaster c, String phase, int pass) {
        for (int p = 0; p + 1 < c.pieces.size(); p++) {
            List<Section> secs = c.pieces.get(p).sections;
            if (!(secs.get(secs.size() - 1) instanceof BridgeSection bs)) continue;
            if (bs.optMode == BridgeSection.OPT_NONE) continue;
            System.out.printf(Locale.US, "        %s pass %d bridge %d: %s%n",
                phase, pass, p, diff(bs.optSig, bs.optSignature()));
        }
    }

    /** FvdDesigner.rebuildTrack's loop verbatim (at rest: gestureHot = false). */
    static String rebuild(Coaster c, boolean verbose) {
        TrackCompiler.Result r = compile(c);
        int fast = 0, exact = 0;
        boolean quiesced = false;
        for (int pass = 0; pass < 12; pass++) {
            fast++;
            if (verbose) printSigs(c, "fast", pass);
            long t0 = System.nanoTime();
            boolean again = c.reconcileChainedSpeeds(r);
            tRecon += System.nanoTime() - t0; nRecon++;
            t0 = System.nanoTime();
            boolean re = c.autoReoptimizeBridges(r);
            tReopt += System.nanoTime() - t0; nReopt++;
            if (re) { again = true; nReoptTrue++; }
            if (!again) break;
            r = compile(c);
        }
        for (int pass = 0; pass < 40; pass++) {
            exact++;
            if (verbose) printSigs(c, "exact", pass);
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
        return String.format(Locale.US,
            "fast=%d exact=%d quiesced=%b samples=%d | compile %d× %.0f ms | reconcile %d× %.0f ms | autoReopt %d× (%d re-marched) %.0f ms",
            fast, exact, quiesced, r.samples().size(), nCompile, tCompile / 1e6,
            nRecon, tRecon / 1e6, nReopt, nReoptTrue, tReopt / 1e6);
    }

    /** One bridge-window spinner click on bridge bp's target (piece
     *  bp+1): GraphEditor.setBridgeTargetOrient → bridgeTargetWrite →
     *  placementEdit (no carry: REAL_ONLY + affect off) → applyBridgeGap
     *  = optimizeBridge(bs, bp+1, lastOptDist, alignYaw) + rebuildTrack.
     *  captureFirst = the candidate fix: re-capture the target's head
     *  (TrackCompiler.captureTargetHead, exactly what the next compile
     *  does) BEFORE the explicit march. Returns ms. */
    static double click(Coaster c, int bp, int axis, double deg, double len,
                        boolean captureFirst, boolean verbose, String label) {
        BridgeSection bs = bridgeOf(c, bp);
        TrackPiece t = c.pieces.get(bp + 1);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        reset();
        long t0 = System.nanoTime();
        if (axis == 1) t.setDispPitch(deg, true);
        else if (axis == 2) t.setDispRoll(deg, true);
        else t.setDispYaw(deg);
        if (captureFirst) TrackCompiler.captureTargetHead(bs, t, DT, c.fric());
        long t1 = System.nanoTime();
        c.optimizeBridge(bs, bp + 1, len, py);
        double msOpt = (System.nanoTime() - t1) / 1e6;
        if (verbose) {
            System.out.printf(Locale.US, "    [%s] %s := %.0f° (raw pitch %.3f roll %.3f yaw %.3f) — explicit optimizeBridge %.0f ms; loop:%n",
                label, axis == 1 ? "pitch" : axis == 2 ? "roll" : "yaw", deg,
                t.pitchDeg, t.rollDeg, t.yawDeg, msOpt);
        }
        String loop = rebuild(c, verbose);
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf(Locale.US, "    [%s] %s := %.0f° @ %.0f m%s: %.0f ms/click (explicit march %.0f ms) — %s%n",
            label, axis == 1 ? "pitch" : axis == 2 ? "roll" : "yaw", deg, len,
            captureFirst ? " +captureFirst" : "", ms, msOpt, loop);
        return ms;
    }

    /** Isolate the march: time Coaster.marchSpine (what EVERY compile of
     *  the bridge runs inside BridgeSection.integrate), bs.integrate
     *  itself, and ONE raw march at the same length through reflection
     *  (private static Coaster.march) to infer how many marches the
     *  marchEnd fixed point / rescue ran. */
    static void marchCost(Coaster c, int bp, String label) throws Exception {
        BridgeSection bs = bridgeOf(c, bp);
        boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
        double best = Double.MAX_VALUE;
        List<double[]> spine = null;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            spine = Coaster.marchSpine(bs, py);
            best = Math.min(best, (System.nanoTime() - t0) / 1e6);
        }
        long t0 = System.nanoTime();
        BridgeSection.Result br = bs.integrate(DT, c.fric());
        double msInt = (System.nanoTime() - t0) / 1e6;

        Method seamState = Coaster.class.getDeclaredMethod("seamState",
            Vec3.class, Vec3.class, Vec3.class, Vec3.class,
            double.class, double.class, double.class);
        Method deadband = Coaster.class.getDeclaredMethod("deadbandSlopes",
            double[].class, double[].class, double.class);
        Method march = Coaster.class.getDeclaredMethod("march",
            Vec3.class, Vec3.class, Vec3.class, double.class,
            double[].class, double[].class, double.class, double.class,
            double.class, List.class, List.class, double[].class);
        seamState.setAccessible(true);
        deadband.setAccessible(true);
        march.setAccessible(true);
        Vec3 tA = bs.solvedEntry.T(), uA = bs.solvedEntry.U();
        Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
        double d = bs.lastOptDist;
        double[] sa = (double[]) seamState.invoke(null, bs.accA, bs.jerkA, uA,
            uA.cross(tA), bs.rollA, bs.rollAccA, bs.vDesign);
        double[] sb = (double[]) seamState.invoke(null, bs.accB, bs.jerkB, uB,
            uB.cross(tB), bs.vB > 0 ? bs.rollB : 0, bs.vB > 0 ? bs.rollAccB : 0,
            bs.vB > 0 ? bs.vB : bs.vDesign);
        deadband.invoke(null, sa, sb, d);
        int steps = (int) Math.min(40_000, Math.max(8, Math.ceil(d / 0.05)));
        // INTERLEAVED against contention (other JVM probes run beside
        // this one): min-of-3 marchSpine vs min-of-3 of a BATCH of 170
        // hump-bearing spine-building raw marches — 170 = the analytic
        // upper bound of marches one marchEnd can run (2 + 14 damped +
        // rescue 9 + 16 × 9 + 1 final), so the ratio reads directly
        // as "fraction of the budget burned".
        double batch = Double.MAX_VALUE;
        best = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long m0 = System.nanoTime();
            spine = Coaster.marchSpine(bs, py);
            best = Math.min(best, (System.nanoTime() - m0) / 1e6);
            long s0 = System.nanoTime();
            for (int k = 0; k < 170; k++) {
                List<double[]> sp = new ArrayList<>();
                march.invoke(null, bs.solvedEntry.pos(), tA, uA, d, sa, sb,
                    0.7, 0.3, 1.2, null, sp, new double[2]);
            }
            batch = Math.min(batch, (System.nanoTime() - s0) / 1e6);
        }
        System.out.printf(Locale.US,
            "      march cost [%s] d=%.0f m: marchSpine %.0f ms (lands=%b%s) | bs.integrate %.0f ms (%d samples) | 170 raw hump+spine marches (%d RK4 steps each) %.0f ms = %.2f ms/march → marchSpine ≈ %.0f marches (bound 170)%n",
            label, d, best, spine != null,
            spine != null ? ", rows=" + spine.size() : "", msInt,
            br.samples().size(), steps, batch, batch / 170, best / (batch / 170));
    }

    static String fingerprint(Coaster c) {
        TrackPiece t = c.pieces.get(2);
        BridgeSection bs = bridgeOf(c, 1);
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        double sum = 0;
        for (FVDIntegrator.Sample s : r.samples()) {
            sum += s.pos().x() + s.pos().y() + s.pos().z();
        }
        return String.format(Locale.US,
            "piece2 x=%s y=%s z=%s yaw=%s pitch=%s roll=%s | b1 lenA=%s lastOptDist=%s | samples=%d Σpos=%s",
            Long.toHexString(Double.doubleToLongBits(t.x)),
            Long.toHexString(Double.doubleToLongBits(t.y)),
            Long.toHexString(Double.doubleToLongBits(t.z)),
            Long.toHexString(Double.doubleToLongBits(t.yawDeg)),
            Long.toHexString(Double.doubleToLongBits(t.pitchDeg)),
            Long.toHexString(Double.doubleToLongBits(t.rollDeg)),
            Long.toHexString(Double.doubleToLongBits(bs.lenA)),
            Long.toHexString(Double.doubleToLongBits(bs.lastOptDist)),
            r.samples().size(), Long.toHexString(Double.doubleToLongBits(sum)));
    }

    static void table(Coaster c) {
        for (int p = 0; p < c.pieces.size(); p++) {
            TrackPiece tp = c.pieces.get(p);
            Section last = tp.sections.get(tp.sections.size() - 1);
            System.out.printf(Locale.US,
                "  piece %d mode=%s affect=%b design=(%.3f,%.3f) true=(%.3f,%.3f) disp=(p %.3f, r %.3f, y %.3f)%s%n",
                p, tp.orientMode, tp.affectSubsequentSeams, tp.designPitchDeg,
                tp.designRollDeg, tp.pitchDeg, tp.rollDeg, tp.dispPitch(),
                tp.dispRoll(), tp.dispYaw(),
                last instanceof BridgeSection b ? String.format(Locale.US,
                    " | bridge optMode=%d optLen=%b lastOptDist=%.2f locks y/p/r=%b/%b/%b",
                    b.optMode, b.optLen, b.lastOptDist, b.lockYaw, b.lockPitch,
                    b.lockRoll) : "");
        }
    }

    /** load → open rebuild → idle rebuild → bridge 1 Length := 120 (the
     *  reported setup). */
    static Coaster setup(double len, boolean verbose) throws Exception {
        Coaster c = load();
        reset();
        long t0 = System.nanoTime();
        String open = rebuild(c, false);
        System.out.printf(Locale.US, "  open rebuild: %.0f ms — %s%n",
            (System.nanoTime() - t0) / 1e6, open);
        reset();
        t0 = System.nanoTime();
        String idle = rebuild(c, false);
        System.out.printf(Locale.US, "  idle rebuild: %.0f ms — %s%n",
            (System.nanoTime() - t0) / 1e6, idle);
        BridgeSection b1 = bridgeOf(c, 1);
        reset();
        t0 = System.nanoTime();
        c.optimizeBridge(b1, 2, len, b1.optMode == BridgeSection.OPT_POS_YAW);
        double msOpt = (System.nanoTime() - t0) / 1e6;
        String s = rebuild(c, verbose);
        System.out.printf(Locale.US,
            "  Length := %.0f m on bridge 1: %.0f ms total (explicit optimizeBridge %.0f ms) — %s%n",
            len, (System.nanoTime() - t0) / 1e6, msOpt, s);
        return c;
    }

    public static void main(String[] a) throws Exception {
        for (int rep = 0; rep < 2; rep++) {
            System.out.println("=== rep " + rep + (rep == 0 ? " (cold JIT)" : " (warm)") + " ===");
            Coaster c = setup(120, rep == 1);
            table(c);
            marchCost(c, 1, "after setup");

            // THE REPORTED CLICK SEQUENCE (absolute displayed values,
            // the bridge window's spinners, piece 2 = bridge 1's target)
            double[][] seq = { {1, 60}, {1, -60}, {1, 80}, {1, -80}, {1, -79},
                               {2, 0}, {2, 90}, {2, -90}, {2, 150}, {2, -150}, {2, -149} };
            double worst = 0, sum = 0;
            for (double[] k : seq) {
                double ms = click(c, 1, (int) k[0], k[1], 120, false, rep == 1, "120 m");
                worst = Math.max(worst, ms);
                sum += ms;
                if (rep == 1) {
                    marchCost(c, 1, "after click");
                    // the STANDING cost: a rebuild that changes nothing
                    // (any later edit / drag event pays this compile)
                    reset();
                    long t0 = System.nanoTime();
                    String idle = rebuild(c, false);
                    System.out.printf(Locale.US, "      idle rebuild after click: %.0f ms — %s%n",
                        (System.nanoTime() - t0) / 1e6, idle);
                }
            }
            System.out.printf(Locale.US, "  120 m summary: mean %.0f ms/click, worst %.0f ms%n",
                sum / seq.length, worst);

            // LENGTH SCALING: same clicks at 27 m and 5 m
            for (double len : new double[] { 27, 5 }) {
                BridgeSection b1 = bridgeOf(c, 1);
                reset();
                c.optimizeBridge(b1, 2, len, true);
                String s = rebuild(c, false);
                System.out.printf(Locale.US, "  Length := %.0f m: %s%n", len, s);
                for (double[] k : new double[][] { {1, 60}, {1, -80}, {2, 90}, {2, -150} }) {
                    click(c, 1, (int) k[0], k[1], len, false, false, len + " m");
                }
                if (rep == 1) marchCost(c, 1, "after clicks @ " + len);
            }

            // BRIDGE 0 CONTROL: its target (piece 1) with the same
            // far-off values at bridge 0's saved 27 m, then at 120 m
            for (double len : new double[] { 27, 120 }) {
                BridgeSection b0 = bridgeOf(c, 0);
                reset();
                c.optimizeBridge(b0, 1, len, true);
                String s = rebuild(c, false);
                System.out.printf(Locale.US, "  bridge 0 Length := %.0f m: %s%n", len, s);
                for (double[] k : new double[][] { {1, 60}, {2, 90} }) {
                    click(c, 0, (int) k[0], k[1], len, false, false, "b0 " + len + " m");
                }
            }
        }

        // CANDIDATE FIX (headless, src untouched): re-capture the target
        // head before the explicit march so the stamped signature equals
        // the next compile's capture — the auto pass never re-marches
        // after a click. Compare the settled documents bit for bit.
        System.out.println("=== candidate fix: captureTargetHead before the explicit optimizeBridge ===");
        for (double[] k : new double[][] { {1, 60}, {2, 90}, {1, -80}, {2, -150} }) {
            Coaster ca = setup(120, false);
            click(ca, 1, (int) k[0], k[1], 120, false, true, "AS-IS");
            String fa = fingerprint(ca);
            Coaster cb = setup(120, false);
            click(cb, 1, (int) k[0], k[1], 120, true, true, "FIXED");
            String fb = fingerprint(cb);
            reset();
            long t0 = System.nanoTime();
            String idle = rebuild(cb, true);
            System.out.printf(Locale.US, "    FIXED idle rebuild: %.0f ms — %s%n",
                (System.nanoTime() - t0) / 1e6, idle);
            System.out.println("    AS-IS  " + fa);
            System.out.println("    FIXED  " + fb);
            System.out.println("    settled documents identical: " + fa.equals(fb));
        }
    }
}
