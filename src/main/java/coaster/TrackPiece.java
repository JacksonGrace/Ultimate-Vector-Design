package coaster;

import java.util.ArrayList;
import java.util.List;

/**
 * A disconnected group of contiguous sections (SECTIONS-DESIGN §2).
 * Placement = origin + BASE ORIENTATION + heading-yaw delta (v12.1,
 * D71). baseOrient is the piece's NATURAL orientation — identity for
 * pieces made from scratch, the exact boundary orientation for pieces
 * made by SPLITTING (user rule: splitting must not change the track's
 * orientation AT ALL), the reversed exit orientation after a piece
 * reversal. The EDIT control stays yaw-only (yawDeg, about world Y, on
 * top of baseOrient — pitch editing may come later per user); zeroing
 * yawDeg reverts to the original orientation exactly. The first
 * section takes its entry pose from here; editing one piece can never
 * move another (the §0 connect-invariance the Phase-1 gate asserts).
 */
public final class TrackPiece {

    public double x, y, z;      // origin (m); y is height above the ground plane
    public double yawDeg;       // heading DELTA, rotation about world Y
    /** Orientation deltas beyond yaw (v13.13, D87 — the roadmap's
     *  "pitch editing if revertible", arrived through the bridge
     *  panel): pitch rotates about the CURRENT heading's horizontal
     *  right axis (changes pitch only — heading preserved), roll
     *  about the resulting heading (changes bank only). Applied in
     *  the order yaw → pitch → roll, each delta controls exactly one
     *  Euler quantity of the entry frame; zeroing reverts exactly.
     *  Persisted as `piece` fields 9-10 (missing = 0). */
    public double pitchDeg;
    public double rollDeg;
    /** UI edit-guards for the Placement pitch/roll rows (v15.3, D101):
     *  locked = the spinner is frozen, matching the bridge panel's
     *  padlock defaults. With a bridge connecting INTO this piece the
     *  bridge's own lock fields govern instead (one shared state);
     *  these apply only to un-bridged pieces and are deliberately NOT
     *  persisted (pure edit-guards, like the graph's axis locks). */
    public boolean uiLockPitch = true;
    public boolean uiLockRoll = true;
    public Quat baseOrient = Quat.IDENTITY;   // natural orientation

    /** CANONICAL GENERATION STATE (v18.27, D199): the compile chain's
     *  exact heading + heading-stripped orientation at this piece's
     *  entry, stored by SPLIT ops so a split piece generates with
     *  bitwise the parent chain's arithmetic (the D71 "splitting
     *  never changes the track" rule under canonical-frame
     *  generation). Null = derive by decomposing baseOrient
     *  (deterministic; every non-split path). INVARIANT:
     *  aboutY(canonPsiDeg)·canonOrient ≈ baseOrient — any write to
     *  baseOrient must update the pair or NULL it. Persisted (piece
     *  fields 19-23); rides undo snapshots. */
    public double canonPsiDeg;
    public Quat canonOrient = null;

    /** TWO-TIER ORIENTATION (v19.0, D221; re-specced D222 — "Lock
     *  Track Shape"): pitchDeg/rollDeg remain the TRUE orientation
     *  deltas (the spinners, the file's piece fields 9-10 — old
     *  files identical); designPitchDeg/designRollDeg are the
     *  DESIGN-orientation deltas the sections GENERATE at (composed
     *  in canonical space — gravity re-solves there). The design→
     *  true gap applies as the rigid conflict tilt: design == (0,0)
     *  is bitwise the historic D100 path, design == true is the pure
     *  link (no ≠v from placement). shapeLocked is a pure EDIT-
     *  ROUTING flag — it never changes the compile: LOCKED (default)
     *  = orientation edits move TRUE only (the conflict grows, the
     *  shape is preserved, the downstream chain carries rigidly);
     *  UNLOCKED = edits move BOTH by the same amount (the offset
     *  frozen at unlock time rides along — unchecking never moves
     *  anything, and the shaping any subsequent edit causes is
     *  IDENTICAL whether or not a conflict rides on top: same design
     *  triple, same generation bits). Persisted as the `porient`
     *  line (locked pd rd); missing = locked + (0,0) = every old
     *  file bitwise. Machine writes (the bridge march's alignment)
     *  bypass the routing and write TRUE directly — they are
     *  conflict-writes by convention, so an aligned target can never
     *  reshape-loop the optimizer. */
    public boolean shapeLocked = true;
    public double designPitchDeg;
    public double designRollDeg;

