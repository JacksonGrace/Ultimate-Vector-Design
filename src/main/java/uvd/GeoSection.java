package uvd;

import java.util.List;

/**
 * GEO section (Phase 2, SECTIONS-DESIGN §3): the designer specifies
 * BODY RATES directly — lanes 0/1/2 = pitch / yaw / roll rate, all in
 * degrees per second of ride time. Integration is direct (GeoIntegrator
 * — no solver): the frame rotates at exactly the authored rates and the
 * track goes wherever that points it. Gravity plays no part in the
 * SHAPE; it only shows up in the felt forces Pass A reports. vDesign
 * still shapes TIME-domain geometry (position advances at the design
 * speed profile), so the §0 freeze applies to geo sections exactly as
 * to force sections.
 */
public final class GeoSection extends LaneSection {

    public GeoSection(List<List<BezierBaker.Node>> lanes, double vDesign) {
        super(lanes, vDesign);
    }

    /** Fresh three empty lanes (loader scaffolding). */
    public static GeoSection empty(double vDesign) {
        return new GeoSection(emptyLanes(), vDesign);
    }

    /** A 1 s all-zero-rate section: straight track continuing the entry
     *  orientation. (No longer the '+' menu's default — v10.1 holds the
     *  end rates instead, matching force-after-force; kept as the
     *  obvious utility and for the headless gates.) */
    public static GeoSection straight(double vDesign) {
        return new GeoSection(holdLanes(0, 0, 0), vDesign);
    }
}
