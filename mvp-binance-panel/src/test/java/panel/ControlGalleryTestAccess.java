package panel;

import javafx.scene.image.WritableImage;

/** Acesso de outros pacotes de teste ao conversor de imagem da galeria (package-private). */
public final class ControlGalleryTestAccess {
    private ControlGalleryTestAccess() {
    }

    public static java.awt.image.BufferedImage toAwt(WritableImage img) {
        return ControlGalleryTest.toAwt(img);
    }
}
