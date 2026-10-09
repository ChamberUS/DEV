package panel.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.VBox;
import panel.adapter.CommandSpec;
import panel.app.AppContext;
import panel.model.Snapshot;
import panel.util.Fmt;

public final class Dialogs {
    private Dialogs() {
    }

    public static void info(String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        style(a.getDialogPane());
        localize(a);
        a.showAndWait();
    }

    /** Executa um comando permitido; comandos pesados pedem confirmação TRAIN explícita. */
    public static void run(AppContext ctx, CommandSpec spec, String sessionId) {
        Snapshot s = ctx.research.snapshot.get();
        if (s.source == panel.model.DataSource.MOCK) {
            info("MOCK mode", "Commands are disabled while DATA SOURCE is MOCK.");
            return;
        }
        if (spec.heavy && !confirm(ctx, spec, sessionId, s)) {
            return;
        }
        try {
            ctx.jobs.submit(spec, sessionId);
            ctx.navigate.accept("jobs");
        } catch (IllegalArgumentException | IllegalStateException | panel.security.AccessDeniedException e) {
            info("Command refused", e.getMessage());
        }
    }

    private static boolean confirm(AppContext ctx, CommandSpec spec, String sessionId, Snapshot s) {
        String what = switch (spec) {
            case LABEL_RUN -> "Generate labels for all TRAIN sessions?";
            case LABEL_RUN_SESSION -> "Generate labels for one TRAIN session?";
            default -> spec.title + " (TRAIN)?";
        };
        ButtonType run = new ButtonType("Run TRAIN", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.CANCEL, run);
        a.setTitle("Confirm");
        a.setHeaderText(what);
        VBox box = new VBox(6,
                Ui.kv("Dataset", s.datasetId),
                Ui.kv("Sessions", sessionId != null ? sessionId : Fmt.num(s.sessionCount)),
                Ui.label("Existing valid sessions will be reused.", "muted"),
                Ui.kvNode("VALIDATION", Ui.badge("LOCKED", "bad")),
                Ui.kvNode("FINAL_HOLDOUT", Ui.badge("LOCKED", "bad")));
        a.getDialogPane().setContent(box);
        style(a.getDialogPane());
        localize(a);
        return a.showAndWait().filter(b -> b == run).isPresent();
    }

    private static void localize(Alert alert) {
        var pane = alert.getDialogPane();
        Runnable attach = () -> {
            if (pane.getScene() == null || pane.getProperties().containsKey("byx.localeView")) return;
            var view = new panel.i18n.LocaleView(pane.getScene());
            view.bind(alert.titleProperty()); view.bind(alert.headerTextProperty()); view.bind(alert.contentTextProperty());
            pane.getProperties().put("byx.localeView", view);
        };
        javafx.beans.value.ChangeListener<javafx.scene.Scene> listener = (o,a,b) -> attach.run();
        pane.sceneProperty().addListener(listener); attach.run();
        alert.setOnHidden(e -> {
            pane.sceneProperty().removeListener(listener);
            Object view = pane.getProperties().remove("byx.localeView");
            if (view instanceof panel.i18n.LocaleView localeView) localeView.close();
        });
    }

    private static panel.motion.MotionService motion;

    public static void init(panel.motion.MotionService m) {
        motion = m;
    }

    private static void style(DialogPane p) {
        p.sceneProperty().addListener((o, a, s) -> {
            if (s != null && motion != null) {
                motion.reset(p);
            }
        });
        p.getStylesheets().add(Dialogs.class.getResource("/panel/panel.css").toExternalForm());
        p.getStylesheets().add(Dialogs.class.getResource("/panel/byx.css").toExternalForm());
        p.getStyleClass().add("dialog");
        panel.design.ByxTheme.apply(p);
    }
}
