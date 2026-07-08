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

    /** "Optimize placement" (v13.1 D73 dead-ahead; v13.4 D76 bisector;
     *  CONTINUATION MARCH v13.4 D77 — SECTIONS-DESIGN §4's second mode
     *  grown up): translate + yaw the TARGET piece (only those DOF,
     *  per spec) so its head sits where the LEAST-GRAPH-CHANGE path
     *  out of the chain end lands after `dist` meters of track. That
     *  path holds the seams' BODY-frame curvature and roll-per-meter,
     *  linearly blended entry→target over the length — matched seam
     *  states (a split banked turn) blend to a CONSTANT: the same
     *  circle, so the re-solved bridge continues the turn and a longer
     *  bridge just slides the piece further around it; zero-curvature
     *  seams reduce to dead-ahead exactly. The target's head PITCH is
     *  a hard constraint yaw cannot fix, so a second pass adds the
     *  smallest smooth vertical-rate hump (parabolic, zero at the
     *  seams) that turns the leftover pitch gap — a straight dive
     *  into a level head lands on a symmetric arc instead of forcing
     *  a dip-and-recover downstream. Yaw then aligns the target's
     *  ACTUAL head heading (solvedTarget — the live capture) to the
     *  march's end heading. Deterministic fixed-step RK4 at ~5 cm,
     *  hard step cap — no solver, no iteration, no failure mode: an
     *  absurd length on a tight turn just loops the circle, honestly
     *  and undoably. dist > 0 = requested length along the track;
     *  ≤ 0 = keep the current chord. */
    public void alignPieceForBridge(BridgeSection bs, int targetIdx,
                                    double dist) {
        TrackPiece t = pieces.get(targetIdx);
        Vec3 pA = bs.solvedEntry.pos();
        Vec3 tA = bs.solvedEntry.T(), uA = bs.solvedEntry.U();
        Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
        double d = dist > 0 ? dist
            : Math.max(1, bs.solvedTarget.pos().sub(pA).length());
        // seam states as BODY components (position/yaw-invariant)
        Vec3 rA = uA.cross(tA), rB = uB.cross(tB);
        double kuA = bs.accA.dot(uA), krA = bs.accA.dot(rA);
        double kuB = bs.accB.dot(uB), krB = bs.accB.dot(rB);
        double rmA = bs.rollA / Math.max(0.5, bs.vDesign);
        double rmB = bs.vB > 0 ? bs.rollB / Math.max(0.5, bs.vB) : 0;
        // pass 1: the pure blend, to see what pitch it naturally ends
        // at; pass 2 re-marches with the correction hump included
        Vec3[] st = march(pA, tA, uA, d, kuA, krA, kuB, krB, rmA, rmB, 0);
        double residual = Math.asin(Math.max(-1, Math.min(1, tB.y())))
                        - Math.asin(Math.max(-1, Math.min(1, st[1].y())));
        if (Math.abs(residual) > 1e-9) {
            st = march(pA, tA, uA, d, kuA, krA, kuB, krB, rmA, rmB,
                residual);
        }
        Vec3 endT = st[1];
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        if (ha2 > 1e-12 && hb2 > 1e-12) {   // both headings defined
            double delta = Math.atan2(-endT.z(), endT.x())
                         - Math.atan2(-tB.z(), tB.x());
            double yaw = t.yawDeg + Math.toDegrees(delta);
            // wrap into the spinner's range, same orientation
            while (yaw > 360) yaw -= 360;
            while (yaw < -360) yaw += 360;
            t.yawDeg = yaw;
        }
        t.x = st[0].x();
        t.y = st[0].y();
        t.z = st[0].z();
    }

    /** Fixed-step RK4 march of the blended continuation: state
     *  {pos, T, U}, body curvature (ku, kr) and roll-per-meter lerped
     *  A→B over the length, plus a parabolic WORLD-vertical curvature
     *  hump integrating to `residual` radians of pitch (0 = pure
     *  blend). Returns the end state. */
    private static Vec3[] march(Vec3 pos, Vec3 T, Vec3 U, double d,
                                double kuA, double krA,
                                double kuB, double krB,
                                double rmA, double rmB, double residual) {
        int n = (int) Math.min(40_000, Math.max(8, Math.ceil(d / 0.05)));
        double h = d / n;
        Vec3[] st = { pos, T.normalize(),
            U.sub(T.scale(U.dot(T))).normalize() };
        for (int i = 0; i < n; i++) {
            double u0 = (double) i / n;
            double uh = (i + 0.5) / n;
            double u1 = (double) (i + 1) / n;
            Vec3[] k1 = contDeriv(st, u0, kuA, krA, kuB, krB, rmA, rmB, residual, d);
            Vec3[] s2 = stepped(st, k1, h / 2);
            Vec3[] k2 = contDeriv(s2, uh, kuA, krA, kuB, krB, rmA, rmB, residual, d);
            Vec3[] s3 = stepped(st, k2, h / 2);
            Vec3[] k3 = contDeriv(s3, uh, kuA, krA, kuB, krB, rmA, rmB, residual, d);
            Vec3[] s4 = stepped(st, k3, h);
            Vec3[] k4 = contDeriv(s4, u1, kuA, krA, kuB, krB, rmA, rmB, residual, d);
            for (int j = 0; j < 3; j++) {
                st[j] = st[j].add(k1[j]
                    .add(k2[j].scale(2)).add(k3[j].scale(2)).add(k4[j])
                    .scale(h / 6));
            }
            st[1] = st[1].normalize();
            st[2] = st[2].sub(st[1].scale(st[2].dot(st[1]))).normalize();
        }
        return st;
    }

    /** st + k·h, componentwise (RK4 stage state). */
    private static Vec3[] stepped(Vec3[] st, Vec3[] k, double h) {
        return new Vec3[]{ st[0].add(k[0].scale(h)),
            st[1].add(k[1].scale(h)), st[2].add(k[2].scale(h)) };
    }

    /** d/ds of {pos, T, U} on the blended continuation at fraction u:
     *  pos' = T; T' = κ(u) rebuilt in the LOCAL frame + the vertical
     *  correction hump; U' = Ω×U with Ω = T×κ + roll·T. */
    private static Vec3[] contDeriv(Vec3[] st, double u,
                                    double kuA, double krA,
                                    double kuB, double krB,
                                    double rmA, double rmB,
                                    double residual, double d) {
        Vec3 T = st[1].normalize();
        Vec3 U = st[2].sub(T.scale(st[2].dot(T))).normalize();
        Vec3 R = U.cross(T);
        double ku = kuA + (kuB - kuA) * u;
        double kr = krA + (krB - krA) * u;
        double rm = rmA + (rmB - rmA) * u;
        Vec3 kap = U.scale(ku).add(R.scale(kr));
        if (residual != 0) {
            // world-vertical direction ⊥ T: rotating T toward it raises
            // pitch; 6u(1−u)/d integrates to exactly `residual`
            Vec3 vup = new Vec3(0, 1, 0).sub(T.scale(T.y()));
            double len = vup.length();
            if (len > 1e-9) {
                kap = kap.add(vup.scale(
                    residual * 6 * u * (1 - u) / d / len));
            }
        }
        return new Vec3[]{ T, kap,
            T.scale(-kap.dot(U)).sub(R.scale(rm)) };
    }
}
