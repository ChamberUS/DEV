package panel.shell.avatar;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/**
 * Visual QA of the REAL JavaFX avatar (not run by surefire). Renders every state at 6x from the actual scene graph into numbered PNGs and a
 * labelled contact sheet. Usage: java -cp target/classes:target/test-classes:DEPS panel.shell.avatar.AvatarVisualQa OUTDIR
 */
public final class AvatarVisualQa {
    private static final double ZOOM = 6;

    private final MotionService motion = new MotionService();
    private final AtomicLong clock = new AtomicLong(10_000);
    private final Button button = new Button();
    private final StackPane around = new StackPane(button);
    private final StackPane root = new StackPane(around);
    private final Scene scene = new Scene(root, 600, 400);
    private final Stage stage = new Stage();
    private MascotAvatar avatar;
    private final List<String> names = new ArrayList<>();
    private final List<WritableImage> images = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = {null};
        Platform.startup(() -> {
            try {
                new AvatarVisualQa().run(out);
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(1);
        }
        System.out.println("AVATAR_VISUAL_QA_OK " + out);
    }

    private void frames(long ms) {
        for (long t = 0; t < ms; t += 16) {
            clock.addAndGet(16);
            avatar.frame(clock.get());
        }
    }

    private void shot(String name) {
        root.applyCss();
        root.layout();
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.web("#0B0E16"));
        sp.setTransform(Transform.scale(ZOOM, ZOOM));
        double bx = button.localToScene(0, 0).getX() - 6;
        double by = button.localToScene(0, 0).getY() - 6;
        sp.setViewport(new javafx.geometry.Rectangle2D(bx * ZOOM, by * ZOOM, 48 * ZOOM, 48 * ZOOM)); // viewport is in transformed space
        WritableImage img = root.snapshot(sp, null);
        names.add(name);
        images.add(img);
    }

    private void run(Path out) throws Exception {
        button.getStyleClass().add("byx-avatar");
        button.setMinSize(36, 36);
        button.setPrefSize(36, 36);
        button.setMaxSize(36, 36);
        root.setStyle("-fx-background-color: #0B0E16;");
        ByxTheme.apply(scene);
        stage.setScene(scene);
        motion.preference.set(MotionPreference.FULL);
        avatar = new MascotAvatar(button, motion, clock::get, false);
        avatar.setUserName("QA");
        stage.show();
        avatar.windowActiveForTest(true);
        frames(3_000);
        shot("idle");

        // pointer in the four quadrants (the avatar sits at the scene centre)
        double[][] spots = {{590, 390}, {10, 10}, {590, 10}, {10, 390}};
        String[] where = {"gaze-bottom-right", "gaze-top-left", "gaze-top-right", "gaze-bottom-left"};
        for (int i = 0; i < spots.length; i++) {
            javafx.event.Event.fireEvent(root, new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_MOVED, spots[i][0], spots[i][1],
                    spots[i][0], spots[i][1], javafx.scene.input.MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
            frames(900);
            shot(where[i]);
        }
        avatar.engine().clearPointer();
        frames(1_500);

        avatar.engine().setHover(true);
        frames(400);
        shot("hover");
        avatar.engine().setHover(false);
        avatar.engine().setPressed(true);
        frames(300);
        shot("pressed");
        avatar.engine().setPressed(false);
        frames(300);

        // click reaction (490 ms): sample the three phases
        long t0 = clock.get();
        avatar.engine().react(t0);
        frames(70);
        shot("reaction-60ms");
        frames(110);
        shot("reaction-180ms-hold");
        frames(240);
        shot("reaction-420ms-return");
        frames(500);

        javafx.beans.property.SimpleBooleanProperty menu = new javafx.beans.property.SimpleBooleanProperty();
        avatar.bindMenuOpen(menu);
        menu.set(true);
        frames(1_000);
        shot("menu-open-looks-at-menu");
        menu.set(false);
        frames(1_500);

        // loading (real operation token): rings enter after the 300 ms delay
        var tok = avatar.operations().begin("visual-qa");
        frames(330);
        shot("loading-enter");
        frames(350);
        shot("loading-t+0");
        frames(150);
        shot("loading-t+150");
        frames(150);
        shot("loading-t+300");
        // reaction during loading: body press only
        avatar.engine().react(clock.get());
        frames(180);
        shot("loading-click-press-only");
        frames(600);
        avatar.operations().end(tok, false, "VISUAL_QA");
        frames(500);
        shot("error");
        frames(4_600);

        motion.preference.set(MotionPreference.REDUCED);
        frames(200);
        var t2 = avatar.operations().begin("visual-qa-reduced");
        frames(1_000);
        shot("reduced-loading-static");
        avatar.operations().end(t2, true, null);
        frames(1_500);
        motion.preference.set(MotionPreference.OFF);
        frames(100);
        var t3 = avatar.operations().begin("visual-qa-off");
        frames(1_000);
        shot("off-loading-static");
        avatar.operations().end(t3, true, null);
        frames(1_500);
        shot("off-idle");
        contactSheet(out);
        avatar.dispose();
        stage.close();
    }

    private void contactSheet(Path out) throws Exception {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setStyle("-fx-background-color: #05070B; -fx-padding: 16;");
        int cols = 6;
        for (int i = 0; i < images.size(); i++) {
            ImageView v = new ImageView(images.get(i));
            Label l = new Label(names.get(i));
            l.setStyle("-fx-text-fill: #AAB3C7; -fx-font-size: 12px;");
            VBox cell = new VBox(4, v, l);
            cell.setAlignment(Pos.TOP_CENTER);
            grid.add(cell, i % cols, i / cols);
            writePng(images.get(i), out.resolve(String.format("%02d-%s.png", i + 1, names.get(i))));
        }
        Scene sheet = new Scene(grid);
        sheet.getStylesheets().setAll(scene.getStylesheets());
        grid.applyCss();
        grid.layout();
        WritableImage img = grid.snapshot(null, null);
        writePng(img, out.resolve("contact-sheet.png"));
    }

    private static void writePng(javafx.scene.image.Image image, Path file) throws Exception {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(bi, "png", file.toFile());
    }
}
