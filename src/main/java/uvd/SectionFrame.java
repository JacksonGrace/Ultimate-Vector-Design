package uvd;

import java.util.List;

/**
 * SectionFrame (Stage 2a, v20.2, D259 §4.2) — the WORLD ↔ CANONICAL
 * conversion of ONE straight/curve section, built ONCE per event from
 * the compile already in hand. {@link ScSolve} works in the section's
 * canonical frame (the frame its ScIntegrator run used: position ZERO
 * at the entry, the entry heading stripped — {@code Span.scBounds}
 * records are in it); the consumer converts the cursor's world point
 * / the seam's or head's world pose through this record before the
 * solve and nothing else — no compiled SAMPLE enters the solver.
 *
 * THE ENTRY PAIR (psi, canon) per §4.2 / the §3.5 A1 compile branch as
 * merged: section k > 0 of a piece = the previous Span's exitPsi /
 * exitCanon (TrackCompiler.chainNext's bits); section 0 of an
 * AUTOMATIC follower (Coaster.autoFollower) = the UPSTREAM span's exit
 * pair and its inherited exitTilt (the IDENTITY object behind an
 * untilted upstream, the conflict tilt behind a tilted one — D259.1b
 * run-3 review); section 0 of every other piece (a run head, a locked /
 * non-automatic follower, a bridged-in piece) = the piece's canonical
 * pair with its design tier (TrackCompiler.designCanon — pieceCanon's
 * bits at zero deltas) and the piece's conflict / follow tilt. The tilt
 * is read off the section's OWN Span (exitTilt — the compile stamps the
 * piece's tilt object on every span of the piece).
 *
 * THE LAW: {@code x_c = rotY(−psi) · tilt⁻¹ · (x_w − entryW)}, tangents
 * the same without the translation — the exact inverse of placeLine /
 * rotY + tiltSample. entryW is the section's WORLD entry position (the
 * previous span's exit for a continuation, the piece's x/y/z for a run
 * head / bridged-in piece): a smoothed seam's postShift and the tilt's
 * anchor offset are both already inside that world entry, so neither is
 * a separate input (deviation note in docs/sc-2a-run1-notes.md). The
 * conversion is a floating rotation, so a yawed copy's drag reproduces
 * params to 1e-12, not bitwise (ScSolveGate g); piece x/y/z + yaw moves
 * leave element bits untouched (D199).
 *
 * @param psiDeg    the chain heading (deg) at the section entry
 * @param canon     the heading-stripped canonical entry orientation
 * @param tilt      the rigid conflict / follow tilt (IDENTITY object when none)
 * @param entryW    the section's world entry position
 * @param spanIdx   the flattened span index of the section
 * @param span      the section's Span (scBounds = the per-border records)
 * @param reversed  a legacy reversed section (read-only for every op)
 * @param hasUpstream the section follows a seam / sits at k > 0
 *                  (SEAM_OWNS_WHY) rather than at a run head (ANCHOR_OWNS_WHY)
 */
