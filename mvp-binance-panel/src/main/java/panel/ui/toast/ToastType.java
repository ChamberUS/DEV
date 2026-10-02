package panel.ui.toast;

import javafx.util.Duration;

public enum ToastType {
    INFO("info", "info", Duration.seconds(4)), SUCCESS("check", "ok", Duration.seconds(3.5)),
    WARNING("warning", "warn", Duration.seconds(6)), ERROR("error", "bad", Duration.seconds(9));

    public final String icon;
    public final String tone;
    public final Duration stay;

    ToastType(String icon, String tone, Duration stay) {
        this.icon = icon;
        this.tone = tone;
        this.stay = stay;
    }
}
