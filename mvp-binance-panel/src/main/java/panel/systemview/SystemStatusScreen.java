package panel.systemview;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxStatusChip;
import panel.design.StatusState;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * System Status V2: o detalhe do que o dock resume. Cada componente mostra estado, razão, idade do dado e, quando existe uma
 * nova tentativa REAL (backend, nó BYX), o botão Retry. A recuperação é representada, nunca simulada. Atualizar o status não
 * navega, não repete a entrada da página e atualiza as linhas no lugar (nada é recriado); escondida, o timer para.
 */
public final class SystemStatusScreen implements View {
    private final MotionService motion;
    private final Clock clock;
    private final Supplier<SystemStatusModel.Inputs> inputs;
    private final RecoveryTracker tracker;
    private final Consumer<String> retry;
    private final ScrollPane scroll;
    private final VBox rows = new VBox(0);
    private final Label updated = Fx.label("", "byx-desk-t3");
    private final List<Row> list = new ArrayList<>();
    private final Label operational = Fx.label("0", "byx-big");
    private final Label attention = Fx.label("0", "byx-big");
    private final Label unknown = Fx.label("0", "byx-big");
    private final Label checked = Fx.label("—", "byx-big");
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> render()));
    private int rebuilds;

    private final class Row extends HBox {
        final String id;
        final ByxStatusChip chip;
        final Label reason = Fx.label("", "byx-desk-secondary");
        final Label age = Fx.label("", "byx-desk-t3");
        final Label recovery = ByxBadge.of("", ByxBadge.Tone.WARNING);
        final ByxButton retryButton;

        Row(String id, String name) {
            super(14);
            this.id = id;
            chip = new ByxStatusChip(StatusState.UNKNOWN, motion);
            chip.setMinWidth(150);
            Label title = Fx.label(name, "byx-section-title-sm");
            title.setMinWidth(140);
            VBox copy = new VBox(2, title, reason);
            reason.setWrapText(true);
            HBox.setHgrow(copy, Priority.ALWAYS);
            retryButton = new ByxButton("Retry", ByxButton.Variant.SECONDARY, motion);
            retryButton.small();
            retryButton.setOnAction(e -> retry.accept(id));
            setAlignment(Pos.CENTER_LEFT);
            getStyleClass().add("byx-desk-row");
            setId("status-" + id);
            getChildren().addAll(copy, recovery, age, chip, retryButton);
        }
    }

    public SystemStatusScreen(MotionService motion, Clock clock, Supplier<SystemStatusModel.Inputs> inputs, RecoveryTracker tracker,
            Consumer<String> retry) {
        this.motion = motion;
        this.clock = clock;
        this.inputs = inputs;
        this.tracker = tracker;
        this.retry = retry;
        timer.setCycleCount(Animation.INDEFINITE);
        VBox panel = Kit.panel(null, Kit.titled("Components", updated), rows);
        panel.setId("status-components");
        VBox page = Kit.page(14);
        HBox tiles = new HBox(14, tile("Operational", operational), tile("Needs attention", attention), tile("Unknown", unknown), tile("Checked", checked));
        tiles.setId("status-summary");
        page.getChildren().addAll(Kit.header("System Status", "The detail behind the status dock. A component that can not be read is UNKNOWN, never OPERATIONAL."), tiles, panel,
                Kit.dim("Retry appears only where a real new attempt exists. Recovery states mirror what each service reports; no attempts are simulated."));
        scroll = Kit.scroll(page);
        render();
    }

    private static VBox tile(String title, Label value) {
        VBox v = new VBox(4, Kit.label(title), value);
        v.getStyleClass().add("byx-panel");
        HBox.setHgrow(v, Priority.ALWAYS);
        v.setMaxWidth(Double.MAX_VALUE);
        v.setPrefWidth(1);
        return v;
    }

    boolean timerRunning() {
        return timer.getStatus() == Animation.Status.RUNNING;
    }

    int rebuilds() {
        return rebuilds;
    }

    List<Row> rowsList() {
        return list;
    }

    private void render() {
        List<SystemStatusModel.Component> comps = SystemStatusModel.components(inputs.get());
        if (list.size() != comps.size()) {
            rows.getChildren().clear();
            list.clear();
            rebuilds++;
            for (SystemStatusModel.Component c : comps) {
                Row r = new Row(c.id(), c.name());
                list.add(r);
                rows.getChildren().add(r);
            }
        }
        for (int i = 0; i < comps.size(); i++) {
            SystemStatusModel.Component c = comps.get(i);
            Row r = list.get(i);
            if (r.chip.state() != c.state() || r.chip.dot().expected() != c.expected()) {
                r.chip.setState(c.state(), c.expected());
            }
            Fx.text(r.reason, c.reason());
            Fx.text(r.age, "Updated " + SystemStatusModel.age(c.lastUpdate(), clock.instant()));
            RecoveryTracker.State rs = tracker.state(c.id());
            boolean showRecovery = rs != RecoveryTracker.State.CONNECTED;
            Fx.shown(r.recovery, showRecovery);
            if (showRecovery) {
                Fx.text(r.recovery, rs.name().replace('_', ' '));
            }
            Fx.shown(r.retryButton, c.retry() && c.state() != StatusState.OPERATIONAL);
            r.setAccessibleText(c.name() + ": " + c.state() + ". " + c.reason());
        }
        long ok = comps.stream().filter(c -> c.state() == StatusState.OPERATIONAL).count();
        long unk = comps.stream().filter(c -> c.state() == StatusState.UNKNOWN).count();
        long att = comps.stream().filter(c -> c.state() != StatusState.OPERATIONAL && c.state() != StatusState.UNKNOWN && !c.expected()).count();
        Fx.text(operational, Long.toString(ok));
        Fx.text(attention, Long.toString(att));
        Fx.text(unknown, Long.toString(unk));
        Fx.text(checked, java.time.LocalTime.ofInstant(clock.instant(), java.time.ZoneId.systemDefault()).withNano(0).toString());
        Fx.text(updated, "Checked " + java.time.LocalTime.ofInstant(clock.instant(), java.time.ZoneId.systemDefault()).withNano(0));
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    @Override
    public void onShow() {
        render();
        timer.play();
    }

    @Override
    public void onHide() {
        timer.stop();
    }

    public void dispose() {
        onHide();
    }
}
