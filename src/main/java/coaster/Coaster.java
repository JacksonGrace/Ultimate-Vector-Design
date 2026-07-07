package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * The DOCUMENT (Phase 1, SECTIONS-DESIGN §2): everything the physics
 * pipeline consumes — pieces of sections plus the global dynamics
 * inputs. Pure Java.
 *
 * Cosmetics (heart offset, gauge, ties, rail colors) are deliberately
 * NOT here yet: they are render/IO concerns living in the UI layer and
 * travel through DesignIO.Cosmetics at save/load; they move into the
 * document with the milestone-B UI split (D50).
 *
 * v0 is the DYNAMICS entry speed (Pass D). Today it always equals the
 * single force section's vDesign — the Start-speed spinner writes both
 * — and they only diverge when Phase 3 speed control arrives.
 */
public class Coaster {

    public double v0 = 26;         // dynamics entry speed, m/s
    public double friction = 0;    // Phase 3 (kept 0, matching the integrator)

    public final List<TrackPiece> pieces = new ArrayList<>();

    /** Phase-3 dynamics regions — the MODEL slot exists from Phase 1 so
     *  FVDD3 and the pass pipeline have a stable place for them (§6).
     *  Anchored by global arc length s. Modes arrive in Phase 3. */
    public record SpeedRegion(double s0, double length, String mode, double value) {}

    public final List<SpeedRegion> speedRegions = new ArrayList<>();

    /** Today's app shape: one piece at (x,y,z), one force section whose
     *  lane lists are SHARED BY REFERENCE with the caller (the graph
     *  editor keeps editing the same objects). vDesign = v0. */
    public static Coaster singleForce(List<List<BezierBaker.Node>> lanes,
                                      double v0, double x, double y, double z) {
        Coaster c = new Coaster();
        c.v0 = v0;
        TrackPiece piece = new TrackPiece(x, y, z, 0);
        piece.sections.add(new ForceSection(lanes, v0));
        c.pieces.add(piece);
        return c;
    }

    /** The FIRST section of the first piece — the ride entry, whose
     *  vDesign the Start-speed spinner writes (any mode since v11.0 —
     *  deleting section 0 can leave a straight/curve section first). */
    public Section soleSection() {
        return pieces.get(0).sections.get(0);
    }