public record SectionFrame(double psiDeg, Quat canon, Quat tilt, Vec3 entryW,
                           int spanIdx, TrackCompiler.Span span,
                           boolean reversed, boolean hasUpstream) {

    /** The frame of section k of piece p from the compile r; null when
     *  the indices are out of range or the compile does not cover it. */
    public static SectionFrame of(TrackCompiler.Result r, Coaster c, int p, int k) {
        if (r == null || c == null || p < 0 || p >= c.pieces.size()) return null;
        TrackPiece piece = c.pieces.get(p);
        if (k < 0 || k >= piece.sections.size()) return null;
        int si = spanBase(c, p) + k;
        List<TrackCompiler.Span> spans = r.spans();
        if (spans == null || si >= spans.size()) return null;
        TrackCompiler.Span span = spans.get(si);
        Quat tilt = span.exitTilt() == null ? Quat.IDENTITY : span.exitTilt();
        boolean followed = c.followsPrev(p);
        boolean bridged = c.bridgedInto(p);
        TrackCompiler.Span prev = si > 0 ? spans.get(si - 1) : null;
        double psi;
        Quat canon;
        Vec3 entryW;
        if (k > 0 || (c.autoFollower(p) && prev != null)) {
            if (prev == null) return null;
            entryW = prev.exit().pos();
            if (prev.exitCanon() != null) {
                psi = prev.exitPsi();
                canon = prev.exitCanon();
            } else {
                TrackCompiler.CanonPose cp = TrackCompiler.canonOf(prev.exit());
                psi = cp.psiDeg();
                canon = cp.canon();
            }
        } else {
            // a run head, a locked / non-automatic follower, a bridged-in
            // piece: the piece's own pair (its design tier composed in
            // canonical space) — TrackCompiler.designCanon's bits
            Vec3 pos = followed && prev != null ? prev.exit().pos()
                : piece.entryPose().pos();
            TrackCompiler.CanonPose cp = TrackCompiler.designCanon(piece, pos);
            psi = cp.psiDeg();
            canon = cp.canon();
            entryW = pos;
        }
        Section sec = piece.sections.get(k);
        return new SectionFrame(psi, canon, tilt, entryW, si, span,
            sec.reversed(), c.hasUpstream(p, k));
    }

    /** Flattened index of piece p's first section (its first span). */
    static int spanBase(Coaster c, int p) {
        int b = 0;
        for (int q = 0; q < p; q++) b += c.pieces.get(q).sections.size();
        return b;
    }

    /** The canonical entry frame the solver starts from. */
    public ScSolve.Frame entryFrame() {
        return ScSolve.Frame.of(ElementChain.entry(Vec3.ZERO, canon));
    }

    /** The per-border records (null for a non-s/c section). */
    public List<ScIntegrator.Bound> bounds() { return span.scBounds(); }

    /** The predictor's δ at a border: landed − sharp (ZERO without a
     *  blend — bitwise the sharp path, D9). */
    public Vec3 delta(int border) {
        List<ScIntegrator.Bound> b = bounds();
        if (b == null || border < 0 || border >= b.size()) return Vec3.ZERO;
        ScIntegrator.Bound bd = b.get(border);
        return bd.pos().sub(bd.sharpPos());
    }

    private Vec3 rotYNeg(Vec3 v) {
        double rad = Math.toRadians(psiDeg);
        double c = Math.cos(rad), s = Math.sin(rad);
        return new Vec3(c * v.x() - s * v.z(), v.y(), s * v.x() + c * v.z());
    }

    private Vec3 rotYPos(Vec3 v) {
        double rad = Math.toRadians(psiDeg);
        double c = Math.cos(rad), s = Math.sin(rad);
        return new Vec3(c * v.x() + s * v.z(), v.y(), -s * v.x() + c * v.z());
    }

    /** A world point into the canonical frame. */
    public Vec3 toCanonical(Vec3 world) {
        Vec3 v = world.sub(entryW);
        if (tilt != Quat.IDENTITY) v = tilt.conjugate().rotate(v);
        return rotYNeg(v);
    }

    /** A world direction into the canonical frame. */
    public Vec3 toCanonicalDir(Vec3 dir) {
        Vec3 v = tilt != Quat.IDENTITY ? tilt.conjugate().rotate(dir) : dir;
        return rotYNeg(v);
    }

    /** A canonical point into the world. */
    public Vec3 toWorld(Vec3 canonPos) {
        Vec3 v = rotYPos(canonPos);
        if (tilt != Quat.IDENTITY) v = tilt.rotate(v);
        return v.add(entryW);
    }

    /** A canonical direction into the world. */
    public Vec3 toWorldDir(Vec3 dir) {
        Vec3 v = rotYPos(dir);
        return tilt != Quat.IDENTITY ? tilt.rotate(v) : v;
    }

    /** A world pose (position + orientation) as a canonical POSE target
     *  (heading atan2(−T.z, T.x), elevation asin T.y — NaN heading on
     *  vertical track, the HEAD_VERTICAL_WHY case). */
    public ScSolve.Target.Pose poseTarget(TrackPose world) {
        Vec3 p = toCanonical(world.pos());
        Vec3 t = toCanonicalDir(world.T());
        double horiz2 = 1 - t.y() * t.y();
        double head = horiz2 < StraightCurveSection.HELIX_VERT_EPS ? Double.NaN
            : Math.toDegrees(Math.atan2(-t.z(), t.x()));
        double elev = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, t.y()))));
        return new ScSolve.Target.Pose(p, head, elev);
    }
}
