package uvd;

/**
 * STRAIGHT/CURVE LOOK (v20.0, D259 §9.5/§9.6 — the owner of the
 * element-kind colors and icons; every surface reads them: bands,
 * chips / band buttons, toolbar pick buttons, the gap, the ghost, the
 * element window's type chooser, the Help diagram). Pure Java, no
 * JavaFX — FvdDesigner.SC_KIND_COLOR / strokeScKindIcon / scKindIcon
 * are the JavaFX halves; ScLookGate e/f pins the recipes.
 *
 * Every table is indexed by {@link StraightCurveSection.Kind#ordinal()}
 * (STRAIGHT, PLANAR, HELICAL — the element model's order).
 */
public final class ScLook {
    private ScLook() {}

    /** D259 §9.5: the kind colors, rgb 0-255 — STRAIGHT blue
     *  (HSB 206°, .489, .922), PLANAR the historic curve rose (331°,
     *  .447, .922), HELICAL the rose's S and V at hue 280°. */
    public static final int[][] KIND_RGB = {
        { 120, 185, 235 },   // STRAIGHT
        { 235, 130, 180 },   // PLANAR
        { 200, 130, 235 },   // HELICAL
    };

    /** D259 §9.6: the icon box (px at scale 1, y down). */
    public static final double ICON_W = 14, ICON_H = 10;

    /** D259 §9.6 HEAD LAW: arrowhead length and half-width (px). */
    public static final double HEAD_LEN = 3.0, HEAD_HALF_W = 1.5;

    /** D259 §9.5: pick-button backgrounds — unselected = the TOOLBTN
     *  bg rgb(45,50,62) interpolated 0.25 toward the kind; selected =
     *  the kind at HSB saturation ×1.5 (hue, brightness kept). The
     *  table's rounded results, verbatim. */
    private static final int[][] PICK_BG = {
        { 64, 84, 105 },     // STRAIGHT
        { 93, 70, 92 },      // PLANAR
        { 84, 70, 105 },     // HELICAL
    };
    private static final int[][] PICK_BG_SEL = {
        { 63, 160, 235 },    // STRAIGHT
        { 235, 78, 153 },    // PLANAR
        { 183, 78, 235 },    // HELICAL
    };

    // PLANAR arc: center (7, 5), r 3.8, θ = 125° → 415° (circleArrowIcon's
    // 70° gap at 12 o'clock, counterclockwise), 32 steps
    private static final double ARC_CX = 7, ARC_CY = 5, ARC_R = 3.8,
        ARC_TH0 = 125, ARC_TH1 = 415;
    private static final int ARC_STEPS = 32;
    // HELICAL coil: x = 1 + a(t − π) − 3 sin t, y = 4.5 − 3 cos t,
    // a = 9.5/(4π), t = π → 5π (two loops), 64 steps
    private static final double COIL_A = 9.5 / (4 * Math.PI);
    private static final int COIL_STEPS = 64;

    private static int idx(int kind) {
        return kind < 0 ? 0 : kind > 2 ? 2 : kind;
    }

    /** D259 §9.6: the kind's icon stroke as a polyline {x0,y0,x1,y1,…}
     *  in the 14×10 box (y down) — STRAIGHT the line (1,5)→(10.5,5);
     *  PLANAR the arc center (7,5) r 3.8, points (7 + 3.8 cos θ,
     *  5 − 3.8 sin θ), θ = 125° → 415° in 32 steps; HELICAL the coil
     *  x = 1 + a(t − π) − 3 sin t, y = 4.5 − 3 cos t, a = 9.5/(4π),
     *  t = π → 5π in 64 steps. FIXED per kind (no κ-sign mirroring).
     *  A fresh array per call. */
    public static double[] iconStroke(int kind) {
        switch (idx(kind)) {
            case 1: {
                double[] p = new double[2 * (ARC_STEPS + 1)];
                for (int i = 0; i <= ARC_STEPS; i++) {
                    double th = Math.toRadians(ARC_TH0
                        + (ARC_TH1 - ARC_TH0) * i / ARC_STEPS);
                    p[2 * i] = ARC_CX + ARC_R * Math.cos(th);
                    p[2 * i + 1] = ARC_CY - ARC_R * Math.sin(th);
                }
                return p;
            }
            case 2: {
                double[] p = new double[2 * (COIL_STEPS + 1)];
                for (int i = 0; i <= COIL_STEPS; i++) {
                    double t = Math.PI + 4 * Math.PI * i / COIL_STEPS;
                    p[2 * i] = 1 + COIL_A * (t - Math.PI) - 3 * Math.sin(t);
                    p[2 * i + 1] = 4.5 - 3 * Math.cos(t);
                }
                return p;
            }
            default:
                return new double[] { 1, 5, 10.5, 5 };
        }
    }

    /** D259 §9.6 HEAD LAW (all kinds): the arrowhead triangle
     *  {tipX,tipY, b1x,b1y, b2x,b2y} — length 3.0, half-width 1.5,
     *  base CENTERED on the stroke's last point P (taken from
     *  {@link #iconStroke}, so stroke end == base midpoint exactly),
     *  tip = P + 3·t̂, base corners P ± 1.5·n̂. t̂ is the curve's
     *  ANALYTIC end tangent — the chord of the last two polyline
     *  points lags the arc by half a step (the planar tip would land
     *  at y −0.02, outside the box) — giving the table's tips
     *  STRAIGHT (13.5, 5), PLANAR (6.72, 0.17), HELICAL (13.5, 7.5). */
    public static double[] iconHead(int kind) {
        int k = idx(kind);
        double[] s = iconStroke(k);
        double px = s[s.length - 2], py = s[s.length - 1];
        double tx, ty;
        switch (k) {
            case 1: {   // d/dθ (cos θ, −sin θ) = (−sin θ, −cos θ)
                double th = Math.toRadians(ARC_TH1);
                tx = -Math.sin(th);
                ty = -Math.cos(th);
                break;
            }
            case 2: {   // d/dt = (a − 3 cos t, 3 sin t) at t = 5π
                double t = 5 * Math.PI;
                tx = COIL_A - 3 * Math.cos(t);
                ty = 3 * Math.sin(t);
                break;
            }
            default:
                tx = 1;
                ty = 0;
        }
        double n = Math.hypot(tx, ty);
        tx /= n;
        ty /= n;
        double nx = -ty, ny = tx;
        return new double[] {
            px + HEAD_LEN * tx, py + HEAD_LEN * ty,
            px + HEAD_HALF_W * nx, py + HEAD_HALF_W * ny,
            px - HEAD_HALF_W * nx, py - HEAD_HALF_W * ny,
        };
    }

    /** D259 §9.5: a kind pick button's background rgb — unselected
     *  (text = the kind color) STRAIGHT (64,84,105) / PLANAR (93,70,92)
     *  / HELICAL (84,70,105); selected (text rgb(15,17,22)) STRAIGHT
     *  (63,160,235) / PLANAR (235,78,153) / HELICAL (183,78,235). A
     *  fresh array per call. */
    public static int[] pickBg(int kind, boolean selected) {
        return (selected ? PICK_BG_SEL : PICK_BG)[idx(kind)].clone();
    }
}
