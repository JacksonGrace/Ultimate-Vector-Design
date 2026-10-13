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
 *  BATCH 37 — the glyph routing and the EXACT ground cut (f4-f6 keep
 *  pinning underMask, the centroid model the render no longer uses):
 *   f7  itemUnder: a whole glyph goes under iff its center's world y
 *       < −0.07 (strict), ±0 / the deadband / NaN toward ABOVE;
 *   f8  beforeFloor, the truth table: a drawable is submitted before
 *       the floor exactly when it and the eye sit on OPPOSITE sides
 *       of the ground;
 *   f9  clipAtGround on a COMB TOOTH crossing the ground (root y =
 *       +1, tip y = −1.8): every ABOVE face lies at/above the plane,
 *       every UNDER face at/below it, the area is conserved, no face
 *       flips against its source, the source points stay a bitwise
 *       prefix, the cut vertices sit exactly ON the plane — and the
 *       contrast: underMask routes the tooth's 8 straddling side
 *       faces 4 above / 4 under, the diagonal two-tone bug;
 *   f10 clipAtGround on the f4 tube: area conserved, the src arrays
 *       match the face counts and index real source faces, one
 *       SHARED cut vertex per crossing edge (crack-free); level
 *       tubes — ON the ground / high above → null (untouched), 1 m
 *       under → everything under, above empty; a pure function.
 *  (the scene-graph order, the world-order swap on the eye's side of
 *  the ground and the MeshView assembly are JavaFX-side — the
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

    /** The tube's texcoords (index = ring, the buildRailMesh layout). */
    static float[] tubeTex(int nRings) {
        float[] tex = new float[nRings * 2];
        for (int r = 0; r < nRings; r++) {
            tex[r * 2] = r / (float) nRings;
            tex[r * 2 + 1] = 0.5f;
        }
        return tex;
    }

    /** A comb tooth (Viewport3D.addToothBox's box: 6 quads, 12 faces)
     *  from world y = yRoot down to yTip, in SCENE coordinates; every
     *  face reads texcoord 0. */
    static Tube tooth(double yRoot, double yTip, double hwT, double hw) {
        double[][] c = {
            {-hwT, yRoot, -hw}, {hwT, yRoot, -hw}, {hwT, yRoot, hw}, {-hwT, yRoot, hw},
            {-hwT, yTip, -hw}, {hwT, yTip, -hw}, {hwT, yTip, hw}, {-hwT, yTip, hw} };
        float[] pts = new float[24];
        for (int i = 0; i < 8; i++) {
            pts[i * 3] = (float) c[i][0];
            pts[i * 3 + 1] = (float) -c[i][1];
            pts[i * 3 + 2] = (float) c[i][2];
        }
        int[][] quads = { {0,1,2,3}, {4,5,6,7}, {0,1,5,4}, {2,3,7,6},
                          {1,2,6,5}, {0,3,7,4} };
        int[] faces = new int[72];
        int f = 0;
        for (int[] q : quads) {
            for (int[] t : new int[][]{ {q[0], q[1], q[2]}, {q[0], q[2], q[3]} }) {
                for (int k = 0; k < 3; k++) faces[f * 6 + 2 * k] = t[k];
                f++;
            }
        }
        return new Tube(pts, faces);
    }

    /** Face f's (unnormalized) normal — its length is twice the area. */
    static double[] normal(float[] P, int[] F, int f) {
        int a = F[f * 6], b = F[f * 6 + 2], c = F[f * 6 + 4];
        double ux = P[b * 3] - P[a * 3], uy = P[b * 3 + 1] - P[a * 3 + 1],
               uz = P[b * 3 + 2] - P[a * 3 + 2];
        double vx = P[c * 3] - P[a * 3], vy = P[c * 3 + 1] - P[a * 3 + 1],
               vz = P[c * 3 + 2] - P[a * 3 + 2];
        return new double[]{ uy * vz - uz * vy, uz * vx - ux * vz,
                             ux * vy - uy * vx };
    }

    static double area(float[] P, int[] F) {
        double sum = 0;
        for (int f = 0; f < F.length / 6; f++) {
            double[] n = normal(P, F, f);
            sum += 0.5 * Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        }
        return sum;
    }

    /** The clip's geometric contract over one output half: {deepest
     *  scene y, shallowest scene y, faces flipped against their
     *  source, src indices out of range}. */
    static double[] halfStats(float[] srcPts, int[] srcFaces,
                              float[] pts, int[] faces, int[] src) {
        double max = -Double.MAX_VALUE, min = Double.MAX_VALUE;
        int flipped = 0, bad = 0;
        for (int f = 0; f < faces.length / 6; f++) {
            for (int k = 0; k < 3; k++) {
                double sy = pts[faces[f * 6 + 2 * k] * 3 + 1];
                max = Math.max(max, sy);
                min = Math.min(min, sy);
            }
            if (src[f] < 0 || src[f] >= srcFaces.length / 6) { bad++; continue; }
            double[] n1 = normal(pts, faces, f);
            double[] n0 = normal(srcPts, srcFaces, src[f]);
            if (n1[0] * n0[0] + n1[1] * n0[1] + n1[2] * n0[2] < 0) flipped++;
        }
        return new double[]{ max, min, flipped, bad };
    }

    /** The clip's bookkeeping contract: the source points / texcoords
     *  are a BITWISE prefix, every appended point sits exactly ON
     *  the plane, and the appended points number exactly the DISTINCT
     *  source edges that cross it (one shared vertex per edge). */
    static boolean cutBookkeeping(float[] srcPts, float[] srcTex, int[] srcFaces,
                                  FloorDepth.Clip c) {
        float plane = (float) DB;
        for (int i = 0; i < srcPts.length; i++) {
            if (Float.floatToRawIntBits(srcPts[i]) != Float.floatToRawIntBits(c.pts()[i])) return false;
        }
        for (int i = 0; i < srcTex.length; i++) {
            if (Float.floatToRawIntBits(srcTex[i]) != Float.floatToRawIntBits(c.tex()[i])) return false;
        }
        for (int i = srcPts.length / 3; i < c.pts().length / 3; i++) {
            if (c.pts()[i * 3 + 1] != plane) return false;
        }
        Set<Long> edges = new HashSet<>();
        for (int f = 0; f < srcFaces.length / 6; f++) {
            for (int k = 0; k < 3; k++) {
                int a = srcFaces[f * 6 + 2 * k], b = srcFaces[f * 6 + 2 * ((k + 1) % 3)];
                float da = srcPts[a * 3 + 1] - plane, db = srcPts[b * 3 + 1] - plane;
                if ((da < 0 && db > 0) || (da > 0 && db < 0)) {
                    edges.add(((long) Math.min(a, b) << 32) | Math.max(a, b));
                }
            }
        }
        return c.pts().length / 3 - srcPts.length / 3 == edges.size()
            && !edges.isEmpty();
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

        // ---------- f7 item routing (batch 37) ----------
        check(FloorDepth.itemUnder(-0.0701) && FloorDepth.itemUnder(-15)
              && !FloorDepth.itemUnder(-0.07) && !FloorDepth.itemUnder(-0.03)
              && !FloorDepth.itemUnder(-0.0) && !FloorDepth.itemUnder(0)
              && !FloorDepth.itemUnder(5) && !FloorDepth.itemUnder(Double.NaN),
              "f7 itemUnder: center below −0.07 → under (strict); the deadband, ±0, above and NaN count as ABOVE");

        // ---------- f8 the truth table ----------
        check(!FloorDepth.beforeFloor(false, false) && FloorDepth.beforeFloor(true, false)
              && FloorDepth.beforeFloor(false, true) && !FloorDepth.beforeFloor(true, true),
              "f8 beforeFloor: before the floor exactly when the item and the eye sit on OPPOSITE sides");
        double[][] ye = { {3, 24}, {3, -25}, {-15, 24}, {-15, -25} };
        boolean[] want = { false, true, true, false };
        boolean f8 = true;
        for (int i = 0; i < 4; i++) {
            f8 &= FloorDepth.beforeFloor(FloorDepth.itemUnder(ye[i][0]),
                FloorDepth.eyeBelowGround(ye[i][1])) == want[i];
        }
        check(f8, "f8b (item y, eye y) = (3,24) (3,−25) (−15,24) (−15,−25) → after, BEFORE, BEFORE, after");

        // ---------- f9 the exact cut: a comb tooth ----------
        float plane = (float) DB;
        Tube th = tooth(1.0, -1.8, 0.06, 0.03);
        float[] thTex = { 0.5f, 0.5f };
        FloorDepth.Clip ct = FloorDepth.clipAtGround(th.pts(), thTex, th.faces(), DB);
        check(ct != null && ct.above().length % 6 == 0 && ct.under().length % 6 == 0
              && ct.srcAbove().length == ct.above().length / 6
              && ct.srcUnder().length == ct.under().length / 6
              && ct.above().length > 0 && ct.under().length > 0,
              "f9 a tooth crossing the ground (+1 → −1.8) is CUT: both halves non-empty, one source index per face",
              ct == null ? "null" : "12 faces → above " + ct.above().length / 6 + " + under " + ct.under().length / 6);
        if (ct != null) {
            double[] sa = halfStats(th.pts(), th.faces(), ct.pts(), ct.above(), ct.srcAbove());
            double[] su = halfStats(th.pts(), th.faces(), ct.pts(), ct.under(), ct.srcUnder());
            check(sa[0] <= plane && su[1] >= plane,
                  "f9a every ABOVE face lies at/above world −0.07, every UNDER face at/below it (no diagonal two-tone)",
                  "deepest ABOVE sceneY " + sa[0] + ", shallowest UNDER sceneY " + su[1]);
            double a0 = area(th.pts(), th.faces()), a1 = area(ct.pts(), ct.above()) + area(ct.pts(), ct.under());
            check(Math.abs(a0 - a1) < 1e-6, "f9b the tooth's area is conserved by the cut", a0 + " vs " + a1);
            check(sa[2] == 0 && su[2] == 0 && sa[3] == 0 && su[3] == 0,
                  "f9c no face flips against its source face (winding preserved)");
            check(cutBookkeeping(th.pts(), thTex, th.faces(), ct)
                  && ct.pts().length / 3 == 8 + 8 && ct.tex().length / 2 == 1 + 16,
                  "f9d source points/texcoords stay a bitwise prefix; 8 cut vertices exactly ON the plane, one per crossing edge (4 verticals + 4 diagonals), a texcoord per cut corner",
                  "pts " + ct.pts().length / 3 + ", tex " + ct.tex().length / 2);
        }
        boolean[] mt = FloorDepth.underMask(th.pts(), th.faces(), DB);
        int[] cm = mt == null ? new int[4] : FloorDepth.splitCounts(th.pts(), th.faces(), mt);
        check(mt != null && cm[2] == 4 && cm[3] == 4,
              "f9e the contrast — the CENTROID mask routes the tooth's 8 straddling side faces 4 above / 4 under (each side quad split along its diagonal: the bug the cut fixes)",
              Arrays.toString(cm));

        // ---------- f10 the exact cut: tubes ----------
        float[] tTex = tubeTex(n);
        FloorDepth.Clip cc = FloorDepth.clipAtGround(t.pts(), tTex, t.faces(), DB);
        check(cc != null && cc.srcAbove().length == cc.above().length / 6
              && cc.srcUnder().length == cc.under().length / 6
              && cc.above().length / 6 + cc.under().length / 6 > nf,
              "f10 the dipping tube is cut (straddlers become several faces; one source index per face)",
              cc == null ? "null" : nf + " faces → above " + cc.above().length / 6 + " + under " + cc.under().length / 6);
        if (cc != null) {
            double[] sa = halfStats(t.pts(), t.faces(), cc.pts(), cc.above(), cc.srcAbove());
            double[] su = halfStats(t.pts(), t.faces(), cc.pts(), cc.under(), cc.srcUnder());
            double a0 = area(t.pts(), t.faces()), a1 = area(cc.pts(), cc.above()) + area(cc.pts(), cc.under());
            check(sa[0] <= plane && su[1] >= plane && sa[2] == 0 && su[2] == 0
                  && sa[3] == 0 && su[3] == 0 && Math.abs(a0 - a1) < 1e-6,
                  "f10a each half on its own side of the plane, no flips, area conserved",
                  "area " + a0 + " vs " + a1);
            check(cutBookkeeping(t.pts(), tTex, t.faces(), cc),
                  "f10b cut vertices are SHARED — one per crossing edge, exactly ON the plane; the source arrays a bitwise prefix (crack-free)",
                  "pts " + t.pts().length / 3 + " → " + cc.pts().length / 3);
            FloorDepth.Clip c2 = FloorDepth.clipAtGround(t.pts(), tTex, t.faces(), DB);
            check(c2 != null && Arrays.equals(cc.pts(), c2.pts()) && Arrays.equals(cc.tex(), c2.tex())
                  && Arrays.equals(cc.above(), c2.above()) && Arrays.equals(cc.under(), c2.under())
                  && Arrays.equals(cc.srcAbove(), c2.srcAbove()) && Arrays.equals(cc.srcUnder(), c2.srcUnder()),
                  "f10c the cut is a pure function of the geometry (same tube → identical arrays)");
        }
        float[] lTex = tubeTex(40);
        Tube lvl1 = tube(40, 1, x -> -1.0, 0.07);
        check(FloorDepth.clipAtGround(lvl00.pts(), lTex, lvl00.faces(), DB) == null
              && FloorDepth.clipAtGround(high.pts(), lTex, high.faces(), DB) == null,
              "f10d a tube exactly ON y = 0 and one high above are null — the mesh stays untouched");
        FloorDepth.Clip cl = FloorDepth.clipAtGround(lvl1.pts(), lTex, lvl1.faces(), DB);
        check(cl != null && cl.above().length == 0 && cl.under().length == lvl1.faces().length
              && Arrays.equals(cl.under(), lvl1.faces()) && Arrays.equals(cl.pts(), lvl1.pts())
              && Arrays.equals(cl.tex(), lTex),
              "f10e a level tube 1 m under: every face under in source order, above empty, no point added");
        FloorDepth.Clip c05c = FloorDepth.clipAtGround(lvl05.pts(), lTex, lvl05.faces(), DB);
        check(c05c != null && c05c.above().length > 0 && c05c.under().length > 0
              && halfStats(lvl05.pts(), lvl05.faces(), c05c.pts(), c05c.above(), c05c.srcAbove())[0] <= plane
              && halfStats(lvl05.pts(), lvl05.faces(), c05c.pts(), c05c.under(), c05c.srcUnder())[1] >= plane
              && Math.abs(area(lvl05.pts(), lvl05.faces())
                  - area(c05c.pts(), c05c.above()) - area(c05c.pts(), c05c.under())) < 1e-6,
              "f10f a level tube centred at −0.05 sheds exactly its part below −0.07 (a lengthwise cut, area conserved)");

        System.out.println();
        System.out.println("FloorGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
