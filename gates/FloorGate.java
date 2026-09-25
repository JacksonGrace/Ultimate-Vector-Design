import uvd.*;

import java.util.*;

/** D249 gate (RE-BASELINED from the D248 depth-lift gate — the
 *  lift API is retired, see FloorDepth's postmortem): the floor's
 *  pure model via uvd.FloorDepth — the D241-D246 STRENGTH LAW
 *  and the GROUND-SPLIT routing of the track layer. Asserts:
 *   f1  strength-law sanity (the D248 f8): the 100 m decade is dead
 *       at a 2 m eye, all three decades alive at 60 m, only the
 *       100 m one at 2.4 km; the ladders are the D243/D245 ones
 *       (21 ring edges to 384q, 17 axis boundaries to 6.4 km, the
 *       30 m axis anchor); GROUND_DEADBAND = the 70 mm rail radius;
 *   f2  eyeBelowGround: below → true, above → false, ±0 counts as
 *       ABOVE;
 *   f3  faceWorldY over scene-coordinate points (scene y = −world y);
 *   f4  a 500-ring rail tube (8 sides, r 0.07) dipping from y = +5
 *       to −15: every face wholly below −0.07 is UNDER, every face
 *       wholly at/above −0.07 is ABOVE, each STRADDLING face (both
 *       signs of world y) goes by its centroid — straddlers exist on
 *       both sides; splitCounts sums to the face count;
 *   f5  the DEADBAND: a level tube centred at y = −0.005 has NO
 *       under face (mask null — the mesh stays untouched: the
 *       lowest face centroid of an 8-sided r = 0.07 tube sits
 *       0.0632 below its centre, so tubes centred down to ≈ −7 mm
 *       stay whole — the y = 0 rail's 6.8 mm margin), one at −0.05
 *       sheds its BOTTOM faces (under < above) while its top faces,
 *       poking above 0, straddle and stay above, one at −0.08
 *       splits half and half, one exactly ON y = 0 is a clean
 *       single tube (null), one fully above is null (the fast
 *       path), a flat quad at y = −15 is all under;
 *   f6  the routing is a pure function of the geometry: the same
 *       tube twice → equal masks; translating the tube by −30 m in
 *       y sends everything under.
 *  (the scene-graph order, the world-order swap on the eye's side of
 *  the ground and the MeshView cut are JavaFX-side — the
 *  -Dfvd.gridshot ladder + the -Dfvd.gridshot.sweep hook are their
 *  verification.) */
public class FloorGate {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static void check(boolean ok, String name, String detail) {
        check(ok, name + "  (" + detail + ")");
    }

    static final double[] E = FloorDepth.RING_EDGE;
    static final int RINGS = E.length - 1;
    static final double DB = FloorDepth.GROUND_DEADBAND;

    /** Which decades draw anything at eye height dRef (base 0:
     *  1 m / 10 m / 100 m) under the real law. */
    static boolean[] alive(double dRef, double subH, double tanH) {
        boolean[] out = new boolean[3];
        for (int d = 0; d < 3; d++) {
            double q = Math.pow(10, d);
            for (int k = 0; k < RINGS; k++) {
                if (q * E[k] > 5500) break;
                if ((int) Math.round(FloorDepth.ringStrength(q, k, dRef, subH, tanH) * 256) > 0) {
                    out[d] = true;
                    break;
                }
            }
        }
        return out;
    }

    /** A rail tube along +x in SCENE coordinates (scene y = −world y),
     *  the buildRailMesh construction: 8-sided rings of radius r
     *  around the centre line y = yOf(x), faces a-c-b / b-c-d per
     *  quad with texcoord index = ring. */
    record Tube(float[] pts, int[] faces) { }

