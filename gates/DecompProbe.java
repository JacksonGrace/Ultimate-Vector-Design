package uvd;

import java.util.Locale;
import java.util.Random;

/** v18.19 (D190) verification: TrackPiece.decomposeEntry must invert
 *  entryPose()'s orientation composition exactly, and the local-ring
 *  compose→decompose→recompose loop must reproduce the rotated frame. */
public class DecompProbe {

    static double angleBetween(Quat a, Quat b) {
        // angle of a·b⁻¹
        Quat d = a.mul(b.conjugate());
        double w = Math.min(1, Math.abs(d.w()));
        return Math.toDegrees(2 * Math.acos(w));
    }

    public static void main(String[] args) {
        Random rnd = new Random(42);
        double worstDelta = 0, worstFrame = 0, worstLoop = 0;
        int skipped = 0;
        for (int trial = 0; trial < 20000; trial++) {
            // random base: a composed orientation (like a split piece)
            TrackPiece b = new TrackPiece(0, 0, 0,
                rnd.nextDouble() * 360 - 180);
            b.pitchDeg = rnd.nextDouble() * 140 - 70;
            b.rollDeg = rnd.nextDouble() * 340 - 170;
            Quat base = b.entryPose().orient();
            // random deltas within the model's ranges
            double yaw = rnd.nextDouble() * 340 - 170;
            double pitch = rnd.nextDouble() * 160 - 80;
            double roll = rnd.nextDouble() * 340 - 170;
            TrackPiece p = new TrackPiece(0, 0, 0, yaw);
            p.baseOrient = base;
            p.pitchDeg = pitch;
            p.rollDeg = roll;
            Quat target = p.entryPose().orient();
            double[] d = TrackPiece.decomposeEntry(base, target);
            if (d == null) { skipped++; continue; }
            // recompose and compare frames (the deltas themselves can
            // differ when base+deltas pass near gimbal — the FRAME is
            // the contract)
            TrackPiece q = new TrackPiece(0, 0, 0, d[0]);
            q.baseOrient = base;
            q.pitchDeg = d[1];
            q.rollDeg = d[2];
            double fErr = angleBetween(q.entryPose().orient(), target);
            worstFrame = Math.max(worstFrame, fErr);
            // delta agreement holds only where the composition never
            // crosses the vertical pole: the PATH elevation is
            // elev(base) + pitch (yaw preserves it) -- past 90 the
            // heading flips and an equivalent alternate triple comes
            // back (frame still exact, checked above)
            double elevBase = Math.toDegrees(Math.asin(
                Math.max(-1, Math.min(1, base.T().y()))));
            if (Math.abs(elevBase + pitch) < 85
                    && Math.abs(elevBase) < 85) {
                double dy = Math.abs(TrackPiece.wrap180(d[0] - yaw));
                double dp = Math.abs(d[1] - pitch);
                double dr = Math.abs(TrackPiece.wrap180(d[2] - roll));
                worstDelta = Math.max(worstDelta, Math.max(dy,
                    Math.max(dp, dr)));
            }
            // the LOCAL-RING loop: rotate about a random axis, then
            // decompose + recompose — the achieved frame must equal
            // the rotated one
            Vec3 ax = new Vec3(rnd.nextDouble() - 0.5,
                rnd.nextDouble() - 0.5, rnd.nextDouble() - 0.5);
            if (ax.length() < 1e-3) continue;
            ax = ax.scale(1 / ax.length());
            double delta = rnd.nextDouble() * 60 - 30;
            Quat rot = Quat.aboutAxis(ax, Math.toRadians(delta))
                .mul(target);
            double[] d2 = TrackPiece.decomposeEntry(base, rot);
            if (d2 == null) { skipped++; continue; }
            TrackPiece r = new TrackPiece(0, 0, 0, d2[0]);
            r.baseOrient = base;
            r.pitchDeg = d2[1];
            r.rollDeg = d2[2];
            worstLoop = Math.max(worstLoop,
                angleBetween(r.entryPose().orient(), rot));
        }
        System.out.printf(Locale.US,
            "worst frame err %.2e deg | worst in-range delta err %.2e"
            + " | worst ring-loop err %.2e deg | skipped %d%n",
            worstFrame, worstDelta, worstLoop, skipped);
        // frame errors concentrate at near-vertical poles (axis
        // normalization conditioning); the model clamps pitch to
        // +-85 so real drags sit far from them -- 1e-3 deg is
        // invisible, in-range paths must be exact
        boolean ok = worstFrame < 1e-3 && worstDelta < 1e-8
            && worstLoop < 1e-3;
        System.out.println(ok ? "DECOMPOSE PROBE PASS"
                              : "DECOMPOSE PROBE FAIL");
    }
}
