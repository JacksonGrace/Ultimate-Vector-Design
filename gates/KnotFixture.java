import coaster.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic STALL-KNOT fixture (2026-08-05): the DetermGates used
 * to read the user's on-disk "determinism test" save, but he re-saves
 * test files — on 2026-08-05 it came back knotless (2.7 KB, min
 * vDesign 5.0). This builds the regime programmatically: a geo climb
 * that eats the kinetic energy, chained into a force section the
 * reconcile floors at 0.1 m/s — curvature ~ 1/v² = the chaotic
 * knife-edge geometry the D197/D198 contracts gate.
 */
public class KnotFixture {

    static List<BezierBaker.Node> nodes(double[][] tv) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : tv) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    /** Piece: geo section A pitches to ~72° and holds (the climb far
     *  outruns a 12 m/s entry's ~7 m of kinetic height), then force
     *  section B (Gv wave + a fast roll) whose re-frozen design speed
     *  lands on the 0.1 floor. */
    public static Coaster build() {
        List<List<BezierBaker.Node>> ga = new ArrayList<>();
        ga.add(nodes(new double[][]{ {0, 0}, {1, 24}, {4, 24}, {5, 0}, {9, 0} }));
        ga.add(nodes(new double[][]{ {0, 0}, {9, 0} }));
        ga.add(nodes(new double[][]{ {0, 0}, {9, 0} }));
        List<List<BezierBaker.Node>> fb = new ArrayList<>();
        fb.add(nodes(new double[][]{ {0, 1}, {4, 1.4}, {8, 1} }));
        fb.add(nodes(new double[][]{ {0, 0}, {8, 0} }));
        fb.add(nodes(new double[][]{ {0, 0}, {4, 40}, {8, 0} }));
        Coaster c = new Coaster();
        c.v0 = 12;
        TrackPiece p = new TrackPiece(0, 30, 0, 0);
        p.sections.add(new GeoSection(ga, 12));
        p.sections.add(new ForceSection(fb, 12));
        c.pieces.add(p);
        return c;
    }
}
