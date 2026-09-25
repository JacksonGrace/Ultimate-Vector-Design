package uvd;

/**
 * A point on (or entry to) the track: position + orientation, plus the
 * running global offsets a section chain accumulates (SECTIONS-DESIGN
 * §2 EntryPose contract). A section's exit pose is a pure function of
 * (entry pose, its own geometry); the next section consumes it. Pure
 * Java, immutable.
 */
public record TrackPose(Vec3 pos, Quat orient, double s0, double t0) {

    public TrackPose(Vec3 pos, Quat orient) { this(pos, orient, 0, 0); }

    public Vec3 T() { return orient.T(); }
    public Vec3 U() { return orient.U(); }
}
