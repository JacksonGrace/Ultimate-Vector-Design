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

    /** Hold extent of a DISTANCE-domain '+' continuation (m) — the s/c
     *  10 m default, the scale a "grabbable flat stretch" needs. */
    public static final double CONT_HOLD_DIST = 10;

    /** A SAME-MODE section holding the given section's final lane
     *  values — what the graph's '+' affordance appends (D55; geo
     *  joined in v10.1: holding the end rates keeps the transition as
     *  seamless as force-after-force). vDesign comes from the caller
     *  (the design speed at the previous section's exit, frozen at
     *  creation per the §0 invariant). The DOMAIN carries over
     *  (v16.0): after a distance section the hold is 10 m of track
     *  instead of 1 s of ride. */
    public static LaneSection continuation(LaneSection prev, double vDesign) {
        double[] end = new double[3];
        for (int i = 0; i < 3; i++) {
            List<BezierBaker.Node> src = prev.lanes.get(i);
            end[i] = src.get(src.size() - 1).val;
        }
        boolean dist = prev.domain() == Section.Domain.DISTANCE;
        List<List<BezierBaker.Node>> lanes = LaneSection.holdLanes(
            end[0], end[1], end[2], dist ? CONT_HOLD_DIST : 1);
        LaneSection out = prev instanceof GeoSection
            ? new GeoSection(lanes, vDesign) : new ForceSection(lanes, vDesign);
        out.domain(prev.domain());
        // axis flags carry (v16.1, D107): a hold of the end state only
        // CONTINUES seamlessly under the same axis semantics (global
        // roll: a zero-rate hold keeps the bank vs the horizon)
        out.rollGlobal = prev.rollGlobal;
        out.yawPitchGlobal = prev.yawPitchGlobal;
        return out;
    }

    /** A 1 s force section holding explicit Gv/Gl/roll-rate values —
     *  the force '+' after a GEO section holds the felt end state
     *  (design-speed Gv/Gl from the compiled samples + the geo roll
     *  lane's final value) instead of copying rate lanes as forces. */
    public static ForceSection holdSection(double gv, double gl, double roll,
                                           double vDesign) {
        return new ForceSection(LaneSection.holdLanes(gv, gl, roll), vDesign);
    }

    /** ENTER influence mode (v15.0 D98; re-specced v15.4 D102 — user:
     *  influenced sections "behave exactly like a normal force or geo
     *  section, just with little nudges"): the SPEEDS are untouched —
     *  vAuthored simply mirrors vDesign from here on (the setter keeps
     *  the pair locked), so entering influence never moves this
     *  section's design speed nor, through its design end, the next
     *  section's (the v15.0 "instant reconciliation" to the true entry
     *  did exactly that and silently closed the downstream section's
     *  speed conflict — the user's bug). Flat-zero delta lanes appear,
     *  spanning the section (a node at 0 AND at the end, dur = the
     *  compiled duration). Model-side so the headless gates drive the
     *  real thing (the v13.14 rule). */
    public static void enterInfluence(LaneSection ls, double dur) {
        if (ls.influenced()) return;
        ls.vAuthored = ls.vDesign;
        ls.deltaLanes = LaneSection.zeroDeltaLanes(dur);
        ls.editInfluence = true;
    }

    /** DISCARD influence: deltas + the operating speed drop, the
     *  authored pair governs again — the shape snaps back exactly
     *  (the un-influenced path is untouched code). */
    public static void discardInfluence(LaneSection ls) {
        if (!ls.influenced()) return;
        ls.vDesign = ls.vAuthored;
        ls.vAuthored = Double.NaN;
        ls.deltaLanes = null;
        ls.baseCurves = null;
        ls.editInfluence = false;
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
        piece.pitchDeg = 0;
        piece.rollDeg = 0;
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
        alignPieceForBridge(bs, targetIdx, dist, true);
    }

    /** Seam states as BODY components for the march (position/yaw-
     *  invariant): {κ_u, κ_r, dκ_u/ds, dκ_r/ds, roll/m, d(roll/m)/ds}.
     *  v13.8 (D82): the march's blend used to be LINEAR in κ and
     *  roll/m, IGNORING the measured seam jerks — while the septic is
     *  forced to LEAVE the seams at exactly those jerks. Placement and
     *  spline constraints disagreed, and the septic overshot near the
     *  seams to reconcile them (the user's "double bump"). The blend
     *  is now a cubic HERMITE per component with the measured jerks as
     *  end slopes — matched seam states with zero jerk (a split
     *  constant turn, straight track) reduce to the old constant blend
     *  exactly, keeping the D77 gates. */
    private static double[] seamState(Vec3 acc, Vec3 jerk, Vec3 u, Vec3 r,
                               double roll, double rollAcc, double v) {
        double vs = Math.max(0.5, v);
        return new double[]{ acc.dot(u), acc.dot(r),
            jerk.dot(u), jerk.dot(r),
            roll / vs, rollAcc / (vs * vs) };
    }

    /** Which target-orientation axes the optimization ALIGNS
     *  {yaw, pitch, roll} (v13.13, D87): in optimize-orientation mode
     *  the UNLOCKED axes align to the march's natural end; locked
     *  axes (and every axis outside that mode) are MET instead — the
     *  yaw/pitch humps and the roll twist close to the user's
     *  values. Defaults = yaw only = the historic P+Y. */
    private static boolean[] alignFlags(BridgeSection bs, boolean orient) {
        return orient
            ? new boolean[]{ !bs.lockYaw, !bs.lockPitch, !bs.lockRoll }
            : new boolean[]{ false, false, false };
    }

    /** March end state {pos, T, U} for a bridge at length d — the
     *  placement AND the virtual-target pose for the length optimizer
     *  share this one path. (The v14.1-14.3 roll-hump/bank-gap
     *  machinery was REVERTED on user verdict, D95 — see there for
     *  what was tried and why it failed before re-attempting.) */
    private static Vec3[] marchEnd(BridgeSection bs, double d,
                                   boolean[] align, List<Vec3> path,
                                   List<double[]> spine) {
        Vec3 pA = bs.solvedEntry.pos();
        Vec3 tA = bs.solvedEntry.T(), uA = bs.solvedEntry.U();
        Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
        double[] sa = seamState(bs.accA, bs.jerkA, uA, uA.cross(tA),
            bs.rollA, bs.rollAccA, bs.vDesign);
        double[] sb = seamState(bs.accB, bs.jerkB, uB, uB.cross(tB),
            bs.vB > 0 ? bs.rollB : 0, bs.vB > 0 ? bs.rollAccB : 0,
            bs.vB > 0 ? bs.vB : bs.vDesign);
        deadbandSlopes(sa, sb, d);
        // pass 1: the pure blend, to see where it naturally ends up;
        // pass 2 re-marches with the correction humps included. The
        // PITCH hump always closes the target's pitch (yaw can't fix
        // pitch in any mode). The YAW hump (v13.10, D84) closes the
        // target's HEADING when yaw is NOT ours to align ("optimize
        // position"): placing the piece on the yaw-blind continuation
        // left the septic to absorb the whole heading error — weird,
        // unsmooth shapes that jumped around with small length
        // changes; now the march itself turns to meet the fixed
        // heading, so the placement is reachable by a smooth path.
        Vec3[] st = march(pA, tA, uA, d, sa, sb, 0, 0, null, null);
        // humps close only the LOCKED axes (v13.13): an aligned axis
        // needs no correction — the PIECE rotates to meet the march
        double resPitch = align[1] ? 0
            : Math.asin(Math.max(-1, Math.min(1, tB.y())))
            - Math.asin(Math.max(-1, Math.min(1, st[1].y())));
        double resYaw = 0;
        Vec3 endT = st[1];
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        if (!align[0] && ha2 > 1e-12 && hb2 > 1e-12) {
            resYaw = Math.atan2(-tB.z(), tB.x())
                   - Math.atan2(-endT.z(), endT.x());
            while (resYaw > Math.PI) resYaw -= 2 * Math.PI;
            while (resYaw < -Math.PI) resYaw += 2 * Math.PI;
        }
        if (Math.abs(resPitch) > 1e-9 || Math.abs(resYaw) > 1e-9
                || path != null || spine != null) {
            st = march(pA, tA, uA, d, sa, sb,
                Math.abs(resPitch) > 1e-9 ? resPitch : 0,
                Math.abs(resYaw) > 1e-9 ? resYaw : 0, path, spine);
            // ONE bounded refinement (v14.5, D97): each hump's
            // integral is exact for the rotation it ADDS, but adding
            // it re-mixes the blend's own pitch/heading along the new
            // path — a second-order residual (~0.06° on a turning
            // fixture) that showed as a rate KINK at the far seam in
            // orientation mode (the user's "instant jump in values at
            // the end of the graph"; position mode's yaw hump happened
            // to cancel it, hence "wrong in one mode, right in the
            // other"). Re-measure once and re-march — quadratic
            // contraction (~1e-5°); the 1e-4 rad deadband keeps every
            // already-clean march on the historic two-march sequence
            // bit-identically. (NOT the reverted D95 bank iteration:
            // no wrap-ambiguous measure, hard one-pass bound.)
            double dP = align[1] ? 0
                : Math.asin(Math.max(-1, Math.min(1, tB.y())))
                - Math.asin(Math.max(-1, Math.min(1, st[1].y())));
            double dY = 0;
            Vec3 eT = st[1];
            double ea2 = eT.x() * eT.x() + eT.z() * eT.z();
            if (!align[0] && ea2 > 1e-12 && hb2 > 1e-12) {
                dY = Math.atan2(-tB.z(), tB.x())
                   - Math.atan2(-eT.z(), eT.x());
                while (dY > Math.PI) dY -= 2 * Math.PI;
                while (dY < -Math.PI) dY += 2 * Math.PI;
            }
            if (Math.abs(dP) > 1e-4 || Math.abs(dY) > 1e-4) {
                resPitch += dP;
                resYaw += dY;
                if (path != null) path.clear();
                if (spine != null) spine.clear();
                st = march(pA, tA, uA, d, sa, sb, resPitch, resYaw,
                    path, spine);
            }
        }
        return st;
    }

    /** The optimize-march SPINE (v13.12, D86): when a bridge's
     *  placement LANDS on its target — it was optimized and nothing
     *  moved since — the blended continuation ITSELF becomes the
     *  bridge geometry: rows {s, pos, T, U, κ} integrated straight
     *  from the seam states, so curvature is the jerk-matched Hermite
     *  blend plus the mid-span correction humps, with NO polynomial
     *  end-concentration (the user's "sharper turns near the start
     *  and end" on big-turn bridges — a degree-7 interpolant cannot
     *  bend uniformly for long, the march can). Returns null when the
     *  march misses the target (never optimized, target moved by
     *  hand, stale length): the SEPTIC remains the absorber there,
     *  which is also how pre-v13.12 files keep their exact shape
     *  (lastOptDist missing = 0). */
    static List<double[]> marchSpine(BridgeSection bs, boolean orient) {
        if (bs.lastOptDist <= 0) return null;
        List<double[]> spine = new ArrayList<>();
        Vec3[] st = marchEnd(bs, bs.lastOptDist,
            alignFlags(bs, orient), null, spine);
        double miss = st[0].sub(bs.solvedTarget.pos()).length();
        double tAng = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1,
            st[1].dot(bs.solvedTarget.T())))));
        return miss > 0.02 || tAng > 0.3 ? null : spine;
    }

    /** orient = false (the "optimize position" mode): the target
     *  keeps its whole user-set orientation — only its position lands
     *  on the march's endpoint; the march humps + twist absorb the
     *  orientation differences. orient = true: the UNLOCKED axes
     *  (yaw/pitch/roll deltas, v13.13) rotate the piece to meet the
     *  march's natural end instead — since each delta controls
     *  exactly one Euler quantity of the head frame (heading /
     *  pitch / bank), the alignments are independent additions. */
    public void alignPieceForBridge(BridgeSection bs, int targetIdx,
                                    double dist, boolean orient) {
        TrackPiece t = pieces.get(targetIdx);
        boolean[] align = alignFlags(bs, orient);
        Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
        double d = dist > 0 ? dist
            : Math.max(1, bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length());
        Vec3[] st = marchEnd(bs, d, align, null, null);
        Vec3 endT = st[1];
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        if (align[0] && ha2 > 1e-12 && hb2 > 1e-12) {   // headings defined
            double delta = Math.atan2(-endT.z(), endT.x())
                         - Math.atan2(-tB.z(), tB.x());
            double yaw = t.yawDeg + Math.toDegrees(delta);
            // wrap into the spinner's range, same orientation
            while (yaw > 360) yaw -= 360;
            while (yaw < -360) yaw += 360;
            t.yawDeg = yaw;
        }
        if (align[1] && Math.abs(tB.y()) < 0.999
                && Math.abs(endT.y()) < 0.999) {
            double dp = Math.asin(Math.max(-1, Math.min(1, endT.y())))
                      - Math.asin(Math.max(-1, Math.min(1, tB.y())));
            t.pitchDeg = Math.max(-85, Math.min(85,
                t.pitchDeg + Math.toDegrees(dp)));
        }
        if (align[2] && ha2 > 1e-6) {
            double endH2 = 1 - endT.y() * endT.y();
            if (endH2 > 1e-6) {
                double dr = ScIntegrator.bankOf(
                        Quat.fromFrame(endT, st[2]), endT, endH2)
                    - ScIntegrator.bankOf(bs.solvedTarget.orient(), tB,
                        1 - tB.y() * tB.y());
                while (dr > Math.PI) dr -= 2 * Math.PI;
                while (dr < -Math.PI) dr += 2 * Math.PI;
                t.rollDeg += Math.toDegrees(dr);
                while (t.rollDeg > 360) t.rollDeg -= 360;
                while (t.rollDeg < -360) t.rollDeg += 360;
            }
        }
        t.x = st[0].x();
        t.y = st[0].y();
        t.z = st[0].z();
    }

    /** What the NEXT compile will capture if the march at length d
     *  places the piece: the target pose (position = march end; the
     *  head frame rides the piece's yaw) AND the yaw rotation itself —
     *  the B-side seam dynamics (accB/jerkB) are WORLD vectors
     *  attached to the target's frame, so candidate solves must
     *  rotate them along (evaluating a ~70° -yawed placement against
     *  the stale vectors chose lens for a bridge that would never
     *  exist — the D77 circle gate caught it). */
    private record VirtTarget(TrackPose pose, Quat rot) {}

    private VirtTarget virtualTargetAt(BridgeSection bs, double d,
                                       boolean orient, List<Vec3> path) {
        boolean[] align = alignFlags(bs, orient);
        Vec3[] st = marchEnd(bs, d, align, path, null);
        Vec3 endT = st[1];
        Vec3 tB = bs.solvedTarget.T();
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        Quat o = bs.solvedTarget.orient();
        Quat rot = null;
        if (align[0] && ha2 > 1e-12 && hb2 > 1e-12) {
            double delta = Math.atan2(-endT.z(), endT.x())
                         - Math.atan2(-tB.z(), tB.x());
            rot = Quat.aboutY(Math.toDegrees(delta));
            o = rot.mul(o);
        }
        // pitch/roll alignment mirrors the piece deltas (v13.13):
        // each step rotates the head about the axis its delta uses,
        // composed onto `rot` so the B-side world vectors ride along
        if (align[1] && Math.abs(o.T().y()) < 0.999
                && Math.abs(endT.y()) < 0.999) {
            Vec3 ct = o.T();
            Vec3 r = new Vec3(-ct.z(), 0, ct.x());
            double len = r.length();
            if (len > 1e-9) {
                double dp = Math.asin(Math.max(-1, Math.min(1, endT.y())))
                          - Math.asin(Math.max(-1, Math.min(1, ct.y())));
                Quat rp = Quat.aboutAxis(r.scale(1 / len), dp);
                o = rp.mul(o);
                rot = rot == null ? rp : rp.mul(rot);
            }
        }
        if (align[2]) {
            Vec3 ct = o.T();
            double h2 = 1 - ct.y() * ct.y();
            double eh2 = 1 - endT.y() * endT.y();
            if (h2 > 1e-6 && eh2 > 1e-6) {
                double dr = ScIntegrator.bankOf(
                        Quat.fromFrame(endT, st[2]), endT, eh2)
                    - ScIntegrator.bankOf(o, ct, h2);
                while (dr > Math.PI) dr -= 2 * Math.PI;
                while (dr < -Math.PI) dr += 2 * Math.PI;
                Quat rr = Quat.aboutAxis(ct, dr);
                o = rr.mul(o);
                rot = rot == null ? rr : rr.mul(rot);
            }
        }
        return new VirtTarget(new TrackPose(st[0], o), rot);
    }

    /** A candidate solve consistent with the virtual placement. */
    private static BridgeSection virtProbe(BridgeSection bs, VirtTarget vt,
                                           double lenA, double lenB) {
        BridgeSection probe = bs.copy();
        probe.solvedTarget = vt.pose();
        if (vt.rot() != null) {
            probe.accB = vt.rot().rotate(bs.accB);
            probe.jerkB = vt.rot().rotate(bs.jerkB);
        }
        probe.lenA = lenA;
        probe.lenB = lenB;
        return probe;
    }

    /** Zero the march's Hermite SLOPE terms where their mid-span
     *  contribution is measurement noise, not signal (v13.8): the
     *  march multiplies seam jerks by d, and on an exact circle the
     *  ⊥-jerk MEASURES ~1e-4/m² of quadratic-fit noise — at d ≈ 30 m
     *  that shifted the placed piece 10 cm off the continued circle
     *  (the D77 gate caught it). Slope terms below 2% of the seam
     *  curvature/roll scale are dropped per end. */
    private static void deadbandSlopes(double[] sa, double[] sb, double d) {
        double kRef = Math.max(0.005, Math.max(
            Math.max(Math.abs(sa[0]), Math.abs(sa[1])),
            Math.max(Math.abs(sb[0]), Math.abs(sb[1]))));
        double lim = 0.02 * kRef / Math.max(1, 0.15 * d);
        if (Math.abs(sa[2]) < lim) sa[2] = 0;
        if (Math.abs(sa[3]) < lim) sa[3] = 0;
        if (Math.abs(sb[2]) < lim) sb[2] = 0;
        if (Math.abs(sb[3]) < lim) sb[3] = 0;
        double rRef = Math.max(0.005,
            Math.max(Math.abs(sa[4]), Math.abs(sb[4])));
        double rlim = 0.02 * rRef / Math.max(1, 0.15 * d);
        if (Math.abs(sa[5]) < rlim) sa[5] = 0;
        if (Math.abs(sb[5]) < rlim) sb[5] = 0;
    }

    /** ABSOLUTE ascending length candidates (m) for
     *  optimalBridgeLength (v13.8 second round, D82): anchoring the
     *  ladder to the CURRENT chord made every application re-anchor
     *  the next one's search domain — the piece moved, the chord
     *  moved, the candidates moved, and mode ping-pong wandered
     *  dramatically. In the optimize modes the whole cost is a pure
     *  function of the SEAM STATES (body components — invariant
     *  under moving/yawing the target), so absolute candidates make
     *  the optimal length a pure function of the seams too: ONE
     *  answer, wherever the piece currently sits. */
    private static final double[] LEN_LADDER =
        { 1, 2, 4, 8, 16, 32, 64, 128 };

    /** Length cost: peak frame-rotation rate (deg/s) plus a slope
     *  term (KNEE_SLOPE_WEIGHT per deg/s²) so a cramped transition —
     *  same peak, violent ramps — still reads as worse than a longer
     *  one. (These optimizer internals were briefly USER SETTINGS —
     *  Options in v13.9, per-bridge in v13.10-dev — and were pulled
     *  back to constants in v13.10, D84: the user found them
     *  confusing and their effects unpredictable; the tuned defaults
     *  ARE the behavior.) */
    private static final double KNEE_SLOPE_WEIGHT = 0.1;

    private static double rateCost(BridgeSection probe) {
        List<FVDIntegrator.Sample> s = probe.integrate(0.02).samples();
        double maxR = 0, maxSlope = 0, prev = Double.NaN;
        for (int i = 1; i < s.size(); i++) {
            double dt = s.get(i).t() - s.get(i - 1).t();
            if (dt < 1e-9) continue;
            Quat qa = Quat.fromFrame(s.get(i - 1).T(), s.get(i - 1).U());
            Quat qb = Quat.fromFrame(s.get(i).T(), s.get(i).U());
            double dot = Math.abs(qa.w() * qb.w() + qa.x() * qb.x()
                + qa.y() * qb.y() + qa.z() * qb.z());
            double r = Math.toDegrees(2 * Math.acos(Math.min(1, dot))) / dt;
            maxR = Math.max(maxR, r);
            if (!Double.isNaN(prev)) {
                maxSlope = Math.max(maxSlope, Math.abs(r - prev) / dt);
            }
            prev = r;
        }
        return maxR + KNEE_SLOPE_WEIGHT * maxSlope;
    }

    /** OPTIMIZE the bridge at length dist: march-place the piece and
     *  stamp the request (v13.12, D86 — the bridge's GEOMETRY is now
     *  the march spine itself whenever the placement lands, so there
     *  are no tangent lengths to search: the old LENS_GRID pair
     *  search chose the septic that best imitated the march; the
     *  march needs no imitation. lenA/lenB are set to the length as
     *  the septic FALLBACK's sweep for when a later manual move
     *  breaks the landing). Deterministic — same geometry + same
     *  dist = the one same bridge. */
    public void optimizeBridge(BridgeSection bs, int targetIdx,
                               double dist, boolean alignYaw) {
        bs.lastOptDist = dist;   // the auto-reoptimize path re-applies
                                 // at this length (v13.10, D84), and
                                 // the march spine reproduces from it
                                 // (v13.12, D86)
        bs.lenA = dist;
        bs.lenB = dist;
        alignPieceForBridge(bs, targetIdx, dist, alignYaw);
        // stamp the signature (v13.12, D86): explicit applies must
        // not re-trigger the auto-reoptimize pass
        bs.optSig = bs.optSignature();
    }

    /** AUTO RE-OPTIMIZE (v13.10, D84 — user rule: editing the track
     *  BEFORE a bridge updates its optimization automatically while
     *  an optimize mode is on; lived on the shell until v13.14 —
     *  moved here so the headless gates drive the real pass): after
     *  a compile, every live optimize-mode bridge whose
     *  PLACEMENT-INVARIANT seam signature changed re-runs its
     *  optimization — at its last requested length (the knee when
     *  "Optimize length" is checked; no zero-distance prompt on the
     *  automatic path). The signature (BridgeSection.optSignature,
     *  v13.12 D86) is pure SEAM GEOMETRY — no mode flags, no target
     *  heading: flag flips and mode switches must never re-place
     *  anything by themselves (their explicit handlers own that),
     *  and optimizeBridge STAMPS the signature when it applies, so
     *  explicit actions never double-fire this path. Optimizing
     *  moves/yaws the piece without changing its own signature, so
     *  ONE extra compile settles — no feedback loop. Returns true
     *  when the caller must recompile. */
    public boolean autoReoptimizeBridges(TrackCompiler.Result r) {
        boolean changed = false;
        int spanIdx = 0;
        for (int p = 0; p < pieces.size(); p++) {
            List<Section> secs = pieces.get(p).sections;
            for (int k = 0; k < secs.size(); k++, spanIdx++) {
                if (!(secs.get(k) instanceof BridgeSection bs)) continue;
                if (bs.optMode == BridgeSection.OPT_NONE
                        || p + 1 >= pieces.size()) continue;
                double[] sig = bs.optSignature();
                if (bs.optSig == null) {
                    // FIRST SIGHT (load / fresh bridge): record only.
                    // Loading a file must never move its pieces — the
                    // auto path acts on CHANGES from here on. (A
                    // partial convert on a march-rendering bridge
                    // plants SIG_STALE instead, which can never match:
                    // its pinned re-place happens right here, off the
                    // seams this compile just captured — v13.14, D88.)
                    bs.optSig = sig;
                    continue;
                }
                if (sigEq(sig, bs.optSig)) continue;
                boolean py = bs.optMode == BridgeSection.OPT_POS_YAW;
                double dist = bs.optLen
                    ? optimalBridgeLength(bs, py)
                    : bs.lastOptDist > 0 ? bs.lastOptDist
                    : Math.max(1, spanIdx < r.spans().size()
                        ? r.spans().get(spanIdx).sLen() : 1);
                optimizeBridge(bs, p + 1, dist, py);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean sigEq(double[] a, double[] b) {
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-9) return false;
        }
        return true;
    }

    /** "MAKE GRAPHS REAL" (v14.5, D97 — §5 round B's become-real,
     *  user spec): resolve a design/true split WITHOUT moving the
     *  track — the section re-authors as lanes fitted at its TRUE
     *  entry speed, so the graphs become the real ride (same fit
     *  engine as convert; shape kept to the fit residual). Lane
     *  sections re-fit in their own mode; s/c sections just
     *  re-freeze vDesign (shape speed-independent — both resolutions
     *  coincide). Bridges have no split (pure geometry in Pass D);
     *  REVERSED sections refuse (§5.3's rule). */
    public boolean makeGraphsReal(int pieceIdx, int secIdx, double dt,
                                  double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec.reversed() || sec instanceof BridgeSection) return false;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        double vTrue = Math.max(0.5, r.trueV()[sp.i0()]);
        // TILTED piece (v16.1, D106 — the refreezeSpeed rationale):
        // the fit below reads the REAL (tilted) samples, so the tilt
        // must be BAKED or the compile would rigidly tilt the fitted
        // section a second time. Baking keeps the entry pose exactly;
        // the fitted lanes reproduce the real shape from that frame
        // (for s/c nothing to fit — its shape is gravity-free, so
        // baking alone leaves the geometry identical and the refrozen
        // speed closes the whole split).
        if (piece.pitchDeg != 0 || piece.rollDeg != 0) piece.bakeTilt();
        if (sec instanceof StraightCurveSection) {
            sec.vDesign(vTrue);
            sec.vPinned(false);   // resolved: re-join the speed flow
            return true;
        }
        if (sp.i1() - sp.i0() < 4) return false;
        piece.sections.set(secIdx, convertToLanes(
            r.samples().subList(sp.i0(), sp.i1() + 1),
            vTrue, sec instanceof GeoSection, dt, spacing, adaptTol));
        return true;
    }

    /** "RE-FREEZE DESIGN SPEED" (v14.5, D97): the OTHER split
     *  resolution — keep the graphs and set the section's design
     *  speed to its true entry speed; the geometry re-derives from
     *  the same graphs at the new speed (the shape changes — the
     *  user's explicit trade). Reversed sections refuse. */
    public boolean refreezeSpeed(int pieceIdx, int secIdx, double dt) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec.reversed() || sec instanceof BridgeSection) return false;
        // ORIENTATION resolution (v16.1, D106 — the user's "refreeze
        // isn't fully resolving"): a TILTED piece's split is only
        // partly speed — the rigid tilt keeps the design solve in the
        // UNTILTED frame, so the felt orientation and the interior
        // speed profile (tilted heights vs design heights) stay split
        // however the entry speed is re-anchored; the old op fixed the
        // entry point only and the curves re-diverged right after it.
        // BAKE the tilt first: the entry pose is unchanged, generation
        // resumes FROM the real frame, gravity re-solves there —
        // "keep graphs, reshape" now covers orientation too and the
        // whole design/real split closes. Piece-scoped by nature (the
        // tilt is a piece property): sibling sections re-solve too.
        if (piece.pitchDeg != 0 || piece.rollDeg != 0) piece.bakeTilt();
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        sec.vDesign(Math.max(0.5,
            r.trueV()[r.spans().get(spanIdx).i0()]));
        sec.vPinned(false);   // resolved: re-join the speed flow
        return true;
    }

    /** AUTHORED-axis map of one compiled span (v16.0, D103): ascending
     *  rows {local t, local s, design v} covering the section, exact
     *  (0,0) and (tLen,sLen) anchors, REVERSED sections flipped out of
     *  train order so the rows map the AUTHORED axis directly. Shared
     *  by the domain switch below and the editor's cross-domain warp —
     *  one map, one truth. */
    public static double[][] sectionMap(List<FVDIntegrator.Sample> samples,
                                        TrackCompiler.Span sp, boolean reversed) {
        int i0 = sp.i0(), i1 = Math.min(sp.i1(), samples.size() - 1);
        double tLen = sp.t1() - sp.t0(), sLen = sp.sLen();
        List<double[]> rows = new ArrayList<>(i1 - i0 + 3);
        if (reversed) {
            // train order flipped: authored τ = tLen − localT ascending
            for (int i = i1; i >= i0; i--) {
                FVDIntegrator.Sample sm = samples.get(i);
                addMapRow(rows, tLen - (sm.t() - sp.t0()),
                    sLen - (sm.s() - sp.s0()), sm.v());
            }
            addMapRow(rows, tLen, sLen,
                rows.get(rows.size() - 1)[2]);   // exact authored end
        } else {
            // a continuation span's exact (0,0) entry was dropped as a
            // duplicate of the previous exit — restore the anchor
            FVDIntegrator.Sample first = samples.get(i0);
            if (first.t() - sp.t0() > 1e-12) {
                addMapRow(rows, 0, 0, first.v());
            }
            for (int i = i0; i <= i1; i++) {
                FVDIntegrator.Sample sm = samples.get(i);
                addMapRow(rows, sm.t() - sp.t0(), sm.s() - sp.s0(), sm.v());
            }
        }
        return rows.toArray(new double[0][]);
    }

    /** Append a map row, skipping degenerate (non-advancing) ones so
     *  both columns stay strictly monotonic through stalls. */
    private static void addMapRow(List<double[]> rows, double t, double s,
                                  double v) {
        if (!rows.isEmpty()) {
            double[] last = rows.get(rows.size() - 1);
            if (t - last[0] < 1e-12 || s - last[1] < 1e-12) return;
        }
        rows.add(new double[]{ t, s, v });
    }

    /** Linear read of a map column at x on another column, binary
     *  search inside, LINEAR EXTRAPOLATION past both ends (the edge
     *  segment's slope — end-node drags and end-of-lane nodes keep
     *  meaning something beyond the compiled extent, the s/c cursor
     *  rule). */
    static double mapCol(double[][] map, int from, int to, double x) {
        int n = map.length;
        if (n == 0) return x;
        if (n == 1) return map[0][to] + (x - map[0][from]);
        int lo = 0, hi = n - 1;
        if (x <= map[0][from]) {
            hi = 1;
        } else if (x >= map[hi][from]) {
            lo = n - 2;
        } else {
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (map[mid][from] <= x) lo = mid; else hi = mid;
            }
        }
        double dx = map[hi][from] - map[lo][from];
        double u = Math.abs(dx) < 1e-12 ? 0 : (x - map[lo][from]) / dx;
        return map[lo][to] + u * (map[hi][to] - map[lo][to]);
    }

    /** DOMAIN CONVERSION (v16.1, D104 — user re-spec 2026-07-12,
     *  REPLACING v16.0's exact node map): a same-mode carpet REFIT
     *  into the OTHER domain through the standard convert engine —
     *  density preview, uniform/adaptive placement, the works. The
     *  exact-shape role belongs to the cross-domain VIEW now (which
     *  warps without refitting); the conversion re-authors, and
     *  CONVERGES onto the original shape as the density rises (gated).
     *  vDesign/vPinned carry — speed conflicts persist; an INFLUENCED
     *  section's resulting curves are CAPTURED and the output is a
     *  plain section (the whole-convert rule: influence folded in,
     *  not preserved); reversed sections refuse (the fit reads the
     *  chain direction, the convert rule). */
    public boolean convertDomain(int pieceIdx, int secIdx, double dt,
                                 double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (!(sec instanceof LaneSection ls) || sec.reversed()) return false;
        boolean toDist = ls.domain() == Section.Domain.TIME;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 4) return false;   // too short to measure
        LaneSection conv = convertToLanes(
            r.samples().subList(sp.i0(), sp.i1() + 1),
            ls.vDesign(), ls instanceof GeoSection, dt, spacing, adaptTol,
            toDist ? Section.Domain.DISTANCE : Section.Domain.TIME);
        conv.vPinned = sec.vPinned();
        piece.sections.set(secIdx, conv);
        return true;
    }

    /** SPEED RECONCILE for same-piece chains (v14.1, D95 — user
     *  rule): editing upstream of a directly-chained force/geo/s-c
     *  section used to leave its FROZEN vDesign behind the speed that
     *  now actually reaches it — the design/true split appeared
     *  inside a plain chain, where the user wants the old
     *  one-integrator behavior: the graphs stay REAL and the
     *  downstream geometry re-derives from them at the true speed.
     *  After each compile, every continuation section of a piece
     *  re-freezes vDesign to the previous section's design-end speed
     *  (the same rule '+' and Shift+B apply, now automatic; within a
     *  piece the design profile then equals Pass D's true speed, so
     *  the split display never opens). Deliberately NOT touched:
     *  each piece's FIRST section (run entries — bridged/attached
     *  pieces keep the §5.1 drift feature: shape preserved, split
     *  shown) and REVERSED sections (their vDesign is the
     *  authored-direction entry, not the train's). Returns true when
     *  anything changed — the caller recompiles (rebuildTrack loops
     *  with the auto-reoptimize pass; heights shift speeds
     *  second-order, so a couple of passes settle). */
    public boolean reconcileChainedSpeeds(TrackCompiler.Result r) {
        boolean changed = false;
        int spanIdx = 0;
        for (TrackPiece piece : pieces) {
            for (int k = 0; k < piece.sections.size(); k++, spanIdx++) {
                if (k == 0 || spanIdx >= r.spans().size()) continue;
                Section sec = piece.sections.get(k);
                if (sec instanceof BridgeSection || sec.reversed()
                        || sec.vPinned()) continue;   // v14.5 D97: a
                        // pinned seam keeps its split deliberately
                // (the v15.0 influenced-section exemption was REMOVED
                // in v15.4, D102 — user re-spec: influenced sections
                // behave exactly like normal ones; the vDesign setter
                // keeps vAuthored mirrored, so a reconcile re-derives
                // the ref at the new speed with the deltas on top.
                // Pinned influenced sections still skip via the pin.)
                int ip = r.spans().get(spanIdx - 1).i1();
                double vIn = r.samples().get(ip).v();
                // REAL-ANCHORED continuation (v15.2, D101): a section
                // lined up with a drifted neighbor's TRUE curves (the
                // transition-to-real state) keeps chasing the REAL
                // seam speed — re-freezing it back onto the design
                // profile would silently undo the resolution. Which
                // anchor it follows is read off which speed its
                // vDesign already sits nearer (0.2 = the UI's
                // DRIFT_EPS).
                if (ip < r.trueV().length) {
                    double vTrue = r.trueV()[ip];
                    if (Math.abs(vTrue - vIn) > 0.2
                            && Math.abs(sec.vDesign() - vTrue)
                               < Math.abs(sec.vDesign() - vIn)) {
                        vIn = vTrue;
                    }
                }
                if (Math.abs(sec.vDesign() - vIn) > 1e-3) {
                    sec.vDesign(vIn);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** The rate cost of a virtually-marched candidate at length d —
     *  the probe's lastOptDist is the candidate length, so its
     *  integrate() runs the MARCH SPINE against the virtual pose (it
     *  lands by construction): the ladder scores exactly the
     *  geometry the apply would produce. A pure function of the seam
     *  states in the optimize modes. */
    private double lengthCost(BridgeSection bs, double d, boolean alignYaw) {
        VirtTarget virt = virtualTargetAt(bs, d, alignYaw, null);
        BridgeSection probe = virtProbe(bs, virt, d, d);
        probe.lastOptDist = d;
        return rateCost(probe);
    }

    /** The OPTIMAL length for the "Optimize length" check (v13.8,
     *  D82): the SHORTEST length whose rate cost (peak + slope term)
     *  is within 10% + 0.5 of the best achievable over the ABSOLUTE
     *  ladder, bisection-refined between neighbors. Smoothness
     *  saturates with length, so this finds the knee — and because
     *  everything here is a pure function of the SEAM STATES, it is
     *  ONE answer however the target piece has been moved or yawed:
     *  seams that continue each other smoothly (a split piece, ANY
     *  placement — matched values AND matched slopes, whose ideal
     *  connector wiggle only grows with length) collapse naturally
     *  to the 1 m floor, the caller's zero-distance cue; real
     *  mismatches (a roll to unwind, curvature to shed) buy exactly
     *  the length where the transition stops improving. Virtual
     *  solves only — nothing moves until the caller applies. */
    public double optimalBridgeLength(BridgeSection bs, boolean alignYaw) {
        double[] cost = new double[LEN_LADDER.length];
        double cmin = Double.MAX_VALUE;
        for (int i = 0; i < LEN_LADDER.length; i++) {
            cost[i] = lengthCost(bs, LEN_LADDER[i], alignYaw);
            cmin = Math.min(cmin, cost[i]);
        }
        double tol = cmin * 1.10 + 0.5;
        int knee = LEN_LADDER.length - 1;
        for (int i = 0; i < LEN_LADDER.length; i++) {
            if (cost[i] <= tol) {
                knee = i;
                break;
            }
        }
        if (knee == 0) return LEN_LADDER[0];
        // refine between the last too-short candidate and the knee
        double lo = LEN_LADDER[knee - 1], hi = LEN_LADDER[knee];
        for (int it = 0; it < 3; it++) {
            double mid = (lo + hi) / 2;
            if (lengthCost(bs, mid, alignYaw) <= tol) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /** Fixed-step RK4 march of the blended continuation: state
     *  {pos, T, U}, body curvature (κ_u, κ_r) and roll-per-meter
     *  blended A→B as cubic HERMITES whose end slopes are the
     *  measured seam jerks (v13.8, D82 — zero jerks reduce to the
     *  old blend), plus a parabolic WORLD-vertical curvature hump
     *  integrating to `residual` radians of pitch (0 = pure blend).
     *  sa/sb per seamState. Returns the end state. */
    private static Vec3[] march(Vec3 pos, Vec3 T, Vec3 U, double d,
                                double[] sa, double[] sb, double resPitch,
                                double resYaw, List<Vec3> path,
                                List<double[]> spine) {
        int n = (int) Math.min(40_000, Math.max(8, Math.ceil(d / 0.05)));
        double h = d / n;
        Vec3[] st = { pos, T.normalize(),
            U.sub(T.scale(U.dot(T))).normalize() };
        if (path != null) path.add(st[0]);
        if (spine != null) {
            spine.add(spineRow(0, st,
                contDeriv(st, 0, sa, sb, resPitch, resYaw, d)[1]));
        }
        for (int i = 0; i < n; i++) {
            double u0 = (double) i / n;
            double uh = (i + 0.5) / n;
            double u1 = (double) (i + 1) / n;
            Vec3[] k1 = contDeriv(st, u0, sa, sb, resPitch, resYaw, d);
            Vec3[] s2 = stepped(st, k1, h / 2);
            Vec3[] k2 = contDeriv(s2, uh, sa, sb, resPitch, resYaw, d);
            Vec3[] s3 = stepped(st, k2, h / 2);
            Vec3[] k3 = contDeriv(s3, uh, sa, sb, resPitch, resYaw, d);
            Vec3[] s4 = stepped(st, k3, h);
            Vec3[] k4 = contDeriv(s4, u1, sa, sb, resPitch, resYaw, d);
            for (int j = 0; j < 3; j++) {
                st[j] = st[j].add(k1[j]
                    .add(k2[j].scale(2)).add(k3[j].scale(2)).add(k4[j])
                    .scale(h / 6));
            }
            st[1] = st[1].normalize();
            st[2] = st[2].sub(st[1].scale(st[2].dot(st[1]))).normalize();
            if (path != null) path.add(st[0]);
            if (spine != null) {
                spine.add(spineRow((i + 1) * h, st,
                    contDeriv(st, u1, sa, sb, resPitch, resYaw, d)[1]));
            }
        }
        return st;
    }

    /** One march-spine row: {s, pos, T, U, κ} (13 doubles). */
    private static double[] spineRow(double s, Vec3[] st, Vec3 kap) {
        return new double[]{ s,
            st[0].x(), st[0].y(), st[0].z(),
            st[1].x(), st[1].y(), st[1].z(),
            st[2].x(), st[2].y(), st[2].z(),
            kap.x(), kap.y(), kap.z() };
    }

    /** CONVERT a compiled shape into editable lanes (v13.7, D81 — the
     *  unified "Convert" that v13.5's bridge unfreeze grew into:
     *  force↔geo, s/c→either, bridge→either). Per-METER rates are
     *  measured straight off the samples (frame change per arc — the
     *  shape's own quantity, no time axis), re-timed onto the target
     *  LaneSection's own ENERGY profile from vDesign (a bridge's
     *  blend profile is not energy; force/geo/s-c profiles ARE, so
     *  for them the re-time is an identity to float noise), then
     *  fitted as slope-matched nodes every 0.1 s. toGeo: lanes =
     *  pitch/yaw/roll rates (°/s). toForce: lanes = the felt targets
     *  that reproduce the same geometry at that profile — G = ω·v/g
     *  + axis.y per sample (the GeoIntegrator felt formulas) — plus
     *  the SAME roll-rate lane (roll is the shared third lane of
     *  both modes). Same shape to the fit residual + the dt cadence
     *  quantum (TESTING #20/#23); the node carpet is deliberately
     *  uniform — delete nodes to taste. */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt) {
        return convertToLanes(s, vDesign, toGeo, dt, NODE_SPACING_DEFAULT, 0);
    }

    /** Default target node spacing for conversions (s) — the density
     *  dialog's starting value (v14.0, D90). */
    public static final double NODE_SPACING_DEFAULT = 0.1;

    /** Full convert with fit control (v14.0, D90): spacing = TARGET
     *  node spacing (s) — endpoints pinned, interior evenly spaced,
     *  count chosen so the actual gaps are closest to the target;
     *  adaptTol > 0 switches to ADAPTIVE placement instead — nodes
     *  inserted at the worst-fit spot until each lane tracks its data
     *  within adaptTol × (that lane's value range); flat lanes come
     *  out as bare endpoints. */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            Section.Domain.TIME);
    }

    /** Default node spacing for DISTANCE-domain fits (m) — the time
     *  default's ×10 sibling (v16.1, D104). */
    public static final double NODE_SPACING_DEFAULT_DIST = 1.0;

    /** Convert with a TARGET DOMAIN (v16.1, D104): DISTANCE fits the
     *  node carpet over the section's ARC instead of the re-timed
     *  clock — spacing is then in METERS, the duration is the exact
     *  arc (distance lanes Newton-land, no dt-grid rounding), and the
     *  fit constants scale ×10 (min transition 0.1 m, insert gap
     *  0.2 m, slope window cap 1.5 m). */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom) {
        int n = s.size();
        double vD = Math.max(1, vDesign);
        double y0 = s.get(0).pos().y();
        double[] tG = new double[n];
        double[] vGs = new double[n];
        double[][] rate = new double[n][3];
        double prevVG = Math.max(0.5, vD);
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = s.get(i);
            double vG = Math.max(0.5,
                FVDIntegrator.speedAt(sm.pos(), vD, y0));
            vGs[i] = vG;
            if (i > 0) {
                double ds = sm.s() - s.get(i - 1).s();
                tG[i] = tG[i - 1] + ds / Math.max(0.5, (vG + prevVG) / 2);
            }
            prevVG = vG;
            // PITCH/YAW: ANALYTIC per sample (v15.1, D99 — the user's
            // "converges to a slightly different spot": the old ±2-
            // sample frame differencing low-passed the rate curves'
            // CORNERS, a floor no node density could recover; every
            // mode records felt to the κ identity κ = g(G − axis.y)/v²
            // — the same assumption Pass A's trueFelt rides — so the
            // curvature components come out exactly)
            double vSafe = Math.max(0.5, sm.v());
            double kU = FVDIntegrator.G0 * (sm.Gv() - sm.U().y())
                / (vSafe * vSafe);
            double kR = FVDIntegrator.G0 * (sm.Gl() - sm.R().y())
                / (vSafe * vSafe);
            rate[i][0] = Math.toDegrees(kU * vG);
            rate[i][1] = Math.toDegrees(kR * vG);
            // ROLL has no felt to invert — still measured, but over ±1
            // sample (second-order error drops 4×; the ±2 window was
            // the visible rounding on the roll spikes)
            FVDIntegrator.Sample a = s.get(Math.max(0, i - 1));
            FVDIntegrator.Sample b = s.get(Math.min(n - 1, i + 1));
            double ds = b.s() - a.s();
            if (ds < 1e-9) {
                if (i > 0) rate[i][2] = rate[i - 1][2];
                continue;
            }
            Vec3 dU = b.U().sub(a.U());
            rate[i][2] = Math.toDegrees(-dU.dot(sm.R()) / ds) * vG;
        }
        // target-mode VALUES per sample: geo = the rates themselves;
        // force = felt targets at the energy profile + shared roll
        double[][] val;
        if (toGeo) {
            val = rate;
        } else {
            val = new double[n][3];
            for (int i = 0; i < n; i++) {
                FVDIntegrator.Sample sm = s.get(i);
                val[i][0] = Math.toRadians(rate[i][0]) * vGs[i]
                    / FVDIntegrator.G0 + sm.U().y();
                val[i][1] = Math.toRadians(rate[i][1]) * vGs[i]
                    / FVDIntegrator.G0 + sm.R().y();
                val[i][2] = rate[i][2];
            }
        }
        // fit axis per target domain (v16.1, D104): TIME = the
        // re-timed clock, DISTANCE = the section-local arc
        double[] xG = tG;
        double xScale = 1;
        double dur;
        if (outDom == Section.Domain.DISTANCE) {
            xG = new double[n];
            double s0 = s.get(0).s();
            for (int i = 0; i < n; i++) xG[i] = s.get(i).s() - s0;
            xScale = 10;
            // EXACT arc: distance lanes Newton-land on their end — no
            // dt-grid quantum to round onto
            dur = Math.max(0.1, xG[n - 1]);
        } else {
            // lane sections integrate on WHOLE dt steps (while t <=
            // end — the D50 cadence), silently dropping a fractional
            // tail: up to 10 ms ≈ a quarter meter of track at speed,
            // which was the entire end error of the first fit. Land
            // the duration ON the grid (nearest, so ≤ 5 ms of shape
            // is cut or held — the unavoidable quantum every lane
            // section shares), nudged up a hair so the accumulated
            // t <= end comparison keeps the final step.
            dur = Math.max(dt,
                Math.round(Math.max(0.1, tG[n - 1]) / dt) * dt) * (1 + 1e-9);
        }
        List<List<BezierBaker.Node>> lanes = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            lanes.add(adaptTol > 0
                ? fitLaneAdaptive(xG, val, c, dur, adaptTol, xScale)
                : fitLaneUniform(xG, val, c, dur,
                    Math.max(0.01 * xScale, spacing), xScale));
        }
        LaneSection out = toGeo ? new GeoSection(lanes, vD)
                                : new ForceSection(lanes, vD);
        out.domain(outDom);
        return out;
    }

    /** Uniform fit: endpoints pinned at 0 and dur, interior nodes
     *  evenly spaced with the COUNT chosen so the actual gap is
     *  closest to the target spacing (v14.0, D90 — the fit's error
     *  scales with spacing⁴; the density dialog owns the knob). */
    private static List<BezierBaker.Node> fitLaneUniform(double[] tG,
            double[][] val, int c, double dur, double spacing,
            double xScale) {
        int nNodes = Math.max(2, (int) Math.round(dur / spacing) + 1);
        // MIN transition (0.01 s / 0.1 m): never fit segments the
        // editor refuses
        nNodes = Math.min(nNodes,
            1 + (int) Math.floor(dur / (0.01 * xScale) + 1e-9));
        List<BezierBaker.Node> lane = new ArrayList<>(nNodes);
        for (int k = 0; k < nNodes; k++) {
            double t = dur * k / (nNodes - 1);
            lane.add(new BezierBaker.Node(t, valAtT(tG, val, c, t)));
        }
        BezierBaker.applyDefaultHandles(lane);
        // C1 fit: each node's handles take the curve's slope AT
        // THE NODE SCALE (v13.13 fix, D87): the old ±2-SAMPLE
        // windows sat inside the rate extraction's own ±2-sample
        // smear at the section EDGES — the first/last few values
        // are nearly equal there, so edge handles came out LEVEL
        // (the user's partially-converted bridges). Slopes
        // difference the fitted node values one spacing apart:
        // central inside, second-order one-sided at the ends.
        double hstep = dur / (nNodes - 1);
        for (int k = 0; k < nNodes; k++) {
            double slope;
            if (k == 0) {
                slope = nNodes >= 3
                    ? (-3 * lane.get(0).val + 4 * lane.get(1).val
                        - lane.get(2).val) / (2 * hstep)
                    : (lane.get(1).val - lane.get(0).val) / hstep;
            } else if (k == nNodes - 1) {
                slope = nNodes >= 3
                    ? (3 * lane.get(k).val - 4 * lane.get(k - 1).val
                        + lane.get(k - 2).val) / (2 * hstep)
                    : (lane.get(k).val - lane.get(k - 1).val) / hstep;
            } else {
                slope = (lane.get(k + 1).val - lane.get(k - 1).val)
                    / (2 * hstep);
            }
            BezierBaker.Node nd = lane.get(k);
            if (k < nNodes - 1) {
                nd.outDv = slope
                    * BezierBaker.effOutDt(nd, lane.get(k + 1));
            }
            if (k > 0) {
                nd.inDv = slope
                    * BezierBaker.effInDt(lane.get(k - 1), nd);
            }
        }
        return lane;
    }

    /** ADAPTIVE fit (v14.0, D90 — the dialog's "Optimal placement"):
     *  start from the two endpoints and repeatedly insert a node at
     *  the worst-fitting sample until the lane tracks its data within
     *  tol × (the lane's value range). Node values come from the
     *  data; slopes are node-scale local differences (window bounded
     *  by the neighbor gaps — the D87 lesson); handles stay C1 at 1/3
     *  fracs, so the segment time-cubic is exactly linear and the
     *  curve evaluates in closed form. A flat lane is just its two
     *  endpoints; each lane gets only the nodes IT needs. */
    private static List<BezierBaker.Node> fitLaneAdaptive(double[] tG,
            double[][] val, int c, double dur, double tol,
            double xScale) {
        int n = tG.length;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            lo = Math.min(lo, val[i][c]);
            hi = Math.max(hi, val[i][c]);
        }
        double eps = Math.max(1e-9, (hi - lo) * tol);
        double dtData = n >= 2 ? Math.max(1e-6, tG[1] - tG[0]) : 0.01;
        List<BezierBaker.Node> lane = new ArrayList<>();
        lane.add(new BezierBaker.Node(0, valAtT(tG, val, c, 0)));
        lane.add(new BezierBaker.Node(dur, valAtT(tG, val, c, dur)));
        for (int guard = 0; guard < 400; guard++) {
            fitHandles(lane, tG, val, c, dtData, xScale);
            double worst = eps, worstT = -1;
            int seg = 0;
            for (int i = 0; i < n; i++) {
                double t = Math.min(tG[i], dur);
                while (seg < lane.size() - 2
                        && lane.get(seg + 1).t <= t) seg++;
                // respect the editor's INSERT_GAP (0.02 s / 0.2 m):
                // never propose a node on top of an existing one
                if (t - lane.get(seg).t < 0.02 * xScale
                        || lane.get(seg + 1).t - t < 0.02 * xScale) continue;
                double err = Math.abs(
                    fittedVal(lane.get(seg), lane.get(seg + 1), t)
                    - val[i][c]);
                if (err > worst) {
                    worst = err;
                    worstT = t;
                }
            }
            if (worstT < 0) break;
            int at = 1;
            while (at < lane.size() && lane.get(at).t < worstT) at++;
            lane.add(at, new BezierBaker.Node(worstT,
                valAtT(tG, val, c, worstT)));
        }
        fitHandles(lane, tG, val, c, dtData, xScale);
        return lane;
    }

    /** C1 handles for a fitted lane: each node takes the DATA's local
     *  slope over a window bounded by its neighbor gaps (node scale,
     *  the D87 lesson — sample-scale windows sit inside the rate
     *  extraction's own smear). */
    private static void fitHandles(List<BezierBaker.Node> lane,
            double[] tG, double[][] val, int c, double dtData,
            double xScale) {
        BezierBaker.applyDefaultHandles(lane);
        for (int k = 0; k < lane.size(); k++) {
            BezierBaker.Node nd = lane.get(k);
            double gapL = k > 0 ? nd.t - lane.get(k - 1).t : Double.MAX_VALUE;
            double gapR = k < lane.size() - 1
                ? lane.get(k + 1).t - nd.t : Double.MAX_VALUE;
            double h = Math.max(2 * dtData,
                Math.min(0.15 * xScale, Math.min(gapL, gapR) / 3));
            double t0 = Math.max(0, nd.t - h);
            double t1 = Math.min(tG[tG.length - 1], nd.t + h);
            double slope = t1 - t0 < 1e-9 ? 0
                : (valAtT(tG, val, c, t1) - valAtT(tG, val, c, t0))
                    / (t1 - t0);
            if (k < lane.size() - 1) {
                nd.outDv = slope * BezierBaker.effOutDt(nd, lane.get(k + 1));
            }
            if (k > 0) {
                nd.inDv = slope * BezierBaker.effInDt(lane.get(k - 1), nd);
            }
        }
    }

    /** Fitted-segment value at t: with 1/3 fracs the time cubic is
     *  exactly linear, so u = (t−a.t)/seg and only the value cubic
     *  needs evaluating. */
    private static double fittedVal(BezierBaker.Node a, BezierBaker.Node b,
                                    double t) {
        double seg = b.t - a.t;
        if (seg < 1e-12) return a.val;
        double u = Math.max(0, Math.min(1, (t - a.t) / seg));
        double w = 1 - u;
        double y0 = a.val, y1 = a.val + a.outDv;
        double y2 = b.val + b.inDv, y3 = b.val;
        return w * w * w * y0 + 3 * w * w * u * y1
             + 3 * w * u * u * y2 + u * u * u * y3;
    }

    /** Linear interpolation of a value channel over the tG grid. */
    private static double valAtT(double[] tG, double[][] val, int c,
                                 double t) {
        if (t <= tG[0]) return val[0][c];
        int hi = tG.length - 1;
        if (t >= tG[hi]) return val[hi][c];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (tG[mid] <= t) lo = mid; else hi = mid;
        }
        double u = (t - tG[lo]) / Math.max(1e-12, tG[hi] - tG[lo]);
        return val[lo][c] + u * (val[hi][c] - val[lo][c]);
    }

    /** PARTIAL bridge convert (v13.11, D85 — user spec): convert ONE
     *  side of a split at arc fraction `frac` into editable lanes;
     *  the OTHER side remains a live bridge. LEFT: the piece becomes
     *  [..., converted, bridge(cut→target)] — the bridge stays
     *  last-of-piece, live-follows the converted section's exit, and
     *  re-solves to the same measured end conditions, so the
     *  remainder stays ~identical (the septic sub-arc shares its end
     *  states). RIGHT: the piece SPLITS — [..., bridge(entry→cut)]
     *  plus a NEW piece at the exact cut pose holding the converted
     *  lanes; the kept bridge's next piece is now that converted
     *  piece and follows it live; the converted lanes end at the old
     *  target's entry, where the run splits (a converted stretch is
     *  real track — the full-convert rule). Both directions PIN
     *  each surviving bridge's LENGTH: optLen off + lastOptDist =
     *  that side's share of the cut arc (v13.12 fix — an inherited
     *  Optimize-length check re-ran the knee ladder on the first
     *  upstream edit and re-lengthed the bridge the user just cut;
     *  a stale full-length lastOptDist did the same on the RIGHT
     *  side). optSig (v13.14, D88): a bridge that was RENDERING the
     *  march gets SIG_STALE — the first rebuild's auto pass then
     *  re-places it at the pinned length off freshly captured seams
     *  (the cut nudges by the former landing miss; fit-residual
     *  order, zero for hump-free marches). Without that, the pinned
     *  march claim went unhonored — a fresh sub-march cannot
     *  reproduce a hump-carrying march's sub-arc, so the landing
     *  gate failed and the graph silently fell to the SEPTIC (the
     *  "partial convert reshapes the bridge graph" bug, which a
     *  placement-mode ping-pong "fixed" by doing this same re-place
     *  by hand). Septic-rendering bridges (never optimized, manual
     *  mode, hand-moved target) keep the old record-only null:
     *  nothing moves. frac clamps to 5–95% with rate-fit margins. */
    public boolean partialConvertBridge(int pieceIdx, int secIdx,
                                        double frac, boolean left,
                                        boolean toGeo, double dt) {
        return partialConvertBridge(pieceIdx, secIdx, frac, left, toGeo,
            dt, NODE_SPACING_DEFAULT, 0);
    }

    public boolean partialConvertBridge(int pieceIdx, int secIdx,
                                        double frac, boolean left,
                                        boolean toGeo, double dt,
                                        double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (!(piece.sections.get(secIdx) instanceof BridgeSection bs)) {
            return false;
        }
        List<FVDIntegrator.Sample> s = bs.integrate(dt).samples();
        int n = s.size();
        if (n < 20) return false;   // too short to split meaningfully
        // EXACT-fraction cut (v14.0, D89): the cut pose interpolates
        // between samples at exactly frac·total, so the split line's
        // % readout, the converted share and the pinned length all
        // agree (the old nearest-sample snap shifted the cut by up to
        // half a sample — the user's "the length is getting altered").
        // Rate-fit margins keep ≥ 7 samples per side.
        double arc = Math.max(s.get(6).s(), Math.min(s.get(n - 7).s(),
            Math.max(0.05, Math.min(0.95, frac)) * s.get(n - 1).s()));
        FVDIntegrator.Sample cs = sampleAtArc(s, arc);
        Quat cq = Quat.fromFrame(cs.T(), cs.U());
        // DESIGN blend speed at the cut (v14.5 corrected, D97 — user
        // rule: converting a bridge changes NOTHING it doesn't have
        // to. The converted lanes take the blend's own speed there,
        // so the design graphs continue what the bridge displayed,
        // the downstream piece keeps its design speed, and the
        // design/true SPLIT survives the conversion exactly as it
        // was. A brief v14.5 draft used the true-energy speed instead
        // — it closed the split and stepped the design graphs, the
        // opposite of "an extremely small difference".)
        double vCut = cs.v();
        double fracArc = arc / s.get(n - 1).s();   // post-clamp share
        // was the cut bridge rendering the MARCH? decided from the
        // pre-cut state, exactly like the integrate() above (D88)
        boolean wasMarch = bs.optMode != BridgeSection.OPT_NONE
            && marchSpine(bs, bs.optMode == BridgeSection.OPT_POS_YAW) != null;
        if (left) {
            LaneSection conv = convertToLanes(sideOf(s, cs, true),
                bs.vDesign, toGeo, dt, spacing, adaptTol);
            BridgeSection rem = new BridgeSection(
                new TrackPose(cs.pos(), cq),
                new TrackPose(bs.solvedTarget.pos(), bs.solvedTarget.orient()),
                Math.max(0.01, bs.lenA * (1 - fracArc)),
                Math.max(0.01, bs.lenB * (1 - fracArc)), vCut);
            rem.accB = bs.accB;
            rem.jerkB = bs.jerkB;
            rem.rollB = bs.rollB;
            rem.rollAccB = bs.rollAccB;
            rem.vB = bs.vB;
            rem.optMode = bs.optMode;
            rem.lockYaw = bs.lockYaw;      // v13.14: the remainder keeps
            rem.lockPitch = bs.lockPitch;  // the orientation locks (they
            rem.lockRoll = bs.lockRoll;    // silently reset before)
            // PIN the remainder's length (v13.12 fix): it keeps the
            // mode but NOT an inherited Optimize-length check — the
            // first upstream edit re-ran the knee ladder for the new
            // seam configuration and re-lengthed the remainder (the
            // user's "the length suddenly changes, with optimize
            // length suddenly becoming checked"). lastOptDist = its
            // own share of the cut, so auto re-optimize re-places at
            // exactly that length.
            rem.optLen = false;
            rem.lastOptDist = Math.max(1, s.get(n - 1).s() - arc);
            // march-rendering bridge: honor the pinned claim on the
            // first rebuild (v13.14, D88 — see the method doc)
            rem.optSig = wasMarch ? BridgeSection.SIG_STALE : null;
            piece.sections.set(secIdx, conv);
            piece.sections.add(secIdx + 1, rem);
        } else {
            List<FVDIntegrator.Sample> side = sideOf(s, cs, false);
            LaneSection conv = convertToLanes(side, vCut, toGeo, dt,
                spacing, adaptTol);
            // the kept bridge now ends at the cut; seed the far seam
            // with the dynamics MEASURED there (the next compile
            // re-captures them from the converted piece's head)
            bs.solvedTarget = new TrackPose(cs.pos(), cq);
            Vec3[] kj = TrackCompiler.seamDerivs(side, 0, 1);
            bs.accB = kj[0];
            bs.jerkB = kj[1];
            bs.rollB = TrackCompiler.seamRollRate(side, 0, 1);
            bs.rollAccB = TrackCompiler.seamRollAcc(side, 0, 1);
            bs.vB = vCut;
            bs.lenA = Math.max(0.01, bs.lenA * fracArc);
            bs.lenB = Math.max(0.01, bs.lenB * fracArc);
            // march-rendering bridge: honor the pinned claim on the
            // first rebuild (v13.14, D88 — see the method doc)
            bs.optSig = wasMarch ? BridgeSection.SIG_STALE : null;
            // PIN the kept bridge's length too (v13.12 fix): the OLD
            // full-length lastOptDist would have re-marched the whole
            // original distance to the converted piece on the first
            // upstream edit; and an inherited Optimize-length check
            // would re-knee it — both re-length the bridge the user
            // just cut.
            bs.optLen = false;
            bs.lastOptDist = Math.max(1, arc);
            if (pieceIdx + 1 < pieces.size()) {
                // KEEP THE TRACK CONNECTED (v13.13, D87 — user: the
                // right-convert used to strand the converted lanes on
                // their own piece, disconnecting everything after
                // them): the converted lanes PREPEND to the original
                // target piece, which re-anchors at the exact cut
                // pose — its old sections chain on after the lanes
                // (their own shapes untouched, §0; the downstream
                // track shifts only by the conversion's fit residual).
                TrackPiece tp = pieces.get(pieceIdx + 1);
                tp.x = cs.pos().x();
                tp.y = cs.pos().y();
                tp.z = cs.pos().z();
                tp.yawDeg = 0;
                tp.pitchDeg = 0;
                tp.rollDeg = 0;
                tp.baseOrient = cq;
                // PIN the old first section's design speed (v14.5,
                // D97): the split must survive the conversion — the
                // D95 reconcile would otherwise re-freeze it to the
                // prepended lanes' design end
                if (!tp.sections.isEmpty()) {
                    tp.sections.get(0).vPinned(true);
                }
                tp.sections.add(0, conv);
            } else {
                // orphaned bridge: no target to join — the converted
                // lanes ride a new piece at the cut (old behavior)
                TrackPiece np = new TrackPiece(cs.pos().x(), cs.pos().y(),
                    cs.pos().z(), 0);
                np.baseOrient = cq;
                np.sections.add(conv);
                pieces.add(pieceIdx + 1, np);
            }
        }
        return true;
    }

    /** One side of a cut: the samples strictly before (left) or after
     *  (right) the interpolated cut sample, with the cut sample itself
     *  closing that end — both sides share the EXACT boundary. */
    private static List<FVDIntegrator.Sample> sideOf(
            List<FVDIntegrator.Sample> s, FVDIntegrator.Sample cs,
            boolean left) {
        List<FVDIntegrator.Sample> out = new ArrayList<>();
        if (left) {
            for (FVDIntegrator.Sample sm : s) {
                if (sm.s() >= cs.s() - 1e-9) break;
                out.add(sm);
            }
            out.add(cs);
        } else {
            out.add(cs);
            for (FVDIntegrator.Sample sm : s) {
                if (sm.s() > cs.s() + 1e-9) out.add(sm);
            }
        }
        return out;
    }

    /** PARTIAL convert for the LANE and STRAIGHT/CURVE modes (v14.0,
     *  D89 — the bridge-only v13.11 feature generalized, user spec):
     *  split section secIdx at `frac` (of its DURATION for lane
     *  sections, of its ARC length for s/c) and re-author ONE side as
     *  editable lanes in the target mode; the OTHER side keeps its
     *  original representation EXACTLY — lane sections split by de
     *  Casteljau (the insert-on-curve identity: zero disturbance; a
     *  cut landing on a node just partitions the list, which is what
     *  the UI's node snap produces), s/c sections by element
     *  arithmetic (straights split by length, arcs by angle; the cut
     *  CLAMPS outside transition-blend and global-roll ease windows
     *  via scCutClamp — the only regions where truncation would
     *  reshape). The RIGHT side's vDesign re-freezes to the design
     *  speed AT the cut — the same energy expression, so the kept
     *  shape continues exactly (the '+' rule). Both sides stay on the
     *  piece, chained. Reversed sections refuse (the §5.3 rule).
     *  Returns false when no valid cut exists. */
    public boolean partialConvertSection(int pieceIdx, int secIdx,
            double frac, boolean left, boolean toGeo, double dt,
            double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec.reversed() || sec instanceof BridgeSection) return false;
        // INFLUENCED sections refuse the partial cut (v15.0, D98): the
        // kept side's ref = integrate(its half-lanes) cannot reproduce
        // the ORIGINAL ref's sub-arc once the deltas bent the entry
        // frame — bake or discard the influence first (the UI says so)
        if (sec instanceof LaneSection li && li.influenced()) return false;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 19) return false;   // too short to split
        List<FVDIntegrator.Sample> s =
            r.samples().subList(sp.i0(), sp.i1() + 1);
        int n = s.size();
        Section keep;
        FVDIntegrator.Sample cs;
        double vCut;
        if (sec instanceof LaneSection ls) {
            List<List<BezierBaker.Node>> half;
            if (ls.domain() == Section.Domain.DISTANCE) {
                // DISTANCE lanes cut in their own axis (v16.0): the cut
                // is an ARC fraction and the de Casteljau split runs on
                // the meter-parameterized lanes directly — exact, no
                // time mapping in the loop at all
                double sLen = sp.sLen();
                double sc2 = Math.max(s.get(6).s() - sp.s0(),
                    Math.min(s.get(n - 7).s() - sp.s0(),
                        Math.max(0.05, Math.min(0.95, frac)) * sLen));
                cs = sampleAtArc(s, sp.s0() + sc2);
                vCut = cs.v();
                half = splitLanes(ls.lanes, sc2, left);
            } else {
                double dur = sp.t1() - sp.t0();
                double tc = Math.max(s.get(6).t() - sp.t0(),
                    Math.min(s.get(n - 7).t() - sp.t0(),
                        Math.max(0.05, Math.min(0.95, frac)) * dur));
                cs = sampleAtTime(s, sp.t0() + tc);
                vCut = cs.v();
                half = splitLanes(ls.lanes, tc, left);
            }
            LaneSection kp = ls instanceof GeoSection
                ? new GeoSection(half, left ? vCut : ls.vDesign)
                : new ForceSection(half, left ? vCut : ls.vDesign);
            kp.domain(ls.domain());   // the kept half stays native
            keep = kp;
        } else if (sec instanceof StraightCurveSection sc) {
            double cutArc = scCutClamp(sc,
                Math.max(0.05, Math.min(0.95, frac)) * sc.totalLen());
            if (cutArc < 0) return false;
            cs = sampleAtArc(s, sp.s0() + cutArc);
            vCut = cs.v();
            keep = new StraightCurveSection(splitElements(sc, cutArc, left),
                left ? vCut : sc.vDesign);
        } else {
            return false;
        }
        keep.reversed(false);
        LaneSection conv = convertToLanes(sideOf(s, cs, left),
            left ? sec.vDesign() : vCut, toGeo, dt, spacing, adaptTol);
        // BOTH halves continue the original's DESIGN speed profile, so
        // a conflicted (pinned) section leaves two conflicted halves —
        // the pin rides onto each (v15.1, D99; unpinned originals get
        // false = today's behavior, where the reconcile's re-freeze of
        // the second half is a no-op on the shared profile anyway)
        keep.vPinned(sec.vPinned());
        conv.vPinned = sec.vPinned();
        piece.sections.set(secIdx, left ? conv : keep);
        piece.sections.add(secIdx + 1, left ? keep : conv);
        return true;
    }

    /** Split three lanes at LOCAL time tc, returning the requested
     *  side (right = the side whose vDesign re-freezes; keep = the
     *  side the caller does NOT convert — left=true means the LEFT
     *  side converts, so this returns the RIGHT half then). Nodes are
     *  deep-copied; a cut inside a segment goes through the exact de
     *  Casteljau identity (curve unchanged); a cut ON a node just
     *  partitions; a lane ending before tc holds — its right half is
     *  the single held-value node. Lock flags ride along (the mid
     *  node inherits the split segment's). */
    private static List<List<BezierBaker.Node>> splitLanes(
            List<List<BezierBaker.Node>> lanes, double tc, boolean keepRight) {
        List<List<BezierBaker.Node>> out = new ArrayList<>(3);
        for (List<BezierBaker.Node> lane : lanes) {
            List<BezierBaker.Node> copy = new ArrayList<>(lane.size());
            for (BezierBaker.Node nd : lane) copy.add(nd.copy());
            List<BezierBaker.Node> side = new ArrayList<>();
            BezierBaker.Node last = copy.get(copy.size() - 1);
            if (last.t <= tc + 1e-9) {
                // hold region: left half = the whole lane; right half
                // holds the final value from its own t = 0
                if (keepRight) {
                    side.add(new BezierBaker.Node(0, last.val));
                } else {
                    side.addAll(copy);
                }
            } else {
                int i = 0;
                while (i < copy.size() - 1 && copy.get(i + 1).t <= tc + 1e-9) i++;
                BezierBaker.Node at = copy.get(i);
                BezierBaker.Node mid;
                if (Math.abs(at.t - tc) <= 1e-9) {
                    mid = at;   // cut exactly ON a node (the UI snap)
                } else {
                    mid = BezierBaker.splitSegment(at, copy.get(i + 1), tc);
                    mid.t = tc;   // exact, not the bisected root
                    mid.straightOut = at.straightOut;
                    mid.levelOut = at.levelOut;
                    copy.add(i + 1, mid);
                    i++;
                }
                if (keepRight) {
                    // rebase by the boundary node's OWN time (== tc up
                    // to float dust when the cut landed on a node) so
                    // the right half's node 0 sits at exactly 0
                    double base = mid.t;
                    for (int k = i; k < copy.size(); k++) {
                        BezierBaker.Node nd = copy.get(k).copy();
                        nd.t -= base;
                        side.add(nd);
                    }
                } else {
                    for (int k = 0; k <= i; k++) side.add(copy.get(k));
                }
            }
            out.add(side);
        }
        return out;
    }

    /** Clamp an s/c cut OUT of the regions where truncating the
     *  element list does not reproduce the original shape: the
     *  sine-blend windows around internal boundaries (half the next
     *  element's smoothIn each side) and the global-roll ease window
     *  at the start of GLOBAL-mode elements (max(smoothIn, 2 m) — the
     *  right half would re-ease from the cut bank). Shared with the
     *  UI's split line so what you see is where it cuts. Returns the
     *  clamped LOCAL arc, or −1 when no valid spot exists. */
    public static double scCutClamp(StraightCurveSection sc, double arc) {
        double total = sc.totalLen();
        double end = 0.5;   // section-end margins (m)
        List<double[]> bad = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < sc.elements.size(); i++) {
            StraightCurveSection.Element e = sc.elements.get(i);
            if (i > 0 && e.smoothIn > 0) {
                bad.add(new double[]{ at - e.smoothIn / 2 - 0.1,
                                      at + e.smoothIn / 2 + 0.1 });
            }
            if (e.rollMode == StraightCurveSection.RollMode.GLOBAL) {
                bad.add(new double[]{ at, at + Math.max(e.smoothIn, 2) });
            }
            at += e.arcLen();
        }
        double x = Math.max(end, Math.min(total - end, arc));
        for (int guard = 0; guard < 8; guard++) {
            boolean moved = false;
            for (double[] b : bad) {
                if (x > b[0] && x < b[1]) {
                    double lo = b[0], hi = b[1];
                    double pick = x - lo <= hi - x ? lo : hi;
                    if (pick < end || pick > total - end) {
                        pick = pick == lo ? hi : lo;   // other edge
                    }
                    if (pick < end || pick > total - end) return -1;
                    x = pick;
                    moved = true;
                }
            }
            if (!moved) return x;
        }
        return -1;
    }

    /** Split an element list at LOCAL arc, returning the requested
     *  side's elements (deep copies; straights split by length, arcs
     *  by angle share; the right half's first element enters SHARP —
     *  its entry is the new section seam, and scCutClamp guarantees
     *  the cut sits in constant-state interior). */
    private static List<StraightCurveSection.Element> splitElements(
            StraightCurveSection sc, double arc, boolean keepRight) {
        List<StraightCurveSection.Element> out = new ArrayList<>();
        double at = 0;
        for (int i = 0; i < sc.elements.size(); i++) {
            StraightCurveSection.Element e = sc.elements.get(i);
            double len = e.arcLen();
            if (arc > at + 1e-9 && arc < at + len - 1e-9) {
                double u = (arc - at) / len;
                StraightCurveSection.Element part = e.copy();
                if (keepRight) {
                    if (e.kind == StraightCurveSection.Kind.STRAIGHT) {
                        part.length = len - (arc - at);
                    } else {
                        part.angleDeg = e.angleDeg * (1 - u);
                    }
                    part.smoothIn = 0;   // new section seam: sharp
                    out.add(part);
                } else {
                    if (e.kind == StraightCurveSection.Kind.STRAIGHT) {
                        part.length = arc - at;
                    } else {
                        part.angleDeg = e.angleDeg * u;
                    }
                    out.add(part);
                }
            } else if (keepRight ? at >= arc - 1e-9 : at + len <= arc + 1e-9) {
                out.add(e.copy());
            }
            at += len;
        }
        return out;
    }

    /** FULL convert of a non-bridge section (v14.0, D90): the model
     *  twin of the editor's v13.7 convert — compiled span re-authored
     *  as lanes at the requested density, in place. Lives here so the
     *  density preview can re-run it against a restored document.
     *  Bridges route to convertBridgeWhole (the attach rule). */
    public boolean convertWholeSection(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec instanceof BridgeSection) {
            return convertBridgeWhole(pieceIdx, secIdx, toGeo, dt,
                spacing, adaptTol);
        }
        if (sec.reversed()) return false;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 4) return false;   // too short to measure
        LaneSection conv = convertToLanes(
            r.samples().subList(sp.i0(), sp.i1() + 1),
            sec.vDesign(), toGeo, dt, spacing, adaptTol);
        // a SPEED-CONFLICTED section stays conflicted through a convert
        // (v15.1, D99 — user rule: "the geo section should still have
        // design/real speed conflict and shouldn't change shape"): the
        // pin rides along, so the reconcile keeps leaving it alone and
        // the shape survives (vDesign already carries over above)
        conv.vPinned = sec.vPinned();
        piece.sections.set(secIdx, conv);
        return true;
    }

    /** FULL bridge convert (v14.0, D89 — back alongside the v13.11
     *  split-line items, user spec "make sure it doesn't disconnect
     *  the track"): the whole solved geometry re-authors as lanes IN
     *  PLACE and the next piece ATTACHES — its sections chain on
     *  after the lanes (floating placement discarded, the attach
     *  rule), so the run stays ONE connected track. The v13.7 whole
     *  convert split the run and left the next piece floating; an
     *  orphaned bridge just converts. */
    public boolean convertBridgeWhole(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (!(piece.sections.get(secIdx) instanceof BridgeSection bs)) {
            return false;
        }
        List<FVDIntegrator.Sample> s = bs.integrate(dt).samples();
        if (s.size() < 5) return false;
        piece.sections.set(secIdx,
            convertToLanes(s, bs.vDesign, toGeo, dt, spacing, adaptTol));
        if (pieceIdx + 1 < pieces.size()) {
            // PIN the first attached section's design speed (v14.5,
            // D97, user rule): the target's design speeds and its
            // design/true split must survive the conversion exactly —
            // without the pin the D95 reconcile re-froze the attached
            // chain to the lanes' design end and closed the split.
            TrackPiece next = pieces.get(pieceIdx + 1);
            if (!next.sections.isEmpty()) {
                next.sections.get(0).vPinned(true);
            }
            attachPiece(pieceIdx, pieceIdx + 1);
        }
        return true;
    }

    /** Sample interpolated at EXACT arc s between its bracketing
     *  samples (linear pos/T/U/v/t/felt, frame re-orthonormalized —
     *  the same interpolation the bridge resampler uses). The
     *  exact-fraction cut poses ride on this (v14.0, D89). */
    static FVDIntegrator.Sample sampleAtArc(List<FVDIntegrator.Sample> s,
                                            double arc) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).s() <= arc) lo = mid; else hi = mid;
        }
        return lerpSample(s.get(lo), s.get(hi),
            keyFrac(s.get(lo).s(), s.get(hi).s(), arc), arc, Double.NaN);
    }

    /** Sample interpolated at EXACT ride time t (lane sections cut in
     *  their own domain). */
    static FVDIntegrator.Sample sampleAtTime(List<FVDIntegrator.Sample> s,
                                             double t) {
        int lo = 0, hi = s.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s.get(mid).t() <= t) lo = mid; else hi = mid;
        }
        return lerpSample(s.get(lo), s.get(hi),
            keyFrac(s.get(lo).t(), s.get(hi).t(), t), Double.NaN, t);
    }

    private static double keyFrac(double a, double b, double k) {
        return b - a < 1e-12 ? 0
            : Math.max(0, Math.min(1, (k - a) / (b - a)));
    }

    private static FVDIntegrator.Sample lerpSample(FVDIntegrator.Sample a,
            FVDIntegrator.Sample b, double u, double sExact, double tExact) {
        Vec3 pos = a.pos().add(b.pos().sub(a.pos()).scale(u));
        Vec3 T = a.T().add(b.T().sub(a.T()).scale(u)).normalize();
        Vec3 U = a.U().add(b.U().sub(a.U()).scale(u));
        U = U.sub(T.scale(U.dot(T))).normalize();
        double t = Double.isNaN(tExact)
            ? a.t() + (b.t() - a.t()) * u : tExact;
        double arc = Double.isNaN(sExact)
            ? a.s() + (b.s() - a.s()) * u : sExact;
        return new FVDIntegrator.Sample(t, arc, pos, T, U, U.cross(T),
            a.v() + (b.v() - a.v()) * u,
            a.Gv() + (b.Gv() - a.Gv()) * u,
            a.Gl() + (b.Gl() - a.Gl()) * u);
    }

    private static int idxAtTime(double[] tG, double t) {
        int lo = 0, hi = tG.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (tG[mid] <= t) lo = mid; else hi = mid;
        }
        return t - tG[lo] <= tG[hi] - t ? lo : hi;
    }

    /** st + k·h, componentwise (RK4 stage state). */
    private static Vec3[] stepped(Vec3[] st, Vec3[] k, double h) {
        return new Vec3[]{ st[0].add(k[0].scale(h)),
            st[1].add(k[1].scale(h)), st[2].add(k[2].scale(h)) };
    }

    /** d/ds of {pos, T, U} on the blended continuation at fraction u:
     *  pos' = T; T' = κ(u) rebuilt in the LOCAL frame + the vertical
     *  (and, position-only mode, HEADING) correction humps; U' = Ω×U
     *  with Ω = T×κ + roll·T. Components blend as quintic Hermites
     *  (value + measured-jerk slope at both ends, slopes scaled by d
     *  since u = s/d). */
    private static Vec3[] contDeriv(Vec3[] st, double u,
                                    double[] sa, double[] sb,
                                    double resPitch, double resYaw,
                                    double d) {
        Vec3 T = st[1].normalize();
        Vec3 U = st[2].sub(T.scale(st[2].dot(T))).normalize();
        Vec3 R = U.cross(T);
        // CUBIC Hermite basis: value + measured-jerk slope pinned at
        // both ends. v13.9 (D83) probed a QUINTIC here (zero end
        // second-derivative — softer rate-graph onsets) and it was
        // REVERTED in v13.10 on user feel: he's fine with graphs
        // bending right off the seam, and the mid-blend bulge the
        // quintic trades for it read as shape weirdness. Constant
        // matched ends reduce to a constant exactly (the D77/D82
        // gates).
        double h00 = 1 + u * u * (2 * u - 3);
        double h10 = u * (1 + u * (u - 2));
        double h01 = u * u * (3 - 2 * u);
        double h11 = u * u * (u - 1);
        double ku = h00 * sa[0] + h01 * sb[0]
                  + d * (h10 * sa[2] + h11 * sb[2]);
        double kr = h00 * sa[1] + h01 * sb[1]
                  + d * (h10 * sa[3] + h11 * sb[3]);
        double rm = h00 * sa[4] + h01 * sb[4]
                  + d * (h10 * sa[5] + h11 * sb[5]);
        Vec3 kap = U.scale(ku).add(R.scale(kr));
        if (resPitch != 0) {
            // world-vertical direction ⊥ T: rotating T toward it raises
            // pitch; 6u(1−u)/d integrates to exactly `resPitch`
            Vec3 vup = new Vec3(0, 1, 0).sub(T.scale(T.y()));
            double len = vup.length();
            if (len > 1e-9) {
                kap = kap.add(vup.scale(
                    resPitch * 6 * u * (1 - u) / d / len));
            }
        }
        if (resYaw != 0) {
            // rotation about WORLD Y: dT/ds = c·(Ŷ×T) changes the
            // HEADING at exactly c rad/m regardless of pitch (the
            // horiz² factors cancel), so the parabolic hump
            // integrates to exactly `resYaw` — the position-only
            // mode's fixed target heading is met BY THE MARCH, not
            // dumped on the septic (v13.10, D84)
            kap = kap.add(new Vec3(T.z(), 0, -T.x()).scale(
                resYaw * 6 * u * (1 - u) / d));
        }
        return new Vec3[]{ T, kap,
            T.scale(-kap.dot(U)).sub(R.scale(rm)) };
    }
}
