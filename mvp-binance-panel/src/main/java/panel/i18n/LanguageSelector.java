package panel.i18n;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

/** Locale choice is independent of backend preference authorization; no preference write is made. */
public final class LanguageSelector extends ComboBox<Strings.Lang> {
    private final ChangeListener<Strings.Lang> locale = (o,a,b) -> setValue(b);
    private final javafx.beans.value.WeakChangeListener<Strings.Lang> weakLocale = new javafx.beans.value.WeakChangeListener<>(locale);
    public LanguageSelector() {
        getStyleClass().add("byx-language");
        getItems().setAll(Strings.Lang.values());
        setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            { getStyleClass().add("byx-language-cell"); }
            @Override protected void updateItem(Strings.Lang item, boolean empty) { super.updateItem(item,empty); setText(empty || item == null ? null : getConverter().toString(item)); }
        });
        setConverter(new StringConverter<>() {
            @Override public String toString(Strings.Lang language) { return language == null ? "" : switch (language) { case EN -> "English"; case PT_BR -> "Português (Brasil)"; }; }
            @Override public Strings.Lang fromString(String value) { throw new UnsupportedOperationException("non-editable language selector"); }
        });
        setValue(Strings.language());
        accessibleTextProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(() -> Presentation.text("Interface language"), Strings.languageProperty()));
        setMinWidth(190); setPrefWidth(210);
        valueProperty().addListener((o,a,b) -> { if (b != null && b != Strings.language()) Strings.use(b); });
        sceneProperty().addListener((o,a,b) -> { if (a != null) Strings.languageProperty().removeListener(weakLocale); if (b != null) { setValue(Strings.language()); Strings.languageProperty().addListener(weakLocale); } });
        LocaleView.literal(this); // Autonyms and enum values must remain exact in either language.
    }
}
