package coaster;

/**
 * One contiguous stretch of track inside a TrackPiece (SECTIONS-DESIGN
 * §2). Sealed: the Phase-2 modes (geo, bridge, straight/curve,
 * external) join the permits list as they land. Common contract: a
 * section's geometry is generated ONLY from its own control data, its
 * entry pose and its design speed — never from actual speed (§0, the
 * invariant that shapes everything).
 */
public sealed interface Section permits ForceSection {

    /** TIME is today's model; DISTANCE arrives with Phase 2 modes. */
    enum Domain { TIME, DISTANCE }

    double vDesign();

    Domain domain();

    /** Geometry-level reversal (Phase 2; carried in the model now so
     *  FVDD3 has a stable slot for it). */
    boolean reversed();
}
