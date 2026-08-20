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

/** (a) Is the parallel depth window W-dependent? (b) does a larger
 *  camera z-scale widen the WORLD window proportionally? (c) does
 *  depth ordering survive the non-uniform scale? */
public class ClipProbe2 {

    public static void main(String[] args) {
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                window(800, 1.0);
                window(1600, 1.0);
                window(800, 20.0);
                depth(20.0);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    /** Report the visible world-y window for a top view at s=1,
     *  camera z-scale = zs. Boxes along scene y every 100. */
    static void window(int W, double zs) {
        int H = 300;
        double s = 1.0;
        Group world = new Group(new AmbientLight(Color.WHITE));
        int k = 0;
        int n = 0;
        for (int z = -3000; z <= 3000; z += 100, k++) {
            n++;
            Box b = new Box(8, 20, 8);
            b.setTranslateX((k % 61) * 12 - 360);
            b.setTranslateY(z);
            b.setMaterial(new PhongMaterial(Color.RED));
            world.getChildren().add(b);
        }
        SubScene sub = new SubScene(world, W, H, true,
            SceneAntialiasing.DISABLED);
        sub.setFill(Color.BLACK);
        ParallelCamera cam = new ParallelCamera();
        cam.getTransforms().addAll(
            new Rotate(-90, Rotate.X_AXIS),
            new Scale(s, s, zs),
            new Translate(-W / 2.0, -H / 2.0, 0));
        sub.setCamera(cam);
        new Scene(new StackPane(sub), W, H);
        WritableImage img = new WritableImage(W, H);
        sub.snapshot(null, img);
        PixelReader pr = img.getPixelReader();
        int minZ = 99999, maxZ = -99999;
        k = 0;
        for (int z = -3000; z <= 3000; z += 100, k++) {
            int cx = (k % 61) * 12 - 360 + W / 2;
            boolean on = false;
            for (int dx = -4; dx <= 4 && !on; dx += 2) {
                for (int dy = -4; dy <= 4 && !on; dy += 2) {
                    int px = cx + dx, py = H / 2 + dy;
                    if (px < 0 || px >= W || py < 0 || py >= H) continue;
                    if (pr.getColor(px, py).getRed() > 0.5) on = true;
                }
            }
            if (on) {
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }
        System.out.println("W=" + W + " zs=" + zs
            + " visible worldY window: [" + minZ + ", " + maxZ + "]");
    }

    /** Depth ordering under non-uniform scale: red box 10 world units
     *  above a bigger blue box; top view must show red inside a blue
     *  ring, with red WINNING the overlap. */
    static void depth(double zs) {
        int W = 400, H = 300;
        Group world = new Group(new AmbientLight(Color.WHITE));
        Box red = new Box(20, 4, 20);
        red.setTranslateY(-10);
        red.setMaterial(new PhongMaterial(Color.RED));
        Box blue = new Box(60, 4, 60);
        blue.setMaterial(new PhongMaterial(Color.BLUE));
        world.getChildren().addAll(red, blue);
        SubScene sub = new SubScene(world, W, H, true,
            SceneAntialiasing.DISABLED);
        sub.setFill(Color.BLACK);
        ParallelCamera cam = new ParallelCamera();
        cam.getTransforms().addAll(
            new Rotate(-90, Rotate.X_AXIS),
            new Scale(1, 1, zs),
            new Translate(-W / 2.0, -H / 2.0, 0));
        sub.setCamera(cam);
        new Scene(new StackPane(sub), W, H);
        WritableImage img = new WritableImage(W, H);
        sub.snapshot(null, img);
        PixelReader pr = img.getPixelReader();
        Color center = pr.getColor(W / 2, H / 2);
        Color ring = pr.getColor(W / 2 + 25, H / 2);
        System.out.println("depth zs=" + zs + ": center="
            + fmt(center) + " (want RED)  ring=" + fmt(ring)
            + " (want BLUE)");
    }

    static String fmt(Color c) {
        return c.getRed() > 0.5 ? "RED" : c.getBlue() > 0.5 ? "BLUE"
            : c.toString();
    }
}
