package panel.design;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.i18n.Strings;
import panel.i18n.LocaleView;

/** Native appearance choice. Session display state never participates in account preference writes. */
public final class ThemeSelector extends VBox {
    public ThemeSelector() {
        super(8);
        getStyleClass().add("byx-theme-selector");
        setMinWidth(0);
        ToggleGroup choices = new ToggleGroup();
        HBox cards = new HBox(8);
        for (ThemeMode mode : ThemeMode.values()) {
            RadioButton choice = new RadioButton();
            String key = switch (mode) { case DARK -> "ui.dark.60acc53f"; case LIGHT -> "ui.light.dbcd5e7b"; case SYSTEM -> "ui.system.6725e7bb"; };
            choice.textProperty().bind(Bindings.createStringBinding(() -> Strings.get(key), Strings.languageProperty()));
            choice.setAccessibleRole(javafx.scene.AccessibleRole.RADIO_BUTTON);
            choice.setId("appearance-" + mode.name().toLowerCase(java.util.Locale.ROOT));
            choice.setUserData(mode);
            choice.setToggleGroup(choices);
            choice.setSelected(ByxTheme.mode() == mode);
            choice.setDisable(mode == ThemeMode.SYSTEM && !ByxTheme.systemSupported());
            choice.getStyleClass().addAll("byx-body", "byx-theme-choice");
            if (mode != ThemeMode.SYSTEM) {
                javafx.scene.layout.Pane preview = new javafx.scene.layout.Pane();
                preview.setPrefSize(72, 36); preview.setMinSize(72, 36); preview.setMaxSize(72, 36);
                var palette = ThemePalette.of(mode);
                preview.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(palette.color(ThemeToken.SURFACE_PRIMARY), new javafx.scene.layout.CornerRadii(4), javafx.geometry.Insets.EMPTY)));
                var rail = new javafx.scene.shape.Rectangle(3, 3, 10, 30); rail.setFill(palette.color(ThemeToken.SURFACE_RAIL));
                var card = new javafx.scene.shape.Rectangle(17, 9, 50, 22); card.setFill(palette.color(ThemeToken.SURFACE_CARD));
                var ink = new javafx.scene.shape.Rectangle(21, 13, 24, 2); ink.setFill(palette.color(ThemeToken.TEXT_PRIMARY));
                var accent = new javafx.scene.shape.Rectangle(21, 21, 34, 4); accent.setFill(palette.color(ThemeToken.ACCENT_TRADING));
                preview.getChildren().addAll(rail, card, ink, accent);
                preview.setMouseTransparent(true);
                choice.setGraphic(preview); choice.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
            }
            choice.setOnAction(e -> { if (choice.isSelected()) ByxTheme.select(mode); });
            LocaleView.literal(choice);
            cards.getChildren().add(choice);
        }
        ByxTheme.observe(this, () -> choices.getToggles().stream().filter(t -> t.getUserData() == ByxTheme.mode()).findFirst().ifPresent(t -> t.setSelected(true)));
        getChildren().add(cards);
        for (String key : new String[]{"appearance.systemUnavailable", "appearance.sessionTitle", "appearance.sessionBody", "appearance.themeHelp"}) {
            Label note = new Label();
            note.setWrapText(true);
            note.setMinWidth(0);
            note.textProperty().bind(Bindings.createStringBinding(() -> Strings.get(key), Strings.languageProperty()));
            note.getStyleClass().addAll("byx-body", "byx-secondary");
            LocaleView.literal(note);
            getChildren().add(note);
        }
    }
}
