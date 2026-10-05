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
    private static final Map<String, Route> ROUTES = new LinkedHashMap<>();

    private static void add(String id, ShellContext c, String title, String rail, String icon, int order) {
        ROUTES.put(id, new Route(id, c, title, rail, icon, order));
    }

    static {
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
        add("t-wallet", ShellContext.BYX, "Wallet", "Wallet", "wallet", 1);
        add("t-benefits", ShellContext.BYX, "Benefits", "Benefits", "benefits", 2);
        add("t-treasury", ShellContext.BYX, "Treasury", "Treasury", "treasury", 3);
        add("t-wallet-verify", ShellContext.BYX, "Verify wallet ownership", null, null, -1);
        // Account (não é workspace): Profile existe; Settings fica no pé de todo rail
        add("t-profile", ShellContext.ACCOUNT, "Profile", "Profile", "profile", 0);
        add(SETTINGS, ShellContext.ACCOUNT, "Settings", "Settings", "settings", -1);
    }

    /** Rail Account da referência: telas que chegam no passo 10. */
    public static final List<Pending> ACCOUNT_PENDING = List.of(
            new Pending("Security", "security", "Security arrives in step 10"),
            new Pending("Sessions", "sessions", "Sessions and devices arrive in step 10"),
            new Pending("Notifications", "bell", "The notification center arrives in step 10"),
            new Pending("Activity", "activity", "Account activity arrives in step 10"));

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
        };
    }

    public static ShellContext contextOf(String id) {
        return get(id).map(Route::context).orElse(ShellContext.TRADING);
    }
}
