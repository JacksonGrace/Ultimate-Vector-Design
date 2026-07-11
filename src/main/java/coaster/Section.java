package coaster;

/**
 * One contiguous stretch of track inside a TrackPiece (SECTIONS-DESIGN
 * §2). Sealed: the three-lane modes (force, geo) come in through
 * LaneSection; the remaining Phase-2 modes (bridge, straight/curve,
 * external) join the permits list as they land. Common contract: a
 * section's geometry is generated ONLY from its own control data, its
 * entry pose and its design speed — never from actual speed (§0, the
 * invariant that shapes everything).
 */
public sealed interface Section
        permits LaneSection, StraightCurveSection, BridgeSection {

    /** TIME is today's model; DISTANCE arrived with straight/curve
     *  sections (v11.0) — force/geo stay TIME until the domain toggle. */
    enum Domain { TIME, DISTANCE }

    double vDesign();

    /** Generic write access (v11.0): the Start-speed spinner and the
     *  editor reach vDesign/reversed through the interface now that not
     *  every section is a LaneSection. */
    void vDesign(double v);

    Domain domain();

    /** Geometry-level reversal (Phase 2; carried in the model now so
     *  FVDD3 has a stable slot for it). */
    boolean reversed();

    void reversed(boolean r);

    /** PINNED design speed (v14.5, D97 — user rule): this section's
     *  vDesign is deliberately independent of what arrives — the
     *  same-piece speed reconcile (D95) leaves it alone, so its
     *  design/true split SURVIVES upstream edits. Set where a bridge
     *  convert stitches formerly-separate pieces together (the split
     *  must stay exactly as it was before the conversion); cleared by
     *  the badge menu's "Resolve speed conflict" ops. Default false;
     *  bridges never pin (pure geometry in Pass D). */
    default boolean vPinned() { return false; }

    default void vPinned(boolean p) { }
}
