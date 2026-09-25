import uvd.*;

import java.util.*;

/** D248 gate — the SPEED STRIP's DESIGN-speed line (v19.19, item S2),
 *  pure model via uvd.SpeedStrip + real compiles:
 *  (a) an unconflicted single section: design == true at every
 *      sample, so the pixel split never fires at any strip scale —
 *      the design line is invisible and the hover has no suffix;
 *  (b) a PINNED second section (a deliberate ≠v): design ≠ true
 *      across the whole section, the split fires at every sample
 *      there and at none of the first section's — purely
 *      pixel-based, no flag read;
 *  (c) a TILTED shape-locked piece (≠o only, the D100 rigid tilt):
 *      the entry sample stays unsplit while interior samples split —
 *      the line appears MID-section like the lanes' true strokes;
 *  (d) the split predicate: strictly MORE than TRUE_SPLIT_PX draws, a
 *      gap AT the threshold hides, NaN hides;
 *  (e) inside a constant-SPEED control design == true (design and
 *      real move equally, D160), so the design line ends at the snap
 *      by itself; before the control the pinned split still shows;
 *  (f) fitHi takes the design speed into the fit (window clamp,
 *      length mismatch read to the shorter, null inert);
 *  (g) the hover suffix format " (design 12.34)" — no unit;
 *  (h) the line styles: design = the old true style / 1.5 (width
 *      0.8, dashes (2, 2.67)), true wider with the historic (3, 4)
 *      dashes and a brighter RGB per channel.
 *  EXTENDED v19.20 (D249, item S — speed-control parity under the
 *  cross-domain view + position readouts):
 *  (s1) SpeedStrip.snapDisplay with a0 = 0 == the strip's historic
 *       native snap round(x / step)·step BITWISE (500 random x) — and
 *       GraphEditor keeps that literal path wherever nothing is warped;
 *  (s2) the DISPLAY-axis snap round trip on a REAL compile through the
 *       very map the editor warps with (Coaster.sectionMap / mapCol):
 *       a TIME-native control viewed in meters snaps onto the 1 m
 *       display grid (forward map of the written native x lands on
 *       the grid ≤ 1e-9) and a DISTANCE-native one viewed in seconds
 *       onto the 0.1 s grid; the section-local anchor a0 is the
 *       map's exact origin;
 *  (s3) the position readout formats "t = 3.20 s" (two decimals) /
 *       "s = 12.5 m" (one), the zero-dust rule (−0.0 and |v| < 1e-6 →
 *       +0; larger dust keeps its sign, D245), Locale.US under a
 *       comma-decimal default locale, and posValue = the readout
 *       minus its "t = " / "s = " head (the popup twins' "= 12.5 m");
 *  (s4) SpeedStrip.diamondAt — the hover readout's draw-time re-check:
 *       a control start / end / mark within tol hits, a removed or
 *       moved diamond misses (the resting-cursor undo corner). */
public class StripGate {

