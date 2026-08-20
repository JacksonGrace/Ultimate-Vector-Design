import javafx.application.Platform;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.ParallelCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * Does JavaFX 26's ParallelCamera do transformed 3D with depth?
 * Offscreen snapshot, console analysis. Tests the exact transform
 * chain the designer would use for a TRUE orthographic top view:
 *   [pivot][yaw][pitch][scale s][translate(-w/2,-h/2, -D)]
 */
public class OrthoProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                run();
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    static void run() {
        scenario("C5: rotate+scale", false, 5);
        scenario("C6: rotate+scale+translate(xy)", false, 6);
        scenario("C7: rotate+scale+translate(xy,-100z)", false, 7);
        scenario("C8: rotate+scale+translate(xy,-16000z)", false, 8);
        scenario("C9: rotate+scale+translate(xy,+16000z)", false, 9);
    }

    static void scenario(String name, boolean persp, int chain) {
        System.out.println("--- " + name + " ---");
        int W = 400, H = 300;
        Group world = new Group();
        // red box at world origin (scene coords: y already flipped-ish
        // — irrelevant for the probe, we just track scene positions)
        world.getChildren().add(box(0, 0, 0, 10, Color.RED));
        // green box +30 scene-x
        world.getChildren().add(box(30, 0, 0, 10, Color.LIME));
        // blue box UNDER the red one (scene +y = down... we look down
        // -y? we pitch the camera -90 so it looks along +y): put it
        // 40 BELOW (scene +y) and bigger — depth test: it must hide
        // BEHIND red from a top-down camera
        world.getChildren().add(box(0, 40, 0, 20, Color.BLUE));
        world.getChildren().add(new AmbientLight(Color.WHITE));

        SubScene sub = new SubScene(world, W, H, true,
            SceneAntialiasing.DISABLED);
        sub.setFill(Color.BLACK);

        javafx.scene.Camera cam;
        double s = 0.25;   // world units per pixel
        if (persp) {
            javafx.scene.PerspectiveCamera pc =
                new javafx.scene.PerspectiveCamera(true);
            pc.setNearClip(0.1);
            pc.setFarClip(1e6);
            pc.setFieldOfView(40);
            pc.getTransforms().addAll(
                new Rotate(-90, Rotate.X_AXIS),
                new Translate(0, 0, -200));
            cam = pc;
        } else {
            ParallelCamera plc = new ParallelCamera();
            plc.setNearClip(0.1);
            plc.setFarClip(1e7);
            switch (chain) {
                case 5 -> plc.getTransforms().addAll(
                    new Rotate(-90, Rotate.X_AXIS), new Scale(s, s, s));
                case 6 -> plc.getTransforms().addAll(
                    new Rotate(-90, Rotate.X_AXIS), new Scale(s, s, s),
                    new Translate(-W / 2.0, -H / 2.0, 0));
                case 7 -> plc.getTransforms().addAll(
                    new Rotate(-90, Rotate.X_AXIS), new Scale(s, s, s),
                    new Translate(-W / 2.0, -H / 2.0, -100));
                case 8 -> plc.getTransforms().addAll(
                    new Rotate(-90, Rotate.X_AXIS), new Scale(s, s, s),
                    new Translate(-W / 2.0, -H / 2.0, -16000));
                case 9 -> plc.getTransforms().addAll(
                    new Rotate(-90, Rotate.X_AXIS), new Scale(s, s, s),
                    new Translate(-W / 2.0, -H / 2.0, 16000));
            }
            cam = plc;
        }
        sub.setCamera(cam);

        StackPane root = new StackPane(sub);
        new Scene(root, W, H);   // parents the tree for snapshot
        WritableImage img = new WritableImage(W, H);
        sub.snapshot(null, img);
        PixelReader pr = img.getPixelReader();

        report(pr, W, H, "RED ", c -> c.getRed() > 0.5
            && c.getGreen() < 0.3 && c.getBlue() < 0.3);
        report(pr, W, H, "GREEN", c -> c.getGreen() > 0.5
            && c.getRed() < 0.3 && c.getBlue() < 0.3);
        report(pr, W, H, "BLUE ", c -> c.getBlue() > 0.5
            && c.getRed() < 0.3 && c.getGreen() < 0.3);
    }

    interface ColTest { boolean is(Color c); }

    static Box box(double x, double y, double z, double size, Color col) {
        Box b = new Box(size, size, size);
        b.setTranslateX(x);
        b.setTranslateY(y);
        b.setTranslateZ(z);
        b.setMaterial(new PhongMaterial(col));
        return b;
    }

    static void report(PixelReader pr, int w, int h, String name, ColTest t) {
        int minX = w, maxX = -1, minY = h, maxY = -1, n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (t.is(pr.getColor(x, y))) {
                    n++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        System.out.println(name + " px=" + n + (n == 0 ? ""
            : " x[" + minX + ".." + maxX + "] y[" + minY + ".." + maxY
              + "] center=(" + (minX + maxX) / 2.0 + ","
              + (minY + maxY) / 2.0 + ")"));
    }
}
