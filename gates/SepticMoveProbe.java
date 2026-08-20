import coaster.*;
import java.io.*;
import java.util.*;

/** Verifies the v17.14 manual-mode tangent rescale: hand-moving a
 *  NONE-mode bridge's target scales lenA/lenB with the gap (stale
 *  proportions stop developing); unmoved compiles stay bitwise. */
public class SepticMoveProbe {

    static Coaster load(String path) throws Exception {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            return DesignIO.load(in).coaster();
        }
    }

    static TrackCompiler.Result settle(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, 0.01);
        for (int i = 0; i < 6; i++) {
            boolean a = c.autoReoptimizeBridges(r);
            boolean b = c.reconcileChainedSpeeds(r);
            if (!a && !b) break;
            r = TrackCompiler.compile(c, 0.01);
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        String f3 = "C:\\Users\\jacks\\OneDrive\\Documents\\AI Fun\\"
            + "Odd bridge behavior 3 [7.24.26].fvd";
        Coaster c = load(f3);
        BridgeSection bs = (BridgeSection) c.pieces.get(0).sections.get(1);
        settle(c);
        double lenA0 = bs.lenA, gap0 = bs.solvedTarget.pos()
            .sub(bs.solvedEntry.pos()).length();
        settle(c);   // second compile, nothing moved
        System.out.printf(Locale.US,
            "unmoved: lenA %.10f -> %.10f (bitwise %b), gap %.3f%n",
            lenA0, bs.lenA, lenA0 == bs.lenA, gap0);
        // hand-drag the target +10 z (gap grows)
        TrackPiece tgt = c.pieces.get(1);
        tgt.z += 10;
        settle(c);
        double gap1 = bs.solvedTarget.pos()
            .sub(bs.solvedEntry.pos()).length();
        System.out.printf(Locale.US,
            "moved +10z: gap %.3f -> %.3f, lenA %.3f (prop %.4f vs %.4f)%n",
            gap0, gap1, bs.lenA, bs.lenA / gap1, lenA0 / gap0);
        // drag back: proportion (and ~length) restores
        tgt.z -= 10;
        settle(c);
        double gap2 = bs.solvedTarget.pos()
            .sub(bs.solvedEntry.pos()).length();
        System.out.printf(Locale.US,
            "moved back: gap %.3f, lenA %.6f (started %.6f)%n",
            gap2, bs.lenA, lenA0);
    }
}
