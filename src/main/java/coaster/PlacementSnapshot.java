package coaster;

import java.util.ArrayList;
import java.util.List;

/** THE FULL-SCOPE PLACEMENT RECORD of a coaster (v19.20, D249 — the
 *  three live windows' Cancel/Esc, user spec: "the open-time record =
 *  a full placement snapshot of EVERY piece"): every piece's
 *  placement fields (position, yaw, both orientation tiers,
 *  baseOrient + the canonical pair, the routing mode + carry flag,
 *  the Track Piece edit guards, the strict-seam follow flag, locks,
 *  held angles and smoothing length) plus every trailing
 *  BridgeSection's placement fields (mode, Optimize-length check,
 *  requested length, septic tangents, axis locks, the D97 manual
 *  stash and the TRANSIENT optSig — SIG_STALE / null identities
 *  kept, a value cloned). Sections, lanes and speed controls are NOT
 *  recorded (graph edits made while a window is open survive its
 *  Cancel). Restore is BY OBJECT IDENTITY and DIRECT — a field write,
 *  never placementEdit: the D221 carry is not an exact inverse
 *  (eulerOf/decomposeNear/wrap360 arithmetic, re-marching targets),
 *  and the routing flags may have changed mid-window; a piece that
 *  vanished is skipped, a piece that appeared is untouched, a bridge
 *  whose object was replaced (a convert) skips its bridge half. This
 *  supersedes D247's "a mid-window Design → Real bake stays"
 *  (sameBase guard): baseOrient and the canonical pair are restored
 *  too, so the bake reverts. Pure Java — gated in OrientModeGate
 *  (h). */
public final class PlacementSnapshot {

    /** One piece's placement fields at capture time. */
    static final class PieceRec {
        final TrackPiece p;
        final double x, y, z, yaw, pitch, roll, dPitch, dRoll;
        final Quat base, canonOrient;
        final double canonPsi;
        final TrackPiece.OrientMode mode;
        final boolean affect, uiP, uiR, follow, lY, lP, lR;
        final double sYaw, sPitch, sRoll, sSmooth;
        // the trailing bridge (null = the piece ends in no bridge)
        final BridgeSection b;
        final int optMode;
        final boolean optLen, bLY, bLP, bLR;
        final double lastOptDist, lenA, lenB;
        final double[] noneStash, optSig;

        PieceRec(TrackPiece p) {
            this.p = p;
            x = p.x; y = p.y; z = p.z;
            yaw = p.yawDeg; pitch = p.pitchDeg; roll = p.rollDeg;
            dPitch = p.designPitchDeg; dRoll = p.designRollDeg;
            base = p.baseOrient;
            canonOrient = p.canonOrient;
            canonPsi = p.canonPsiDeg;
            mode = p.orientMode;
            affect = p.affectSubsequentSeams;
            uiP = p.uiLockPitch; uiR = p.uiLockRoll;
            follow = p.followPrev;
            lY = p.sLockYaw; lP = p.sLockPitch; lR = p.sLockRoll;
            sYaw = p.sYawDeg; sPitch = p.sPitchDeg; sRoll = p.sRollDeg;
            sSmooth = p.sSmoothLen;
            BridgeSection bs = trailing(p);
            b = bs;
            if (bs != null) {
                optMode = bs.optMode;
                optLen = bs.optLen;
                bLY = bs.lockYaw; bLP = bs.lockPitch; bLR = bs.lockRoll;
                lastOptDist = bs.lastOptDist;
                lenA = bs.lenA; lenB = bs.lenB;
                noneStash = bs.noneStash == null ? null : bs.noneStash.clone();
                optSig = cloneSig(bs.optSig);
            } else {
                optMode = 0;
                optLen = bLY = bLP = bLR = false;
                lastOptDist = lenA = lenB = 0;
                noneStash = optSig = null;
            }
        }

