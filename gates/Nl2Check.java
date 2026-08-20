import coaster.FVDIntegrator;
import coaster.Nl2CsvExporter;
import coaster.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Headless validation of Nl2CsvExporter (run against target/classes). */
public class Nl2Check {

    static int failures = 0;

    static void check(boolean ok, String what) {
        if (!ok) { failures++; System.out.println("FAIL: " + what); }
    }

    static List<FVDIntegrator.Sample> integrate(List<FVDIntegrator.Keyframe> kfs) {
        FVDIntegrator.Options opts = new FVDIntegrator.Options();
        opts.dt = 0.01;
        opts.v0 = 26;
        opts.startHeading = new Vec3(1, 0, 0);
        return FVDIntegrator.integrateTrack(kfs, opts);
    }

    record Row(Vec3 pos, Vec3 front, Vec3 left, Vec3 up) {}

    static List<Row> parse(String csv) {
        String[] lines = csv.split("\n");
        String expectHeader = "\"No.\"\t\"PosX\"\t\"PosY\"\t\"PosZ\"\t\"FrontX\"\t\"FrontY\"\t\"FrontZ\"\t"
            + "\"LeftX\"\t\"LeftY\"\t\"LeftZ\"\t\"UpX\"\t\"UpY\"\t\"UpZ\"";
        check(lines[0].equals(expectHeader), "header exact");
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t");
            check(f.length == 13, "13 fields on row " + i);
            check(Integer.parseInt(f[0]) == i, "row numbering at " + i);
            double[] v = new double[12];
            for (int j = 0; j < 12; j++) v[j] = Double.parseDouble(f[j + 1]);
            rows.add(new Row(new Vec3(v[0], v[1], v[2]), new Vec3(v[3], v[4], v[5]),
                             new Vec3(v[6], v[7], v[8]), new Vec3(v[9], v[10], v[11])));
        }
        return rows;
    }

    // tolerance 5e-6: values round-trip through %.6f text (±5e-7/component)
    static void checkFrames(List<Row> rows, String tag) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            check(Math.abs(r.front().length() - 1) < 5e-6, tag + " |front|=1 @" + i);
            check(Math.abs(r.left().length() - 1) < 5e-6, tag + " |left|=1 @" + i);
            check(Math.abs(r.up().length() - 1) < 5e-6, tag + " |up|=1 @" + i);
            check(Math.abs(r.front().dot(r.left())) < 5e-6, tag + " front.left=0 @" + i);
            check(Math.abs(r.front().dot(r.up())) < 5e-6, tag + " front.up=0 @" + i);
            check(Math.abs(r.left().dot(r.up())) < 5e-6, tag + " left.up=0 @" + i);
            Vec3 fxl = r.front().cross(r.left());
            check(fxl.sub(r.up()).length() < 5e-6, tag + " up=front x left @" + i);
        }
    }

    static double distToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.sub(a);
        double len2 = ab.dot(ab);
        double t = len2 < 1e-18 ? 0 : Math.max(0, Math.min(1, p.sub(a).dot(ab) / len2));
        return a.add(ab.scale(t)).sub(p).length();
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.GERMANY); // comma-decimal trap: output must stay Locale.US

        // ---- 1. straight flat line: 5 s at 26 m/s = 130 m ----
        List<FVDIntegrator.Keyframe> straight = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0, 0),
            new FVDIntegrator.Keyframe(5, 1, 0, 0));
        List<FVDIntegrator.Sample> s1 = integrate(straight);
        Nl2CsvExporter.Result r1 = Nl2CsvExporter.export(s1, true, 1.0, 1.0);
        List<Row> rows1 = parse(r1.csv());
        check(rows1.size() == r1.points(), "points field matches rows");
        check(rows1.size() == (int) Math.round(r1.length() / r1.step()) + 1, "count = nSeg+1");
        checkFrames(rows1, "straight");
        Row first = rows1.get(0);
        check(first.front().sub(new Vec3(1, 0, 0)).length() < 1e-6, "straight front=+X");
        check(first.up().sub(new Vec3(0, 1, 0)).length() < 1e-6, "straight up=+Y");
        check(first.left().sub(new Vec3(0, 0, -1)).length() < 1e-6, "straight left=-Z (mirrored)");
        double maxSpacingErr = 0;
        for (int i = 1; i < rows1.size(); i++) {
            double d = rows1.get(i).pos().sub(rows1.get(i - 1).pos()).length();
            maxSpacingErr = Math.max(maxSpacingErr, Math.abs(d - r1.step()));
        }
        check(maxSpacingErr < 1e-6, "straight uniform spacing (err " + maxSpacingErr + ")");
        System.out.printf(Locale.US, "straight: %d pts, len %.3f, step %.4f%n",
            r1.points(), r1.length(), r1.step());

        // ---- 2. positive Gl = right turn: clockwise from above in NL2 (f x f' has y<0) ----
        List<FVDIntegrator.Keyframe> turn = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0.5, 0),
            new FVDIntegrator.Keyframe(6, 1, 0.5, 0));
        List<FVDIntegrator.Sample> s2 = integrate(turn);
        // raw-world reference: T must move toward R (rider's right)
        Vec3 dT = s2.get(50).T().sub(s2.get(0).T());
        check(dT.dot(s2.get(0).R()) > 0, "raw: +Gl turns toward R");
        Nl2CsvExporter.Result r2 = Nl2CsvExporter.export(s2, true, 1.0, 1.0);
        List<Row> rows2 = parse(r2.csv());
        checkFrames(rows2, "turn");
        for (int i = 1; i < rows2.size(); i++) {
            double y = rows2.get(i - 1).front().cross(rows2.get(i).front()).y();
            check(y < 0, "turn: clockwise from above (right turn) @" + i);
        }

        // ---- 3. hilly + rolling layout: spine offset & arc-length independence ----
        List<FVDIntegrator.Keyframe> hilly = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0, 0),
            new FVDIntegrator.Keyframe(2, 3.5, 0, 0),
            new FVDIntegrator.Keyframe(4, -0.5, 0.3, 40),
            new FVDIntegrator.Keyframe(7, 2.0, -0.2, -30),
            new FVDIntegrator.Keyframe(10, 1, 0, 0));
        List<FVDIntegrator.Sample> s3 = integrate(hilly);
        double h = 1.1;
        Nl2CsvExporter.Result rHeart = Nl2CsvExporter.export(s3, true, h, 1.0);
        Nl2CsvExporter.Result rSpine = Nl2CsvExporter.export(s3, false, h, 1.0);
        List<Row> heartRows = parse(rHeart.csv());
        List<Row> spineRows = parse(rSpine.csv());
        checkFrames(heartRows, "hilly-heart");
        checkFrames(spineRows, "hilly-spine");
        // start is straight: spine sits exactly heartOffset below the heart
        Vec3 d0 = heartRows.get(0).pos().sub(spineRows.get(0).pos());
        check(d0.sub(new Vec3(0, h, 0)).length() < 1e-6, "spine = heart - U*h at start");
        // offset curve has its own length (must differ through hills)
        check(Math.abs(rHeart.length() - rSpine.length()) > 0.5,
            String.format(Locale.US, "offset curve resampled by own arc (heart %.2f vs spine %.2f)",
                rHeart.length(), rSpine.length()));
        // spine rows must sit ON the offset polyline (distance to segments,
        // not to sample points — points can be ~0.7 m apart at 67 m/s here)
        List<Vec3> spinePoly = new ArrayList<>();
        for (FVDIntegrator.Sample sm : s3) spinePoly.add(sm.pos().sub(sm.U().scale(h)));
        double worst = 0;
        for (Row row : spineRows) {
            Vec3 p = new Vec3(row.pos().x(), row.pos().y(), -row.pos().z()); // un-mirror
            double best = 1e9;
            for (int j = 0; j + 1 < spinePoly.size(); j++)
                best = Math.min(best, distToSegment(p, spinePoly.get(j), spinePoly.get(j + 1)));
            worst = Math.max(worst, best);
        }
        check(worst < 1e-5, "spine points on the offset polyline (worst dev " + worst + ")");
        // heart-line samples arc-length vs exporter total for heartline export
        double sTotal = s3.get(s3.size() - 1).s();
        check(Math.abs(rHeart.length() - sTotal) < 0.5,
            String.format(Locale.US, "heart export length %.2f ~ integrator s %.2f", rHeart.length(), sTotal));
        System.out.printf(Locale.US, "hilly: heart %.2f m / spine %.2f m, %d/%d pts%n",
            rHeart.length(), rSpine.length(), rHeart.points(), rSpine.points());

        // ---- 4. roll chirality: +roll rate tips the up vector toward the LEFT vector ----
        List<FVDIntegrator.Keyframe> roll = List.of(
            new FVDIntegrator.Keyframe(0, 1, 0, 50),
            new FVDIntegrator.Keyframe(3, 1, 0, 50));
        List<FVDIntegrator.Sample> s4 = integrate(roll);
        // raw reference: U tips toward -R
        Vec3 dU = s4.get(50).U().sub(s4.get(0).U());
        check(dU.dot(s4.get(0).R()) < 0, "raw: +roll tips U toward -R");
        List<Row> rows4 = parse(Nl2CsvExporter.export(s4, true, 1.0, 1.0).csv());
        checkFrames(rows4, "roll");
        Vec3 dUp = rows4.get(3).up().sub(rows4.get(0).up());
        check(dUp.dot(rows4.get(0).left()) > 0, "exported: +roll tips up toward left (same chirality)");

        // ---- 5. tiny segment lengths & clamping ----
        Nl2CsvExporter.Result rFine = Nl2CsvExporter.export(s1, true, 1.0, 0.1);
        check(Math.abs(rFine.step() - 0.1) < 0.01, "0.1 m spacing honored");
        Nl2CsvExporter.Result rCoarse = Nl2CsvExporter.export(s1, true, 1.0, 10.0);
        check(rCoarse.points() == (int) Math.round(rCoarse.length() / rCoarse.step()) + 1, "coarse count");
        check(parse(rCoarse.csv()).size() >= 2, "coarse still >= 2 points");

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
