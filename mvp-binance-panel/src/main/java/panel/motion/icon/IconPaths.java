package panel.motion.icon;

import java.util.LinkedHashMap;
import java.util.Map;
import panel.motion.icon.SvgIcon.Kind;

/** Catálogo de ícones nativos (desenhados para este projeto, traço 24x24). Lottie opcional por nome. */
public final class IconPaths {
    public static final Map<String, AnimationAsset> CATALOG = new LinkedHashMap<>();

    private static void add(String name, String svg, Kind kind, String lottie) {
        CATALOG.put(name, new AnimationAsset(name, svg, kind, lottie));
    }

    static {
        add("lock", "M6 11h12v9H6z M8 11V8a4 4 0 0 1 8 0v3", Kind.SHAKE, null);
        add("unlock", "M6 11h12v9H6z M8 11V8a4 4 0 0 1 7.6-1.7", Kind.POP, null);
        add("check", "M5 12.5l4.5 4.5L19 7.5", Kind.DRAW, "/animations/original/check-pop.json");
        add("warning", "M12 4l9 16H3z M12 10v4 M12 17v.4", Kind.SHAKE, null);
        add("error", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M9 9l6 6 M15 9l-6 6", Kind.SHAKE, null);
        add("info", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 11v5 M12 8v.4", Kind.POP, null);
        add("mail", "M3 6h18v12H3z M3 7l9 6 9-6", Kind.RING, null);
        add("phone", "M8 3h8v18H8z M11 18h2", Kind.RING, null);
        add("refresh", "M20 12a8 8 0 1 1-2.6-5.9 M20 4v5h-5", Kind.SPIN, null);
        add("chart", "M4 19V5 M4 19h16 M7 15l4-5 3 3 5-7", Kind.DRAW, null);
        add("signal", "M4 18v-3 M9 18v-7 M14 18v-11 M19 18V4", Kind.DRAW, null);
        add("user", "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M4 20c0-4 4-6 8-6s8 2 8 6", Kind.POP, null);
        add("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", Kind.POP, null);
        add("logout", "M10 4H5v16h5 M14 8l4 4-4 4 M18 12H9", Kind.POP, null);
        add("settings", "M4 8h16 M4 16h16 M9 5v6 M15 13v6", Kind.POP, null);
        add("orders", "M5 6h14 M5 12h14 M5 18h9", Kind.POP, null);
        add("positions", "M4 8h16v11H4z M9 8V5h6v3", Kind.POP, null);
        add("bot", "M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9z M9.5 12h5", Kind.POP, "/animations/original/pulse-ring.json");
        add("search", "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z M20 20l-4-4", Kind.POP, null);
        add("feed", "M4 12a8 8 0 0 1 8-8 M4 12a8 8 0 0 0 8 8 M8 12a4 4 0 0 1 4-4 M12 12h.01", Kind.POP, null);
        add("dashboard", "M4 4h7v7H4z M13 4h7v4h-7z M13 10h7v10h-7z M4 13h7v7H4z", Kind.POP, null);
        add("clock", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v5l3 2", Kind.POP, null);
    }

    private IconPaths() {
    }
}
