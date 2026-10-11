package panel.shell;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registro de rotas do shell. Os ids são os das Views existentes (legadas) para que o roteador e as Views
 * continuem falando a mesma língua. {@code rail >= 0} = posição no rail do contexto (atalho Cmd/Ctrl+n);
 * as demais rotas seguem acessíveis pela busca e por links internos. Itens do rail Account cujas telas
 * ainda não existem são {@link Pending}: aparecem desabilitados com o motivo, nunca abrem tela falsa.
 */
public final class ShellRoutes {
    public record Route(String id, ShellContext context, String title, String railLabel, String icon, int rail) {
        public boolean inRail() {
            return rail >= 0;
        }
    }

    /** Destino V2 ainda sem tela (passo em que chega). */
    public record Pending(String label, String icon, String reason) {
    }

    public static final String SETTINGS = "t-settings";
    /** Home (Package B). Prefix {@code t-} keeps it outside {@link #isResearch}: Home never needs admin verification. */
    public static final String HOME = "t-home";
    /** Rail of the Home context: a launcher. Destinations keep their own context (and rail) once opened. */
    private static final List<String> HOME_RAIL = List.of(HOME, "t-desk", "t-markets", "t-byx", "t-benefits", "h-faq");
    private static final Map<String, Route> ROUTES = new LinkedHashMap<>();

    private static void add(String id, ShellContext c, String title, String rail, String icon, int order) {
        ROUTES.put(id, new Route(id, c, title, rail, icon, order));
    }

    static {
        add(HOME, ShellContext.HOME, "Home", "Home", "home", 0);
        // Trading (referência: Desk, Markets, Bot, Wallet = Portfolio, Orders)
        add("t-desk", ShellContext.TRADING, "Trading Desk", "Desk", "desk", 0);
        add("t-markets", ShellContext.TRADING, "Markets & Portfolio", "Markets", "markets", 1);
        add("t-bot", ShellContext.TRADING, "Bot", "Bot", "bot", 2);
        add("t-portfolio", ShellContext.TRADING, "Portfolio", "Wallet", "wallet", 3);
        add("t-orders", ShellContext.TRADING, "Orders", "Orders", "orders", 4);
        add("t-strategies", ShellContext.TRADING, "Strategies", null, null, -1);
        add("t-signals", ShellContext.TRADING, "Signals", null, null, -1);
        add("t-positions", ShellContext.TRADING, "Positions", null, null, -1);
        add("t-performance", ShellContext.TRADING, "Performance", null, null, -1);
        add("t-activity", ShellContext.TRADING, "Bot Activity", null, null, -1);
        // Research
        add("overview", ShellContext.RESEARCH, "Research Overview", "Overview", "desk", 0);
        add("capture", ShellContext.RESEARCH, "Capture", "Capture", "capture", 1);
        add("labels", ShellContext.RESEARCH, "Labels", "Labels", "orders", 2);
        add("features", ShellContext.RESEARCH, "Features", "Features", "markets", 3);
        add("hypotheses", ShellContext.RESEARCH, "Hypotheses", "Hypotheses", "hypotheses", 4);
        add("sessions", ShellContext.RESEARCH, "Sessions", null, null, -1);
        add("dataset", ShellContext.RESEARCH, "Dataset", null, null, -1);
        add("validation", ShellContext.RESEARCH, "Validation", null, null, -1);
        add("execution", ShellContext.RESEARCH, "Simulator", null, null, -1);
        add("paper", ShellContext.RESEARCH, "Paper / Shadow", null, null, -1);
        add("live", ShellContext.RESEARCH, "Live", null, null, -1);
        add("jobs", ShellContext.RESEARCH, "Jobs", null, null, -1);
        add("logs", ShellContext.RESEARCH, "Logs", null, null, -1);
        add("users", ShellContext.RESEARCH, "Users", null, null, -1);
        add("settings", ShellContext.RESEARCH, "Research Settings", null, null, -1);
        // BYX
        add("t-byx", ShellContext.BYX, "BYX Network", "Network", "network", 0);
        add("t-chain-data", ShellContext.BYX, "Chain data", "Chain data", "orders", 1); // leitura pública dos módulos (somente leitura)
        add("t-wallet", ShellContext.BYX, "Wallet", "Wallet", "wallet", 2);
        add("t-benefits", ShellContext.BYX, "Benefits", "Benefits", "benefits", 3);
        add("t-treasury", ShellContext.BYX, "Treasury", "Treasury", "treasury", 4);
        add("t-mascot-gallery", ShellContext.BYX, "Mascot gallery", null, null, -1); // DEV (LOCAL_QA): só pela command palette
        add("t-tx-lab", ShellContext.BYX, "Transaction Lab", null, null, -1); // DEV (LOCAL_QA, SINTÉTICO): só pela command palette; nunca no rail
        add("t-wallet-verify", ShellContext.BYX, "Verify wallet ownership", null, null, -1);
        // Account (não é workspace): Profile existe; Settings fica no pé de todo rail
        add("t-profile", ShellContext.ACCOUNT, "Profile", "Profile", "profile", 0);
        add("t-security", ShellContext.ACCOUNT, "Security", "Security", "security", 1);
        add("t-sessions", ShellContext.ACCOUNT, "Sessions and devices", "Sessions", "sessions", 2);
        add("t-notifications", ShellContext.ACCOUNT, "Notifications", "Notifications", "bell", 3);
        add("t-account-activity", ShellContext.ACCOUNT, "Activity", "Activity", "activity", 4);
        add(SETTINGS, ShellContext.ACCOUNT, "Settings", "Settings", "settings", -1);
        // Help (não é workspace): FAQ, Support, Diagnostics, About, Overview, What's new no rail; o resto por links
        add("h-faq", ShellContext.HELP, "FAQ", "FAQ", "help", 0);
        add("h-help", ShellContext.HELP, "Help and support", "Support", "support", 1);
        add("h-diagnostics", ShellContext.HELP, "Diagnostics", "Diagnostics", "pulse", 2);
        add("h-about", ShellContext.HELP, "About BYX", "About", "info", 3);
        add("h-overview", ShellContext.HELP, "Product overview", "Overview", "desk", 4);
        add("h-whats-new", ShellContext.HELP, "What's new", "What's new", "benefits", 5);
        add("sys-status", ShellContext.SYSTEM, "System Status", null, null, -1);
        add("sys-unavailable", ShellContext.SYSTEM, "Page unavailable", null, null, -1);
        add("h-terms", ShellContext.HELP, "Terms of Use", null, null, -1);
        add("h-privacy", ShellContext.HELP, "Privacy", null, null, -1);
        add("h-shortcuts", ShellContext.HELP, "Keyboard shortcuts", null, null, -1);
    }

