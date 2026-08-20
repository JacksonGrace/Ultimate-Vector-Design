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

/** Maps the ParallelCamera's effective depth window: a row of boxes
 *  along camera-local z; reports which render, for combinations of
 *  viewport height and near/far clip settings. */
public class ClipProbe {

    public static void main(String[] args) {
        Platform.startup(() -> {});
        Platform.runLater(() -> {
            try {
                probe(300, false);
                probe(300, true);
                probe(600, true);
                probe(600, false);
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                Platform.exit();
            }
        });
    }

    static void probe(int H, boolean setClips) {
        int W = 1700;
        double s = 1.0;   // 1 world unit per px for easy reading
        Group world = new Group(new AmbientLight(Color.WHITE));
        // boxes along world -y (ABOVE ground => camera-local +z for a
        // top view): local z from -2400..+2400 step 200, spread in x
        int k = 0;
        for (int z = -2400; z <= 2400; z += 200, k++) {
            Box b = new Box(60, 20, 60);
            b.setTranslateX(k * 80 - 840);   // fan across the screen
            b.setTranslateY(-z);             // scene -y = camera +z (top view... see chain)
            b.setMaterial(new PhongMaterial(Color.RED));
            world.getChildren().add(b);
        }
        SubScene sub = new SubScene(world, W, H, true,
            SceneAntialiasing.DISABLED);
        sub.setFill(Color.BLACK);
        ParallelCamera cam = new ParallelCamera();
        if (setClips) {
            cam.setNearClip(-1e6);
            cam.setFarClip(1e6);
        }
        cam.getTransforms().addAll(
            new Rotate(-90, Rotate.X_AXIS),
            new Scale(s, s, s),
            new Translate(-W / 2.0, -H / 2.0, 0));
        sub.setCamera(cam);
        new Scene(new StackPane(sub), W, H);
        WritableImage img = new WritableImage(W, H);
        sub.snapshot(null, img);
        PixelReader pr = img.getPixelReader();
        StringBuilder vis = new StringBuilder();
        k = 0;
        for (int z = -2400; z <= 2400; z += 200, k++) {
            int cx = k * 80 - 840 + W / 2;
            boolean on = false;
            for (int dx = -20; dx <= 20 && !on; dx += 5) {
                for (int dy = -20; dy <= 20 && !on; dy += 5) {
                    int px = cx + dx, py = H / 2 + dy;
                    if (px < 0 || px >= W || py < 0 || py >= H) continue;
                    if (pr.getColor(px, py).getRed() > 0.5) on = true;
                }
            }
            if (on) vis.append(z).append(' ');
        }
        System.out.println("H=" + H + " setClips=" + setClips
            + " visible localZ: " + vis);
    }
}
