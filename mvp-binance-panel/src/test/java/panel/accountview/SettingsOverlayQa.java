package panel.accountview;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import panel.design.ByxTheme;
import panel.design.OverlayLayer;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;

/** Native JavaFX only, isolated in-memory authorized preferences; no production mutations. */
public final class SettingsOverlayQa {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]); Files.createDirectories(out);
        List<String> evidence = new ArrayList<>();
        Throwable[] failure = {null}; CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                for (int[] size : new int[][] {{1920,1080},{1440,900},{1100,700}}) {
                    MotionService motion = new MotionService(); motion.preference.set(MotionPreference.OFF);
                    ShellRouter router = new ShellRouter(new Navigator(), (t,k) -> ShellRouter.Decision.ALLOW, t -> {});
                    ByxShell shell = new ByxShell(router,motion,new LegacyHost());
                    var data = new AccountScreensTest.Stub();
                    SettingsScreen settings = new SettingsScreen(motion,data,router::request,shell::overlay);
                    shell.v2Content().getChildren().add(settings.node()); shell.showV2(true); router.request("t-settings");
                    Scene scene = new Scene(shell,size[0],size[1]); ByxTheme.apply(scene);
                    Stage stage = new Stage(); stage.setScene(scene); stage.show();
                    settings.select("Appearance"); shell.applyCss(); shell.layout();
                    ((ToggleButton)settings.node().lookupAll(".byx-desk-seg-btn").stream()
                            .filter(n -> n instanceof ToggleButton b && "REDUCED".equals(b.getText())).findFirst().orElseThrow()).setSelected(true);
                    stage.getScene().setRoot(new javafx.scene.layout.Pane());
                    scene = new Scene(shell,size[0],size[1]); ByxTheme.apply(scene);
                    shell.applyCss(); shell.layout();
                    var bar = shell.overlay().layer(OverlayLayer.SAVEBAR).getChildren().getFirst();
                    evidence.add(size[0]+"x"+size[1]+" savebar="+bar.getLayoutBounds()+" host="+shell.overlay().getLayoutBounds());
                    var image = scene.snapshot(null); BufferedImage png = new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
                    for(int y=0;y<png.getHeight();y++) for(int x=0;x<png.getWidth();x++) png.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                    javax.imageio.ImageIO.write(png,"png",out.resolve("settings-dirty-"+size[0]+"x"+size[1]+".png").toFile());
                    settings.dispose(); shell.dispose(); stage.close();
                }
                Files.write(out.resolve("measurements.txt"),evidence);
            } catch(Throwable t) {failure[0]=t;} finally {done.countDown();}
        });
        done.await(); Platform.exit(); if(failure[0]!=null) throw new RuntimeException(failure[0]);
        evidence.forEach(System.out::println);
    }
}
