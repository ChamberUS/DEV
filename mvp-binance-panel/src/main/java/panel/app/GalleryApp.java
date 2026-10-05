package panel.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import panel.design.ByxTheme;
import panel.design.ControlGallery;
import panel.motion.MotionService;

/** Galeria de controles V2 (DEV ONLY). Abre com {@code Main --gallery}; não toca em sessão, backend ou rotas. */
public class GalleryApp extends Application {
    @Override
    public void start(Stage stage) {
        MotionService motion = new MotionService();
        ControlGallery gallery = new ControlGallery(motion);
        Scene scene = new Scene(gallery, 1440, 900);
        ByxTheme.apply(scene);
        stage.setTitle("BYX V2 control gallery (DEV ONLY)");
        stage.setMinWidth(1280);
        stage.setMinHeight(760);
        stage.setScene(scene);
        stage.show();
    }
}
