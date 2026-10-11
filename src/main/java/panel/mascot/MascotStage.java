package panel.mascot;

import javafx.geometry.Insets;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
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
 * Tratamento de CONTRASTE do mascote (um só lugar; nenhuma tela escolhe cores). O corpo é quase preto e some sobre as superfícies escuras do tema, então:
 * <ul>
 * <li>{@link Mode#HALO} (PADRÃO): fundo TOTALMENTE transparente + contorno de silhueta sutil (glow da máscara alfa, 1–2 px, {@code text.secondary} #AAB3C7 a baixa opacidade). Sem círculo, sem card.</li>
 * <li>{@link Mode#TRANSPARENT}: nada (quando a tela já garante contraste).</li>
 * <li>{@link Mode#SURFACE}: disco preenchido derivado dos tokens; só quando realmente necessário (ex.: fundo variável de revisão).</li>
 * </ul>
 */
public final class MascotStage extends Region {
    public enum Mode { TRANSPARENT, HALO, SURFACE }

    public static final Mode DEFAULT_MODE = Mode.HALO;
    static final Color CENTER = Color.web("#C3CADB");
    static final Color EDGE = Color.web("#AAB3C7");
    static final Color RING = Color.web("#2A3144");
    static final Color HALO_COLOR = Color.web("#AAB3C7");
    public static final Color ACCENT_BYX = Color.web("#5ED6C4");

    private final double size;
    private Mode selectedMode;

    public MascotStage(double size, Mode mode) {
        this.size = size;
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setMouseTransparent(true);
        getStyleClass().add("byx-mascot-stage");
        apply(mode);
        panel.design.ByxTheme.observe(this, () -> apply(selectedMode));
    }

    public MascotStage(double size, Color ignoredAccent) {
        this(size, DEFAULT_MODE);
    }

    public void apply(Mode mode) {
        selectedMode = mode;
        setBackground(Background.EMPTY);
        setBorder(Border.EMPTY);
        if (mode == Mode.SURFACE) {
            double r = size / 2;
            setBackground(new Background(new BackgroundFill(new RadialGradient(0, 0, 0.42, 0.38, 0.75, true, CycleMethod.NO_CYCLE, new Stop(0, panel.design.ByxTheme.paint("#C3CADB", panel.design.ThemeToken.SURFACE_HOVER)), new Stop(1, panel.design.ByxTheme.paint("#AAB3C7", panel.design.ThemeToken.AVATAR_CONTAINER))), new CornerRadii(r), Insets.EMPTY)));
            setBorder(new Border(new BorderStroke(panel.design.ByxTheme.paint("#2A3144", panel.design.ThemeToken.AVATAR_BOUNDARY), BorderStrokeStyle.SOLID, new CornerRadii(r), new BorderWidths(Math.max(1, size / 96)))));
        }
    }

    /** Efeito aplicado ao DESENHO do mascote (não ao palco): contorno de silhueta; null nos outros modos. */
    public static Effect effectFor(Mode mode, double size) {
        if (mode != Mode.HALO) {
            return null;
        }
        DropShadow rim = new DropShadow(BlurType.GAUSSIAN, Color.color(HALO_COLOR.getRed(), HALO_COLOR.getGreen(), HALO_COLOR.getBlue(), 0.42), Math.max(1.6, size * 0.022), 0.62, 0, 0);
        return rim;
    }
}
