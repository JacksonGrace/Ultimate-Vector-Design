package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * Common base of the LANE-DRIVEN section modes (Phase 2, SECTIONS-DESIGN
 * §3): three Bezier lanes of control values over section-local ride
 * time, integrated into geometry at the section's DESIGN speed by
 * TrackCompiler.passG. What the lanes MEAN is the subclass's business
 * (force targets vs body rates); everything the graph editor touches —
 * the lane lists, vDesign, reversed — lives here so it can edit any
 * lane section through one type. Pure Java; public mutable fields in
 * the project's Node style (the editor edits lanes in place).
 *
 * Phase-2 modes that are NOT three-lane (bridge, straight/curve,
 * external) implement Section directly; the editor grows a real
 * per-mode surface when they land.
 */
public sealed abstract class LaneSection implements Section
        permits ForceSection, GeoSection {

    /** Lanes 0/1/2 — subclass defines the semantics. The list OBJECTS
     *  are long-lived: the editor holds references. */
    public final List<List<BezierBaker.Node>> lanes;

    public double vDesign;
    public boolean reversed = false;
    public String name = "";

    protected LaneSection(List<List<BezierBaker.Node>> lanes, double vDesign) {
        this.lanes = lanes;
        this.vDesign = vDesign;
    }

    /** Fresh three empty lanes (loader scaffolding). */
    protected static List<List<BezierBaker.Node>> emptyLanes() {
        List<List<BezierBaker.Node>> l = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) l.add(new ArrayList<>());
        return l;
    }

    /** Two-node 1 s lanes holding the given per-lane values (flat
     *  default handles) — the '+' menu's hold/straight continuations. */
    protected static List<List<BezierBaker.Node>> holdLanes(double v0, double v1, double v2) {
        double[] vals = { v0, v1, v2 };
        List<List<BezierBaker.Node>> lanes = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> lane = new ArrayList<>(2);
            lane.add(new BezierBaker.Node(0, vals[i]));
            lane.add(new BezierBaker.Node(1, vals[i]));
            BezierBaker.applyDefaultHandles(lane);
            lanes.add(lane);
        }
        return lanes;
    }

    @Override public double vDesign() { return vDesign; }

    @Override public void vDesign(double v) { vDesign = v; }

    @Override public Domain domain() { return Domain.TIME; }

    @Override public boolean reversed() { return reversed; }

    @Override public void reversed(boolean r) { reversed = r; }
}
