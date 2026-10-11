package panel.i18n;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.Tooltip;

/** For Tooltip.install on a non-Control owner, outside the Scene's Control.tooltipProperty tree. */
public final class LocaleTooltip extends Tooltip {
    private final String source;
    private final ChangeListener<Strings.Lang> locale = (o,a,b) -> refresh();
    private final WeakChangeListener<Strings.Lang> weakLocale = new WeakChangeListener<>(locale);
    public LocaleTooltip(String source) {
        this.source = source;
        Strings.languageProperty().addListener(weakLocale);
        refresh();
    }
    private void refresh() { setText(Presentation.text(source)); }
}
