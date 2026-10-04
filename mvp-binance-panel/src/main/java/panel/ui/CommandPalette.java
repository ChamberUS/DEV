package panel.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public final class CommandPalette {
    public record Command(String target, String title, String state) { }
    private final Consumer<String> navigate;
    private StackPane host;
    private StackPane overlay;

    public CommandPalette(Consumer<String> navigate) { this.navigate = navigate; }

    public static List<Command> commands(boolean admin, boolean verified) {
        List<Command> result = new ArrayList<>();
        result.add(new Command("t-desk", "Go to Trading Desk", ""));
        result.add(new Command("t-markets", "Open Markets", ""));
        result.add(new Command("t-portfolio", "Open Portfolio", ""));
        result.add(new Command("t-byx", "Open BYX Network", ""));
        result.add(new Command("t-wallet", "Open Wallet", ""));
        result.add(new Command("t-benefits", "Open Benefits", ""));
        result.add(new Command("t-treasury", "Open Treasury", ""));
        if (admin) {
            String state = verified ? "" : "Admin verification required";
            result.add(new Command("overview", "Open Research", state));
            result.add(new Command("capture", "Open Capture · read-only", state));
            result.add(new Command("validation", "Open Validation", verified ? "Locked · ResearchGuard" : state));
            result.add(new Command(null, "Final holdout", "Sealed"));
        }
        result.add(new Command(null, "Switch to MAINNET", "Environment unavailable"));
        result.add(new Command("t-settings", "Open Settings", ""));
        result.add(new Command("t-profile", "Open Profile / Security", ""));
        for (String id : List.of("bot", "strategies", "signals", "positions", "orders", "performance", "activity"))
            result.add(new Command("t-" + id, "Open " + id, ""));
        if (admin) {
            String state = verified ? "" : "Admin verification required";
            for (String id : List.of("sessions", "dataset", "labels", "features", "hypotheses", "execution", "paper", "live", "jobs", "logs", "users", "settings"))
                result.add(new Command(id, "Research / " + id, verified && List.of("paper", "live").contains(id) ? "Locked · ResearchGuard" : state));
        }
        return List.copyOf(result);
    }

    public void open(StackPane root, boolean admin, boolean verified) {
        close(); host = root;
        TextField search = new TextField();
        search.setPromptText("Type a command…");
        search.setAccessibleText("Search commands");
        VBox rows = new VBox(4);
        var results = Ui.scroll(rows); results.setPrefHeight(400);
        VBox panel = new VBox(14, search, results, Ui.label("↑↓ navigate   ↵ open   esc close", "muted"));
        panel.getStyleClass().add("command-panel");
        panel.setMaxSize(560, javafx.scene.layout.Region.USE_PREF_SIZE);
        List<Command> available = commands(admin, verified);
        Runnable filter = () -> {
            rows.getChildren().clear();
            available.stream().filter(c -> c.title().toLowerCase(java.util.Locale.ROOT)
                    .contains(search.getText().toLowerCase(java.util.Locale.ROOT))).forEach(c -> {
                Button button = Ui.button(c.title() + (c.state().isEmpty() ? "" : "  ·  " + c.state()), "ghost");
                button.setMaxWidth(Double.MAX_VALUE);
                button.getStyleClass().add("command-row");
                button.setDisable(c.target() == null);
                button.setOnAction(e -> { close(); navigate.accept(c.target()); });
                rows.getChildren().add(button);
            });
        };
        search.textProperty().addListener((o, a, b) -> filter.run());
        filter.run();
        overlay = new StackPane(panel);
        StackPane.setAlignment(panel, javafx.geometry.Pos.TOP_CENTER);
        StackPane.setMargin(panel, new javafx.geometry.Insets(110, 0, 0, 0));
        overlay.getStyleClass().add("command-overlay");
        overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) close(); });
        overlay.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) { close(); e.consume(); }
            else if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                var buttons = rows.getChildren().stream().map(n -> (Button) n).filter(b -> !b.isDisabled()).toList();
                if (!buttons.isEmpty()) {
                    int index = buttons.indexOf(host.getScene().getFocusOwner());
                    buttons.get(Math.floorMod(index + (e.getCode() == KeyCode.DOWN ? 1 : -1), buttons.size())).requestFocus();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && search.isFocused()) {
                rows.getChildren().stream().map(n -> (Button) n).filter(b -> !b.isDisabled()).findFirst().ifPresent(Button::fire);
                e.consume();
            }
        });
        root.getChildren().add(overlay);
        Platform.runLater(search::requestFocus);
    }

    public void close() {
        if (host != null && overlay != null) host.getChildren().remove(overlay);
        overlay = null;
    }
}
