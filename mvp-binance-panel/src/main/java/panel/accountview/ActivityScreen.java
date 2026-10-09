package panel.accountview;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.model.Snapshot;
import panel.security.SecurityAuditService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * Activity V2. A única fonte real é a auditoria de segurança local da conta; categorias sem fonte (Account, Research, BYX)
 * não existem. Sem registros: EMPTY real; leitura que falha: UNAVAILABLE. Nada de histórico fictício nem guardado na UI.
 */
public final class ActivityScreen implements View {
    private final AccountRead read = new AccountRead();
    private final AccountData data;
    private final Clock clock;
    private final ScrollPane scroll;
    private final VBox body = new VBox(0);
    private final Label state = ByxBadge.of("", ByxBadge.Tone.NEUTRAL);

    public ActivityScreen(Clock clock, AccountData data) {
        this.clock = clock;
        this.data = data;
        VBox panel = Kit.panel(null, Kit.titled("Security events", state), body);
        panel.setId("activity-list");
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Activity", "Your account and security history. Only security events are recorded in this build."), panel);
        scroll = Kit.scroll(page);
        state.setText("NOT LOADED");
    }

    String stateText() {
        return state.getText();
    }

    private void render() {
        body.getChildren().setAll(Kit.muted("Loading security activity…"));
        state.setText("LOADING");
        boolean signedIn = data.user().isPresent();
        read.load(() -> signedIn ? data.activity(50) : List.<SecurityAuditService.Entry>of(), (entries, error) -> {
            body.getChildren().clear();
            if (error != null) {
                state.setText("UNAVAILABLE");
                body.getChildren().add(Kit.muted("The local audit log could not be read."));
                return;
            }
            display(entries);
        });
    }

    private void display(List<SecurityAuditService.Entry> entries) {
        state.setText(entries.isEmpty() ? "EMPTY" : "SECURITY · " + entries.size());
        if (entries.isEmpty()) {
            body.getChildren().add(Kit.muted("No activity recorded yet."));
            return;
        }
        for (AccountModel.Group g : AccountModel.group(entries, clock, ZoneId.systemDefault())) {
            Label day = Fx.label(g.day(), "byx-label");
            day.setPadding(new javafx.geometry.Insets(12, 0, 4, 0));
            body.getChildren().add(day);
            for (SecurityAuditService.Entry e : g.entries()) {
                Label t = Fx.label(Fmt.time(Instant.parse(e.ts())), "byx-table-cell", "mono");
                t.setMinWidth(90);
                Label ev = Fx.label(AccountModel.eventLabel(e.event()), "byx-table-cell");
                ev.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(ev, Priority.ALWAYS);
                String result = AccountModel.result(e.event());
                HBox r = new HBox(12, t, ev, ByxBadge.of("SECURITY", ByxBadge.Tone.NEUTRAL),
                        ByxBadge.of(result.toUpperCase(), SecurityScreen.tone(result)));
                r.setAlignment(Pos.CENTER_LEFT);
                r.getStyleClass().add("byx-table-row");
                body.getChildren().add(r);
            }
        }
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    @Override public void onShow() { render(); }
    @Override public void onHide() { read.cancel(); }
    @Override public void dispose() { read.close(); }
}
