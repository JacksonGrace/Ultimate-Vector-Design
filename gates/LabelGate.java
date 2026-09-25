import uvd.*;

import java.util.*;

/** D248 gate — SECTION + JUNCTION NAMING (v19.19, batch 33 item L;
 *  the D249 label REVERTS + display name since v19.20, batch 34 item
 *  L — checks 9-11):
 *  the pure-model text every label site reads (SectionNames) and the
 *  ONE run-topology definition it rides (Coaster.endsWithBridge /
 *  followsPrev / bridgedInto / runStartOf / runEndOf — GraphEditor's
 *  runStart/runEnd/followsPrev delegate to these).
 *  Pins the edge tables scratchpad/b33_edges_L-labels.md and
 *  b34_edges_L-labels.md:
 *   1  per-TYPE run-wide titles on a single piece (Force / Geometric /
 *      S/C, flags never change the type word), the run head's owner
 *      label (the D248 short form "Geo 2" is GONE since D249)
 *   2  a BRIDGED run: "Bridge Section 1", the target's "Force Section
 *      2", the junction "Strict Seam 1 (bridge)" (bridge test FIRST —
 *      a stale followPrev flag on the bridged-in piece is inert), the
 *      bridge section's OWN owner = its exit junction
 *   3  a STRICT run + a bridge→strict CHAIN: "Strict Seam 1", the
 *      chain's "Strict Seam 1 (bridge)" / "Strict Seam 2", counters
 *      running across all three pieces
 *   4  s/c + influenced + distance + reversed tags
 *   5  an ORPHAN bridge: title, would-be exit owner label, no junction
 *      past the list end
 *   6  DETACHED runs restart the numbering; tags order
 *   7  out-of-range / empty-coaster guards
 *   8  runStartOf/runEndOf == the historic GraphEditor loops on every
 *      piece of every fixture (the R-item delegation contract)
 *   9  (D249) the DRAWN badge text badgeLabel = short word + SPACED
 *      tags, no number: "Force", "Geo", "S/C", "Geo ·m", "Force ·m ◀
 *      fx", "Bridge", "Bridge !"
 *  10  (D249) the bridge's badge-menu item "Bridge settings…" and the
 *      window title "Bridge settings (Strict Seam n)" — n = the
 *      bridge's exit junction, live and orphan alike, while the
 *      toolbar button keeps "Strict Seam n (bridge) ▴"
 *  11  (D249) the .nl2elem description carries the display name
 *      "Ultimate Vector Design Export" */
