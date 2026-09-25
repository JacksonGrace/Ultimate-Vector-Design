import uvd.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** D199 gate — CANONICAL-FRAME GENERATION: placement moves are
 *  BITWISE-rigid even across a live chaotic stall knot.
 *
 *  On the user's determinism-test file with the 0.1-floor knot
 *  PINNED alive:
 *   a) Y+50: every sample's design speed BITWISE, every height moves
 *      by exactly +50 (ulp), ZERO reconcile rewrites;
 *   b) yaw+37: speeds AND heights BITWISE, zero rewrites, the track
 *      heading rotates by exactly 37 deg;
 *   c) X/Z translate: rigid, zero rewrites;
 *   d) piece SPLIT at a section boundary reproduces the track
 *      BITWISE (the D71 rule under canonical frames — the stored
 *      canonical pair), and the split doc round-trips save->load->
 *      rebuild BITWISE (the pair persists).
 */
public class DetermGate199 {

    static final String FILE = "C:/Users/jacks/OneDrive/Documents/AI Fun/"
        + "FULL TEST COASTER 7.30.26 determinism test.fvd";
    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name + "  " + detail); }
    }

    static Coaster load(String text) throws IOException {
        return DesignIO.load(new BufferedReader(new StringReader(text))).coaster();
    }

    static String save(Coaster c) {
        StringWriter sw = new StringWriter();
        DesignIO.Cosmetics cos = new DesignIO.Cosmetics(1, 1, 1, true,
            new double[]{0.45, 0.67, 1.0}, new double[18], new double[6],
            new double[9], new double[3], null);
        try (PrintWriter pw = new PrintWriter(sw)) { DesignIO.save(c, cos, pw); }
        return sw.toString();
    }

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

    /** the knot-pinned settled doc from the file — settled to PROVEN
     *  quiescence (a non-quiescent fixture would attribute its own
     *  churn to the placement moves) */
    static Coaster knotDoc(String text) throws IOException {
        Coaster c = load(text);
        boolean pinned = false;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (!pinned && s.vDesign() < 1.0) { s.vPinned(true); pinned = true; }
        if (!pinned) throw new IllegalStateException(
            "fixture lost its knot: no section under 1 m/s in the file");
        for (int k = 0; k < 6; k++) {
            if ((Integer) rebuild(c)[1] == 0) return c;
        }
        throw new IllegalStateException("knot doc does not quiesce");
    }

    static boolean vBitwise(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return false;
        for (int i = 0; i < a.samples().size(); i++)
            if (Double.doubleToLongBits(a.samples().get(i).v())
                    != Double.doubleToLongBits(b.samples().get(i).v()))
                return false;
        return true;
    }

    static boolean yBitwise(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return false;
        for (int i = 0; i < a.samples().size(); i++)
            if (Double.doubleToLongBits(a.samples().get(i).pos().y())
                    != Double.doubleToLongBits(b.samples().get(i).pos().y()))
                return false;
        return true;
    }

    static boolean posBitwise(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.samples().size() != b.samples().size()) return false;
        for (int i = 0; i < a.samples().size(); i++) {
            Vec3 x = a.samples().get(i).pos(), y = b.samples().get(i).pos();
            if (Double.doubleToLongBits(x.x()) != Double.doubleToLongBits(y.x())
             || Double.doubleToLongBits(x.y()) != Double.doubleToLongBits(y.y())
             || Double.doubleToLongBits(x.z()) != Double.doubleToLongBits(y.z()))
                return false;
        }
        return true;
    }

    static int vdRewrites(Coaster c, double[] before) {
        int i = 0, n = 0;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (Math.abs(before[i++] - s.vDesign()) > 1e-12) n++;
        return n;
    }

    static double[] vdSnap(Coaster c) {
        double[] out = new double[64];
        int i = 0;
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections) out[i++] = s.vDesign();
        return out;
    }


    /** The knot text: the on-disk fixture IF it still carries a
     *  sub-1 m/s section — he re-saves test files, and on 2026-08-05
     *  it came back knotless — else the deterministic constructed
     *  knot (KnotFixture), settled and saved. */
    static String fixtureText() throws Exception {
        try {
            String t = new String(Files.readAllBytes(Paths.get(FILE)), StandardCharsets.UTF_8);
            Coaster c = load(t);
            for (TrackPiece p : c.pieces)
                for (Section s : p.sections)
                    if (s.vDesign() < 1.0) return t;
        } catch (Exception ignore) { }
        Coaster c = KnotFixture.build();
        rebuild(c);
        for (TrackPiece p : c.pieces)
            for (Section s : p.sections)
                if (s.vDesign() < 1.0) return save(c);
        throw new IllegalStateException("constructed knot failed to stall");
    }

    public static void main(String[] args) throws Exception {
        String text = fixtureText();

        Coaster base = knotDoc(text);
        TrackCompiler.Result r0 = (TrackCompiler.Result) rebuild(base)[0];
        int n0 = r0.samples().size();

        // ---- a) Y + 50 ----
        {
            Coaster c = knotDoc(text);
            double[] vd = vdSnap(c);
            c.pieces.get(0).y += 50;
            TrackCompiler.Result r = (TrackCompiler.Result) rebuild(c)[0];
            double worstY = 0;
            int n = Math.min(n0, r.samples().size());
            for (int i = 0; i < n; i++)
                worstY = Math.max(worstY, Math.abs(
                    r.samples().get(i).pos().y() - r0.samples().get(i).pos().y() - 50));
            check("Y+50: design speeds BITWISE through the knot",
                r.samples().size() == n0 && vBitwise(r, r0), "");
            check("Y+50: every height exactly +50", worstY < 1e-9,
                String.format(Locale.US, "worst |dy-50| = %.3e", worstY));
            check("Y+50: ZERO document rewrites", vdRewrites(c, vd) == 0,
                "(was 9 rewrites + 561 m of shape change pre-D199)");
        }

        // ---- b) yaw + 37 ----
        {
            Coaster c = knotDoc(text);
            double[] vd = vdSnap(c);
            c.pieces.get(0).yawDeg += 37;
            TrackCompiler.Result r = (TrackCompiler.Result) rebuild(c)[0];
            // scope: piece 1 OUTSIDE the strict-seam smoothing window
            // — piece 2 holds ABSOLUTE pitch/roll across the seam, so
            // under a yaw it legitimately re-aims (the user's "okay
            // and expected") and the ±5 m blend window legitimately
            // re-blends toward it
            int nsec1 = c.pieces.get(0).sections.size();
            TrackCompiler.Span sa = r0.spans().get(nsec1 - 1);
            int p1end = sa.i1();
            double sSeam = r0.samples().get(p1end).s();
            double hUp = c.pieces.size() > 1
                ? Math.min(c.pieces.get(1).sSmoothLen / 2, sa.sLen() * 0.5)
                : 0;   // constructed single-piece knot: no follower
            int wStart = p1end;
            while (wStart > 0
                    && r0.samples().get(wStart).s() > sSeam - hUp - 0.05)
                wStart--;
            boolean bit = r.samples().size() == n0;
            for (int i = 0; bit && i < wStart; i++) {
                bit = Double.doubleToLongBits(r.samples().get(i).v())
                        == Double.doubleToLongBits(r0.samples().get(i).v())
                   && Double.doubleToLongBits(r.samples().get(i).pos().y())
                        == Double.doubleToLongBits(r0.samples().get(i).pos().y());
            }
            check("yaw+37: piece-1 speeds AND heights BITWISE through the knot",
                bit, "samples 0.." + (wStart - 1)
                    + " (blend window " + wStart + ".." + p1end
                    + " legitimately re-aims)");
            check("yaw+37: ZERO document rewrites", vdRewrites(c, vd) == 0, "");
            // aim: the entry tangent's heading rotates by exactly 37
            FVDIntegrator.Sample s0 = r0.samples().get(50), s1 = r.samples().get(50);
            double h0 = Math.toDegrees(Math.atan2(-s0.T().z(), s0.T().x()));
            double h1 = Math.toDegrees(Math.atan2(-s1.T().z(), s1.T().x()));
            double dh = (h1 - h0 - 37 + 540) % 360 - 180;
            check("yaw+37: world heading rotates by exactly +37 deg",
                Math.abs(dh) < 1e-9,
                String.format(Locale.US, "err = %.3e deg", dh));
        }

        // ---- c) X/Z translate ----
        {
            Coaster c = knotDoc(text);
            double[] vd = vdSnap(c);
            c.pieces.get(0).x += 100;
            c.pieces.get(0).z -= 45;
            TrackCompiler.Result r = (TrackCompiler.Result) rebuild(c)[0];
            double worst = 0;
            for (int i = 0; i < n0; i++) {
                Vec3 d = r.samples().get(i).pos()
                    .sub(new Vec3(100, 0, -45)).sub(r0.samples().get(i).pos());
                worst = Math.max(worst, d.length());
            }
            check("X/Z translate: rigid + speeds bitwise + no rewrites",
                vBitwise(r, r0) && worst < 1e-9 && vdRewrites(c, vd) == 0,
                String.format(Locale.US, "worst %.3e m", worst));
        }

        // ---- d) piece split is BITWISE + the pair survives the file ----
        {
            Coaster c = knotDoc(text);
            TrackCompiler.Result rPre = (TrackCompiler.Result) rebuild(c)[0];
            // a mid-chain section boundary of piece 0 (the
            // constructed knot has 2 sections; his file had many)
            int cut = Math.min(5, c.pieces.get(0).sections.size() - 1);
            int np = c.splitPiece(0, cut, rPre.spans().get(cut - 1));
            TrackCompiler.Result rPost = TrackCompiler.compile(c, DT);
            // a split piece KEEPS its first sample (detached pieces
            // are genuinely new points), so the boundary appears
            // twice — compare geometry with the +1 offset (the D71
            // "0.0 m" contract was always geometric)
            int b = rPre.spans().get(cut - 1).i1();
            int extra = rPost.samples().size() - rPre.samples().size();
            boolean bit = np > 0 && extra == 1;
            for (int i = 0; bit && i < rPre.samples().size(); i++) {
                int j = i <= b ? i : i + extra;
                FVDIntegrator.Sample x = rPre.samples().get(i);
                FVDIntegrator.Sample y = rPost.samples().get(j);
                bit = Double.doubleToLongBits(x.pos().x()) == Double.doubleToLongBits(y.pos().x())
                   && Double.doubleToLongBits(x.pos().y()) == Double.doubleToLongBits(y.pos().y())
                   && Double.doubleToLongBits(x.pos().z()) == Double.doubleToLongBits(y.pos().z())
                   && Double.doubleToLongBits(x.v()) == Double.doubleToLongBits(y.v());
            }
            check("piece split at a boundary: whole track BITWISE",
                bit, "(+1 duplicated boundary sample expected)");
            Object[] rb = rebuild(c);   // settle (should be quiet)
            TrackCompiler.Result rSet = (TrackCompiler.Result) rb[0];
            String s1 = save(c);
            Coaster c2 = load(s1);
            Object[] rb2 = rebuild(c2);
            check("split doc: save->load->rebuild BITWISE (pair persists)",
                posBitwise((TrackCompiler.Result) rb2[0], rSet)
                    && vBitwise((TrackCompiler.Result) rb2[0], rSet)
                    && (Integer) rb2[1] == 0,
                "reload passes=" + rb2[1]);
            check("split doc: canonical round-trip byte-identical",
                s1.equals(save(load(s1))), "");
        }

        System.out.println("----");
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
