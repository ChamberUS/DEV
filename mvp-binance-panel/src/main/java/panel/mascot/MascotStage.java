package panel.mascot;

import javafx.geometry.Insets;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/**
 * Palco do mascote: disco derivado dos TOKENS do tema (BYX_DESIGN_TOKENS.json). O corpo do mascote é quase preto e SOME sobre as superfícies escuras (bg0 #0B0E16 … bg3 #232A3D), e os pontos cinza
 * do THINKING têm alfa; por isso o palco é um disco claro-ardósia (centro = highContrast.text.secondary #C3CADB, borda = text.secondary #AAB3C7) com anel surface.line (#2A3144) e um halo discreto
 * (accent do contexto a ~12%). Sem quadrado, sem borda de vídeo. O desenho do mascote não é alterado: só o fundo sobre o qual ele é visto.
 */
public final class MascotStage extends Region {
    static final Color CENTER = Color.web("#C3CADB");
    static final Color EDGE = Color.web("#AAB3C7");
    static final Color RING = Color.web("#2A3144");
    public static final Color ACCENT_BYX = Color.web("#5ED6C4");

    public MascotStage(double size, Color accent) {
        double r = size / 2;
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setBackground(new Background(new BackgroundFill(new RadialGradient(0, 0, 0.42, 0.38, 0.75, true, CycleMethod.NO_CYCLE, new Stop(0, CENTER), new Stop(1, EDGE)), new CornerRadii(r), Insets.EMPTY)));
        setBorder(new Border(new BorderStroke(RING, BorderStrokeStyle.SOLID, new CornerRadii(r), new BorderWidths(Math.max(1, size / 96)))));
        if (accent != null) {
            DropShadow glow = new DropShadow(size * 0.14, Color.color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.12));
            glow.setSpread(0.02);
            setEffect(glow);
        }
        setMouseTransparent(true);
        getStyleClass().add("byx-mascot-stage");
    }
}
