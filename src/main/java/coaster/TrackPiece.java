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
    public Quat baseOrient = Quat.IDENTITY;   // natural orientation

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
     *  and split gates ride on these paths). */
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
}