    /** A 1 s SAME-MODE section holding the given section's final lane
     *  values — what the graph's '+' affordance appends (D55; geo
     *  joined in v10.1: holding the end rates keeps the transition as
     *  seamless as force-after-force). vDesign comes from the caller
     *  (the design speed at the previous section's exit, frozen at
     *  creation per the §0 invariant). */
    public static LaneSection continuation(LaneSection prev, double vDesign) {
        double[] end = new double[3];
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> src = prev.lanes.get(i);
            end[i] = src.get(src.size() - 1).val;
        }
        List<List<BezierBaker.Node>> lanes = LaneSection.holdLanes(end[0], end[1], end[2]);
        return prev instanceof GeoSection
            ? new GeoSection(lanes, vDesign) : new ForceSection(lanes, vDesign);
    }

    /** A 1 s force section holding explicit Gv/Gl/roll-rate values —
     *  the force '+' after a GEO section holds the felt end state
     *  (design-speed Gv/Gl from the compiled samples + the geo roll
     *  lane's final value) instead of copying rate lanes as forces. */
    public static ForceSection holdSection(double gv, double gl, double roll,
                                           double vDesign) {
        return new ForceSection(LaneSection.holdLanes(gv, gl, roll), vDesign);
    }

    /** SPLIT a piece before section sectionIdx (v12.0, D70; exact
     *  orientation v12.1, D71): sections [sectionIdx..] move to a NEW
     *  piece inserted right after, placed at the compiled boundary
     *  pose — position AND full orientation (the pose lands in the
     *  piece's baseOrient, yaw delta 0), so splitting NEVER changes
     *  the track (bitwise, gated — user rule). Section objects move
     *  BY REFERENCE — lanes/elements stay the same objects the editor
     *  holds. Returns the new piece's index. */
    public int splitPiece(int pieceIdx, int sectionIdx, TrackPose boundary) {
        TrackPiece src = pieces.get(pieceIdx);
        if (sectionIdx <= 0 || sectionIdx >= src.sections.size()) return -1;
        TrackPiece cut = new TrackPiece(boundary.pos().x(), boundary.pos().y(),
            boundary.pos().z(), 0);
        cut.baseOrient = boundary.orient();
        List<Section> tail = src.sections.subList(sectionIdx, src.sections.size());
        cut.sections.addAll(tail);
        tail.clear();
        pieces.add(pieceIdx + 1, cut);
        return pieceIdx + 1;
    }

    /** REVERSE a whole piece (v12.1, D71 — the user-facing reversal;
     *  the per-section flag is its mechanism): a pure MODEL
     *  transformation, so the rest of the app needs no reversal mode —
     *  the section list flips to TRAIN order with each section's
     *  reversed flag toggled, and the placement re-anchors at the old
     *  EXIT pose (position + full orientation with T flipped, into
     *  baseOrient, yaw delta reset). On a level-seamed piece the track
     *  STAYS PUT and the train runs it backward (gated); pitched or
     *  banked seams re-join with the same yaw-only honesty as always
     *  (D67). Applying it twice restores the original (level: bitwise).
     *  The caller passes the piece's compiled exit pose. */
    public void reversePiece(int pieceIdx, TrackPose exit) {
        TrackPiece piece = pieces.get(pieceIdx);
        piece.x = exit.pos().x();
        piece.y = exit.pos().y();
        piece.z = exit.pos().z();
        piece.yawDeg = 0;
        piece.baseOrient = Quat.fromFrame(exit.T().scale(-1), exit.U());
        java.util.Collections.reverse(piece.sections);
        for (Section s : piece.sections) s.reversed(!s.reversed());
    }

    /** ATTACH a floating piece to the end of another (v12.0, D70): its
     *  sections append to the target's chain (re-chaining from the
     *  target's exit pose on the next compile — their LOCAL shapes are
     *  untouched, §0) and the emptied piece dissolves. The floating
     *  placement is deliberately discarded — attaching MEANS "continue
     *  from the target's end". */
    public void attachPiece(int targetIdx, int floatingIdx) {
        if (targetIdx == floatingIdx) return;
        TrackPiece target = pieces.get(targetIdx);
        TrackPiece floating = pieces.get(floatingIdx);
        target.sections.addAll(floating.sections);
        pieces.remove(floatingIdx);
    }

    /** Move a piece to a new list position (v13.1, D73): bridging to
     *  an arbitrary piece reorders it to just after the bridging piece
     *  — the piece order IS the ride timeline, so "connect these two"
     *  means "this one comes next". `to` is the FINAL index. */
    public void movePiece(int from, int to) {
        if (from == to) return;
        TrackPiece p = pieces.remove(from);
        pieces.add(to, p);
    }

    /** "Optimize placement" (v13.1, D73; SECTIONS-DISTANCE spinner
     *  v13.2; SECTIONS-DESIGN §4's second
     *  mode, closed-form): translate + yaw the TARGET piece (only
     *  those DOF, per spec) so it sits dead ahead of the chain end
     *  with its heading aligned — for level
     *  ends that is the zero-curvature (minimum strain) placement, and
     *  the live-follow bridge straightens accordingly. Pitched ends
     *  keep their honest vertical curvature (yaw cannot tilt).
     *  dist > 0 = the requested gap length (the toolbar spinner);
     *  dist ≤ 0 = keep the current gap distance. */
    public void alignPieceForBridge(TrackPose chainEnd, int targetIdx,
                                    double dist) {
        TrackPiece t = pieces.get(targetIdx);
        TrackPose cur = t.entryPose();
        double d = dist > 0 ? dist
            : Math.max(1, cur.pos().sub(chainEnd.pos()).length());
        Vec3 p = chainEnd.pos().add(chainEnd.T().scale(d));
        t.x = p.x();
        t.y = p.y();
        t.z = p.z();
        Vec3 ta = chainEnd.T(), tb = cur.T();
        double ha2 = ta.x() * ta.x() + ta.z() * ta.z();
        double hb2 = tb.x() * tb.x() + tb.z() * tb.z();
        if (ha2 > 1e-12 && hb2 > 1e-12) {   // both headings defined
            double delta = Math.toDegrees(
                Math.atan2(-ta.z(), ta.x()) - Math.atan2(-tb.z(), tb.x()));
            double yaw = t.yawDeg + delta;
            // wrap into the spinner's range, same orientation
            while (yaw > 360) yaw -= 360;
            while (yaw < -360) yaw += 360;
            t.yawDeg = yaw;
        }
    }
}
