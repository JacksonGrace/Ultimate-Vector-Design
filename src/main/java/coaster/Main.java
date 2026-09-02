package coaster;

/** LAUNCHER (v19.12, D241 — the Resolution Scale entry point).
 *
 *  This class deliberately does NOT extend javafx.application.
 *  Application: with the standard java launcher, a main class that
 *  extends Application gets the JavaFX toolkit pre-initialized
 *  BEFORE user main() runs — and glass.win.uiScale is consumed in
 *  WinApplication's static initializer, so a System.setProperty
 *  inside FvdDesigner.main() arrives too late (probed: ignored).
 *  Launching through this plain class defers toolkit init to
 *  Application.launch, after the property is set.
 *
 *  Semantics: the stored factor is RELATIVE to the OS display scale
 *  (the glass property REPLACES the OS scale, it doesn't multiply
 *  it — a naive absolute 1.0 default would shrink the app by a
 *  third on a 150% desktop). Factor 1.0 = don't set the property
 *  at all = the OS default, bitwise today's behavior. The OS scale
 *  is read pre-FX via AWT; any failure falls back to not setting
 *  the property (a malformed value CRASHES toolkit startup, so
 *  everything here is defensive). */
public final class Main {

    private Main() { }

    public static void main(String[] args) {
        try {
            double factor = new AppSettings().uiScale();   // pure Java
            factor = FvdDesigner.clamp(factor, 0.5, 2.0);
            if (Double.isFinite(factor) && Math.abs(factor - 1.0) > 1e-9) {
                double os = 1.0;
                try {
                    os = java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration()
                        .getDefaultTransform().getScaleX();
                } catch (Throwable t) { /* headless/odd AWT: factor-as-absolute */ }
                if (!Double.isFinite(os) || os <= 0) os = 1.0;
                double eff = FvdDesigner.clamp(os * factor, 0.5, 3.0);
                System.setProperty("glass.win.uiScale",
                    String.format(java.util.Locale.US, "%.4f", eff));
            }
        } catch (Throwable t) {
            // never let the scale plumbing keep the app from opening
        }
        javafx.application.Application.launch(FvdDesigner.class, args);
    }
}
