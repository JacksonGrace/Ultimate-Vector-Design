package uvd;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * THE FLOOR'S PURE ARITHMETIC (v19.19, D248; RE-BASED v19.20, D249),
 * factored out of Viewport3D so FloorGate can pin it headlessly (no
 * JavaFX): the D241-D246 STRENGTH LAW, the ring/axis LADDERS, and
 * — since D249 — the GROUND-SPLIT routing of the track layer.
 *
 * POSTMORTEM (D249). D248 drew the floor AFTER the track with the
 * depth test ENABLED and sheared its coplanar layers (sheet, line
 * decades, axes) onto ranked tilted planes two depth LSB apart —
 * "the lift API" that lived here. It shipped and was REVERTED in
 * D249: on the user's GPU the coplanar depth bias between two
 * independently rasterized kilometre-scale triangles exceeds a
 * 2-LSB margin, it is PER TRIANGLE (a losing layer is dropped
 * wholesale, not re-blended), and the eye-following sheet — the
 * depth reference of every line — was re-clipped and re-set-up on
 * every frame of camera motion, so the whole grid flashed while
 * moving and vanished at some rest poses. No margin can be both
 * robust to that bias and sub-pixel on screen, and under ~2 m the
 * required separation sits below float32 vertex rounding anyway.
 * RULE: no depth-tested coplanar floor layer ever again without a
 * MEASURED platform envelope (a coplanar-quad probe at k = 1..64
 * LSB on the user's GPU) and a screen-float budget. The floor is
 * back to the D242-D246 build BITWISE: DepthTest.DISABLE, drawn
 * FIRST — pure submission order — and the see-through look comes
 * from ORDER alone: every track-layer mesh is split at y = 0
 * ({@link #underMask}), its underground half drawn BEFORE the
 * floor so the sheet + lines + axes composite over it, the rest
 * after the floor as always. Zero coplanar depth tests remain.
 *
 * BATCH 37. Two holes in that split are closed here. (1) The render
 * now CUTS a straddling face exactly at the plane
 * ({@link #clipAtGround}) instead of routing it whole by its
 * centroid: a comb tooth crossing the ground had each side quad's
 * two triangles routed to DIFFERENT halves — a diagonal two-tone
 * tooth — and straight rail faces span up to 1.2 m of track.
 * underMask / splitCounts stay as the centroid model FloorGate
 * f4-f6 pin; they no longer describe the render. (2) The GLYPHS —
 * node markers, boundary / control disks, the un-revealed reveal
 * balls — were left whole in the after-floor block, wrong on BOTH
 * sides of the ground once one sat below it; they route per ITEM
 * by their center ({@link #itemUnder}) into twin groups of the
 * before-floor block, so the eye-side swap carries them with the
 * track. {@link #beforeFloor} is the one truth table.
 */
public final class FloorDepth {

    private FloorDepth() {}

    /** Ring bounds × spacing — the decade ladder (D242 geometric
     *  inner rings, D245 384q extent). Shared with Viewport3D's
     *  grid builder so the gate sweeps the real ladder. */
    public static final double[] RING_EDGE = {
        0, 1, 1.35, 1.8, 2.4, 3.2, 4.2, 5.6, 7.5, 10,
        13.5, 18, 24, 32, 44, 64, 88, 128, 180, 256, 384 };

    /** The main axes' segment boundaries out from the camera anchor
     *  (m) — geometric past the near field, ±6.4 km (D243). */
    public static final double[] AXIS_SEG = { 0, 30, 60, 90, 120, 180,
        240, 360, 480, 720, 960, 1440, 1920, 2880, 3840, 5120, 6400 };

    /** The main axes' anchor quantum (m): the axis meshes are rebuilt
     *  only when the 30 m-quantized eye anchor moves (D243). */
    public static final double AXIS_ANCHOR_Q = 30;

    // ---------- the GROUND SPLIT (v19.20, D249) ----------

    /** The split's deadband below y = 0 (world m) = the rail radius
     *  (Viewport3D.RAIL_RADIUS, 70 mm): a face whose centroid lies
     *  ABOVE −GROUND_DEADBAND stays in the after-floor half, so a
     *  rail resting ON the ground (its tube straddling y = 0 by
     *  construction) renders as one clean tube over the floor and
     *  numerical dust can never alternate neighbouring faces. */
    public static final double GROUND_DEADBAND = 0.07;

    /** Is the eye below ground? Decides which track half is drawn
     *  before the floor (the halves SWAP on the sign flip — from
     *  below, the underground points on any ray precede the ground
     *  crossing). A ground-level eye (±0) counts as ABOVE. */
    public static boolean eyeBelowGround(double eyeY) {
        return eyeY < 0;
    }

    /** ITEM routing (batch 37): a whole GLYPH — node marker, boundary
     *  / control disk, reveal ball — goes UNDER iff its center's
     *  world y < −GROUND_DEADBAND: one decision per glyph (never a
     *  two-tone sphere or disk), ties and NaN toward ABOVE — the
     *  rail rule. */
    public static boolean itemUnder(double centerWorldY) {
        return centerWorldY < -GROUND_DEADBAND;
    }

    /** THE TRUTH TABLE (batch 37): a drawable is submitted BEFORE the
     *  floor — the floor composites over it — exactly when it and
     *  the eye sit on OPPOSITE sides of the ground. */
    public static boolean beforeFloor(boolean itemUnder, boolean eyeBelow) {
        return itemUnder != eyeBelow;
    }

    /** World y of face f's centroid over SCENE-coordinate points
     *  (scene y = −world y, the mesh convention) and a POINT_TEXCOORD
     *  face array (6 ints per face: p0 t0 p1 t1 p2 t2). */
    public static double faceWorldY(float[] pts, int[] faces, int f) {
        int b = f * 6;
        double sy = pts[faces[b] * 3 + 1]
                  + pts[faces[b + 2] * 3 + 1]
                  + pts[faces[b + 4] * 3 + 1];
        return -sy / 3;
    }

    /** The routing mask of a finished track-layer mesh: out[f] =
     *  true when face f goes UNDER the floor (centroid world y <
     *  −deadband — straddlers by centroid, the tie-break toward
     *  ABOVE), false = the after-floor half. Returns null when no
     *  face is underground, so the caller leaves the mesh UNTOUCHED
     *  (bitwise the D246 object graph for every above-ground file);
     *  the fast path checks the points first — a mesh with no point
     *  deeper than the deadband can have no underground centroid. */
    public static boolean[] underMask(float[] pts, int[] faces,
                                      double deadband) {
        boolean anyDeep = false;
        for (int i = 1; i < pts.length; i += 3) {
            if (pts[i] > deadband) { anyDeep = true; break; }
        }
        if (!anyDeep) return null;
        int n = faces.length / 6;
        boolean[] out = new boolean[n];
        boolean any = false;
        for (int f = 0; f < n; f++) {
            if (faceWorldY(pts, faces, f) < -deadband) {
                out[f] = true;
                any = true;
            }
        }
        return any ? out : null;
    }

    /** Face counts {above, under, straddling-above, straddling-under}
     *  of a mask over its mesh — straddling = the face's vertices
     *  span both signs of world y (the gate's routing pin). */
    public static int[] splitCounts(float[] pts, int[] faces,
                                    boolean[] mask) {
        int n = faces.length / 6;
        int[] c = new int[4];
        for (int f = 0; f < n; f++) {
            boolean under = mask != null && mask[f];
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (int k = 0; k < 3; k++) {
                double wy = -pts[faces[f * 6 + 2 * k] * 3 + 1];
                lo = Math.min(lo, wy);
                hi = Math.max(hi, wy);
            }
            boolean straddle = lo < 0 && hi > 0;
            c[under ? 1 : 0]++;
            if (straddle) c[under ? 3 : 2]++;
        }
        return c;
    }

    // ---------- the EXACT ground cut (batch 37) ----------

    /** A mesh cut at the ground plane: the grown point / texcoord
     *  arrays (the source's own entries first, UNCHANGED, the cut
     *  vertices appended), the two POINT_TEXCOORD face arrays, and
     *  per output face its SOURCE face index (the caller maps the
     *  smoothing groups through these). */
    public record Clip(float[] pts, float[] tex, int[] above, int[] under,
                       int[] srcAbove, int[] srcUnder) { }

    /** Cut a finished track-layer mesh EXACTLY at world y = −deadband
     *  (scene y = +deadband; SCENE-coordinate points, POINT_TEXCOORD
     *  faces — the {@link #underMask} conventions). d = scene y − the
     *  plane, d > 0 = UNDER. A face with no vertex strictly under
     *  goes ABOVE whole, one with none strictly above goes UNDER
     *  whole (on-plane vertices go with the rest — the tie toward
     *  ABOVE); a STRADDLER is walked in winding order into an above
     *  polygon and an under polygon (3 or 4 corners each, fan-
     *  triangulated, the winding preserved). The cut vertex is
     *  interpolated ALWAYS from the above vertex toward the under
     *  one, its y set exactly ON the plane, and SHARED per edge
     *  (keyed by the edge's two point indices), so neighbouring
     *  faces meet crack-free; each cut corner gets its own lerped
     *  texcoord. Returns null — the caller leaves the mesh UNTOUCHED
     *  — when no point lies deeper than the deadband (the fast path:
     *  a rail resting ON the ground stays one clean tube) or no
     *  under face results. */
    public static Clip clipAtGround(float[] pts, float[] tex, int[] faces,
                                    double deadband) {
        boolean anyDeep = false;
        for (int i = 1; i < pts.length; i += 3) {
            if (pts[i] > deadband) { anyDeep = true; break; }
        }
        if (!anyDeep) return null;
        float planeSy = (float) deadband;   // scene y of the cut
        int n = faces.length / 6;
        float[] P = Arrays.copyOf(pts, pts.length + 64 * 3);
        float[] X = Arrays.copyOf(tex, tex.length + 64 * 2);
        int np = pts.length / 3, nt = tex.length / 2;
        int[] fa = new int[faces.length + 64 * 6];
        int[] fu = new int[faces.length + 64 * 6];
        int[] sa = new int[n + 64], su = new int[n + 64];
        int na = 0, nu = 0;
        Map<Long, Integer> edgePt = new HashMap<>();
        for (int f = 0; f < n; f++) {
            int b = f * 6;
            int[] p = { faces[b], faces[b + 2], faces[b + 4] };
            int[] t = { faces[b + 1], faces[b + 3], faces[b + 5] };
            // d > 0 = BELOW the plane (scene y grows downward)
            float[] d = { P[p[0] * 3 + 1] - planeSy,
                          P[p[1] * 3 + 1] - planeSy,
                          P[p[2] * 3 + 1] - planeSy };
            boolean anyU = d[0] > 0 || d[1] > 0 || d[2] > 0;
            boolean anyA = d[0] < 0 || d[1] < 0 || d[2] < 0;
            if (!anyU || !anyA) {   // wholly on one side
                if (anyU) {
                    if ((nu + 1) * 6 > fu.length) fu = Arrays.copyOf(fu, fu.length * 2);
                    if (nu + 1 > su.length) su = Arrays.copyOf(su, su.length * 2);
                    System.arraycopy(faces, b, fu, nu * 6, 6);
                    su[nu++] = f;
                } else {
                    if ((na + 1) * 6 > fa.length) fa = Arrays.copyOf(fa, fa.length * 2);
                    if (na + 1 > sa.length) sa = Arrays.copyOf(sa, sa.length * 2);
                    System.arraycopy(faces, b, fa, na * 6, 6);
                    sa[na++] = f;
                }
                continue;
            }
            // a straddler: walk the winding, emitting each corner to
            // its side(s) and the cut vertex of each crossing edge to
            // both
            int[] ap = new int[4], at = new int[4], up = new int[4], ut = new int[4];
            int ac = 0, uc = 0;
            for (int k = 0; k < 3; k++) {
                int k2 = (k + 1) % 3;
                if (d[k] <= 0) { ap[ac] = p[k]; at[ac++] = t[k]; }
                if (d[k] >= 0) { up[uc] = p[k]; ut[uc++] = t[k]; }
                if ((d[k] < 0 && d[k2] > 0) || (d[k] > 0 && d[k2] < 0)) {
                    int ia = d[k] < 0 ? k : k2, iu = d[k] < 0 ? k2 : k;
                    double w = d[ia] / (double) (d[ia] - d[iu]);
                    long key = ((long) Math.min(p[ia], p[iu]) << 32)
                        | Math.max(p[ia], p[iu]);
                    Integer pi = edgePt.get(key);
                    if (pi == null) {
                        if ((np + 1) * 3 > P.length) P = Arrays.copyOf(P, P.length * 2);
                        P[np * 3] = (float) (P[p[ia] * 3]
                            + w * (P[p[iu] * 3] - P[p[ia] * 3]));
                        P[np * 3 + 1] = planeSy;
                        P[np * 3 + 2] = (float) (P[p[ia] * 3 + 2]
                            + w * (P[p[iu] * 3 + 2] - P[p[ia] * 3 + 2]));
                        pi = np++;
                        edgePt.put(key, pi);
                    }
                    if ((nt + 1) * 2 > X.length) X = Arrays.copyOf(X, X.length * 2);
                    X[nt * 2] = (float) (X[t[ia] * 2]
                        + w * (X[t[iu] * 2] - X[t[ia] * 2]));
                    X[nt * 2 + 1] = (float) (X[t[ia] * 2 + 1]
                        + w * (X[t[iu] * 2 + 1] - X[t[ia] * 2 + 1]));
                    int ti = nt++;
                    ap[ac] = pi; at[ac++] = ti;
                    up[uc] = pi; ut[uc++] = ti;
                }
            }
            for (int k = 1; k + 1 < ac; k++) {
                if ((na + 1) * 6 > fa.length) fa = Arrays.copyOf(fa, fa.length * 2);
                if (na + 1 > sa.length) sa = Arrays.copyOf(sa, sa.length * 2);
                int o = na * 6;
                fa[o] = ap[0]; fa[o + 1] = at[0];
                fa[o + 2] = ap[k]; fa[o + 3] = at[k];
                fa[o + 4] = ap[k + 1]; fa[o + 5] = at[k + 1];
                sa[na++] = f;
            }
            for (int k = 1; k + 1 < uc; k++) {
                if ((nu + 1) * 6 > fu.length) fu = Arrays.copyOf(fu, fu.length * 2);
                if (nu + 1 > su.length) su = Arrays.copyOf(su, su.length * 2);
                int o = nu * 6;
                fu[o] = up[0]; fu[o + 1] = ut[0];
                fu[o + 2] = up[k]; fu[o + 3] = ut[k];
                fu[o + 4] = up[k + 1]; fu[o + 5] = ut[k + 1];
                su[nu++] = f;
            }
        }
        if (nu == 0) return null;
        return new Clip(Arrays.copyOf(P, np * 3),
            Arrays.copyOf(X, nt * 2),
            Arrays.copyOf(fa, na * 6),
            Arrays.copyOf(fu, nu * 6),
            Arrays.copyOf(sa, na),
            Arrays.copyOf(su, nu));
    }

    // ---------- the STRENGTH LAW (D241-D246), moved here verbatim from
    // Viewport3D in v19.19 (D248) — bitwise; the gate evaluates which
    // decades are alive at an eye height ----------

    /** smoothstep, clamped. */
    public static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** The one strength law (D241): a line family whose cells cover
     *  px effective pixels draws at this strength; f = clean-tuning
     *  proximity (1 = steep view or a near-ground eye, 0 = edge-on
     *  from height — the D245 reach lives at LOW f); the high-side
     *  rolloff retires a decade whose sparse cells pass ~150 px. */
    public static double gridAlpha(double px, double f) {
        double start = 0.09 + 0.71 * f;
        return (0.55 * smooth((px - start) / (5 * start))
              + 0.30 * smooth(px / 60))
             * (1 - smooth((px - 150) / 350));
    }

    /** Line HALF-width per decade spacing (q^0.6 since D243, halved
     *  in D244): 1 m: 1.5 cm; 10 m: 6 cm; 100 m: 24 cm. */
    public static double gridHw(double q) {
        return 0.015 * Math.pow(q, 0.6);
    }

    /** Ring k of decade spacing q under the PERSPECTIVE lens: the
     *  strength Viewport3D.updateFloorGrid buckets at 1/256 (eye
     *  height dRef, subscene height subH px, tan(fov/2) tanH) —
     *  the D242 width-coverage attenuation, the D243 density gate,
     *  the D245 grazing-aware + D246 height-gated thresholds. */
    public static double ringStrength(double q, int k, double dRef,
                                      double subH, double tanH) {
        double r = q * (RING_EDGE[k] + RING_EDGE[k + 1]) / 2;
        // projected cell size × grazing: q/upp(dist) · dRef/dist
        double d2 = dRef * dRef + r * r;
        double dist = Math.sqrt(d2);
        double pxCell = q * subH * dRef / (2 * d2 * tanH);
        // WIDTH-COVERAGE attenuation: a quad much thinner than a
        // pixel rasterizes as dash chains (sqrt-grazing)
        double wPx = 2 * gridHw(q)
            * subH / (2 * dist * tanH)
            * Math.sqrt(dRef / dist);
        // DENSITY gate (D243): the 100 m grid appears only once the
        // camera is well away
        double dens = smooth(3 * dRef / q - 0.3);
        // GRAZING-AWARE thresholds (D245), HEIGHT-GATED (D246): the
        // width floor slides 0.04 px steep → 0.008 px edge-on, the
        // long reach unlocking only as the eye rises
        double f = smooth(dRef / dist / 0.45);
        double hf = smooth(dRef / 70);
        double c = 1 - hf * (1 - f);
        double thr = 0.008 + 0.032 * c;
        return gridAlpha(pxCell, c)
            * smooth((wPx - thr) / (3 * thr)) * dens;
    }
}
