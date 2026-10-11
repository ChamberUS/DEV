package panel.shell;

import java.util.Map;

/** Ícones do shell V2, copiados dos SVG das telas de referência (viewBox 24, traço). */
public final class ShellIcons {
    public static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("desk", "M4 4h7v7H4zM13 4h7v4h-7zM13 11h7v9h-7zM4 14h7v6H4z"),
            Map.entry("markets", "M4 18l5-6 4 3 7-9"),
            Map.entry("bot", "M5 9h14v10H5zM12 5v4M9 14h.01M15 14h.01"),
            Map.entry("wallet", "M4 7h16v12H4zM16 13h2"),
            Map.entry("orders", "M5 7h14M5 12h14M5 17h9"),
            Map.entry("settings", "M12 9a3 3 0 100 6 3 3 0 000-6zM12 3v3M12 18v3M3 12h3M18 12h3"),
            Map.entry("capture", "M5 6c0-2 14-2 14 0v12c0 2-14 2-14 0zM5 12c0 2 14 2 14 0"),
            Map.entry("hypotheses", "M10 4h4M11 4v6l-5 9h12l-5-9V4"),
            Map.entry("network", "M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5"),
            Map.entry("benefits", "M12 4l2.5 5 5.5.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.5-.8z"),
            Map.entry("treasury", "M4 5h16v14H4zM12 9a3 3 0 100 6 3 3 0 000-6"),
            Map.entry("profile", "M12 4a4 4 0 100 8 4 4 0 000-8zM5 20c0-4 14-4 14 0"),
            Map.entry("security", "M12 3l8 3v6c0 5-4 8-8 9-4-1-8-4-8-9V6z"),
            Map.entry("sessions", "M4 5h16v11H4zM2 19h20"),
            Map.entry("bell", "M6 16V11a6 6 0 0112 0v5l2 2H4zM10 20a2 2 0 004 0"),
            Map.entry("activity", "M12 3a9 9 0 100 18 9 9 0 000-18zM12 7v5l3 2"),
            Map.entry("search", "M11 4a7 7 0 100 14 7 7 0 000-14zM20 20l-4-4"),
            Map.entry("help", "M12 3a9 9 0 100 18 9 9 0 000-18zM9.5 9a2.5 2.5 0 015 .5c0 1.5-2.5 2-2.5 3.5M12 17v.4"),
            Map.entry("keyboard", "M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10"),
            Map.entry("info", "M12 3a9 9 0 100 18 9 9 0 000-18zM12 11v5M12 8v.4"),
            Map.entry("logout", "M9 4H5v16h4M16 8l4 4-4 4M20 12H9"),
            Map.entry("support", "M4 13a8 8 0 0116 0M4 13v3a2 2 0 002 2h1v-5H4M20 13v3a2 2 0 01-2 2h-1v-5h3"),
            Map.entry("pulse", "M3 12h4l3-7 4 14 3-7h4"),
            Map.entry("home", "M4 11l8-7 8 7M6 10v10h4v-6h4v6h4V10"),
            Map.entry("lock", "M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3"));

    private ShellIcons() {
    }

    public static String path(String name) {
        String p = PATHS.get(name);
        if (p == null) {
            throw new IllegalArgumentException("unknown shell icon " + name);
        }
        return p;
    }
}
