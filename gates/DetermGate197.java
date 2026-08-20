import coaster.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** D197 gate — determinism of the rebuild pipeline on the user's
 *  FULL TEST COASTER:
 *   a) the app rebuild loop QUIESCES (cascade carry: few passes);
 *   b) IDEMPOTENCE: a second rebuild changes NOTHING (bitwise);
 *   c) save -> load -> rebuild reproduces the shape (%.10f writes);
 *   d) X/Z translation stays exactly rigid;
 *   e) a converged doc's reconcile writes nothing (historic path);
 *   f) two independent load+rebuild runs agree bitwise.
 */
public class DetermGate197 {

    static final String FILE =
        "C:/Users/jacks/OneDrive/Documents/AI Fun/FULL TEST COASTER 7.30.26.fvd";
    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

    static Coaster load(String text) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static String fileText() throws IOException {
        return new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);
    }

    /** The app's rebuild loop verbatim (v18.25 cap). Returns
     *  {result, passesUsed}. */
    static Object[] rebuild(Coaster c) {
        TrackCompiler.Result compiled = TrackCompiler.compile(c, DT);
        int used = 0;
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            used++;
            compiled = TrackCompiler.compile(c, DT);
        }
        // EXACT settle tail (v18.28, D200) - mirrors rebuildTrack's
        // at-rest behavior: land every reconciled speed ON its bits
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(compiled, true);
            if (c.autoReoptimizeBridges(compiled)) again = true;
            if (!again) break;
            used++;
            compiled = TrackCompiler.compile(c, DT);
        }
        return new Object[]{ compiled, used };
    }

    static double maxDelta(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return Double.MAX_VALUE;
        double mx = 0;
        for (int i = 0; i < a.samples().size(); i++)
            mx = Math.max(mx, a.samples().get(i).pos()
                .sub(b.samples().get(i).pos()).length());
        return mx;
    }

    public static void main(String[] args) throws Exception {
        String text = fileText();

        // a) quiescence with the cascade carry
        Coaster c = load(text);
        Object[] rb = rebuild(c);
        TrackCompiler.Result r1 = (TrackCompiler.Result) rb[0];
        int used = (Integer) rb[1];
        // the bound covers the D200 exact tail: first-open of a
        // pre-D200 file legitimately walks fast passes + the full
        // chain-depth of exact passes ONCE (caps are 12 + 40)
        check("rebuild quiesces well under the cap", used < 40,
            used + " reconcile passes (was 9+ with the stale read)");

        // b) idempotence: a second rebuild moves NOTHING
        Object[] rb2 = rebuild(c);
        TrackCompiler.Result r2 = (TrackCompiler.Result) rb2[0];
        check("second rebuild is a no-op (bitwise)",
            (Integer) rb2[1] == 0 && maxDelta(r1, r2) == 0.0,
            "passes=" + rb2[1] + " delta=" + maxDelta(r1, r2));

        // c) save -> load -> rebuild reproduces the shape
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3], null);
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        Coaster c2 = load(sw.toString());
        Object[] rb3 = rebuild(c2);
        double dReload = maxDelta(r1, (TrackCompiler.Result) rb3[0]);
        check("save->load->rebuild reproduces the shape", dReload < 1e-4,
            String.format(Locale.US, "delta %.3e m, %d passes on reload (was 0.5 m at %%.4f)",
                dReload, (Integer) rb3[1]));

        // d) X/Z translation rigid (write-back live, converged doc)
        Coaster c3 = load(sw.toString());
        rebuild(c3);
        TrackCompiler.Result rb4 = (TrackCompiler.Result) rebuild(c3)[0];
        c3.pieces.get(0).x += 100; c3.pieces.get(0).z -= 45;
        TrackCompiler.Result r5 = (TrackCompiler.Result) rebuild(c3)[0];
        Vec3 off = new Vec3(100, 0, -45);
        double mx = 0;
        int n = Math.min(r5.samples().size(), rb4.samples().size());
        for (int i = 0; i < n; i++)
            mx = Math.max(mx, r5.samples().get(i).pos().sub(off)
                .sub(rb4.samples().get(i).pos()).length());
        check("X/Z translate exactly rigid through the full rebuild", mx < 1e-9,
            String.format(Locale.US, "max %.3e m", mx));

        // e) converged doc: reconcile's write pass stays silent
        Coaster c4 = load(sw.toString());
        TrackCompiler.Result r6 = (TrackCompiler.Result) rebuild(c4)[0];
        double[] before = new double[64]; int bi = 0;
        for (TrackPiece p : c4.pieces) for (Section s : p.sections) before[bi++] = s.vDesign();
        boolean ch = c4.reconcileChainedSpeeds(r6);
        boolean same = true; bi = 0;
        for (TrackPiece p : c4.pieces) for (Section s : p.sections)
            if (before[bi++] != s.vDesign()) same = false;
        check("converged doc: reconcile writes nothing", !ch && same, "");

        // f) two independent load+rebuild runs agree bitwise
        Coaster cA = load(text), cB = load(text);
        double d = maxDelta((TrackCompiler.Result) rebuild(cA)[0],
                            (TrackCompiler.Result) rebuild(cB)[0]);
        check("independent runs agree bitwise", d == 0.0, "delta=" + d);

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