    static int pass = 0, fail = 0;
    static final double DT = 0.01;
    static final double TRUE_SPLIT_PX = 1.5;   // GraphEditor.TRUE_SPLIT_PX

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static void check(boolean ok, String name, String detail) {
        check(ok, name + "  (" + detail + ")");
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** A three-lane hill (the GestureGate / EndFollowGate fixture). */
    static List<List<BezierBaker.Node>> hillLanes(double len, double amp) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 1 + amp},
            {len * 0.7, 1 - amp * 0.6}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.3}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 25}, {len, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> flatLanes(double len) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        return ls;
    }

    /** The shell's rebuild loop (ConflictGate's twin): fast tube
     *  passes, then the exact settle. */
    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** Pixel rows on a strip whose scale k px per m/s is fitted the
     *  strip's way (line height / (1.12·vHi)); returns
     *  {splitCount, sampleCount, maxGap m/s, firstSplitIdx} over the
     *  span's samples. The split test is SpeedStrip.split on the two
     *  rows — exactly GraphEditor.spDraw's per-sample decision. */
    static double[] splitStats(TrackCompiler.Result r, int spanIdx,
                               double stripPx) {
        List<FVDIntegrator.Sample> ss = r.samples();
        double[] tv = r.trueV();
        int n = Math.min(tv.length, ss.size());
        double vHi = SpeedStrip.fitHi(10, tv, ss, i -> ss.get(i).t(),
            -1e9, 1e9) * 1.12;
        double k = stripPx / vHi;
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(), n - 1);
        int cnt = 0, tot = 0, first = -1;
        double maxGap = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            double yd = -ss.get(i).v() * k, yt = -tv[i] * k;   // spVToY
            boolean sp1 = SpeedStrip.split(yd, yt, TRUE_SPLIT_PX);
            maxGap = Math.max(maxGap, Math.abs(ss.get(i).v() - tv[i]));
            tot++;
            if (sp1) { cnt++; if (first < 0) first = i; }
        }
        return new double[]{ cnt, tot, maxGap, first };
    }

    public static void main(String[] args) {
        // ================= (a) unconflicted =================
        {
            Coaster co = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(new ForceSection(hillLanes(6, 1.2), 26));
            co.pieces.add(p);
            co.v0 = 26;
            TrackCompiler.Result r = rebuild(co);
            double[] s = splitStats(r, 0, 400);
            check(s[0] == 0 && s[1] > 100,
                "a1 unconflicted section: design == true, no split at 400 px",
                String.format("%d/%d split, max gap %.3e m/s",
                    (int) s[0], (int) s[1], s[2]));
            double[] s2 = splitStats(r, 0, 4000);
            check(s2[0] == 0,
                "a2 …nor at a 10x taller strip (the gap is numerically zero)",
                String.format("max gap %.3e m/s", s2[2]));
        }

        // ================= (b) pinned second section =================
        {
            Coaster co = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection fa = new ForceSection(hillLanes(6, 1.2), 26);
            ForceSection fb = new ForceSection(hillLanes(5, 0.8), 18);
            fb.vPinned(true);   // a deliberate ≠v the reconcile keeps
            p.sections.add(fa);
            p.sections.add(fb);
            co.pieces.add(p);
            co.v0 = 26;
            TrackCompiler.Result r = rebuild(co);
            check(fb.vDesign() == 18, "b0 the pin survives the rebuild",
                String.valueOf(fb.vDesign()));
            double[] sA = splitStats(r, 0, 200);
            double[] sB = splitStats(r, 1, 200);
            check(sA[0] == 0, "b1 first section: no split",
                String.format("%d/%d, gap %.3e", (int) sA[0], (int) sA[1], sA[2]));
            check(sB[0] == sB[1] && sB[1] > 100,
                "b2 pinned section: EVERY sample splits (design line spans it)",
                String.format("%d/%d, max gap %.3f m/s", (int) sB[0],
                    (int) sB[1], sB[2]));
            check(sB[2] > 1.0,
                "b3 the gap is a real m/s-scale conflict, not dust",
                String.format("%.3f m/s", sB[2]));
            // the sample sources are the strip's: Sample.v vs trueV
            int i = r.spans().get(1).i0() + 5;
            check(r.samples().get(i).v() != r.trueV()[i]
                && Math.abs(r.samples().get(i).v() - r.trueV()[i]) > 1,
                "b4 Sample.v (design) vs trueV (real) differ inside the pinned section",
                String.format("design %.3f true %.3f",
                    r.samples().get(i).v(), r.trueV()[i]));
        }

        // ================= (c) tilted piece (≠o only) =================
        {
            Coaster co = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;     // pure conflict (D248: explicit)
            p.editPitch(15);          // the D100 rigid tilt, real only
            p.sections.add(new ForceSection(hillLanes(6, 1.2), 26));
            co.pieces.add(p);
            co.v0 = 26;
            TrackCompiler.Result r = rebuild(co);
            check(p.pitchDeg == 15 && p.designPitchDeg == 0,
                "c0 tilt = true tier only (design 0 / real 15)");
            double[] s = splitStats(r, 0, 300);
            TrackCompiler.Span sp = r.spans().get(0);
            double gap0 = Math.abs(r.samples().get(sp.i0()).v()
                - r.trueV()[sp.i0()]);
            check(gap0 < 1e-9,
                "c1 entry sample: design == true (the run's energy reference)",
                String.format("%.3e m/s", gap0));
            check(s[3] > sp.i0() && s[0] > 0 && s[0] < s[1],
                "c2 interior samples split — the line appears MID-section",
                String.format("first split at %d (i0 %d), %d/%d, max gap %.3f",
                    (int) s[3], sp.i0(), (int) s[0], (int) s[1], s[2]));
        }

        // ================= (d) the predicate =================
        check(!SpeedStrip.split(10, 10 + TRUE_SPLIT_PX, TRUE_SPLIT_PX),
            "d1 a gap exactly AT the threshold is hidden");
        check(SpeedStrip.split(10, 10 + TRUE_SPLIT_PX + 1e-9, TRUE_SPLIT_PX),
            "d2 just over the threshold draws");
        check(SpeedStrip.split(10 + 3, 10, TRUE_SPLIT_PX)
            && SpeedStrip.split(10 - 3, 10, TRUE_SPLIT_PX),
            "d3 symmetric (design above or below the true row)");
        check(!SpeedStrip.split(Double.NaN, 10, TRUE_SPLIT_PX)
            && !SpeedStrip.split(10, Double.NaN, TRUE_SPLIT_PX),
            "d4 NaN rows hide");
        check(!SpeedStrip.split(10, 10, TRUE_SPLIT_PX),
            "d5 equal rows hide");

        // ================= (e) inside a speed control =================
        {
            Coaster co = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection fa = new ForceSection(flatLanes(4), 26);
            ForceSection fb = new ForceSection(flatLanes(6), 18);
            fb.vPinned(true);
            SpeedControl c = new SpeedControl(2, 3);   // [2, 5] of fb
            c.speed = 15;
            fb.speedCtls.add(c);
            p.sections.add(fa);
            p.sections.add(fb);
            co.pieces.add(p);
            co.v0 = 26;
            TrackCompiler.Result r = rebuild(co);
            TrackCompiler.Span sp = r.spans().get(1);
            List<FVDIntegrator.Sample> ss = r.samples();
            double t0 = sp.t0();
            int before = 0, beforeSplit = 0, inside = 0;
            double insideGap = 0;
            for (int i = sp.i0(); i <= Math.min(sp.i1(), r.trueV().length - 1); i++) {
                double x = ss.get(i).t() - t0;
                double gap = Math.abs(ss.get(i).v() - r.trueV()[i]);
                if (x < 1.9) { before++; if (gap > 1) beforeSplit++; }
                else if (x > 2.05 && x < 5) {
                    inside++;
                    insideGap = Math.max(insideGap, gap);
                }
            }
            check(before > 50 && beforeSplit == before,
                "e1 before the control the pinned split shows at every sample",
                before + " samples");
            check(inside > 100 && insideGap < 1e-9,
                "e2 INSIDE the constant-speed control design == true "
                + "(the design line ends at the snap by itself)",
                String.format("%d samples, max gap %.3e", inside, insideGap));
        }

        // ================= (f) fitHi =================
        {
            double[] tv = { 5, 10, 7 };
            List<FVDIntegrator.Sample> ss = new ArrayList<>();
            double[] dv = { 20, 3, 30 };
            for (int i = 0; i < 3; i++) {
                ss.add(new FVDIntegrator.Sample(i, i, new Vec3(0, 0, 0),
                    new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(1, 0, 0),
                    dv[i], 1, 0));
            }
            check(SpeedStrip.fitHi(10, tv, ss, i -> i, 0, 1) == 20,
                "f1 the design speed raises the fit (window [0,1] → 20)");
            check(SpeedStrip.fitHi(10, tv, ss, i -> i, 1, 1) == 10,
                "f2 window clamp: [1,1] → the true 10 (design 3 below)");
            check(SpeedStrip.fitHi(10, tv, ss, i -> i, 0, 2) == 30,
                "f3 whole window → 30");
            check(SpeedStrip.fitHi(10, new double[]{ 5 }, ss, i -> i, 0, 2) == 20,
                "f4 length mismatch reads to the SHORTER array (trueV 1 long → 20)");
            check(SpeedStrip.fitHi(10, null, ss, i -> i, 0, 2) == 10
                && SpeedStrip.fitHi(10, tv, null, i -> i, 0, 2) == 10,
                "f5 null inputs leave vHi unchanged");
            check(SpeedStrip.fitHi(50, tv, ss, i -> i, 0, 2) == 50,
                "f6 never lowers vHi");
        }

        // ================= (g) hover suffix =================
        check(SpeedStrip.designSuffix(10.5).equals(
                String.format(" (design %.2f)", 10.5))
            && SpeedStrip.designSuffix(10.5).endsWith("10.50)")
            && SpeedStrip.designSuffix(10.5).startsWith(" (design "),
            "g1 suffix = \" (design 10.50)\" — two decimals, no unit",
            "\"" + SpeedStrip.designSuffix(10.5) + "\"");
        check(!SpeedStrip.designSuffix(10.5).contains("m/s"),
            "g2 no unit on the suffix");

        // ================= (h) styles =================
        check(Math.abs(SpeedStrip.DESIGN_W * 1.5 - 1.2) < 1e-12,
            "h1 design width = the old true width 1.2 / 1.5");
        check(SpeedStrip.TRUE_W > 1.2,
            "h2 true line wider than before");
        check(SpeedStrip.DESIGN_DASH.length == 2 && SpeedStrip.TRUE_DASH.length == 2
            && SpeedStrip.DESIGN_DASH[0] * 1.5 == SpeedStrip.TRUE_DASH[0]
            && SpeedStrip.DESIGN_DASH[1] * 1.5 == SpeedStrip.TRUE_DASH[1],
            "h3 design dashes = true dashes / 1.5 (1.5x smaller, 1.5x denser)",
            Arrays.toString(SpeedStrip.DESIGN_DASH));
        check(SpeedStrip.TRUE_DASH[0] == 3 && SpeedStrip.TRUE_DASH[1] == 4,
            "h4 true dashes keep the historic (3, 4)");
        check(SpeedStrip.TRUE_RGB[0] > 100 && SpeedStrip.TRUE_RGB[1] > 220
            && SpeedStrip.TRUE_RGB[2] > 110
            && SpeedStrip.TRUE_RGB[0] <= 255 && SpeedStrip.TRUE_RGB[1] <= 255
            && SpeedStrip.TRUE_RGB[2] <= 255,
            "h5 true RGB brighter per channel than SP_SPEED_COLOR (100,220,110)",
            Arrays.toString(SpeedStrip.TRUE_RGB));

        // ================= (s1) snapDisplay == the native snap =================
        {
            boolean okBits = true;
            Random rs1 = new Random(2491);
            for (int i = 0; i < 500; i++) {
                double nx = rs1.nextDouble() * 40 - 5;
                double st = new double[]{ 0.1, 1.0, 0.01, 0.5 }[i % 4];
                double hist = Math.round(nx / st) * st;   // spDragDiamondNative's literal snap
                if (SpeedStrip.snapDisplay(nx, 0, st) != hist) okBits = false;
            }
            check(okBits, "s1 snapDisplay(x, 0, step) == round(x/step)·step bitwise (500 random)");
            check(SpeedStrip.snapDisplay(7.3, 5.0, 1.0) == 7.0
                && SpeedStrip.snapDisplay(7.6, 5.0, 1.0) == 8.0
                && SpeedStrip.snapDisplay(5.049, 5.0, 0.1) == 5.0,
                "s1 the section-local anchor a0 shifts the grid (5 + k·step)");
            check(SpeedStrip.snapDisplay(7.3, 5.0, 0) == 7.3
                && SpeedStrip.snapDisplay(7.3, 5.0, -1) == 7.3,
                "s1 step ≤ 0 = untouched");
        }

        // ================= (s2) display-axis snap round trip =================
        {
            Coaster co = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection ft = new ForceSection(hillLanes(4, 1.2), 26);   // TIME native
            ForceSection fd = new ForceSection(hillLanes(60, 0.5), 22);  // DISTANCE native
            fd.domain(Section.Domain.DISTANCE);
            p.sections.add(ft);
            p.sections.add(fd);
            co.pieces.add(p);
            co.v0 = 26;
            TrackCompiler.Result r = rebuild(co);
            List<TrackCompiler.Span> spans = r.spans();
            check(spans.size() == 2, "s2 fixture compiles (TIME + DISTANCE spans)");
            // TIME-native viewed in METERS: snap on the 1 m display grid
            {
                double[][] map = Coaster.sectionMap(r.samples(), spans.get(0), false);
                double a0 = Coaster.mapCol(map, 0, 1, 0);   // the editor's spAxisOf(rs, 0) − t0
                check(a0 == 0.0, "s2 the TIME span's map origin is exactly 0 (a0)", String.valueOf(a0));
                boolean okGrid = true, okRt = true;
                double worst = 0;
                for (double nx : new double[]{ 0.37, 1.234, 2.0, 3.111, 3.9 }) {
                    double a = Coaster.mapCol(map, 0, 1, nx);            // native t → display s
                    double g = SpeedStrip.snapDisplay(a, a0, 1.0);        // the 1 m grid
                    double back = Coaster.mapCol(map, 1, 0, g);           // display s → native t
                    double fwd = Coaster.mapCol(map, 0, 1, back);         // what the strip draws
                    worst = Math.max(worst, Math.abs(fwd - g));
                    if (Math.abs(fwd - g) > 1e-9) okRt = false;
                    double k = (g - a0) / 1.0;
                    if (Math.abs(k - Math.rint(k)) > 1e-9) okGrid = false;
                    if (Math.abs(a - g) > 0.5 + 1e-9) okGrid = false;     // the nearest cell
                }
                check(okGrid, "s2 TIME-native viewed in meters: the snapped display position sits on the 1 m grid, nearest cell");
                check(okRt, "s2 …and the written native x maps forward back onto it (≤ 1e-9)", "worst " + worst);
            }
            // DISTANCE-native viewed in SECONDS: snap on the 0.1 s display grid
            {
                double[][] map = Coaster.sectionMap(r.samples(), spans.get(1), false);
                double a0 = Coaster.mapCol(map, 1, 0, 0);   // native s = 0 → display t
                check(a0 == 0.0, "s2 the DISTANCE span's map origin is exactly 0 (a0)", String.valueOf(a0));
                boolean okGrid = true, okRt = true;
                double worst = 0;
                for (double nx : new double[]{ 0.5, 7.77, 12.5, 30.03, 59.0 }) {
                    double a = Coaster.mapCol(map, 1, 0, nx);            // native s → display t
                    double g = SpeedStrip.snapDisplay(a, a0, 0.1);
                    double back = Coaster.mapCol(map, 0, 1, g);
                    double fwd = Coaster.mapCol(map, 1, 0, back);
                    worst = Math.max(worst, Math.abs(fwd - g));
                    if (Math.abs(fwd - g) > 1e-9) okRt = false;
                    double k = (g - a0) / 0.1;
                    if (Math.abs(k - Math.rint(k)) > 1e-9) okGrid = false;
                    if (Math.abs(a - g) > 0.05 + 1e-9) okGrid = false;
                }
                check(okGrid, "s2 DISTANCE-native viewed in seconds: snapped onto the 0.1 s grid, nearest cell");
                check(okRt, "s2 …round trip ≤ 1e-9", "worst " + worst);
            }
        }

        // ================= (s3) position readout formats =================
        {
            java.util.Locale was = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY);   // comma decimals
                check(SpeedStrip.posReadout(3.2, false).equals("t = 3.20 s"),
                    "s3 seconds: \"t = 3.20 s\" (two decimals, Locale.US under a comma locale)",
                    SpeedStrip.posReadout(3.2, false));
                check(SpeedStrip.posReadout(12.5, true).equals("s = 12.5 m"),
                    "s3 meters: \"s = 12.5 m\" (one decimal)", SpeedStrip.posReadout(12.5, true));
                check(SpeedStrip.posReadout(-0.0, false).equals("t = 0.00 s")
                    && SpeedStrip.posReadout(5e-7, true).equals("s = 0.0 m")
                    && SpeedStrip.posReadout(-5e-7, true).equals("s = 0.0 m"),
                    "s3 −0.0 and |v| < 1e-6 print as +0 (the z3 rule)");
                check(SpeedStrip.posReadout(-0.04, true).equals("s = -0.0 m")
                    && SpeedStrip.posReadout(-0.004, false).equals("t = -0.00 s"),
                    "s3 larger sub-display dust keeps its sign (D245)",
                    SpeedStrip.posReadout(-0.04, true));
                check(SpeedStrip.posValue(12.5, true).equals("12.5 m")
                    && SpeedStrip.posValue(3.2, false).equals("3.20 s")
                    && SpeedStrip.posReadout(7.25, false).equals("t = " + SpeedStrip.posValue(7.25, false)),
                    "s3 posValue = the readout without its head (the popup twins' \"= 12.5 m\")");
                check(SpeedStrip.posReadout(1234.5678, true).equals("s = 1234.6 m")
                    && SpeedStrip.posReadout(0.005, false).equals("t = 0.01 s"),
                    "s3 rounding: half-up at the displayed decimal, no grouping");
            } finally {
                java.util.Locale.setDefault(was);
            }
        }

        // ================= (s4) hover re-check: diamondAt =================
        {
            java.util.List<SpeedControl> ctls = new java.util.ArrayList<>();
            java.util.List<Double> marks = new java.util.ArrayList<>();
            ctls.add(new SpeedControl(2.0, 1.5));
            marks.add(6.25);
            check(SpeedStrip.diamondAt(ctls, marks, 2.0, 1e-9)
                && SpeedStrip.diamondAt(ctls, marks, 3.5, 1e-9)
                && SpeedStrip.diamondAt(ctls, marks, 6.25, 1e-9),
                "s4 diamondAt: the control's start, end and a mark hit");
            check(!SpeedStrip.diamondAt(ctls, marks, 2.7, 1e-9)
                && !SpeedStrip.diamondAt(ctls, marks, 3.5 + 1e-6, 1e-9),
                "s4 diamondAt: a point off every diamond misses (1e-6 off the end too)");
            ctls.clear();
            check(!SpeedStrip.diamondAt(ctls, marks, 2.0, 1e-9)
                && !SpeedStrip.diamondAt(null, null, 6.25, 1e-9),
                "s4 diamondAt: the undone control's diamonds are gone; null lists miss");
        }

        System.out.println();
        System.out.println("StripGate: " + pass + " passed, " + fail
            + " failed");
        if (fail > 0) System.exit(1);
    }
}
