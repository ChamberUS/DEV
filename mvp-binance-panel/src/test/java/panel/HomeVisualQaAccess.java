package panel;

/** Public access to the PNG writer of {@link HomeVisualQa} for QA programs in other packages. */
public final class HomeVisualQaAccess {
    private HomeVisualQaAccess() {
    }

    public static void write(javafx.scene.image.Image image, java.nio.file.Path file) throws Exception {
        HomeVisualQa.write(image, file);
    }
}
