import coaster.*;
import java.util.*;

/** Where does the 48f stall fixture's design/true split sit now that
 *  both records are honest? (sqrt near zero amplifies profile
 *  residuals — measure the actual worst split + its speed zone) */
public class SplitProbe {
    public static void main(String[] args) throws Exception {
        // mirror the gate fixture via reflection-free rebuild is
        // complex — approximate: run the gate class's own fixture by
        // calling its main is noisy. Instead reproduce inline like
        // the gate: read Phase3Gate source constants? Simpler: just
        // print the split distribution from a similar stalled doc.
        // (identical construction to Phase3Gate.reverseStall)
        System.out.println("see gate rerun with diagnostics instead");
    }
}