public class LabelGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + name
            + (detail.isEmpty() ? "" : "  (" + detail + ")")); }
        else    { fail++; System.out.println("FAIL  " + name
            + "  (" + detail + ")"); }
    }

    static void eq(String want, String got, String name) {
        check(Objects.equals(want, got), name,
            "want \"" + want + "\" got \"" + got + "\"");
    }

    static void eq(int want, int got, String name) {
        check(want == got, name, "want " + want + " got " + got);
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> forceLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {2, 1.5}, {4, 0.5}, {6, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.3}, {4, -0.2}, {6, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 20}, {4, -15}, {6, 0} }));
        return ls;
    }

    static List<List<BezierBaker.Node>> geoLanes() {
        List<List<BezierBaker.Node>> gl = new ArrayList<>();
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 8}, {3, -4} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 12}, {3, 20} }));
        gl.add(lane(new double[][]{ {0, 0}, {1.5, 15}, {3, 0} }));
        return gl;
    }

    static ForceSection force(double v) {
        return new ForceSection(forceLanes(), v);
    }

    static GeoSection geo(double v) {
        return new GeoSection(geoLanes(), v);
    }

    static StraightCurveSection sc() {
        StraightCurveSection sc = StraightCurveSection.straight(8, 24);
        StraightCurveSection.Element arc = new StraightCurveSection.Element();
        arc.kind = StraightCurveSection.Kind.CURVE;
        arc.radius = 20;
        arc.angleDeg = 75;
        arc.dirDeg = 0;
        arc.smoothIn = 3;
        sc.elements.add(arc);
        return sc;
    }

    /** Piece a [Force, Bridge] → piece b (its sections given). */
    static Coaster bridgeFixture(List<Section> bSecs) {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(force(26));
        c.pieces.add(a);
        TrackPiece b = new TrackPiece(150, 16, 20, 15);
        b.sections.addAll(bSecs);
        c.pieces.add(b);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        BridgeSection bs = BridgeSection.solve(r0.spans().get(0).exit(),
            b.entryPose(), r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_NONE;
        a.sections.add(bs);
        return c;
    }

    static Coaster orphanFixture() {
        Coaster c = new Coaster();
        TrackPiece a = new TrackPiece(0, 21, 0, 0);
        a.sections.add(force(26));
        c.pieces.add(a);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        TrackPose exit = r0.spans().get(0).exit();
        TrackPose tgt = new TrackPose(exit.pos().add(new Vec3(30, -4, 6)),
            exit.orient());
        BridgeSection bs = BridgeSection.solve(exit, tgt,
            r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_NONE;
        a.sections.add(bs);
        return c;
    }

    // ---- the HISTORIC GraphEditor loops (pre-D248), re-stated here
    // as the reference for the delegation contract ----

    static boolean refEnds(Coaster c, int p) {
        List<Section> s = c.pieces.get(p).sections;
        return !s.isEmpty() && s.get(s.size() - 1) instanceof BridgeSection;
    }

    static boolean refFollows(Coaster c, int p) {
        return p > 0 && c.pieces.get(p).followPrev
            && !refEnds(c, p - 1)
            && !c.pieces.get(p - 1).sections.isEmpty()
            && !c.pieces.get(p).sections.isEmpty();
    }

    static int refStart(Coaster c, int p) {
        while (p > 0 && (refEnds(c, p - 1) || refFollows(c, p))) p--;
        return p;
    }

    static int refEnd(Coaster c, int p) {
        while (p < c.pieces.size() - 1
                && (refEnds(c, p) || refFollows(c, p + 1))) p++;
        return p;
    }

    static void topology(String name, Coaster c) {
        boolean ok = true;
        StringBuilder why = new StringBuilder();
        for (int p = 0; p < c.pieces.size(); p++) {
            if (c.runStartOf(p) != refStart(c, p)
                    || c.runEndOf(p) != refEnd(c, p)
                    || c.followsPrev(p) != refFollows(c, p)
                    || c.endsWithBridge(p) != refEnds(c, p)) {
                ok = false;
                why.append(" p").append(p);
            }
        }
        check(ok, "8 " + name + ": runStartOf/runEndOf/followsPrev/"
            + "endsWithBridge == the historic editor loops", why.toString());
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);

        // ---------- 1: one piece, mixed types ----------
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            GeoSection g1 = geo(24);
            g1.yawPitchGlobal = true;
            g1.rollGlobal = true;
            g1.ypAbsolute = true;
            g1.rollAbsolute = true;
            g1.ratePerMeter = true;
            p.sections.add(g1);
            p.sections.add(geo(24));
            ForceSection f2 = force(22);
            SpeedControl ctl = new SpeedControl(1.0, 1.2);
            ctl.speed = 22;
            f2.speedCtls.add(ctl);
            p.sections.add(f2);
            p.sections.add(sc());
            c.pieces.add(p);
            eq("Force Section 1", c == null ? null
                : SectionNames.sectionTitle(c, 0, 0), "1a force first");
            eq("Geometric Section 1", SectionNames.sectionTitle(c, 0, 1),
                "1a geo (global/abs/°m flags never change the type word)");
            eq("Geometric Section 2", SectionNames.sectionTitle(c, 0, 2),
                "1a second geo counts per type");
            eq("Force Section 2", SectionNames.sectionTitle(c, 0, 3),
                "1b force with a speed control = Force Section 2");
            eq("S/C Section 1", SectionNames.sectionTitle(c, 0, 4),
                "1c straight/curve = S/C Section 1");
            eq(2, SectionNames.sectionOrdinal(c, 0, 2), "1d ordinal alone");
            eq(0, SectionNames.junctionOrdinal(c, 0), "1e head: no junction");
            eq(null, SectionNames.junctionTitle(c, 0), "1e head: null title");
            eq("Track Piece ▴", SectionNames.ownerButtonLabel(c, 0, 2),
                "1f head owner = Track Piece ▴");
            eq("", SectionNames.tags(c.pieces.get(0).sections.get(1), false),
                "1g flags alone add no tag");
            topology("fixture 1", c);
        }

        // ---------- 2: a bridged run ----------
        {
            Coaster c = bridgeFixture(List.of(force(24), geo(24)));
            eq("Force Section 1", SectionNames.sectionTitle(c, 0, 0), "2a head force");
            eq("Bridge Section 1", SectionNames.sectionTitle(c, 0, 1), "2a bridge title");
            eq("Force Section 2", SectionNames.sectionTitle(c, 1, 0),
                "2b the target's force continues the run count");
            eq("Geometric Section 1", SectionNames.sectionTitle(c, 1, 1),
                "2b the target's geo starts its own type count");
            check(c.bridgedInto(1) && !c.followsPrev(1),
                "2c bridged-in: bridgedInto true, followsPrev false", "");
            eq("Strict Seam 1 (bridge)", SectionNames.junctionTitle(c, 1),
                "2c bridge exit junction");
            eq(1, SectionNames.junctionOrdinal(c, 1), "2c ordinal 1");
            c.pieces.get(1).followPrev = true;   // a stale flag (D135: inert)
            check(!c.followsPrev(1), "2d stale followPrev on a bridged-in "
                + "piece stays inert", "");
            eq("Strict Seam 1 (bridge)", SectionNames.junctionTitle(c, 1),
                "2d … and the junction keeps the (bridge) form");
            eq(0, c.runStartOf(1), "2d run start through the bridge");
            eq(1, c.runEndOf(0), "2d run end through the bridge");
            c.pieces.get(1).followPrev = false;
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 0, 1),
                "2e the bridge section's owner = its own exit junction");
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 1, 0),
                "2e the target's sections are owned by the bridge exit");
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 1, 1),
                "2e … every section of the target");
            eq("Track Piece ▴", SectionNames.ownerButtonLabel(c, 0, 0),
                "2e the head's force stays Track Piece ▴");
            eq("", SectionNames.tags(c.pieces.get(0).sections.get(1), false),
                "2f live bridge: no tag");
            eq(" !", SectionNames.tags(c.pieces.get(0).sections.get(1), true),
                "2f dirty bridge tag");
            topology("fixture 2", c);
        }

        // ---------- 3: a strict run + a bridge→strict chain ----------
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            p.sections.add(force(23));
            c.pieces.add(p);
            int f = c.makeSeamStrict(0, 1, DT);
            check(f == 1, "3a makeSeamStrict → piece 1", "got " + f);
            check(c.followsPrev(1) && !c.bridgedInto(1),
                "3a follower: followsPrev true, bridgedInto false", "");
            eq("Strict Seam 1", SectionNames.junctionTitle(c, 1), "3b plain seam");
            eq("Force Section 2", SectionNames.sectionTitle(c, 1, 0),
                "3b the follower's section continues the count");
            eq("Strict Seam 1 ▴", SectionNames.ownerButtonLabel(c, 1, 0),
                "3b follower owner label");
            eq(0, c.runStartOf(1), "3b run start through the seam");
            eq(1, c.runEndOf(0), "3b run end through the seam");
            topology("fixture 3 (strict)", c);
        }
        {
            // head [Force, Bridge] → P1 [Force, Geo] → strict → P2 [Geo]
            Coaster c = bridgeFixture(List.of(force(24), geo(24)));
            int f = c.makeSeamStrict(1, 1, DT);
            check(f == 2, "3c chain: makeSeamStrict on the bridged-in piece → piece 2",
                "got " + f);
            eq("Strict Seam 1 (bridge)", SectionNames.junctionTitle(c, 1),
                "3c chain junction 1 = the bridge exit");
            eq("Strict Seam 2", SectionNames.junctionTitle(c, 2),
                "3c chain junction 2 = the strict seam");
            eq("Geometric Section 1", SectionNames.sectionTitle(c, 2, 0),
                "3c the follower's geo (the split moved P1's only geo)");
            eq("Force Section 2", SectionNames.sectionTitle(c, 1, 0),
                "3c P1's force still Force Section 2");
            eq("Strict Seam 2 ▴", SectionNames.ownerButtonLabel(c, 2, 0),
                "3c follower owner past a bridge");
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 1, 0),
                "3c bridged-in owner unchanged by the seam after it");
            eq(0, c.runStartOf(2), "3c run start walks bridge + seam");
            eq(2, c.runEndOf(0), "3c run end walks bridge + seam");
            topology("fixture 3 (chain)", c);
        }

        // ---------- 4: tags ----------
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection dist = force(26);
            dist.domain(Section.Domain.DISTANCE);
            p.sections.add(dist);
            ForceSection infl = force(24);
            Coaster.enterInfluence(infl, 6);
            p.sections.add(infl);
            ForceSection rev = force(24);
            rev.reversed(true);
            p.sections.add(rev);
            ForceSection all = force(24);
            all.domain(Section.Domain.DISTANCE);
            Coaster.enterInfluence(all, 6);
            all.reversed(true);
            p.sections.add(all);
            p.sections.add(sc());
            c.pieces.add(p);
            eq(" ·m", SectionNames.tags(dist, false), "4a distance tag");
            eq(" fx", SectionNames.tags(infl, false), "4a influence tag");
            eq(" ◀", SectionNames.tags(rev, false), "4a reversed tag");
            eq(" ·m ◀ fx", SectionNames.tags(all, false), "4b tag order ·m ◀ fx");
            eq("", SectionNames.tags(p.sections.get(4), false), "4a s/c: no tag");
            eq("Force Section 2", SectionNames.sectionTitle(c, 0, 1),
                "4c influenced force keeps the type word");
            eq("Force Section 4", SectionNames.sectionTitle(c, 0, 3),
                "4c distance + influenced + reversed = still Force");
            eq("S/C Section 1", SectionNames.sectionTitle(c, 0, 4), "4c s/c");
        }

        // ---------- 5: an orphan bridge ----------
        {
            Coaster c = orphanFixture();
            eq("Bridge Section 1", SectionNames.sectionTitle(c, 0, 1),
                "5a orphan bridge title");
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 0, 1),
                "5b orphan bridge owner = the would-be exit (item W grays it)");
            eq(null, SectionNames.junctionTitle(c, 1),
                "5b no junction past the list end");
            eq(0, SectionNames.junctionOrdinal(c, 1), "5b ordinal 0 out of range");
            check(!c.bridgedInto(1), "5b bridgedInto out of range false", "");
            eq(0, c.runEndOf(0), "5b run end stops at the orphan");
            topology("fixture 5", c);
        }

        // ---------- 6: detached runs restart the numbering ----------
        {
            Coaster c = new Coaster();
            TrackPiece a = new TrackPiece(0, 21, 0, 0);
            a.sections.add(force(26));
            a.sections.add(geo(24));
            c.pieces.add(a);
            TrackPiece b = new TrackPiece(80, 15, 40, 30);   // detached
            b.sections.add(force(22));
            b.sections.add(geo(22));
            c.pieces.add(b);
            eq("Force Section 1", SectionNames.sectionTitle(c, 1, 0),
                "6b detached piece restarts at Force Section 1");
            eq("Geometric Section 1", SectionNames.sectionTitle(c, 1, 1),
                "6b … and Geometric Section 1");
            eq(0, SectionNames.junctionOrdinal(c, 1), "6c detached = its own head");
            eq("Track Piece ▴", SectionNames.ownerButtonLabel(c, 1, 1),
                "6c detached owner = Track Piece ▴");
            eq(1, c.runStartOf(1), "6c runStartOf(detached) = itself");
            eq(0, c.runEndOf(0), "6c runEndOf(head) stops before it");
            b.followPrev = true;   // an actual strict follow now
            eq("Force Section 2", SectionNames.sectionTitle(c, 1, 0),
                "6d followPrev joins the run: Force Section 2");
            eq("Strict Seam 1", SectionNames.junctionTitle(c, 1), "6d seam title");
            topology("fixture 6", c);
        }

        // ---------- 7: guards ----------
        {
            Coaster c = new Coaster();
            eq("Section", SectionNames.sectionTitle(c, 0, 0), "7a empty coaster title");
            eq(0, SectionNames.sectionOrdinal(c, 0, 0), "7a empty ordinal 0");
            eq(0, SectionNames.junctionOrdinal(c, 0), "7a empty junction 0");
            eq("Track Piece ▴", SectionNames.ownerButtonLabel(c, 0, 0),
                "7a empty owner falls back to Track Piece ▴");
            check(!c.followsPrev(0) && !c.endsWithBridge(0) && !c.bridgedInto(0),
                "7a empty coaster topology false", "");
            eq(0, c.runStartOf(0), "7a runStartOf(0) empty = 0");
            eq(-1, c.runStartOf(-1), "7b runStartOf(-1) = -1");
            eq(9, c.runEndOf(9), "7b runEndOf(9) = 9");
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            c.pieces.add(p);
            eq("Section", SectionNames.sectionTitle(c, 0, 3), "7c k out of range");
            eq("Section", SectionNames.sectionTitle(c, 2, 0), "7c p out of range");
            eq(null, SectionNames.junctionTitle(c, 4), "7c junction p out of range");
            check(!c.followsPrev(-1) && !c.endsWithBridge(7) && !c.bridgedInto(3),
                "7c out-of-range topology false", "");
            TrackPiece empty = new TrackPiece(5, 21, 5, 0);   // sectionless
            empty.followPrev = true;
            c.pieces.add(empty);
            check(!c.followsPrev(1), "7d a sectionless piece never follows", "");
            eq(1, c.runStartOf(1), "7d … and is its own run");
            topology("fixture 7", c);
        }

        // ---------- 9: the DRAWN badge text (D249 revert) ----------
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            GeoSection g = geo(24);
            g.yawPitchGlobal = true;
            g.rollGlobal = true;
            g.ratePerMeter = true;
            p.sections.add(g);
            GeoSection gd = geo(24);
            gd.domain(Section.Domain.DISTANCE);
            p.sections.add(gd);
            ForceSection all = force(24);
            all.domain(Section.Domain.DISTANCE);
            Coaster.enterInfluence(all, 6);
            all.reversed(true);
            p.sections.add(all);
            p.sections.add(sc());
            c.pieces.add(p);
            eq("Force", SectionNames.badgeLabel(p.sections.get(0), false),
                "9a plain force badge = the bare short word, no number");
            eq("Geo", SectionNames.badgeLabel(g, false),
                "9a geo badge (global/°m flags add no tag)");
            eq("S/C", SectionNames.badgeLabel(p.sections.get(4), false),
                "9b s/c badge");
            eq("Geo ·m", SectionNames.badgeLabel(gd, false),
                "9c distance geo = SPACED tag (tags() is the one source)");
            eq("Force ·m ◀ fx", SectionNames.badgeLabel(all, false),
                "9d distance + reversed + influenced, tag order kept");
            eq(2, SectionNames.sectionOrdinal(c, 0, 2),
                "9d the numbered ordinal still exists for the menu title");
            eq("Geometric Section 2", SectionNames.sectionTitle(c, 0, 2),
                "9d … the bold title row keeps the run-wide number");
        }
        {
            Coaster c = bridgeFixture(List.of(force(24), geo(24)));
            eq("Bridge", SectionNames.badgeLabel(c.pieces.get(0).sections.get(1),
                false), "9e live bridge badge");
            eq("Bridge !", SectionNames.badgeLabel(c.pieces.get(0).sections.get(1),
                true), "9f dirty bridge badge = the warning tag");
        }

        // ---------- 10: bridge settings item + window title (D249) ----------
        {
            eq("Bridge settings…", SectionNames.BRIDGE_SETTINGS_ITEM,
                "10a the bridge's badge-menu owner item");
            Coaster c = bridgeFixture(List.of(force(24), geo(24)));
            eq("Bridge settings (Strict Seam 1)",
                SectionNames.bridgeWindowTitle(c, 0),
                "10b live bridge window title = its exit junction");
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 0, 1),
                "10b … while the toolbar button keeps the junction form");
            Coaster o = orphanFixture();
            eq("Bridge settings (Strict Seam 1)",
                SectionNames.bridgeWindowTitle(o, 0),
                "10c orphan: the would-be exit's number (same as the button)");
            // a bridge→strict chain: the SECOND bridge would read n+1 —
            // here piece 1's junction is the bridge exit (1), so a
            // bridge AFTER piece 1 would be junction 2
            int f = c.makeSeamStrict(1, 1, DT);
            check(f == 2, "10d chain fixture built", "got " + f);
            eq("Bridge settings (Strict Seam 2)",
                SectionNames.bridgeWindowTitle(c, 1),
                "10d a bridge after the bridged-in piece = junction 2");
        }

        // ---------- 11: the .nl2elem description (D249 display name) ----------
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            c.pieces.add(p);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            Nl2ElemExporter.Result x =
                Nl2ElemExporter.export(r.samples(), false, 1.0, 1.0);
            check(x.xml().contains(
                "<description>Ultimate Vector Design Export</description>"),
                "11a .nl2elem description = \"Ultimate Vector Design Export\"",
                "");
            check(!x.xml().contains("FVD Coaster Designer"),
                "11b the old product name is gone from the export", "");
        }

        System.out.println();
        System.out.println("LabelGate: " + pass + " passed, " + fail
            + " failed");
        if (fail > 0) System.exit(1);
    }
}
