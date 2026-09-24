import coaster.*;

import java.util.*;

/** D248 gate — PRISTINE-LANE END FOLLOW (v19.19, batch 33 item E):
 *  LaneSection.endFollows + pristineHold / touchLane / syncFollowingEnds
 *  and the `lane` line's 3rd field.
 *  a) creation + carry: continuation / holdSection set every flag,
 *     TrackPiece.copy deep-copies them, splitSectionAt hands both
 *     halves the parent's flags, mergeSections' output and any plain
 *     constructor start unflagged.
 *  b) the follow write: nothing moves while every lane still follows;
 *     an edited lane's end drags the untouched lanes' ends along, up
 *     AND down; two defining lanes = the later end; the belt clears
 *     (never moves) a flagged lane that stopped being hold-shaped
 *     (value / handle / lock); 1-node lanes neither define nor follow;
 *     delta lanes are outside the mechanism; the minGap floor; the
 *     DISTANCE domain's meters; touchLane's identity semantics; the
 *     redraw belt clearStaleFollows CLEARS and never moves (D248
 *     review fix — every GraphEditor sink that can move a last node
 *     runs the full syncFollowingEnds before its own rebuild, so no
 *     follower depends on a belt move).
 *  c) IO: the field is written 1/0, read verbatim (an explicit 0 on a
 *     hold-shaped lane survives), DERIVED from the loaded shape when
 *     missing (pre-D248 files: pristine → follows, edited → not — and
 *     the derived state is a FIXED POINT of syncFollowingEnds: a
 *     pristine hold short of the end, a sole longest hold and a
 *     time-dragged sibling all derive false, so the first follow write
 *     after opening moves nothing and the save is unchanged, D248
 *     review fix), a healed lane never follows, save→load→save is
 *     byte-identical, and the flags feed no geometry (compile bitwise
 *     with the flags flipped).
 */
public class EndFollowGate {

