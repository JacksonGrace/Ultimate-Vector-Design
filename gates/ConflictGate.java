package coaster;

import java.io.*;
import java.util.*;

/** D237 gate — the PHANTOM UNRESOLVABLE ≠v (the user's "Conflict not
 *  getting resolved 8.25.26.fvd", embedded below verbatim): a strict
 *  follower's FIRST section carries a small stale design speed
 *  (0.155 m/s at the seam — under DRIFT_EPS), the constant ENERGY
 *  offset amplifies to 0.357 m/s at the next section's slow crest
 *  (over DRIFT_EPS → badge), and the reconcile's real-anchor gate —
 *  which tested the SEAM gap against the same 0.2 — refused to
 *  recognize either resolve op's anchor and snapped it back every
 *  rebuild. Checks:
 *  a) the scenario reproduces (B badges, A does not);
 *  b) "Set Design to Real" (refreezeSpeed) on B STICKS through the
 *     rebuild loop — bitwise (the ops anchor at the reconcile's own
 *     seam sample now);
 *  c) "Convert Real to Design" (makeGraphsReal) on B sticks too;
 *  d) refreezing the SOURCE section (A) clears the whole chain. */
public class ConflictGate {

    static final double DT = 0.01;
    static final double EPS = 0.2;   // GraphEditor.DRIFT_EPS

    static final String FVD = String.join("\n",
        "FVDD4",
        "ncoasters 2",
        "coaster Coaster 1",
        "v0 20.0",
        "friction 0.03 1.0E-4",
        "piece 0.0 5.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 20.0 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "coaster Coaster 2",
        "v0 20.0",
        "friction 0.03 1.0E-4",
        "piece 0.0 10.0 0.0 -0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 20.0 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 3.550373831775701 1.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 5.0 1.8 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "sctl 0 0 4.12570093457944 0.2876635514018684 25.0 0.5 1 1 0.5 1",
        "endsection",
        "endpiece",
        "piece 101.00558685777081 12.525635131104872 0.0 0.0 1.0 0.0 -0.0 -2.069147755613188E-17 0.0 0.0 1 1 1 1 -0.0 -2.3710686717120474E-15 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 24.108558580194405 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "section force 23.757080736105365 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.39773554885220364 2.4489795918367347 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 0.7444678598607126 0.42857142857142905 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 1.0 1.0 0.3633802276324186 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.583550580343065 1.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 1.9835505803430649 3.1 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 4.030671555919328 3.1 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 2.1909574811102335 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "");

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        String tag = ok ? "PASS  " : "FAIL  ";
        if (ok) pass++; else fail++;
        System.out.println(tag + name + (detail.isEmpty()
            ? "" : "  (" + detail + ")"));
    }

    static Coaster load() throws Exception {
        return DesignIO.load(new BufferedReader(new StringReader(FVD)))
            .coasters().get(1);   // Coaster 2
    }

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

    /** GraphEditor.sectionDrift replicated: whole-span max split. */
    static double drift(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(),
            Math.min(r.trueV().length, r.samples().size()) - 1);
        double worst = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) the scenario reproduces ----
        Coaster c = load();
        TrackCompiler.Result r = rebuild(c);
        double dA = drift(r, 1), dB = drift(r, 2);
        check(dB > EPS && dA < EPS,
            "a1 amplified badge on B only (the phantom's shape)",
            String.format("A %.3f B %.3f", dA, dB));

        // ---- (b) Set Design to Real on B STICKS bitwise ----
        Coaster c2 = load();
        rebuild(c2);
        check(c2.refreezeSpeed(1, 1, DT), "b1 refreeze applies", "");
        TrackCompiler.Result r2 = rebuild(c2);
        double vAfter = c2.pieces.get(1).sections.get(1).vDesign();
        double dB2 = drift(r2, 2);
        check(dB2 < 0.05,
            "b2 the resolved conflict STAYS resolved",
            String.format("B drift %.5f (was 0.357, snapped back)", dB2));
        TrackCompiler.Result r3 = rebuild(c2);
        check(Double.doubleToLongBits(vAfter) == Double.doubleToLongBits(
                c2.pieces.get(1).sections.get(1).vDesign()),
            "b3 the anchor is a BITWISE fixed point", "");

        // ---- (c) Convert Real to Design on B sticks too ----
        Coaster c3 = load();
        rebuild(c3);
        check(c3.makeGraphsReal(1, 1, DT, 0.1, 0.003),
            "c1 make-real applies", "");
        TrackCompiler.Result r4 = rebuild(c3);
        double dB3 = drift(r4, 2);
        check(dB3 < 0.1,
            "c2 make-real's resolution survives the rebuild",
            String.format("B drift %.5f", dB3));

        // ---- (d) refreezing the SOURCE clears the whole chain ----
        Coaster c4 = load();
        rebuild(c4);
        check(c4.refreezeSpeed(1, 0, DT), "d1 source refreeze applies", "");
        TrackCompiler.Result r5 = rebuild(c4);
        check(drift(r5, 1) < 0.05 && drift(r5, 2) < 0.05,
            "d2 whole chain clean from the source",
            String.format("A %.5f B %.5f", drift(r5, 1), drift(r5, 2)));

        System.out.println();
        System.out.println(fail == 0
            ? "ALL CONFLICT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