    static Tube tube(int nRings, double dx, java.util.function.DoubleUnaryOperator yOf, double r) {
        int sides = 8;
        float[] pts = new float[nRings * sides * 3];
        int[] faces = new int[(nRings - 1) * sides * 2 * 6];
        int p = 0;
        for (int i = 0; i < nRings; i++) {
            double x = i * dx, cy = yOf.applyAsDouble(x);
            for (int j = 0; j < sides; j++) {
                double th = 2 * Math.PI * j / sides;
                double wy = cy + Math.sin(th) * r, wz = Math.cos(th) * r;
                pts[p++] = (float) x; pts[p++] = (float) -wy; pts[p++] = (float) wz;
            }
        }
        int f = 0;
        for (int ring = 0; ring < nRings - 1; ring++) {
            int r0 = ring * sides, r1 = (ring + 1) * sides;
            for (int j = 0; j < sides; j++) {
                int jn = (j + 1) % sides;
                int a = r0 + j, b = r0 + jn, c = r1 + j, d = r1 + jn;
                int[] f1 = { a, ring, c, ring + 1, b, ring };
                int[] f2 = { b, ring, c, ring + 1, d, ring + 1 };
                System.arraycopy(f1, 0, faces, f, 6); f += 6;
                System.arraycopy(f2, 0, faces, f, 6); f += 6;
            }
        }
        return new Tube(pts, faces);
    }

