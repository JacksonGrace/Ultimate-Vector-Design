package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * Today's (and Phase 1's only) section mode: three Bezier lanes of
 * force targets over ride time — vertical G, lateral G, roll rate —
 * integrated into geometry at the section's DESIGN speed by
 * TrackCompiler.passG. Pure Java; public mutable fields in the
 * project's Node style (the graph editor edits lanes in place).
 */
public final class ForceSection implements Section {

    /** Lanes 0/1/2 = Gv / Gl / roll rate, each a time-ordered node list.
     *  The list OBJECTS are long-lived: the editor holds references. */
    public final List<List<BezierBaker.Node>> lanes;

    public double vDesign;
    public boolean reversed = false;
    public String name = "";

    public ForceSection(List<List<BezierBaker.Node>> lanes, double vDesign) {
        this.lanes = lanes;
        this.vDesign = vDesign;
    }

    /** Fresh three empty lanes. */
    public static ForceSection empty(double vDesign) {
        List<List<BezierBaker.Node>> l = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) l.add(new ArrayList<>());
        return new ForceSection(l, vDesign);
    }

    @Override public double vDesign() { return vDesign; }

    @Override public Domain domain() { return Domain.TIME; }

    @Override public boolean reversed() { return reversed; }
}
