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
    public Quat baseOrient = Quat.IDENTITY;   // natural orientation

    public final List<Section> sections = new ArrayList<>();

    public TrackPiece(double x, double y, double z, double yawDeg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yawDeg = yawDeg;
    }

    /** Entry pose of the piece's first section. Identity base + yaw 0
     *  = exactly the identity orientation, and yaw 0 in general is
     *  exactly baseOrient (float-exact — the bit-for-bit and split
     *  gates ride on these paths). */
    public TrackPose entryPose() {
        return new TrackPose(new Vec3(x, y, z),
            yawDeg == 0 ? baseOrient : Quat.aboutY(yawDeg).mul(baseOrient));
    }
}
