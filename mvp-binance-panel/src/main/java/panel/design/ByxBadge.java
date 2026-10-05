package panel.design;

import javafx.scene.control.Label;
import javafx.scene.shape.Circle;

/**
 * Badges V2 (tint 12%). Disponibilidade e dados sempre levam ícone + texto (components.availabilityBadges,
 * components.dataBadges); a cor nunca é o único sinal.
 */
public final class ByxBadge {
    public enum Tone { NEUTRAL, POSITIVE, WARNING, NEGATIVE, INFO, ACCENT }

    /** components.availabilityBadges */
    public enum Availability {
        LOCKED("Locked", Tone.NEGATIVE, "lock"),
        NOT_CONFIGURED("Not configured", Tone.NEUTRAL, null),
        UNAVAILABLE("Unavailable", Tone.WARNING, "warning"),
        PERMISSION_REQUIRED("Permission required", Tone.INFO, "shield"),
        COMING_SOON("Coming soon", Tone.INFO, "clock");

        public final String text;
        final Tone tone;
        final String icon;

        Availability(String text, Tone tone, String icon) {
            this.text = text;
            this.tone = tone;
            this.icon = icon;
        }
    }

    /** components.dataBadges: todos em tom de aviso. */
    public enum Data {
        DEMO_DATA("DEMO DATA"), PLACEHOLDER_CONTENT("PLACEHOLDER CONTENT"), LEGAL_PLACEHOLDER("LEGAL PLACEHOLDER"),
        DEMO_ONLY("DEMO ONLY · NOTHING IS SENT");

        public final String text;

        Data(String text) {
            this.text = text;
        }
    }

    private ByxBadge() {
    }

    public static Label of(String text, Tone tone) {
        Label l = new Label(text);
        l.getStyleClass().add("byx-badge");
        String t = toneClass(tone);
        if (t != null) {
            l.getStyleClass().add(t);
        }
        return l;
    }

    public static Label availability(Availability a) {
        Label l = of(a.text, a.tone);
        if (a.icon != null) {
            l.setGraphic(ByxIcon.of(a.icon, 12, null));
        } else {
            Circle dot = new Circle(3);
            dot.getStyleClass().add("byx-dot");
            l.setGraphic(dot);
        }
        l.getProperties().put("byx.availability", a);
        return l;
    }

    public static Label data(Data d) {
        Label l = of(d.text, Tone.WARNING);
        l.setGraphic(ByxIcon.of("warning", 12, null));
        return l;
    }

    static String toneClass(Tone tone) {
        return switch (tone) {
            case POSITIVE -> "tone-pos";
            case WARNING -> "tone-wrn";
            case NEGATIVE -> "tone-neg";
            case INFO -> "tone-inf";
            case ACCENT -> "tone-accent";
            case NEUTRAL -> null;
        };
    }
}
