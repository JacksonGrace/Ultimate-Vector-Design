package uvd;

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

    /** TIME is the classic model; DISTANCE arrived with straight/curve
     *  sections (v11.0) and opened to force/geo lanes with the domain
     *  toggle (v16.0, D103): the same lanes authored over METERS of
     *  track instead of seconds of ride. */
    enum Domain { TIME, DISTANCE }

    double vDesign();

    /** Generic write access (v11.0): the Start-speed spinner and the
     *  editor reach vDesign/reversed through the interface now that not
     *  every section is a LaneSection. */
    void vDesign(double v);

    Domain domain();

    /** Domain write access (v16.0, D103): only force/geo lanes are
     *  domain-switchable — s/c is inherently DISTANCE and a bridge is
     *  world geometry; both ignore writes. */
    default void domain(Domain d) {}

    /** CROSS-DOMAIN VIEW (v16.0, D103): while true the strip DISPLAYS
     *  (and edits through) the OTHER domain's axis — the underlying
     *  lanes/elements stay native, the editor warps x through the
     *  section's compiled map. Session state like editInfluence:
     *  snapshotted in DocState, never saved to file. Bridges have no
     *  view (their strip is read-only time-derived curves). */
    default boolean viewOtherDomain() { return false; }

    default void viewOtherDomain(boolean v) {}

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

    /** SPEED CONTROLS (Phase 3, v18.0 D160): lift/brake/launch
     *  intervals on the section's own authoring axis, applied to the
     *  design AND real speed alike. Bridges have none (pure geometry)
     *  — the default is an immutable empty list; lane and s/c
     *  sections override with their live lists. */
    default java.util.List<SpeedControl> speedControls() {
        return java.util.List.of();
    }

    /** Plain speed-line diamonds (potential control boundaries) on
     *  the section's authoring axis — visual markers only. */
    default java.util.List<Double> speedMarks() {
        return java.util.List.of();
    }

    /** Can this section carry speed controls at all? */
    default boolean speedControllable() { return false; }
}
