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
 *      window title "Bridge Settings (Strict Seam n)" — n = the
 *      bridge's exit junction, live and orphan alike, while the
 *      toolbar button keeps "Strict Seam n (bridge) ▴"
 *  11  (D249) the .nl2elem description carries the display name
 *      "Ultimate Vector Design Export"
 *  12  (D254) File > Rename…'s name refusals (DesignIO.fileNameWhy)
 *  13  (batch 37) the gray reasons that moved into SectionNames: the
 *      resolve ops behind a bridge BY ITS MODE (bridgeResolveWhy —
 *      null under "No optimization", the re-places / re-places and
 *      re-aims sentences + BRIDGE_RESOLVE_HINT), MARCH_OWNS_RESOLVE /
 *      MARCH_OWNS_R2D, and the run-scoped ops row's REVERSE_MULTI_WHY
 *      / SWITCH_DEST_ORPHAN_WHY
 *  14  (batch 37, review round 4) the bridged-in entry refusals of the
 *      resolve ops BY THE REAL SPEED off the bridge (bridgeCrawlWhy —
 *      stalled at or under Pass D's 0.1 floor, crawling under
 *      BRIDGE_CRAWL_SPEED 1.0, null from 1.0 up) and the blow-up belt's
 *      sentence with its numbers (bridgeBlowUpWhy)
 *  15  (batch 38, D256) the badge's THREE-STATE speed glyph: the
 *      constants SPEED_GLYPH " ≠v" / SLIGHT_SPEED_GLYPH " ~v" /
 *      ORIENT_GLYPH " ≠o"; speedLevel / speedGlyph at 0, 0.005 (level
 *      0, ""), 0.0050001 and 0.2 (level 1, " ~v"), 0.2000001 and 5.0
 *      (level 2, " ≠v"), NaN (0, ""); Gestures.DRIFT_EPS == 0.2 (its
 *      new home); the composed badge texts "Force ~v", "Geo ·m ~v ≠o",
 *      "Force ·m ◀ fx ≠v", a bridge's bare "Bridge"
 *  16  (batch 38, D256) Reverse's gray sentences: the four new
 *      REVERSE_* constants verbatim and reverseWhy over all eight
 *      (multi, orphanTail, controls) combinations — null for none,
 *      multi beats orphan, controls appended to both
 *  17  (batch 38, D256; shrunk in D257 — the translational check,
 *      its tooltip, the mode-aware sentence twins and their choosers
 *      went with the translational mode) the SMOOTHING OPTIONS
 *      window's strings: the title word, the seam menu's two items,
 *      the length label, the two buttons, the teardown reason, the
 *      D141 sentences at both sites (badge line / initial-speed
 *      window — the D255 text verbatim), and smoothWindowTitle on a
 *      plain strict seam ("Smoothing Options (Strict Seam 1)"), past
 *      a bridge (junction 2), at a run head and out of range */
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
        StraightCurveSection.Element arc =
            StraightCurveSection.Element.curve(20, 75, 0, 3);   // v20.0 κ-form
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
            eq("Bridge Settings (Strict Seam 1)",
                SectionNames.bridgeWindowTitle(c, 0),
                "10b live bridge window title = its exit junction");
            eq("Strict Seam 1 (bridge) ▴", SectionNames.ownerButtonLabel(c, 0, 1),
                "10b … while the toolbar button keeps the junction form");
            Coaster o = orphanFixture();
            eq("Bridge Settings (Strict Seam 1)",
                SectionNames.bridgeWindowTitle(o, 0),
                "10c orphan: the would-be exit's number (same as the button)");
            // a bridge→strict chain: the SECOND bridge would read n+1 —
            // here piece 1's junction is the bridge exit (1), so a
            // bridge AFTER piece 1 would be junction 2
            int f = c.makeSeamStrict(1, 1, DT);
            check(f == 2, "10d chain fixture built", "got " + f);
            eq("Bridge Settings (Strict Seam 2)",
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

        // ---------- 12: File > Rename…'s name refusals (D254 review) ----------
        {
            eq("Type a name.", DesignIO.fileNameWhy("   "),
                "12a a blank name refuses");
            eq("Just a file name — no folders or drive letters.",
                DesignIO.fileNameWhy("sub/ride"),
                "12b a path-like name refuses");
            eq("Just a file name — no folders or drive letters.",
                DesignIO.fileNameWhy("sub" + (char) 92 + "ride"),
                "12b' … backslash too");
            eq("A file name can't contain * ? \" < > | or control characters.",
                DesignIO.fileNameWhy("ride?2"),
                "12c an illegal character refuses (was an uncaught InvalidPathException)");
            eq("A file name can't contain * ? \" < > | or control characters.",
                DesignIO.fileNameWhy("ride" + (char) 9 + "b"),
                "12d a control character refuses");
            eq("NUL is a reserved Windows name — pick another.",
                DesignIO.fileNameWhy("nul.uvd"),
                "12e a Windows device name refuses, extension or not");
            check(DesignIO.fileNameWhy("My Ride 2 (final).uvd") == null,
                "12f an ordinary name passes", "");
            check(DesignIO.fileNameWhy("console") == null,
                "12g a name merely STARTING like a device name passes", "");
        }

        // ---------- 13: gray reasons homed in SectionNames (batch 37) ----------
        {
            eq(" — convert the bridge first or set it to \"No optimization.\"",
                SectionNames.BRIDGE_RESOLVE_HINT,
                "13a the hint closing every optimizing-bridge reason (user wording)");
            check(SectionNames.bridgeResolveWhy(BridgeSection.OPT_NONE) == null,
                "13b \"No optimization\": the resolve ops run behind the bridge (null)",
                "got \"" + SectionNames.bridgeResolveWhy(BridgeSection.OPT_NONE) + "\"");
            eq("The bridge before this section re-places this piece after any"
                + " resolution — convert the bridge first or set it to"
                + " \"No optimization.\"",
                SectionNames.bridgeResolveWhy(BridgeSection.OPT_POS),
                "13c \"Optimize position\": the re-places sentence");
            eq("The bridge before this section re-places and re-aims this piece"
                + " after any resolution — convert the bridge first or set it"
                + " to \"No optimization.\"",
                SectionNames.bridgeResolveWhy(BridgeSection.OPT_POS_YAW),
                "13d \"Optimize position and orientation\": the re-places and re-aims sentence");
            check(SectionNames.bridgeResolveWhy(BridgeSection.OPT_POS)
                    .endsWith(SectionNames.BRIDGE_RESOLVE_HINT)
                && SectionNames.bridgeResolveWhy(BridgeSection.OPT_POS_YAW)
                    .endsWith(SectionNames.BRIDGE_RESOLVE_HINT)
                && !SectionNames.bridgeResolveWhy(BridgeSection.OPT_POS).equals(
                    SectionNames.bridgeResolveWhy(BridgeSection.OPT_POS_YAW)),
                "13e both optimize sentences end with the hint and differ by mode", "");
            eq("The optimize-orientation bridge owns this piece's pitch/roll"
                + " — convert the bridge first or set it to \"Optimize position\""
                + " or \"No optimization.\"",
                SectionNames.MARCH_OWNS_RESOLVE,
                "13f a later section behind an optimize-orientation bridge");
            eq("The optimize-orientation bridge owns this piece's pitch/roll"
                + " — set the bridge to \"Optimize position\" or"
                + " \"No optimization.\"",
                SectionNames.MARCH_OWNS_R2D,
                "13g Real → Design's gray reason in the windows");
            eq("Reverse works on a track piece without strict seams or bridges"
                + " — reversing a whole track piece is not available yet.",
                SectionNames.REVERSE_MULTI_WHY,
                "13h Reverse on a run of several pieces (v20.1 D259 §3.10 text)");
            eq("That coaster's last track piece ends in an orphaned bridge"
                + " — delete or convert it first.",
                SectionNames.SWITCH_DEST_ORPHAN_WHY,
                "13i Switch Coaster: a destination ending in an orphan bridge");
        }

        // ---------- 14: the bridged-in entry refusals (batch 37, round 4) ----------
        {
            check(SectionNames.BRIDGE_CRAWL_SPEED == 1.0,
                "14a the crawl threshold is 1 m/s",
                "got " + SectionNames.BRIDGE_CRAWL_SPEED);
            String stall = "The train stalls on the bridge before this track"
                + " piece — there is no real entry speed to take.";
            eq(stall, SectionNames.bridgeCrawlWhy(0.0),
                "14b a real speed of 0 at the bridge's end: the stall sentence");
            eq(stall, SectionNames.bridgeCrawlWhy(0.1),
                "14c exactly the 0.1 stall floor still stalls");
            eq("The train crawls off the bridge at 0.45 m/s — re-solving"
                + " this track piece at that speed would blow the bridge up;"
                + " raise the bridge or the entry speed first.",
                SectionNames.bridgeCrawlWhy(0.45),
                "14d 0.45 m/s (the train does cross): the crawl sentence with the number");
            eq("The train crawls off the bridge at 0.55 m/s — re-solving"
                + " this track piece at that speed would blow the bridge up;"
                + " raise the bridge or the entry speed first.",
                SectionNames.bridgeCrawlWhy(0.55),
                "14e 0.55 m/s (the 940 km case) crawls");
            check(SectionNames.bridgeCrawlWhy(0.9999).startsWith("The train crawls"),
                "14f just under 1 m/s crawls", "");
            check(SectionNames.bridgeCrawlWhy(1.0) == null,
                "14g exactly 1 m/s is usable (null)",
                "got \"" + SectionNames.bridgeCrawlWhy(1.0) + "\"");
            check(SectionNames.bridgeCrawlWhy(18.0) == null,
                "14h 18 m/s is usable (null)", "");
            eq("at 2.00 m/s the bridge re-solves to 2038 m of track (it was"
                + " 38 m) — raise the bridge or the entry speed first.",
                SectionNames.bridgeBlowUpWhy(2.0, 2037.6, 38.4),
                "14i the blow-up belt's sentence carries speed and both arcs");
        }

        // ---------- 15: the badge's THREE-STATE speed glyph (batch 38, D256) ----------
        {
            eq(" ≠v", SectionNames.SPEED_GLYPH, "15a the speed-conflict glyph");
            eq(" ~v", SectionNames.SLIGHT_SPEED_GLYPH, "15a the SLIGHT speed glyph");
            eq(" ≠o", SectionNames.ORIENT_GLYPH, "15a the orientation glyph");
            check(Gestures.DRIFT_EPS == 0.2,
                "15b Gestures.DRIFT_EPS is the 0.2 conflict threshold (its new home)",
                "got " + Gestures.DRIFT_EPS);
            check(Gestures.INIT_SPEED_SHOW_EPS == 0.005,
                "15b the lower threshold is the init-speed box's 0.005", "");
            eq(0, SectionNames.speedLevel(0.0), "15c gap 0 = level 0");
            eq("", SectionNames.speedGlyph(0.0), "15c … no glyph");
            eq(0, SectionNames.speedLevel(0.005),
                "15d exactly 0.005 = level 0 (the box cannot show it)");
            eq("", SectionNames.speedGlyph(0.005), "15d … no glyph");
            eq(1, SectionNames.speedLevel(0.0050001),
                "15e just over 0.005 = level 1 (slight)");
            eq(" ~v", SectionNames.speedGlyph(0.0050001), "15e … \" ~v\"");
            eq(1, SectionNames.speedLevel(0.2),
                "15f exactly 0.2 = level 1 (the historic > test keeps it slight)");
            eq(" ~v", SectionNames.speedGlyph(0.2), "15f … \" ~v\"");
            eq(2, SectionNames.speedLevel(0.2000001),
                "15g just over 0.2 = level 2 (the conflict)");
            eq(" ≠v", SectionNames.speedGlyph(0.2000001), "15g … \" ≠v\"");
            eq(2, SectionNames.speedLevel(5.0), "15h 5 m/s = level 2");
            eq(" ≠v", SectionNames.speedGlyph(5.0), "15h … \" ≠v\"");
            eq(0, SectionNames.speedLevel(Double.NaN),
                "15i NaN (no compile / a bridge) = level 0");
            eq("", SectionNames.speedGlyph(Double.NaN), "15i … no glyph");
            // the composed badge texts (GraphEditor: badgeLabel +
            // driftGlyph + ORIENT_GLYPH)
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            ForceSection f = force(26);
            p.sections.add(f);
            GeoSection gd = geo(24);
            gd.domain(Section.Domain.DISTANCE);
            p.sections.add(gd);
            ForceSection all = force(24);
            all.domain(Section.Domain.DISTANCE);
            Coaster.enterInfluence(all, 6);
            all.reversed(true);
            p.sections.add(all);
            eq("Force ~v", SectionNames.badgeLabel(f, false)
                    + SectionNames.speedGlyph(0.1),
                "15j a slight conflict on a plain force badge");
            eq("Geo ·m ~v ≠o", SectionNames.badgeLabel(gd, false)
                    + SectionNames.speedGlyph(0.05) + SectionNames.ORIENT_GLYPH,
                "15k slight speed + orientation: speed glyph first, as today");
            eq("Force ·m ◀ fx ≠v", SectionNames.badgeLabel(all, false)
                    + SectionNames.speedGlyph(1.0),
                "15l the full conflict after every tag");
            Coaster bc = bridgeFixture(List.of(force(24), geo(24)));
            eq("Bridge", SectionNames.badgeLabel(bc.pieces.get(0).sections.get(1),
                    false) + SectionNames.speedGlyph(Double.NaN),
                "15m a bridge never wears a speed glyph (the editor hands NaN)");
        }

        // ---------- 16: Reverse's gray sentences (batch 38, D256) ----------
        {
            eq("Reverse works on a track piece without speed controls —"
                + " remove its speed controls first.",
                SectionNames.REVERSE_CONTROLS_WHY,
                "16a a single piece with speed controls");
            eq("Reverse works on a track piece without strict seams, bridges or"
                + " speed controls — reversing a whole track piece is not available"
                + " yet.",
                SectionNames.REVERSE_MULTI_CONTROLS_WHY,
                "16b a run of several pieces that also carries speed controls (v20.1 D259 §3.10 text)");
            eq("Reverse works on a track piece without bridges — delete or"
                + " convert its bridge first.",
                SectionNames.REVERSE_BRIDGE_WHY,
                "16c a single piece ending in an orphan bridge");
            eq("Reverse works on a track piece without bridges or speed"
                + " controls — delete or convert its bridge and remove its"
                + " speed controls first.",
                SectionNames.REVERSE_BRIDGE_CONTROLS_WHY,
                "16d … that also carries speed controls");
            check(SectionNames.reverseWhy(false, false, false) == null,
                "16e nothing in the way: null (Reverse enabled)",
                "got \"" + SectionNames.reverseWhy(false, false, false) + "\"");
            eq(SectionNames.REVERSE_CONTROLS_WHY,
                SectionNames.reverseWhy(false, false, true),
                "16f controls only");
            eq(SectionNames.REVERSE_BRIDGE_WHY,
                SectionNames.reverseWhy(false, true, false),
                "16g orphan tail only");
            eq(SectionNames.REVERSE_BRIDGE_CONTROLS_WHY,
                SectionNames.reverseWhy(false, true, true),
                "16h orphan tail + controls: controls appended");
            eq(SectionNames.REVERSE_MULTI_WHY,
                SectionNames.reverseWhy(true, false, false),
                "16i multi only (13h's sentence, unchanged)");
            eq(SectionNames.REVERSE_MULTI_CONTROLS_WHY,
                SectionNames.reverseWhy(true, false, true),
                "16j multi + controls: controls appended");
            eq(SectionNames.REVERSE_MULTI_WHY,
                SectionNames.reverseWhy(true, true, false),
                "16k multi + orphan tail: multi wins (\"bridges\" covers the tail)");
            eq(SectionNames.REVERSE_MULTI_CONTROLS_WHY,
                SectionNames.reverseWhy(true, true, true),
                "16l all three: the multi + controls sentence");
            for (String w : new String[]{ SectionNames.REVERSE_MULTI_WHY,
                    SectionNames.REVERSE_CONTROLS_WHY,
                    SectionNames.REVERSE_MULTI_CONTROLS_WHY,
                    SectionNames.REVERSE_BRIDGE_WHY,
                    SectionNames.REVERSE_BRIDGE_CONTROLS_WHY }) {
                check(w.startsWith("Reverse works on a track piece without ")
                        && (w.endsWith(" first.")
                            || w.endsWith(" not available yet.")),   // v20.1 D259 §3.10:
                            // the multi sentences name no "do X first" (L4)
                    "16m every sentence takes the \"Reverse works on …\" form"
                        + " (the stats-bar belt lowercases the first letter)",
                    w);
            }
        }

        // ---------- 17: the SMOOTHING OPTIONS window's strings (batch 38, D256) ----------
        {
            eq("Smoothing Options", SectionNames.SMOOTH_TITLE,
                "17a the window's title word (title case like Bridge Settings)");
            eq("Smoothing options…", SectionNames.SMOOTH_OPTIONS_ITEM,
                "17b the seam menu's item while the seam smooths");
            eq("Enable smoothing (10 m)", SectionNames.ENABLE_SMOOTHING_ITEM,
                "17c the seam menu's item while it does not (the D141 label)");
            eq("Smoothing length (m)", SectionNames.SMOOTH_LEN_LABEL,
                "17d the length row's label");
            eq("Make smoothing real…", SectionNames.SMOOTH_REAL_BUTTON,
                "17g the Make-real button = the former menu item's label");
            eq("Disable smoothing", SectionNames.SMOOTH_DISABLE_BUTTON,
                "17h the Disable button = the former menu item's label");
            eq("The smoothing options window closed — its seam no longer smooths.",
                SectionNames.SMOOTH_WINDOW_CLOSED,
                "17i the window's self-teardown reason");
            // the D141 sentences at the two sites (the D255 text verbatim;
            // the D256 mode-aware twins and choosers are gone, D257)
            eq("Speed conflict caused by seam smoothing — shrink the ◇ window instead",
                SectionNames.SMOOTH_SHRINK_WHY,
                "17j the badge's D141 line (the historic text, no period)");
            eq("Seam smoothing opens this speed difference — shrink the ◇ window instead.",
                SectionNames.INIT_SMOOTH_SHRINK_WHY,
                "17m the initial-speed window's smoothing-opened sentence (historic)");
            // the window title on the section-3 fixtures: a plain strict
            // seam and the bridge → strict chain, plus the run head
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            p.sections.add(force(23));
            c.pieces.add(p);
            int f = c.makeSeamStrict(0, 1, DT);
            eq("Smoothing Options (Strict Seam 1)", SectionNames.smoothWindowTitle(c, f),
                "17p the title on a plain strict seam");
            eq("Smoothing Options", SectionNames.smoothWindowTitle(c, 0),
                "17q a run head has no junction: the bare title (never shown there)");
            Coaster cc = bridgeFixture(List.of(force(24), geo(24)));
            int f2 = cc.makeSeamStrict(1, 1, DT);
            eq("Smoothing Options (Strict Seam 2)", SectionNames.smoothWindowTitle(cc, f2),
                "17r the title past a bridge counts the bridge exit (junction 2)");
            eq("Smoothing Options", SectionNames.smoothWindowTitle(cc, 9),
                "17s out of range: the bare title (guard)");
        }

        // ---------- 18: the STRAIGHT/CURVE OVERHAUL's element sentences (v20.0, D259 §1.8) ----------
        {
            eq("A helix can't start on vertical track — add a curve first.",
                SectionNames.HELIX_VERTICAL_WHY, "18a the one helix-on-vertical refusal");
            eq("This element starts on vertical track — its plane is inherited from the element before it.",
                SectionNames.DIR_INHERITED_WHY, "18b the planar direction refusal after a vertical entry");
            eq("A helix's end elevation must stay under 90° — use a planar pull-up for a loop.",
                SectionNames.HELIX_ELEV_WHY, "18c the helix end-elevation cap");
            eq("Old straight/curve elements were converted — their roll settings and entry ease were dropped.",
                SectionNames.SC_LEGACY_CONVERTED,
                "18d the load report's converted-elements sentence (the Stage-1b LONG form, §10.4)");
            eq("∞", SectionNames.INFINITY_TWIN, "18e the radius twin under KAPPA_INF");
            eq("Straight", SectionNames.kindWord(StraightCurveSection.Kind.STRAIGHT), "18f kind word");
            eq("Planar", SectionNames.kindWord(StraightCurveSection.Kind.PLANAR), "18g kind word");
            eq("Helical", SectionNames.kindWord(StraightCurveSection.Kind.HELICAL), "18h kind word");
            eq("Linear", SectionNames.interpWord(StraightCurveSection.Interp.LINEAR), "18i interpolation word");
            eq("Sinusoidal", SectionNames.interpWord(StraightCurveSection.Interp.SINUSOIDAL), "18j interpolation word");
        }

        // ---------- 19: the ROLL LANE per s/c element (v20.1, D259 §2.10) ----------
        {
            eq("Straight/curve sections author their shape as elements — only the roll lane exists.",
                SectionNames.SC_LANES_WHY, "19a the yaw/pitch lane rows over an s/c");
            eq("The roll lane ends at the element end — resize the element to extend it.",
                SectionNames.ROLL_WALL_WHY, "19b the hard end wall");
            eq("Straight/curve sections are always distance-based.",
                SectionNames.SC_DISTANCE_WHY, "19c the Domain row over an s/c (D30)");
            eq("Absolute bank holds a bank against the horizon — it needs the global roll axis.",
                SectionNames.ABS_NEEDS_GLOBAL_WHY, "19d the Local radio under Absolute");
            eq("Switch to These Settings (same graph, change bank)",
                SectionNames.ROLL_SWITCH_BUTTON, "19e the Switch button");
            eq("Bake These Settings (convert graph, keep bank)",
                SectionNames.ROLL_BAKE_BUTTON, "19f the Bake button");
            eq("Reinterprets this element's roll graph under the new settings.",
                SectionNames.ROLL_SWITCH_TIP, "19g the Switch tooltip (one sentence)");
            eq("Converts this element's roll graph so the bank stays the same.",
                SectionNames.ROLL_BAKE_TIP, "19h the Bake tooltip (one sentence)");
            eq("Roll", SectionNames.ROLL_BLOCK, "19i the Roll block's title");
            eq("Switched the roll lane to this element — double-click again to insert a node.",
                SectionNames.SC_TRACK_DBLCLICK_WHY, "19j the track double-click's lane switch");
        }

        section20();

        System.out.println();
        System.out.println("LabelGate: " + pass + " passed, " + fail
            + " failed");
        if (fail > 0) System.exit(1);
    }

    // ---------- 20: the SEAM INVARIANT's sentences (v20.1, D259 §3.10 — run 3) ----------
    // Appended as ONE method (the parallel-work rule): every §3.10 string
    // except the Stage-4/5 ones (COMBINE_*, ALIGN_PREV_*, ALIGN_NO_CURVE_WHY,
    // ALIGN_LOCKED_WHY) and REVERSED_WHY (Stage 2a's register), plus the
    // model choosers that hand them out.
    static void section20() throws Exception {
        eq("A straight/curve section always begins and ends at a strict seam"
            + " — connect the sections instead.",
            SectionNames.UNSTRICT_SC_WHY, "20a un-strict / absorb on an s/c‖s/c seam (L4)");
        eq("A straight/curve section always begins and ends at a strict seam"
            + " — convert it first.",
            SectionNames.UNSTRICT_CONVERT_WHY, "20b un-strict / absorb with an s/c on one side (L4)");
        eq("This seam already follows the previous section.",
            SectionNames.ALIGN_FOLLOWS_WHY, "20c the forward align with no locked axis");
        eq("The track is vertical at this seam — there is no heading or bank to align.",
            SectionNames.ALIGN_VERTICAL_WHY, "20d the forward align on a vertical frame");
        eq("B needs a selected node or a highlighted strict seam.",
            SectionNames.B_NO_TARGET_WHY, "20e B with nothing to act on");
        eq("Align to previous end", SectionNames.ALIGN_BUTTON, "20f the seam window's align button");
        eq("Sets the locked angles to the previous section's end orientation.",
            SectionNames.ALIGN_TIP, "20g its tooltip (one sentence)");
        eq("The smoothing window holds too few samples to engage — lengthen it.",
            SectionNames.SMOOTH_TOO_SHORT_WHY, "20h smoothEngagesWhy: too few samples");
        eq("A section beside this seam is too short to smooth.",
            SectionNames.SMOOTH_SECTION_SHORT_WHY, "20i smoothEngagesWhy: a clamped half");
        eq("smoothing conversion needs force/geo sections on both sides of the seam",
            SectionNames.SMOOTH_REAL_SC_WHY, "20j make-real next to an s/c (promoted verbatim)");
        eq("The bank lane sets the bank at this seam — edit its first node.",
            SectionNames.SEAM_ROLL_ABS_WHY, "20k the seam window's roll row under an absolute s/c lane");
        eq("Reverse works on a track piece without strict seams or bridges —"
            + " reversing a whole track piece is not available yet.",
            SectionNames.REVERSE_MULTI_WHY, "20l Reverse on a multi-piece run (the L4-safe text)");
        eq("Reverse works on a track piece without strict seams, bridges or"
            + " speed controls — reversing a whole track piece is not available yet.",
            SectionNames.REVERSE_MULTI_CONTROLS_WHY, "20m … that also carries speed controls");
        eq(SectionNames.REVERSE_MULTI_WHY, SectionNames.reverseWhy(true, false, false),
            "20n reverseWhy(multi) → the rewritten sentence");
        eq(SectionNames.REVERSE_MULTI_CONTROLS_WHY, SectionNames.reverseWhy(true, true, true),
            "20o reverseWhy(multi + controls) → the rewritten sentence");
        eq(null, SectionNames.reverseWhy(false, false, false),
            "20p a lone piece (an s/c piece included) stays reversible (D32)");
        // the model choosers on fixtures: [force] | [sc] (one side), [sc] |
        // [sc] (both), [force] | [force] (a lane seam: null) — through
        // ensureScFollower / appendSectionWithInvariant
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(force(26));
            c.pieces.add(p);
            c.appendSectionWithInvariant(0, StraightCurveSection.straight(10, 24), DT);
            c.appendSectionWithInvariant(1, StraightCurveSection.straight(8, 24), DT);
            eq(SectionNames.UNSTRICT_CONVERT_WHY, c.unstrictSeamWhy(1),
                "20q unstrictSeamWhy: force | s/c → the convert sentence");
            eq(SectionNames.UNSTRICT_SC_WHY, c.unstrictSeamWhy(2),
                "20r unstrictSeamWhy: s/c | s/c → the connect sentence");
            eq(SectionNames.UNSTRICT_SC_WHY, c.absorbFollowerWhy(1, 2),
                "20s absorbFollowerWhy mirrors it");
            eq(SectionNames.ALIGN_FOLLOWS_WHY, c.alignSeamWhy(1, TrackCompiler.compile(c, DT)),
                "20t alignSeamWhy on an all-unlocked (automatic) seam");
            Coaster l = new Coaster();
            TrackPiece q = new TrackPiece(0, 21, 0, 0);
            q.sections.add(force(26));
            q.sections.add(force(24));
            l.pieces.add(q);
            int f = l.makeSeamStrict(0, 1, DT);
            TrackCompiler.Result rl = TrackCompiler.compile(l, DT);
            eq(null, l.unstrictSeamWhy(f), "20u unstrictSeamWhy on a lane seam: null (may go)");
            eq(null, l.alignSeamWhy(f, rl), "20v alignSeamWhy on an all-locked lane seam: null (would write)");
            // smoothEngagesWhy: a 0.3 m window holds too few samples; a
            // 60 m window beside a 6 s section is clamped by that section
            // (the force section's arc ≈ 150 m, so 60 m engages — the
            // clamp sentence needs a SHORT neighbor: a 0.8 m s/c)
            eq(SectionNames.SMOOTH_TOO_SHORT_WHY,
                TrackCompiler.smoothEngagesWhy(rl.samples(), rl.spans(), 1, 0.3),
                "20w smoothEngagesWhy: a 0.3 m window → too few samples");
            eq(null, TrackCompiler.smoothEngagesWhy(rl.samples(), rl.spans(), 1, 10),
                "20x smoothEngagesWhy: a 10 m window engages (null)");
            Coaster s = new Coaster();
            TrackPiece sp = new TrackPiece(0, 21, 0, 0);
            sp.sections.add(force(26));
            s.pieces.add(sp);
            s.appendSectionWithInvariant(0, StraightCurveSection.straight(0.8, 24), DT);
            TrackCompiler.Result rs = TrackCompiler.compile(s, DT);
            eq(SectionNames.SMOOTH_SECTION_SHORT_WHY,
                TrackCompiler.smoothEngagesWhy(rs.samples(), rs.spans(), 1, 10),
                "20y smoothEngagesWhy: a 0.8 m s/c beside the seam clamps a 10 m window → the short-section sentence");
        }
    }
}