    /** TRUE-pitch edit through the D222 routing: locked = the
     *  conflict absorbs it; unlocked = design rides along by the
     *  same amount (Euler-value lockstep — the D222 invariance). */
    public void editPitch(double v) {
        if (!shapeLocked) designPitchDeg += v - pitchDeg;
        pitchDeg = v;
    }

    public void editRoll(double v) {
        double d = v - rollDeg;
        // both tiers stay inside the spinners' ±360 (a wrap is the
        // same rotation; in-range values never move — bitwise)
        if (!shapeLocked) designRollDeg = wrap360(designRollDeg + d);
        rollDeg = wrap360(v);
    }

    /** Any design→true orientation conflict? (The buttons/readout
     *  and the resolve ops key off this, not off the flag.) Roll
     *  compares mod 360 — a ±360 wrap on one tier is no conflict. */
    public boolean orientConflict() {
        return pitchDeg != designPitchDeg
            || wrap180(rollDeg - designRollDeg) != 0;
    }

    /** STRICT SEAM (v17.1, D135): this piece FOLLOWS the previous
     *  piece's exit — its entry POSITION always sits on the seam, its
     *  entry orientation composes per the three locks (unlocked axes
     *  ALIGN to the incoming frame, locked axes keep the FROZEN
     *  values stored in this piece's own placement — which never
     *  mutates by following, so relocking an axis returns it to the
     *  original). The whole piece places RIGIDLY (the D100 tilt
     *  machinery): upstream edits can never reshape it, only move/
     *  re-aim it. Its design speeds stay independent (piece-first
     *  reconcile exclusion) — upstream speed changes surface as an
     *  honest ≠v conflict while the real ride flows through (same
     *  run). Ignored when there is no previous piece or the previous
     *  piece ends with a bridge (the bridge IS the connection).
     *  Persisted as `piece` fields 11-14 (missing = detached). */
    public boolean followPrev = false;
    public boolean sLockYaw = false;    // heading follows by default
    public boolean sLockPitch = true;   // pitch/roll DISCONTINUOUS by
    public boolean sLockRoll = true;    //   default (bridge convention)
    /** The DISCONTINUOUS axis values (v17.1 feedback round, D136):
     *  a locked axis holds THIS angle (deg) — user-editable in the
     *  seam settings (spinners; ↺ restores the value the seam was
     *  created with, re-read from the stored placement). Initialized
     *  from the stored placement at strict-creation; toggling an axis
     *  discontinuous captures the CURRENT followed value (no jump).
     *  Persisted as `piece` fields 15-17. */
    public double sYawDeg;
    public double sPitchDeg;
    public double sRollDeg;

    /** STRICT-SEAM geometric SMOOTHING length (m of track, half each
     *  side of the seam; v17.2, D139 — the s/c entry-smoothing idea
     *  applied to the frozen junction): the compiler blends the two
     *  sides' orientations across the window and re-marches the
     *  positions, so the seam's instant orientation/curvature change
     *  spreads over the window instead of one sample. 0 = off
     *  (bitwise-historic). Persisted as `piece` field 18. */
    public double sSmoothLen;

    /** Has this seam's SETTINGS pop-out been opened before (v17.8,
     *  D145)? First open: Discontinuous toggles seed the creation
     *  angle; later opens capture the current one. Interaction
     *  history — deliberately NOT persisted and NOT in undo. */
    public boolean sSeenSettings;

    public final List<Section> sections = new ArrayList<>();

