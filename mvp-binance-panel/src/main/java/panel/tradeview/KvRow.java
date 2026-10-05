package panel.tradeview;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/** Linha rótulo/valor (referência .r): borda superior, rótulo secundário à esquerda, valor à direita. */
final class KvRow extends HBox {
    private final Label key;
    private final Label value = Fx.label("N/A", "byx-desk-row-value");

    KvRow(String key) {
        super(12);
        this.key = Fx.label(key, "byx-desk-row-key");
        getStyleClass().add("byx-desk-row");
        setAlignment(Pos.CENTER_LEFT);
        this.key.setMinWidth(USE_PREF_SIZE);
        value.setMinWidth(0);
        value.setAlignment(Pos.CENTER_RIGHT);
        getChildren().addAll(this.key, Fx.spacer(), value);
    }

    Label valueLabel() {
        return value;
    }

    String key() {
        return key.getText();
    }

    /** mono = valor numérico/ID (JetBrains Mono); tone: "neg", "pos", "warn", "muted" ou null. */
    void set(String text, boolean mono, String tone) {
        Fx.text(value, text);
        Fx.cls(value, "mono", mono);
        Fx.tone(value, tone, "neg", "pos", "warn", "muted");
        setAccessibleText(key.getText() + ": " + text);
    }
}
