package uvd;

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
 * single force section's vDesign — the Start-speed spinner writes
 * both. (Phase 3's speed CONTROLS live per section — SpeedControl on
 * LaneSection/StraightCurveSection, v18.0 D160.)
 */
public class Coaster {

    /** The coaster's NAME (v19.2, D230 — multi-coaster files): shown
     *  in the Coaster menu's list, the export dropdown and the file's
     *  `coaster` line. */
    public String name = "Coaster 1";

    /** Render look of an INACTIVE coaster (v19.2, D230): the shell
     *  keeps the ACTIVE coaster's look in its own fields and stashes
     *  it here on switch-away; null = defaults. Inactive coasters
     *  render in their SOLID rail color whatever their rail mode says
     *  (user spec). */
    public DesignIO.Cosmetics look;

    public double v0 = 26;         // dynamics entry speed, m/s

    /** PHASE 3 FRICTION (v17.0, D133) — the two per-coaster loss
     *  coefficients (SECTIONS-DESIGN §7; NL2's "friction" and "air
     *  resistance"): ROLLING is dimensionless (deceleration
     *  fricRoll·g — constant per meter, the first cut without
     *  normal-force scaling) and AIR is 1/m (deceleration
     *  fricAir·v²). Both 0 = the historic loss-free model, bitwise.
     *  Saved as the `friction` line; ride document undo (they change
     *  the track path). */
    public double fricRoll = 0;
    public double fricAir = 0;

    /** The document's friction model as one bundle. */
    FVDIntegrator.Friction fric() {
        return new FVDIntegrator.Friction(fricRoll, fricAir);
    }

    /** The compile step every path integrates at (0.01 s — the
     *  shell's FvdDesigner.INTEGRATE_DT reads this since v19.20,
     *  D249): the bridge march's LOCKSTEP inner settle re-captures
     *  the target's head at this dt so its stamped signature is
     *  bitwise the next compile's capture. */
    public static final double INTEGRATE_DT = 0.01;

    public final List<TrackPiece> pieces = new ArrayList<>();

