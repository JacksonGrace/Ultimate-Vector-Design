package uvd;

import java.util.List;

/**
 * FORCE section (today's default mode): three Bezier lanes of force
 * targets over ride time — lanes 0/1/2 = vertical G / lateral G / roll
 * rate (deg/s) — integrated into geometry at the section's DESIGN speed
 * by TrackCompiler.passG (the original FVD pipeline, bit-for-bit gated).
 */
public final class ForceSection extends LaneSection {

    public ForceSection(List<List<BezierBaker.Node>> lanes, double vDesign) {
        super(lanes, vDesign);
    }

    /** Fresh three empty lanes. */
    public static ForceSection empty(double vDesign) {
        return new ForceSection(emptyLanes(), vDesign);
    }
}
