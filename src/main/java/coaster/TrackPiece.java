package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * A disconnected group of contiguous sections (SECTIONS-DESIGN §2).
 * Placement = origin + heading yaw (rotation about vertical ONLY, per
 * spec — pieces never tilt). The first section takes its entry pose
 * from here; editing one piece can never move another (the §0
 * connect-invariance the Phase-1 gate asserts).
 */
public final class TrackPiece {

    public double x, y, z;      // origin (m); y is height above the ground plane
    public double yawDeg;       // heading, rotation about world Y

    public final List<Section> sections = new ArrayList<>();

    public TrackPiece(double x, double y, double z, double yawDeg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yawDeg = yawDeg;
    }

    /** Entry pose of the piece's first section. yaw 0 = exactly the
     *  identity orientation (float-exact — the bit-for-bit gate rides
     *  on this path). */
    public TrackPose entryPose() {
        return new TrackPose(new Vec3(x, y, z),
            yawDeg == 0 ? Quat.IDENTITY : Quat.aboutY(yawDeg));
    }
}