    // (The Phase-1 SpeedRegion placeholder record retired in v18.0,
    // D160: real speed controls live PER SECTION — SpeedControl on
    // LaneSection/StraightCurveSection. No file ever carried a
    // `speedregion` line; the loader ignores any as unknown.)

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
        // rate units carry too (v16.6, D115): the copied end values
        // only mean the same thing in the same units
        out.ratePerMeter = prev.ratePerMeter;
        out.rollAbsolute = prev.rollAbsolute;   // v16.7, D118
        out.ypAbsolute = prev.ypAbsolute;       // v19.8, D237
        out.endFollows(true);   // a fresh hold follows the end (D248)
        return out;
    }

    /** A 1 s force section holding explicit Gv/Gl/roll-rate values —
     *  the force '+' after a GEO section holds the felt end state
     *  (design-speed Gv/Gl from the compiled samples + the geo roll
     *  lane's final value) instead of copying rate lanes as forces. */
    public static ForceSection holdSection(double gv, double gl, double roll,
                                           double vDesign) {
        return (ForceSection) new ForceSection(
            LaneSection.holdLanes(gv, gl, roll), vDesign).endFollows(true);
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
        // influence axis defaults (v16.5, D112): mirror the section's
        // roll semantics — with zero deltas the base then reproduces
        // the ref without representation conversion; yaw/pitch start
        // local (force sections have no ypG of their own to mirror)
        ls.inflRollGlobal = ls.rollGlobal;
        ls.inflYawPitchGlobal = false;
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
        ls.inflRollGlobal = false;
        ls.inflYawPitchGlobal = false;
    }

    /** SPLIT a piece before section sectionIdx (v12.0, D70; exact
     *  orientation v12.1, D71): sections [sectionIdx..] move to a NEW
     *  piece inserted right after, placed at the compiled boundary
     *  pose — position AND full orientation (the pose lands in the
     *  piece's baseOrient, yaw delta 0), so splitting NEVER changes
     *  the track (bitwise, gated — user rule). Section objects move
     *  BY REFERENCE — lanes/elements stay the same objects the editor
     *  holds. Returns the new piece's index. */
    public int splitPiece(int pieceIdx, int sectionIdx,
                          TrackCompiler.Span boundarySpan) {
        TrackPiece src = pieces.get(pieceIdx);
        if (sectionIdx <= 0 || sectionIdx >= src.sections.size()) return -1;
        TrackPose boundary = boundarySpan.exit();
        TrackPiece cut = new TrackPiece(boundary.pos().x(), boundary.pos().y(),
            boundary.pos().z(), 0);
        cut.baseOrient = boundary.orient();
        // CANONICAL pair (v18.27, D199): the new piece generates with
        // bitwise the parent chain's arithmetic — without this, the
        // decomposition of the composed world orientation costs
        // ~1e-16 rad, enough to re-roll a stall knot on split
        boolean pair = boundarySpan.exitCanon() != null;
        if (pair) {
            cut.canonPsiDeg = boundarySpan.exitPsi();
            cut.canonOrient = boundarySpan.exitCanon();
        }
        // TILTED parent (v19.1, D224 — closes the D100 "splits of a
        // tilted piece re-solve" corner): the boundary's world pose is
        // the DESIGN chain rigidly rotated by the parent's tilt
        // (conflict tilt or a follower's re-aim). The new piece takes
        // the design orientation as its base — the same chain the
        // pair generates — and the tilt as its OWN true-tier deltas
        // (a rotation about its entry, which is the parent's rotation
        // about ITS entry composed with the boundary translation, so
        // the maps coincide): generation stays bitwise, the world
        // geometry stays put to dust, and the panel shows the honest
        // conflict. Vertical design headings fall back to the old
        // path (no Euler triple exists there).
        Quat tilt = boundarySpan.exitTilt();
        if (pair && tilt != null && tilt != Quat.IDENTITY) {
            Quat design = tilt.conjugate().mul(boundary.orient());
            double[] d = TrackPiece.decomposeNear(design, boundary.orient(),
                0, 0, 0);
            if (d != null) {
                cut.baseOrient = design;
                cut.yawDeg = d[0];
                cut.pitchDeg = d[1];
                cut.rollDeg = d[2];
            } else {
                cut.canonOrient = null;   // re-solve from the world pose
            }
        }
        List<Section> tail = src.sections.subList(sectionIdx, src.sections.size());
        cut.sections.addAll(tail);
        tail.clear();
        // ABSOLUTE heading lanes REBASE onto the new piece (v19.9,
        // D238 — piece-relative heading): the moved sections' zero
        // was the OLD piece's entry; the new piece's is the
        // boundary. The shift is the chain-psi difference — pure
        // generation bits, so the split stays bitwise (the compile's
        // own shift changes by exactly the amount added here).
        if (pair) {
            rebaseAbsHeading(cut.sections,
                boundarySpan.exitPsi() - entryPsiInternal(src));
        }
        pieces.add(pieceIdx + 1, cut);
        return pieceIdx + 1;
    }

    /** A piece's entry chain heading (deg, INTERNAL sign — the psi
     *  pieceCanon generates at; v19.9, D238). */
    private static double entryPsiInternal(TrackPiece p) {
        return p.canonOrient != null
            ? p.canonPsiDeg + p.yawDeg
            : TrackCompiler.headingDeg(p.baseOrient) + p.yawDeg;
    }

    /** Shift every ABSOLUTE heading lane of the given sections by
     *  the INTERNAL chain-psi delta (deg) their piece's entry moved
     *  by (v19.9, D238): h' = h + Δpsi — reported-left heading is
     *  the negated chain psi, so a boundary the chain reached at
     *  +Δpsi sits at −Δpsi reported, and re-zeroing there subtracts
     *  it. Constant shift: handles, slopes and shape untouched. */
    private static void rebaseAbsHeading(List<Section> secs,
                                         double dPsiDeg) {
        if (dPsiDeg == 0) return;
        for (Section s : secs) {
            if (s instanceof GeoSection g && g.ypAbsolute
                    && g.yawPitchGlobal && !g.reversed) {
                for (BezierBaker.Node nd : g.lanes.get(1)) {
                    nd.val += dPsiDeg;
                }
            }
        }
    }

    /** RE-ANCHOR a piece at a compiled section boundary (v19.6, D235
     *  — deleting a piece's FIRST section must leave the remaining
     *  track exactly where it was, user spec): the splitPiece pose
     *  recipe written onto the EXISTING piece — position + full
     *  orientation from the boundary Span, the canonical pair for
     *  bitwise chain arithmetic (D199), a tilted parent handing the
     *  DESIGN chain + the tilt as the piece's own true-tier deltas
     *  (D224). Callers skip strict-seam FOLLOWERS (their entry is
     *  glued to the seam — the stored placement matters only when
     *  orphaned). */
    public void reanchorAtBoundary(TrackPiece p,
                                   TrackCompiler.Span boundarySpan) {
        // the OLD entry psi — read BEFORE the placement overwrites
        // below (v19.9, D238: the abs-heading rebase needs it)
        double oldPsi = entryPsiInternal(p);
        TrackPose boundary = boundarySpan.exit();
        p.x = boundary.pos().x();
        p.y = boundary.pos().y();
        p.z = boundary.pos().z();
        p.baseOrient = boundary.orient();
        p.canonOrient = null;
        p.canonPsiDeg = 0;
        p.yawDeg = 0;
        p.pitchDeg = 0;
        p.rollDeg = 0;
        p.designPitchDeg = 0;
        p.designRollDeg = 0;
        boolean pair = boundarySpan.exitCanon() != null;
        if (pair) {
            p.canonPsiDeg = boundarySpan.exitPsi();
            p.canonOrient = boundarySpan.exitCanon();
        }
        Quat tilt = boundarySpan.exitTilt();
        if (pair && tilt != null && tilt != Quat.IDENTITY) {
            Quat design = tilt.conjugate().mul(boundary.orient());
            double[] d = TrackPiece.decomposeNear(design, boundary.orient(),
                0, 0, 0);
            if (d != null) {
                p.baseOrient = design;
                p.yawDeg = d[0];
                p.pitchDeg = d[1];
                p.rollDeg = d[2];
            } else {
                p.canonOrient = null;   // re-solve from the world pose
            }
        }
        // ABSOLUTE heading lanes REBASE at the new entry (v19.9,
        // D238 — the splitPiece rule: the piece's heading zero moved
        // to the boundary; the yaw-delta term cancels between psi0
        // and the chain, so the shift is the plain psi delta)
        if (pair) {
            rebaseAbsHeading(p.sections, boundarySpan.exitPsi() - oldPsi);
        }
    }

    /** REVERSE PIECE = a CONVERT (v17.0, D134 — user re-spec,
     *  replacing both the D71 model transformation and the short-lived
     *  D133 design-direction scheme): the piece's compiled geometry is
     *  mirrored into the opposite travel order and re-fitted as
     *  ordinary FORWARD sections at ONE energy flow from the reversed
     *  entry speed — the speed the original ride REACHES its end with
     *  (Pass D true), or the caller's custom value. The output piece
     *  re-anchors at the old exit running backward, keeps one new
     *  section per original section at the same boundaries
     *  (force→force, geo→geo; straight/curve re-authors as FORCE
     *  lanes — an element list has no mirrored form; influence folds
     *  in like any whole convert; a LEGACY reversed-flag section just
     *  comes out as plain forward lanes), and carries NO speed
     *  conflict by construction — the first section anchors the flow
     *  and the D95 reconcile keeps the rest real. Pieces containing a
     *  BRIDGE refuse (world-anchored A→B has no reverse). Friction-
     *  aware end to end: the fit's target profile sheds the document
     *  losses over the mirrored arc, so the reversed track
     *  re-integrates onto the original shape to the fit residual with
     *  friction on. vEntry NaN = the recorded end speed. Undo/preview
     *  are the caller's (the editor runs this through the standard
     *  density preview). */
    public boolean reversePiece(int pieceIdx, double dt, double spacing,
                                double adaptTol, double vEntry) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (piece.sections.isEmpty()) return false;
        for (Section s : piece.sections) {
            if (s instanceof BridgeSection) return false;
            // SPEED CONTROLS refuse reversal (v18.0, D160): a lift/
            // brake acting on the mirrored flow is ill-defined —
            // remove the controls first (the editor PRE-CHECKS with
            // its own reason since v19.6, D235 — this false return
            // used to fall through misattributed as "too short")
            if (!s.speedControls().isEmpty()) return false;
        }
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int span0 = 0;
        for (int p = 0; p < pieceIdx; p++) {
            span0 += pieces.get(p).sections.size();
        }
        int nSec = piece.sections.size();
        if (span0 + nSec > r.spans().size()) return false;
        int i0 = r.spans().get(span0).i0();
        int i1 = Math.min(r.spans().get(span0 + nSec - 1).i1(),
            r.samples().size() - 1);
        // a piece BRIDGED INTO drops its first sample (continuation,
        // D84) — step back onto the true entry when position-continuous
        if (i0 > 0) {
            FVDIntegrator.Sample a = r.samples().get(i0 - 1);
            FVDIntegrator.Sample b = r.samples().get(i0);
            if (b.pos().sub(a.pos()).length()
                    < 3 * Math.max(0.5, b.v()) * dt) {
                i0--;
            }
        }
        if (i1 - i0 < 8) return false;
        double vE = Double.isNaN(vEntry)
            ? (i1 < r.trueV().length ? r.trueV()[i1]
                : piece.sections.get(nSec - 1).vDesign())
            : vEntry;
        vE = Math.max(1, Math.min(100, vE));
        // mirrored samples (world positions kept): rev[q] = orig[i1−q]
        List<FVDIntegrator.Sample> rev =
            reversedSamples(r.samples(), i0, i1);
        // ONE energy flow over the mirrored arc — each new section's
        // design speed reads off it at that section's entry, so the
        // whole reversed piece is conflict-free from the start
        double[] prof = reversedProfile(rev, vE, fric());
        List<Section> out = new ArrayList<>(nSec);
        for (int j = 0; j < nSec; j++) {
            int k = nSec - 1 - j;                  // original index
            TrackCompiler.Span sk = r.spans().get(span0 + k);
            // include the entry-boundary sample (the withEntry rule,
            // D113) — but only across a frame-SMOOTH seam: a LEGACY
            // reversed section's seam can be KINKED (D67 matched
            // heading only), and a cross-kink row poisons the fit's
            // edge extraction
            int a = sk.i0();
            if (a - 1 >= i0
                    && r.samples().get(a - 1).T()
                        .dot(r.samples().get(a).T())
                        > Math.cos(Math.toRadians(2))) {
                a--;
            }
            int b = Math.min(sk.i1(), i1);         // exit boundary
            int qa = i1 - b, qb = i1 - a;          // rev-index range
            if (qb - qa < 4) return false;         // too short to fit
            double vJ = Math.max(1, Math.min(100, prof[qa]));
            boolean toGeo = piece.sections.get(k) instanceof GeoSection;
            out.add(fitLanes(rev.subList(qa, qb + 1), vJ, toGeo, dt,
                spacing, adaptTol));
        }
        // re-anchor at the old exit running backward (the D71
        // placement math); any piece tilt is already baked into the
        // mirrored samples — the deltas reset so it can't apply twice
        FVDIntegrator.Sample end = r.samples().get(i1);
        piece.x = end.pos().x();
        piece.y = end.pos().y();
        piece.z = end.pos().z();
        piece.yawDeg = 0;
        piece.pitchDeg = 0;
        piece.rollDeg = 0;
        // the DESIGN tier resets with the true tier (D222): the
        // reversal's refit consumed the compiled (design-generated +
        // conflict-tilted) samples — leftovers would apply twice
        piece.designPitchDeg = 0;
        piece.designRollDeg = 0;
        piece.baseOrient = Quat.fromFrame(end.T().scale(-1), end.U());
        piece.canonOrient = null;   // reversal = a new natural frame (D199)
        piece.followPrev = false;   // v17.1: the entry moved to the old
                                    // exit — a strict follow-link would
                                    // point at the wrong end now
        piece.sections.clear();
        piece.sections.addAll(out);
        return true;
    }

    /** MIRROR PIECE (v19.8, D237 — user spec): flip the piece
     *  HORIZONTALLY about the vertical plane through its entry
     *  heading — same start position, heading and elevation; the
     *  starting bank mirrors. A pure ONE-TO-ONE GRAPH NEGATION, no
     *  re-fit: force Gl, the roll lane (rate or absolute bank), geo
     *  yaw, influence delta lane 1, s/c arc angle+direction+roll and
     *  the piece's roll deltas / held seam bank all negate; every
     *  integrator arithmetic path is sign-symmetric, so the compile
     *  produces BITWISE the z-negated track in the canonical entry
     *  frame (MirrorGate). Pieces ending with a bridge refuse — the
     *  bridge re-solves against BOTH pieces every compile. */
    public boolean mirrorPiece(int pieceIdx) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (piece.sections.isEmpty()) return false;
        for (Section s : piece.sections) {
            if (s instanceof BridgeSection) return false;
        }
        for (Section s : piece.sections) {
            if (s instanceof LaneSection ls) {
                negateLane(ls.lanes.get(1));
                negateLane(ls.lanes.get(2));
                if (ls.deltaLanes != null) {
                    negateLane(ls.deltaLanes.get(1));
                }
            } else if (s instanceof StraightCurveSection sc) {
                for (StraightCurveSection.Element e : sc.elements) {
                    // κ' components: cU/cT negate, cR kept ⇒ negate
                    // BOTH the signed angle and the plane direction
                    e.angleDeg = -e.angleDeg;
                    e.dirDeg = -e.dirDeg;
                    e.rollVal = -e.rollVal;   // rate AND global bank
                }
            }
        }
        // orientation: conjugate the heading-stripped entry by the
        // z-reflection — heading + elevation kept, bank negated. The
        // canonical pair stays exact (heading 0 is mirror-invariant);
        // without a stored pair the same math runs through the
        // decomposed heading.
        if (piece.canonOrient != null) {
            piece.canonOrient = piece.canonOrient.mirrorZ();
            piece.baseOrient = Quat.aboutY(piece.canonPsiDeg)
                .mul(piece.canonOrient);
        } else {
            double psi = TrackCompiler.headingDeg(piece.baseOrient);
            piece.baseOrient = Quat.aboutY(psi).mul(
                Quat.aboutY(-psi).mul(piece.baseOrient).mirrorZ());
        }
        piece.rollDeg = -piece.rollDeg;
        piece.designRollDeg = -piece.designRollDeg;
        piece.sRollDeg = -piece.sRollDeg;   // held seam bank mirrors
        return true;
    }

    /** Negate a lane in place: node values AND both handle value
     *  offsets (slopes mirror with the curve; fracs/locks keep). */
    private static void negateLane(List<BezierBaker.Node> lane) {
        for (BezierBaker.Node n : lane) {
            n.val = -n.val;
            n.outDv = -n.outDv;
            n.inDv = -n.inDv;
        }
    }

    // ---------- COPY (v19.18, D247 — the Track Piece panel's Copy) ----------

    /** The Copy button's fixed world offset (m along +Z): the copy
     *  sits beside the original, never on top of it. World +Z is
     *  rider-LEFT of a +X heading — a literal world offset, not a
     *  side-relative one (predictable and bitwise-rigid per D199). */
    public static final double COPY_DZ = 5;

    /** Deep copy of a 3-lane node-list set (null-safe) — the undo
     *  snapshot's copyLanes, model-side so the copy op is gate-
     *  testable without the shell. */
    private static List<List<BezierBaker.Node>> copyLanes(
            List<List<BezierBaker.Node>> lanes) {
        if (lanes == null) return null;
        List<List<BezierBaker.Node>> copy = new ArrayList<>(3);
        for (List<BezierBaker.Node> lane : lanes) {
            List<BezierBaker.Node> laneCopy = new ArrayList<>(lane.size());
            for (BezierBaker.Node n : lane) laneCopy.add(n.copy());
            copy.add(laneCopy);
        }
        return copy;
    }

    /** A DEEP COPY of one section (D247) — coverage == the undo
     *  snapshot's SecState/restorePieces: lanes, delta lanes, every
     *  domain/axis/unit/behavior flag, the influence pair (vAuthored
     *  copies unconditionally — inert without delta lanes, exact
     *  with them), speed controls + marks, s/c elements, bridges. All
     *  lists are NEW objects (the editor holds list references).
     *  SESSION flags start fresh on the copy (viewOtherDomain /
     *  viewOtherRate / editInfluence = false — a copy is a NEW
     *  section, not a restore of the same one; the view flags auto-
     *  drop on leaving a section anyway) and baseCurves stays null
     *  (the first compile recomputes it). A BRIDGE copies through
     *  BridgeSection.copy() and then (a) optSig := null — FIRST SIGHT
     *  is record-only in autoReoptimizeBridges, and the signature
     *  carries the WORLD entry position, so a kept array would read
     *  as changed after the dz translation and re-place the copied
     *  target on the first rebuild; lastOptDist rides along so the
     *  march spine still renders; (b) noneStash := null (a shared
     *  transient); (c) the stored solve poses translate by dz (they
     *  re-capture every compile — a translated cache just keeps an
     *  accidental orphan honest). ONE place for these rules so a
     *  future whole-coaster copy inherits them. */
    public static Section copySection(Section s, double dz) {
        if (s instanceof BridgeSection bs) {
            BridgeSection b = bs.copy();
            b.optSig = null;
            b.noneStash = null;
            Vec3 d = new Vec3(0, 0, dz);
            if (b.solvedEntry != null) {
                b.solvedEntry = new TrackPose(b.solvedEntry.pos().add(d),
                    b.solvedEntry.orient());
            }
            if (b.solvedTarget != null) {
                b.solvedTarget = new TrackPose(b.solvedTarget.pos().add(d),
                    b.solvedTarget.orient());
            }
            return b;
        }
        if (s instanceof StraightCurveSection sc) {
            List<StraightCurveSection.Element> els =
                new ArrayList<>(sc.elements.size());
            for (StraightCurveSection.Element e : sc.elements) els.add(e.copy());
            StraightCurveSection out = new StraightCurveSection(els, sc.vDesign);
            out.reversed = sc.reversed;
            out.vPinned = sc.vPinned;
            out.viewOtherDomain = false;
            out.speedCtls.addAll(SpeedControl.copyList(sc.speedCtls));
            out.speedMarks.addAll(sc.speedMarks);
            return out;
        }
        LaneSection ls = (LaneSection) s;
        LaneSection out = ls instanceof GeoSection
            ? new GeoSection(copyLanes(ls.lanes), ls.vDesign)
            : new ForceSection(copyLanes(ls.lanes), ls.vDesign);
        out.reversed = ls.reversed;
        out.vPinned = ls.vPinned;
        out.domain = ls.domain;
        out.viewOtherDomain = false;
        out.viewOtherRate = false;
        out.rollGlobal = ls.rollGlobal;
        out.yawPitchGlobal = ls.yawPitchGlobal;
        out.ratePerMeter = ls.ratePerMeter;
        out.rollAbsolute = ls.rollAbsolute;
        out.ypAbsolute = ls.ypAbsolute;
        out.inflRollGlobal = ls.inflRollGlobal;
        out.inflYawPitchGlobal = ls.inflYawPitchGlobal;
        out.deltaLanes = copyLanes(ls.deltaLanes);
        out.vAuthored = ls.vAuthored;
        out.editInfluence = false;
        out.baseCurves = null;
        System.arraycopy(ls.endFollows, 0, out.endFollows, 0, 3);   // D248
        out.speedCtls.addAll(SpeedControl.copyList(ls.speedCtls));
        out.speedMarks.addAll(ls.speedMarks);
        return out;
    }

    /** COPY A RUN (v19.18, D247 — the Track Piece panel's "Copy",
     *  user spec "copy of the track piece": a RUN = pieces chained by
     *  trailing bridges or strict follow links is what the user calls
     *  the track piece — a lone follower's stored placement is its
     *  frozen pre-attach pose and a lone bridge-ending piece would
     *  leave an orphan bridge still aimed at the ORIGINAL target, so
     *  the connected whole is the only copy that lands 5 m away
     *  bitwise). Appends consecutive deep copies (TrackPiece.copy)
     *  at the END of the piece list — inserting mid-list would
     *  re-index every later piece and could hijack a following
     *  piece's followPrev — so every internal link copies verbatim:
     *  copied followers follow copied predecessors, copied bridges
     *  target the copied next piece. The head's followPrev is
     *  normalized to false (a run head never actively follows; an
     *  inert orphaned-follower flag would glue the copy onto the
     *  current last piece). REFUSES (−1) when the LAST piece of the
     *  LIST ends in an ORPHAN bridge — a bridge always targets the
     *  next list entry, so appending after it would hand that bridge
     *  the copy's head as its target and move the ORIGINAL track.
     *  The test is on the list end, not the copied run's end (D247
     *  review fix): copying ANY run appends at the list end, and a
     *  run whose last piece ends in a live bridge extends through its
     *  target, so the run-end case is a subset of this one. No
     *  compile, no reconcile writes on the first rebuild: interior
     *  spans are bitwise the source's (canonical generation), copied
     *  bridges are first-sight record-only. Returns the copied head's
     *  index. */
    public int copyRun(int runStart, int runEnd, double dz) {
        if (runStart < 0 || runStart > runEnd || runEnd >= pieces.size()) {
            return -1;
        }
        if (listEndsInOrphanBridge()) {
            return -1;   // orphan bridge at the list end — see above
        }
        int head = pieces.size();
        for (int p = runStart; p <= runEnd; p++) {
            TrackPiece cp = pieces.get(p).copy(dz);
            if (p == runStart) cp.followPrev = false;
            pieces.add(cp);
        }
        return head;
    }

    /** True when the piece list's LAST piece ends in a bridge — an
     *  orphan by construction (nothing follows it in the list), which
     *  would seize any piece appended after it as its target. The
     *  copyRun refusal + the UI's gray reason share this test (D247). */
    public boolean listEndsInOrphanBridge() {
        if (pieces.isEmpty()) return false;
        List<Section> tail = pieces.get(pieces.size() - 1).sections;
        return !tail.isEmpty()
            && tail.get(tail.size() - 1) instanceof BridgeSection;
    }

    // ---- RUN TOPOLOGY (v19.19, D248): the ONE definition of a run
    // — pieces chained by trailing bridges OR strict-seam follows —
    // that the editor (GraphEditor.runStart/runEnd/followsPrev/
    // endsWithBridge delegate here), the naming helpers
    // (SectionNames) and the headless gates share. Out-of-range
    // indices are tolerated (false / p itself). ----

    /** Piece p's last section is a bridge. */
    public boolean endsWithBridge(int p) {
        if (p < 0 || p >= pieces.size()) return false;
        List<Section> s = pieces.get(p).sections;
        return !s.isEmpty() && s.get(s.size() - 1) instanceof BridgeSection;
    }

    /** Piece p is a STRICT-SEAM follower of p−1 (v17.1, D135) — the
     *  rule the compiler applies: the flag is ignored when bridged-in
     *  or when either side is empty. */
    public boolean followsPrev(int p) {
        return p > 0 && p < pieces.size() && pieces.get(p).followPrev
            && !endsWithBridge(p - 1)
            && !pieces.get(p - 1).sections.isEmpty()
            && !pieces.get(p).sections.isEmpty();
    }

    /** Piece p is BRIDGED INTO — the previous piece ends in a bridge,
     *  so the boundary entering p is a bridge exit (the junction's
     *  "(bridge)" form). Checked BEFORE followsPrev by every naming
     *  site: a bridged-in piece's stale followPrev flag is inert. */
    public boolean bridgedInto(int p) {
        return p > 0 && p < pieces.size() && endsWithBridge(p - 1);
    }

    /** First piece of the connected RUN holding piece p; p itself
     *  when out of range. */
    public int runStartOf(int p) {
        if (p < 0 || p >= pieces.size()) return p;
        while (p > 0 && (endsWithBridge(p - 1) || followsPrev(p))) p--;
        return p;
    }

    /** DELETE A RUN (v19.20, D249 — the Track Piece window's
     *  "Delete…"): removes pieces [head..runEndOf(head)] — the whole
     *  connected run, trailing bridge and bridged-in targets included
     *  (a lone piece is a run of one). `head` must be a run HEAD
     *  (runStartOf(head) == head) — the UI resolves the active
     *  piece's run first. The coaster's ONLY run may go: an empty
     *  coaster is legal (D239, the "Add Floating Piece" placeholder).
     *  The successor's followPrev is normalized inert when it now
     *  heads the list or sits behind a bridge-ending piece (the D248
     *  deleteSection rule) — by the run topology it is already false
     *  (a follower of the run's tail would be IN the run) and the
     *  piece before a run head never ends in a bridge (endsWithBridge
     *  would have absorbed the head into that run, and an orphan
     *  bridge is necessarily the LIST's last piece), so nothing
     *  re-targets; the belt stays cheap. v0 is the CALLER's (the
     *  ride entry's design speed when run 0 goes — the Switch-Coaster
     *  precedent). Returns the index to ACTIVATE: the run after (now
     *  at `head`) when one exists, else the previous run's head;
     *  −1 when the coaster is empty now; −2 refused (bad index / not
     *  a run head — nothing touched). */
    public int deleteRun(int head) {
        if (head < 0 || head >= pieces.size()
                || runStartOf(head) != head) {
            return -2;
        }
        int re = runEndOf(head);
        pieces.subList(head, re + 1).clear();
        if (pieces.isEmpty()) return -1;
        if (head < pieces.size()) {
            TrackPiece succ = pieces.get(head);
            if (succ.followPrev
                    && (head == 0 || endsWithBridge(head - 1))) {
                succ.followPrev = false;
            }
            return head;
        }
        return runStartOf(pieces.size() - 1);
    }

    /** Last piece of the connected RUN holding piece p; p itself
     *  when out of range. */
    public int runEndOf(int p) {
        if (p < 0 || p >= pieces.size()) return p;
        while (p < pieces.size() - 1
                && (endsWithBridge(p) || followsPrev(p + 1))) p++;
        return p;
    }

    /** The run HEADS in list order — one entry per run (v19.19, D248
     *  — RUN AS PIECE: the toolbar selector, the '+' attach/bridge
     *  lists and the export picker enumerate these). Empty list on
     *  an empty coaster. Allocates: menu builders and the selector
     *  refresh only — never per redraw. */
    public List<Integer> runHeads() {
        List<Integer> heads = new ArrayList<>();
        for (int p = 0; p < pieces.size(); p = runEndOf(p) + 1) heads.add(p);
        return heads;
    }

    /** Number of runs (the "n" of "Piece k/n"). */
    public int runCount() {
        int n = 0;
        for (int p = 0; p < pieces.size(); p = runEndOf(p) + 1) n++;
        return n;
    }

    /** 1-based ordinal of the run holding piece p (the "k" of
     *  "Piece k/n" — keyed off runStartOf(p), so a member piece reads
     *  its run's number); 0 out of range. */
    public int runOrdinal(int p) {
        if (p < 0 || p >= pieces.size()) return 0;
        int head = runStartOf(p), n = 0;
        for (int q = 0; q <= head; q = runEndOf(q) + 1) n++;
        return n;
    }

    /** DRY RUN of moveRun (D248): true when the run headed by `head`
     *  may be moved to come right after piece `afterIdx`. Refuses a
     *  non-head, an afterIdx inside the block, a destination piece
     *  that ENDS IN A BRIDGE (the block's head would be seized as its
     *  target — TrackCompiler targets pieces.get(p + 1)), and a block
     *  that itself ends in an ORPHAN bridge unless it stays where it
     *  is. An orphan-bridge run is NECESSARILY the list's last run (a
     *  bridge-ending tail with a piece after it would have absorbed
     *  that piece as its target), so the only landing that keeps the
     *  bridge orphan is "already last" — afterIdx + 1 == head, the
     *  '+' lists' "(next)" no-move case; anywhere else the piece
     *  landing after it becomes that bridge's target — the moved run
     *  swallows the following run, and an optimize-mode bridge with a
     *  recorded signature would MOVE the swallowed run's head on the
     *  next rebuild. Also refuses a destination that is not the END
     *  of its own run (D248 review fix): the block lands at afterIdx +
     *  1, and with a strict follower there moveRun (which touches no
     *  follower flag) would splice the block into the middle of that
     *  run — the follower re-chained onto the block's tail. The UI's
     *  callers always pass the active run's tail, so this is the
     *  model-side guard; the no-move case afterIdx + 1 == head still
     *  passes (a run head never follows afterIdx). The UI grays the
     *  entries with these reasons; the model guards anyway (the
     *  copyRun precedent). */
    public boolean canMoveRun(int head, int afterIdx) {
        int n = pieces.size();
        if (head < 0 || head >= n || afterIdx < 0 || afterIdx >= n) return false;
        if (runStartOf(head) != head) return false;
        int re = runEndOf(head);
        if (afterIdx >= head && afterIdx <= re) return false;
        if (endsWithBridge(afterIdx)) return false;
        if (runEndOf(afterIdx) != afterIdx) return false;   // mid-run landing
        if (endsWithBridge(re) && afterIdx + 1 != head) return false;
        return true;
    }

    /** MOVE A WHOLE RUN (v19.19, D248 — RUN AS PIECE): the contiguous
     *  block head..runEndOf(head) reorders, in order, to come right
     *  after piece `afterIdx` — the piece list IS the ride timeline
     *  (D73), so bridging to / attaching a run means "this whole run
     *  follows". Internal links are index-relative and the block
     *  keeps its order, so every follower still follows its
     *  predecessor inside the block and every internal bridge still
     *  targets the next block element (signatures carry no index and
     *  no heading — nothing re-places). No TrackPiece field is
     *  touched EXCEPT the head's followPrev, normalized to false (the
     *  copyRun rule: an inert orphaned-follower flag at index 0 or
     *  behind a deleted bridge must not turn into a live follow at the
     *  new slot). For a run of one this is movePiece with
     *  connectStrict's index arithmetic, bitwise (RunGate f). Returns
     *  the moved head's new index, or −1 (list untouched) when
     *  canMoveRun refuses. */
    public int moveRun(int head, int afterIdx) {
        if (!canMoveRun(head, afterIdx)) return -1;
        int re = runEndOf(head);
        List<TrackPiece> block = new ArrayList<>(pieces.subList(head, re + 1));
        pieces.subList(head, re + 1).clear();
        int t = afterIdx > re ? afterIdx - block.size() : afterIdx;
        pieces.addAll(t + 1, block);
        block.get(0).followPrev = false;
        return t + 1;
    }

    /** connectStrict for a WHOLE RUN (D248): moveRun the run headed
     *  by `head` to follow piece `targetIdx`, then the exact
     *  connectStrict tail on the moved head (followPrev + the three
     *  locks + seedStrictValues from its pre-attach entry pose — the
     *  D247 conflict-preserving attach). Returns the head's new index
     *  (the follower), or −1 with the list untouched when canMoveRun
     *  refuses. */
    public int connectStrictRun(int targetIdx, int head,
                                boolean lockYaw, boolean lockPitch,
                                boolean lockRoll) {
        int to = moveRun(head, targetIdx);
        if (to < 0) return -1;
        TrackPiece f = pieces.get(to);
        f.followPrev = true;
        f.sLockYaw = lockYaw;
        f.sLockPitch = lockPitch;
        f.sLockRoll = lockRoll;
        seedStrictValues(f, f.entryPose().orient());
        return to;
    }

    /** Flattened index of piece p's FIRST section (its first span). */
    private static int spanBase(Coaster c, int p) {
        int b = 0;
        for (int q = 0; q < p; q++) b += c.pieces.get(q).sections.size();
        return b;
    }

    /** The compiled ARC LENGTH of the run headed by `head` — every
     *  section of every member piece incl. bridges (the selector /
     *  '+' / export labels, D248); NaN without spans or before the
     *  compile covers the run. */
    public static double runLengthM(Coaster c, List<TrackCompiler.Span> spans,
                                    int head) {
        if (c == null || spans == null || head < 0 || head >= c.pieces.size()) {
            return Double.NaN;
        }
        int re = c.runEndOf(head);
        int b = spanBase(c, head), e = spanBase(c, re + 1);
        if (e > spans.size()) return Double.NaN;
        double len = 0;
        for (int k = b; k < e; k++) len += spans.get(k).sLen();
        return len;
    }

    /** The run headed by `head` as a compiled SAMPLE RANGE {i0, i1}
     *  inclusive (the export picker, D248): the head's first span to
     *  the tail's last span — every member's samples incl. bridges
     *  and the seam samples INSIDE the run. A run head is never a
     *  continuation (TrackCompiler: continuation = bridgedIn ||
     *  followed), so its first sample is its own entry; the D223
     *  per-piece export's "dropped seam sample" prepend (D84) is
     *  therefore dead here — under flat sample indexing that test was
     *  always true and handed a DETACHED piece one foreign sample
     *  from the previous piece, closed with the run picker. null when
     *  the spans don't cover the run or the range is degenerate. */
    public static int[] runSampleRange(Coaster c, List<TrackCompiler.Span> spans,
                                       int head) {
        if (c == null || spans == null || head < 0 || head >= c.pieces.size()) {
            return null;
        }
        int re = c.runEndOf(head);
        int b = spanBase(c, head), e = spanBase(c, re + 1);
        if (e <= b || e > spans.size()) return null;
        int i0 = spans.get(b).i0();
        int i1 = spans.get(e - 1).i1();
        return i1 > i0 ? new int[]{ i0, i1 } : null;
    }

    /** {x, y, z, reportedYaw, trueV} 5 m past the END of the RUN
     *  holding piece p along its exit heading (level) — the floating
     *  wizard's default placement (D248: the run TAIL's end, not the
     *  active member's — "5 m past the track piece's end"); null
     *  without a compile covering the run. trueV may be null (design
     *  speed then). */
    public static double[] runTailEnd(Coaster c, List<FVDIntegrator.Sample> samples,
                                      List<TrackCompiler.Span> spans,
                                      double[] trueV, int p) {
        if (c == null || samples == null || spans == null) return null;
        if (p < 0 || p >= c.pieces.size()) return null;
        int re = c.runEndOf(p);
        int e = spanBase(c, re + 1);
        if (e == 0 || e > spans.size()) return null;
        int i1 = spans.get(e - 1).i1();
        if (i1 < 0 || i1 >= samples.size()) return null;
        FVDIntegrator.Sample sm = samples.get(i1);
        Vec3 t = sm.T();
        double hl = Math.hypot(t.x(), t.z());
        double hx = hl > 1e-9 ? t.x() / hl : 1, hz = hl > 1e-9 ? t.z() / hl : 0;
        double heading = Math.toDegrees(Math.atan2(-hz, hx));   // internal
        double v = trueV != null && i1 < trueV.length ? trueV[i1] : sm.v();
        return new double[]{ sm.pos().x() + 5 * hx, sm.pos().y(),
            sm.pos().z() + 5 * hz, -heading, v };
    }

    /** A sample range mirrored into the opposite travel direction,
     *  world positions kept: T and R flip, U stays, felt Gl negates
     *  (the D67 identities), t/s re-base from the old end. The
     *  recorded per-sample v (the DESIGN profile) is kept — the fit
     *  decodes curvature from the felt at that speed, which is
     *  direction-blind. */
    static List<FVDIntegrator.Sample> reversedSamples(
            List<FVDIntegrator.Sample> all, int i0, int i1) {
        List<FVDIntegrator.Sample> out = new ArrayList<>(i1 - i0 + 1);
        FVDIntegrator.Sample endS = all.get(i1);
        for (int m = i1; m >= i0; m--) {
            FVDIntegrator.Sample sm = all.get(m);
            Vec3 T = sm.T().scale(-1);
            Vec3 U = sm.U();
            out.add(new FVDIntegrator.Sample(endS.t() - sm.t(),
                endS.s() - sm.s(), sm.pos(), T, U, U.cross(T),
                sm.v(), sm.Gv(), -sm.Gl()));
        }
        return out;
    }

    /** Speed profile over a mirrored sample list from one entry
     *  speed — the passD march (Heun on the friction deficit).
     *  Shared by the reversal's boundary speeds and the editor's
     *  preview guides. */
    static double[] reversedProfile(List<FVDIntegrator.Sample> rev,
                                    double vEntry,
                                    FVDIntegrator.Friction fric) {
        int n = rev.size();
        double[] v = new double[n];
        double y0 = rev.get(0).pos().y();
        double loss = 0;
        v[0] = Math.max(0.5, vEntry);
        for (int q = 1; q < n; q++) {
            double ds = rev.get(q).s() - rev.get(q - 1).s();
            if (!fric.off() && ds > 0) {
                double f0 = fric.dLossDs(v[q - 1]);
                double vp = Math.max(0.5, FVDIntegrator.speedAt(
                    rev.get(q).pos(), vEntry, y0, loss + f0 * ds));
                loss += (f0 + fric.dLossDs(vp)) / 2 * ds;
            }
            v[q] = Math.max(0.5, FVDIntegrator.speedAt(
                rev.get(q).pos(), vEntry, y0, loss));
        }
        return v;
    }

    /** STRICT-SEAM effective entry (v17.1, D135): where a FOLLOWING
     *  piece's entry really sits this compile — position = the seam
     *  (the previous piece's exit), orientation composed per the
     *  locks: unlocked axes read the INCOMING frame, locked axes the
     *  piece's own STORED placement (the frozen original — never
     *  mutated by following, so relocking restores it). All-locked
     *  and all-unlocked take the exact frames (no Euler round-trip);
     *  a vertical heading on either side has no defined heading/bank
     *  — that side's frame is taken whole (the global-axis hold-off
     *  rule). */
    public static TrackPose strictEntry(TrackPose prevExit, TrackPiece p) {
        Quat eo;
        if (!p.sLockYaw && !p.sLockPitch && !p.sLockRoll) {
            eo = prevExit.orient();            // fully continuous — exact
        } else {
            // locked (DISCONTINUOUS) axes hold the piece's editable
            // sYaw/sPitch/sRollDeg values (D136 — created from the
            // stored placement, spinner-edited since); unlocked axes
            // read the incoming frame. A vertical incoming heading
            // has no defined heading/bank — hold the frozen triple
            // whole (the global-axis rule).
            double[] in = eulerOf(prevExit.orient());
            eo = Double.isNaN(in[0])
                ? eulerFrame(p.sYawDeg, p.sPitchDeg, p.sRollDeg)
                : eulerFrame(
                    p.sLockYaw ? p.sYawDeg : in[0],
                    p.sLockPitch ? p.sPitchDeg : in[1],
                    p.sLockRoll ? p.sRollDeg : in[2]);
        }
        return new TrackPose(prevExit.pos(), eo);
    }

    /** Seed a follower's DISCONTINUOUS values from an orientation
     *  (creation + the settings' ↺). Vertical frames seed what they
     *  can (heading/bank 0). */
    static void seedStrictValues(TrackPiece p, Quat q) {
        double[] e = eulerOf(q);
        p.sYawDeg = Double.isNaN(e[0]) ? 0 : e[0];
        p.sPitchDeg = e[1];
        p.sRollDeg = Double.isNaN(e[2]) ? 0 : e[2];
    }

    /** {heading°, elevation°, bank°} of a frame — the TrackPiece
     *  delta axes (heading about world Y, elevation = asin T.y, bank
     *  about T with the roll-lane sign). heading/bank NaN near a
     *  vertical T. PUBLIC since D136 (the seam settings + gates read
     *  it). */
    public static double[] eulerOf(Quat q) {
        Vec3 T = q.T();
        double horiz = Math.sqrt(T.x() * T.x() + T.z() * T.z());
        if (horiz < 1e-6) {
            return new double[]{ Double.NaN,
                T.y() > 0 ? 90 : -90, Double.NaN };
        }
        return new double[]{
            Math.toDegrees(Math.atan2(-T.z(), T.x())),
            Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, T.y())))),
            Math.toDegrees(FVDIntegrator.bankOfTU(T, q.U())) };
    }

    /** Frame from {heading, elevation, bank} — composed exactly like
     *  TrackPiece's deltas: yaw about Y, pitch about the heading's
     *  horizontal right, roll about the resulting heading. */
    static Quat eulerFrame(double headingDeg, double elevDeg,
                           double bankDeg) {
        Quat q = Quat.aboutY(headingDeg);
        if (elevDeg != 0) {
            Vec3 t = q.T();
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                q = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(elevDeg)).mul(q);
            }
        }
        if (bankDeg != 0) {
            q = Quat.aboutAxis(q.T(), Math.toRadians(bankDeg)).mul(q);
        }
        return q;
    }

    // ---------- strict-seam TWO TIERS (v19.18, D247) ----------
    // A follower's DESIGN orientation is its designPose() (the
    // compiler's follower branch generates there since D247); its REAL
    // orientation is the follow contract (strictEntry). The gap is the
    // seam's conflict tilt — exactly the `tq` the compile applies.

    /** The seam's CONFLICT TILT for follower `followerIdx` given the
     *  previous piece's exit pose: real (the follow contract) on top of
     *  design — the same product TrackCompiler's follower branch tilts
     *  by. Pure-heading gaps live inside it too (see
     *  isSeamOrientConflict). */
    public Quat seamConflictTilt(int followerIdx, TrackPose prevExit) {
        TrackPiece f = pieces.get(followerIdx);
        return strictEntry(prevExit, f).orient()
            .mul(f.designPose().orient().conjugate());
    }

    /** Does a seam tilt count as an ORIENTATION conflict? Near-identity
     *  (|w| > 1 − 1e-14 — the compiler's own IDENTITY short-circuit) is
     *  none, and neither is a PURE WORLD-Y rotation (a heading-only
     *  re-aim commutes with gravity exactly — rigid placement, the
     *  D199-accepted follower re-aim): only a tilt with a horizontal
     *  axis component (hypot(x, z) > 1e-9) reshapes the felt ride. */
    public static boolean isSeamOrientConflict(Quat tq) {
        if (Math.abs(tq.w()) > 1 - 1e-14) return false;
        return Math.hypot(tq.x(), tq.z()) > 1e-9;
    }

    /** The LOCKSTEP-mode lockstep for a follower (D247 — the model
     *  half of GraphEditor.seamEdit; mode-keyed since D249):
     *  `before`/`after` are the follower's REAL {heading, elevation,
     *  bank} around a seam write (followerEuler); under LOCKSTEP the
     *  DESIGN tier moves by the same Euler delta (applyDesignEulerDelta
     *  — elevation → designPitchDeg, bank → designRollDeg wrapped
     *  ±180, then kept inside ±360 like editRoll) so the design/real
     *  difference stays CONSTANT and the shape re-solves — the D222
     *  invariance on the seam. REAL_ONLY = the real angles move
     *  alone, the conflict absorbs the edit (today's locked behavior,
     *  bitwise); DESIGN_ONLY takes seamEdit's own design branch (the
     *  same delta applied to the design tier with the held values
     *  restored) and returns true here. Heading never touches the
     *  design tier (it is rigid); a NaN angle on either side (vertical
     *  frame) leaves the tier alone. POLE GUARD (D247 review fix):
     *  the triples are CANONICAL (elevation within ±90, heading/bank
     *  flipping by 180 past straight up/down — the D224 NL2 flip)
     *  while a held pitch runs ±360, so a write carrying the real
     *  frame OVER the pole reads as "elevation unchanged, bank +180"
     *  and would roll the design tier 180° about T for a 10° pitch
     *  turn. Either side within 0.1° of vertical, or the flip
     *  signature itself (heading AND bank both jumping by more than
     *  90° — a typed yaw alone moves only the heading), leaves the
     *  tier alone and returns FALSE so the caller can post the
     *  reason; true = applied or nothing to do. */
    public static boolean seamDesignLockstep(TrackPiece f, double[] before,
                                             double[] after) {
        if (f.orientMode != TrackPiece.OrientMode.LOCKSTEP) return true;
        return applyDesignEulerDelta(f, before, after);
    }

    /** The shared design-tier Euler-delta write (D249 — factored out
     *  of seamDesignLockstep so the DESIGN_ONLY seam branch applies
     *  the identical math): design elevation += after[1] − before[1],
     *  design bank += wrap180(after[2] − before[2]); null / NaN sides
     *  = nothing to do (true); the pole guard above returns false with
     *  the tier untouched. */
    public static boolean applyDesignEulerDelta(TrackPiece f, double[] before,
                                                double[] after) {
        if (before == null || after == null) return true;
        if (Double.isNaN(before[1]) || Double.isNaN(before[2])
                || Double.isNaN(after[1]) || Double.isNaN(after[2])) {
            return true;
        }
        boolean flipped = !Double.isNaN(before[0]) && !Double.isNaN(after[0])
            && Math.abs(TrackPiece.wrap180(after[0] - before[0])) > 90
            && Math.abs(TrackPiece.wrap180(after[2] - before[2])) > 90;
        if (Math.abs(before[1]) > 89.9 || Math.abs(after[1]) > 89.9
                || flipped) {
            return false;   // crossed the pole — misattributed otherwise
        }
        f.designPitchDeg += after[1] - before[1];
        f.designRollDeg = TrackPiece.wrap360(
            f.designRollDeg + TrackPiece.wrap180(after[2] - before[2]));
        return true;
    }

    /** DESIGN-tier absolute write for a follower (v19.20, D249 — the
     *  seam window's pitch/roll boxes and the seam rig's rings under
     *  DESIGN_ONLY, which show the design elevation/bank): axis 1 =
     *  design elevation := deg, axis 2 = design bank := deg (shortest
     *  way, wrap180 like the lockstep), through the design frame's
     *  Euler triple (eulerOf(designPose())). The held angles, locks
     *  and the real entry are untouched — the conflict changes by the
     *  design delta. Refuses (false, nothing written) when the design
     *  frame is vertical (no Euler triple) or the axis is yaw (heading
     *  is shared, never a design quantity); callers post the reason
     *  and rebuild. */
    public static boolean seamDesignSet(TrackPiece f, int axis, double deg) {
        if (axis != 1 && axis != 2) return false;
        double[] g = eulerOf(f.designPose().orient());
        if (Double.isNaN(g[1]) || Double.isNaN(g[2])
                || Math.abs(g[1]) > 89.9) {
            return false;
        }
        if (axis == 1) {
            f.designPitchDeg += deg - g[1];
        } else {
            f.designRollDeg = TrackPiece.wrap360(
                f.designRollDeg + TrackPiece.wrap180(deg - g[2]));
        }
        return true;
    }

    /** Seam REAL → DESIGN (D247, the seam window's "Real → Design
     *  Orientation"): hold the follower's pitch and roll AT ITS DESIGN
     *  elevation/bank (both padlocks lock; yaw untouched — heading is
     *  placement, never a conflict). Afterwards the seam tilt is
     *  heading-only ⇒ no ≠o, and the track is a RIGID image of what
     *  it was (generation untouched; SeamShapeGate 3). Refuses (false)
     *  when the design frame is vertical (no Euler triple — the caller
     *  posts the reason) or the piece is not an active follower. */
    public boolean seamRealToDesign(int followerIdx) {
        if (!followerActive(followerIdx)) return false;
        TrackPiece f = pieces.get(followerIdx);
        double[] g = eulerOf(f.designPose().orient());
        if (Double.isNaN(g[0]) || Double.isNaN(g[2])) return false;
        f.sPitchDeg = g[1];
        f.sRollDeg = g[2];
        f.sLockPitch = true;
        f.sLockRoll = true;
        return true;
    }

    /** Does a USER placement edit on `p` carry its connected
     *  downstream rigidly (v19.19, D248 — the ONE predicate
     *  FvdDesigner.placementEdit and PlacementGate read; mode-keyed
     *  since D249)? Only a REAL_ONLY piece whose "Affect Subsequent
     *  Strict Seams" flag is ON: LOCKSTEP pieces reshape and their
     *  downstream updates naturally (D222), DESIGN_ONLY never moves
     *  the real entry (nothing to carry — the flag is inert there),
     *  and the flag OFF stops the edit at the piece's own exit
     *  junction (bridge or seam). */
    public static boolean carriesDownstream(TrackPiece p) {
        return p.orientMode == TrackPiece.OrientMode.REAL_ONLY
            && p.affectSubsequentSeams;
    }

    /** The carry HALF of a user placement edit (D248): the caller ran
     *  its write between capturing `before` and `after` (the edited
     *  piece's effective entry poses); this applies carryDownstream
     *  when carriesDownstream(pieces[pieceIdx]) says so and returns
     *  whether it did. Null poses (a follower without a compiled seam)
     *  carry nothing. */
    public boolean carryAfterEdit(int pieceIdx, TrackPose before,
                                  TrackPose after,
                                  List<TrackCompiler.Span> spans) {
        if (pieceIdx < 0 || pieceIdx >= pieces.size()) return false;
        if (!carriesDownstream(pieces.get(pieceIdx))) return false;
        if (before == null || after == null) return false;
        carryDownstream(pieceIdx, before, after, spans);
        return true;
    }

    /** RIGID DOWNSTREAM CARRY (v19.0, D221): a hand edit to an
     *  orientation-RIGID piece's placement moves the CONNECTED
     *  downstream chain (trailing bridges + strict followers,
     *  recursively) as the same rigid body — nothing anywhere
     *  reshapes, and every carried piece keeps its own design
     *  orientation while its TRUE orientation rides along: the whole
     *  assembly adopts the one design/real conflict. `before`/`after`
     *  are the edited piece's effective entry poses around the write
     *  (the rotation anchors at before.pos()). This writes only what
     *  no machinery re-derives on its own:
     *   - manual-bridge (OPT_NONE) targets: stored position + the
     *     rotated orientation as delta triples (decomposeEntry — yaw
     *     carries the heading conflict-free, pitch/roll ARE the
     *     adopted conflict);
     *   - optimize-mode targets: orientation only — the march
     *     re-lands the position on the rigid image itself (both seam
     *     states transform together and the blend is body-frame);
     *   - followers: LOCKED (held) Euler angles — position and
     *     unlocked axes follow the moved seam automatically.
     *  A carried LINKED piece that receives a pitch/roll delta flips
     *  to explicit RIGID (folding the delta into its generation would
     *  reshape it — the opposite of a rigid carry). `spans` is the
     *  PRE-EDIT compile's span list (follower seam poses); null skips
     *  the follower angle rewrites gracefully. Callers push the undo
     *  snapshot — the carry rides the same step. */
    public void carryDownstream(int pieceIdx, TrackPose before,
                                TrackPose after,
                                List<TrackCompiler.Span> spans) {
        Quat dq = after.orient().mul(before.orient().conjugate());
        boolean rot = Math.abs(dq.w()) < 1 - 1e-15;
        Vec3 dp = after.pos().sub(before.pos());
        boolean move = dp.x() != 0 || dp.y() != 0 || dp.z() != 0;
        if (!rot && !move) return;
        Vec3 a0 = before.pos();
        // span index of each piece's LAST section (follower seams)
        int spanBase = 0;
        for (int q = 0; q < pieceIdx && q < pieces.size(); q++) {
            spanBase += pieces.get(q).sections.size();
        }
        for (int p = pieceIdx + 1; p < pieces.size(); p++) {
            TrackPiece prev = pieces.get(p - 1);
            TrackPiece m = pieces.get(p);
            if (prev.sections.isEmpty()) break;
            Section last = prev.sections.get(prev.sections.size() - 1);
            boolean viaBridge = last instanceof BridgeSection;
            boolean viaFollow = !viaBridge && m.followPrev
                && !m.sections.isEmpty();
            if (!viaBridge && !viaFollow) break;
            if (viaFollow) {
                // position + unlocked axes follow the (rigidly moved)
                // upstream exit; a LOCKED axis holds an ABSOLUTE Euler
                // angle — rewrite it from the rotated old effective
                // orientation so the follower rides along instead of
                // pinning to the old world angle
                if (rot && (m.sLockYaw || m.sLockPitch || m.sLockRoll)
                        && spans != null) {
                    int exitIdx = spanBase + prev.sections.size() - 1;
                    if (exitIdx >= 0 && exitIdx < spans.size()) {
                        Quat oldEff = strictEntry(
                            spans.get(exitIdx).exit(), m).orient();
                        double[] e = eulerOf(dq.mul(oldEff));
                        if (m.sLockYaw && !Double.isNaN(e[0])) {
                            m.sYawDeg = e[0];
                        }
                        if (m.sLockPitch) m.sPitchDeg = e[1];
                        if (m.sLockRoll && !Double.isNaN(e[2])) {
                            m.sRollDeg = e[2];
                        }
                    }
                }
            } else {
                BridgeSection bs = (BridgeSection) last;
                if (bs.optMode == BridgeSection.OPT_NONE && (rot || move)) {
                    Vec3 np = a0.add(dq.rotate(
                        new Vec3(m.x, m.y, m.z).sub(a0))).add(dp);
                    m.x = np.x();
                    m.y = np.y();
                    m.z = np.z();
                }
                if (rot) {
                    Quat no = dq.mul(m.entryPose().orient());
                    // branch-continuous (D224): the triple nearest the
                    // piece's current deltas — a carried piece near
                    // vertical never flips its yaw/roll by 180
                    double[] d = TrackPiece.decomposeNear(m.baseOrient, no,
                        m.yawDeg, m.pitchDeg, m.rollDeg);
                    if (d != null) {
                        // TRUE-tier writes only (D222): the carried
                        // piece keeps its design orientation and
                        // adopts the conflict — whatever its own
                        // edit-routing flag says
                        m.yawDeg = TrackPiece.wrap360(d[0]);
                        m.pitchDeg = d[1];
                        m.rollDeg = TrackPiece.wrap360(d[2]);
                    }
                    // d == null (vertical heading): position-only
                    // carry for this piece — the delta model cannot
                    // represent the rotation there (accepted corner)
                }
            }
            spanBase += prev.sections.size();
        }
    }

    /** ATTACH a floating piece to the end of another (v12.0, D70): its
     *  sections append to the target's chain (re-chaining from the
     *  target's exit pose on the next compile — their LOCAL shapes are
     *  untouched, §0) and the emptied piece dissolves. The floating
     *  placement is deliberately discarded — attaching MEANS "continue
     *  from the target's end". Since v17.1 (D135) the UI's connect
     *  flows route through connectStrict instead; this remains the
     *  model-level merge (and unstrictSeam's engine). */
    public void attachPiece(int targetIdx, int floatingIdx) {
        if (targetIdx == floatingIdx) return;
        TrackPiece target = pieces.get(targetIdx);
        TrackPiece floating = pieces.get(floatingIdx);
        // ABSOLUTE heading lanes REBASE into the target's frame
        // (v19.9, D238 — piece-relative heading): the moved lanes'
        // zero was the floating piece's entry; shifting by the
        // psi delta keeps their WORLD heading targets — exactly how
        // elevation and bank carry through an attach (and the exact
        // inverse of splitPiece's rebase, so split→attach
        // round-trips; the un-strict and bridge-stitch flows ride
        // this too — the attached track's shape survives instead of
        // re-aiming at the target's entry heading).
        rebaseAbsHeading(floating.sections,
            entryPsiInternal(target) - entryPsiInternal(floating));
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

    /** MAKE SEAM STRICT (v17.1, D135 — the user's "the track after
     *  the seam should work as if the track before it were a
     *  bridge"): split the piece at the section boundary (the D71
     *  rigid split — the boundary pose becomes the new piece's
     *  STORED placement, i.e. the frozen original orientation) and
     *  mark the new piece as FOLLOWING. Nothing moves at creation
     *  (the effective entry equals the stored one); from then on
     *  upstream edits translate/re-aim the downstream rigidly —
     *  unlocked axes align, locked axes hold the original — and its
     *  design speeds stay independent (honest ≠v instead of a
     *  reshape). Returns the follower's piece index, −1 refused. */
    public int makeSeamStrict(int pieceIdx, int secIdx, double dt) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (secIdx <= 0 || secIdx >= piece.sections.size()) return -1;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx - 1;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        if (spanIdx >= r.spans().size()) return -1;
        int np = splitPiece(pieceIdx, secIdx, r.spans().get(spanIdx));
        if (np < 0) return -1;
        TrackPiece f = pieces.get(np);
        f.followPrev = true;
        f.sLockYaw = true;       // ALL THREE locked at creation (v19.6,
        f.sLockPitch = true;     // D235 — user spec; the seeds are the
        f.sLockRoll = true;      // current angles, so nothing moves)
        seedStrictValues(f, f.entryPose().orient());
        return np;
    }

    /** INSERT A SECTION AT A SEAM (v19.18, D247 — the seam menu's
     *  "Insert section…"): `added` lands INSIDE piece pieceIdx at
     *  list index secIdx (secIdx == size = append). The STRUCTURAL
     *  step only — the caller builds `added` (the editor's hold
     *  recipe reads its compiled arrays; the gates pass a hold of
     *  their own). Two shapes:
     *  <ul><li>lockDownstream on an INTERIOR seam: makeSeamStrict
     *  (pieceIdx, secIdx) runs FIRST — it compiles the CURRENT,
     *  pre-insert document and freezes the downstream at its present
     *  shape AND orientation (all three axes locked at the seam's
     *  current angles, D235) — and `added` is then APPENDED to the
     *  upstream piece. The follower's position follows the new exit
     *  by the follow contract; its shape is bitwise, its locked
     *  orientation unchanged; its design speed stays independent
     *  (the D135 honest ≠v). Order matters: appending first would
     *  freeze the re-chained, gravity-re-solved downstream.</li>
     *  <li>otherwise a plain list insert: the sections after it
     *  re-chain through the new one (force sections re-solve from
     *  its exit frame — the '+'-style continuation semantics) and
     *  the reconcile re-freezes their design speeds on the next
     *  rebuild (interior k>0 sections; a follower's first section
     *  is never re-frozen). The STRICT-seam case IS this branch:
     *  the caller passes the UPSTREAM piece and secIdx = its size —
     *  the existing follower's held angles hold, its position
     *  follows.</li></ul>
     *  Speed controls, marks and influence are per section OBJECT,
     *  so nothing of theirs moves. Returns {piece, section} of the
     *  inserted section, or null (bad indices / the boundary isn't
     *  compiled — nothing mutated then). */
    public int[] insertSectionAtSeam(int pieceIdx, int secIdx,
                                     Section added, boolean lockDownstream,
                                     double dt) {
        if (added == null || pieceIdx < 0 || pieceIdx >= pieces.size()) {
            return null;
        }
        TrackPiece piece = pieces.get(pieceIdx);
        int n = piece.sections.size();
        if (secIdx < 0 || secIdx > n) return null;
        if (lockDownstream && secIdx > 0 && secIdx < n) {
            // freeze the downstream at its PRE-insert shape: the
            // split moves the tail out by reference, the head piece
            // object keeps sections [0, secIdx)
            int np = makeSeamStrict(pieceIdx, secIdx, dt);
            if (np < 0) return null;
            piece.sections.add(added);
            return new int[]{ pieceIdx, piece.sections.size() - 1 };
        }
        piece.sections.add(secIdx, added);
        return new int[]{ pieceIdx, secIdx };
    }

    /** Is piece p an ACTIVE follower (the compiler's guard)? */
    boolean followerActive(int p) {
        if (p <= 0 || p >= pieces.size()) return false;
        TrackPiece f = pieces.get(p);
        TrackPiece prev = pieces.get(p - 1);
        return f.followPrev && !f.sections.isEmpty()
            && !prev.sections.isEmpty()
            && !(prev.sections.get(prev.sections.size() - 1)
                instanceof BridgeSection);
    }

    /** BAKE a follower's CURRENT effective placement into its stored
     *  fields (D136 — the D106 bakeTilt lesson extended to strict
     *  seams): the follow rotation becomes identity, so generation
     *  resumes FROM the real frame. Without this, "Make graphs real"
     *  fit the REAL (follow-rotated) samples and the next compile
     *  applied the follow rotation A SECOND TIME (the user's "launches
     *  off into the distance"), and re-freeze reshaped against
     *  gravity in the stored frame. The follow itself stays ACTIVE
     *  (flags + discontinuous values untouched). */
    boolean bakeFollow(int followerIdx, TrackCompiler.Result r) {
        TrackPose eff = followContract(followerIdx, r);
        if (eff == null) return false;
        TrackPiece f = pieces.get(followerIdx);
        f.x = eff.pos().x();
        f.y = eff.pos().y();
        f.z = eff.pos().z();
        f.baseOrient = eff.orient();
        f.canonOrient = null;   // baked follow = a new natural frame (D199)
        f.yawDeg = 0;
        f.pitchDeg = 0;
        f.rollDeg = 0;
        // the DESIGN tier resets too: since D247 a follower GENERATES
        // through its design deltas (TrackCompiler's follower branch),
        // so this bake IS the seam's "Design → Real Orientation" —
        // design := the follow contract, the seam tilt closes to the
        // identity (the pure link; SeamShapeGate 4). Follow, locks and
        // held angles stay — it does NOT detach (detachStrictSeam is
        // the detaching twin).
        f.designPitchDeg = 0;
        f.designRollDeg = 0;
        return true;
    }

    /** The follow contract of an ACTIVE follower read off a compile —
     *  strictEntry at the previous piece's exit span — or null when
     *  the piece is not an active follower or the compile has no exit
     *  for it yet. Shared by bakeFollow and detachStrictSeam (D247). */
    TrackPose followContract(int followerIdx, TrackCompiler.Result r) {
        if (!followerActive(followerIdx)) return null;
        int spanIdx = -1;
        for (int p = 0; p < followerIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        if (spanIdx < 0 || spanIdx >= r.spans().size()) return null;
        return strictEntry(r.spans().get(spanIdx).exit(),
                           pieces.get(followerIdx));
    }

    /** MAKE SMOOTHING REAL (v19.3, D232 — user spec): re-author a
     *  smoothed strict seam's BLEND WINDOW as ordinary lanes of the
     *  UPSTREAM section's type/settings, guarded by a fresh strict
     *  seam at the window's END so nothing downstream can reshape.
     *  Structure change (A = the upstream piece, B = the follower,
     *  W = the window, [sA, sB] = the compiler's own clamped blend
     *  range around the seam):
     *    A: [..., S_prev] | B: [S_next, ...]      (seam at s_seam)
     *  becomes
     *    A: [..., S_prev·left-of-sA, W] | B': [S_next·right-of-sB, ...]
     *  W is FIT from the smoothed compiled samples over the window —
     *  AT THE TRUE entry speed ("to be real"), so the reconcile holds
     *  it real-anchored and the fitted geometry never re-times — in
     *  S_prev's type, domain, axes and units; the kept halves are
     *  exact de Casteljau cuts (native, bitwise node arithmetic). B'
     *  keeps following at the window end: its stored placement is the
     *  blended pose at sB, its locks re-seed to the hand-made default
     *  (yaw follows, pitch/roll hold the creation angles — nothing
     *  moves at creation), smoothing off. The original seam is gone —
     *  A's chain runs continuously through W.
     *
     *  The trailing seam CANNOT be removed bitwise in general: W is a
     *  fit, so its exit misses the original window end by the fit
     *  residual (mm / millidegrees), and un-stricting would re-bend the
     *  downstream by exactly that miss. It IS removed automatically
     *  when the joint lands essentially exact (orientation < 1e-4
     *  rad, design-speed step < 1e-3 m/s, position < 1e-4 m) — fine
     *  fits on gentle windows can reach it; adaptive defaults
     *  usually keep the seam (honest).
     *
     *  Refusals (returned as the stats-bar reason; null = applied):
     *  s/c or bridge neighbors (smoothing is UI-banned on s/c edges
     *  since D232 anyway — conflicting eases, and the window convert
     *  could not keep an element list), legacy-reversed neighbors,
     *  speed controls/marks or non-zero influence DELTAS inside the
     *  WINDOW itself (window-scoped since v19.4/v19.5 D233/D234 —
     *  controls and influence elsewhere on the seam sections carry
     *  onto the kept halves; legacy divergent-speed influence pairs
     *  still refuse outright), a stall inside the window (the
     *  re-time is ill-defined below the 0.5 m/s floor), or a window
     *  too small to have engaged. */
    public String makeSmoothingReal(int followerIdx, double dt,
                                    double spacing, double adaptTol) {
        return makeSmoothingReal(followerIdx, dt, spacing, adaptTol,
            false);
    }

    /** DRY-RUN check (v19.6, D235 — the seam menu GRAYS the item with
     *  the reason instead of failing on click, the user's "never let
     *  a click do nothing" rule): every refusal above the fit runs,
     *  nothing mutates. null = would apply (up to fit degeneracy). */
    public String makeSmoothingRealWhy(int followerIdx, double dt) {
        return makeSmoothingReal(followerIdx, dt, 0.1, 0, true);
    }

    public String makeSmoothingReal(int followerIdx, double dt,
                                    double spacing, double adaptTol,
                                    boolean dryRun) {
        if (!followerActive(followerIdx)) return "not a strict seam";
        TrackPiece B = pieces.get(followerIdx);
        TrackPiece A = pieces.get(followerIdx - 1);
        if (B.sSmoothLen <= 0.01) return "the seam has no smoothing";
        Section sPrevS = A.sections.get(A.sections.size() - 1);
        Section sNextS = B.sections.get(0);
        if (!(sPrevS instanceof LaneSection lPrev)
                || !(sNextS instanceof LaneSection lNext)) {
            return "smoothing conversion needs force/geo sections on "
                + "both sides of the seam";
        }
        // INFLUENCE on the seam sections is fine as long as the
        // deltas are ZERO across the window (v19.5, D234 — the
        // speed-control precedent; the window-zero test runs below
        // once the cuts are known): the kept halves stay influenced,
        // deltas cut with the lanes. (The D235 divergent-pair
        // refusal is GONE, v19.7 D236 — under the hybrid the delta
        // axis IS the authoring axis, every cut registers.)
        if (lPrev.reversed() || lNext.reversed()) {
            return "legacy reversed sections refuse — rebuild them "
                + "forward first";
        }
        // the SMOOTHED truth (plain compile — unlike lane refits,
        // the blend IS the target here, the reversal precedent)
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanB = 0;
        for (int p = 0; p < followerIdx; p++) {
            spanB += pieces.get(p).sections.size();
        }
        int spanA = spanB - 1;
        if (spanB >= r.spans().size()) return "not compiled";
        TrackCompiler.Span sa = r.spans().get(spanA);
        TrackCompiler.Span sb = r.spans().get(spanB);
        if (sa.i1() - sa.i0() < 10 || sb.i1() - sb.i0() < 10) {
            return "the seam's sections are too short";
        }
        List<FVDIntegrator.Sample> all = r.samples();
        int iSeam = sa.i1();
        double sSeam = all.get(iSeam).s();
        // the compiler's own window clamps (smoothStrictSeam)
        double hUp = Math.min(B.sSmoothLen / 2, sa.sLen() * 0.5);
        double hDn = Math.min(B.sSmoothLen / 2, sb.sLen() * 0.5);
        double arcA = sSeam - hUp, arcB = sSeam + hDn;
        int iA = iSeam;
        while (iA > sa.i0() + 1 && sSeam - all.get(iA - 1).s() <= hUp) iA--;
        int iB = iSeam + 1;
        while (iB < sb.i1() - 1 && all.get(iB + 1).s() - sSeam <= hDn) iB++;
        if (iSeam - iA < 2 || iB - iSeam < 3) {
            return "the smoothing window is too small to convert";
        }
        for (int i = iA; i <= iB; i++) {
            if (r.trueV()[i] < 0.5 || all.get(i).v() < 0.5) {
                return "the ride stalls inside the smoothing window — "
                    + "the conversion's re-time is ill-defined there";
            }
        }
        // exact window-end states + the interior samples
        List<FVDIntegrator.Sample> subA =
            all.subList(sa.i0(), sa.i1() + 1);
        List<FVDIntegrator.Sample> subB =
            all.subList(sb.i0(), sb.i1() + 1);
        FVDIntegrator.Sample csA = sampleAtArc(subA, arcA);
        FVDIntegrator.Sample csB = sampleAtArc(subB, arcB);
        List<FVDIntegrator.Sample> w = new ArrayList<>(iB - iA + 3);
        w.add(csA);
        for (int i = iA; i <= iB; i++) {
            if (all.get(i).s() > csA.s() + 1e-9
                    && all.get(i).s() < csB.s() - 1e-9) {
                w.add(all.get(i));
            }
        }
        w.add(csB);
        if (w.size() < 8) return "the smoothing window is too small";
        // TRUE speed at the window entry ("to be real"): interpolated
        // between the bracketing samples like the pose
        double vW;
        {
            int lo = Math.max(0, iA - 1);
            double s0 = all.get(lo).s(), s1 = all.get(iA).s();
            double f = s1 - s0 < 1e-12 ? 0
                // pure-core rule: no FvdDesigner reference here (the
                // gates load Coaster without JavaFX on the classpath)
                : Math.max(0, Math.min(1, (arcA - s0) / (s1 - s0)));
            vW = r.trueV()[lo] + f * (r.trueV()[iA] - r.trueV()[lo]);
            vW = Math.max(1, vW);
        }
        // native cut coordinates on each section's own axis
        double cutA = lPrev.domain() == Section.Domain.DISTANCE
            ? csA.s() - sa.s0() : csA.t() - sa.t0();
        double cutB = lNext.domain() == Section.Domain.DISTANCE
            ? csB.s() - sb.s0() : csB.t() - sb.t0();
        // SPEED CONTROLS are fine on the seam's sections as long as
        // none touches the WINDOW itself (v19.4, D233 — user spec;
        // was a blanket refusal): outside-the-window controls/marks
        // ride onto the kept halves below
        for (SpeedControl ctl : lPrev.speedCtls) {
            if (ctl.x0 + ctl.len > cutA - 1e-9) {
                return "a speed control overlaps the smoothing window "
                    + "— move or remove it first";
            }
        }
        for (double m : lPrev.speedMarks) {
            if (m > cutA - 1e-9) {
                return "a speed mark sits inside the smoothing window "
                    + "— move or remove it first";
            }
        }
        for (SpeedControl ctl : lNext.speedCtls) {
            if (ctl.x0 < cutB + 1e-9) {
                return "a speed control overlaps the smoothing window "
                    + "— move or remove it first";
            }
        }
        for (double m : lNext.speedMarks) {
            if (m < cutB + 1e-9) {
                return "a speed mark sits inside the smoothing window "
                    + "— move or remove it first";
            }
        }
        // influence DELTAS must be zero across the window (D234 —
        // the fitted window section is plain; non-zero deltas there
        // would bake in silently and step against the kept half)
        if (lPrev.influenced() && !deltasZeroOn(lPrev, cutA,
                Double.POSITIVE_INFINITY)) {
            return "the influence deltas are non-zero inside the "
                + "smoothing window — zero them there or bake the "
                + "influence first";
        }
        if (lNext.influenced() && !deltasZeroOn(lNext,
                Double.NEGATIVE_INFINITY, cutB)) {
            return "the influence deltas are non-zero inside the "
                + "smoothing window — zero them there or bake the "
                + "influence first";
        }
        if (dryRun) return null;   // every check passed (D235)
        // the FIT (may be refused by degenerate geometry — do it
        // before mutating anything)
        LaneSection W = fitLanes(w, vW, lPrev instanceof GeoSection,
            dt, spacing, adaptTol, lPrev.domain(), lPrev.rollGlobal,
            lPrev.yawPitchGlobal, lPrev.ratePerMeter, lPrev.rollAbsolute,
            lPrev.ypAbsolute,
            pieceHeadDeg(r, followerIdx - 1));   // W joins piece A (D238)
        if (W == null) return "the window is too short to re-fit";
        // ---- assemble ----
        List<List<BezierBaker.Node>> leftLanes =
            splitLanes(lPrev.lanes, cutA, false);
        LaneSection prevLeft = lPrev instanceof GeoSection
            ? new GeoSection(leftLanes, lPrev.vDesign)
            : new ForceSection(leftLanes, lPrev.vDesign);
        prevLeft.domain(lPrev.domain());
        prevLeft.rollGlobal = lPrev.rollGlobal;
        prevLeft.yawPitchGlobal = lPrev.yawPitchGlobal;
        prevLeft.ratePerMeter = lPrev.ratePerMeter;
        prevLeft.rollAbsolute = lPrev.rollAbsolute;
        prevLeft.ypAbsolute = lPrev.ypAbsolute;
        prevLeft.vPinned = lPrev.vPinned;
        System.arraycopy(lPrev.endFollows, 0, prevLeft.endFollows, 0, 3);
        List<List<BezierBaker.Node>> rightLanes =
            splitLanes(lNext.lanes, cutB, true);
        LaneSection nextRight = lNext instanceof GeoSection
            ? new GeoSection(rightLanes, csB.v())
            : new ForceSection(rightLanes, csB.v());
        nextRight.domain(lNext.domain());
        nextRight.rollGlobal = lNext.rollGlobal;
        nextRight.yawPitchGlobal = lNext.yawPitchGlobal;
        nextRight.ratePerMeter = lNext.ratePerMeter;
        nextRight.rollAbsolute = lNext.rollAbsolute;
        nextRight.ypAbsolute = lNext.ypAbsolute;
        System.arraycopy(lNext.endFollows, 0, nextRight.endFollows, 0, 3);
        // the ABS heading rebase at the window end (piece-relative
        // since v19.9, D238): B re-anchors at the window end, so
        // EVERY absolute heading lane in B re-zeroes there — the
        // shift is the heading traversed from B's old entry to the
        // cut. Read off the authored lane when B's first section is
        // itself abs (exact), else off the samples (a multi-turn
        // window may land a 360° representative off — the same
        // orientation either way, the integrator wraps).
        double hCutB = Double.NaN;
        if (lNext.ypAbsolute && lNext.yawPitchGlobal) {
            hCutB = BezierBaker.exactAt(lNext.lanes.get(1), cutB);
            for (BezierBaker.Node nd : nextRight.lanes.get(1)) {
                nd.val -= hCutB;
            }
        }
        if (Double.isNaN(hCutB)) {
            double hs = FVDIntegrator.headingOfT(csB.T());
            double hp = pieceHeadDeg(r, followerIdx);
            hCutB = Double.isNaN(hs) || Double.isNaN(hp)
                ? 0 : Math.IEEEremainder(Math.toDegrees(hs) - hp, 360);
        }
        for (int k2 = 1; k2 < B.sections.size(); k2++) {
            if (B.sections.get(k2) instanceof GeoSection g2
                    && g2.ypAbsolute && g2.yawPitchGlobal
                    && !g2.reversed && hCutB != 0) {
                for (BezierBaker.Node nd : g2.lanes.get(1)) {
                    nd.val -= hCutB;
                }
            }
        }
        nextRight.vPinned = lNext.vPinned;
        // influence rides onto the kept halves (D234 — deltas are
        // zero across the window, checked above, so the discarded
        // parts carried none)
        if (lPrev.influenced()) carryInfluence(lPrev, prevLeft, cutA, false);
        if (lNext.influenced()) carryInfluence(lNext, nextRight, cutB, true);
        // outside-the-window speed controls/marks ride onto the kept
        // halves (D233 — checked above that none touches the window)
        carrySpeedCtls(lPrev, sa, all, prevLeft, 0, cutA);
        carrySpeedCtls(lNext, sb, all, nextRight, cutB,
            Double.POSITIVE_INFINITY);
        A.sections.set(A.sections.size() - 1, prevLeft);
        A.sections.add(W);
        B.sections.set(0, nextRight);
        // B follows at the WINDOW END now: stored placement = the
        // blended pose at sB (the D71 rule — nothing moves at
        // creation), smoothing off, locks re-seeded hand-made.
        // CRITICAL (the follow freeze): the remainder must GENERATE
        // at the DESIGN-chain frame, not the world frame — the
        // follower's re-aim tilt (held angles ≠ the stored design)
        // means the world frame at the cut is the design frame
        // rotated by the follow tilt, and force lanes generated
        // straight from the tilted frame would RE-SOLVE against
        // gravity (a 6° held pitch walked the tail 37 m in the
        // gate). Span.exitTilt (D224) is exactly that rotation:
        // baseOrient := tilt⁻¹ · worldFrame restores the design
        // chain, and the re-seeded held angles re-aim the rigid
        // image back onto the world — the original geometry.
        Quat worldCut = Quat.fromFrame(csB.T(), csB.U());
        Quat fTilt = sb.exitTilt();
        B.x = csB.pos().x();
        B.y = csB.pos().y();
        B.z = csB.pos().z();
        B.baseOrient = fTilt == null || fTilt == Quat.IDENTITY
            ? worldCut : fTilt.conjugate().mul(worldCut);
        B.canonOrient = null;
        B.yawDeg = 0;
        B.pitchDeg = 0;
        B.rollDeg = 0;
        B.designPitchDeg = 0;
        B.designRollDeg = 0;
        B.sSmoothLen = 0;
        B.sLockYaw = true;    // ALL THREE locked at creation (v19.6,
        B.sLockPitch = true;  // D235 — user spec; direct attach is
        B.sLockRoll = true;   // the one yaw-unlocked exception)
        seedStrictValues(B, worldCut);
        // ---- the trailing seam auto-removes when the joint is
        // essentially exact (see the doc above) ----
        TrackCompiler.Result r2 = TrackCompiler.compile(this, dt);
        int spanW = spanA + 1;
        if (spanW < r2.spans().size()) {
            TrackPose exitW = r2.spans().get(spanW).exit();
            Quat dq = exitW.orient().mul(B.baseOrient.conjugate());
            double ang = 2 * Math.acos(Math.min(1, Math.abs(dq.w())));
            double dp = exitW.pos()
                .sub(new Vec3(B.x, B.y, B.z)).length();
            int eW = r2.spans().get(spanW).i1();
            double dv = Math.abs(r2.samples().get(eW).v()
                - nextRight.vDesign);
            if (ang < 1e-4 && dp < 1e-4 && dv < 1e-3) {
                unstrictSeam(followerIdx);
            }
        }
        return null;
    }

    /** UN-STRICT a seam (v17.1, D135 — user spec: "KEEPS SPEED
     *  CONFLICT, but reorients track to be continuous"): the follower
     *  merges back into the previous piece as a normal continuation —
     *  orientation continuity restored (sections re-chain through the
     *  seam, so force sections re-solve and the shape CAN change),
     *  graphs kept exactly, and the speed conflict deliberately KEPT
     *  via the D97 pin. This is exactly the historic whole-bridge-
     *  convert stitch, which is what makes "convert bridge with a
     *  strict seam, then un-strict" identical to the old convert. */
    public boolean unstrictSeam(int followerIdx) {
        if (followerIdx <= 0 || followerIdx >= pieces.size()) return false;
        TrackPiece f = pieces.get(followerIdx);
        if (!f.followPrev) return false;
        TrackPiece prev = pieces.get(followerIdx - 1);
        if (prev.sections.isEmpty() || prev.sections
                .get(prev.sections.size() - 1) instanceof BridgeSection) {
            return false;
        }
        Section first = f.sections.isEmpty() ? null : f.sections.get(0);
        f.followPrev = false;
        attachPiece(followerIdx - 1, followerIdx);
        if (first != null) first.vPinned(true);   // the conflict stays
        return true;
    }

    /** SPLIT TRACK AT a strict seam (v17.1): bake the CURRENT
     *  effective placement into the stored fields (the piece stays
     *  exactly where it sits) and drop the follow — an ordinary
     *  detached piece from here on. CONFLICT-PRESERVING since v19.18
     *  (D247 review fix): bakeFollow is the seam's Design → Real
     *  (it zeroes the design tier), so routing a detach through it
     *  silently re-solved a design-tilted follower against gravity
     *  while connectStrict (D5) had preserved that conflict on attach
     *  — attach→detach was no longer an involution. Now the position
     *  bakes, the TRUE tier is decomposed onto the follow contract
     *  (decomposeNear, branch-continuous about the current deltas)
     *  and baseOrient, the canonical pair and the DESIGN deltas stay
     *  untouched: yaw is a world-Y rotation of designPose (additive
     *  psi ⇒ generation bitwise) and the seam's ≠o becomes the free
     *  piece's ≠o (SeamShapeGate 9). A vertical frame on either side
     *  (no Euler triple) falls back to the bakeFollow bake. */
    public boolean detachStrictSeam(int followerIdx, double dt) {
        if (!followerActive(followerIdx)) return false;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        TrackPose eff = followContract(followerIdx, r);
        if (eff == null) return false;
        TrackPiece f = pieces.get(followerIdx);
        double[] d = TrackPiece.decomposeNear(f.baseOrient, eff.orient(),
            f.yawDeg, f.pitchDeg, f.rollDeg);
        if (d != null) {
            f.x = eff.pos().x();
            f.y = eff.pos().y();
            f.z = eff.pos().z();
            f.yawDeg = TrackPiece.wrap360(d[0]);
            f.pitchDeg = d[1];
            f.rollDeg = TrackPiece.wrap360(d[2]);
        } else if (!bakeFollow(followerIdx, r)) {
            return false;
        }
        f.followPrev = false;
        return true;
    }

    /** CONNECT pieces directly = a STRICT SEAM (v17.1, D135 — user
     *  spec; replaces the dissolving attach in the UI flows): the
     *  floating piece reorders to come next on the timeline and
     *  FOLLOWS with all axes UNLOCKED — fully continuous, the closest
     *  thing to the old attach but with the downstream shape frozen
     *  rigid. Its stored placement stays untouched (the frozen
     *  original, for later relocks). Returns the follower's index. */
    public int connectStrict(int targetIdx, int floatingIdx) {
        return connectStrict(targetIdx, floatingIdx, false, false, false);
    }

    /** Lock-carrying variant (v17.12, D153 — user spec): the UI's
     *  attach flows seed the seam's DISCONTINUOUS axes from the
     *  surface the user was just editing — a direct attach from the
     *  floating piece's placement padlocks (yaw has none =
     *  continuous), the zero-distance-bridge YES from the bridge's
     *  yaw/pitch/roll locks. Locked axes hold the creation angles
     *  (seeded below), so nothing moves at creation either way. */
    public int connectStrict(int targetIdx, int floatingIdx,
                             boolean lockYaw, boolean lockPitch,
                             boolean lockRoll) {
        if (targetIdx == floatingIdx) return -1;
        int to = floatingIdx < targetIdx ? targetIdx : targetIdx + 1;
        movePiece(floatingIdx, to);
        TrackPiece f = pieces.get(to);
        f.followPrev = true;
        f.sLockYaw = lockYaw;
        f.sLockPitch = lockPitch;
        f.sLockRoll = lockRoll;
        // ATTACH PRESERVES A TWO-TIER CONFLICT FOR FREE (v19.18, D247
        // — no fold, deliberately): the stored placement, the
        // canonical pair AND the design deltas stay untouched
        // (movePiece only reorders the list), and since D247 the
        // compiler's follower branch GENERATES through designPose/
        // designCanon, so a design-tilted piece keeps generating
        // bitwise the shape it had as a free piece; the held TRUE
        // angles seeded below from entryPose() (its pre-attach real
        // elevation/bank) make the seam tilt reproduce the old
        // conflict tilt plus the seam's heading delta — a rigid image
        // in the world (SeamShapeGate 1), and a (0,0)-design piece
        // stays the historic path bitwise (SeamShapeGate 2). The same
        // holds for makeSeamStrict on a tilted piece: splitPiece hands
        // the cut piece the DESIGN chain + the tilt as its own true-
        // tier deltas (D224), so its generation is bitwise and its
        // world placement holds to Euler dust.
        seedStrictValues(f, f.entryPose().orient());
        // smoothing OFF by default when connecting pieces (v17.4;
        // D141 — user spec; hand-made strict seams stay sharp)
        // (v17.6, D143 — user walk-back: connects start SHARP;
        // only bridge converts arrive smoothed)
        return to;
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
     *  smallest smooth vertical-rate hump (30u²(1−u)², zero value and
     *  slope at the seams) that turns the leftover pitch gap — a
     *  gap measured BRANCH-CORRECTLY against the march's unwrapped
     *  pitch since v17.14 (D155) — a straight dive
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
        double[] meta = new double[2];
        Vec3[] st = march(pA, tA, uA, d, sa, sb, 0, 0, 0, null, null, meta);
        // humps close only the LOCKED axes (v13.13): an aligned axis
        // needs no correction — the PIECE rotates to meet the march.
        // BRANCH-CORRECT since v17.14 (D155): the end pitch is the
        // march's UNWRAPPED vertical-plane angle — a crest's pure
        // blend (both seam curvatures point UP while the net pitch
        // change points DOWN) routinely overshoots past vertical,
        // where asin alone reflects and the fixed point converged
        // onto reversed-heading / full-loop branches (the user's
        // crest-bridge corkscrew rolls and inverted loops). Yaw and
        // bank are measured only on POLE-FREE passes: across a pole
        // both flip ~180°, and that seed walked the twist into a
        // full barrel roll (minUy −0.99 at every mid length).
        boolean crossed = meta[1] != 0;
        double resPitch = align[1] ? 0
            : Math.asin(Math.max(-1, Math.min(1, tB.y())))
            - unwrappedEndPitch(st[1], meta);
        double resYaw = 0;
        Vec3 endT = st[1];
        double ha2 = endT.x() * endT.x() + endT.z() * endT.z();
        double hb2 = tB.x() * tB.x() + tB.z() * tB.z();
        if (!align[0] && !crossed && ha2 > 1e-12 && hb2 > 1e-12) {
            resYaw = Math.atan2(-tB.z(), tB.x())
                   - Math.atan2(-endT.z(), endT.x());
            while (resYaw > Math.PI) resYaw -= 2 * Math.PI;
            while (resYaw < -Math.PI) resYaw += 2 * Math.PI;
        }
        // RESIDUAL TWIST (v17.11, D150): roll not aligned = the bank
        // gap to the target joins the march (see contDeriv) instead
        // of being painted on by the render's quintic afterwards —
        // which left the spine's curvature in the untwisted frame
        // (the user's ~1 g far-seam Gv/Gl jump on rolling seams).
        double resTw = align[2] || crossed ? 0
            : bankGap(st[1], st[2], tB, uB);
        if (Math.abs(resPitch) > 1e-9 || Math.abs(resYaw) > 1e-9
                || Math.abs(resTw) > 1e-9
                || path != null || spine != null) {
            resPitch = Math.abs(resPitch) > 1e-9 ? resPitch : 0;
            resYaw = Math.abs(resYaw) > 1e-9 ? resYaw : 0;
            resTw = Math.abs(resTw) > 1e-9 ? resTw : 0;
            st = march(pA, tA, uA, d, sa, sb,
                resPitch, resYaw, resTw, path, spine, meta);
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
                - unwrappedEndPitch(st[1], meta);
            double dY = 0;
            Vec3 eT = st[1];
            double ea2 = eT.x() * eT.x() + eT.z() * eT.z();
            if (!align[0] && meta[1] == 0 && ea2 > 1e-12 && hb2 > 1e-12) {
                dY = Math.atan2(-tB.z(), tB.x())
                   - Math.atan2(-eT.z(), eT.x());
                while (dY > Math.PI) dY -= 2 * Math.PI;
                while (dY < -Math.PI) dY += 2 * Math.PI;
            }
            // the twist's own second-order re-mix (v17.11): rolling
            // the frame mid-march re-aims the blend's κ, moving the
            // end bank a little — re-measure like pitch/heading
            double dTw = align[2] || meta[1] != 0 ? 0
                : bankGap(st[1], st[2], tB, uB);
            // BOUNDED FIXED-POINT (v17.11, D150 — was one pass): with
            // a LARGE residual twist (his split-and-rebridge file:
            // ~48° of locked-roll bank over 24 m) the twist re-aims
            // the blend enough that the pitch hump's response gain is
            // well off 1 — a single correction left DEGREES of far-
            // seam tangent error and the spine could never land on
            // its own placement. All three measures are wrap-safe
            // (asin / wrapped atan2 — NOT the reverted D95 bank
            // iteration's ambiguous measure), so iterating is sound;
            // clean marches still exit on the first measure, keeping
            // the historic two-march sequence bit-identically.
            // DAMPED since v17.15 (D156 — the user's banked-crest
            // cliff at ~45-51 m): the pitch↔twist coupling gain grows
            // with resPitch × twist (the twist re-aims the blend's
            // body κ, the hump re-mixes the bank along the new path),
            // and past unit gain the full-step iteration DIVERGED —
            // clean below the threshold, an unconverged march + the
            // septic fallback immediately above it ("suddenly shoot
            // the track around"). A pass whose worst defect GROWS
            // halves the step (floor 1/8); contracting passes keep
            // the full step, so every previously-converging sequence
            // — and every gate baseline — is reproduced verbatim.
            double lam = 1;
            double prevMax = Math.max(Math.abs(dP),
                Math.max(Math.abs(dY), Math.abs(dTw)));
            for (int it = 0; it < 14; it++) {
                // a pole-crossed pass can never declare convergence:
                // its yaw/twist went unmeasured, and the unwrapped
                // pitch defect on a wrong branch is ±360k — never
                // small — so the loop always pushes off it (v17.14)
                if (meta[1] == 0 && Math.abs(dP) <= 1e-4
                        && Math.abs(dY) <= 1e-4
                        && Math.abs(dTw) <= 1e-4) break;
                resPitch += lam * dP;
                // yaw/twist accumulate MOD 2π (v17.14): a heading or
                // bank gap is a pose property — the short way around
                // is always the correction; summed wrapped deltas
                // once walked the twist to a 360° barrel roll
                resYaw = wrapPi(resYaw + lam * dY);
                resTw = wrapPi(resTw + lam * dTw);
                if (path != null) path.clear();
                if (spine != null) spine.clear();
                st = march(pA, tA, uA, d, sa, sb, resPitch, resYaw,
                    resTw, path, spine, meta);
                dP = align[1] ? 0
                    : Math.asin(Math.max(-1, Math.min(1, tB.y())))
                    - unwrappedEndPitch(st[1], meta);
                dY = 0;
                Vec3 eT2 = st[1];
                double ea3 = eT2.x() * eT2.x() + eT2.z() * eT2.z();
                if (!align[0] && meta[1] == 0 && ea3 > 1e-12
                        && hb2 > 1e-12) {
                    dY = Math.atan2(-tB.z(), tB.x())
                       - Math.atan2(-eT2.z(), eT2.x());
                    while (dY > Math.PI) dY -= 2 * Math.PI;
                    while (dY < -Math.PI) dY += 2 * Math.PI;
                }
                dTw = align[2] || meta[1] != 0 ? 0
                    : bankGap(st[1], st[2], tB, uB);
                double m = Math.max(Math.abs(dP),
                    Math.max(Math.abs(dY), Math.abs(dTw)));
                if (m > prevMax + 1e-12) lam = Math.max(0.125, lam * 0.5);
                prevMax = m;
            }
            // STAGED HOMOTOPY RESCUE (v17.15, D156 — the user's
            // banked-crest report #4): when the coupled loop still
            // failed — banked crests past ~45-51 m, where the
            // pitch↔twist gain κ·d·twist crosses 1 and the FIRST full
            // steps throw the iterate out of the sane basin before
            // any damping can engage — restart from zero and walk in:
            // converge the pitch/heading humps ALONE (their fixed
            // point is robust at any length — the twist is what
            // couples), then ramp the twist toward the bank gap in
            // coupling-bounded increments (≤ 0.8/(κ·d) rad), re-
            // converging pitch after each. Every iterate stays in the
            // convergent basin by construction, extending the clean
            // behavior to arbitrary practical lengths. Never entered
            // by a case the direct loop converges — those (and every
            // gate baseline) stay bit-identical.
            if (meta[1] != 0 || Math.abs(dP) > 1e-4
                    || Math.abs(dY) > 1e-4 || Math.abs(dTw) > 1e-4) {
                // the twist SEEDS at the seam-to-seam bank gap — a
                // pose property, zero for unbanked seams. Starting
                // the rescue at twist 0 put its first march in the
                // WRONG PATH FAMILY on banked crests (the blend
                // sweeping an inclined arc whose end bank reads
                // ~180° off), and the bank homotopy then walked the
                // wrapped short way into a full-inversion corkscrew
                // that self-consistently CONVERGED (end bank exact,
                // mid-span upside down). Seeded, the first march is
                // already the twisted crest and the ramp only walks
                // the small path-transport drift.
                // pitch seeds at the ANALYTIC defect — target pitch
                // minus entry pitch plus the blend's planar κ_u
                // integral (Hermite value + slope terms). Starting at
                // 0 sent stage 1's first iterates over the poles at
                // long lengths (the raw blend climbs κ_u·d ≈ 150°+
                // before any hump exists) and the 8-pass budget burned
                // out recovering; seeded, the first march is already
                // in the crest basin and the family extends smoothly.
                double blendP = d * ((sa[0] + sb[0]) / 2
                    + d * (sa[2] - sb[2]) / 12);
                double pSeed = align[1] ? 0
                    : Math.asin(Math.max(-1, Math.min(1, tB.y())))
                    - Math.asin(Math.max(-1, Math.min(1, tA.y())))
                    - blendP;
                double[] io = { pSeed, 0,
                    align[2] ? 0 : bankGap(tA, uA, tB, uB) };
                double seed = io[2];
                st = pitchConverge(pA, tA, uA, d, sa, sb, io, align,
                    tB, hb2, 8, meta);
                if (!align[2]) {
                    double kScale = Math.max(
                        Math.max(Math.abs(sa[0]), Math.abs(sb[0])),
                        Math.max(Math.abs(sa[1]), Math.abs(sb[1])));
                    double maxStep = 0.8 / Math.max(1, kScale * d);
                    double lastGood = seed;
                    for (int gs = 0; gs < 16; gs++) {
                        if (meta[1] != 0) {
                            // this twist increment threw the march
                            // over a pole — revert it and retry at
                            // half the step (never break here: a
                            // crossed iterate reads NO bank, and
                            // breaking once left ~80° of twist
                            // unapplied on a march that then passed
                            // the angle-only render gate)
                            io[2] = lastGood;
                            maxStep *= 0.5;
                            st = pitchConverge(pA, tA, uA, d, sa, sb,
                                io, align, tB, hb2, 8, meta);
                            continue;
                        }
                        double rem = bankGap(st[1], st[2], tB, uB);
                        if (Math.abs(rem) <= 1e-4) break;
                        // wrong-family guardrail: the transport drift
                        // past the seeded twist is small on any real
                        // bridge — a walk straying beyond 90° of it
                        // is chasing an inverted family; stop and let
                        // the render gates hand the septic the job
                        if (Math.abs(wrapPi(io[2] - seed))
                                > Math.PI / 2) break;
                        lastGood = io[2];
                        io[2] = wrapPi(io[2] + Math.max(-maxStep,
                            Math.min(maxStep, rem)));
                        st = pitchConverge(pA, tA, uA, d, sa, sb, io,
                            align, tB, hb2, 8, meta);
                    }
                }
                resPitch = io[0];
                resYaw = io[1];
                resTw = io[2];
                if (path != null || spine != null) {
                    if (path != null) path.clear();
                    if (spine != null) spine.clear();
                    st = march(pA, tA, uA, d, sa, sb, resPitch, resYaw,
                        resTw, path, spine, meta);
                }
            }
        }
        return st;
    }

    /** Damped fixed point for the PITCH (and, position mode, HEADING)
     *  humps at a FROZEN twist — the v17.15 rescue's building block.
     *  io = {resPitch, resYaw, resTw}; io[0]/io[1] are updated in
     *  place, io[2] is read only. Returns the final march end state;
     *  meta carries its pole-crossing info. */
    private static Vec3[] pitchConverge(Vec3 pA, Vec3 tA, Vec3 uA,
            double d, double[] sa, double[] sb, double[] io,
            boolean[] align, Vec3 tB, double hb2, int iters,
            double[] meta) {
        Vec3[] st = march(pA, tA, uA, d, sa, sb, io[0], io[1], io[2],
            null, null, meta);
        double lam = 1, prevMax = Double.MAX_VALUE;
        for (int it = 0; it < iters; it++) {
            double dP = align[1] ? 0
                : Math.asin(Math.max(-1, Math.min(1, tB.y())))
                - unwrappedEndPitch(st[1], meta);
            double dY = 0;
            double ea2 = st[1].x() * st[1].x() + st[1].z() * st[1].z();
            if (!align[0] && meta[1] == 0 && ea2 > 1e-12
                    && hb2 > 1e-12) {
                dY = wrapPi(Math.atan2(-tB.z(), tB.x())
                   - Math.atan2(-st[1].z(), st[1].x()));
            }
            if (meta[1] == 0 && Math.abs(dP) <= 1e-4
                    && Math.abs(dY) <= 1e-4) break;
            double m = Math.max(Math.abs(dP), Math.abs(dY));
            if (m > prevMax + 1e-12) lam = Math.max(0.125, lam * 0.5);
            prevMax = m;
            io[0] += lam * dP;
            io[1] = wrapPi(io[1] + lam * dY);
            st = march(pA, tA, uA, d, sa, sb, io[0], io[1], io[2],
                null, null, meta);
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
        // (a v17.10 draft briefly tightened this to 5 mm / 0.02° —
        // REVERTED same round on user verdict, D150: it flipped old
        // files' optimized bridges to the septic and didn't capture
        // his actual bug; 2 cm / 0.3° is the shipped gate again)
        // BANK gate (v17.15, D156): an unconverged rescue can land
        // position AND tangent while the twist never closed — a
        // corkscrewed march with its end bank ~80° off the target
        // slipped through the angle-only test and rendered. Converged
        // marches close bank to 1e-4 rad (0.006°), so 1° is 170×
        // margin — only genuine garbage falls to the septic, which
        // absorbs it honestly (near-vertical NaN banks skip the test,
        // matching bankGap's own hold-off).
        double bMiss = Math.abs(bankGap(st[1], st[2],
            bs.solvedTarget.T(), bs.solvedTarget.U()));
        return miss > 0.02 || tAng > 0.3 || bMiss > Math.toRadians(1)
            ? null : spine;
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
        // ONE TIER RULE (v19.20, D249 — superseding D222's "the march
        // writes the TRUE tier by convention"): the alignment goes
        // through the target's editPitch/editRoll like every user
        // writer, so LOCKSTEP moves both tiers (the march can never
        // open a conflict — the target re-solves, its head dynamics
        // shift the signature and ONE more re-march settles: a
        // contracting fixed point, OrientModeGate g4), REAL_ONLY
        // writes the true tier only (bitwise the historic write —
        // the shape is a rigid image, the conflict grows) and
        // DESIGN_ONLY is defensive: optimize-orientation is
        // unavailable there (item O grays the radio), so a file that
        // still holds the pair writes TRUE only like REAL_ONLY
        // rather than re-solving the design blindly. Yaw is shared.
        boolean designOnly =
            t.orientMode == TrackPiece.OrientMode.DESIGN_ONLY;
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
            double np = Math.max(-85, Math.min(85,
                t.pitchDeg + Math.toDegrees(dp)));
            if (designOnly) t.pitchDeg = np;
            else t.editPitch(np);   // REAL_ONLY: pitchDeg = np, bitwise
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
                // editRoll's wrap360 IS the old while-loop pair on the
                // true tier (bitwise); LOCKSTEP wraps the design too
                double nr = t.rollDeg + Math.toDegrees(dr);
                if (designOnly) t.rollDeg = TrackPiece.wrap360(nr);
                else t.editRoll(nr);
            }
        }
        t.x = st[0].x();
        t.y = st[0].y();
        t.z = st[0].z();
    }

    /** LOCKSTEP INNER SETTLE (v19.20, D249): under the one tier rule
     *  an optimize-orientation alignment of a LOCKSTEP target moves
     *  its DESIGN tier too, so the target RE-SOLVES against gravity
     *  and its head dynamics (accB/jerkB/rollB — the B side of the
     *  march's blend) shift; the march's natural end then shifts by a
     *  fraction of the alignment (measured gain 0.2-0.4 per pass:
     *  ∂κ_head/∂pitch ≈ g·sinθ/v² times the blend's ~d/2 lever), so
     *  left to the rebuild loop the fixed point took 13-23 whole-
     *  document compiles (probed on the OrientModeGate fixture; the
     *  loop's 12 + 40 cap held, but a live gesture's 12-pass tube
     *  showed a mid-cascade target). This inner loop settles it
     *  HERE: re-capture the target's head exactly as the next compile
     *  will (TrackCompiler.captureTargetHead at INTEGRATE_DT) and
     *  re-align until the deltas fall under 1e-9° (≤ 24 rounds, each
     *  integrating only the target's first section), so the stamped
     *  signature already matches the next capture and the outer loop
     *  sees a fixed point in one pass (gated g4). REAL_ONLY /
     *  DESIGN_ONLY targets never re-solve under the march (true-tier
     *  writes) — the historic path, bitwise; a yaw-only alignment
     *  never re-solves either (heading commutes with gravity).
     *  REST GUARANTEE (D249 review fix — determinism contract (b)):
     *  whatever the convergence, the target's head is re-captured
     *  ONCE MORE after the last alignment, so the signature
     *  optimizeBridge stamps equals the NEXT compile's capture (a
     *  converged loop repeats its last capture to < 1e-9; the loop
     *  used to stamp the PENULTIMATE capture on a 24-round exit, and
     *  a slow pitched target — vDesign 2-8 m/s at ±25-30°, probed —
     *  left the auto pass re-marching through the rebuild loop's
     *  whole 12 + 40 cap on EVERY rebuild, the D197 "reopened coaster
     *  keeps changing" class). Such a target has a REPELLING fixed
     *  point (the pitch↔head-curvature gain exceeds 1 — no step size
     *  converges, so damping is no fix); the loop STOPS HONESTLY:
     *  each round snapshots the placement before its alignment, an
     *  unconverged exit restores the round with the SMALLEST delta,
     *  and a round whose pitch lands on the ±85° clamp ends the loop.
     *  The stamped placement then fails marchSpine's 2 cm / 0.3°
     *  landing test and the bridge renders the honest septic instead
     *  of cascading. Returns whether any round ran — the target may
     *  have moved, optimizeBridge's re-knee reads it. */
    private boolean settleLockstepAlign(BridgeSection bs, int targetIdx,
                                        double dist, boolean orient) {
        TrackPiece t = pieces.get(targetIdx);
        if (!orient || t.orientMode != TrackPiece.OrientMode.LOCKSTEP) {
            return false;
        }
        boolean[] align = alignFlags(bs, orient);
        if (!align[1] && !align[2]) return false;
        double[] best = null;    // the pre-alignment placement of the
                                 // smallest-delta round (D249 review)
        double bestDelta = Double.POSITIVE_INFINITY;
        boolean converged = false;
        for (int round = 0; round < 24; round++) {
            TrackCompiler.captureTargetHead(bs, t, INTEGRATE_DT, fric());
            double[] pre = placementOf(t);
            alignPieceForBridge(bs, targetIdx, dist, orient);
            double delta = Math.max(
                Math.abs(TrackPiece.wrap180(t.yawDeg - pre[3])),
                Math.max(Math.abs(t.pitchDeg - pre[4]),
                    Math.abs(TrackPiece.wrap180(t.rollDeg - pre[5]))));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = pre;
            }
            if (delta < 1e-9) {
                converged = true;
                break;
            }
            // the pitch clamp: the march's natural end is steeper
            // than the alignment can express — stop honestly
            if (align[1] && Math.abs(t.pitchDeg) >= 85) break;
        }
        if (!converged && best != null) restorePlacement(t, best);
        // the MANDATORY re-capture: stamp == the next compile's capture
        TrackCompiler.captureTargetHead(bs, t, INTEGRATE_DT, fric());
        return true;
    }

    /** The settle loop's placement snapshot {x, y, z, yaw, pitch,
     *  roll, designPitch, designRoll} (D249 review fix). */
    private static double[] placementOf(TrackPiece t) {
        return new double[] { t.x, t.y, t.z, t.yawDeg, t.pitchDeg,
            t.rollDeg, t.designPitchDeg, t.designRollDeg };
    }

    private static void restorePlacement(TrackPiece t, double[] s) {
        t.x = s[0];
        t.y = s[1];
        t.z = s[2];
        t.yawDeg = s[3];
        t.pitchDeg = s[4];
        t.rollDeg = s[5];
        t.designPitchDeg = s[6];
        t.designRollDeg = s[7];
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
        // CAP the big end too (v17.14, D155): contDeriv scales the
        // Hermite slope terms by d, so a violently-authored seam (a
        // force lane plunging 4 g right at the cut measures ~0.025/m²
        // of real jerk) EXTRAPOLATES across a third of a long bridge —
        // κ hit −0.2/m (−10 g) mid-span at 46 m on the user's crest
        // file. Jerk continuity is a seam-LOCAL property: keep the
        // seam slope exact in direction but bound its d-scaled reach
        // to MARCH_SLOPE_CAP × the seam curvature scale (the h10
        // basis peaks at 0.148, so the mid-span contribution stays
        // ≤ ~0.6× the curvature values). Small/clean seams pass
        // through UNTOUCHED — every existing gate fixture is far
        // below the cap.
        double cap = MARCH_SLOPE_CAP * kRef / Math.max(1, d);
        if (Math.abs(sa[2]) > cap) sa[2] = Math.copySign(cap, sa[2]);
        if (Math.abs(sa[3]) > cap) sa[3] = Math.copySign(cap, sa[3]);
        if (Math.abs(sb[2]) > cap) sb[2] = Math.copySign(cap, sb[2]);
        if (Math.abs(sb[3]) > cap) sb[3] = Math.copySign(cap, sb[3]);
        double rcap = MARCH_SLOPE_CAP * rRef / Math.max(1, d);
        if (Math.abs(sa[5]) > rcap) sa[5] = Math.copySign(rcap, sa[5]);
        if (Math.abs(sb[5]) > rcap) sb[5] = Math.copySign(rcap, sb[5]);
    }

    /** Ceiling on a seam slope's d-scaled march contribution, in
     *  units of the seam curvature scale (v17.14, D155). */
    private static final double MARCH_SLOPE_CAP = 4.0;

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

    /** Knee tolerance floor as a multiple of the seams' own rotation
     *  rate (v17.14, D155 — see optimalBridgeLength). */
    private static final double KNEE_SEAM_FLOOR = 1.2;

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
        alignPieceForBridge(bs, targetIdx, dist, alignYaw);
        boolean settled = settleLockstepAlign(bs, targetIdx, dist, alignYaw);
        // RE-KNEE (D249 review fix): under "Optimize length" the
        // caller's dist is the knee of the seams as the COMPILE
        // captured them, but a LOCKSTEP settle moves the target's
        // head dynamics (accB / jerkB / rollB / vB) and the knee IS
        // a function of the seam states (D82 / D155) — optSignature
        // stores 0 in the length slot while optLen is on, so once the
        // settle converged the next compile found sigEq true and the
        // stale knee stayed (probed: the gate fixture 9.00 → 8.00 m,
        // vDesign 24 optLen 9.00 → 6.50 m). ONE extra pass off the
        // settled seams: a contracting knee converges in one; a
        // still-moving knee keeps the second answer (bounded by
        // design — never a loop; the rest guarantee re-captures).
        if (settled && bs.optLen) {
            double d2 = optimalBridgeLength(bs, alignYaw);
            if (Math.abs(d2 - dist) > 1e-9) {
                dist = d2;
                bs.lastOptDist = d2;
                alignPieceForBridge(bs, targetIdx, dist, alignYaw);
                settleLockstepAlign(bs, targetIdx, dist, alignYaw);
            }
        }
        // SEPTIC-FALLBACK tangents (v17.15, D156): the fallback lens
        // used to be the raw requested length — when the march missed
        // its own placement (an unconverged extreme), 120 m tangents
        // over a 45 m chord ballooned the septic into the user's
        // "inverted vertical loop". Cap them at 1.5× the PLACED chord:
        // any march that lands keeps lens == dist (arc never exceeds
        // 1.5× chord on a sane path, and a landing march never reads
        // them anyway), so only the failure shapes change — to the
        // honest D75 full-gap-family sweep.
        TrackPiece tgt = pieces.get(targetIdx);
        double chord = new Vec3(tgt.x, tgt.y, tgt.z)
            .sub(bs.solvedEntry.pos()).length();
        double fb = Math.min(dist, Math.max(1, 1.5 * chord));
        bs.lenA = fb;
        bs.lenB = fb;
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
     *  REVERSED sections refuse (§5.3's rule — reversal itself is
     *  the convert-based rebuild now, v17.0 D134). The bakes below
     *  are PIECE-wide — on an orientation-conflicted piece the UI
     *  therefore isolates a section range first (v19.18, D247 — see
     *  isolateRange) and chains this op over it one section at a
     *  time; a pure speed conflict calls it directly, as always.
     *  Runs under withSeamsSuspended since v19.20 (D249): the fit
     *  already read compileForFit and the follow bake the same
     *  result, so nothing inside depends on the two lengths — the
     *  wrapper keeps the four resolve ops uniform. */
    public boolean makeGraphsReal(int pieceIdx, int secIdx, double dt,
                                  double spacing, double adaptTol) {
        if (pieceIdx < 0 || pieceIdx >= pieces.size()) return false;
        return withSeamsSuspended(pieceIdx,
            () -> makeGraphsRealBody(pieceIdx, secIdx, dt, spacing, adaptTol));
    }

    private boolean makeGraphsRealBody(int pieceIdx, int secIdx, double dt,
                                       double spacing, double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec.reversed() || sec instanceof BridgeSection) return false;
        TrackCompiler.Result r = compileForFit(dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        if (spanIdx >= r.spans().size()) return false;
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        // the too-short refusal runs BEFORE any bake (v19.18, D247 —
        // the leak: the follow/tilt bakes below mutated the piece and
        // THEN the `< 4` test returned false, so a refused first
        // preview run left a half-baked document with no undo entry;
        // the range flow's composite op inserts seams first and made
        // the hole visible). s/c sections have nothing to fit and
        // keep passing whatever their span length.
        if (!(sec instanceof StraightCurveSection)
                && sp.i1() - sp.i0() < 4) return false;
        // a STRICT-SEAM follower BAKES its follow rotation into the
        // stored placement (D136 — the D106 lesson: without it the
        // next compile applied the follow rotation a SECOND time on
        // top of the fitted world-frame lanes, the user's "launches
        // off into the distance"). The bake uses — and the fit reads —
        // THIS compile's samples: unlike a rigid tilt-bake, baking a
        // follow changes the generation frame, so a post-bake compile
        // would gravity re-solve into a different shape than the one
        // on screen, and "keep shape" must fit what the user sees.
        if (followerActive(pieceIdx)) {
            bakeFollow(pieceIdx, r);
        }
        // anchor at the SEAM sample — the very sample the reconcile
        // compares and writes at (v19.8, D237: the ops used to read
        // one dt INSIDE, so a fresh resolve moved by ~mm/s dust on
        // the next rebuild; the boundary read makes it a bitwise
        // fixed point). No previous span = the run entry: keep i0.
        int ipTrue = spanIdx > 0 ? r.spans().get(spanIdx - 1).i1()
                                 : sp.i0();
        double vTrue = Math.max(0.5, r.trueV()[ipTrue]);
        // TILTED piece (v16.1, D106 — the refreezeSpeed rationale):
        // the fit below reads the REAL (tilted) samples, so the tilt
        // must be BAKED or the compile would rigidly tilt the fitted
        // section a second time. Baking keeps the entry pose exactly;
        // the fitted lanes reproduce the real shape from that frame
        // (for s/c nothing to fit — its shape is gravity-free, so
        // baking alone leaves the geometry identical and the refrozen
        // speed closes the whole split).
        // bake only a design→true CONFLICT (v19.0, D222): a piece
        // whose design tier already matches its true orientation
        // generates AT the real frame — baking would swap the
        // canon-space arithmetic for decomposed-base bits (a
        // dust-level reshape for nothing)
        if (piece.orientConflict()) piece.bakeTilt();
        if (sec instanceof StraightCurveSection) {
            sec.vDesign(vTrue);
            sec.vPinned(false);   // resolved: re-join the speed flow
            return true;
        }
        LaneSection realFit = fitLanes(
            withEntry(r.samples(), sp, dt),
            vTrue, sec instanceof GeoSection, dt, spacing, adaptTol,
            Section.Domain.TIME, false, false, false, false,
            sec, sp);   // ctl-aware re-time (D236)
        // speed controls keep their TRACK position (v18.0, D160): a
        // TIME section's refit runs on the TRUE clock, so control
        // times re-map arc-invariantly (design t → arc → true t);
        // DISTANCE sections carry verbatim (the arc axis is shared).
        if (sec.domain() == Section.Domain.TIME
                && !sec.speedControls().isEmpty()) {
            double[][] tm = trueClockMap(r, sp);
            List<SpeedControl> part = SpeedControl.portion(
                sec.speedControls(), 0, Double.POSITIVE_INFINITY);
            for (SpeedControl c : part) {
                double a = mapCol(tm, 0, 1, c.x0);
                double b = mapCol(tm, 0, 1, c.x0 + c.len);
                if (c.complex && !c.lane.isEmpty()) {
                    double[] gaps = BezierBaker.segGaps(c.lane);
                    double cx0 = c.x0;
                    for (BezierBaker.Node n : c.lane) {
                        n.t = mapCol(tm, 0, 1, cx0 + n.t) - a;
                    }
                    BezierBaker.rescaleDvToGaps(c.lane, gaps);
                }
                c.x0 = a;
                c.len = Math.max(0.01, b - a);
            }
            realFit.speedCtls.addAll(part);
            for (double m : sec.speedMarks()) {
                realFit.speedMarks.add(mapCol(tm, 0, 1, m));
            }
        } else {
            carrySpeedCtls(sec, sp, r.samples(), realFit, 0,
                Double.POSITIVE_INFINITY);
        }
        piece.sections.set(secIdx, realFit);
        return true;
    }

    /** DESIGN → REAL with ABSOLUTE lanes CONVERTED to the current
     *  true shape (v19.11, D240 — user spec, his "Design to real
     *  not working" file): absolute elevation/heading/bank targets
     *  re-assert themselves whatever entry frame the design tier
     *  supplies (projectYp / the abs-bank projection), so the plain
     *  design := true write snapped an all-absolute piece straight
     *  back to the DESIGN shape after one integration step. This op
     *  refits the CHOSEN absolute lanes (convYp = elevation+heading,
     *  convBank = bank) from the CURRENT compiled — i.e. conflict-
     *  tilted, on-screen — curves, installs ONLY those lanes in
     *  place (every other lane, flag, control and pin untouched;
     *  rate lanes are entry-relative and re-solve correctly on
     *  their own), then writes design := true. The fit reads
     *  compileForFit BEFORE the write (the samples must be the
     *  tilted truth) with the section's own flags + the D238 piece
     *  heading reference. Sections too short to fit keep their
     *  authored lanes (they'd snap — the honest fallback). */
    public boolean designToRealConvert(int pieceIdx, boolean convYp,
            boolean convBank, double dt, double spacing,
            double adaptTol) {
        if (pieceIdx < 0 || pieceIdx >= pieces.size()) return false;
        TrackPiece piece = pieces.get(pieceIdx);
        TrackCompiler.Result r = compileForFit(dt);
        double headRef = pieceHeadDeg(r, pieceIdx);
        int spanBase = 0;
        for (int p = 0; p < pieceIdx; p++) {
            spanBase += pieces.get(p).sections.size();
        }
        for (int k = 0; k < piece.sections.size(); k++) {
            Section sec = piece.sections.get(k);
            int si = spanBase + k;
            if (!(sec instanceof LaneSection ls) || ls.reversed()
                    || ls.influenced() || si >= r.spans().size()) {
                continue;
            }
            boolean ypHon = ls instanceof GeoSection && ls.ypAbsolute
                && ls.yawPitchGlobal;
            boolean bankHon = ls.rollAbsolute && ls.rollGlobal;
            boolean doYp = convYp && ypHon;
            boolean doBank = convBank && bankHon;
            if (!doYp && !doBank) continue;
            TrackCompiler.Span sp = r.spans().get(si);
            if (sp.i1() - sp.i0() < 4) continue;   // too short to fit
            LaneSection fit = fitLanes(withEntry(r.samples(), sp, dt),
                ls.vDesign(), ls instanceof GeoSection, dt, spacing,
                adaptTol, ls.domain(), ls.rollGlobal, ls.yawPitchGlobal,
                ls.ratePerMeter, bankHon, ypHon, sec, sp, headRef);
            if (fit == null) continue;
            for (int L = 0; L < 3; L++) {
                if (!(L < 2 ? doYp : doBank)) continue;
                List<BezierBaker.Node> dst = ls.lanes.get(L);
                dst.clear();
                for (BezierBaker.Node nd : fit.lanes.get(L)) {
                    dst.add(nd.copy());
                }
            }
        }
        if (followerActive(pieceIdx)) {
            // a strict-seam FOLLOWER's "design := real" is the follow
            // bake (v19.18, D247 — the seam window's Design → Real
            // Orientation): baseOrient := the follow contract, both
            // tiers zero, follow + locks + held angles kept. It reads
            // the SAME compileForFit result the fit just read — the
            // D136 rule: baking a follow changes the generation frame,
            // so a second compile here would hand the refit lanes a
            // frame they were not fitted against.
            bakeFollow(pieceIdx, r);
        } else {
            piece.designPitchDeg = piece.pitchDeg;
            piece.designRollDeg = piece.rollDeg;
        }
        return true;
    }

    /** Compile with every piece's strict-seam SMOOTHING suspended
     *  (v17.2, D139) — the read for every lane-refit op. The smoothing
     *  blend rewrites the samples around a followed seam, so a fit
     *  that read them would re-author the BLENDED entry while its
     *  section still integrates from the follow-contract frame — the
     *  refit swung by the half-kink (gate 50h: 122 m of tail). Fits
     *  read the sharp design geometry; the still-active smoothing
     *  re-applies on top of the refit shape next compile, unchanged. */
    private TrackCompiler.Result compileForFit(double dt) {
        double[] stash = new double[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            stash[i] = pieces.get(i).sSmoothLen;
            pieces.get(i).sSmoothLen = 0;
        }
        try {
            return TrackCompiler.compile(this, dt);
        } finally {
            for (int i = 0; i < pieces.size(); i++) {
                pieces.get(i).sSmoothLen = stash[i];
            }
        }
    }

    /** RESOLVE UNDER SEAM SMOOTHING (v19.20, D249 — item C): run `op`
     *  with the strict-seam SMOOTHING of piece pieceIdx's OWN seam and
     *  of the seam right AFTER it (pieces[pieceIdx+1]'s) suspended —
     *  both lengths set to 0, restored BITWISE (the same doubles) in
     *  a finally, so every compile the op makes (makeSeamStrict's
     *  boundary cut, the follow bake) reads the same unsmoothed
     *  frames the fit / speed anchor read through compileForFit. The
     *  resolve ops used to MIX compiles (bakeFollow WITH smoothing,
     *  the anchor without) and the range flow refused smoothed seams
     *  outright; the smoothing re-applies on top of the resolved
     *  shape next compile. SCOPED, not compileForFit's all-off: an
     *  unrelated smoothed seam keeps its blend during the op. The
     *  restore goes by piece OBJECT, not index — isolateRange's
     *  splits move the successor down the list and the pieces born
     *  inside the op (TrackPiece's ctor default 0) own freshly
     *  inserted sharp seams and never inherit a stash. A piece
     *  removed by the op (absorbFollower) keeps its restored value
     *  harmlessly. Nested calls (the chained convert's step lambda
     *  around the model ops that suspend again) stash the inner 0
     *  and restore 0 — a no-op by construction. */
    public <T> T withSeamsSuspended(int pieceIdx,
                                    java.util.function.Supplier<T> op) {
        TrackPiece own = pieceIdx >= 0 && pieceIdx < pieces.size()
            ? pieces.get(pieceIdx) : null;
        TrackPiece next = pieceIdx >= 0 && pieceIdx + 1 < pieces.size()
            ? pieces.get(pieceIdx + 1) : null;
        double ownLen = own != null ? own.sSmoothLen : 0;
        double nextLen = next != null ? next.sSmoothLen : 0;
        if (own != null) own.sSmoothLen = 0;
        if (next != null) next.sSmoothLen = 0;
        try {
            return op.get();
        } finally {
            if (own != null) own.sSmoothLen = ownLen;
            if (next != null) next.sSmoothLen = nextLen;
        }
    }

    /** A section's samples for CONVERT extraction, INCLUDING its
     *  entry state (v16.6, D113 — the user's "STILL a 0.3 m miss on
     *  an overbanked turn"): a continuation section's span starts one
     *  dt AFTER its entry — the compiler drops the sample duplicating
     *  the previous exit (D84) — so span-only extraction lost 0.01 s
     *  of profile and every mid-chain convert re-fit the section one
     *  step SHORT: v·dt ≈ 0.2-0.3 m of arc, the "converges slightly
     *  off" no node density could fix (sole-section fixtures never
     *  showed it — their spans start at the ride's own t=0). The
     *  sample before i0 IS the boundary state — exact entry
     *  pose/arc/clock; its felt belongs to the previous section, so
     *  a deliberate value jump at the seam reads the old target for
     *  one row — a dt-local ripple replacing a whole-profile shift.
     *  The position-continuity guard keeps a DETACHED piece's run
     *  start (whose span is already complete, nothing was dropped)
     *  from pulling in the previous run's unrelated tail. */
    private static List<FVDIntegrator.Sample> withEntry(
            List<FVDIntegrator.Sample> all, TrackCompiler.Span sp,
            double dt) {
        int i0 = sp.i0();
        if (i0 > 0) {
            FVDIntegrator.Sample a = all.get(i0 - 1);
            FVDIntegrator.Sample b = all.get(i0);
            double step = 3 * Math.max(0.5, b.v()) * dt;
            if (b.pos().sub(a.pos()).length() < step) {
                double cosKink = Math.cos(Math.toRadians(2));
                FVDIntegrator.Sample c = all.get(i0 + 1);
                // DISCONTINUOUS seam test (v17.2, D137 — the user's
                // "the first node always lands between the previous
                // section's end and this section's value"): the D136
                // T-kink guard missed U-kinks (roll steps) and pure
                // VALUE steps — a frozen-shape strict seam or a
                // deliberate target jump keeps the frame continuous
                // while felt/curvature step, and the boundary row
                // (whose felt belongs to the PREVIOUS section) seeded
                // the fit with the old value: node 0 pulled off the
                // section's own value, area matching rippling the
                // miss down the lane at ANY density. Steps are read
                // against the section's own first interval so smooth
                // seams stay bitwise.
                boolean disc = a.T().dot(b.T()) < cosKink
                    || a.U().dot(b.U()) < cosKink
                    || jumpAt(a.Gv(), b.Gv(), c.Gv(), 0.02)
                    || jumpAt(a.Gl(), b.Gl(), c.Gl(), 0.02)
                    || jumpAt(a.v(), b.v(), c.v(), 0.05);
                if (!disc) {
                    i0--;   // smooth seam: the boundary sample IS the
                            // entry state (D113) — historic path
                } else {
                    // SYNTHESIZED entry (D137): keep the exact seam
                    // clock/arc/position (no D113 one-step-short
                    // regression) but take the DYNAMICS from this
                    // section's own side — frame back-rotated at the
                    // b→c body rate, felt and design speed linearly
                    // extrapolated. "What comes before shouldn't
                    // matter": the entry row becomes the section's
                    // own limit from the right.
                    Quat qb = Quat.fromFrame(b.T(), b.U());
                    Quat qc = Quat.fromFrame(c.T(), c.U());
                    // world-side step qs = qc·qb⁻¹ maps frame b's
                    // vectors onto frame c's; the entry frame is one
                    // inverse step back: qi = qs⁻¹ = qb·qc⁻¹
                    Quat qi = qb.mul(qc.conjugate());
                    Vec3 T0 = qi.rotate(b.T());
                    Vec3 U0 = qi.rotate(b.U());
                    FVDIntegrator.Sample synth = new FVDIntegrator.Sample(
                        a.t(), a.s(), a.pos(), T0, U0, U0.cross(T0),
                        Math.max(0.5, 2 * b.v() - c.v()),
                        2 * b.Gv() - c.Gv(), 2 * b.Gl() - c.Gl());
                    List<FVDIntegrator.Sample> out =
                        new ArrayList<>(sp.i1() - i0 + 2);
                    out.add(synth);
                    out.addAll(all.subList(i0, sp.i1() + 1));
                    return out;
                }
            }
        }
        return all.subList(i0, sp.i1() + 1);
    }

    /** Is a→b a value JUMP against the section's own b→c step? The
     *  6× + floor band keeps every smooth seam (where the two steps
     *  match to O(dt²)) on the historic path bitwise. */
    private static boolean jumpAt(double a, double b, double c,
                                  double floor) {
        return Math.abs(a - b) > floor + 6 * Math.abs(b - c);
    }

    /** "RE-FREEZE DESIGN SPEED" (v14.5, D97): the OTHER split
     *  resolution — keep the graphs and set the section's design
     *  speed to its true entry speed; the geometry re-derives from
     *  the same graphs at the new speed (the shape changes — the
     *  user's explicit trade). Reversed sections refuse (reversal
     *  is the convert-based rebuild now, v17.0 D134). The bakes are
     *  PIECE-wide — on an orientation-conflicted piece the UI runs
     *  refreezeRange over an isolated section range instead (v19.18,
     *  D247); a pure speed conflict calls this directly, as always.
     *  Runs under withSeamsSuspended since v19.20 (D249): the follow
     *  bake below used to read a compile WITH smoothing while the
     *  speed anchor (refreezeSpeedOnly's compileForFit) read without
     *  — on a smoothed follower the two disagreed by the blend. */
    public boolean refreezeSpeed(int pieceIdx, int secIdx, double dt) {
        if (pieceIdx < 0 || pieceIdx >= pieces.size()) return false;
        return withSeamsSuspended(pieceIdx,
            () -> refreezeSpeedBody(pieceIdx, secIdx, dt));
    }

    private boolean refreezeSpeedBody(int pieceIdx, int secIdx, double dt) {
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
        // bake only a design→true CONFLICT (D222): a matched design
        // tier already generates at the real frame — nothing to
        // resolve orientation-wise
        if (piece.orientConflict()) piece.bakeTilt();
        // a follower resolves FROM the real frame (D136, the D106
        // rule): bake the follow rotation so the reshape is
        // gravity-honest where the track really sits
        if (followerActive(pieceIdx)) {
            bakeFollow(pieceIdx, TrackCompiler.compile(this, dt));
        }
        return refreezeSpeedOnly(pieceIdx, secIdx, dt);
    }

    /** The SPEED HALF of the resolve op alone (v19.13, D242 — user
     *  spec for the initial-speed dialog's "Set Design to Real":
     *  "purely adjust the starting speed"): vDesign := the true seam
     *  speed + un-pin so it follows naturally — an orientation
     *  conflict (tilt / follow) is left EXACTLY as it stands. With
     *  no such conflict this IS the whole resolve op. */
    public boolean refreezeSpeedOnly(int pieceIdx, int secIdx, double dt) {
        Section sec = pieces.get(pieceIdx).sections.get(secIdx);
        if (sec.reversed() || sec instanceof BridgeSection) return false;
        TrackCompiler.Result r = compileForFit(dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        // the SEAM sample — the reconcile's own anchor read (D237,
        // see makeGraphsReal)
        sec.vDesign(Math.max(0.5, r.trueV()[spanIdx > 0
            ? r.spans().get(spanIdx - 1).i1()
            : r.spans().get(spanIdx).i0()]));
        sec.vPinned(false);   // re-join the speed flow
        return true;
    }

    // ---------- RANGE-SCOPED orientation resolve (v19.18, D247) ----------
    // An orientation conflict is a PIECE property (the design→true
    // tilt, or a follower's held seam angles vs its design tier), so
    // today's per-section resolve ops bake the WHOLE piece: every
    // sibling section re-solves against gravity at the real frame —
    // sections the user never asked about reshape. The range flow
    // (user spec) isolates sections [k..m] of the piece as their OWN
    // piece behind inserted STRICT seams first: the upstream part
    // keeps its conflict as its own piece, the remainder keeps it as
    // an all-locked follower (local shape bitwise by the canonical
    // pair, world placement to Euler dust — the D71/D224 split rule),
    // and only the range resolves. A trailing BRIDGE is never in a
    // range and never gets a seam (it re-solves live, as bridges do).

    /** Index of the piece's last NON-BRIDGE section, −1 if none. */
    public int lastNonBridge(int pieceIdx) {
        if (pieceIdx < 0 || pieceIdx >= pieces.size()) return -1;
        List<Section> secs = pieces.get(pieceIdx).sections;
        for (int i = secs.size() - 1; i >= 0; i--) {
            if (!(secs.get(i) instanceof BridgeSection)) return i;
        }
        return -1;
    }

    /** ISOLATE sections [k..m] of a piece as their own piece (D247):
     *  (i) a strict seam AFTER m when m is not the last non-bridge
     *  section (the remainder [m+1..] — trailing bridge included —
     *  becomes an all-locked follower keeping its conflict), then
     *  (ii) a strict seam BEFORE k when k > 0 (the range becomes
     *  piece pieceIdx+1). (i) runs first so m+1 is still in the
     *  original numbering. Returns the RANGE piece's index, −1 when
     *  a seam could not be made (an uncompiled boundary — the UI
     *  pre-checks every span, so a partial state is not expected;
     *  callers hold an undo snapshot regardless). k == m == the
     *  last section of a one-section piece = no seams at all.
     *  Both cuts read the withSeamsSuspended compile since v19.20
     *  (D249) — the inserted seams land on the same unsmoothed
     *  frames the range's fit / speed anchor read; the original
     *  seams' smoothing comes back (by object) around them. */
    public int isolateRange(int pieceIdx, int k, int m, double dt) {
        if (pieceIdx < 0 || pieceIdx >= pieces.size()) return -1;
        return withSeamsSuspended(pieceIdx,
            () -> isolateRangeBody(pieceIdx, k, m, dt));
    }

    private int isolateRangeBody(int pieceIdx, int k, int m, double dt) {
        int last = lastNonBridge(pieceIdx);
        if (k < 0 || k > m || m > last) return -1;
        if (m < last && makeSeamStrict(pieceIdx, m + 1, dt) < 0) return -1;
        if (k > 0) {
            int rp = makeSeamStrict(pieceIdx, k, dt);
            return rp < 0 ? -1 : rp;
        }
        return pieceIdx;
    }

    /** SET DESIGN TO REAL over an isolated RANGE piece (D247): the
     *  refreezeSpeed prologue on the range piece (bake the tilt if the
     *  tiers conflict, then the follow if it follows — both PIECE-
     *  level, so every range section re-solves from the real frame
     *  exactly as today's op makes a whole piece do), the SPEED half
     *  on its first section (vDesign := the true seam speed, D237's
     *  anchor) and an UN-PIN of the other non-bridge sections so the
     *  reconcile re-derives them from the new compile. The interior
     *  sections deliberately get NO refreezeSpeedOnly of their own —
     *  a fresh compileForFit per section would write pre-reconcile
     *  stale values the exact settle rewrites anyway (D200's fixed
     *  point). Equivalent per section to today's refreezeSpeed
     *  restricted to the range (RangeResolveGate g2/g4). Runs under
     *  withSeamsSuspended since v19.20 (D249) — the refreezeSpeed
     *  rationale: the follow bake and the speed anchor read the
     *  same unsmoothed seam frames. */
    public boolean refreezeRange(int rp, double dt) {
        if (rp < 0 || rp >= pieces.size()) return false;
        return withSeamsSuspended(rp, () -> refreezeRangeBody(rp, dt));
    }

    private boolean refreezeRangeBody(int rp, double dt) {
        TrackPiece piece = pieces.get(rp);
        if (piece.sections.isEmpty()) return false;
        Section first = piece.sections.get(0);
        if (first.reversed() || first instanceof BridgeSection) return false;
        if (piece.orientConflict()) piece.bakeTilt();
        if (followerActive(rp)) {
            bakeFollow(rp, TrackCompiler.compile(this, dt));
        }
        if (!refreezeSpeedOnly(rp, 0, dt)) return false;
        for (int i = 1; i < piece.sections.size(); i++) {
            Section s = piece.sections.get(i);
            if (s instanceof BridgeSection || s.reversed()) continue;
            s.vPinned(false);
        }
        return true;
    }

    /** ABSORB a just-converted follower back into the converted piece
     *  before it (D247 — the chained "Convert Real to Design" re-joins
     *  the range one section at a time): the follow link drops and
     *  the sections append as a plain continuation — NO pin (the
     *  fitted section already starts at the true seam speed, so the
     *  reconcile's re-derivation is a dust-level no-op), and any
     *  follower AFTER it (the still-unconverted rest of the range, or
     *  the remainder) now follows the merged piece. False when the
     *  pair is not an active follower and its predecessor. */
    public boolean absorbFollower(int prevIdx, int followerIdx) {
        if (followerIdx != prevIdx + 1 || !followerActive(followerIdx)) {
            return false;
        }
        pieces.get(followerIdx).followPrev = false;
        attachPiece(prevIdx, followerIdx);
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
     *  rule). PUBLIC since v19.20 (D249, item S1) so StripGate pins the
     *  strip's display-axis snap round trip through the editor's own
     *  map read. */
    public static double mapCol(double[][] map, int from, int to, double x) {
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

    /** Carry SPEED CONTROLS + marks from a section onto its refit
     *  twin (v18.0, D160): the source range [lo, hi] (source-native
     *  axis) clips EXACTLY (SpeedControl.portion — complex graphs de
     *  Casteljau at the cut), then, when the domains differ, every
     *  coordinate maps through the span's compiled {t,s} map into the
     *  output domain (the hardware keeps its track position; the
     *  refit reproduces the speeds, so the mapped clock matches to
     *  fit residual). Same-domain carries are exact copies. dst must
     *  be freshly built (its lists start empty). */
    static void carrySpeedCtls(Section src, TrackCompiler.Span sp,
                               List<FVDIntegrator.Sample> samples,
                               Section dst, double lo, double hi) {
        if (!dst.speedControllable()) return;
        List<SpeedControl> part =
            SpeedControl.portion(src.speedControls(), lo, hi);
        List<Double> marks =
            SpeedControl.marksPortion(src.speedMarks(), lo, hi);
        if (part.isEmpty() && marks.isEmpty()) return;
        boolean fromDist = src.domain() == Section.Domain.DISTANCE;
        boolean toDist = dst.domain() == Section.Domain.DISTANCE;
        if (fromDist != toDist && sp != null && samples != null) {
            double[][] map = sectionMap(samples, sp, false);
            int fc = fromDist ? 1 : 0, tc = toDist ? 1 : 0;
            double base = mapCol(map, fc, tc, lo);
            for (SpeedControl c : part) {
                double a = mapCol(map, fc, tc, lo + c.x0) - base;
                double b = mapCol(map, fc, tc, lo + c.x0 + c.len) - base;
                if (c.complex && !c.lane.isEmpty()) {
                    double[] gaps = BezierBaker.segGaps(c.lane);
                    double cx0 = c.x0;
                    for (BezierBaker.Node n : c.lane) {
                        n.t = mapCol(map, fc, tc, lo + cx0 + n.t)
                            - base - a;
                    }
                    BezierBaker.rescaleDvToGaps(c.lane, gaps);
                }
                // the decel graph remaps identically (v18.45, D217)
                if (c.complex && !c.decelLane.isEmpty()) {
                    double[] gaps = BezierBaker.segGaps(c.decelLane);
                    double cx0 = c.x0;
                    for (BezierBaker.Node n : c.decelLane) {
                        n.t = mapCol(map, fc, tc, lo + cx0 + n.t)
                            - base - a;
                    }
                    BezierBaker.rescaleDvToGaps(c.decelLane, gaps);
                }
                c.x0 = a;
                c.len = Math.max(0.01, b - a);
            }
            for (int i = 0; i < marks.size(); i++) {
                marks.set(i, mapCol(map, fc, tc, lo + marks.get(i)) - base);
            }
        }
        dst.speedControls().addAll(part);
        dst.speedMarks().addAll(marks);
    }

    /** Which lanes a same-mode same-domain convert KEEPS verbatim
     *  (v19.9, D238 — user rule, generalizing v18.2/D169's roll-only
     *  case): a lane whose MEANING is untouched by the requested
     *  flag flips would only re-fit onto itself — at best converging
     *  back to the original — so its authored nodes carry bit-exact
     *  and the conversion residual lives in the lanes that actually
     *  change meaning. Lanes 0/1 keep when the yaw/pitch axis AND
     *  behavior are unchanged and a rate-unit flip doesn't reach
     *  them (force G targets aren't rates; absolute angles aren't
     *  either — D213/D237). The roll lane keeps symmetrically (an
     *  absolute bank lane ignores rate units the same way). Null =
     *  nothing keeps: cross-mode/domain, influenced, or a FULLY
     *  IDENTICAL flag tuple — the identity convert is the deliberate
     *  re-author-at-density tool. */
    static boolean[] laneKeepMap(LaneSection src, boolean toGeo,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs, boolean ypAbs) {
        if (rollAbs) rollG = true;              // forced pairs — the
        ypAbs = ypAbs && toGeo;                 // convertToLanes
        if (ypAbs) ypG = true;                  // normalization
        if (src.influenced() || toGeo != (src instanceof GeoSection)
                || outDom != src.domain() || src.reversed()) {
            return null;
        }
        boolean srcYpAbs = src.ypAbsolute && src.yawPitchGlobal;
        boolean srcRollAbs = src.rollAbsolute && src.rollGlobal;
        if (rollG == src.rollGlobal && ypG == src.yawPitchGlobal
                && rateM == src.ratePerMeter && rollAbs == srcRollAbs
                && ypAbs == srcYpAbs) {
            return null;   // identity convert = re-author everything
        }
        boolean keep01 = ypG == src.yawPitchGlobal && ypAbs == srcYpAbs
            && (rateM == src.ratePerMeter || ypAbs || !toGeo);
        boolean keep2 = rollG == src.rollGlobal && rollAbs == srcRollAbs
            && (rateM == src.ratePerMeter || rollAbs);
        if (!keep01 && !keep2) return null;
        return new boolean[]{ keep01, keep01, keep2 };
    }

    /** Deep-copy the kept lanes of the source over the fitted ones —
     *  locks, handles and all (the D169 preserve, per-lane). */
    private static void preserveLanes(LaneSection src, LaneSection conv,
                                      boolean[] keep) {
        for (int L = 0; L < 3; L++) {
            if (keep == null || !keep[L]) continue;
            List<BezierBaker.Node> dst = conv.lanes.get(L);
            dst.clear();
            for (BezierBaker.Node n : src.lanes.get(L)) dst.add(n.copy());
        }
    }

    /** makeGraphsReal's clock carry (v18.0): a TIME section refit at
     *  the TRUE speed keeps each control at its ARC position — old
     *  design time → arc → the true clock (trapezoid over trueV).
     *  Rows {tOld, tTrue} local to the span. */
    private static double[][] trueClockMap(TrackCompiler.Result r,
                                           TrackCompiler.Span sp) {
        int i0 = sp.i0(), i1 = Math.min(sp.i1(), r.samples().size() - 1);
        double[][] map = new double[i1 - i0 + 1][2];
        double tt = 0;
        for (int i = i0; i <= i1; i++) {
            if (i > i0) {
                double ds = r.samples().get(i).s()
                    - r.samples().get(i - 1).s();
                double vm = Math.max(0.5,
                    (r.trueV()[i] + r.trueV()[i - 1]) / 2);
                tt += ds / vm;
            }
            map[i - i0][0] = r.samples().get(i).t() - sp.t0();
            map[i - i0][1] = tt;
        }
        return map;
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
        TrackCompiler.Result r = compileForFit(dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 4) return false;   // too short to measure
        LaneSection conv = fitLanes(
            withEntry(r.samples(), sp, dt),
            ls.vDesign(), ls instanceof GeoSection, dt, spacing, adaptTol,
            toDist ? Section.Domain.DISTANCE : Section.Domain.TIME,
            false, false, false, false, sec, sp);   // ctl-aware (D236)
        conv.vPinned = sec.vPinned();
        // speed controls follow the domain flip (v18.0, D160)
        carrySpeedCtls(sec, sp, r.samples(), conv, 0,
            Double.POSITIVE_INFINITY);
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
     *  second-order, so a couple of passes settle).
     *
     *  TWO MODES since v18.28 (D200): the FAST pass (exact=false —
     *  the live-gesture path) keeps the 1e-3 write tube + the D197
     *  cascade carry, cheap but path-dependent inside the tube; the
     *  EXACT pass (exact=true — every at-rest rebuild) writes on BIT
     *  difference and disables the carry, so each pass finalizes the
     *  next section of the TRIANGULAR chain (a section's derived
     *  entry depends only on sections before it) and the settled
     *  speeds are a pure function of the graphs — a hand-reverted
     *  edit lands back on its original bits, not within ±1e-3 of
     *  them. Terminates in at most chain-depth passes by the
     *  triangular structure; the intended D101 anchor memory (which
     *  of design/true a resolved section chases) is the one
     *  deliberate piece of history that stays. */
    public boolean reconcileChainedSpeeds(TrackCompiler.Result r) {
        return reconcileChainedSpeeds(r, false);
    }

    public boolean reconcileChainedSpeeds(TrackCompiler.Result r,
                                          boolean exact) {
        boolean changed = false;
        int spanIdx = 0;
        for (TrackPiece piece : pieces) {
            // CASCADE CARRY (v18.25, D197): when THIS pass just moved
            // a section's design speed, the stale compile's end sample
            // for it is one rewrite behind — prevEnd carries the move
            // through the frozen energy profile (end² − entry² is a
            // property of the old geometry+losses), so ONE pass walks
            // the WHOLE chain instead of advancing one section per
            // compile (the user's 17-section file needed 9 compiles
            // and the old 3-pass rebuild cap froze it mid-cascade —
            // the "track changes on every click" report). NaN =
            // previous section untouched: the stale sample is exact
            // and the historic path stays bitwise.
            double prevEnd = Double.NaN;
            for (int k = 0; k < piece.sections.size(); k++, spanIdx++) {
                double nextPrev = Double.NaN;
                double carryIn = prevEnd;
                prevEnd = Double.NaN;
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
                // floored where the record was historically clamped
                // (v18.6, D175 — samples carry honest speeds now)
                double vIn = Double.isNaN(carryIn)
                    ? Math.max(0.1, r.samples().get(ip).v())
                    : carryIn;
                // REAL-ANCHORED continuation (v15.2, D101): a section
                // lined up with a drifted neighbor's TRUE curves (the
                // transition-to-real state) keeps chasing the REAL
                // seam speed — re-freezing it back onto the design
                // profile would silently undo the resolution. Which
                // anchor it follows is read off which speed its
                // vDesign already sits nearer (0.2 = the UI's
                // DRIFT_EPS). AMPLIFICATION-AWARE since v19.8 (D237,
                // the user's "conflict not getting resolved" file): a
                // seam gap dv is an ENERGY offset ~2·vTrue·dv, which
                // reads as dv·vTrue/vMin at the section's slowest
                // point — the quantity the ≠v badge tested until
                // v19.17 (the D106 whole-span max). Since v19.18
                // (D247) the BADGE tests the RAW entry gap only (an
                // interior-only split is an ORIENTATION conflict, ≠o)
                // while this reconcile KEEPS its amplified gate — the
                // two measure different things by design: a sub-0.2
                // seam gap with a slow interior can split the true
                // strokes with no ≠v, and this gate still recognizes
                // the user's resolution there. The old constant entry test
                // let every gap in (0.2/amp, 0.2) trip the badge
                // downstream while the reconcile REFUSED to recognize
                // the user's resolution and snapped it back each
                // rebuild — the unresolvable phantom. Flat sections
                // (vMin ≈ vTrue) keep the historic test bitwise.
                if (ip < r.trueV().length) {
                    // floored where trueV was historically clamped
                    // (v18.6, D175 — the array records honest values)
                    double vTrue = Math.max(0.1, r.trueV()[ip]);
                    double vMin = vTrue;
                    if (spanIdx < r.spans().size()) {
                        TrackCompiler.Span own = r.spans().get(spanIdx);
                        int e = Math.min(own.i1(), r.trueV().length - 1);
                        for (int i = own.i0(); i <= e; i++) {
                            vMin = Math.min(vMin,
                                Math.max(0.1, r.trueV()[i]));
                        }
                    }
                    if (Math.abs(vTrue - vIn) * (vTrue / vMin) > 0.2
                            && Math.abs(sec.vDesign() - vTrue)
                               < Math.abs(sec.vDesign() - vIn)) {
                        vIn = vTrue;
                    }
                }
                if (exact) {
                    // EXACT mode (D200): land ON the derived bits; no
                    // carry — a prediction is never bit-final, and
                    // without it each pass finalizes the next section
                    // of the triangular chain, which is the
                    // termination proof. NaN guard: the fast tube's
                    // |diff| test is structurally NaN-immune, the bit
                    // test is not — never launder a NaN into the doc
                    if (!Double.isNaN(vIn)
                            && Double.doubleToLongBits(sec.vDesign())
                               != Double.doubleToLongBits(vIn)) {
                        sec.vDesign(vIn);
                        changed = true;
                    }
                } else if (Math.abs(sec.vDesign() - vIn) > 1e-3) {
                    double vOld = sec.vDesign();
                    sec.vDesign(vIn);
                    changed = true;
                    // predict THIS section's design end for the next
                    // one: same heights/losses, new entry energy —
                    // second-order wrong where a speed control
                    // saturates or the shape is speed-dependent; the
                    // rebuild loop's verify compile corrects those
                    if (spanIdx < r.spans().size()) {
                        double endStale = Math.max(0.1, r.samples()
                            .get(r.spans().get(spanIdx).i1()).v());
                        nextPrev = Math.max(0.1, Math.sqrt(Math.max(0,
                            vIn * vIn + endStale * endStale
                            - vOld * vOld)));
                    }
                }
                prevEnd = nextPrev;
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
        // fallback tangents capped like optimizeBridge (v17.15, D156):
        // a candidate whose march fails the render gate is scored as
        // the sane full-gap-family septic, not a ballooned one — the
        // ladder must rank the geometry the apply would produce
        double chord = virt.pose().pos().sub(bs.solvedEntry.pos())
            .length();
        double fb = Math.min(d, Math.max(1, 1.5 * chord));
        BridgeSection probe = virtProbe(bs, virt, fb, fb);
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
        // SEAM-RATE FLOOR (v17.14, D155): a candidate no rougher than
        // KNEE_SEAM_FLOOR × the seams' own frame-rotation rate is
        // long enough. A CREST's cost decays ~1/L indefinitely — both
        // end curvatures keep pumping pitch the mid-span hump must
        // undo, so the mid rate asymptotes near the seam rate and the
        // relative-only knee ran away (88 m over a 45 m crest gap,
        // the user's "massive hill"). Matched/straight seams keep
        // their 1 m collapse (their costs sit at/below the floor from
        // the first candidate), and valley knees stay governed by the
        // slope term, which towers over the floor at short lengths.
        double vsA = Math.max(0.5, bs.vDesign);
        double vsB = Math.max(0.5, bs.vB > 0 ? bs.vB : bs.vDesign);
        double rateA = Math.hypot(bs.accA.length() * vsA, bs.rollA);
        double rateB = Math.hypot(bs.accB.length() * vsB,
            bs.vB > 0 ? bs.rollB : 0);
        double tol = Math.max(cmin * 1.10 + 0.5, KNEE_SEAM_FLOOR
            * Math.toDegrees(Math.max(rateA, rateB)));
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
     *  old blend), plus a WORLD-vertical curvature hump (30u²(1−u)²
     *  since D153) integrating to `resPitch` radians of pitch (0 =
     *  pure blend). sa/sb per seamState. meta (nullable) returns
     *  {unwrapped end pitch rad, pole crossings} — v17.14, D155.
     *  Returns the end state. */
    private static Vec3[] march(Vec3 pos, Vec3 T, Vec3 U, double d,
                                double[] sa, double[] sb, double resPitch,
                                double resYaw, double resTwist,
                                List<Vec3> path,
                                List<double[]> spine, double[] meta) {
        int n = (int) Math.min(40_000, Math.max(8, Math.ceil(d / 0.05)));
        double h = d / n;
        Vec3[] st = { pos, T.normalize(),
            U.sub(T.scale(U.dot(T))).normalize() };
        // UNWRAPPED-PITCH TRACKING (v17.14, D155): accumulate the
        // vertical-plane angle continuously, counting POLE crossings
        // (the horizontal heading reverses in one step only while T
        // is near vertical — per-step rotations are far too small
        // anywhere else). Past an ODD number of crossings the
        // measured asin runs MIRRORED (θ_true = ±π − asin), so both
        // the per-step deltas and the crossing direction flip with
        // the parity; dLast remembers the raw measured delta as the
        // travel-direction memory through the pole. meta = {unwrapped
        // end pitch rad, crossings} for marchEnd's branch-correct
        // residual measures.
        double aPrev = Math.asin(Math.max(-1, Math.min(1, st[1].y())));
        double pAcc = aPrev;
        double lgx = st[1].x(), lgz = st[1].z();
        double dLast = 0;
        int cross = 0;
        if (path != null) path.add(st[0]);
        if (spine != null) {
            spine.add(spineRow(0, st,
                contDeriv(st, 0, sa, sb, resPitch, resYaw, resTwist, d)[1]));
        }
        for (int i = 0; i < n; i++) {
            double u0 = (double) i / n;
            double uh = (i + 0.5) / n;
            double u1 = (double) (i + 1) / n;
            Vec3[] k1 = contDeriv(st, u0, sa, sb, resPitch, resYaw, resTwist, d);
            Vec3[] s2 = stepped(st, k1, h / 2);
            Vec3[] k2 = contDeriv(s2, uh, sa, sb, resPitch, resYaw, resTwist, d);
            Vec3[] s3 = stepped(st, k2, h / 2);
            Vec3[] k3 = contDeriv(s3, uh, sa, sb, resPitch, resYaw, resTwist, d);
            Vec3[] s4 = stepped(st, k3, h);
            Vec3[] k4 = contDeriv(s4, u1, sa, sb, resPitch, resYaw, resTwist, d);
            for (int j = 0; j < 3; j++) {
                st[j] = st[j].add(k1[j]
                    .add(k2[j].scale(2)).add(k3[j].scale(2)).add(k4[j])
                    .scale(h / 6));
            }
            st[1] = st[1].normalize();
            st[2] = st[2].sub(st[1].scale(st[2].dot(st[1]))).normalize();
            double aNew = Math.asin(Math.max(-1, Math.min(1, st[1].y())));
            double h2t = st[1].x() * st[1].x() + st[1].z() * st[1].z();
            double parity = (cross & 1) == 0 ? 1 : -1;
            if (h2t > 1e-18 && Math.abs(aPrev) > 1.0
                    && lgx * st[1].x() + lgz * st[1].z() < 0) {
                // heading flipped while T was near vertical: the true
                // angle passed the pole and asin reflected. Magnitude
                // is the pole-relative sum; direction continues the
                // last true travel direction (parity-corrected raw
                // delta; entering deeper toward the near pole when
                // there is no memory yet).
                double mag = Math.PI - Math.abs(aNew) - Math.abs(aPrev);
                double dir = dLast != 0 ? parity * Math.signum(dLast)
                    : Math.signum(aPrev) * parity;
                pAcc += (dir >= 0 ? mag : -mag);
                cross++;
                dLast = 0;
            } else {
                pAcc += parity * (aNew - aPrev);
                if (aNew != aPrev) dLast = aNew - aPrev;
            }
            if (h2t > 1e-18) { lgx = st[1].x(); lgz = st[1].z(); }
            aPrev = aNew;
            if (path != null) path.add(st[0]);
            if (spine != null) {
                spine.add(spineRow((i + 1) * h, st,
                    contDeriv(st, u1, sa, sb, resPitch, resYaw, resTwist,
                        d)[1]));
            }
        }
        if (meta != null) {
            meta[0] = pAcc;
            meta[1] = cross;
        }
        return st;
    }

    /** The march's end pitch on the RIGHT BRANCH (v17.14, D155): with
     *  no pole crossings this is Math.asin(endT.y) VERBATIM — every
     *  clean march keeps its historic bit-identical measure. With
     *  crossings, asin's reflection parity is (−1)^c and the missing
     *  π-grid offset is snapped from the march's tracked angle sum
     *  (accurate to integration noise, far below π/2 — the exact
     *  end-frame asin stays the precision anchor). */
    private static double unwrappedEndPitch(Vec3 endT, double[] meta) {
        double aEnd = Math.asin(Math.max(-1, Math.min(1, endT.y())));
        if (meta[1] == 0) return aEnd;
        double refl = ((int) meta[1] & 1) == 0 ? aEnd : -aEnd;
        return refl + Math.rint((meta[0] - refl) / Math.PI) * Math.PI;
    }

    private static double wrapPi(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }

    /** The march's residual-twist measure (v17.11, D150): the
     *  TARGET's bank-vs-horizon minus the march end's (rad, wrapped)
     *  — each about its OWN heading, so the measure is INVARIANT
     *  under the yaw rotation the placement applies after the march.
     *  (A direct U→uB angle was not: the placement's yaw rotated uB,
     *  the re-measured twist re-aimed the blend mid-span, and the
     *  render march missed its own placement by ~1° — permanent
     *  septic fallback.) Near-vertical either side = 0; the render's
     *  residual quintic still closes whatever bank remains. */
    private static double bankGap(Vec3 tM, Vec3 uM, Vec3 tB, Vec3 uB) {
        double bM = FVDIntegrator.bankOfTU(tM, uM);
        double bB = FVDIntegrator.bankOfTU(tB, uB);
        if (Double.isNaN(bM) || Double.isNaN(bB)) return 0;
        double d2 = bB - bM;
        while (d2 > Math.PI) d2 -= 2 * Math.PI;
        while (d2 < -Math.PI) d2 += 2 * Math.PI;
        return d2;
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

    /** Instance fit wrappers (v17.0, D133): converts INSIDE the
     *  document thread the coaster's friction into the re-timed
     *  target profile — new call sites must use these, not the static
     *  tower, or the fit goes friction-blind. */
    LaneSection fitLanes(List<FVDIntegrator.Sample> s, double vDesign,
                         boolean toGeo, double dt, double spacing,
                         double adaptTol) {
        return fitLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            Section.Domain.TIME);
    }

    LaneSection fitLanes(List<FVDIntegrator.Sample> s, double vDesign,
                         boolean toGeo, double dt, double spacing,
                         double adaptTol, Section.Domain outDom) {
        return fitLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, false, false, false, false);
    }

    LaneSection fitLanes(List<FVDIntegrator.Sample> s, double vDesign,
                         boolean toGeo, double dt, double spacing,
                         double adaptTol, Section.Domain outDom,
                         boolean toRollG, boolean toYpG, boolean toRateM,
                         boolean toRollAbs) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric());
    }

    /** + ABSOLUTE yaw/pitch target (v19.8, D237); ypHeadDeg = the
     *  piece-relative heading reference (v19.9, D238 — NaN =
     *  self-relative, right for a piece FIRST section). */
    LaneSection fitLanes(List<FVDIntegrator.Sample> s, double vDesign,
                         boolean toGeo, double dt, double spacing,
                         double adaptTol, Section.Domain outDom,
                         boolean toRollG, boolean toYpG, boolean toRateM,
                         boolean toRollAbs, boolean toYpAbs,
                         double ypHeadDeg) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric(),
            null, 0, false, toYpAbs, ypHeadDeg);
    }

    /** fitLanes with the SOURCE section's speed controls fed into
     *  the re-time march (v19.7, D236): src/sp locate the controls'
     *  native axis. Sections without controls fall through bitwise. */
    LaneSection fitLanes(List<FVDIntegrator.Sample> s, double vDesign,
                         boolean toGeo, double dt, double spacing,
                         double adaptTol, Section.Domain outDom,
                         boolean toRollG, boolean toYpG, boolean toRateM,
                         boolean toRollAbs, Section src,
                         TrackCompiler.Span sp) {
        SpeedControl.Ctl ctl = src != null && src.speedControllable()
            ? SpeedControl.Ctl.of(src.speedControls()) : null;
        boolean sd = src != null
            && src.domain() == Section.Domain.DISTANCE;
        double off = sp == null ? 0 : (sd ? sp.s0() : sp.t0());
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric(),
            ctl, off, sd);
    }

    /** The D236 ctl-aware instance path + ABSOLUTE yaw/pitch (D237)
     *  + the piece-relative heading reference (v19.9, D238). */
    LaneSection fitLanes(List<FVDIntegrator.Sample> s, double vDesign,
                         boolean toGeo, double dt, double spacing,
                         double adaptTol, Section.Domain outDom,
                         boolean toRollG, boolean toYpG, boolean toRateM,
                         boolean toRollAbs, boolean toYpAbs, Section src,
                         TrackCompiler.Span sp, double ypHeadDeg) {
        SpeedControl.Ctl ctl = src != null && src.speedControllable()
            ? SpeedControl.Ctl.of(src.speedControls()) : null;
        boolean sd = src != null
            && src.domain() == Section.Domain.DISTANCE;
        double off = sp == null ? 0 : (sd ? sp.s0() : sp.t0());
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric(),
            ctl, off, sd, toYpAbs, ypHeadDeg);
    }

    /** The ABSOLUTE-heading reference of a piece (v19.9, D238): its
     *  FIRST compiled sample's reported-left heading (deg) — the
     *  zero every piece-relative absolute heading lane speaks from.
     *  Vertical entries scan forward within the piece for the first
     *  defined heading; NaN = none / not compiled (self-relative
     *  fallback). */
    double pieceHeadDeg(TrackCompiler.Result r, int pieceIdx) {
        if (r == null || pieceIdx < 0 || pieceIdx >= pieces.size()) {
            return Double.NaN;
        }
        int k = 0;
        for (int p = 0; p < pieceIdx; p++) {
            k += pieces.get(p).sections.size();
        }
        int nSec = pieces.get(pieceIdx).sections.size();
        if (nSec == 0 || k >= r.spans().size()) return Double.NaN;
        int i0 = r.spans().get(k).i0();
        int i1 = Math.min(
            r.spans().get(Math.min(k + nSec - 1, r.spans().size() - 1)).i1(),
            r.samples().size() - 1);
        for (int i = i0; i <= i1; i++) {
            double h = FVDIntegrator.headingOfT(r.samples().get(i).T());
            if (!Double.isNaN(h)) return Math.toDegrees(h);
        }
        return Double.NaN;
    }

    /** pieceHeadDeg without a compile in hand: the STORED-placement
     *  reference — the chain heading the compile generates at
     *  (pieceCanon's psi, negated to the reported sense). Equal to
     *  the measured first sample to float dust on plain pieces; a
     *  re-aimed follower / tilted piece differs by its rigid tilt —
     *  the same world-vs-chain corner every global-axis fit has. */
    double pieceHeadDeg(int pieceIdx) {
        TrackPiece p = pieces.get(pieceIdx);
        double psi = p.canonOrient != null
            ? p.canonPsiDeg + p.yawDeg
            : TrackCompiler.headingDeg(p.baseOrient) + p.yawDeg;
        return Math.IEEEremainder(-psi, 360);
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
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, false, false);
    }

    /** Measured GLOBAL rates clamp here (°/s) — near-vertical track
     *  has no defined heading/bank and the finite differences can
     *  spike; the cap keeps a requested bake finite instead of
     *  crawling or crashing (v16.2, user rule: guard, don't refuse). */
    private static final double GLOBAL_RATE_CAP = 3600;

    /** Convert with target AXIS FLAGS too (v16.2 — the "bake
     *  global/local" ops): global-axis lanes take the measured
     *  world-angle rates — heading/elevation for yaw/pitch, the
     *  bank-vs-horizon rate for roll — instead of body rates, so the
     *  fitted section reproduces the same shape under the other
     *  semantics (to the fit residual; vertical stretches are the
     *  documented edge case). */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, false);
    }

    /** Convert with target RATE UNITS too (v16.6, D115): toRateM =
     *  the rate lanes come out in °/m — each sample's rate divides by
     *  the target profile's speed there, so the fitted per-meter
     *  section reproduces the same shape (rate·v round-trips). Force
     *  G lanes are targets, not rates — untouched. */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG,
                                             boolean toRateM) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, false);
    }

    /** Convert with an ABSOLUTE-ROLL target too (v16.7, D118): the
     *  roll lane comes out as the measured BANK ANGLE (°) per sample
     *  — global roll only (forced on). Vertical samples hold the
     *  previous bank (no defined angle there). */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG,
                                             boolean toRateM,
                                             boolean toRollAbs) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs,
            FVDIntegrator.Friction.NONE);
    }

    /** Convert with the document FRICTION (Phase 3, v17.0 D133): the
     *  re-timed target profile sheds the coaster's rolling + air
     *  losses along the extracted samples' arc, so a fitted section
     *  reproduces the shape when compiled under the same friction.
     *  Every in-document convert routes here via fitLanes; the
     *  loss-free tower above stays for probes and gates. */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG,
                                             boolean toRateM,
                                             boolean toRollAbs,
                                             FVDIntegrator.Friction fric) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric,
            null, 0, false);
    }

    /** Convert with the SOURCE section's SPEED CONTROLS (v19.7,
     *  D236 — the user's "influence bake and conversion not working
     *  with a speed section"): the re-time march below rebuilds the
     *  target's design profile from energy + friction, and a
     *  controlled section's actual profile is DRIVEN — a 29 m/s
     *  lift over an energy dip made the rebuilt clock near-stall,
     *  stretching the fit's axis ~4× and scaling the felt
     *  back-conversion by (v/vG)²: an 11 m shape shift on the
     *  user's file. The march now consumes the same Ctl the compile
     *  does (drive + snap, the passD twins), keyed by the SOURCE-
     *  native coordinate (srcOff = the span's t0/s0). null =
     *  bitwise. */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG,
                                             boolean toRateM,
                                             boolean toRollAbs,
                                             FVDIntegrator.Friction fric,
                                             SpeedControl.Ctl srcCtl,
                                             double srcOff,
                                             boolean srcDist) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric,
            srcCtl, srcOff, srcDist, false);
    }

    /** Convert with an ABSOLUTE-YAW/PITCH target too (v19.8, D237 —
     *  the toRollAbs twin for lanes 0/1, GEO + global yaw/pitch
     *  only): lane 0 = the measured ELEVATION angle (°), lane 1 =
     *  the HEADING (°, reported-left, UNWRAPPED across samples —
     *  heading crosses ±180 on any half-turn, unlike bank).
     *  Vertical samples hold the last defined heading. */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG,
                                             boolean toRateM,
                                             boolean toRollAbs,
                                             FVDIntegrator.Friction fric,
                                             SpeedControl.Ctl srcCtl,
                                             double srcOff,
                                             boolean srcDist,
                                             boolean toYpAbs) {
        return convertToLanes(s, vDesign, toGeo, dt, spacing, adaptTol,
            outDom, toRollG, toYpG, toRateM, toRollAbs, fric,
            srcCtl, srcOff, srcDist, toYpAbs, Double.NaN);
    }

    /** + the ABSOLUTE-heading reference (v19.9, D238): ypHeadDeg =
     *  the PIECE's entry heading (deg, reported-left) — the zero of
     *  a piece-relative absolute heading lane. NaN = fall back to
     *  the section's own first defined heading (self-relative — the
     *  reference a piece FIRST section has anyway). */
    public static LaneSection convertToLanes(List<FVDIntegrator.Sample> s,
                                             double vDesign, boolean toGeo,
                                             double dt, double spacing,
                                             double adaptTol,
                                             Section.Domain outDom,
                                             boolean toRollG, boolean toYpG,
                                             boolean toRateM,
                                             boolean toRollAbs,
                                             FVDIntegrator.Friction fric,
                                             SpeedControl.Ctl srcCtl,
                                             double srcOff,
                                             boolean srcDist,
                                             boolean toYpAbs,
                                             double ypHeadDeg) {
        if (toRollAbs) toRollG = true;   // the angle IS the target
        toYpAbs = toYpAbs && toGeo;      // geo lanes only (D237)
        if (toYpAbs) toYpG = true;
        int n = s.size();
        double vD = Math.max(1, vDesign);
        double y0 = s.get(0).pos().y();
        double[] tG = new double[n];
        double[] vGs = new double[n];
        double[][] rate = new double[n][3];
        double prevVG = Math.max(0.5, vD);
        double lossG = 0;
        for (int i = 0; i < n; i++) {
            FVDIntegrator.Sample sm = s.get(i);
            if (i > 0) {
                double dsF = sm.s() - s.get(i - 1).s();
                double xPrev = srcDist ? s.get(i - 1).s() - srcOff
                                       : s.get(i - 1).t() - srcOff;
                double drv = srcCtl == null ? Double.NaN
                    : srcCtl.drive(xPrev, prevVG);
                if (!Double.isNaN(drv)) {
                    // driven interval (the passD twin): loss' is the
                    // control's ∓2gA — friction absorbed by the drive
                    lossG += drv * dsF;
                } else if (!fric.off()) {
                    double f0 = fric.dLossDs(prevVG);
                    double vp = Math.max(0.5, FVDIntegrator.speedAt(
                        sm.pos(), vD, y0, lossG + f0 * dsF));
                    lossG += (f0 + fric.dLossDs(vp)) / 2 * dsF;
                }
                if (srcCtl != null) {
                    double vA = Math.max(0.5, FVDIntegrator.speedAt(
                        sm.pos(), vD, y0, lossG));
                    double dl = srcCtl.snap(xPrev, prevVG, vA,
                        !Double.isNaN(drv));
                    if (dl != 0) lossG += dl;
                }
            }
            double vG = Math.max(0.5,
                FVDIntegrator.speedAt(sm.pos(), vD, y0, lossG));
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
            // REPORTED yaw curvature (+ = left, D185; the D184 and
            // D185 negations cancel here)
            double kR = FVDIntegrator.G0 * (sm.Gl() + sm.R().y())
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
                    / FVDIntegrator.G0 - sm.R().y();   // D184+D185
                val[i][2] = rate[i][2];
            }
        }
        // GLOBAL-axis target lanes: ANALYTIC world-angle rates since
        // v16.3 (the D99 lesson re-applied — the v16.2 finite
        // differences of heading/elevation/bank low-passed the rate
        // corners exactly like the old frame differencing, the
        // user's "converges to a slightly different spot"): Ω is
        // known per sample (the κ-identity body pitch/yaw + measured
        // roll, already in rate[] at the target profile), and the
        // Euler-rate relations give the world rates exactly —
        //   θ' = (Ω×T)·ŷ / cosθ,   ψ' = (T.z·T'x − T.x·T'z)/horiz²,
        //   φ' = Ω·T + ψ'·sinθ.
        // Vertical samples (cosθ → 0) have no heading/bank: capped.
        if (toRollG || (toGeo && toYpG)) {
            for (int i = 0; i < n; i++) {
                FVDIntegrator.Sample sm = s.get(i);
                double[] wr = worldRates(sm,
                    rate[i][0], rate[i][1], rate[i][2]);
                if (toGeo && toYpG) {
                    val[i][0] = capRate(wr[0]);
                    val[i][1] = capRate(wr[1]);
                }
                if (toRollG) val[i][2] = capRate(wr[2]);
            }
        }
        // ABSOLUTE-ROLL target (v16.7, D118): lane 2 = the measured
        // bank angle per sample; vertical stretches hold the last
        // defined bank. UNWRAPPED sample to sample since v19.9
        // (D238 sweep find — the heading extraction's rule): a bank
        // crossing ±180 (barrel roll) used to jump 360 between
        // adjacent knots and the fitted lane swept back the wrong
        // way; no-crossing tracks are bitwise (the correction is 0).
        if (toRollAbs) {
            double lastBank = Double.NaN;
            for (int i = 0; i < n; i++) {
                FVDIntegrator.Sample sm = s.get(i);
                double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
                if (!Double.isNaN(b)) {
                    double deg = Math.toDegrees(b);
                    if (!Double.isNaN(lastBank)) {
                        deg += 360 * Math.round((lastBank - deg) / 360);
                    }
                    lastBank = deg;
                }
                val[i][2] = Double.isNaN(lastBank) ? 0 : lastBank;
            }
        }
        // ABSOLUTE-YAW/PITCH target (v19.8, D237): lane 0 = the
        // elevation angle, lane 1 = the heading — UNWRAPPED sample to
        // sample (the D120 nearest-representative rule; heading
        // crosses ±180 on any half-turn); vertical stretches hold the
        // last defined heading
        if (toYpAbs) {
            // PIECE-RELATIVE heading (v19.9, D238 — superseding
            // D237's section-entry-relative): the compile shifts an
            // absolute heading lane by the section entry's
            // piece-relative heading, so the lane holds heading
            // MINUS the PIECE entry's — the caller supplies the
            // reference; the first defined sample seeds at the
            // representative nearest it (the entry value lands in
            // (−180, 180] of the piece frame), then unwraps sample
            // to sample. NaN reference = self-relative (D237).
            double lastRaw = Double.NaN, h0 = 0;
            for (int i = 0; i < n; i++) {
                FVDIntegrator.Sample sm = s.get(i);
                val[i][0] = Math.toDegrees(
                    FVDIntegrator.elevationOfT(sm.T()));
                double hd = FVDIntegrator.headingOfT(sm.T());
                if (!Double.isNaN(hd)) {
                    double deg = Math.toDegrees(hd);
                    if (Double.isNaN(lastRaw)) {
                        h0 = Double.isNaN(ypHeadDeg) ? deg : ypHeadDeg;
                        deg += 360 * Math.round((h0 - deg) / 360);
                        lastRaw = deg;
                    } else {
                        deg += 360 * Math.round((lastRaw - deg) / 360);
                        lastRaw = deg;
                    }
                }
                val[i][1] = Double.isNaN(lastRaw) ? 0 : lastRaw - h0;
            }
        }
        // PER-METER targets (v16.6, D115): rate values divide by the
        // target profile's speed per sample — the integrator consumes
        // value × v, so the round trip is exact where the fit is.
        // NEVER the ABSOLUTE-ROLL lane (v18.41, D213): a bank lane is
        // DEGREES, not a rate — the integrators consume it verbatim
        // under every flag (laneRatePM exempts it the same way), and
        // dividing it here flattened every °/m + absolute convert's
        // banking to angle/v (~1/25th at 25 m/s), the user's "always
        // generated erroneous results".
        if (toRateM) {
            for (int i = 0; i < n; i++) {
                double vG = Math.max(0.5, vGs[i]);
                // ... and never the ABSOLUTE yaw/pitch lanes either
                // (v19.8, D237 — the same D213 rule for lanes 0/1)
                if (toGeo && !toYpAbs) {
                    val[i][0] /= vG;
                    val[i][1] /= vG;
                }
                if (!toRollAbs) val[i][2] /= vG;
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
            // EXACT duration since v17.11 (D151): the integrator's
            // closing step lands on ANY authored end now (D147), so
            // the old round-onto-the-dt-grid workaround — which cut
            // or held up to 5 ms of shape per fit — is retired.
            dur = Math.max(0.1, tG[n - 1]);
        }
        List<List<BezierBaker.Node>> lanes = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) {
            // area-match weight (v16.5, D111): the integral a lane
            // error feeds into the turned angle — 1 for time-axis
            // rates, an extra 1/v per arc-axis lookup (∫rate ds/v)
            // and per force inversion (ω = g(G−axis.y)/v). PER-METER
            // rate lanes consume value × v: ∫val·v dt on a time axis
            // (w = v, wPow −1) and exactly ∫val ds on an arc axis
            // (w = 1) — v16.6, D115.
            boolean rateLane = toGeo || c == 2;
            int wPow;
            if (toRateM && rateLane) {
                wPow = outDom == Section.Domain.DISTANCE ? 0 : -1;
            } else {
                wPow = (outDom == Section.Domain.DISTANCE ? 1 : 0)
                    + (!toGeo && c < 2 ? 1 : 0);
            }
            List<BezierBaker.Node> lane = adaptTol > 0
                ? fitLaneAdaptive(xG, val, c, dur, adaptTol, xScale)
                : fitLaneUniform(xG, val, c, dur,
                    Math.max(0.01 * xScale, spacing), xScale);
            // an ABSOLUTE lane IS the integrated quantity —
            // pointwise fidelity is the whole story, area matching
            // would chase a meaningless integral (v16.7, D118;
            // yp lanes join in v19.8, D237)
            if (!((toRollAbs && c == 2) || (toYpAbs && c < 2))) {
                matchSegmentAreas(lane, xG, val, c, vGs, wPow,
                    outDom == Section.Domain.DISTANCE ? 0.05 : 0.01);
            }
            lanes.add(lane);
        }
        LaneSection out = toGeo ? new GeoSection(lanes, vD)
                                : new ForceSection(lanes, vD);
        out.domain(outDom);
        out.rollGlobal = toRollG;
        out.yawPitchGlobal = toGeo && toYpG;
        out.ratePerMeter = toRateM;
        out.rollAbsolute = toRollAbs;
        out.ypAbsolute = toYpAbs;
        return out;
    }

    private static double capRate(double r) {
        if (Double.isNaN(r)) return 0;
        return Math.max(-GLOBAL_RATE_CAP, Math.min(GLOBAL_RATE_CAP, r));
    }

    /** WORLD-angle rates {elevation', heading', bank'} (deg/s) at a
     *  sample, ANALYTIC from its body rates (deg/s) — the Euler-rate
     *  relations the global-axis lanes author in (v16.3, D109):
     *  Ω = yaw·U − pitch·R + roll·T; θ' = (Ω×T)·ŷ/cosθ; ψ' from the
     *  atan2(−T.z, T.x) quotient; φ' = Ω·T − ψ'·sinθ (the transport
     *  term is exactly why local roll 0 drifts bank through pitched
     *  turns; MINUS — our up-Y world flips the aircraft-convention
     *  sign, probed numerically against d(bank)/dt to 2 decimals).
     *  Vertical track: heading'/bank' come back NaN (no
     *  defined heading or bank there — callers cap or refuse). Shared
     *  by the bake extraction and the editor's world-rate matcher. */
    static double[] worldRates(FVDIntegrator.Sample sm, double pitchDeg,
                               double yawDeg, double rollDeg) {
        Vec3 T = sm.T(), U = sm.U(), R = sm.R();
        // yawDeg arrives in the REPORTED sense (+yaw = left turn,
        // v18.14 D185) — the internal U-rotation negates it; the
        // returned heading' negates back on the way out
        Vec3 om = U.scale(-Math.toRadians(yawDeg))
            .add(R.scale(-Math.toRadians(pitchDeg)))
            .add(T.scale(Math.toRadians(rollDeg)));
        Vec3 tp = om.cross(T);
        double horiz2 = T.x() * T.x() + T.z() * T.z();
        double cosTh = Math.sqrt(Math.max(horiz2, 0));
        // NEAR-VERTICAL HOLD-OFF (v16.3, the 42c lesson): the exact
        // ψ'/φ' amplify by 1/cos²θ toward vertical, and a capped spike
        // in a BANK-RATE lane integrates into a garbage bank target
        // downstream of the loop (measured: 121 m of track error).
        // Past the threshold the rates read NaN — bakes hold the
        // angle (rate 0, matching the integrator's own vertical
        // hold-off), matchers refuse with the reason. TIGHTENED
        // 0.15 → 0.05 (~81° → ~87°) in v18.10 (D181, user spec:
        // resulting graphs flattened to zero too early; the 3600 °/s
        // GLOBAL_RATE_CAP still bounds what a capped spike can
        // inject, and the display paths draw 0 at NaN as before).
        boolean ok = cosTh > 0.05;
        double thp = ok ? tp.y() / cosTh : Double.NaN;
        double psp = ok
            ? (T.z() * tp.x() - T.x() * tp.z()) / horiz2 : Double.NaN;
        double php = ok ? om.dot(T) - psp * T.y()   // sinθ = T.y
                        : Double.NaN;
        return new double[]{ Math.toDegrees(thp), Math.toDegrees(-psp),
            Math.toDegrees(php) };
    }

    /** BAKE a section's shape into the other AXIS semantics (v16.2 —
     *  user spec): same mode, same domain, the target flags, via the
     *  convert engine (density preview drives spacing/adaptTol).
     *  Shape preserved to the fit residual — vertical stretches are
     *  the accepted edge case (rates cap instead of crashing).
     *  Influenced sections capture; reversed refuse. */
    public boolean bakeAxes(int pieceIdx, int secIdx, boolean toRollG,
                            boolean toYpG, double dt, double spacing,
                            double adaptTol) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (!(sec instanceof LaneSection ls) || sec.reversed()) return false;
        TrackCompiler.Result r = compileForFit(dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 4) return false;
        LaneSection conv = fitLanes(
            withEntry(r.samples(), sp, dt),
            ls.vDesign(), ls instanceof GeoSection, dt, spacing, adaptTol,
            ls.domain(), toRollG, toYpG, false, false, sec, sp);
        // per-lane preserve against the fit's ACTUAL targets (v19.9,
        // D238 — the D169 roll-only rule generalized; the keep map
        // gets the same rateM=false / abs=false the fitLanes call
        // above targets, which also FIXES a pre-existing bug: a
        // roll-only bake on an ABSOLUTE-yaw/pitch section used to
        // copy its ANGLE lanes verbatim into the rate-flagged
        // output — degrees consumed as °/s)
        preserveLanes(ls, conv, laneKeepMap(ls, ls instanceof GeoSection,
            ls.domain(), toRollG, toYpG, false, false, false));
        conv.vPinned = sec.vPinned();
        // same domain + clock: speed controls carry verbatim (v18.0)
        carrySpeedCtls(sec, sp, r.samples(), conv, 0,
            Double.POSITIVE_INFINITY);
        piece.sections.set(secIdx, conv);
        return true;
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


    /** Per-segment INTEGRAL matching (v16.5, D111 — the user's "all
     *  converts converge to a point that isn't quite the original"):
     *  nodes on the data with the data's slopes still let the curve
     *  BETWEEN nodes integrate slightly off the data's area — and
     *  what a rate lane integrates into is the turned angle, so
     *  signed per-segment residuals accumulate into an end-pose miss
     *  that no pointwise density fully kills (measured: the
     *  pitch-lane integral error predicts the default layout's
     *  convert end miss almost linearly). The fix nudges the NODE
     *  VALUES: handles keep their slopes and 1/3 fracs (the
     *  closed-form property survives), and a segment's weighted area
     *  responds to its end values through the shift bases g0/g1, so
     *  the constraints form the bidiagonal system c0·δv_k +
     *  c1·δv_{k+1} = E_k — solved LEAST-NORM (δv = Aᵀ(AAᵀ)⁻¹E, AAᵀ
     *  tridiagonal, Thomas O(N)): the cumulative angle is then
     *  pinned at EVERY node, which position accuracy needs, at the
     *  smallest possible nudges — 1e-3-unit scale at high densities.
     *  E_k integrates (data − BAKED curve)·w — the very polyline the
     *  integrator consumes, so the 0.05-grid bias cancels too — with
     *  w = 1/v^p because the angle a lane error feeds is ∫err·g/v dt
     *  for force targets and ∫err/v ds on an arc axis (the
     *  unweighted first cut REGRESSED adaptive force fits: long
     *  segments span real speed change). ITERATED with DAMPED
     *  re-baked passes: the smooth-basis response moments drift off
     *  what the keyframe-sampled polyline actually does as segments
     *  shrink toward the bake cell — a few percent at 2-3 cells per
     *  segment, PAST 100% at one cell, where the undamped iteration
     *  measurably diverged (a 0.05 s carpet blew up to 177 m).
     *  Scaling each pass's solution by 0.7 keeps the fixed point and
     *  contracts for any response error in (−57%, +186%); every
     *  pass's measurement competes on peak |cumulative error| and
     *  the BEST state ships (the raw fit is pass 0's entry), so a
     *  lane the matcher cannot help keeps its pointwise fit. Probed
     *  and REJECTED along the way: a handle-fraction knob (symmetric
     *  fractions have zero area authority wherever the end slopes
     *  are parallel); forward substitution with a pinned start (its
     *  alternating homogeneous mode random-walks over long carpets —
     *  −89 g at the tail of a 269-segment fit); smoothing/centering/
     *  capping that walk (each biased some fixture worse than no
     *  matching at all). KNOWN FLOOR: adaptive carpets of violent
     *  FORCE layouts keep ~0.1-0.2 m of through-the-solution residual
     *  (407 m torture layout) that no lane-local metric can rank —
     *  fine UNIFORM spacing is the max-fidelity setting there. */
    private static void matchSegmentAreas(List<BezierBaker.Node> lane,
            double[] tG, double[][] val, int c, double[] vG, int wPow,
            double bakeGrid) {
        int nn = lane.size();
        if (nn < 2) return;
        // SUB-GRID carpets skip the pass: nodes packed finer than the
        // 0.05 bake grid can't be told apart by the baked polyline —
        // attribution AND response are noise there, and both chasing
        // it and constraining coarsened node subsets measurably
        // WORSENED a 0.02 s carpet. Spacing AT the grid still
        // converges under the damping+guard (measured 3× better than
        // unmatched), so only genuinely finer carpets bail; the
        // pointwise fit alone already behaves at such densities.
        double[] gaps = new double[nn - 1];
        for (int k = 0; k + 1 < nn; k++) {
            gaps[k] = lane.get(k + 1).t - lane.get(k).t;
        }
        java.util.Arrays.sort(gaps);
        if (gaps[gaps.length / 2] < 0.6 * bakeGrid) return;
        int ns = nn - 1;
        double[] eA = new double[ns];
        double[] c0A = new double[ns];
        double[] c1A = new double[ns];
        double[] md = new double[nn];    // ridge system diagonal
        double[] mu1 = new double[nn];   // ... super-diagonal
        double[] rr = new double[nn];    // ... right-hand side
        double[] dv = new double[nn];
        double[] best = new double[nn];
        for (int k = 0; k < nn; k++) best[k] = lane.get(k).val;
        double bestCum = Double.MAX_VALUE;
        double prevWorst = Double.MAX_VALUE;
        // POINTWISE GUARD (v19.6, D235 — the user's "oscillations in
        // baking influence", reproduced on ANY sharply-stepped data
        // at coarse uniform spacing): the cumulative-area metric
        // alone PREFERS an alternating solution wherever segments
        // genuinely can't hold the data's area (a 0.2 s step inside
        // 0.15 s segments) — ±2 g node ringing through FLAT
        // stretches shipped as the "best" state. A pass's state may
        // now win only while its BAKED CURVE stays pointwise close
        // to the data relative to the raw fit's own miss — ringing
        // swings the curve far off the data where it is flat, while
        // a legitimate coarse-fit area correction keeps the curve
        // inside the data's own envelope (the D111 coarse
        // convergence survives, gated 40d).
        double dLo = Double.MAX_VALUE, dHi = -Double.MAX_VALUE;
        for (double[] row : val) {
            dLo = Math.min(dLo, row[c]);
            dHi = Math.max(dHi, row[c]);
        }
        double devSlack = 0.02 * Math.max(1e-6, dHi - dLo);
        double rawCurveDev = -1;   // pass 0 (the raw fit) records it
        // 9th pass measures the 8th application; nothing unmeasured
        for (int pass = 0; pass < 9; pass++) {
            // bake THIS lane exactly as the compiler will (the
            // domain's own grid — 0.01 time / 0.05 distance since
            // v17.11 D151; every convert lane ends at dur, so the
            // grid matches the final 3-lane bake)
            List<List<BezierBaker.Node>> tmp = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) tmp.add(lane);
            List<FVDIntegrator.Keyframe> kfs = BezierBaker.bake(tmp, bakeGrid);
            double worst = 0;
            double cum = 0, maxCum = 0;
            for (int k = 0; k < ns; k++) {
                BezierBaker.Node a = lane.get(k), b = lane.get(k + 1);
                if (b.t - a.t < 1e-9) {   // can't occur in fits
                    eA[k] = 0;
                    c0A[k] = c1A[k] = 1e-6;
                    continue;
                }
                double[] ec = segMoments(kfs, tG, val, c, vG, wPow,
                    a.t, b.t);
                eA[k] = ec[0];
                c0A[k] = ec[1];
                c1A[k] = Math.max(1e-12, ec[2]);
                worst = Math.max(worst, Math.abs(ec[0]));
                cum += ec[0];
                maxCum = Math.max(maxCum, Math.abs(cum));
            }
            // BEST-STATE tracking (v16.5, D111): the position-relevant
            // metric is the peak |cumulative error| across nodes (an
            // interior angle bound the lever arm multiplies) — the
            // matcher can lower every segment's error while
            // REDISTRIBUTING that cumulative worse (measured 0.03 →
            // 0.19 m on an adaptive force→geo fit). Every pass's
            // measurement competes; the best state is what ships, so
            // a lane the matcher can't help keeps its pointwise fit.
            // Since v19.6 (D235) a state must ALSO pass the pointwise
            // curve guard above to win.
            double curveDev = 0;
            for (FVDIntegrator.Keyframe kf : kfs) {
                double t = kf.t();
                if (t < lane.get(0).t || t > lane.get(nn - 1).t) continue;
                curveDev = Math.max(curveDev,
                    Math.abs(kf.Gv() - valAtT(tG, val, c, t)));
            }
            if (rawCurveDev < 0) rawCurveDev = curveDev;   // pass 0
            if (maxCum < bestCum
                    && curveDev <= rawCurveDev * 1.3 + devSlack) {
                bestCum = maxCum;
                for (int k = 0; k < nn; k++) best[k] = lane.get(k).val;
            }
            if (worst >= prevWorst) break;   // stalled/diverging
            if (worst < 1e-9) break;         // converged
            if (pass == 8) break;            // measurement-only tail
            // RIDGE least-squares (v19.7, D236 — superseding the
            // EXACT least-norm constraint solve): on data with
            // features finer than a segment, exact per-segment areas
            // are only satisfiable by node-to-node ALTERNATION
            // (measured −0.93 lag-1 autocorrelation of the node
            // deviations — the user's oscillating bakes; the
            // positionally-good coarse solutions rang the same way).
            // Solve min ‖A·δv − E‖² + μ‖δv‖² + λ‖D₁·δv‖² instead:
            // the first-difference penalty suppresses exactly the
            // alternating mode, areas are matched as far as SMOOTH
            // corrections allow, and the cumulative best-state
            // competition (+ the pointwise curve guard) still judges
            // what ships. AᵀA + λD₁ᵀD₁ + μI is tridiagonal SPD —
            // the same O(N) Thomas as before.
            double cs2 = 0;
            for (int k = 0; k < ns; k++) {
                cs2 += c0A[k] * c0A[k] + c1A[k] * c1A[k];
            }
            cs2 = Math.max(1e-12, cs2 / ns);
            double lam = 1.0 * cs2;
            double mu = 1e-4 * cs2;
            for (int j = 0; j < nn; j++) {
                double d = mu;
                if (j < ns) d += c0A[j] * c0A[j];
                if (j > 0) d += c1A[j - 1] * c1A[j - 1];
                if (j > 0) d += lam;
                if (j < nn - 1) d += lam;
                md[j] = d;
                if (j < nn - 1) {
                    mu1[j] = (j < ns ? c0A[j] * c1A[j] : 0) - lam;
                }
                rr[j] = (j < ns ? c0A[j] * eA[j] : 0)
                    + (j > 0 ? c1A[j - 1] * eA[j - 1] : 0);
            }
            for (int j = 1; j < nn; j++) {   // symmetric Thomas
                double m = mu1[j - 1] / md[j - 1];
                md[j] -= m * mu1[j - 1];
                rr[j] -= m * rr[j - 1];
            }
            dv[nn - 1] = rr[nn - 1] / md[nn - 1];
            for (int j = nn - 2; j >= 0; j--) {
                dv[j] = (rr[j] - mu1[j] * dv[j + 1]) / md[j];
            }
            for (int k = 0; k < nn; k++) {
                lane.get(k).val += 0.7 * dv[k];
            }
            prevWorst = worst;
        }
        // ship the best measured state (pass 0 measured the raw fit,
        // so "no matching" always competes)
        for (int k = 0; k < nn; k++) lane.get(k).val = best[k];
    }

    /** One segment's weighted error + response moments {E, c0, c1}:
     *    E  = ∫ (data(x) − baked(x)) · w(x) dx     over [x0, x1]
     *    c0 = ∫ g0(u) · w(x) dx,   c1 = ∫ g1(u) · w(x) dx
     *  with u = (x−x0)/(x1−x0) (1/3 fracs keep the time cubic linear),
     *  g0 = (1−u)²(1+2u) / g1 = u²(3−2u) the value-cubic's response
     *  to shifting the left/right node value (handles riding along),
     *  and w = 1/vG(x)^p. Trapezoid on the UNION of the data and bake
     *  grids — both curves are polylines there. */
    private static double[] segMoments(List<FVDIntegrator.Keyframe> kfs,
            double[] tG, double[][] val, int c, double[] vG, int wPow,
            double x0, double x1) {
        double e = 0, c0 = 0, c1 = 0;
        int i = 0, j = 0;
        while (i < tG.length && tG[i] <= x0) i++;
        while (j < kfs.size() && kfs.get(j).t() <= x0) j++;
        double x = x0;
        double[] f = integrand(kfs, tG, val, c, vG, wPow, x0, x0, x1);
        while (x < x1) {
            double xd = i < tG.length ? tG[i] : Double.MAX_VALUE;
            double xk = j < kfs.size() ? kfs.get(j).t() : Double.MAX_VALUE;
            double xn = Math.min(x1, Math.min(xd, xk));
            if (xn > x) {
                double[] fn = integrand(kfs, tG, val, c, vG, wPow,
                    xn, x0, x1);
                double hh = (xn - x) / 2;
                e += (f[0] + fn[0]) * hh;
                c0 += (f[1] + fn[1]) * hh;
                c1 += (f[2] + fn[2]) * hh;
                f = fn;
                x = xn;
            }
            if (xd <= xn) i++;
            if (xk <= xn) j++;
        }
        return new double[]{ e, c0, c1 };
    }

    /** Integrand triple {(data−baked)·w, g0·w, g1·w} at x. */
    private static double[] integrand(List<FVDIntegrator.Keyframe> kfs,
            double[] tG, double[][] val, int c, double[] vG, int wPow,
            double x, double x0, double x1) {
        double w = 1;
        if (wPow != 0) {
            double v = Math.max(0.5, gridAt(tG, vG, x));
            w = wPow == -1 ? v : wPow == 1 ? 1 / v : 1 / (v * v);
        }
        double u = Math.max(0, Math.min(1, (x - x0) / (x1 - x0)));
        double g0 = (1 - u) * (1 - u) * (1 + 2 * u);
        return new double[]{
            (valAtT(tG, val, c, x) - kfVal(kfs, x)) * w,
            g0 * w,
            (1 - g0) * w };   // g1 = u²(3−2u) = 1 − g0
    }

    /** Linear read of the baked Gv channel at x (clamped ends — the
     *  bake writes one lane's values into all three slots here). */
    private static double kfVal(List<FVDIntegrator.Keyframe> kfs, double x) {
        if (x <= kfs.get(0).t()) return kfs.get(0).Gv();
        int hi = kfs.size() - 1;
        if (x >= kfs.get(hi).t()) return kfs.get(hi).Gv();
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (kfs.get(mid).t() <= x) lo = mid; else hi = mid;
        }
        double u = (x - kfs.get(lo).t())
            / Math.max(1e-12, kfs.get(hi).t() - kfs.get(lo).t());
        return kfs.get(lo).Gv() + u * (kfs.get(hi).Gv() - kfs.get(lo).Gv());
    }

    /** Linear read of a paired (xs, ys) grid at x (clamped ends). */
    private static double gridAt(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        int hi = xs.length - 1;
        if (x >= xs[hi]) return ys[hi];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] <= x) lo = mid; else hi = mid;
        }
        return ys[lo] + (ys[hi] - ys[lo])
            * (x - xs[lo]) / Math.max(1e-12, xs[hi] - xs[lo]);
    }

    /** ADAPTIVE fit (v14.0, D90 — the dialog's "Optimal placement"):
     *  start from the two endpoints and repeatedly insert a node at
     *  the worst-fitting sample until the lane tracks its data within
     *  tol × (the lane's value range). Node values come from the
     *  data; slopes are node-scale local differences (window bounded
     *  by the neighbor gaps — the D87 lesson); handles stay C1 at 1/3
     *  fracs, so the segment time-cubic is exactly linear and the
     *  curve evaluates in closed form (the final area-match pass only
     *  nudges node VALUES — the property survives, v16.5 D111). A
     *  flat lane is just its two endpoints; each lane gets only the
     *  nodes IT needs. */
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
            // per-segment SIGNED deviation area + peak (v16.5, D111):
            // a long segment can pass the pointwise test while its
            // deviation accumulates real turned-angle error inside —
            // the post-fit area match zeroes each segment's TOTAL,
            // but the correction bump's within-segment SHAPE is free,
            // and its residual moment grows with h². Non-flat
            // segments therefore cap their LENGTH (midpoint nodes),
            // and any segment whose mean signed deviation beats half
            // the tolerance splits too; flat stretches keep their
            // bare-endpoint economy.
            double[] segE = new double[lane.size() - 1];
            double[] segPeak = new double[lane.size() - 1];
            double fePrev = 0;
            int segPrev = -1;
            double tPrev = 0;
            int seg = 0;
            for (int i = 0; i < n; i++) {
                double t = Math.min(tG[i], dur);
                while (seg < lane.size() - 2
                        && lane.get(seg + 1).t <= t) seg++;
                double fe = fittedVal(lane.get(seg), lane.get(seg + 1), t)
                    - val[i][c];
                if (seg == segPrev) {
                    segE[seg] += (fe + fePrev) / 2 * (t - tPrev);
                }
                segPeak[seg] = Math.max(segPeak[seg], Math.abs(fe));
                fePrev = fe;
                segPrev = seg;
                tPrev = t;
                // respect the editor's INSERT_GAP (0.02 s / 0.2 m):
                // never propose a node on top of an existing one
                if (t - lane.get(seg).t < 0.02 * xScale
                        || lane.get(seg + 1).t - t < 0.02 * xScale) continue;
                double err = Math.abs(fe);
                if (err > worst) {
                    worst = err;
                    worstT = t;
                }
            }
            if (worstT < 0) {
                // pointwise satisfied — worst offending segment by
                // its mean deviation, then by capped length
                double worstMean = eps * 0.5;
                for (int k = 0; k + 1 < lane.size(); k++) {
                    double h = lane.get(k + 1).t - lane.get(k).t;
                    if (h < 2.5 * 0.02 * xScale) continue;   // no room
                    double mean = Math.abs(segE[k]) / h;
                    if (mean > worstMean) {
                        worstMean = mean;
                        worstT = (lane.get(k).t + lane.get(k + 1).t) / 2;
                    }
                }
                if (worstT < 0) {
                    double worstH = 0.6 * xScale;
                    for (int k = 0; k + 1 < lane.size(); k++) {
                        double h = lane.get(k + 1).t - lane.get(k).t;
                        if (h > worstH && segPeak[k] > 0.1 * eps) {
                            worstH = h;
                            worstT = (lane.get(k).t
                                + lane.get(k + 1).t) / 2;
                        }
                    }
                }
                if (worstT < 0) break;
            }
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
        return partialConvertBridge(pieceIdx, secIdx, frac, left, toGeo,
            dt, spacing, adaptTol, Section.Domain.TIME, false, false,
            false);
    }

    public boolean partialConvertBridge(int pieceIdx, int secIdx,
                                        double frac, boolean left,
                                        boolean toGeo, double dt,
                                        double spacing, double adaptTol,
                                        Section.Domain outDom,
                                        boolean rollG, boolean ypG,
                                        boolean rateM) {
        return partialConvertBridge(pieceIdx, secIdx, frac, left, toGeo,
            dt, spacing, adaptTol, outDom, rollG, ypG, rateM, false);
    }

    public boolean partialConvertBridge(int pieceIdx, int secIdx,
                                        double frac, boolean left,
                                        boolean toGeo, double dt,
                                        double spacing, double adaptTol,
                                        Section.Domain outDom,
                                        boolean rollG, boolean ypG,
                                        boolean rateM, boolean rollAbs) {
        return partialConvertBridge(pieceIdx, secIdx, frac, left, toGeo,
            dt, spacing, adaptTol, outDom, rollG, ypG, rateM, rollAbs,
            false);
    }

    public boolean partialConvertBridge(int pieceIdx, int secIdx,
                                        double frac, boolean left,
                                        boolean toGeo, double dt,
                                        double spacing, double adaptTol,
                                        Section.Domain outDom,
                                        boolean rollG, boolean ypG,
                                        boolean rateM, boolean rollAbs,
                                        boolean ypAbs) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (!(piece.sections.get(secIdx) instanceof BridgeSection bs)) {
            return false;
        }
        List<FVDIntegrator.Sample> s = bs.integrate(dt, fric()).samples();
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
            LaneSection conv = fitLanes(sideOf(s, cs, true),
                bs.vDesign, toGeo, dt, spacing, adaptTol,
                outDom, rollG, ypG, rateM, rollAbs, ypAbs,
                pieceHeadDeg(pieceIdx));   // conv stays mid-piece (D238)
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
            LaneSection conv = fitLanes(side, vCut, toGeo, dt,
                spacing, adaptTol, outDom, rollG, ypG, rateM, rollAbs,
                ypAbs, Double.NaN);   // conv becomes the target
                // piece's FIRST section — self-relative (D238)
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
                // the target's entry moves to the cut — its ABSOLUTE
                // heading lanes rebase by the psi delta (v19.9, D238
                // sweep find: without it "their own shapes untouched"
                // broke for abs-heading sections — the reanchor rule)
                double tpOldPsi = entryPsiInternal(tp);
                tp.x = cs.pos().x();
                tp.y = cs.pos().y();
                tp.z = cs.pos().z();
                tp.yawDeg = 0;
                tp.pitchDeg = 0;
                tp.rollDeg = 0;
                tp.designPitchDeg = 0;   // D222: both tiers re-anchor
                tp.designRollDeg = 0;
                tp.baseOrient = cq;
                tp.canonOrient = null;   // interpolated cut pose (D199)
                rebaseAbsHeading(tp.sections,
                    TrackCompiler.headingDeg(cq) - tpOldPsi);
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
        return partialConvertSection(pieceIdx, secIdx, frac, left, toGeo,
            dt, spacing, adaptTol, Section.Domain.TIME, false, false,
            false);
    }

    /** Full-settings variant (v16.6, D116): the CONVERTED side comes
     *  out in the requested type/domain/axes/rate units; the kept
     *  side stays native as ever. */
    public boolean partialConvertSection(int pieceIdx, int secIdx,
            double frac, boolean left, boolean toGeo, double dt,
            double spacing, double adaptTol, Section.Domain outDom,
            boolean rollG, boolean ypG, boolean rateM) {
        return partialConvertSection(pieceIdx, secIdx, frac, left, toGeo,
            dt, spacing, adaptTol, outDom, rollG, ypG, rateM, false);
    }

    public boolean partialConvertSection(int pieceIdx, int secIdx,
            double frac, boolean left, boolean toGeo, double dt,
            double spacing, double adaptTol, Section.Domain outDom,
            boolean rollG, boolean ypG, boolean rateM, boolean rollAbs) {
        return partialConvertSection(pieceIdx, secIdx, frac, left, toGeo,
            dt, spacing, adaptTol, outDom, rollG, ypG, rateM, rollAbs,
            false);
    }

    public boolean partialConvertSection(int pieceIdx, int secIdx,
            double frac, boolean left, boolean toGeo, double dt,
            double spacing, double adaptTol, Section.Domain outDom,
            boolean rollG, boolean ypG, boolean rateM, boolean rollAbs,
            boolean ypAbs) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec.reversed() || sec instanceof BridgeSection) return false;
        // INFLUENCED sections refuse the partial cut (v15.0, D98): the
        // kept side's ref = integrate(its half-lanes) cannot reproduce
        // the ORIGINAL ref's sub-arc once the deltas bent the entry
        // frame — bake or discard the influence first (the UI says so)
        if (sec instanceof LaneSection li && li.influenced()) return false;
        TrackCompiler.Result r = compileForFit(dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 19) return false;   // too short to split
        List<FVDIntegrator.Sample> s =
            r.samples().subList(sp.i0(), sp.i1() + 1);   // frac axis: span-only (D89)
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
            kp.domain(ls.domain());   // the kept half stays native —
            // INCLUDING its axis/rate/behavior flags (v19.9, D238
            // sweep find: they were silently dropped since v16.x —
            // "the unconverted side keeps its original
            // representation EXACTLY" read a global/°/m/absolute
            // section's kept half as local °/s rates)
            kp.rollGlobal = ls.rollGlobal;
            kp.yawPitchGlobal = ls.yawPitchGlobal;
            kp.ratePerMeter = ls.ratePerMeter;
            kp.rollAbsolute = ls.rollAbsolute;
            kp.ypAbsolute = ls.ypAbsolute;
            // the kept half inherits the END-FOLLOW flags (D248 — a
            // split is not an edit; the belt clears a half that is
            // no longer hold-shaped)
            System.arraycopy(ls.endFollows, 0, kp.endFollows, 0, 3);
            // (the D237 kept-right abs-heading rebase is GONE —
            // v19.9, D238: piece-relative heading, same piece, the
            // kept half's values carry verbatim)
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
        List<FVDIntegrator.Sample> convSide = sideOf(s, cs, left);
        if (left) {
            // the LEFT half starts at the section ENTRY — the same
            // dropped-boundary-sample rule as the whole refits
            // (v16.6, D113); the cut FRACTION axis above stays
            // span-only so D89's exact node cuts don't shift
            List<FVDIntegrator.Sample> we = withEntry(r.samples(), sp, dt);
            if (we.size() == sp.i1() - sp.i0() + 2) {
                List<FVDIntegrator.Sample> tmp =
                    new ArrayList<>(convSide.size() + 1);
                tmp.add(we.get(0));
                tmp.addAll(convSide);
                convSide = tmp;
            }
        }
        LaneSection conv = fitLanes(convSide,
            left ? sec.vDesign() : vCut, toGeo, dt, spacing, adaptTol,
            outDom, rollG, ypG, rateM, rollAbs, ypAbs, sec, sp,
            pieceHeadDeg(r, pieceIdx));   // D236; piece-relative D238
        // BOTH halves continue the original's DESIGN speed profile, so
        // a conflicted (pinned) section leaves two conflicted halves —
        // the pin rides onto each (v15.1, D99; unpinned originals get
        // false = today's behavior, where the reconcile's re-freeze of
        // the second half is a no-op on the shared profile anyway)
        keep.vPinned(sec.vPinned());
        conv.vPinned = sec.vPinned();
        // speed controls partition at the cut (v18.0, D160): each
        // half takes its share — the kept side exactly, the converted
        // side mapped when the output domain differs. cutNative = the
        // cut position on the SOURCE's own axis.
        double cutNative = sec.domain() == Section.Domain.DISTANCE
            ? cs.s() - sp.s0() : cs.t() - sp.t0();
        carrySpeedCtls(sec, sp, r.samples(), left ? conv : keep,
            0, cutNative);
        carrySpeedCtls(sec, sp, r.samples(), left ? keep : conv,
            cutNative, Double.POSITIVE_INFINITY);
        piece.sections.set(secIdx, left ? conv : keep);
        piece.sections.add(secIdx + 1, left ? keep : conv);
        return true;
    }

    /** MID-SECTION NODE-ANCHORED SPLIT (v16.18, D130 — the last
     *  Phase-2 item): cut lane section secIdx of piece pieceIdx in
     *  TWO at LOCAL x `xc` (seconds or meters per its domain). All
     *  three lanes go through splitLanes' de Casteljau identity /
     *  node partition and the RIGHT half's vDesign re-freezes to
     *  the design speed AT the cut, so the result is EXACTLY the
     *  chain the user would get authoring two sections there
     *  (gated 0.0 m). Versus the UNSPLIT original the new seam
     *  inherits the STANDARD grid-truncation every seam has
     *  (authored ends truncate to the dt grid — up to one dt of
     *  profile absorbed at the boundary; ~2 cm path shift on the
     *  gate's violent fixture, invisible against the 14 cm rails).
     *  Axis/rate/roll flags and the vPinned conflict ride onto
     *  both halves. INFLUENCED sections split EXACTLY under the
     *  v19.7 hybrid (D236, retiring D235's split anchor): the delta
     *  lanes live on the section's OWN axis now, so both lane sets
     *  cut at the same xc and the halves continue by the Markov
     *  property alone — no stored frame, nothing re-solves.
     *  Refuses reversed, s/c and bridge
     *  sections and cuts at the very ends. The editor's "Split
     *  section/track piece here" both ride on this (the piece
     *  variant chains splitPiece at the fresh boundary — D71's
     *  rigid pose copy, gated 0.0 m against the section split). */
    public boolean splitSectionAt(int pieceIdx, int secIdx, double xc,
                                  double dt) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (!(sec instanceof LaneSection ls) || sec.reversed()) {
            return false;
        }
        double end = BezierBaker.rideEnd(ls.lanes);
        if (xc <= 1e-9 || xc >= end - 1e-9) return false;
        TrackCompiler.Result r = TrackCompiler.compile(this, dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        if (spanIdx >= r.spans().size()) return false;
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(), r.samples().size() - 1);
        if (i1 - sp.i0() < 1) return false;
        List<FVDIntegrator.Sample> s = r.samples().subList(sp.i0(), i1 + 1);
        boolean da = ls.domain() == Section.Domain.DISTANCE;
        FVDIntegrator.Sample cs = da ? sampleAtArc(s, sp.s0() + xc)
                                     : sampleAtTime(s, sp.t0() + xc);
        // plain math — FvdDesigner.clamp would drag JavaFX into the
        // headless gates through its static Color fields
        double vCut = Math.max(1, Math.min(100, cs.v()));
        List<List<BezierBaker.Node>> leftL = splitLanes(ls.lanes, xc, false);
        List<List<BezierBaker.Node>> rightL = splitLanes(ls.lanes, xc, true);
        LaneSection a = ls instanceof GeoSection
            ? new GeoSection(leftL, ls.vDesign)
            : new ForceSection(leftL, ls.vDesign);
        LaneSection b = ls instanceof GeoSection
            ? new GeoSection(rightL, vCut)
            : new ForceSection(rightL, vCut);
        for (LaneSection h : new LaneSection[]{ a, b }) {
            h.domain(ls.domain());
            h.rollGlobal = ls.rollGlobal;
            h.yawPitchGlobal = ls.yawPitchGlobal;
            h.ratePerMeter = ls.ratePerMeter;
            h.rollAbsolute = ls.rollAbsolute;
            h.ypAbsolute = ls.ypAbsolute;
            h.vPinned(ls.vPinned());
            // both halves inherit the END-FOLLOW flags (D248 — a
            // split is not an edit; the left half's cut node IS its
            // end, a 1-node right hold neither defines nor follows)
            System.arraycopy(ls.endFollows, 0, h.endFollows, 0, 3);
        }
        if (ls.influenced()) {   // both halves stay influenced (D234;
                                 // hybrid-exact since D236 — the
                                 // deltas share the authoring axis)
            carryInfluence(ls, a, xc, false);
            carryInfluence(ls, b, xc, true);
        }
        // (the D237 abs-heading rebase at the cut is GONE — v19.9,
        // D238: the heading lane is PIECE-relative now, and both
        // halves stay in the same piece, so the values carry
        // verbatim like elevation and bank)
        // speed controls partition at the cut, native axis — exact
        // (v18.0, D160; straddling controls clip into two)
        a.speedCtls.addAll(SpeedControl.portion(ls.speedCtls, 0, xc));
        b.speedCtls.addAll(SpeedControl.portion(ls.speedCtls, xc,
            Double.POSITIVE_INFINITY));
        a.speedMarks.addAll(SpeedControl.marksPortion(ls.speedMarks, 0, xc));
        b.speedMarks.addAll(SpeedControl.marksPortion(ls.speedMarks, xc,
            Double.POSITIVE_INFINITY));
        piece.sections.set(secIdx, a);
        piece.sections.add(secIdx + 1, b);
        return true;
    }

    /** Value/slope tolerance of {@link #mergeSections}' seam tests
     *  (the D131 matched tolerance, shared by every branch since
     *  v19.18 D247): two seam values (or slopes) within this are
     *  "equal" — split nodes and B-matched seams are bit-equal
     *  copies, so the lossless boundary sits exactly where it did. */
    public static final double MERGE_EPS = 1e-9;

    /** The STEP length of a keep-both-values merge (v19.18, D247):
     *  the domain's MINIMUM TRANSITION — 0.01 s TIME / 0.1 m
     *  DISTANCE. Mirrors the editor's GraphEditor.MIN_GAP (0.01,
     *  seconds) and GraphEditor.minGapOf (DISTANCE ? 0.1 : MIN_GAP),
     *  which are editor-private (Coaster is pure Java); keep the
     *  three in step. On the physics bake grid (0.01 s / 0.05 m) a
     *  step this long is a one-to-two-cell linear ramp whatever the
     *  handles do — "very steep" IS the felt jump at the seam. */
    public static double mergeStepLen(Section.Domain d) {
        return d == Section.Domain.DISTANCE ? 0.1 : 0.01;
    }

    /** CONNECT two adjacent LIKE sections into one (v16.19, D131 —
     *  the split's inverse, user spec): secIdx and secIdx+1 of the
     *  piece merge when they share type, domain and every axis/rate/
     *  roll flag (reversed/influenced refuse). The second section's
     *  lanes append shifted by the first's duration; where BOTH
     *  sides own a seam node they become ONE node — a seam that was
     *  MATCHED (equal value + slope, what B leaves behind) merges
     *  losslessly (split→connect round-trips to the original,
     *  gated). An UNMATCHED seam KEEPS BOTH VALUES since v19.18
     *  (D247, user re-spec superseding D131's average value + slope
     *  and D132's held-side averaging): equal values with differing
     *  slopes = ONE BROKEN node carrying A's in handle and B's out
     *  handle verbatim; differing values = a STEP over δ =
     *  {@link #mergeStepLen}(domain) — A's value at seam−δ (A's last
     *  segment shrinks by δ, its handles keeping their SLOPES by the
     *  D105 dv rescale) and B's value AT the seam (B's node times,
     *  speed controls and marks stay put), flat facing handles,
     *  each node broken unless its outer slope is flat. Fallbacks:
     *  A's last segment shorter than 2δ → the step mirrors onto B's
     *  side (B's first node moves to seam+δ); both short → δ' =
     *  max(gap)/2; both zero → the old average. A lane that HELD
     *  early (A ended before the seam) contributes its held value
     *  with slope 0: equal values → b0 keeps its value with a flat
     *  in handle and its own out handle (broken unless that slope
     *  is flat — a B-matched hold still merges bit-identically to
     *  D132); differing → a flat HOLD node at seam−δ, then b0 at
     *  the seam (the hold's dead out handle is killed either way,
     *  D132). The merged section keeps the FIRST's vDesign and
     *  pin; B's vDesign is dropped (the seam is gone, nothing to
     *  re-freeze). Gated by MergeGate. */
    public boolean mergeSections(int pieceIdx, int secIdx) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (secIdx < 0 || secIdx + 1 >= piece.sections.size()) return false;
        Section sa = piece.sections.get(secIdx);
        Section sb = piece.sections.get(secIdx + 1);
        if (!(sa instanceof LaneSection a) || !(sb instanceof LaneSection b)
                || a.reversed() || b.reversed()
                || a.influenced() || b.influenced()) {
            return false;
        }
        boolean like = (a instanceof GeoSection) == (b instanceof GeoSection)
            && a.domain() == b.domain()
            && a.rollGlobal == b.rollGlobal
            && a.yawPitchGlobal == b.yawPitchGlobal
            && a.ratePerMeter == b.ratePerMeter
            && a.rollAbsolute == b.rollAbsolute
            && a.ypAbsolute == b.ypAbsolute;
        if (!like) return false;
        double dA = BezierBaker.rideEnd(a.lanes);
        List<List<BezierBaker.Node>> merged = new ArrayList<>(3);
        for (int L = 0; L < 3; L++) {
            List<BezierBaker.Node> la = new ArrayList<>();
            for (BezierBaker.Node nd : a.lanes.get(L)) la.add(nd.copy());
            List<BezierBaker.Node> lb = new ArrayList<>();
            for (BezierBaker.Node nd : b.lanes.get(L)) {
                BezierBaker.Node c = nd.copy();
                c.t += dA;
                // (the D237 abs-heading LIFT is GONE — v19.9, D238:
                // piece-relative headings share one zero within the
                // piece, so the values append verbatim; the split
                // rebase it inverted is gone too — still lossless)
                lb.add(c);
            }
            List<BezierBaker.Node> m = new ArrayList<>(la);
            BezierBaker.Node endA = la.get(la.size() - 1);
            BezierBaker.Node startB = lb.get(0);
            if (Math.abs(endA.t - startB.t) <= 1e-9) {
                // both sides own the seam → ONE node: A's in-handle,
                // B's out-handle and lock flags
                // effInDt is NEGATIVE (−inFrac·gap) — guards must be
                // on |dt| or every in-slope reads 0 (v16.20: that
                // phantom flat side failed the matched test on any
                // sloped seam and the "average" HALVED the slope —
                // the user's "the average slope was not accurate";
                // the flat-handled gate fixtures were blind to it)
                double inDt = la.size() >= 2
                    ? BezierBaker.effInDt(la.get(la.size() - 2), endA) : 0;
                double outDt = lb.size() >= 2
                    ? BezierBaker.effOutDt(startB, lb.get(1)) : 0;
                double sIn = Math.abs(inDt) > 1e-12 ? endA.inDv / inDt : 0;
                double sOut = Math.abs(outDt) > 1e-12 ? startB.outDv / outDt : 0;
                BezierBaker.Node seam = endA.copy();
                seam.outFrac = startB.outFrac;
                seam.outDv = startB.outDv;
                seam.straightOut = startB.straightOut;
                seam.levelOut = startB.levelOut;
                boolean matched = Math.abs(endA.val - startB.val) <= MERGE_EPS
                    && Math.abs(sIn - sOut) <= MERGE_EPS;
                if (matched) {
                    seam.smooth = true;
                    m.set(m.size() - 1, seam);
                } else if (Math.abs(endA.val - startB.val) <= MERGE_EPS) {
                    // KEEP BOTH SLOPES (v19.18, D247): equal values,
                    // differing slopes → the node as built — A's in
                    // handle, B's out handle, both verbatim — marked
                    // BROKEN (nothing re-collinearizes a smooth node
                    // at redraw, but the next drag would mirror the
                    // partner; a "smooth" kink is a lie)
                    seam.smooth = false;
                    m.set(m.size() - 1, seam);
                } else {
                    // KEEP BOTH VALUES (v19.18, D247, superseding the
                    // D131 average): a STEP over δ — A's value at
                    // seam−δ, B's value AT the seam, flat facing
                    // handles. Preferred host = A's last segment
                    // (shrinks by δ; B's node times, controls and
                    // marks keep their +dA offsets); it mirrors onto
                    // B's first segment when A's is shorter than 2δ,
                    // both short → δ' = max(gap)/2, both zero (two
                    // one-node lanes) → the old average as the last
                    // resort. The step's outer handles keep their
                    // SLOPES through the D105 dv rescale (the
                    // fraction already rescales the x-run).
                    double gA = la.size() >= 2
                        ? endA.t - la.get(la.size() - 2).t : 0;
                    double gB = lb.size() >= 2 ? lb.get(1).t - startB.t : 0;
                    double delta = mergeStepLen(a.domain());
                    if (gA < 2 * delta && gB < 2 * delta) {
                        delta = Math.max(gA, gB) / 2;
                    }
                    if (delta < 1e-9) {
                        seam.val = (endA.val + startB.val) / 2;
                        double sAvg = (sIn + sOut) / 2;
                        seam.inDv = sAvg * inDt;
                        seam.outDv = sAvg * outDt;
                        seam.smooth = true;
                        m.set(m.size() - 1, seam);
                    } else if (gA >= 2 * delta) {
                        // LEFT node = endA moved to seam−δ: in handle
                        // kept (slope-preserving rescale of it and
                        // of prev's out handle), out handle FLAT and
                        // UNLOCKED — enforceLockLane would otherwise
                        // level the right node onto the left across
                        // the step (levelOut) or re-chord the flat
                        // handles (straightOut)
                        BezierBaker.Node left = endA.copy();
                        left.t = endA.t - delta;
                        double r = (gA - delta) / gA;
                        la.get(la.size() - 2).outDv *= r;   // shared with m
                        left.inDv *= r;
                        left.outFrac = BezierBaker.DEFAULT_FRAC;
                        left.outDv = 0;
                        left.straightOut = false;
                        left.levelOut = false;
                        left.smooth = Math.abs(sIn) <= MERGE_EPS;
                        // RIGHT node = the seam node at dA: B's value,
                        // in handle flat, out handle + locks = B's
                        seam.val = startB.val;
                        seam.inFrac = BezierBaker.DEFAULT_FRAC;
                        seam.inDv = 0;
                        seam.smooth = Math.abs(sOut) <= MERGE_EPS;
                        m.set(m.size() - 1, left);
                        m.add(seam);
                    } else {
                        // MIRROR: A's last segment is too short — the
                        // left node stays endA at the seam (out flat,
                        // unlocked), B's first node moves to seam+δ
                        // with a flat in handle and its out handle's
                        // slope kept (its next node's in handle too)
                        BezierBaker.Node left = endA.copy();
                        left.outFrac = BezierBaker.DEFAULT_FRAC;
                        left.outDv = 0;
                        left.straightOut = false;
                        left.levelOut = false;
                        left.smooth = Math.abs(sIn) <= MERGE_EPS;
                        BezierBaker.Node right = startB.copy();
                        right.t = startB.t + delta;
                        double r = (gB - delta) / gB;
                        right.outDv *= r;
                        lb.get(1).inDv *= r;
                        right.inFrac = BezierBaker.DEFAULT_FRAC;
                        right.inDv = 0;
                        right.smooth = Math.abs(sOut) <= MERGE_EPS;
                        m.set(m.size() - 1, left);
                        m.add(right);
                    }
                }
                for (int k = 1; k < lb.size(); k++) m.add(lb.get(k));
            } else {
                // A's lane HELD to the seam: its value there is the
                // hold, its slope 0 — the held side contributes that
                // pair like any seam side (v16.20), and since v19.18
                // (D247, superseding the D132 average) BOTH values are
                // KEPT: equal values → b0 keeps its value with a flat
                // in handle and its own out handle (broken unless
                // that slope is flat — a B-MATCHED hold, equal value
                // + slope 0, merges bit-identically to D132);
                // differing → a flat HOLD node at seam−δ (the hold
                // becomes a real flat segment) then b0 AT the seam
                // with a flat in handle and its out handle verbatim.
                // Both facing handles of the revived segment carried
                // DEAD data while the lanes ended here (a lane's last
                // node keeps its out fields, unused) — appending them
                // verbatim bent the join (D132, the user's "connect
                // after Shift+B changes the slope"): endA leaves the
                // hold FLAT, b0's in handle is flat.
                BezierBaker.Node b0 = lb.get(0);
                double outDt = lb.size() >= 2
                    ? BezierBaker.effOutDt(b0, lb.get(1)) : 0;
                double sOut = Math.abs(outDt) > 1e-12 ? b0.outDv / outDt : 0;
                endA.outDv = 0;
                b0.inDv = 0;
                b0.smooth = Math.abs(sOut) <= MERGE_EPS;
                if (Math.abs(endA.val - b0.val) > MERGE_EPS) {
                    double gH = b0.t - endA.t;      // > 1e-9 here
                    double delta = mergeStepLen(a.domain());
                    if (gH < 2 * delta) delta = gH / 2;
                    // flat default handles, smooth (flat/flat is
                    // collinear), no locks
                    m.add(new BezierBaker.Node(b0.t - delta, endA.val));
                }
                m.addAll(lb);
            }
            merged.add(m);
        }
        LaneSection ms = a instanceof GeoSection
            ? new GeoSection(merged, a.vDesign)
            : new ForceSection(merged, a.vDesign);
        ms.domain(a.domain());
        ms.rollGlobal = a.rollGlobal;
        ms.yawPitchGlobal = a.yawPitchGlobal;
        ms.ratePerMeter = a.ratePerMeter;
        ms.rollAbsolute = a.rollAbsolute;
        ms.ypAbsolute = a.ypAbsolute;
        ms.vPinned(a.vPinned());
        // speed controls: A's verbatim + B's shifted by A's extent
        // (v18.0, D160 — the split partition's exact inverse)
        ms.speedCtls.addAll(SpeedControl.copyList(a.speedCtls));
        for (SpeedControl c : SpeedControl.copyList(b.speedCtls)) {
            c.x0 += dA;
            ms.speedCtls.add(c);
        }
        ms.speedMarks.addAll(a.speedMarks);
        for (double m : b.speedMarks) ms.speedMarks.add(m + dA);
        piece.sections.set(secIdx, ms);
        piece.sections.remove(secIdx + 1);
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

    /** Carry INFLUENCE onto a split half (v19.5 D234; hybrid-simple
     *  since v19.7 D236 — the delta lanes live on the section's own
     *  axis, no anchor exists): cut the deltas at the same xc as
     *  the authored lanes, copy the axis flags, keep the mirrored
     *  pair. editInfluence stays off — re-check "Edit influence" to
     *  edit the half's deltas. */
    private static void carryInfluence(LaneSection src, LaneSection dst,
                                       double xc, boolean keepRight) {
        dst.deltaLanes = splitLanes(src.deltaLanes, xc, keepRight);
        dst.inflRollGlobal = src.inflRollGlobal;
        dst.inflYawPitchGlobal = src.inflYawPitchGlobal;
        dst.vAuthored = dst.vDesign;
    }

    /** True when every delta lane evaluates to (essentially) zero
     *  over [x0, x1] of the section's delta axis (v19.5, D234 —
     *  make-real's window test): checks the drawn polyline's rows
     *  inside the range, the held tail past each lane's last node,
     *  and a uniform exact sweep so a narrow bump between polyline
     *  knots can't hide. */
    private static boolean deltasZeroOn(LaneSection ls, double x0,
                                        double x1) {
        if (ls.deltaLanes == null) return true;
        for (List<BezierBaker.Node> lane : ls.deltaLanes) {
            if (lane.isEmpty()) continue;
            for (double[] p : BezierBaker.polyline(lane)) {
                if (p[0] >= x0 - 1e-9 && p[0] <= x1 + 1e-9
                        && Math.abs(p[1]) > 1e-7) {
                    return false;
                }
            }
            BezierBaker.Node lastN = lane.get(lane.size() - 1);
            // a lane ending before x1 HOLDS its final value into the
            // range (whether it ended before or inside it)
            if (lastN.t < x1 - 1e-9 && Math.abs(lastN.val) > 1e-7) {
                return false;
            }
            double lo = Math.max(x0, lane.get(0).t);
            double hi = Math.min(x1, lastN.t);
            for (int k = 0; k <= 32 && lo < hi; k++) {
                double x = lo + (hi - lo) * k / 32.0;
                if (Math.abs(BezierBaker.exactAt(lane, x)) > 1e-7) {
                    return false;
                }
            }
        }
        return true;
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
                // the ease formula mirrors ScIntegrator (D232/D233:
                // elem 0's smoothIn is the entry ease VERBATIM — 0 =
                // sharp, nothing to protect)
                double ease = i == 0 ? e.smoothIn : Math.max(e.smoothIn, 2);
                if (ease > 1e-9) bad.add(new double[]{ at, at + ease });
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
        return convertWholeSection(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, Section.Domain.TIME, false, false, false);
    }

    /** FULL-SETTINGS convert (v16.6, D116 — the one-dialog rule): any
     *  combination of type × domain × axes × rate units converts to
     *  any other in ONE engine call. */
    public boolean convertWholeSection(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM) {
        return convertWholeSection(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, outDom, rollG, ypG, rateM, false);
    }

    public boolean convertWholeSection(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs) {
        return convertWholeSection(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, outDom, rollG, ypG, rateM, rollAbs, false);
    }

    /** strictSeam applies to BRIDGE whole-converts only (v17.1, D135):
     *  keep the next piece as a strict-seam follower instead of the
     *  classic attach + pin. */
    public boolean convertWholeSection(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs, boolean strictSeam) {
        return convertWholeSection(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, outDom, rollG, ypG, rateM, rollAbs, strictSeam,
            false);
    }

    public boolean convertWholeSection(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs, boolean strictSeam,
            boolean ypAbs) {
        TrackPiece piece = pieces.get(pieceIdx);
        Section sec = piece.sections.get(secIdx);
        if (sec instanceof BridgeSection) {
            return convertBridgeWhole(pieceIdx, secIdx, toGeo, dt,
                spacing, adaptTol, outDom, rollG, ypG, rateM, rollAbs,
                strictSeam, ypAbs);
        }
        if (sec.reversed()) return false;
        TrackCompiler.Result r = compileForFit(dt);
        int spanIdx = secIdx;
        for (int p = 0; p < pieceIdx; p++) {
            spanIdx += pieces.get(p).sections.size();
        }
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i1() - sp.i0() < 4) return false;   // too short to measure
        LaneSection conv = fitLanes(
            withEntry(r.samples(), sp, dt),
            sec.vDesign(), toGeo, dt, spacing, adaptTol,
            outDom, rollG, ypG, rateM, rollAbs, ypAbs, sec, sp,
            pieceHeadDeg(r, pieceIdx));   // D236: the source's speed
            // controls drive the re-time march; piece-relative D238
        // PER-LANE preserve (v19.9, D238, generalizing D169's
        // roll-only case): every lane whose meaning the flag flips
        // don't touch keeps its AUTHORED nodes bit-exactly — a
        // roll-only change keeps lanes 0/1, a yaw/pitch-only change
        // keeps the roll lane, a rate-unit-only change keeps the
        // absolute-angle lanes. The conversion residual lives in
        // the lanes that actually change meaning.
        if (sec instanceof LaneSection src) {
            preserveLanes(src, conv, laneKeepMap(src, toGeo, outDom,
                rollG, ypG, rateM, rollAbs, ypAbs));
        }
        // a SPEED-CONFLICTED section stays conflicted through a convert
        // (v15.1, D99 — user rule: "the geo section should still have
        // design/real speed conflict and shouldn't change shape"): the
        // pin rides along, so the reconcile keeps leaving it alone and
        // the shape survives (vDesign already carries over above)
        conv.vPinned = sec.vPinned();
        // speed controls carry (v18.0, D160 — mapped on domain flips)
        carrySpeedCtls(sec, sp, r.samples(), conv, 0,
            Double.POSITIVE_INFINITY);
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
        return convertBridgeWhole(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, Section.Domain.TIME, false, false, false);
    }

    public boolean convertBridgeWhole(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM) {
        return convertBridgeWhole(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, outDom, rollG, ypG, rateM, false);
    }

    public boolean convertBridgeWhole(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs) {
        return convertBridgeWhole(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, outDom, rollG, ypG, rateM, rollAbs, false);
    }

    /** strictSeam (v17.1, D135 — the Convert dialog's "Make following
     *  seam strict", checked by default): instead of dissolving the
     *  target piece into this one, KEEP it as a strict-seam FOLLOWER —
     *  its shape frozen exactly as the bridge left it, position + the
     *  bridge's unlocked axes following the converted lanes' end, its
     *  design speeds independent (no pin needed — piece-firsts are
     *  reconcile-exempt). The locks CARRY from the bridge, so the
     *  seam behaves like the bridge did. Un-stricting later
     *  reproduces the classic stitch exactly (attach + pin). */
    public boolean convertBridgeWhole(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs, boolean strictSeam) {
        return convertBridgeWhole(pieceIdx, secIdx, toGeo, dt, spacing,
            adaptTol, outDom, rollG, ypG, rateM, rollAbs, strictSeam,
            false);
    }

    public boolean convertBridgeWhole(int pieceIdx, int secIdx,
            boolean toGeo, double dt, double spacing, double adaptTol,
            Section.Domain outDom, boolean rollG, boolean ypG,
            boolean rateM, boolean rollAbs, boolean strictSeam,
            boolean ypAbs) {
        TrackPiece piece = pieces.get(pieceIdx);
        if (!(piece.sections.get(secIdx) instanceof BridgeSection bs)) {
            return false;
        }
        List<FVDIntegrator.Sample> s = bs.integrate(dt, fric()).samples();
        if (s.size() < 5) return false;
        piece.sections.set(secIdx,
            fitLanes(s, bs.vDesign, toGeo, dt, spacing, adaptTol,
                outDom, rollG, ypG, rateM, rollAbs, ypAbs,
                pieceHeadDeg(pieceIdx)));   // conv stays mid-piece (D238)
        if (pieceIdx + 1 < pieces.size()) {
            TrackPiece next = pieces.get(pieceIdx + 1);
            if (strictSeam) {
                next.followPrev = true;
                // ALL THREE locked at creation (v19.6, D235 — user
                // spec: "after a bridge is converted, yaw locked
                // too"; the seeds are the current angles, nothing
                // moves; was the bridge's own locks)
                next.sLockYaw = true;
                next.sLockPitch = true;
                next.sLockRoll = true;
                seedStrictValues(next, next.entryPose().orient());
                // smoothing defaults ON at 10 m after a bridge convert
                // (v17.4, D141 — user spec: the joint replaces a
                // smooth bridge, so it should start smooth)
                if (next.sSmoothLen <= 0.01) next.sSmoothLen = 10;
                return true;
            }
            // classic stitch — PIN the first attached section's design
            // speed (v14.5, D97, user rule): the target's design
            // speeds and its design/true split must survive the
            // conversion exactly — without the pin the D95 reconcile
            // re-froze the attached chain to the lanes' design end
            // and closed the split.
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
                                    double resTwist, double d) {
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
        // CORRECTION WEIGHT (v17.12, D153 — user: "make the slopes
        // line up" at the bridge seams, asked AFTER the D150 value
        // fix landed): all three correction channels — the pitch and
        // heading humps and the D150 residual-twist rate — share
        // 30u²(1−u)², unit integral like the old 6u(1−u) parabola
        // but with ZERO VALUE AND ZERO SLOPE at both ends, so the
        // blend leaves each seam exactly on the measured jerks and
        // roll accelerations. (The same weight shipped briefly in
        // v17.10/D148 and was reverted — at that point the VALUE
        // jumps still dominated and it read as not-the-bug; with
        // D150 in, the end slopes are what remains.) Mid-span peak
        // 1.875× vs the parabola's 1.5×.
        double hump = 30 * u * u * (1 - u) * (1 - u);
        // RESIDUAL TWIST IN THE MARCH (v17.11, D150 — the user's
        // split-and-rebridge file): with roll LOCKED (or position
        // mode) over rolling seam states, the blend's rm carries the
        // neighbors' roll on but the TARGET's bank stays put — the
        // march used to end tens of degrees off in bank and the
        // render's residual quintic twisted U afterwards, while κ
        // below was built in the UNTWISTED frame: the far seam's
        // curvature vector arrived rotated by the whole residual
        // (~48° on the user's file = a ~1 g instant normal/lateral
        // jump). The residual joins the march as a roll RATE (zero
        // at the ends so the seam roll rates stay the neighbors'),
        // so the frame rolls toward the target DURING the march and
        // κ — rebuilt in the CURRENT frame each step — arrives in
        // the target's directions.
        if (resTwist != 0) {
            rm += resTwist * hump / d;
        }
        Vec3 kap = U.scale(ku).add(R.scale(kr));
        if (resPitch != 0) {
            // world-vertical direction ⊥ T: rotating T toward it raises
            // pitch; hump/d integrates to exactly `resPitch`
            Vec3 vup = new Vec3(0, 1, 0).sub(T.scale(T.y()));
            double len = vup.length();
            if (len > 1e-9) {
                kap = kap.add(vup.scale(resPitch * hump / d / len));
            }
        }
        if (resYaw != 0) {
            // rotation about WORLD Y: dT/ds = c·(Ŷ×T) changes the
            // HEADING at exactly c rad/m regardless of pitch (the
            // horiz² factors cancel), so the hump integrates to
            // exactly `resYaw` — the position-only mode's fixed
            // target heading is met BY THE MARCH, not dumped on the
            // septic (v13.10, D84)
            kap = kap.add(new Vec3(T.z(), 0, -T.x()).scale(
                resYaw * hump / d));
        }
        return new Vec3[]{ T, kap,
            T.scale(-kap.dot(U)).sub(R.scale(rm)) };
    }
}
