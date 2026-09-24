package coaster;

import java.util.List;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;

/** SPEED STRIP — the pure arithmetic behind the strip's tall-view
 *  DESIGN-speed line (v19.19, D248, item S2). Pure Java, no JavaFX,
 *  so StripGate pins it headlessly; GraphEditor.spDraw / spFitScales /
 *  the true-line hover delegate here.
 *  - {@link #split}: the lanes' drawTrueStrokes rule on the strip —
 *    the design line draws at a sample only where its pixel row
 *    differs from the true line's by MORE than TRUE_SPLIT_PX (a gap
 *    exactly at the threshold stays hidden); purely pixel-based, no
 *    ≠v / ≠o flag is read, so tilted pieces (≠o only), followers,
 *    bridges and pinned sections all show the split alike;
 *  - {@link #fitHi}: the strip's speed-scale fit takes the design
 *    speed as well as the true one (the D174 rule: a derived curve
 *    must never leave the strip);
 *  - {@link #designSuffix}: the hover readout's " (design 12.34)"
 *    tail — two decimals, no unit;
 *  - {@link #snapDisplay} (v19.20, D249, item S1): the strip's
 *    Ctrl-snap on the DISPLAY axis — a section-local ABSOLUTE grid in
 *    the displayed units (the strip never had a "previous node");
 *    GraphEditor.spSnapNative maps native → display, snaps here and
 *    maps back, so a control viewed in the other domain lands on the
 *    grid the user SEES;
 *  - {@link #posReadout} / {@link #posValue} (item S2): the position
 *    readout of a diamond / disk — "t = 3.20 s" (two decimals) or
 *    "s = 12.5 m" (one), Locale.US, essentially-zero values (|v| <
 *    1e-6, the FvdDesigner.z3 rule — a pure-Java copy, the shell class
 *    is JavaFX-side) print as +0; {@link #diamondAt}: the hover
 *    readout's draw-time "is the diamond still there" re-check (a
 *    resting cursor over an undone control must not keep it);
 *  - the line STYLES: design = SP_SPEED_COLOR at width DESIGN_W with
 *    DESIGN_DASH (the true line's old 1.2 / (3, 4) shrunk 1.5× — dots
 *    1.5× smaller and 1.5× denser), true = SP_TRUE_COLOR (brighter) at
 *    TRUE_W with TRUE_DASH. "Dimmer" is the thinner line, finer dots
 *    and darker green — never alpha, so the current true brightness
 *    is exactly the new design brightness (user spec). */
public final class SpeedStrip {

    private SpeedStrip() {}

    /** True-line width (px) — was 1.2 before D248. */
    public static final double TRUE_W = 1.6;
    /** True-line dashes — unchanged from the historic (3, 4). */
    public static final double[] TRUE_DASH = { 3, 4 };
    /** Design-line width = the old true width 1.2 / 1.5. */
    public static final double DESIGN_W = 0.8;
    /** Design-line dashes = (3, 4) / 1.5 — 1.5× smaller, 1.5× denser. */
    public static final double[] DESIGN_DASH = { 2, 4.0 / 1.5 };
    /** The true line's RGB (brighter than SP_SPEED_COLOR's
     *  (100, 220, 110), which stays the design line's and every
     *  other speed-typed element's color). Feel-tunable. */
    public static final int[] TRUE_RGB = { 160, 250, 170 };

    /** Does the design line DRAW at this sample? yDesign / yTrue are
     *  the two speeds' pixel rows on the strip scale; a gap of more
     *  than px (GraphEditor.TRUE_SPLIT_PX) draws, at or under it the
     *  design line is hidden and the hover shows no suffix. NaN in
     *  either row = hidden. */
    public static boolean split(double yDesign, double yTrue, double px) {
        double d = Math.abs(yDesign - yTrue);
        return d > px;   // NaN compares false = hidden
    }

    /** The speed-scale fit: vHi raised to every TRUE and DESIGN speed
     *  whose axis coordinate axis.applyAsDouble(i) lies in [a0, a1]
     *  (the run window). Reads only the first min(trueV.length,
     *  samples.size()) samples — a mid-rebuild length mismatch never
     *  throws. Null / empty inputs return vHi unchanged. */
    public static double fitHi(double vHi, double[] trueV,
                               List<FVDIntegrator.Sample> samples,
                               IntToDoubleFunction axis,
                               double a0, double a1) {
        if (trueV == null || samples == null || axis == null) return vHi;
        int n = Math.min(trueV.length, samples.size());
        for (int i = 0; i < n; i++) {
            double a = axis.applyAsDouble(i);
            if (a < a0 || a > a1) continue;
            vHi = Math.max(vHi, trueV[i]);
            vHi = Math.max(vHi, samples.get(i).v());
        }
        return vHi;
    }

    /** The true-line hover's design tail: " (design 12.34)" — two
     *  decimals, no unit (the speed unit is already on the readout). */
    public static String designSuffix(double vDesign) {
        return String.format(" (design %.2f)", vDesign);
    }

    /** Snap a DISPLAY-axis coordinate a onto the section-local
     *  absolute grid anchored at the section's own display origin a0:
     *  a0 + round((a − a0) / step)·step (v19.20, D249, item S1). With
     *  a0 = 0 this is the strip's historic native snap
     *  round(x / step)·step — GraphEditor keeps THAT code path literal
     *  when nothing is warped (bitwise), and routes only warped /
     *  cross-domain strips through here. step ≤ 0 returns a. */
    public static double snapDisplay(double a, double a0, double step) {
        if (!(step > 0)) return a;
        return a0 + Math.round((a - a0) / step) * step;
    }

    /** The FvdDesigner.z3 rule, pure-Java copy: |v| < 1e-6 → +0.0. */
    static double zeroDust(double v) {
        return Math.abs(v) < 1e-6 ? 0.0 : v;
    }

    /** A position VALUE with its unit: "12.5 m" (one decimal) on a
     *  meters axis, "3.20 s" (two decimals) on a seconds axis;
     *  Locale.US, zero dust snapped to +0 (item S2). */
    public static String posValue(double v, boolean meters) {
        return String.format(Locale.US, meters ? "%.1f m" : "%.2f s",
            zeroDust(v));
    }

    /** The diamond / disk position READOUT: "s = 12.5 m" or
     *  "t = 3.20 s" (item S2 — the strip hover, the strip drag and the
     *  viewport's bottom-left label all print this one string). */
    public static String posReadout(double v, boolean meters) {
        return (meters ? "s = " : "t = ") + posValue(v, meters);
    }

    /** Does a diamond still sit at native x on a section — a control's
     *  START or END, or a plain mark (|Δ| ≤ tol)? The strip hover's
     *  DRAW-TIME re-check (v19.20, D249, item S2 review fix): the
     *  hover stores only (piece, sec, native x) and re-resolves at
     *  draw time, so an undo that removes or moves the hovered
     *  diamond under a RESTING cursor must not leave "t = 3.20 s"
     *  floating where nothing is; the next mouse move re-hovers. */
    public static boolean diamondAt(List<SpeedControl> ctls,
                                    List<Double> marks, double nx,
                                    double tol) {
        if (ctls != null) {
            for (SpeedControl c : ctls) {
                if (Math.abs(c.x0 - nx) <= tol
                        || Math.abs(c.x0 + c.len - nx) <= tol) return true;
            }
        }
        if (marks != null) {
            for (double m : marks) if (Math.abs(m - nx) <= tol) return true;
        }
        return false;
    }
}