    public static void main(String[] args) {
        double subH = 700, tanH = Math.tan(Math.toRadians(30));

        // ---------- f1 strength law + ladders ----------
        boolean[] a2 = alive(2, subH, tanH), a60 = alive(60, subH, tanH), a2400 = alive(2400, subH, tanH);
        check(!a2[2] && a60[0] && a60[1] && a60[2] && !a2400[0] && !a2400[1] && a2400[2],
              "f1 real law: 100 m decade dead at a 2 m eye; all three alive at 60 m; only 100 m at 2.4 km",
              Arrays.toString(a2) + " " + Arrays.toString(a60) + " " + Arrays.toString(a2400));
        check(E.length == 21 && E[0] == 0 && E[20] == 384
              && FloorDepth.AXIS_SEG.length == 17 && FloorDepth.AXIS_SEG[16] == 6400
              && FloorDepth.AXIS_ANCHOR_Q == 30 && DB == 0.07,
              "f1b the D243/D245 ladders (21 ring edges to 384q, 17 axis boundaries to 6.4 km, 30 m anchor) + GROUND_DEADBAND 0.07");
        // the D242 smoothstep + the D243 half-width law
        check(FloorDepth.smooth(0.5) == 0.5 && FloorDepth.smooth(-1) == 0 && FloorDepth.smooth(2) == 1
              && Math.abs(FloorDepth.gridHw(1) - 0.015) < 1e-15
              && Math.abs(FloorDepth.gridHw(100) - 0.015 * Math.pow(100, 0.6)) < 1e-15,
              "f1c smooth()/gridHw() verbatim (1 m: 1.5 cm)");

        // ---------- f2 eye side ----------
        check(FloorDepth.eyeBelowGround(-25) && !FloorDepth.eyeBelowGround(24)
              && !FloorDepth.eyeBelowGround(0.0) && !FloorDepth.eyeBelowGround(-0.0),
              "f2 eyeBelowGround: below true, above false, ±0 counts as ABOVE");

        // ---------- f3 faceWorldY ----------
        float[] tri = { 0, -1f, 0,  1, -2f, 0,  0, -3f, 1 };   // world y 1, 2, 3
        int[] one = { 0, 0, 1, 0, 2, 0 };
        check(Math.abs(FloorDepth.faceWorldY(tri, one, 0) - 2.0) < 1e-12,
              "f3 faceWorldY = the centroid's WORLD y over scene points (scene y = −world y)");

        // ---------- f4 the dipping tube ----------
        int n = 500;
        double L = 200;
        Tube t = tube(n, L / (n - 1), x -> 5 - 20 * (1 - Math.cos(2 * Math.PI * x / L)) / 2, 0.07);
        boolean[] mask = FloorDepth.underMask(t.pts(), t.faces(), DB);
        int nf = t.faces().length / 6;
        boolean f4a = mask != null && mask.length == nf;
        boolean f4b = true, f4c = true, f4d = true;
        int strU = 0, strA = 0;
        if (f4a) {
            for (int f = 0; f < nf; f++) {
                double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                for (int k = 0; k < 3; k++) {
                    double wy = -t.pts()[t.faces()[f * 6 + 2 * k] * 3 + 1];
                    lo = Math.min(lo, wy); hi = Math.max(hi, wy);
                }
                double cy = FloorDepth.faceWorldY(t.pts(), t.faces(), f);
                if (hi < -DB && !mask[f]) f4b = false;           // wholly below → under
                if (lo >= -DB && mask[f]) f4c = false;           // wholly at/above → above
                if (lo < 0 && hi > 0) {                          // straddler
                    if (mask[f] != (cy < -DB)) f4d = false;
                    if (mask[f]) strU++; else strA++;
                }
            }
        }
        int[] cnt = f4a ? FloorDepth.splitCounts(t.pts(), t.faces(), mask) : new int[4];
        check(f4a, "f4 a tube dipping +5 → −15 m splits (mask over every face)", nf + " faces");
        check(f4b, "f4b every face wholly below −0.07 is UNDER");
        check(f4c, "f4c every face wholly at/above −0.07 is ABOVE");
        check(f4d && strU > 0 && strA > 0,
              "f4d straddling faces go by CENTROID (< −0.07 → under) — straddlers land on both sides",
              "straddle under " + strU + ", above " + strA);
        check(cnt[0] + cnt[1] == nf && cnt[2] == strA && cnt[3] == strU && cnt[1] > 0 && cnt[0] > 0,
              "f4e splitCounts {above, under, straddle-above, straddle-under} sums to the face count",
              Arrays.toString(cnt));

        // ---------- f5 the deadband ----------
        Tube lvl01 = tube(40, 1, x -> -0.005, 0.07);
        Tube lvl05 = tube(40, 1, x -> -0.05, 0.07);
        Tube lvl08 = tube(40, 1, x -> -0.08, 0.07);
        Tube lvl00 = tube(40, 1, x -> 0.0, 0.07);
        Tube high = tube(40, 1, x -> 21, 0.07);
        check(FloorDepth.underMask(lvl01.pts(), lvl01.faces(), DB) == null,
              "f5 a level tube centred at −0.005 (spanning −0.075..+0.065) has NO under face — deadband: untouched");
        boolean[] m05 = FloorDepth.underMask(lvl05.pts(), lvl05.faces(), DB);
        int[] c05 = m05 == null ? null : FloorDepth.splitCounts(lvl05.pts(), lvl05.faces(), m05);
        check(m05 != null && c05[1] > 0 && c05[1] < c05[0] && c05[2] > 0 && c05[3] == 0,
              "f5a a level tube centred at −0.05 sheds its BOTTOM faces (under < above); its top faces straddle 0 and stay ABOVE",
              Arrays.toString(c05));
        boolean[] m08 = FloorDepth.underMask(lvl08.pts(), lvl08.faces(), DB);
        int[] c08 = m08 == null ? null : FloorDepth.splitCounts(lvl08.pts(), lvl08.faces(), m08);
        check(m08 != null && c08[0] > 0 && c08[1] > 0 && c08[2] == 0 && c08[3] == 0,
              "f5b a level tube centred at −0.08 splits (top faces above, the rest under, no straddlers)",
              Arrays.toString(c08));
        check(FloorDepth.underMask(lvl00.pts(), lvl00.faces(), DB) == null,
              "f5c a tube exactly ON y = 0 (straddling by construction) is ONE clean above tube (null)");
        check(FloorDepth.underMask(high.pts(), high.faces(), DB) == null,
              "f5d a tube wholly above ground is null (the point fast path)");
        float[] quad = { -1, 15f, -1,  1, 15f, -1,  1, 15f, 1,  -1, 15f, 1 };
        int[] qf = { 0, 0, 1, 0, 2, 0,  0, 0, 2, 0, 3, 0 };
        boolean[] mq = FloorDepth.underMask(quad, qf, DB);
        check(mq != null && mq[0] && mq[1], "f5e a flat quad at y = −15 is all under");

        // ---------- f6 purity + translation ----------
        boolean[] again = FloorDepth.underMask(t.pts(), t.faces(), DB);
        boolean same = Arrays.equals(mask, again);
        float[] shifted = t.pts().clone();
        for (int i = 1; i < shifted.length; i += 3) shifted[i] += 30;   // world y −30
        boolean[] ms = FloorDepth.underMask(shifted, t.faces(), DB);
        boolean allUnder = ms != null;
        if (allUnder) for (boolean b : ms) allUnder &= b;
        check(same, "f6 the mask is a pure function of the geometry (same tube → equal masks)");
        check(allUnder, "f6b the tube translated 30 m down goes wholly under");

        System.out.println();
        System.out.println("FloorGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