    /** Páginas que existem antes do login (modo público): o roteador recusa qualquer outra rota sem sessão. */
    public static final java.util.Set<String> PUBLIC = java.util.Set.of("h-about", "h-faq", "h-help", "h-terms", "h-privacy");

    /** Rotas de Research (exigem sessão de admin verificada): tudo que não é Trading/BYX/Account (t-) nem Help (h-). */
    public static boolean isResearch(String id) {
        return !id.startsWith("t-") && !id.startsWith("h-") && !id.startsWith("sys-") && !id.startsWith("auth:");
    }

    private ShellRoutes() {
    }

    public static Optional<Route> get(String id) {
        return Optional.ofNullable(ROUTES.get(id));
    }

    public static Route require(String id) {
        Route r = ROUTES.get(id);
        if (r == null) {
            throw new IllegalArgumentException("unknown route " + id);
        }
        return r;
    }

    public static List<Route> all() {
        return List.copyOf(ROUTES.values());
    }

    /** Itens do rail do contexto, na ordem do atalho. */
    public static List<Route> rail(ShellContext c) {
        if (c == ShellContext.HOME) {
            return HOME_RAIL.stream().map(ROUTES::get).toList();
        }
        return ROUTES.values().stream().filter(r -> r.context() == c && r.inRail())
                .sorted(java.util.Comparator.comparingInt(Route::rail)).toList();
    }

    /** Rota inicial de cada workspace quando ainda não há uma última rota válida. */
    public static String home(ShellContext c) {
        return switch (c) {
            case TRADING -> "t-desk";
            case RESEARCH -> "overview";
            case BYX -> "t-byx";
            case ACCOUNT -> "t-profile";
            case HELP -> "h-faq";
            case SYSTEM -> "sys-status";
            case HOME -> HOME;
        };
    }

    public static ShellContext contextOf(String id) {
        return get(id).map(Route::context).orElse(ShellContext.TRADING);
    }
}
