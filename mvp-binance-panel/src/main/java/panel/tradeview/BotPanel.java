package panel.tradeview;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import panel.design.ByxBadge;
import panel.design.ByxFonts;
import panel.model.TraderSnapshot;
import panel.motion.MotionService;
import panel.motion.MotionTokens;
import panel.util.Fmt;

/**
 * Adaptive Trader no Desk. STRIP (COMPACT): faixa com "Research · Live OFF". ROWS (STANDARD, flex no fim da coluna, e
 * aba Bot do COMPACT) e FULL (EXPANDED, painel próprio no topo da coluna de contexto): as quatro linhas da referência
 * (Mode, Live trading, Strategy, Signal) com o selo de estado. Só mostra os estados que o app declara (MONITORING,
 * IDLE, UNAVAILABLE, ERROR) e o live trading aparece sempre como texto, nunca como controle.
 */
final class BotPanel extends VBox {
    enum Variant { STRIP, ROWS, FULL }

    private final MotionService motion;
    private final Variant variant;
    private final Label state = ByxBadge.of("MONITORING", ByxBadge.Tone.WARNING);
    private final Circle dot = new Circle(4);
    private final Label stripText = Fx.label("Research · Live OFF", "byx-desk-secondary");
    private final KvRow mode = new KvRow("Mode");
    private final KvRow live = new KvRow("Live trading");
    private final KvRow strategy = new KvRow("Strategy");
    private final KvRow signal = new KvRow("Signal");

    BotPanel(MotionService motion, Variant variant, boolean inline) {
        this.motion = motion;
        this.variant = variant;
        getStyleClass().add("byx-desk-bot");
        setId(variant == Variant.STRIP ? "desk-bot-strip" : "desk-bot");
        Label k = ByxFonts.upper(Fx.label("Bot", "byx-label"));
        Label name = Fx.label("Adaptive Trader", variant == Variant.STRIP ? "byx-desk-bot-name" : "byx-section-title-sm");
        VBox id = new VBox(k, name);
        dot.getStyleClass().add("byx-desk-bot-dot");
        state.setGraphic(dot);
        state.setGraphicTextGap(6);
        if (variant == Variant.STRIP) {
            getStyleClass().add("strip");
            HBox row = new HBox(id, Fx.spacer(), stripText);
            row.setAlignment(Pos.CENTER_LEFT);
            getChildren().add(row);
            return;
        }
        HBox head = new HBox(id, Fx.spacer(), state);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("byx-desk-bot-head");
        getChildren().addAll(head, mode, live, strategy, signal);
        setInline(inline);
    }

    void setInline(boolean inline) {
        if (variant != Variant.STRIP) {
            Fx.cls(this, "byx-panel", !inline);
            Fx.cls(this, "byx-desk-inline", inline);
        }
    }

    Variant variant() {
        return variant;
    }

    Label stateBadge() {
        return state;
    }

    Label stripText() {
        return stripText;
    }

    Circle dot() {
        return dot;
    }

    int rowCount() {
        return (int) getChildren().stream().filter(n -> n instanceof KvRow).count();
    }

    KvRow modeRow() {
        return mode;
    }

    KvRow liveRow() {
        return live;
    }

    void apply(TraderSnapshot t) {
        DeskModel.Bot bot = DeskModel.bot(t);
        if (variant == Variant.STRIP) {
            Fx.text(stripText, DeskModel.botStrip(t));
            return;
        }
        Fx.text(state, bot.label);
        Fx.tone(state, switch (bot) {
            case MONITORING -> "tone-wrn";
            case IDLE -> "tone-inf";
            case ERROR -> "tone-neg";
            case UNAVAILABLE -> null;
        }, "tone-wrn", "tone-inf", "tone-neg");
        Fx.cls(dot, "on", bot == DeskModel.Bot.MONITORING);
        // a marca de vida respira só em FULL e só enquanto o bot monitora; o texto do estado sempre acompanha
        motion.reference.setBreathing(dot, bot == DeskModel.Bot.MONITORING, MotionTokens.LIVE, MotionTokens.CSS_EASE_IN_OUT);
        Fx.visible(dot, bot == DeskModel.Bot.MONITORING);
        mode.set(DeskModel.botMode(t), false, null);
        live.set(DeskModel.liveTrading(t), false, null);
        strategy.set(Fmt.text(t.strategy), false, null);
        boolean noSignal = t.signal == null || t.signal.isBlank();
        signal.set(Fmt.text(t.signal), noSignal, null);
    }
}
