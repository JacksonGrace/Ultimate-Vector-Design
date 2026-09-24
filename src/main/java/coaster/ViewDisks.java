package coaster;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** VIEWPORT DISKS — the pure arithmetic behind Viewport3D's on-track
 *  disks (v19.19, D248, item S1). Pure Java, no JavaFX, so
 *  GestureGate pins it headlessly:
 *  - {@link #arcAtNative} / {@link #nativeAtArc}: a speed control's
 *    native x (seconds or meters on the section's OWN axis) ↔ the
 *    GLOBAL arc of the compiled samples — arcAtNative is the plates'
 *    historic arcAtTime mapping (moved here; the DISTANCE branch is
 *    s0 + x), nativeAtArc its inverse. The disk sits at
 *    arcAtNative(x); a viewport drag reads nativeAtArc(nearest
 *    track point) and hands THAT to the strip's own write path, so
 *    disk and diamond coincide by construction;
 *  - {@link #nearestOnPolyline}: the drag's screen-space mapping —
 *    the nearest point on a projected polyline (segment index + u,
 *    u clamped 0..1, NO radius cap — the cursor may leave the track,
 *    the control follows the closest projected track point);
 *  - {@link #rimPoints} / {@link #pickRadiusPx} / {@link #pickScore}
 *    (v19.20, D249, item S3): the WHOLE-DISK pick — RIM_N rim points
 *    of the ring, projected by the viewport, bound the pick radius
 *    (a PICK_PX floor keeps far disks pickable as a point); disks are
 *    ranked by their NORMALIZED distance d²/pickR², so a big near
 *    disk can never steal a far one, strict-nearest in build order
 *    kept on ties;
 *  - {@link #boundarySamples}: the SECTION-BOUNDARY disks' sample
 *    set for one piece — the EXIT sample of every section but the
 *    last (the plain interior boundaries = the graph's single seam
 *    lines, since v19.20 D249; never the piece entry/exit), deduped
 *    by index and by position (1e-6 m). Only exit samples are
 *    placed, never the next section's entry: after the continuation
 *    drop they sit one dt apart, and two disks a fraction of a meter
 *    apart read as a bug. */
public final class ViewDisks {

    private ViewDisks() {}

    /** Speed-control disk core radius (m) — bigger than a node marker
     *  (0.22) so a coincident marker never hides the drag handle. */
    public static final double CTL_DISK_R = 0.26;
    /** Core half-thickness along T (m) — DOUBLED since v19.20 (D249,
     *  item S3, user spec; D248 had 0.035). */
    public static final double CTL_DISK_HT = 0.07;
    /** The lighter outer ring: radius + half-thickness (m) — the
     *  half-thickness doubled with the core's (D249; was 0.015). */
    public static final double CTL_RING_R = 0.30, CTL_RING_HT = 0.03;
    /** The SELECTED control's ring = its type color lightened by this
     *  brightness factor (Color.deriveColor's brightness multiplier,
     *  saturation kept) — v19.20, D249, item S3, user spec: "slightly
     *  brighter", not the D248 white. Feel constant. */
    public static final double CTL_SEL_RING_LIGHTEN = 1.18;
    /** Rim points sampled for the whole-disk pick (every 45°). */
    public static final int RIM_N = 8;
    /** Section-boundary disk radius + half-thickness (m) — LARGER than
     *  the speed-control disks since v19.20 (D249, user spec; D248 had
     *  0.16/0.015, smaller than a node marker): 0.34 > CTL_RING_R
     *  0.30, so at a control starting exactly on a boundary the
     *  boundary disk reads as a ring around the control's. Named for
     *  retune (with Viewport3D.BOUNDARY_COLOR/GLOW). */
    public static final double BOUNDARY_R = 0.34, BOUNDARY_HT = 0.02;
    /** Disk press pick radius FLOOR (px): the pick region is the
     *  projected ring's bounding radius, never less than this (D249,
     *  item S3 — a far disk stays a 12 px point pick); the disk wins
     *  within it at PRESS, node markers keep their 14 px RELEASE pick
     *  elsewhere (a big near disk now shadows markers inside its whole
     *  rim — the D248 precedence, accepted). */
    public static final double PICK_PX = 12;
    /** Position dedupe band for boundary disks (m). */
    public static final double DEDUPE_M = 1e-6;
    /** Rim points of a disk's triangle fan. */
    public static final int DISK_N = 20;

    /** The first sample index of a span's lerp bracket: its i0 — or
     *  the SEAM sample i0 − 1 when the span's first sample sits
     *  strictly past its origin t0 (a continuation / bridged-in span:
     *  its entry sample is dropped, D84, and native x = 0 IS the seam
     *  one dt earlier). The seam sample must itself sit at/after t0
     *  and within 6 m (the piece-gap heuristic) — a detached piece's
     *  first sample sits AT t0, so it never reaches back into another
     *  piece. */
    public static int spanLo(List<FVDIntegrator.Sample> samples,
                             TrackCompiler.Span sp) {
        int lo = sp.i0();
        if (lo > 0 && lo < samples.size()
                && samples.get(lo).t() > sp.t0() + 1e-9
                && samples.get(lo - 1).t() >= sp.t0() - 1e-9
                && samples.get(lo).pos().sub(samples.get(lo - 1).pos())
                    .length() < 6.0) {
            return lo - 1;
        }
        return lo;
    }

    /** GLOBAL arc of a section-native coordinate x: DISTANCE = s0 + x;
     *  TIME = the arc lerped by t between the span's own bracketing
     *  samples — the plates' historic arcAtTime, extended by the seam
     *  sample (spanLo) so a continuation's x = 0 lands ON the seam
     *  (the old clamp parked it one dt in; interior x bitwise).
     *  Clamps to the bracket's first/last sample outside it. */
    public static double arcAtNative(List<FVDIntegrator.Sample> samples,
                                     TrackCompiler.Span sp,
                                     boolean distance, double x) {
        if (distance) return sp.s0() + x;
        double t = sp.t0() + x;
        int lo = spanLo(samples, sp), hi = Math.min(sp.i1(), samples.size() - 1);
        if (t <= samples.get(lo).t()) return samples.get(lo).s();
        if (t >= samples.get(hi).t()) return samples.get(hi).s();
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).t() <= t) lo = mid; else hi = mid;
        }
        double u = (t - samples.get(lo).t())
            / Math.max(1e-12, samples.get(hi).t() - samples.get(lo).t());
        return samples.get(lo).s()
            + u * (samples.get(hi).s() - samples.get(lo).s());
    }

    /** Section-native coordinate of a GLOBAL arc: DISTANCE = s − s0;
     *  TIME = the ride time lerped by s between the span's bracketing
     *  samples, minus t0. The inverse of arcAtNative to lerp dust. */
    public static double nativeAtArc(List<FVDIntegrator.Sample> samples,
                                     TrackCompiler.Span sp,
                                     boolean distance, double s) {
        if (distance) return s - sp.s0();
        int lo = spanLo(samples, sp), hi = Math.min(sp.i1(), samples.size() - 1);
        double t;
        if (s <= samples.get(lo).s()) {
            t = samples.get(lo).t();
        } else if (s >= samples.get(hi).s()) {
            t = samples.get(hi).t();
        } else {
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (samples.get(mid).s() <= s) lo = mid; else hi = mid;
            }
            double u = (s - samples.get(lo).s())
                / Math.max(1e-12, samples.get(hi).s() - samples.get(lo).s());
            t = samples.get(lo).t()
                + u * (samples.get(hi).t() - samples.get(lo).t());
        }
        return t - sp.t0();
    }

    /** n rim points of the ring of radius r about center c in the disk
     *  plane (U cos θ + R sin θ, θ = 2πk/n — the mesh's own rim
     *  parameterization). */
    public static Vec3[] rimPoints(Vec3 c, Vec3 U, Vec3 R, double r, int n) {
        Vec3[] out = new Vec3[Math.max(0, n)];
        for (int k = 0; k < out.length; k++) {
            double th = 2 * Math.PI * k / n;
            out[k] = c.add(U.scale(r * Math.cos(th))).add(R.scale(r * Math.sin(th)));
        }
        return out;
    }

    /** The pick radius (px) of a disk whose center projects to
     *  (cx, cy) and whose rim points project to (rx[k], ry[k]): the
     *  farthest projected rim point's distance from the center, floored
     *  at floorPx. A NaN rim entry (behind the camera) is skipped; no
     *  usable rim point → the floor. */
    public static double pickRadiusPx(double cx, double cy, double[] rx,
                                      double[] ry, double floorPx) {
        double r = floorPx;
        int n = Math.min(rx.length, ry.length);
        for (int k = 0; k < n; k++) {
            if (Double.isNaN(rx[k]) || Double.isNaN(ry[k])) continue;
            r = Math.max(r, Math.hypot(rx[k] - cx, ry[k] - cy));
        }
        return r;
    }

    /** The NORMALIZED pick score of a press at squared distance d2
     *  from a disk of pick radius pickR: d2 / pickR² — inside the
     *  region < 1, on the rim exactly 1. Ranking disks by this score
     *  keeps the nearest-in-build-order rule fair across unequal radii
     *  (a big near disk cannot steal a far one whose score is
     *  smaller). pickR ≤ 0 → +∞ (never hit). */
    public static double pickScore(double d2, double pickR) {
        if (!(pickR > 0)) return Double.POSITIVE_INFINITY;
        return d2 / (pickR * pickR);
    }

    /** The sample indices of a drag polyline over [lo, hi]: every
     *  `stride`-th index from lo, and ALWAYS hi as the last vertex —
     *  a strided walk that skipped hi left the section's end sample
     *  out of the polyline, so an END disk could never reach the
     *  section-end wall on long sections (D248 review fix). lo == hi
     *  → {lo}; hi < lo → empty. */
    public static int[] strideIndices(int lo, int hi, int stride) {
        if (hi < lo) return new int[0];
        int st = Math.max(1, stride);
        int n = (hi - lo) / st + 1;
        boolean addHi = lo + (n - 1) * st != hi;
        int[] out = new int[n + (addHi ? 1 : 0)];
        for (int k = 0; k < n; k++) out[k] = lo + k * st;
        if (addHi) out[n] = hi;
        return out;
    }

    /** Nearest point on the polyline (xs[k], ys[k]) to (px, py):
     *  returns {segment index k, u ∈ [0, 1] along k→k+1, squared
     *  distance}; a NaN vertex (behind the camera) skips both of its
     *  segments; a single usable vertex returns {k, 0, d²}; nothing
     *  usable → null. No radius cap. */
    public static double[] nearestOnPolyline(double[] xs, double[] ys,
                                             double px, double py) {
        double[] best = null;
        double bd = Double.MAX_VALUE;
        int n = Math.min(xs.length, ys.length);
        for (int k = 0; k < n; k++) {
            if (Double.isNaN(xs[k]) || Double.isNaN(ys[k])) continue;
            boolean seg = k + 1 < n && !Double.isNaN(xs[k + 1])
                && !Double.isNaN(ys[k + 1]);
            double u = 0, qx, qy;
            if (seg) {
                double vx = xs[k + 1] - xs[k], vy = ys[k + 1] - ys[k];
                double vv = vx * vx + vy * vy;
                u = vv < 1e-12 ? 0
                    : Math.max(0, Math.min(1,
                        ((px - xs[k]) * vx + (py - ys[k]) * vy) / vv));
                qx = xs[k] + u * vx - px;
                qy = ys[k] + u * vy - py;
            } else {
                qx = xs[k] - px;
                qy = ys[k] - py;
            }
            double d2 = qx * qx + qy * qy;
            if (d2 < bd) {
                bd = d2;
                best = new double[]{ k, u, d2 };
            }
        }
        return best;
    }

    /** The boundary-disk sample indices of ONE piece whose sections
     *  occupy spans[b0 .. b0+nSec−1]: the exit sample (i1, clamped
     *  into the sample list) of every section EXCEPT the last — i.e.
     *  the PLAIN boundaries inside the piece, exactly where the graph
     *  draws a single seam line (v19.20, D249 — user spec; D248 also
     *  emitted the piece's entry and its last exit, which put disks
     *  at run heads/ends, strict seams and bridge exits, where the
     *  graph draws nothing or a double line). Inside a piece no
     *  boundary is strict or a bridge exit (a bridge is always the
     *  last section, so the boundary INTO it gets a disk), and the
     *  piece's entry/exit are run heads/ends or strict/bridge
     *  junctions. Unique by index and by position (DEDUPE_M),
     *  ascending order of first appearance; a one-section piece has
     *  none. */
    public static List<Integer> boundarySamples(
            List<FVDIntegrator.Sample> samples, List<TrackCompiler.Span> spans,
            int b0, int nSec) {
        List<Integer> out = new ArrayList<>();
        if (samples == null || samples.isEmpty() || nSec <= 1
                || b0 < 0 || b0 + nSec > spans.size()) return out;
        Set<Integer> seen = new HashSet<>();
        List<Vec3> placed = new ArrayList<>();
        int n = samples.size();
        int[] cand = new int[nSec - 1];
        for (int k = 0; k < nSec - 1; k++) cand[k] = spans.get(b0 + k).i1();
        for (int idx : cand) {
            int i = Math.max(0, Math.min(idx, n - 1));
            if (!seen.add(i)) continue;
            Vec3 p = samples.get(i).pos();
            boolean dup = false;
            for (Vec3 q : placed) {
                if (q.sub(p).length() <= DEDUPE_M) { dup = true; break; }
            }
            if (dup) continue;
            placed.add(p);
            out.add(i);
        }
        return out;
    }
}
