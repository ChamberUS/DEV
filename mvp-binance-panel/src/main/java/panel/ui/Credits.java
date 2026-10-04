package panel.ui;

import java.util.List;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.VBox;

/** About / Credits. Mantido em sincronia com THIRD_PARTY_ASSETS.md. */
public final class Credits {
    public record Entry(String name, String license, String use) {
    }

    public static final List<Entry> ENTRIES = List.of(
            new Entry("Schibsted Grotesk Project Authors", "SIL OFL 1.1", "Reference UI typeface (static 400/600 instances)"),
            new Entry("Inter (Rasmus Andersson)", "SIL OFL 1.1", "Legacy UI typeface (bundled)"),
            new Entry("JetBrains Mono", "SIL OFL 1.1", "Numbers and code typeface (bundled)"),
            new Entry("Lottie4J", "Apache-2.0", "Native Lottie renderer"),
            new Entry("OpenJFX", "GPL v2 + Classpath Exception", "UI toolkit"),
            new Entry("Bouncy Castle", "MIT", "Argon2id password hashing"),
            new Entry("SQLite JDBC (xerial)", "Apache-2.0", "Local storage"),
            new Entry("Jackson", "Apache-2.0", "JSON"),
            new Entry("Application icons and animations", "Original work of this project", "Native vector icons and two Lottie files"));

    private Credits() {
    }

    public static void show() {
        VBox box = new VBox(8);
        for (Entry e : ENTRIES) {
            box.getChildren().add(Ui.kv(e.name(), e.license() + " — " + e.use()));
        }
        box.getChildren().add(Ui.label("No Lordicon or Lottieflow assets are bundled, so no third-party attribution is currently required for icons.", "muted"));
        Alert a = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.CLOSE);
        a.setTitle("About / Credits");
        a.setHeaderText(panel.app.AppBranding.title("About / Credits"));
        a.getDialogPane().setContent(box);
        a.getDialogPane().getStylesheets().add(Credits.class.getResource("/panel/panel.css").toExternalForm());
        a.getDialogPane().getStylesheets().add(Credits.class.getResource("/panel/byx.css").toExternalForm());
        a.getDialogPane().getStyleClass().add("dialog");
        a.showAndWait();
    }
}