        /** Does the piece (or its bridge) differ from the record in
         *  ANY recorded field? Bitwise on doubles (a hand-reverted
         *  edit that lands on the same bits is no change). */
        boolean differs() {
            if (!bits(p.x, x) || !bits(p.y, y) || !bits(p.z, z)
                    || !bits(p.yawDeg, yaw) || !bits(p.pitchDeg, pitch)
                    || !bits(p.rollDeg, roll)
                    || !bits(p.designPitchDeg, dPitch)
                    || !bits(p.designRollDeg, dRoll)
                    || p.baseOrient != base || p.canonOrient != canonOrient
                    || !bits(p.canonPsiDeg, canonPsi)
                    || p.orientMode != mode
                    || p.affectSubsequentSeams != affect
                    || p.uiLockPitch != uiP || p.uiLockRoll != uiR
                    || p.followPrev != follow
                    || p.sLockYaw != lY || p.sLockPitch != lP
                    || p.sLockRoll != lR
                    || !bits(p.sYawDeg, sYaw) || !bits(p.sPitchDeg, sPitch)
                    || !bits(p.sRollDeg, sRoll)
                    || !bits(p.sSmoothLen, sSmooth)) {
                return true;
            }
            BridgeSection bs = trailing(p);
            if (bs == null || bs != b) return false;   // replaced: skipped
            return bs.optMode != optMode || bs.optLen != optLen
                || bs.lockYaw != bLY || bs.lockPitch != bLP
                || bs.lockRoll != bLR
                || !bits(bs.lastOptDist, lastOptDist)
                || !bits(bs.lenA, lenA) || !bits(bs.lenB, lenB)
                || !sameArr(bs.noneStash, noneStash)
                || !sameSig(bs.optSig, optSig);
        }

        void restore() {
            p.x = x; p.y = y; p.z = z;
            p.yawDeg = yaw; p.pitchDeg = pitch; p.rollDeg = roll;
            p.designPitchDeg = dPitch; p.designRollDeg = dRoll;
            p.baseOrient = base;
            p.canonOrient = canonOrient;
            p.canonPsiDeg = canonPsi;
            p.orientMode = mode;
            p.affectSubsequentSeams = affect;
            p.uiLockPitch = uiP; p.uiLockRoll = uiR;
            p.followPrev = follow;
            p.sLockYaw = lY; p.sLockPitch = lP; p.sLockRoll = lR;
            p.sYawDeg = sYaw; p.sPitchDeg = sPitch; p.sRollDeg = sRoll;
            p.sSmoothLen = sSmooth;
            BridgeSection bs = trailing(p);
            if (bs == null || bs != b) return;   // replaced: skipped
            bs.optMode = optMode;
            bs.optLen = optLen;
            bs.lockYaw = bLY; bs.lockPitch = bLP; bs.lockRoll = bLR;
            bs.lastOptDist = lastOptDist;
            bs.lenA = lenA; bs.lenB = lenB;
            bs.noneStash = noneStash == null ? null : noneStash.clone();
            bs.optSig = cloneSig(optSig);
        }
    }

    private final List<PieceRec> recs = new ArrayList<>();
    /** The coaster the record was taken on (identity). */
    public final Coaster coaster;

    private PlacementSnapshot(Coaster c) {
        coaster = c;
        for (TrackPiece p : c.pieces) recs.add(new PieceRec(p));
    }

    /** Record every piece of `c` now. */
    public static PlacementSnapshot capture(Coaster c) {
        return new PlacementSnapshot(c);
    }

    /** Number of pieces recorded. */
    public int size() { return recs.size(); }

    /** Does any SURVIVING recorded piece (or its bridge) differ from
     *  its record? Vanished pieces never count; pieces that appeared
     *  since are not recorded. */
    public boolean differs() {
        for (PieceRec r : recs) {
            if (coaster.pieces.contains(r.p) && r.differs()) return true;
        }
        return false;
    }

    /** Restore every surviving recorded piece's fields DIRECTLY;
     *  returns how many pieces were written (vanished ones skipped).
     *  Callers push undo / rebuild themselves. */
    public int restore() {
        int n = 0;
        for (PieceRec r : recs) {
            if (!coaster.pieces.contains(r.p)) continue;
            r.restore();
            n++;
        }
        return n;
    }

    /** The piece's trailing bridge, or null. */
    static BridgeSection trailing(TrackPiece p) {
        if (p.sections.isEmpty()) return null;
        Section s = p.sections.get(p.sections.size() - 1);
        return s instanceof BridgeSection bs ? bs : null;
    }

    /** optSig clone keeping the two sentinel identities (null = first
     *  sight, SIG_STALE = a deliberate re-march). */
    static double[] cloneSig(double[] sig) {
        if (sig == null || sig == BridgeSection.SIG_STALE) return sig;
        return sig.clone();
    }

    static boolean sameSig(double[] a, double[] b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a == BridgeSection.SIG_STALE || b == BridgeSection.SIG_STALE) {
            return false;
        }
        return sameArr(a, b);
    }

    static boolean sameArr(double[] a, double[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!bits(a[i], b[i])) return false;
        }
        return true;
    }

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }
}
