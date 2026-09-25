import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** D196 gate: 1-node lanes (splitLanes' hold branch) must LOAD.
 *
 *  a) the user's real file (two 1-node yaw lanes from section
 *     splits) loads, compiles, and round-trips save→load→save
 *     byte-identically;
 *  b) the creation path reproduced from scratch: split a section
 *     past a lane's last node → save emits a 1-node lane → loads;
 *  c) an EMPTY lane block still throws (corruption guard kept).
 */
public class LoadGate196 {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

    static DesignIO.LoadResult load(String text) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(text)));
    }

    static String save(Coaster c, DesignIO.Cosmetics cos) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        return sw.toString();
    }

    public static void main(String[] args) throws Exception {

        // ---- a) the user's real file ----
        Path fvd = Paths.get("C:/Users/jacks/OneDrive/Documents/AI Fun/FULL TEST COASTER 7.30.26.fvd");
        String orig = new String(Files.readAllBytes(fvd), StandardCharsets.UTF_8);

        DesignIO.LoadResult lr;
        try {
            lr = load(orig);
        } catch (IOException ex) {
            check("user file loads", false, ex.getMessage());
            summary(); return;
        }
        Coaster c = lr.coaster();
        List<Section> secs = c.pieces.get(0).sections;
        check("user file loads", true, secs.size() + " sections");

        int oneNode = 0;
        for (Section s : secs)
            if (s instanceof LaneSection ls)
                for (List<BezierBaker.Node> lane : ls.lanes)
                    if (lane.size() == 1) oneNode++;
        check("the two 1-node lanes survive as-is", oneNode == 2
                && ((LaneSection) secs.get(2)).lanes.get(1).size() == 1
                && ((LaneSection) secs.get(3)).lanes.get(1).size() == 1,
              "1-node lanes: " + oneNode);

        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        double len = r.samples().get(r.samples().size() - 1).s();
        check("compiles", r.samples().size() > 100 && len > 50,
              String.format("%d samples, %.3f m", r.samples().size(), len));

        String s1 = save(c, lr.cosmetics());
        Coaster c2 = load(s1).coaster();
        String s2 = save(c2, lr.cosmetics());
        check("canonical round-trip (save→load→save)", s1.equals(s2), "");
        String origLf = orig.replace("\r\n", "\n"), s1lf = s1.replace("\r\n", "\n");
        System.out.println("INFO  re-save matches the on-disk file (modulo EOL): " + origLf.equals(s1lf));

        // ---- b) creation path from scratch ----
        String mini = String.join("\n",
            "FVDD3", "v0 5.0",
            "piece 0 21 0 0",
            "section force 26.0 time 0",
            "lane 0",
            "n 0 1 0.3333 0 0.3333 0 1 0 0",
            "n 10 1 0.3333 0 0.3333 0 1 0 0",
            "lane 1",
            "n 0 0 0.3333 0 0.3333 0 1 0 0",
            "n 10 0 0.3333 0 0.3333 0 1 0 0",
            "lane 2",
            "n 0 0 0.3333 0 0.3333 0 1 0 0",
            "n 2 0 0.3333 0 0.3333 0 1 0 0",   // ends at 2 s: held after
            "endsection", "endpiece", "");
        DesignIO.LoadResult mlr = load(mini);
        Coaster mc = mlr.coaster();
        boolean split = mc.splitSectionAt(0, 0, 5.0, 0.01);   // cut past lane 2's end
        LaneSection right = (LaneSection) mc.pieces.get(0).sections.get(1);
        check("splitSectionAt past a lane end emits the 1-node hold half",
              split && right.lanes.get(2).size() == 1,
              "right lane 2 nodes: " + right.lanes.get(2).size());

        String msave = save(mc, mlr.cosmetics());
        try {
            Coaster mc2 = load(msave).coaster();
            LaneSection r2 = (LaneSection) mc2.pieces.get(0).sections.get(1);
            check("the split save LOADS (pre-fix: refused)", r2.lanes.get(2).size() == 1, "");
        } catch (IOException ex) {
            check("the split save LOADS (pre-fix: refused)", false, ex.getMessage());
        }

        // ---- c) empty lane HEALS with a warning (v18.44, D216 —
        // RE-BASELINED: the fail-soft loader heals an empty block to
        // a 1-node held constant and reports it, instead of refusing
        // the whole file; the load must succeed, warn, and hold) ----
        String broken = String.join("\n",
            "FVDD3", "v0 5.0",
            "piece 0 21 0 0",
            "section force 26.0 time 0",
            "lane 0",
            "n 0 1 0.3333 0 0.3333 0 1 0 0",
            "n 10 1 0.3333 0 0.3333 0 1 0 0",
            "lane 1",
            "lane 2",
            "n 0 0 0.3333 0 0.3333 0 1 0 0",
            "n 10 0 0.3333 0 0.3333 0 1 0 0",
            "endsection", "endpiece", "");
        try {
            DesignIO.LoadResult blr = load(broken);
            LaneSection bsec = (LaneSection)
                blr.coaster().pieces.get(0).sections.get(0);
            check("EMPTY lane heals + warns (was: refused)",
                blr.warnings() > 0 && bsec.lanes.get(1).size() == 1,
                "warnings=" + blr.warnings()
                + " lane1 nodes=" + bsec.lanes.get(1).size());
        } catch (IOException ex) {
            check("EMPTY lane heals + warns (was: refused)", false,
                "still throws: " + ex.getMessage());
        }

        summary();
    }

    static void summary() {
        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
