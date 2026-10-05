package panel.helpview;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.Label;
import panel.model.Snapshot;
import panel.shell.ShortcutRegistry;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/** Keyboard shortcuts V2 (página e conteúdo do diálogo "?"): gerado do {@link ShortcutRegistry}; só atalhos que existem. */
public final class ShortcutsScreen implements View {
    private final ScrollPane scroll;

    public ShortcutsScreen(String modifier) {
        VBox page = Kit.reading(14, 1240);
        page.getChildren().addAll(Kit.header("Keyboard shortcuts", "Every shortcut that exists in the app."), content(modifier));
        scroll = Kit.scroll(page);
    }

    /** Corpo reutilizado pelo diálogo. */
    public static VBox content(String modifier) {
        VBox out = new VBox(14);
        for (ShortcutRegistry.Group g : ShortcutRegistry.GROUPS) {
            VBox panel = Kit.panel(g.title());
            for (ShortcutRegistry.Entry e : g.items()) {
                HBox keys = new HBox(4);
                for (String k : e.keys()) {
                    Label key = Fx.label(k.replace("⌘/Ctrl", modifier), "byx-key");
                    keys.getChildren().add(key);
                }
                HBox row = new HBox(12, Fx.label(e.action(), "byx-table-cell"), Fx.spacer(), keys);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                row.getStyleClass().add("byx-desk-row");
                row.setAccessibleText(e.action() + ": " + ShortcutRegistry.keysText(e, modifier));
                panel.getChildren().add(row);
            }
            out.getChildren().add(panel);
        }
        return out;
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
