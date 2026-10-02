package panel.ui;

import javafx.scene.control.Button;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.control.Tooltip;
import panel.app.AppContext;
import panel.model.CaptureInfo;
import panel.model.SessionInfo;
import panel.model.Snapshot;
import panel.util.Fmt;

public class CaptureView extends PageView {
    public CaptureView(AppContext ctx) {
        super(ctx);
    }

    @Override
    protected void build(Snapshot s, VBox page) {
        CaptureInfo c = s.capture;
        Button start = Ui.button("START CAPTURE", "primary");
        Button stop = Ui.button("STOP CAPTURE", "danger");
        for (Button b : new Button[] {start, stop}) {
            b.setDisable(true);
            Tooltip.install(b, new Tooltip("Not available: the backend does not expose recorder control yet."));
        }
        page.getChildren().add(Ui.pageHeader("Capture", "Continuous Binance market data recorder", start, stop));

        FlowPane flow = new FlowPane(14, 14);
        flow.getChildren().addAll(
                size(Ui.card("Recorder",
                        Ui.kvNode("Recorder", Ui.badge(Fmt.text(c.recorder()), c.recorder() == null ? "muted" : "ok")),
                        Ui.kv("Campaign", s.campaignId), Ui.kv("Streams", s.captureStreams),
                        Ui.kv("Started at", Fmt.dateTime(s.captureStartedAt)),
                        Ui.kv("Status timestamp", Fmt.dateTime(s.captureStatusAt)),
                        Ui.kv("Health", s.recorderHealth), Ui.kv("Connection", c.connection()), Ui.kv("Exchange", c.exchange()), Ui.kv("Market", c.market()),
                        Ui.kv("Symbol", c.symbol()), Ui.kv("Current session", c.currentSession()), Ui.kv("Session duration", c.sessionDuration()))),
                size(Ui.card("Throughput",
                        Ui.kv("Events received", Fmt.num(c.events())), Ui.kv("Events / sec", c.eventsPerSec() == null ? null : String.valueOf(c.eventsPerSec())),
                        Ui.kv("Order book updates", Fmt.num(c.bookUpdates())), Ui.kv("Trades", Fmt.num(c.trades())),
                        Ui.kv("Reconnects", Fmt.num(c.reconnects())), Ui.kv("Gap warnings", c.gapWarnings()))),
                size(Ui.card("Storage",
                        Ui.kv("Last event", Fmt.dateTime(c.lastEvent())), Ui.kv("Disk written", c.diskWritten()), Ui.kv("Storage path", c.storagePath()))));
        page.getChildren().add(flow);
        if (c.recorder() == null) {
            page.getChildren().add(Ui.label("Live recorder status is unavailable. See technical warnings.", "muted"));
        }

        VBox recent = Ui.card("Latest captured sessions");
        int from = Math.max(0, s.sessions.size() - 8);
        for (int i = s.sessions.size() - 1; i >= from; i--) {
            SessionInfo x = s.sessions.get(i);
            HBox row = new HBox(14, Ui.label(x.id(), "mono"), Ui.spacer(), Ui.label(Fmt.dateTime(x.start()), "muted"), Ui.label(Fmt.duration(x.duration()), "muted"), Ui.label(Fmt.num(x.events()) + " events", "muted"));
            recent.getChildren().add(row);
        }
        if (s.sessions.isEmpty()) {
            recent.getChildren().add(Ui.emptyState("No sessions"));
        }
        page.getChildren().add(recent);
    }

    private static VBox size(VBox c) {
        c.setPrefWidth(380);
        return c;
    }
}
