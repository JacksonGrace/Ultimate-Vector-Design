package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * A disconnected group of contiguous sections (SECTIONS-DESIGN §2).
 * Placement = origin + BASE ORIENTATION + heading-yaw delta (v12.1,
 * D71). baseOrient is the piece's NATURAL orientation — identity for
 * pieces made from scratch, the exact boundary orientation for pieces
 * made by SPLITTING (user rule: splitting must not change the track's
 * orientation AT ALL), the reversed exit orientation after a piece
 * reversal. The EDIT control stays yaw-only (yawDeg, about world Y, on
 * top of baseOrient — pitch editing may come later per user); zeroing
 * yawDeg reverts to the original orientation exactly. The first
 * section takes its entry pose from here; editing one piece can never
 * move another (the §0 connect-invariance the Phase-1 gate asserts).
 */
public final class TrackPiece {

    public double x, y, z;      // origin (m); y is height above the ground plane
    public double yawDeg;       // heading DELTA, rotation about world Y
    /** Orientation deltas beyond yaw (v13.13, D87 — the roadmap's
     *  "pitch editing if revertible", arrived through the bridge
     *  panel): pitch rotates about the CURRENT heading's horizontal
     *  right axis (changes pitch only — heading preserved), roll
     *  about the resulting heading (changes bank only). Applied in
     *  the order yaw → pitch → roll, each delta controls exactly one
     *  Euler quantity of the entry frame; zeroing reverts exactly.
     *  Persisted as `piece` fields 9-10 (missing = 0). */
    public double pitchDeg;
    public double rollDeg;
    /** UI edit-guards for the Placement pitch/roll rows (v15.3, D101):
     *  locked = the spinner is frozen, matching the bridge panel's
     *  padlock defaults. With a bridge connecting INTO this piece the
     *  bridge's own lock fields govern instead (one shared state);
     *  these apply only to un-bridged pieces and are deliberately NOT
     *  persisted (pure edit-guards, like the graph's axis locks). */
    public boolean uiLockPitch = true;
    public boolean uiLockRoll = true;
    public Quat baseOrient = Quat.IDENTITY;   // natural orientation

    /** STRICT SEAM (v17.1, D135): this piece FOLLOWS the previous
     *  piece's exit — its entry POSITION always sits on the seam, its
     *  entry orientation composes per the three locks (unlocked axes
     *  ALIGN to the incoming frame, locked axes keep the FROZEN
     *  values stored in this piece's own placement — which never
     *  mutates by following, so relocking an axis returns it to the
     *  original). The whole piece places RIGIDLY (the D100 tilt
     *  machinery): upstream edits can never reshape it, only move/
     *  re-aim it. Its design speeds stay independent (piece-first
     *  reconcile exclusion) — upstream speed changes surface as an
     *  honest ≠v conflict while the real ride flows through (same
     *  run). Ignored when there is no previous piece or the previous
     *  piece ends with a bridge (the bridge IS the connection).
     *  Persisted as `piece` fields 11-14 (missing = detached). */
    public boolean followPrev = false;
    public boolean sLockYaw = false;    // heading follows by default
    public boolean sLockPitch = true;   // pitch/roll DISCONTINUOUS by
    public boolean sLockRoll = true;    //   default (bridge convention)
    /** The DISCONTINUOUS axis values (v17.1 feedback round, D136):
     *  a locked axis holds THIS angle (deg) — user-editable in the
     *  seam settings (spinners; ↺ restores the value the seam was
     *  created with, re-read from the stored placement). Initialized
     *  from the stored placement at strict-creation; toggling an axis
     *  discontinuous captures the CURRENT followed value (no jump).
     *  Persisted as `piece` fields 15-17. */
    public double sYawDeg;
    public double sPitchDeg;
    public double sRollDeg;

    public final List<Section> sections = new ArrayList<>();

    public TrackPiece(double x, double y, double z, double yawDeg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yawDeg = yawDeg;
    }

    /** Entry pose of the piece's first section. Identity base + all
     *  deltas 0 = exactly the identity orientation, and deltas 0 in
     *  general is exactly baseOrient (float-exact — the bit-for-bit
     *  and split gates ride on these paths). Always equals
     *  tilt() ∘ genPose() — same math, kept verbatim for the
     *  identity-path guarantees. */
    public TrackPose entryPose() {
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        if (pitchDeg != 0) {
            Vec3 t = q.T();
            // horizontal right of the heading (left-handed world:
            // rider-right of +X heading is −Z, i.e. U×T for level
            // frames); rotating about it moves pitch only
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                q = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(pitchDeg)).mul(q);
            }
        }
        if (rollDeg != 0) {
            q = Quat.aboutAxis(q.T(), Math.toRadians(rollDeg)).mul(q);
        }
        return new TrackPose(new Vec3(x, y, z), q);
    }

    /** GENERATION pose (v15.2, D100): baseOrient ⊕ yaw only — the
     *  DESIGN orientation the sections integrate at. The pitch/roll
     *  deltas are a RIGID placement transform on top (tilt()), so
     *  tilting a piece PRESERVES its geometric shape instead of
     *  re-solving it against gravity (user rule: "make sure the real
     *  geometric shape is being preserved"); the felt-G difference
     *  the tilt causes shows as the design/real split. Yaw stays in
     *  the generation pose — rotation about world Y commutes with
     *  gravity exactly, so its placement was always rigid. */
    public TrackPose genPose() {
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        return new TrackPose(new Vec3(x, y, z), q);
    }

    /** BAKE every orientation delta into the base orientation (v16.1,
     *  D106 — the resolve ops' orientation resolution; the D100
     *  split/reverse corner as an explicit op): the entry pose is
     *  IDENTICAL afterwards, but genPose == entryPose from here on,
     *  so sections generate FROM the real frame — force sections
     *  re-solve against gravity there and the tilt's design/real
     *  orientation split closes. Yaw folds in too (the split/reverse
     *  convention: the natural orientation is redefined as the
     *  current one; zeroing the deltas afterwards changes nothing). */
    public void bakeTilt() {
        baseOrient = entryPose().orient();
        yawDeg = 0;
        pitchDeg = 0;
        rollDeg = 0;
    }

    /** The rigid TILT the pitch/roll deltas add on top of genPose():
     *  entryPose().orient() == tilt().mul(genPose().orient()) by
     *  construction (identical axis math). Returns Quat.IDENTITY —
     *  the same object — when both deltas are 0, so the compiler's
     *  identity short-circuit keeps every untilted piece bitwise. */
    public Quat tilt() {
        if (pitchDeg == 0 && rollDeg == 0) return Quat.IDENTITY;
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        Quat tilt = Quat.IDENTITY;
        if (pitchDeg != 0) {
            Vec3 t = q.T();
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                Quat pq = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(pitchDeg));
                tilt = pq;
                q = pq.mul(q);
            }
        }
        if (rollDeg != 0) {
            tilt = Quat.aboutAxis(q.T(), Math.toRadians(rollDeg)).mul(tilt);
        }
        return tilt;
    }
}
