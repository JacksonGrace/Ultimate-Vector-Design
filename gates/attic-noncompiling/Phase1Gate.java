import coaster.BezierBaker;
import coaster.Coaster;
import coaster.DesignIO;
import coaster.FVDIntegrator;
import coaster.ForceSection;
import coaster.Quat;
import coaster.TrackCompiler;
import coaster.TrackPiece;
import coaster.Vec3;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Phase 1 acceptance gates (SECTIONS-DESIGN §9 + D50):
 *  1. BIT-FOR-BIT: TrackCompiler on the default document == the old
 *     direct bake+integrate call, every sample field, raw double bits.
 *  2. Baseline invariants unchanged (1347 samples, 13.46 s, 406.878 m,
 *     pitch 0.2284°, U.y 0.999971).
 *  3. Pass D pass-through: trueV bitwise equals Sample.v today.
 *  4. FVDD3 canonical round-trip: save -> load -> save is byte-identical.
 *  5. FVDD2 + FVDD1 legacy files load correctly (one piece/one section).
 *  6. Piece independence: editing piece 1 leaves piece 2 bitwise alone.
 *  7. v0 change leaves ALL geometry bitwise alone (design-speed shape).
 *  8. Quat identity exactness + frame round-trip.
 */
public class Phase1Gate {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) { failures++; System.out.println("FAIL: " + what); }
    }

    static boolean bitEq(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    static boolean bitEq(Vec3 a, Vec3 b) {
        return bitEq(a.x(), b.x()) && bitEq(a.y(), b.y()) && bitEq(a.z(), b.z());
    }

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> defaultLanes() {
        List<List<BezierBaker.Node>> lanes = new ArrayList<>();
        lanes.add(nodes(new double[][]{
            {0,1},{0.5,1},{0.7,2.5},{0.96,2.5},{1.16,0},{3.25,0},{3.45,2.6},
            {4.36,2.6},{4.56,1},{5.56,1},{6.36,1.14},{7.16,1.28},{10.36,1.28},
            {11.16,1.14},{11.96,1},{13.46,1}}));
        lanes.add(nodes(new double[][]{ {0,0},{13.46,0} }));
        lanes.add(nodes(new double[][]{
            {0,0},{5.56,0},{6.36,50},{7.16,0},{10.36,0},{11.16,-50},{11.96,0},{13.46,0}}));
        return lanes;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.GERMANY);   // comma-decimal trap stays covered

        // ---- 1+2+3: bit-for-bit against the old direct pipeline ----
        List<List<BezierBaker.Node>> lanes = defaultLanes();
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        opts.pos0 = new Vec3(0, 21, 0);
        opts.startHeading = new Vec3(1, 0, 0);
        List<FVDIntegrator.Sample> old =
            FVDIntegrator.integrateTrack(BezierBaker.bake(lanes, 0.05), opts);

        Coaster doc = Coaster.singleForce(lanes, 26, 0, 21, 0);
        TrackCompiler.Result res = TrackCompiler.compile(doc, 0.01);
        List<FVDIntegrator.Sample> neu = res.samples();

        check(old.size() == neu.size(), "sample count " + old.size() + " vs " + neu.size());
        int mismatches = 0;
        for (int i = 0; i < Math.min(old.size(), neu.size()); i++) {
            FVDIntegrator.Sample a = old.get(i), b = neu.get(i);
            boolean eq = bitEq(a.t(), b.t()) && bitEq(a.s(), b.s())
                && bitEq(a.pos(), b.pos()) && bitEq(a.T(), b.T())
                && bitEq(a.U(), b.U()) && bitEq(a.R(), b.R())
                && bitEq(a.v(), b.v()) && bitEq(a.Gv(), b.Gv()) && bitEq(a.Gl(), b.Gl());
            if (!eq && mismatches++ < 3)
                check(false, "sample " + i + " not bit-identical");
        }
        check(mismatches == 0, "bit-for-bit total mismatches: " + mismatches);

        FVDIntegrator.Sample end = neu.get(neu.size() - 1);
        double pitch = Math.toDegrees(Math.asin(end.T().y()));
        check(neu.size() == 1347, "baseline sample count 1347: " + neu.size());
        check(Math.abs(end.t() - 13.46) < 1e-9, "baseline ride time");
        check(Math.abs(end.s() - 406.878) < 5e-4, "baseline length: " + String.format(Locale.US, "%.4f", end.s()));
        check(Math.abs(pitch - 0.2284) < 5e-4, "baseline end pitch: " + String.format(Locale.US, "%.4f", pitch));
        check(end.U().y() > 0.99997, "baseline U.y");
        double[] trueV = res.trueV();
        boolean vPass = trueV.length == neu.size();
        for (int i = 0; vPass && i < trueV.length; i++) vPass = bitEq(trueV[i], neu.get(i).v());
        check(vPass, "Pass D pass-through: trueV == Sample.v bitwise");

        // ---- 4: FVDD3 canonical round-trip ----
        Coaster c1 = Coaster.singleForce(defaultLanes(), 31, 3, 25, -7);
        c1.pieces.get(0).yawDeg = 15;
        ForceSection s1 = c1.soleSection();
        s1.lanes.get(0).get(3).smooth = false;
        s1.lanes.get(0).get(3).outDv = -0.775;
        s1.lanes.get(0).get(2).straightOut = true;
        s1.vDesign = 31;
        c1.speedRegions.add(new Coaster.SpeedRegion(12.5, 30, "constv", 14));
        c1.pieces.get(0).sections.add(Coaster.continuation(s1, 24.5));   // 2-section doc
        DesignIO.Cosmetics cos1 = new DesignIO.Cosmetics(1.3, 1.1, 0.8, true,
            new double[]{ 0.9, 0.2, 0.1 },
            new double[]{ 0.1,0.2,0.3, 0.4,0.5,0.6, 0.7,0.8,0.9, 0.15,0.25,0.35 },
            new double[]{ -0.5, 0.5, 2.5, 4.5 });

        String round1 = saveToString(c1, cos1);
        DesignIO.LoadResult lr = DesignIO.load(new BufferedReader(new StringReader(round1)));
        String round2 = saveToString(lr.coaster(), lr.cosmetics());
        check(round1.equals(round2), "FVDD3 canonical round-trip (save->load->save identical)");
        check(round1.startsWith("FVDD3"), "FVDD3 header written");
        check(lr.coaster().pieces.get(0).yawDeg == 15.0, "yaw survives");
        check(((ForceSection) lr.coaster().pieces.get(0).sections.get(0))
                .lanes.get(0).get(2).straightOut, "straight-lock survives");
        check(!((ForceSection) lr.coaster().pieces.get(0).sections.get(0))
                .lanes.get(0).get(3).smooth, "broken node survives");
        check(lr.coaster().speedRegions.size() == 1
                && lr.coaster().speedRegions.get(0).mode().equals("constv"), "speed region survives");
        check(lr.cosmetics().railSolid() && Math.abs(lr.cosmetics().gradStops()[0] + 0.5) < 1e-9,
            "cosmetics survive");

        // ---- 5: FVDD2 legacy (the exact shape the old writer produced) ----
        String fvdd2 = """
            FVDD2
            heart 1.2000
            gauge 0.9000
            tiespace 1.5000
            start 5.0000 30.0000 -2.0000
            v0 22.0000
            railmode solid
            railcolor 0.8000 0.3000 0.2000
            gradcolors 0.2 0.4 1.0 0.2 0.9 0.3 1.0 0.9 0.1 1.0 0.15 0.1
            gradstops 0 1 3 5
            lane 0
            n 0.0000 1.0000 0.3333 0.0000 0.3333 0.0000 1
            n 2.0000 2.5000 0.3333 0.1000 0.4200 -0.2000 1 1
            lane 1
            n 0.0000 0.0000 0.3333 0.0000 0.3333 0.0000 1
            n 2.0000 0.0000 0.3333 0.0000 0.3333 0.0000 0
            lane 2
            n 0.0000 0.0000 0.3333 0.0000 0.3333 0.0000 1
            n 2.0000 40.0000 0.3333 0.0000 0.3333 0.0000 1
            """;
        DesignIO.LoadResult l2 = DesignIO.load(new BufferedReader(new StringReader(fvdd2)));
        TrackPiece p2 = l2.coaster().pieces.get(0);
        ForceSection f2 = (ForceSection) p2.sections.get(0);
        check(l2.coaster().pieces.size() == 1 && p2.sections.size() == 1, "FVDD2 -> 1 piece/1 section");
        check(p2.x == 5 && p2.y == 30 && p2.z == -2 && p2.yawDeg == 0, "FVDD2 start -> placement");
        check(l2.coaster().v0 == 22 && f2.vDesign == 22, "FVDD2 v0 -> v0 + vDesign");
        check(f2.lanes.get(0).get(1).straightOut, "FVDD2 8th field read");
        check(!f2.lanes.get(1).get(1).smooth, "FVDD2 smooth flag read");
        check(Math.abs(l2.cosmetics().heart() - 1.2) < 1e-9, "FVDD2 cosmetics read");
        // no start/v0 lines -> defaults (0,21,0) and 26
        String fvdd2min = """
            FVDD2
            lane 0
            n 0.0000 1.0000 0.3333 0.0000 0.3333 0.0000 1
            n 2.0000 1.0000 0.3333 0.0000 0.3333 0.0000 1
            lane 1
            n 0.0000 0.0000 0.3333 0.0000 0.3333 0.0000 1
            n 2.0000 0.0000 0.3333 0.0000 0.3333 0.0000 1
            lane 2
            n 0.0000 0.0000 0.3333 0.0000 0.3333 0.0000 1
            n 2.0000 0.0000 0.3333 0.0000 0.3333 0.0000 1
            """;
        DesignIO.LoadResult l2m = DesignIO.load(new BufferedReader(new StringReader(fvdd2min)));
        check(l2m.coaster().pieces.get(0).y == 21 && l2m.coaster().v0 == 26,
            "FVDD2 defaults: start (0,21,0), v0 26");

        // ---- FVDD1 legacy: absolute-second handles convert to fractions ----
        String fvdd1 = """
            FVDD1
            lane 0
            n 0.0000 1.0000 0.5000 0.0000 -0.5000 0.0000 1
            n 2.0000 3.0000 0.5000 0.0000 -0.5000 0.1000 1
            lane 1
            n 0.0000 0.0000 0.6000 0.0000 -0.1000 0.0000 1
            n 2.0000 0.0000 0.6000 0.0000 -0.1000 0.0000 1
            lane 2
            n 0.0000 0.0000 0.5000 0.0000 -0.5000 0.0000 1
            n 2.0000 0.0000 0.5000 0.0000 -0.5000 0.0000 1
            """;
        DesignIO.LoadResult l1 = DesignIO.load(new BufferedReader(new StringReader(fvdd1)));
        ForceSection f1 = (ForceSection) l1.coaster().pieces.get(0).sections.get(0);
        check(Math.abs(f1.lanes.get(0).get(0).outFrac - 0.25) < 1e-9,
            "FVDD1 out 0.5s over 2s segment -> frac 0.25");
        check(Math.abs(f1.lanes.get(0).get(1).inFrac - 0.25) < 1e-9,
            "FVDD1 in -0.5s -> frac 0.25");
        check(Math.abs(f1.lanes.get(1).get(0).outFrac - 0.30) < 1e-9,
            "FVDD1 out 0.6s -> frac 0.30");

        // ---- 6: piece independence (connect invariance across pieces) ----
        Coaster two = new Coaster();
        two.v0 = 26;
        TrackPiece pa = new TrackPiece(0, 21, 0, 0);
        pa.sections.add(new ForceSection(defaultLanes(), 26));
        TrackPiece pb = new TrackPiece(120, 35, 60, 40);
        List<List<BezierBaker.Node>> lanesB = new ArrayList<>();
        lanesB.add(nodes(new double[][]{ {0,1},{1,3},{2.5,-0.5},{4,1} }));
        lanesB.add(nodes(new double[][]{ {0,0},{2,0.4},{4,0} }));
        lanesB.add(nodes(new double[][]{ {0,0},{1.5,35},{3,0},{4,0} }));
        pb.sections.add(new ForceSection(lanesB, 19));
        two.pieces.add(pa);
        two.pieces.add(pb);

        List<FVDIntegrator.Sample> before = TrackCompiler.compile(two, 0.01).samples();
        // locate piece B's tail: piece A alone has this many samples
        Coaster onlyA = new Coaster();
        onlyA.pieces.add(pa);
        int aCount = TrackCompiler.compile(onlyA, 0.01).samples().size();
        // edit piece A hard: add a hill node
        List<BezierBaker.Node> gvA = ((ForceSection) pa.sections.get(0)).lanes.get(0);
        gvA.get(5).val = -0.8;
        List<FVDIntegrator.Sample> after = TrackCompiler.compile(two, 0.01).samples();
        check(before.size() == after.size(), "piece edit keeps sample counts");
        boolean tailSame = true;
        for (int i = aCount; i < before.size() && tailSame; i++) {
            FVDIntegrator.Sample a = before.get(i), b = after.get(i);
            // world-space GEOMETRY bitwise; t/s rebase offsets shift with
            // piece A's length, so compare the shape fields only
            tailSame = bitEq(a.pos(), b.pos()) && bitEq(a.T(), b.T())
                    && bitEq(a.U(), b.U()) && bitEq(a.v(), b.v());
        }
        check(tailSame, "editing piece A leaves piece B's world geometry bitwise unchanged");
        boolean headChanged = false;
        for (int i = 0; i < aCount && !headChanged; i++)
            headChanged = !bitEq(before.get(i).pos(), after.get(i).pos());
        check(headChanged, "sanity: the edit really changed piece A");

        // ---- 7: v0 change leaves geometry alone (shape from vDesign only) ----
        List<FVDIntegrator.Sample> v26 = TrackCompiler.compile(two, 0.01).samples();
        two.v0 = 55;
        TrackCompiler.Result r55 = TrackCompiler.compile(two, 0.01);
        boolean geoSame = v26.size() == r55.samples().size();
        for (int i = 0; geoSame && i < v26.size(); i++) {
            geoSame = bitEq(v26.get(i).pos(), r55.samples().get(i).pos())
                   && bitEq(v26.get(i).T(), r55.samples().get(i).T());
        }
        check(geoSame, "changing global v0 leaves all geometry bitwise unchanged");
        check(bitEq(r55.trueV()[0], 55.0), "trueV re-derives from the new v0");

        // ---- 8: Quat exactness ----
        Vec3 vx = new Vec3(1, 0, 0), vy = new Vec3(0, 1, 0);
        check(bitEq(Quat.IDENTITY.rotate(vx), vx) && bitEq(Quat.IDENTITY.rotate(vy), vy),
            "identity rotate is float-exact");
        check(bitEq(Quat.aboutY(0).w(), 1.0) && bitEq(Quat.aboutY(0).x(), 0.0)
            && bitEq(Quat.aboutY(0).y(), 0.0) && bitEq(Quat.aboutY(0).z(), 0.0),
            "aboutY(0) is exactly identity");
        FVDIntegrator.Sample last = neu.get(neu.size() - 1);
        Quat q = Quat.fromFrame(last.T(), last.U());
        check(q.T().sub(last.T()).length() < 1e-12 && q.U().sub(last.U()).length() < 1e-12,
            "fromFrame/T()/U() round-trip < 1e-12");

        // ---- 9: continuation sections + in-piece chaining (D55) ----
        Coaster chain = Coaster.singleForce(defaultLanes(), 26, 0, 21, 0);
        ForceSection first = chain.soleSection();
        ForceSection cont = Coaster.continuation(first, 19.25);
        for (int L = 0; L < 3; L++) {
            List<BezierBaker.Node> src = first.lanes.get(L);
            check(cont.lanes.get(L).size() == 2, "continuation lane " + L + " has 2 nodes");
            check(cont.lanes.get(L).get(0).val == src.get(src.size() - 1).val
                    && cont.lanes.get(L).get(1).val == src.get(src.size() - 1).val,
                "continuation lane " + L + " holds the previous end value");
            check(cont.lanes.get(L).get(1).t == 1.0, "continuation is 1 s");
        }
        check(cont.vDesign == 19.25, "continuation keeps the given vDesign");
        chain.pieces.get(0).sections.add(cont);
        TrackCompiler.Result rc = TrackCompiler.compile(chain, 0.01);
        List<FVDIntegrator.Sample> cs = rc.samples();
        check(Math.abs(cs.get(cs.size() - 1).t() - (13.46 + 1.0)) < 0.02,
            "chained ride time ~ 14.46 s: " + cs.get(cs.size() - 1).t());
        boolean mono = true;
        double maxGap = 0;
        for (int i = 1; i < cs.size(); i++) {
            if (cs.get(i).t() <= cs.get(i - 1).t()) mono = false;
            maxGap = Math.max(maxGap, cs.get(i).pos().sub(cs.get(i - 1).pos()).length());
        }
        check(mono, "chained t strictly increasing across the boundary");
        check(maxGap < 0.5, "no position jump at the section boundary (max step "
            + String.format(Locale.US, "%.3f", maxGap) + " m)");
        // boundary frame continuity: the first appended sample's frame is one
        // integration step from section A's exit
        int secACount = TrackCompiler.compile(Coaster.singleForce(defaultLanes(), 26, 0, 21, 0), 0.01)
            .samples().size();
        FVDIntegrator.Sample exitA = cs.get(secACount - 1), entryB = cs.get(secACount);
        check(exitA.T().dot(entryB.T()) > 0.999 && exitA.U().dot(entryB.U()) > 0.999,
            "frame continuous across the boundary");
        // geometry unchanged when only the SECOND section's lanes are edited
        List<FVDIntegrator.Sample> headBefore = cs.subList(0, secACount);
        cont.lanes.get(0).get(1).val = 2.2;
        List<FVDIntegrator.Sample> cs2 = TrackCompiler.compile(chain, 0.01).samples();
        boolean headSame = true;
        for (int i = 0; i < secACount && headSame; i++) {
            headSame = bitEq(headBefore.get(i).pos(), cs2.get(i).pos());
        }
        check(headSame, "editing section B leaves section A bitwise unchanged");

        System.out.println(failures == 0 ? "ALL PHASE-1 GATES PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static String saveToString(Coaster c, DesignIO.Cosmetics cos) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            DesignIO.save(c, cos, pw);
        }
        return sw.toString();
    }
}
