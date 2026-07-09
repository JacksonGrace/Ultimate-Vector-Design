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
    private double[] seamState(Vec3 acc, Vec3 jerk, Vec3 u, Vec3 r,
                               double roll, double rollAcc, double v) {
        double vs = Math.max(0.5, v);
        return new double[]{ acc.dot(u), acc.dot(r),
            jerk.dot(u), jerk.dot(r),
            roll / vs, rollAcc / (vs * vs) };
    }

    /** March end state {pos, T, U} for a bridge at length d — the
     *  placement AND the virtual-target pose for the lens/length
     *  optimizers share this one path. `path` (nullable) collects the
     *  marched positions: the IDEAL the lens search measures against. */
    private Vec3[] marchEnd(BridgeSection bs, double d, List<Vec3> path) {
        Vec3 pA = bs.solvedEntry.pos();
        Vec3 tA = bs.solvedEntry.T(), uA = bs.solvedEntry.U();
        Vec3 tB = bs.solvedTarget.T(), uB = bs.solvedTarget.U();
        double[] sa = seamState(bs.accA, bs.jerkA, uA, uA.cross(tA),
            bs.rollA, bs.rollAccA, bs.vDesign);
        double[] sb = seamState(bs.accB, bs.jerkB, uB, uB.cross(tB),
            bs.vB > 0 ? bs.rollB : 0, bs.vB > 0 ? bs.rollAccB : 0,
            bs.vB > 0 ? bs.vB : bs.vDesign);
        deadbandSlopes(sa, sb, d);
        // pass 1: the pure blend, to see what pitch it naturally ends
        // at; pass 2 re-marches with the correction hump included
        Vec3[] st = march(pA, tA, uA, d, sa, sb, 0, null);
        double residual = Math.asin(Math.max(-1, Math.min(1, tB.y())))
                        - Math.asin(Math.max(-1, Math.min(1, st[1].y())));
        if (Math.abs(residual) > 1e-9 || path != null) {
            st = march(pA, tA, uA, d, sa, sb,
                Math.abs(residual) > 1e-9 ? residual : 0, path);
        }
        return st;
    }

    /** alignYaw = false (v13.5, the "optimize position" mode): the
     *  target keeps its user-set yaw — only its position lands on the
     *  march's endpoint; the bridge absorbs the heading difference. */
    public void alignPieceForBridge(BridgeSection bs, int targetIdx,
                                    double dist, boolean alignYaw) {
        TrackPiece t = pieces.get(targetIdx);
        Vec3 tB = bs.solvedTarget.T();
        double d = dist > 0 ? dist
            : Math.max(1, bs.solvedTarget.pos().sub(bs.solvedEntry.pos()).length());
        Vec3[] st = marchEnd(bs, d, null);
        Vec3 endT = st[1];
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        if (alignYaw && ha2 > 1e-12 && hb2 > 1e-12) {   // headings defined
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
                                       boolean alignYaw, List<Vec3> path) {
        Vec3[] st = marchEnd(bs, d, path);
        Vec3 endT = st[1];
        Vec3 tB = bs.solvedTarget.T();
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        Quat o = bs.solvedTarget.orient();
        Quat rot = null;
        if (alignYaw && ha2 > 1e-12 && hb2 > 1e-12) {
            double delta = Math.atan2(-endT.z(), endT.x())
                         - Math.atan2(-tB.z(), tB.x());
            rot = Quat.aboutY(Math.toDegrees(delta));
            o = rot.mul(o);
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

    /** Tangent-length candidates (× the requested length), preference
     *  order first (ties keep the earliest). FIXED grid = every
     *  re-optimize is a pure function of the geometry: the old
     *  multiplicative rescale (lens ×= requested/realized arc)
     *  compounded a non-inverting ratio and drifted the shape a
     *  little on every length round trip — the user's "different
     *  each time, a bit rougher". */
    private static final double[] LENS_GRID = { 1.0, 0.85, 1.2, 0.7, 1.45 };

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

    /** Lens cost: max POSITION deviation of a candidate solve from
     *  the MARCH PATH at matched arc fractions — the march is the
     *  ideal (it's why the piece sits where it sits), so the best
     *  tangents make the septic BE the continuation. (A peak-RATE
     *  cost was probed and rejected: on a continued circle it
     *  rewarded corner-cutting — lower peak curvature, 10–30 cm of
     *  radial sag.) Coarse dt is plenty — both curves are smooth. */
    private static double lensCost(BridgeSection bs, VirtTarget virt,
                                   double lenA, double lenB,
                                   List<Vec3> marchPath) {
        List<FVDIntegrator.Sample> s =
            virtProbe(bs, virt, lenA, lenB).integrate(0.02).samples();
        // LATERAL deviation only: nearest distance to the march
        // polyline. (Matching at arc FRACTIONS was probed and
        // rejected: the realized arc differs from the march length by
        // ~0.1%, and the resulting TANGENTIAL offset — centimeters,
        // pure parameterization — swamped the millimeters of real
        // lateral deviation the search needs to see.)
        int stride = Math.max(1, marchPath.size() / 200);
        double dev = 0;
        for (int k = 1; k < 24; k++) {
            Vec3 p = s.get((int) Math.round(
                (k / 24.0) * (s.size() - 1))).pos();
            dev = Math.max(dev, distToPath(p, marchPath, stride));
        }
        return dev;
    }

    /** Distance from p to the strided polyline (point-to-segment). */
    private static double distToPath(Vec3 p, List<Vec3> path, int stride) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < path.size() - 1; i += stride) {
            Vec3 a = path.get(i);
            Vec3 b = path.get(Math.min(i + stride, path.size() - 1));
            Vec3 ab = b.sub(a);
            double len2 = ab.dot(ab);
            double t = len2 < 1e-12 ? 0
                : Math.max(0, Math.min(1, p.sub(a).dot(ab) / len2));
            best = Math.min(best,
                p.sub(a).sub(ab.scale(t)).length());
        }
        return best;
    }

    // ---- bridge-optimizer preferences (v13.9, D83): per-user
    // Options ("Bridge optimizer" group) — applied at OPTIMIZE-ACTION
    // time only; the results (tangents, placement, length) are stored
    // in the .fvd, so saved files compile identically everywhere
    // regardless of these. Defaults = the gated v13.8 behavior.

    /** Tangent-sweep scale: multiplies the LENS_GRID candidates.
     *  Bigger = the bridge leaves/arrives straighter and turns more
     *  mid-span; smaller = tighter, hugging turns at the seams. */
    public static final double SWEEP_DEFAULT = 1.0;
    /** Knee tolerance (fraction): how much smoothness the length
     *  search trades for a shorter bridge. */
    public static final double KNEE_TOL_DEFAULT = 0.10;
    /** Slope weight (per deg/s²) in the length cost: how heavily
     *  violent rate ramps count against a candidate length. */
    public static final double SLOPE_W_DEFAULT = 0.1;

    /** Length cost: peak frame-rotation rate (deg/s) plus a slope
     *  term (slopeW per deg/s²) so a cramped transition — same peak,
     *  violent ramps — still reads as worse than a longer one. */
    private static double rateCost(BridgeSection bs, VirtTarget virt,
                                   double lenA, double lenB,
                                   double slopeW) {
        List<FVDIntegrator.Sample> s =
            virtProbe(bs, virt, lenA, lenB).integrate(0.02).samples();
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
        return maxR + slopeW * maxSlope;
    }

    /** OPTIMIZE the bridge at length dist (v13.8, D82): pick the
     *  tangent-length PAIR from (LENS_GRID·sweep)²·dist that keeps
     *  the septic closest to the march path, then march-place the
     *  piece. Deterministic — same geometry + same dist (+ same
     *  sweep) = the one same bridge, every time (the old
     *  multiplicative lens rescale drifted a little on every length
     *  round trip). */
    public void optimizeBridge(BridgeSection bs, int targetIdx,
                               double dist, boolean alignYaw) {
        optimizeBridge(bs, targetIdx, dist, alignYaw, SWEEP_DEFAULT);
    }

    public void optimizeBridge(BridgeSection bs, int targetIdx,
                               double dist, boolean alignYaw,
                               double sweep) {
        List<Vec3> path = new ArrayList<>();
        VirtTarget virt = virtualTargetAt(bs, dist, alignYaw, path);
        double best = Double.MAX_VALUE;
        double bestA = dist, bestB = dist;
        for (double ma : LENS_GRID) {
            for (double mb : LENS_GRID) {
                double la = ma * sweep * dist, lb = mb * sweep * dist;
                double c = lensCost(bs, virt, la, lb, path);
                if (c < best - 1e-9) {
                    best = c;
                    bestA = la;
                    bestB = lb;
                }
            }
        }
        bs.lenA = bestA;
        bs.lenB = bestB;
        alignPieceForBridge(bs, targetIdx, dist, alignYaw);
    }

    /** The rate cost of a virtually-marched candidate at length d,
     *  with the lens quick-picked by deviation (symmetric grid). A
     *  pure function of the seam states in the optimize modes. */
    private double lengthCost(BridgeSection bs, double d, boolean alignYaw,
                              double sweep, double slopeW) {
        List<Vec3> path = new ArrayList<>();
        VirtTarget virt = virtualTargetAt(bs, d, alignYaw, path);
        double bestDev = Double.MAX_VALUE, bestM = 1;
        for (double m : LENS_GRID) {
            double c = lensCost(bs, virt, m * sweep * d, m * sweep * d, path);
            if (c < bestDev - 1e-9) {
                bestDev = c;
                bestM = m;
            }
        }
        return rateCost(bs, virt, bestM * sweep * d, bestM * sweep * d,
            slopeW);
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
        return optimalBridgeLength(bs, alignYaw,
            KNEE_TOL_DEFAULT, SLOPE_W_DEFAULT, SWEEP_DEFAULT);
    }

    public double optimalBridgeLength(BridgeSection bs, boolean alignYaw,
                                      double kneeTol, double slopeW,
                                      double sweep) {
        double[] cost = new double[LEN_LADDER.length];
        double cmin = Double.MAX_VALUE;
        for (int i = 0; i < LEN_LADDER.length; i++) {
            cost[i] = lengthCost(bs, LEN_LADDER[i], alignYaw, sweep, slopeW);
            cmin = Math.min(cmin, cost[i]);
        }
        double tol = cmin * (1 + kneeTol) + 0.5;
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
            if (lengthCost(bs, mid, alignYaw, sweep, slopeW) <= tol) hi = mid;
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
                                double[] sa, double[] sb, double residual,
                                List<Vec3> path) {
        int n = (int) Math.min(40_000, Math.max(8, Math.ceil(d / 0.05)));
        double h = d / n;
        Vec3[] st = { pos, T.normalize(),
            U.sub(T.scale(U.dot(T))).normalize() };
        if (path != null) path.add(st[0]);
        for (int i = 0; i < n; i++) {
            double u0 = (double) i / n;
            double uh = (i + 0.5) / n;
            double u1 = (double) (i + 1) / n;
            Vec3[] k1 = contDeriv(st, u0, sa, sb, residual, d);
            Vec3[] s2 = stepped(st, k1, h / 2);
            Vec3[] k2 = contDeriv(s2, uh, sa, sb, residual, d);
            Vec3[] s3 = stepped(st, k2, h / 2);
            Vec3[] k3 = contDeriv(s3, uh, sa, sb, residual, d);
            Vec3[] s4 = stepped(st, k3, h);
            Vec3[] k4 = contDeriv(s4, u1, sa, sb, residual, d);
            for (int j = 0; j < 3; j++) {
                st[j] = st[j].add(k1[j]
                    .add(k2[j].scale(2)).add(k3[j].scale(2)).add(k4[j])
                    .scale(h / 6));
            }
            st[1] = st[1].normalize();
            st[2] = st[2].sub(st[1].scale(st[2].dot(st[1]))).normalize();
            if (path != null) path.add(st[0]);
        }
        return st;
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
            // C1 fit: each node's handles take the curve's local slope
            for (int k = 0; k < nNodes; k++) {
                double t = dur * k / (nNodes - 1);
                int i = idxAtTime(tG, t);
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                double span = tG[i1] - tG[i0];
                double slope = span < 1e-9 ? 0
                    : (val[i1][c] - val[i0][c]) / span;
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
     *  correction hump; U' = Ω×U with Ω = T×κ + roll·T. Components
     *  blend as cubic Hermites (value + measured-jerk slope at both
     *  ends, slopes scaled by d since u = s/d). */
    private static Vec3[] contDeriv(Vec3[] st, double u,
                                    double[] sa, double[] sb,
                                    double residual, double d) {
        Vec3 T = st[1].normalize();
        Vec3 U = st[2].sub(T.scale(st[2].dot(T))).normalize();
        Vec3 R = U.cross(T);
        // QUINTIC Hermite basis (v13.9, D83 — was cubic): value +
        // slope pinned at both ends AND zero end second-derivative,
        // so the rate graphs LEAVE each seam along their seam slope
        // and do their bending mid-blend — the cubic started bending
        // immediately (the user's "high onset curvature at the
        // ends"). Constant matched ends still reduce to a constant
        // exactly (the D77/D82 gates).
        double u2 = u * u, u3 = u2 * u;
        double h00 = 1 - u3 * (10 - 15 * u + 6 * u2);
        double h01 = u3 * (10 - 15 * u + 6 * u2);
        double h10 = u - u3 * (6 - 8 * u + 3 * u2);
        double h11 = -u3 * (4 - 7 * u + 3 * u2);
        double ku = h00 * sa[0] + h01 * sb[0]
                  + d * (h10 * sa[2] + h11 * sb[2]);
        double kr = h00 * sa[1] + h01 * sb[1]
                  + d * (h10 * sa[3] + h11 * sb[3]);
        double rm = h00 * sa[4] + h01 * sb[4]
                  + d * (h10 * sa[5] + h11 * sb[5]);
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
