package panel.helpview;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/** Destaque do termo buscado (referência V2 do FAQ): trechos que casam, sem caixa, ganham a classe {@code byx-hit}; o resto é texto normal. */
public final class Highlight {
    private Highlight() {
    }

    /** Divide em segmentos {texto, casa?}; query vazia ou sem ocorrência devolve um único segmento sem destaque. */
    public static List<Object[]> split(String text, String query) {
        List<Object[]> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            out.add(new Object[] {text, false});
            return out;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String needle = q.toLowerCase(Locale.ROOT);
        int from = 0;
        int at;
        while ((at = lower.indexOf(needle, from)) >= 0) {
            if (at > from) {
                out.add(new Object[] {text.substring(from, at), false});
            }
            out.add(new Object[] {text.substring(at, at + needle.length()), true});
            from = at + needle.length();
        }
        if (from < text.length()) {
            out.add(new Object[] {text.substring(from), false});
        }
        if (out.isEmpty()) {
            out.add(new Object[] {text, false});
        }
        return out;
    }

    public static TextFlow flow(String text, String query, String... baseClasses) {
        TextFlow flow = new TextFlow();
        Runnable render = () -> {
        flow.getChildren().clear();
        String shown = panel.i18n.Presentation.text(text);
        for (Object[] seg : split(shown, query)) {
            Text t = new Text((String) seg[0]);
            t.getStyleClass().addAll(baseClasses);
            if ((boolean) seg[1]) {
                t.getStyleClass().add("byx-hit");
            }
            flow.getChildren().add(t);
        }
        flow.setAccessibleText(shown);
        };
        render.run();
        javafx.beans.value.ChangeListener<panel.i18n.Strings.Lang> listener = (o,a,b) -> render.run();
        flow.getProperties().put("byx.i18n.localeDelegate", listener);
        javafx.beans.value.WeakChangeListener<panel.i18n.Strings.Lang> weakLocale = new javafx.beans.value.WeakChangeListener<>(listener);
        flow.sceneProperty().addListener((o,a,b) -> {
            if (a != null) panel.i18n.Strings.languageProperty().removeListener(weakLocale);
            if (b != null) { render.run(); panel.i18n.Strings.languageProperty().addListener(weakLocale); }
        });
        return flow;
    }
}
