import uvd.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Round-2 shakedown probe (v18.38-v18.45): camera line IO, the
 * fail-soft loader (D216), and the accel/decel split (D217).
 */
public class R2Probe {

    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static Coaster flatDoc(double dur, double v0) {
        List<List<BezierBaker.Node>> nl = new ArrayList<>();
        nl.add(nodes(new double[][]{ {0, 1}, {dur, 1} }));
        nl.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        nl.add(nodes(new double[][]{ {0, 0}, {dur, 0} }));
        return Coaster.singleForce(nl, v0, 0, 21, 0);
    }

    static String save(Coaster c, double[] cam) {
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics def = DesignIO.Cosmetics.defaults();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(def.heart(),
            def.gauge(), def.tiespace(), def.railSolid(), def.railColor(),
            def.gradColors(), def.gradStops(), def.latColors(),
            def.latStops(), cam);
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        return sw.toString();
    }

    static DesignIO.LoadResult load(String text) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(text)));
    }

    static double arcWhereBelow(TrackCompiler.Result r, double v) {
        for (FVDIntegrator.Sample s : r.samples()) {
            if (s.v() <= v) return s.s();
        }
        return Double.MAX_VALUE;
    }

    static boolean bitEqualV(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return false;
        for (int i = 0; i < a.samples().size(); i++) {
            if (Double.doubleToLongBits(a.samples().get(i).v())
                    != Double.doubleToLongBits(b.samples().get(i).v())
                || Double.doubleToLongBits(a.samples().get(i).pos().y())
                    != Double.doubleToLongBits(b.samples().get(i).pos().y())) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        double dt = 0.01;

        // ---- A) camera line round-trips; missing = null ----
        {
            Coaster c = flatDoc(2, 10);
            double[] cam = { 12.5, 5.25, -3.75, -30, -20, 35 };
            String s1 = save(c, cam);
            DesignIO.LoadResult lr = load(s1);
            double worst = 0;
            for (int k = 0; k < 6; k++) {
                worst = Math.max(worst,
                    Math.abs(lr.cosmetics().camera()[k] - cam[k]));
            }
            check(lr.cosmetics().camera() != null && worst < 1e-3,
                "camera line round-trips (worst " + worst + ")");
            check(save(lr.coaster(), lr.cosmetics().camera()).equals(s1),
                "camera save->load->save byte-identical");
            DesignIO.LoadResult lr0 = load(save(c, null));
            check(lr0.cosmetics().camera() == null,
                "no camera line = null (old files: view stays put)");
        }

        // ---- B) fail-soft loader (D216) ----
        {
            Coaster c = flatDoc(2, 10);
            String good = save(c, null);
            // truncate the LAST `n` line mid-token
            int cut = good.lastIndexOf("\nn ");
            int lineEnd = good.indexOf('\n', cut + 1);
            String broken = good.substring(0, cut + 8)
                + good.substring(lineEnd);
            DesignIO.LoadResult lr = load(broken);
            check(lr.warnings() > 0 && !lr.coaster().pieces.isEmpty(),
                "truncated node line: loads with warnings ("
                + lr.warnings() + ")");
            // BOM before the header
            DesignIO.LoadResult lb = load("﻿" + good);
            check(lb.warnings() == 0 && !lb.coaster().pieces.isEmpty(),
                "UTF-8 BOM header loads clean");
            // header-only file: clean refusal BEFORE any mutation
            boolean threw = false;
            try { load("FVDD3\nv0 10\n"); } catch (IOException ex) {
                threw = ex.getMessage().contains("no track data");
            }
            check(threw, "header-only file: clean 'no track data' refusal");
            // a >1000 m/s design speed round-trips (the reconcile is
            // uncapped; the old load clamp silently rewrote it)
            ((LaneSection) c.pieces.get(0).sections.get(0))
                .vDesign(1085.4761425086938);
            DesignIO.LoadResult lv = load(save(c, null));
            check(((LaneSection) lv.coaster().pieces.get(0).sections
                    .get(0)).vDesign() == 1085.4761425086938,
                "vDesign 1085.476... admits bitwise (was clamped to 1000)");
            // truncated cosmetic railcolor line no longer kills the file
            String rc = good.replaceFirst("railcolor [^\n]*", "railcolor 0.5 0.5");
            DesignIO.LoadResult lc2 = load(rc);
            check(!lc2.coaster().pieces.isEmpty(),
                "truncated railcolor line: file still loads ("
                + lc2.warnings() + " warnings)");
        }

        // ---- C) ACCEL/DECEL split (D217) ----
        {
            // C1: tied ignores the decel field entirely (bitwise)
            Coaster a = flatDoc(20, 30);
            Section sa = a.pieces.get(0).sections.get(0);
            SpeedControl ca = new SpeedControl(0, 500);
            ca.type = SpeedControl.ACCEL;
            ca.speed = 15;
            ca.accel = 0.3;
            ca.decel = 7.77;   // garbage — tied must ignore it
            ca.tied = true;
            sa.speedControls().add(ca);
            Coaster b = flatDoc(20, 30);
            Section sb = b.pieces.get(0).sections.get(0);
            SpeedControl cb = new SpeedControl(0, 500);
            cb.type = SpeedControl.ACCEL;
            cb.speed = 15;
            cb.accel = 0.3;
            cb.decel = 0.3;
            cb.tied = true;
            sb.speedControls().add(cb);
            check(bitEqualV(TrackCompiler.compile(a, dt),
                            TrackCompiler.compile(b, dt)),
                "tied control ignores the decel field BITWISE");

            // C2: untied brakes at its OWN felt G — 1.2 g reaches the
            // target ~4x sooner than 0.3 g from 30 -> 15 m/s
            Coaster u = flatDoc(20, 30);
            SpeedControl cu = new SpeedControl(0, 500);
            cu.type = SpeedControl.ACCEL;
            cu.speed = 15;
            cu.accel = 0.3;
            cu.decel = 1.2;
            cu.tied = false;
            u.pieces.get(0).sections.get(0).speedControls().add(cu);
            double sTied = arcWhereBelow(TrackCompiler.compile(a, dt), 15.01);
            double sUntied = arcWhereBelow(TrackCompiler.compile(u, dt), 15.01);
            double expTied = (30 * 30 - 15 * 15) / (2 * 9.81 * 0.3);
            double expUntied = (30 * 30 - 15 * 15) / (2 * 9.81 * 1.2);
            check(Math.abs(sTied - expTied) < 2
                    && Math.abs(sUntied - expUntied) < 2,
                String.format("untied decel brakes at its own G: tied "
                    + "%.1f m (theory %.1f), untied %.1f m (theory %.1f)",
                    sTied, expTied, sUntied, expUntied));

            // C3: untied COMPLEX with flat graphs == the constants
            Coaster uc = flatDoc(20, 30);
            SpeedControl cc = new SpeedControl(0, 500);
            cc.type = SpeedControl.ACCEL;
            cc.speed = 15;
            cc.accel = 0.3;
            cc.decel = 1.2;
            cc.tied = false;
            cc.complex = true;
            cc.freshLane(0.3);
            cc.freshDecelLane(1.2);
            uc.pieces.get(0).sections.get(0).speedControls().add(cc);
            // flat complex == constant to ULP dust (exactAt's
            // weighted Bezier sum is within an ulp of the constant,
            // deliberately not bit-compared)
            TrackCompiler.Result ru = TrackCompiler.compile(u, dt);
            TrackCompiler.Result rc = TrackCompiler.compile(uc, dt);
            double dv = Double.MAX_VALUE;
            if (ru.samples().size() == rc.samples().size()) {
                dv = 0;
                for (int i = 0; i < ru.samples().size(); i++) {
                    dv = Math.max(dv, Math.abs(ru.samples().get(i).v()
                        - rc.samples().get(i).v()));
                }
            }
            check(dv < 1e-6,
                "untied COMPLEX flat graphs == the constants (max dv "
                + dv + " m/s)");

            // C4: IO round-trip carries decel/tied/decelLane
            String s1 = save(uc, null);
            DesignIO.LoadResult lr = load(s1);
            SpeedControl lc = lr.coaster().pieces.get(0).sections.get(0)
                .speedControls().get(0);
            check(!lc.tied && lc.decel == 1.2
                    && lc.decelLane.size() == cc.decelLane.size()
                    && save(lr.coaster(), null).equals(s1),
                "untied complex control round-trips byte-identically");
            check(bitEqualV(TrackCompiler.compile(uc, dt),
                            TrackCompiler.compile(lr.coaster(), dt)),
                "reloaded untied control compiles BITWISE");

            // C5: portion() clips the decel graph with the control
            SpeedControl clip = cc.copy();
            clip.freshLane(0.3);
            clip.lane.get(0).val = 0.2;      // slope so clipping matters
            clip.decelLane.get(0).val = 0.8;
            List<SpeedControl> part = SpeedControl.portion(
                List.of(clip), 100, 400);
            SpeedControl pc = part.get(0);
            double want = BezierBaker.exactAt(clip.decelLane, 250);
            double got = BezierBaker.exactAt(pc.decelLane, 150);
            check(part.size() == 1 && Math.abs(got - want) < 1e-9,
                "portion clips the decel graph exactly (" + got
                + " vs " + want + ")");
        }

        System.out.println(failures == 0 ? "ALL R2 CHECKS PASS"
            : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }
}