    public TrackPiece(double x, double y, double z, double yawDeg) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yawDeg = yawDeg;
    }

    /** Entry pose of the piece's first section. Identity base + all
     *  deltas 0 = exactly the identity orientation, and deltas 0 in
     *  general is exactly baseOrient (float-exact — the bit-for-bit
     *  and split gates ride on these paths). Always equals
     *  tilt() ∘ genPose() — same math, kept verbatim for the
     *  identity-path guarantees. */
    public TrackPose entryPose() {
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        if (pitchDeg != 0) {
            Vec3 t = q.T();
            // horizontal right of the heading (left-handed world:
            // rider-right of +X heading is −Z, i.e. U×T for level
            // frames); rotating about it moves pitch only
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                q = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(pitchDeg)).mul(q);
            }
        }
        if (rollDeg != 0) {
            q = Quat.aboutAxis(q.T(), Math.toRadians(rollDeg)).mul(q);
        }
        return new TrackPose(new Vec3(x, y, z), q);
    }

    /** GENERATION pose (v15.2, D100): baseOrient ⊕ yaw only — the
     *  DESIGN orientation the sections integrate at. The pitch/roll
     *  deltas are a RIGID placement transform on top (tilt()), so
     *  tilting a piece PRESERVES its geometric shape instead of
     *  re-solving it against gravity (user rule: "make sure the real
     *  geometric shape is being preserved"); the felt-G difference
     *  the tilt causes shows as the design/real split. Yaw stays in
     *  the generation pose — rotation about world Y commutes with
     *  gravity exactly, so its placement was always rigid. (Since
     *  D222 this is the design orientation only while the DESIGN
     *  deltas are zero — designPose() is the general form.) */
    public TrackPose genPose() {
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        return new TrackPose(new Vec3(x, y, z), q);
    }

    /** The DESIGN orientation pose (v19.0, D222): baseOrient ⊕ yaw ⊕
     *  the DESIGN pitch/roll deltas — entryPose()'s exact axis math
     *  with the design tier. Zero design deltas = genPose() (the
     *  yaw==0 short-circuit keeps the identity paths float-exact). */
    public TrackPose designPose() {
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        if (designPitchDeg != 0) {
            Vec3 t = q.T();
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                q = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(designPitchDeg)).mul(q);
            }
        }
        if (designRollDeg != 0) {
            q = Quat.aboutAxis(q.T(), Math.toRadians(designRollDeg)).mul(q);
        }
        return new TrackPose(new Vec3(x, y, z), q);
    }

    /** INVERSE of entryPose()'s orientation composition (v18.19,
     *  D190 — the local gizmo's rotate-about-the-drawn-axis): the
     *  {yawDeg, pitchDeg, rollDeg} deltas that reproduce `target`
     *  on top of `base`, or null where a heading is undefined
     *  (either T near vertical). Exact for |pitch| < 90: the
     *  composition preserves heading through its pitch/roll steps
     *  and elevation through roll, so the three extract in
     *  sequence — yaw from the heading gap, pitch from the
     *  elevation gap, roll as the U-vs-U twist about the shared T. */
    public static double[] decomposeEntry(Quat base, Quat target) {
        Vec3 tb = base.T(), tt = target.T();
        double hb = Math.hypot(tb.x(), tb.z());
        double ht = Math.hypot(tt.x(), tt.z());
        if (hb < 1e-6 || ht < 1e-6) return null;
        double yaw = wrap180(Math.toDegrees(
            Math.atan2(-tt.z(), tt.x()) - Math.atan2(-tb.z(), tb.x())));
        Quat q1 = yaw == 0 ? base : Quat.aboutY(yaw).mul(base);
        Vec3 t1 = q1.T();
        double pitch = Math.toDegrees(
            Math.atan2(tt.y(), ht)
            - Math.atan2(t1.y(), Math.hypot(t1.x(), t1.z())));
        Quat q2 = q1;
        if (pitch != 0) {
            Vec3 r = new Vec3(-t1.z(), 0, t1.x());
            double len = r.length();
            if (len > 1e-9) {
                q2 = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(pitch)).mul(q1);
            }
        }
        Vec3 u2 = q2.U(), ut = target.U(), ax = q2.T();
        double roll = Math.toDegrees(Math.atan2(
            u2.cross(ut).dot(ax), u2.dot(ut)));
        return new double[]{ yaw, pitch, roll };
    }

    public static double wrap180(double d) {
        d = d % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    /** ±360 wrap — the spinners' range; leaves anything inside it
     *  untouched (bitwise), so historic values never move. */
    public static double wrap360(double d) {
        while (d > 360) d -= 360;
        while (d < -360) d += 360;
        return d;
    }

    /** decomposeEntry's BRANCH-CONTINUOUS twin (v19.1, D224 —
     *  "pass through vertical seamlessly"): the same inverse, but
     *  where two delta triples reproduce `target` — the PRIMARY
     *  branch (yaw = heading gap, |elevation gap| pitch) and the
     *  FLIPPED one (yaw ± 180°, pitch carried OVER the pole:
     *  ±180 − e₁ − eₜ, roll ± 180°) — it returns the triple NEAREST
     *  the hints by total angular change, each component wrapped to
     *  the hint's own 360° window. A local ring drag crossing the
     *  vertical therefore keeps yaw/roll continuous and lets pitch
     *  run past 90° (the old canonical decompose jumped yaw and roll
     *  by 180° there — with a shared yaw that would have flipped the
     *  DESIGN tier too, and the ±85° clamp made the "wobble" the user
     *  saw), while a rigid yaw carry of any size still comes out as
     *  the pure yaw it is (the flipped branch costs ~360° there).
     *  null where either heading is undefined (vertical T). Hints of
     *  (0,0,0) reproduce decomposeEntry for every |pitch| < 90 case. */
    public static double[] decomposeNear(Quat base, Quat target,
                                         double yawHint, double pitchHint,
                                         double rollHint) {
        Vec3 tb = base.T(), tt = target.T();
        double hb = Math.hypot(tb.x(), tb.z());
        double ht = Math.hypot(tt.x(), tt.z());
        if (hb < 1e-6 || ht < 1e-6) return null;
        double ycan = wrap180(Math.toDegrees(
            Math.atan2(-tt.z(), tt.x()) - Math.atan2(-tb.z(), tb.x())));
        double et = Math.toDegrees(Math.atan2(tt.y(), ht));
        double[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (int branch = 0; branch < 2; branch++) {
            double y0 = branch == 0 ? ycan : ycan + 180;
            double yaw = nearestTurn(y0, yawHint);
            Quat q1 = yaw == 0 ? base : Quat.aboutY(yaw).mul(base);
            Vec3 t1 = q1.T();
            double e1 = Math.toDegrees(
                Math.atan2(t1.y(), Math.hypot(t1.x(), t1.z())));
            // primary: rotate the elevation gap; flipped: carry T
            // over the top (or under) onto the reversed heading —
            // both signs are the same rotation mod 360, so the
            // window wrap toward the hint picks between them
            double p0 = branch == 0 ? et - e1 : 180 - e1 - et;
            double pitch = nearestTurn(p0, pitchHint);
            Quat q2 = q1;
            if (pitch != 0) {
                Vec3 r = new Vec3(-t1.z(), 0, t1.x());
                double len = r.length();
                if (len > 1e-9) {
                    q2 = Quat.aboutAxis(r.scale(1 / len),
                        Math.toRadians(pitch)).mul(q1);
                }
            }
            Vec3 u2 = q2.U(), ut = target.U(), ax = q2.T();
            double r0 = Math.toDegrees(Math.atan2(
                u2.cross(ut).dot(ax), u2.dot(ut)));
            double roll = nearestTurn(r0, rollHint);
            double cost = Math.abs(yaw - yawHint) + Math.abs(pitch - pitchHint)
                + Math.abs(roll - rollHint);
            if (cost < bestCost - 1e-9) {
                bestCost = cost;
                best = new double[]{ yaw, pitch, roll };
            }
        }
        return best;
    }

    /** angle + 360k nearest the hint. */
    static double nearestTurn(double angle, double hint) {
        return angle + 360 * Math.rint((hint - angle) / 360);
    }

    // -----------------------------------------------------------------
    // ABSOLUTE ORIENTATION DISPLAY (v19.1, D224 — user spec: "what is
    // in the boxes is the REAL starting orientation"). The MODEL keeps
    // baseOrient + deltas (a split's boundary pose lands in baseOrient
    // bitwise, D71/D199); the panels show CANONICAL heading/elevation/
    // bank of the entry: heading = base heading + yaw, elevation =
    // base elevation + pitch, bank = base bank + roll — exact by the
    // composition's construction (yaw about Y keeps elevation and
    // bank, pitch about the horizontal right keeps heading and bank,
    // roll about T keeps both) — EXCEPT past the pole: a raw
    // elevation beyond ±90° reads as the reversed heading (+180),
    // the mirrored elevation and the bank +180 (the frame's level-up
    // reference flips with it). That flip is what "passes through
    // vertical seamlessly": the RAW deltas run continuously (a ring
    // drag drives them raw), only the numbers flip at the pole. Every
    // display returns the delta itself for a fresh piece (identity
    // base = zeros exactly, bitwise the pre-D224 boxes) and for a
    // vertical base (no heading to add to).
    // -----------------------------------------------------------------

    /** {heading, elevation, bank} of baseOrient in the delta
     *  convention (decomposeEntry off the identity), or null for a
     *  vertical base. */
    double[] baseEuler() {
        return decomposeEntry(Quat.IDENTITY, baseOrient);
    }

    /** Reduce a raw absolute elevation to the pole-side branch:
     *  {canonical elevation, flip (0/1), the 360k window base}. */
    private static double[] reduceElev(double rawAbs) {
        double m = wrap180(rawAbs);
        double k = rawAbs - m;   // the 360k window
        if (m > 90) return new double[]{ 180 - m, 1, k };
        if (m < -90) return new double[]{ -180 - m, 1, k };
        return new double[]{ m, 0, k };
    }

    /** RAW absolute elevation of the true entry (base + delta,
     *  unreduced) — the ring drags' continuous coordinate. */
    public double rawAbsPitch() {
        double[] b = baseEuler();
        return (b == null ? 0 : b[1]) + pitchDeg;
    }

    public double rawAbsYaw() {
        double[] b = baseEuler();
        return (b == null ? 0 : b[0]) + yawDeg;
    }

    public double rawAbsRoll() {
        double[] b = baseEuler();
        return (b == null ? 0 : b[2]) + rollDeg;
    }

    /** Canonical displayed heading (internal sense — the UI negates
     *  per D186), ±360. */
    public double dispYaw() {
        double[] b = baseEuler();
        if (b == null) return yawDeg;
        double flip = reduceElev(b[1] + pitchDeg)[1];
        return wrap360(b[0] + yawDeg + 180 * flip);
    }

    /** Canonical displayed elevation, [−90, 90]. */
    public double dispPitch() {
        double[] b = baseEuler();
        if (b == null) return pitchDeg;
        return reduceElev(b[1] + pitchDeg)[0];
    }

    /** Canonical displayed bank, ±360. */
    public double dispRoll() {
        double[] b = baseEuler();
        if (b == null) return rollDeg;
        double flip = reduceElev(b[1] + pitchDeg)[1];
        return wrap360(b[2] + rollDeg + 180 * flip);
    }

    /** The DESIGN tier's displayed elevation / bank (its own flip;
     *  heading is shared with the true tier). */
    public double dispDesignPitch() {
        double[] b = baseEuler();
        if (b == null) return designPitchDeg;
        return reduceElev(b[1] + designPitchDeg)[0];
    }

    public double dispDesignRoll() {
        double[] b = baseEuler();
        if (b == null) return designRollDeg;
        double flip = reduceElev(b[1] + designPitchDeg)[1];
        return wrap360(b[2] + designRollDeg + 180 * flip);
    }

    /** Displayed heading := v (internal sense). Yaw is shared by both
     *  tiers, so there is nothing to route. */
    public void setDispYaw(double v) {
        double[] b = baseEuler();
        if (b == null) {
            yawDeg = wrap360(v);
            return;
        }
        // computed from the TYPED value + the base only (never from
        // the current delta): a fresh piece stores v bitwise, and a
        // typed round-trip returns bitwise
        double flip = reduceElev(b[1] + pitchDeg)[1];
        yawDeg = wrap360(v - b[0] - 180 * flip);
    }

    /** Displayed elevation := v on the CURRENT pole branch (so a
     *  piece past the pole keeps its heading and moves along its
     *  mirrored scale); route = the D222 edit routing (true only vs
     *  lockstep), false = a plain true-tier write (bridge rows). */
    public void setDispPitch(double v, boolean route) {
        double[] b = baseEuler();
        double np;
        if (b == null) {
            np = v;
        } else {
            double raw = b[1] + pitchDeg;
            double[] red = reduceElev(raw);
            double target;
            if (red[1] == 0) {
                target = red[2] + v;
            } else {
                double m = wrap180(raw);
                target = red[2] + (m > 0 ? 180 - v : -180 - v);
            }
            np = target - b[1];
        }
        if (route) editPitch(np);
        else pitchDeg = np;
    }

    /** Displayed bank := v (the shortest way is NOT taken — the
     *  spinner's ±360 is honored, like the roll box always was). */
    public void setDispRoll(double v, boolean route) {
        double[] b = baseEuler();
        double nr;
        if (b == null) {
            nr = v;
        } else {
            double flip = reduceElev(b[1] + pitchDeg)[1];
            nr = v - b[2] - 180 * flip;
        }
        if (route) editRoll(nr);
        else rollDeg = wrap360(nr);
    }

    /** RAW-coordinate writes for the ring drags (absolute-from-press,
     *  continuous through the pole): raw absolute := v. */
    public void setRawAbsYaw(double v, boolean route) {
        double[] b = baseEuler();
        yawDeg = wrap360(b == null ? v : v - b[0]);
    }

    public void setRawAbsPitch(double v, boolean route) {
        double[] b = baseEuler();
        double np = b == null ? v : v - b[1];
        if (route) editPitch(np);
        else pitchDeg = np;
    }

    public void setRawAbsRoll(double v, boolean route) {
        double[] b = baseEuler();
        double nr = b == null ? v : v - b[2];
        if (route) editRoll(nr);
        else rollDeg = wrap360(nr);
    }

    /** BAKE every orientation delta into the base orientation (v16.1,
     *  D106 — the resolve ops' orientation resolution; the D100
     *  split/reverse corner as an explicit op): the entry pose is
     *  IDENTICAL afterwards, but genPose == entryPose from here on,
     *  so sections generate FROM the real frame — force sections
     *  re-solve against gravity there and the tilt's design/real
     *  orientation split closes. Yaw folds in too (the split/reverse
     *  convention: the natural orientation is redefined as the
     *  current one; zeroing the deltas afterwards changes nothing). */
    public void bakeTilt() {
        if (pitchDeg == 0 && rollDeg == 0
                && designPitchDeg == 0 && designRollDeg == 0) {
            // pure-yaw bake: the canonical pair survives EXACTLY —
            // heading is additive (D199)
            if (canonOrient != null) canonPsiDeg += yawDeg;
        } else {
            // a real tilt re-solves generation (the D100 corner):
            // the stored chain state no longer describes it
            canonOrient = null;
        }
        baseOrient = entryPose().orient();
        yawDeg = 0;
        pitchDeg = 0;
        rollDeg = 0;
        // the design tier bakes in too (D222): generation resumes
        // from the real frame, both tiers are now the base
        designPitchDeg = 0;
        designRollDeg = 0;
    }

    /** The rigid TILT the pitch/roll deltas add on top of genPose():
     *  entryPose().orient() == tilt().mul(genPose().orient()) by
     *  construction (identical axis math). Returns Quat.IDENTITY —
     *  the same object — when both deltas are 0, so the compiler's
     *  identity short-circuit keeps every untilted piece bitwise. */
    public Quat tilt() {
        if (pitchDeg == 0 && rollDeg == 0) return Quat.IDENTITY;
        Quat q = yawDeg == 0 ? baseOrient
            : Quat.aboutY(yawDeg).mul(baseOrient);
        Quat tilt = Quat.IDENTITY;
        if (pitchDeg != 0) {
            Vec3 t = q.T();
            Vec3 r = new Vec3(-t.z(), 0, t.x());
            double len = r.length();
            if (len > 1e-9) {
                Quat pq = Quat.aboutAxis(r.scale(1 / len),
                    Math.toRadians(pitchDeg));
                tilt = pq;
                q = pq.mul(q);
            }
        }
        if (rollDeg != 0) {
            tilt = Quat.aboutAxis(q.T(), Math.toRadians(rollDeg)).mul(tilt);
        }
        return tilt;
    }
}