    static final double DT = 0.01;
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else    { fail++; System.out.println("FAIL  " + name); }
    }

    static void check(boolean ok, String name, String detail) {
        check(ok, name + "  (" + detail + ")");
    }

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] p : pts) l.add(new BezierBaker.Node(p[0], p[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> hillLanes(double len, double amp) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len * 0.3, 1 + amp},
            {len * 0.7, 1 - amp * 0.6}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.5, 0.3}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len * 0.4, 25}, {len, 0} }));
        return ls;
    }

    static double[] lastT(LaneSection ls) {
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> l = ls.lanes.get(i);
            t[i] = l.get(l.size() - 1).t;
        }
        return t;
    }

    static boolean flags(LaneSection ls, boolean a, boolean b, boolean c) {
        return ls.endFollows[0] == a && ls.endFollows[1] == b && ls.endFollows[2] == c;
    }

    static String flagStr(LaneSection ls) {
        return (ls.endFollows[0] ? 1 : 0) + "" + (ls.endFollows[1] ? 1 : 0)
            + "" + (ls.endFollows[2] ? 1 : 0);
    }

    static final String NL = System.lineSeparator();

    static String saveText(Coaster c) {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(), new java.io.PrintWriter(sw));
        return sw.toString();
    }

    static Coaster loadText(String s) throws Exception {
        return DesignIO.load(new java.io.BufferedReader(
            new java.io.StringReader(s))).coasters().get(0);
    }

    static LaneSection sec(Coaster c, int p, int k) {
        return (LaneSection) c.pieces.get(p).sections.get(k);
    }

    /** The default-piece coaster: one authored hill section (flags
     *  off) followed by a '+' continuation hold (flags on). */
    static Coaster fixture() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        ForceSection a = new ForceSection(hillLanes(4, 1.2), 26);
        p.sections.add(a);
        p.sections.add(Coaster.continuation(a, 24));
        c.pieces.add(p);
        return c;
    }

    /** The loader's FIXED-POINT contract (D248 review fix): one full
     *  syncFollowingEnds pass on a freshly loaded section moves
     *  nothing, rideEnd stays `end`, and the save text is unchanged
     *  (so the first redraw / first save after opening is bitwise). */
    static boolean fixedPoint(Coaster c, LaneSection ls, double end) {
        String pre = saveText(c);
        int moved = LaneSection.syncFollowingEnds(ls, 0.01);
        boolean ok = moved == 0 && BezierBaker.rideEnd(ls.lanes) == end
            && saveText(c).equals(pre);
        if (!ok) System.out.println("      fixedPoint: moved=" + moved
            + " rideEnd=" + BezierBaker.rideEnd(ls.lanes) + " saveSame="
            + saveText(c).equals(pre));
        return ok;
    }

    static long checksum(TrackCompiler.Result r) {
        long h = 17;
        for (FVDIntegrator.Sample s : r.samples()) {
            h = h * 31 + Double.doubleToLongBits(s.pos().x());
            h = h * 31 + Double.doubleToLongBits(s.pos().y());
            h = h * 31 + Double.doubleToLongBits(s.pos().z());
            h = h * 31 + Double.doubleToLongBits(s.v());
        }
        return h;
    }

    public static void main(String[] args) throws Exception {
        // ================= a) creation + carry =================
        {
            Coaster c = fixture();
            LaneSection hill = sec(c, 0, 0), hold = sec(c, 0, 1);
            check(flags(hill, false, false, false),
                "a0 an authored (constructor) section starts unflagged", flagStr(hill));
            check(flags(hold, true, true, true),
                "a1 Coaster.continuation sets every lane's flag", flagStr(hold));
            for (int i = 0; i < 3; i++) {
                check(LaneSection.pristineHold(hold.lanes.get(i)),
                    "a1." + i + " the continuation lane IS pristine-hold shaped");
            }
            ForceSection hs = Coaster.holdSection(1.2, 0.1, 5, 20);
            check(flags(hs, true, true, true),
                "a2 Coaster.holdSection sets every lane's flag", flagStr(hs));
            check(hs.lanes.get(0).size() == 2 && hs.lanes.get(0).get(1).t == 1,
                "a2b holdSection is the 2-node 1 s hold");
            // a3 copy (TrackPiece.copy → Coaster.copySection)
            hold.endFollows[1] = false;
            TrackPiece cp = c.pieces.get(0).copy(5);
            LaneSection holdCopy = (LaneSection) cp.sections.get(1);
            check(flags(holdCopy, true, false, true),
                "a3 TrackPiece.copy deep-copies the flags", flagStr(holdCopy));
            holdCopy.endFollows[0] = false;
            check(hold.endFollows[0],
                "a3b the copy's array is its own (source untouched)");
            hold.endFollows[1] = true;
        }
        {
            // a4 splitSectionAt: both halves inherit
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            hold.endFollows[2] = false;
            // grow the hold to 2 s so the cut lands inside it
            for (List<BezierBaker.Node> l : hold.lanes) l.get(1).t = 2;
            boolean ok = c.splitSectionAt(0, 1, 1.0, DT);
            check(ok, "a4 splitSectionAt on the hold succeeds");
            LaneSection L = sec(c, 0, 1), R = sec(c, 0, 2);
            check(flags(L, true, true, false),
                "a4a the LEFT half inherits the parent's flags", flagStr(L));
            check(flags(R, true, true, false),
                "a4b the RIGHT half inherits the parent's flags", flagStr(R));
            check(LaneSection.pristineHold(L.lanes.get(0))
                    && LaneSection.pristineHold(R.lanes.get(0)),
                "a4c a hold's split halves stay pristine-hold shaped (de Casteljau of a flat line)");
            check(L.endFollows != R.endFollows && L.endFollows != hold.endFollows,
                "a4d each half owns its own flag array");
        }
        {
            // a6 merge: the merged section starts unflagged
            Coaster c = fixture();
            LaneSection a = sec(c, 0, 0);
            LaneSection b = sec(c, 0, 1);
            a.endFollows(true);   // even a flagged pair
            check(c.mergeSections(0, 0), "a6 mergeSections succeeds on the fixture");
            LaneSection m = sec(c, 0, 0);
            check(m != a && m != b && flags(m, false, false, false),
                "a6a the merged section is a new, unflagged object", flagStr(m));
        }
        {
            // a7 the chaining setter
            ForceSection f = new ForceSection(hillLanes(3, 1), 20);
            LaneSection r = f.endFollows(true);
            check(r == f && flags(f, true, true, true), "a7 endFollows(true) sets all three and returns this");
            f.endFollows(false);
            check(flags(f, false, false, false), "a7b endFollows(false) clears all three");
        }

        // ================= b) the follow write =================
        {
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            int moved = LaneSection.syncFollowingEnds(hold, 0.01);
            check(moved == 0 && flags(hold, true, true, true)
                    && lastT(hold)[0] == 1 && lastT(hold)[1] == 1 && lastT(hold)[2] == 1,
                "b1 a wholly untouched section moves nothing (no defining lane)");
            // b2 GROW: lane 0 edited (touched) and extended to 3 s
            boolean was = LaneSection.touchLane(hold, hold.lanes.get(0));
            check(was && flags(hold, false, true, true), "b2a touchLane clears ONLY lane 0's flag", flagStr(hold));
            hold.lanes.get(0).get(1).t = 3;
            moved = LaneSection.syncFollowingEnds(hold, 0.01);
            double[] t = lastT(hold);
            check(moved == 2 && t[1] == 3 && t[2] == 3,
                "b2 the untouched lanes' ends follow the edited lane's end UP", t[1] + " " + t[2]);
            check(hold.lanes.get(1).get(1).val == 0 && hold.lanes.get(1).get(1).inDv == 0
                    && hold.lanes.get(1).get(1).inFrac == BezierBaker.DEFAULT_FRAC
                    && hold.lanes.get(1).get(0).outDv == 0,
                "b2c values and handles of a follower are untouched");
            check(BezierBaker.rideEnd(hold.lanes) == 3, "b2d rideEnd == the new end");
            check(LaneSection.syncFollowingEnds(hold, 0.01) == 0,
                "b2e a second sync is a no-op (fixed point)");
            // b3 SHRINK: lane 0's end back to 0.5 s — the section shrinks
            hold.lanes.get(0).get(1).t = 0.5;
            moved = LaneSection.syncFollowingEnds(hold, 0.01);
            t = lastT(hold);
            check(moved == 2 && t[1] == 0.5 && t[2] == 0.5 && BezierBaker.rideEnd(hold.lanes) == 0.5,
                "b3 the untouched lanes follow the edited lane's end DOWN (the section shrinks)",
                t[1] + " " + t[2]);
            // b4 two defining lanes: the later end wins
            LaneSection.touchLane(hold, hold.lanes.get(1));
            hold.lanes.get(1).get(1).t = 2.5;
            moved = LaneSection.syncFollowingEnds(hold, 0.01);
            t = lastT(hold);
            check(moved == 1 && t[2] == 2.5 && t[0] == 0.5 && t[1] == 2.5,
                "b4 with two defining lanes the follower lands on the LATER end", t[2] + "");
            // b12 touch semantics: foreign / delta / already-cleared
            check(!LaneSection.touchLane(hold, hold.lanes.get(1)),
                "b12a touching an already-cleared lane returns false");
            check(!LaneSection.touchLane(hold, new ArrayList<>(hold.lanes.get(2)))
                    && hold.endFollows[2],
                "b12b a foreign list (equal contents, other identity) touches nothing");
            check(!LaneSection.touchLane(null, hold.lanes.get(2))
                    && !LaneSection.touchLane(hold, null) && hold.endFollows[2],
                "b12c null section / null lane are no-ops");
        }
        {
            // b5/b6/b7 the belt: a flagged lane that is no longer
            // hold-shaped is CLEARED and NOT moved
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            LaneSection.touchLane(hold, hold.lanes.get(0));
            hold.lanes.get(0).get(1).t = 4;
            hold.lanes.get(1).get(1).val = 0.7;          // value edited behind the flag
            hold.lanes.get(2).get(1).inDv = 3;           // handle edited behind the flag
            int moved = LaneSection.syncFollowingEnds(hold, 0.01);
            double[] t = lastT(hold);
            check(moved == 0 && flags(hold, false, false, false) && t[1] == 1 && t[2] == 1,
                "b5/b6 belt: value- or handle-edited followers are cleared, never moved", flagStr(hold) + " " + t[1] + " " + t[2]);
            Coaster c2 = fixture();
            LaneSection h2 = sec(c2, 0, 1);
            LaneSection.touchLane(h2, h2.lanes.get(0));
            h2.lanes.get(0).get(1).t = 4;
            h2.lanes.get(1).get(0).levelOut = true;      // a lock behind the flag
            moved = LaneSection.syncFollowingEnds(h2, 0.01);
            t = lastT(h2);
            check(moved == 1 && flags(h2, false, false, true) && t[1] == 1 && t[2] == 4,
                "b7 belt: a locked follower is cleared and left, the pristine one follows", flagStr(h2) + " " + t[1] + " " + t[2]);
            // b13 the redraw belt alone: clearStaleFollows CLEARS a
            // value-/handle-/lock-edited follower and NEVER moves any
            // last node — even with a defining lane far ahead (D248
            // review fix: redraw() runs only this; the moving sync stays
            // at the gesture sinks, before their rebuild)
            Coaster c3 = fixture();
            LaneSection h3 = sec(c3, 0, 1);
            LaneSection.touchLane(h3, h3.lanes.get(0));
            h3.lanes.get(0).get(1).t = 6;                // lane 0 defines 6 s
            h3.lanes.get(1).get(1).val = 0.4;            // lane 1 edited behind the flag
            double[] t3 = lastT(h3);
            int cleared = LaneSection.clearStaleFollows(h3);
            double[] t3b = lastT(h3);
            check(cleared == 1 && flags(h3, false, false, true)
                    && t3b[0] == t3[0] && t3b[1] == t3[1] && t3b[2] == t3[2] && t3b[2] == 1,
                "b13 clearStaleFollows clears the edited follower and moves NOTHING (lane 2 still at 1 s under a 6 s definer)",
                cleared + " " + flagStr(h3) + " " + t3b[2]);
            check(LaneSection.clearStaleFollows(h3) == 0 && LaneSection.clearStaleFollows(null) == 0,
                "b13b a second belt pass / a null section clear nothing");
            check(LaneSection.syncFollowingEnds(h3, 0.01) == 1 && lastT(h3)[2] == 6,
                "b13c the sink's full sync then moves the pristine follower onto the definer", lastT(h3)[2] + "");
            // the predicate itself
            List<BezierBaker.Node> p = lane(new double[][]{ {0, 1}, {1, 1} });
            check(LaneSection.pristineHold(p), "b5a pristineHold: the hold shape");
            List<BezierBaker.Node> q = lane(new double[][]{ {0, 1}, {1, 1}, {2, 1} });
            check(!LaneSection.pristineHold(q), "b5b pristineHold: 3 nodes is not a hold");
            check(!LaneSection.pristineHold(lane(new double[][]{ {0, 1} })),
                "b5c pristineHold: 1 node is not a hold");
            List<BezierBaker.Node> r = lane(new double[][]{ {0, 1}, {1, 1} });
            r.get(0).outFrac = 0.5;
            check(!LaneSection.pristineHold(r), "b5d pristineHold: a non-default facing handle fails");
            List<BezierBaker.Node> s = lane(new double[][]{ {0, 1}, {1, 1} });
            s.get(1).outFrac = 0.5; s.get(0).inFrac = 0.9;   // DEAD handles
            check(LaneSection.pristineHold(s), "b5e pristineHold ignores the two dead outer handles");
            List<BezierBaker.Node> u = lane(new double[][]{ {0, 1}, {1, 1} });
            u.get(0).straightOut = true;
            check(!LaneSection.pristineHold(u), "b5f pristineHold: a lock fails");
            check(!LaneSection.pristineHold(null) && !LaneSection.pristineHold(new ArrayList<>()),
                "b5g pristineHold: null / empty");
        }
        {
            // b8 1-node lanes neither define nor follow
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            hold.lanes.get(2).remove(1);                 // a 1-node hold (split's right half shape)
            hold.endFollows[2] = true;
            LaneSection.touchLane(hold, hold.lanes.get(0));
            hold.lanes.get(0).get(1).t = 2;
            int moved = LaneSection.syncFollowingEnds(hold, 0.01);
            check(moved == 1 && hold.lanes.get(2).size() == 1 && lastT(hold)[1] == 2,
                "b8a a 1-node follower is not moved (belt clears it), the 2-node one follows", flagStr(hold));
            Coaster c2 = fixture();
            LaneSection h2 = sec(c2, 0, 1);
            h2.lanes.get(0).remove(1);
            h2.endFollows[0] = false;                    // a 1-node DEFINING lane
            moved = LaneSection.syncFollowingEnds(h2, 0.01);
            check(moved == 0 && lastT(h2)[1] == 1 && lastT(h2)[2] == 1,
                "b8b a 1-node lane never defines the end (nothing moves)");
        }
        {
            // b9 delta lanes are outside the mechanism
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            Coaster.enterInfluence(hold, 1.0);
            double dEnd = BezierBaker.rideEnd(hold.deltaLanes);
            check(!LaneSection.touchLane(hold, hold.deltaLanes.get(0)) && flags(hold, true, true, true),
                "b9a touching a delta lane clears nothing");
            LaneSection.touchLane(hold, hold.lanes.get(0));
            hold.lanes.get(0).get(1).t = 3;
            LaneSection.syncFollowingEnds(hold, 0.01);
            check(BezierBaker.rideEnd(hold.deltaLanes) == dEnd && lastT(hold)[1] == 3,
                "b9b the sync moves the section's own lanes, never the deltas");
        }
        {
            // b10 the minGap floor, b11 DISTANCE domain
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            LaneSection.touchLane(hold, hold.lanes.get(0));
            hold.lanes.get(0).get(1).t = 0.001;          // below the floor (the editor never does this)
            LaneSection.syncFollowingEnds(hold, 0.01);
            check(lastT(hold)[1] == 0.01 && lastT(hold)[2] == 0.01,
                "b10 a follower never lands closer than minGap to its node 0", lastT(hold)[1] + "");
            LaneSection d = Coaster.continuation(sec(c, 0, 0), 24);
            d.domain(Section.Domain.DISTANCE);
            for (List<BezierBaker.Node> l : d.lanes) l.get(1).t = 10;   // the 10 m hold
            LaneSection.touchLane(d, d.lanes.get(1));
            d.lanes.get(1).get(1).t = 25;
            int moved = LaneSection.syncFollowingEnds(d, 0.1);
            check(moved == 2 && lastT(d)[0] == 25 && lastT(d)[2] == 25,
                "b11 DISTANCE lanes follow in meters (minGap 0.1 m passed by the caller)");
            d.lanes.get(1).get(1).t = 0.05;
            LaneSection.syncFollowingEnds(d, 0.1);
            check(lastT(d)[0] == 0.1, "b11b the meters floor", lastT(d)[0] + "");
        }

        // ================= c) IO =================
        {
            Coaster c = fixture();
            LaneSection hold = sec(c, 0, 1);
            hold.endFollows[1] = false;                  // an explicit 0 on a pristine lane
            String s1 = saveText(c);
            check(s1.contains(NL + "lane 0 0" + NL) && s1.contains(NL + "lane 0 1" + NL),
                "c1 the lane line carries the flag as its 3rd field (0 on the hill, 1 on the hold)");
            check(s1.contains(NL + "lane 1 0" + NL), "c1b an explicit 0 on the pristine lane is written");
            Coaster back = loadText(s1);
            LaneSection h = sec(back, 0, 1), hill = sec(back, 0, 0);
            check(flags(h, true, false, true), "c2 explicit flags load verbatim", flagStr(h));
            check(LaneSection.pristineHold(h.lanes.get(1)) && !h.endFollows[1],
                "c3 an explicit 0 on a hold-shaped lane SURVIVES (a deliberate non-follow is not re-derived)");
            check(flags(hill, false, false, false), "c2b the hill's flags load off", flagStr(hill));
            String s2 = saveText(back);
            check(s1.equals(s2), "c7 save→load→save is byte-identical with the new field");
            // c4/c5: a pre-D248 file = the same text with the field stripped
            String old = s1.replaceAll("(?m)^lane (\\d) [01](?=\\r?\\n)", "lane $1");
            check(!old.equals(s1) && old.contains(NL + "lane 1" + NL), "c4a the stripped fixture has 2-field lane lines");
            Coaster oldBack = loadText(old);
            LaneSection oh = sec(oldBack, 0, 1), ohill = sec(oldBack, 0, 0);
            check(flags(oh, true, true, true),
                "c4 missing field: a pristine hold DERIVES true (every lane, the explicit 0 is gone with the field)", flagStr(oh));
            check(fixedPoint(oldBack, oh, 1.0), "c4c ... a fixed point (no defining lane: the sync moves nothing)");
            check(flags(ohill, false, false, false),
                "c5 missing field: authored lanes derive false", flagStr(ohill));
            // c8 an explicitly EDITED-then-hold-shaped lane in an old file follows after reload (documented corner)
            check(oh.endFollows[1], "c8 (documented) an old file's hold-shaped lane follows even if it was once edited");
            // c10 a pristine hold sitting SHORT of the section end derives
            // FALSE (the follower invariant: never short of the end; a
            // derived true would move the node on first open — review
            // fix) — and so does the SOLE LONGEST pristine hold (D248
            // review fix #2): lane 0 here is the only lane at 3 s, so it
            // DEFINES the end; had it derived true the first sync would
            // have pulled it back onto the 1 s siblings (the section
            // shrinks on open — non-bitwise, undirtied). The derived
            // state must be a FIXED POINT of syncFollowingEnds.
            {
                Coaster sc = fixture();
                LaneSection sh = sec(sc, 0, 1);
                sh.endFollows(false);
                sh.lanes.get(0).get(1).t = 3;           // lane 0 extended past the holds
                String shortTxt = saveText(sc).replaceAll("(?m)^lane (\\d) [01](?=\\r?\\n)", "lane $1");
                Coaster sbc = loadText(shortTxt);
                LaneSection sb = sec(sbc, 0, 1);
                check(flags(sb, false, false, false),
                    "c10 missing field: a pristine hold SHORT of the end derives false AND the sole longest hold (the definer) derives false", flagStr(sb));
                check(fixedPoint(sbc, sb, 3.0), "c10b ... a fixed point: the sync moves nothing, rideEnd 3 s kept, save unchanged");
            }
            // c11 the reviewer's repro: a '+' hold (111, 1 s) whose Gl
            // last node was time-dragged short to 0.6 s (dv 0, DEFAULT
            // fractions — still pristine-shaped), saved, field stripped
            {
                Coaster sc = fixture();
                LaneSection sh = sec(sc, 0, 1);
                sh.lanes.get(1).get(1).t = 0.6;
                String txt = saveText(sc).replaceAll("(?m)^lane (\\d) [01](?=\\r?\\n)", "lane $1");
                Coaster ld = loadText(txt);
                LaneSection sb = sec(ld, 0, 1);
                check(LaneSection.pristineHold(sb.lanes.get(1)) && lastT(sb)[1] == 0.6,
                    "c11a fixture: the time-dragged Gl hold is still pristine-shaped at 0.6 s");
                check(flags(sb, false, false, false),
                    "c11 missing field: a time-dragged short sibling makes the 1 s holds the definers — nothing follows (was 101: the first redraw shrank the section to 0.6 s)", flagStr(sb));
                check(fixedPoint(ld, sb, 1.0), "c11b ... a fixed point: the sync moves nothing, rideEnd 1 s kept, save unchanged");
            }
            // c12 the triage probe: lane 0 authored 0..5 s, lane 1 a
            // pristine hold 0..6 s, lane 2 a pristine hold 0..5 s
            {
                Coaster sc = fixture();
                LaneSection sh = sec(sc, 0, 1);
                sh.lanes.set(0, lane(new double[][]{ {0, 1}, {2, 1.4}, {5, 1} }));
                sh.lanes.get(1).get(1).t = 6;
                sh.lanes.get(2).get(1).t = 5;
                String txt = saveText(sc).replaceAll("(?m)^lane (\\d) [01](?=\\r?\\n)", "lane $1");
                Coaster ld = loadText(txt);
                LaneSection sb = sec(ld, 0, 1);
                check(flags(sb, false, false, false),
                    "c12 missing field: the 6 s pristine hold is the sole longest lane — it defines, nothing follows", flagStr(sb));
                check(fixedPoint(ld, sb, 6.0), "c12b ... a fixed point: the sync moves nothing, rideEnd 6 s kept, save unchanged");
            }
            // c13 every pristine hold AT the end with an authored definer
            // ending there too: the holds follow, and that is a fixed point
            {
                Coaster sc = fixture();
                LaneSection sh = sec(sc, 0, 1);
                sh.lanes.set(0, lane(new double[][]{ {0, 1}, {0.5, 1.3}, {1, 1} }));
                String txt = saveText(sc).replaceAll("(?m)^lane (\\d) [01](?=\\r?\\n)", "lane $1");
                Coaster ld = loadText(txt);
                LaneSection sb = sec(ld, 0, 1);
                check(flags(sb, false, true, true),
                    "c13 missing field: holds AT the end follow when the defining lane ends there", flagStr(sb));
                check(fixedPoint(ld, sb, 1.0), "c13b ... a fixed point: the sync moves nothing, save unchanged");
            }
            // the derived document re-saves WITH the field and round-trips
            String s3 = saveText(oldBack);
            check(s3.contains(NL + "lane 1 1" + NL) && saveText(loadText(s3)).equals(s3),
                "c4b a re-saved pre-D248 file carries the field and round-trips");
            // flags feed no geometry
            TrackCompiler.Result r1 = TrackCompiler.compile(back, DT);
            TrackCompiler.Result r2 = TrackCompiler.compile(oldBack, DT);
            check(checksum(r1) == checksum(r2),
                "c9 the flags feed no geometry: compiles bitwise across flipped flags");
            sec(back, 0, 1).endFollows(false);
            check(checksum(TrackCompiler.compile(back, DT)) == checksum(r1),
                "c9b clearing every flag changes no sample");
        }
        {
            // c6 a healed (empty) lane never follows; a 1-node lane loads unflagged
            Coaster c = fixture();
            String s1 = saveText(c);
            // strip the hold's lane 1 node lines (the block "lane 1 1" + its n lines)
            int i = s1.lastIndexOf(NL + "lane 1 1" + NL);
            int j = s1.indexOf(NL + "lane 2 1" + NL, i + 1);
            check(i > 0 && j > i, "c6a fixture surgery found the hold's lane blocks");
            String damaged = s1.substring(0, i) + NL + "lane 1" + s1.substring(j);
            Coaster back = loadText(damaged);
            LaneSection h = sec(back, 0, 1);
            check(h.lanes.get(1).size() == 1 && !h.endFollows[1] && h.endFollows[0] && h.endFollows[2],
                "c6 a healed 1-node lane loads unflagged; its siblings still follow", flagStr(h));
        }

        System.out.println("\nEndFollowGate: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
