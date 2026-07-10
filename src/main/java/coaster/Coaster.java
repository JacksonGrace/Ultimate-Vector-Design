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
     *  share this one path. */
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
            FVDIntegrator.Sample a = s.get(Math.max(0, i - 2));
            FVDIntegrator.Sample b = s.get(Math.min(n - 1, i + 2));
            double ds = b.s() - a.s();
            if (ds < 1e-9) {
                if (i > 0) System.arraycopy(rate[i - 1], 0, rate[i], 0, 3);
                continue;
            }
            Vec3 dT = b.T().sub(a.T());
            Vec3 dU = b.U().sub(a.U());
            rate[i][0] = Math.toDegrees(dT.dot(sm.U()) / ds) * vG;
            rate[i][1] = Math.toDegrees(dT.dot(sm.R()) / ds) * vG;
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
        // lane sections integrate on WHOLE dt steps (while t <= end —
        // the D50 cadence), silently dropping a fractional tail: up to
        // 10 ms ≈ a quarter meter of track at speed, which was the
        // entire end error of the first fit. Land the duration ON the
        // grid (nearest, so ≤ 5 ms of shape is cut or held — the
        // unavoidable quantum every lane section shares), nudged up a
        // hair so the accumulated t <= end comparison keeps the final
        // step.
        double dur = Math.max(dt,
            Math.round(Math.max(0.1, tG[n - 1]) / dt) * dt) * (1 + 1e-9);
        // 0.1 s node spacing: the cubic fit's error scales with the
        // spacing⁴, and bridge rate curves can swing fast inside.
        // Delete nodes to taste after.
        int nNodes = Math.max(4, (int) Math.round(dur / 0.1) + 1);
        List<List<BezierBaker.Node>> lanes = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            List<BezierBaker.Node> lane = new ArrayList<>(nNodes);
            for (int k = 0; k < nNodes; k++) {
                double t = dur * k / (nNodes - 1);
                lane.add(new BezierBaker.Node(t,
                    val[idxAtTime(tG, t)][c]));
            }
            BezierBaker.applyDefaultHandles(lane);
            // C1 fit: each node's handles take the curve's slope AT
            // THE NODE SCALE (v13.13 fix, D87): the old ±2-SAMPLE
            // windows sat inside the rate extraction's own ±2-sample
            // smear at the section EDGES — the first/last few values
            // are nearly equal there, so edge handles came out LEVEL
            // (the user's partially-converted bridges). Slopes now
            // difference the fitted node values 0.1 s apart: central
            // inside, second-order one-sided at the ends.
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
            lanes.add(lane);
        }
        return toGeo ? new GeoSection(lanes, vD)
                     : new ForceSection(lanes, vD);
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
     *  real track — the full-convert rule). Both directions leave
     *  optSig null (first-sight: auto re-optimize records without
     *  acting — a partial convert must not move anything) and PIN
     *  each surviving bridge's LENGTH: optLen off + lastOptDist =
     *  that side's share of the cut arc (v13.12 fix — an inherited
     *  Optimize-length check re-ran the knee ladder on the first
     *  upstream edit and re-lengthed the bridge the user just cut;
     *  a stale full-length lastOptDist did the same on the RIGHT
     *  side). frac clamps to 5–95% with rate-fit margins. */
    public boolean partialConvertBridge(int pieceIdx, int secIdx,
                                        double frac, boolean left,
                                        boolean toGeo, double dt) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (!(piece.sections.get(secIdx) instanceof BridgeSection bs)) {
            return false;
        }
        List<FVDIntegrator.Sample> s = bs.integrate(dt).samples();
        int n = s.size();
        if (n < 20) return false;   // too short to split meaningfully
        double target = Math.max(0.05, Math.min(0.95, frac))
            * s.get(n - 1).s();
        int cut = 0;
        while (cut < n - 1 && s.get(cut).s() < target) cut++;
        cut = Math.max(6, Math.min(n - 7, cut));
        FVDIntegrator.Sample cs = s.get(cut);
        Quat cq = Quat.fromFrame(cs.T(), cs.U());
        double vCut = cs.v();       // the blend's design speed there
        if (left) {
            LaneSection conv = convertToLanes(s.subList(0, cut + 1),
                bs.vDesign, toGeo, dt);
            BridgeSection rem = new BridgeSection(
                new TrackPose(cs.pos(), cq),
                new TrackPose(bs.solvedTarget.pos(), bs.solvedTarget.orient()),
                Math.max(0.01, bs.lenA * (1 - frac)),
                Math.max(0.01, bs.lenB * (1 - frac)), vCut);
            rem.accB = bs.accB;
            rem.jerkB = bs.jerkB;
            rem.rollB = bs.rollB;
            rem.rollAccB = bs.rollAccB;
            rem.vB = bs.vB;
            rem.optMode = bs.optMode;
            // PIN the remainder's length (v13.12 fix): it keeps the
            // mode but NOT an inherited Optimize-length check — the
            // first upstream edit re-ran the knee ladder for the new
            // seam configuration and re-lengthed the remainder (the
            // user's "the length suddenly changes, with optimize
            // length suddenly becoming checked"). lastOptDist = its
            // own share of the cut, so auto re-optimize re-places at
            // exactly that length.
            rem.optLen = false;
            rem.lastOptDist = Math.max(1,
                s.get(n - 1).s() - cs.s());
            piece.sections.set(secIdx, conv);
            piece.sections.add(secIdx + 1, rem);
        } else {
            LaneSection conv = convertToLanes(s.subList(cut, n),
                vCut, toGeo, dt);
            // the kept bridge now ends at the cut; seed the far seam
            // with the dynamics MEASURED there (the next compile
            // re-captures them from the converted piece's head)
            bs.solvedTarget = new TrackPose(cs.pos(), cq);
            Vec3[] kj = TrackCompiler.seamDerivs(s, cut, 1);
            bs.accB = kj[0];
            bs.jerkB = kj[1];
            bs.rollB = TrackCompiler.seamRollRate(s, cut, 1);
            bs.rollAccB = TrackCompiler.seamRollAcc(s, cut, 1);
            bs.vB = vCut;
            bs.lenA = Math.max(0.01, bs.lenA * frac);
            bs.lenB = Math.max(0.01, bs.lenB * frac);
            bs.optSig = null;
            // PIN the kept bridge's length too (v13.12 fix): the OLD
            // full-length lastOptDist would have re-marched the whole
            // original distance to the converted piece on the first
            // upstream edit; and an inherited Optimize-length check
            // would re-knee it — both re-length the bridge the user
            // just cut.
            bs.optLen = false;
            bs.lastOptDist = Math.max(1, cs.s());
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
