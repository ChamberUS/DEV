package panel.helpview;

import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import panel.app.AppBranding;
import panel.app.AppInfo;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.v2.Kit;

/**
 * About BYX V2 (página de leitura 1240). Só fatos fornecidos pela especificação: Cosmos SDK, ubyx exibido como BYX com
 * 6 casas, LOCALNET. Nada de licenças, certificações, regulação, clientes, parcerias ou números comerciais: missão e texto
 * completo são PLACEHOLDER_CONTENT. Versão e build vêm do build; copiar a versão copia exatamente o que é mostrado.
 */
public final class AboutScreen implements View {
    private final ScrollPane scroll;
    private final MotionService motion;
    private final Consumer<String> copy;

    /** links: rotas de Help abertas pelos botões (vazio no modo público, que só tem páginas públicas). */
    public AboutScreen(MotionService motion, Consumer<String> navigate, Consumer<String> copy, boolean publicMode) {
        this.motion = motion;
        this.copy = copy;
        VBox page = Kit.reading(14, 1240);
        HBox ledger = new HBox(0);
        VBox bars = new VBox(4);
        for (double w : new double[] {26, 18, 10}) {
            Region r = new Region();
            r.getStyleClass().add("byx-ledger");
            r.setPrefSize(w, 6);
            r.setMinSize(w, 6);
            r.setMaxSize(w, 6);
            bars.getChildren().add(r);
        }
        VBox title = new VBox(2, Fx.label("BY BUYNNEX", "byx-label"), Fx.label(AppBranding.NAME, "byx-page-title"),
                Kit.muted("A desktop terminal for market observation, scientific research and the BYX ecosystem."));
        HBox head = new HBox(16, bars, title, Fx.spacer(), ByxBadge.of("LOCALNET", ByxBadge.Tone.WARNING),
                ByxBadge.of("LIVE TRADING OFF", ByxBadge.Tone.NEUTRAL), ByxBadge.data(ByxBadge.Data.PLACEHOLDER_CONTENT));
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("byx-panel");
        page.getChildren().add(head);
        page.getChildren().add(section("What is BYX", Kit.muted("BYX is a blockchain built with Cosmos SDK. Its base denomination is ubyx, displayed as BYX with 6 decimals. "
                + "In this build it runs as LOCALNET for development."), Kit.dim("[PLACEHOLDER_CONTENT: full description of BYX to be provided]")));
        page.getChildren().add(section("Mission", Kit.dim("[PLACEHOLDER_CONTENT: mission statement to be provided by Buynnex]")));
        page.getChildren().add(section("Product philosophy", principle("Research before risk", "Live trading stays off until strategies pass validation."),
                principle("Measured, not assumed", "Missing data shows as N/A or Waiting. Values are never simulated."),
                principle("Clear environments", "TEST, PAPER and REAL never mix, and TEST never looks like money.")));
        page.getChildren().add(section("Architecture overview", principle("Desktop app", "BYX-MVP · JavaFX. Trading, Research, BYX, Account."),
                principle("Quant backend", "Python services. Capture, datasets, research guard."),
                principle("Market data", "Binance USD-M Futures. Data source only, read-only."),
                principle("BYX chain", "Cosmos SDK · ubyx. Displayed as BYX, 6 decimals."),
                principle("Environments", "LOCALNET · DEVNET. MAINNET not configured."),
                Kit.dim("Conceptual overview. Exact connection paths depend on configuration.")));
        page.getChildren().add(section("Security philosophy", Kit.muted("BYX-MVP never holds private keys or seed phrases. Wallet ownership is proven by signing in your wallet."),
                Kit.muted("Live trading and research gates are enforced by the system. The interface can not override them."),
                Kit.muted("Diagnostics never include passwords, tokens, keys or session secrets."),
                Kit.muted("Admin access requires a verified admin session.")));
        page.getChildren().add(section("Environment philosophy", principle("LOCALNET", "Development network on your machine."),
                principle("TEST", "Assets with no financial value."), principle("PAPER", "Simulated capital for the bot."),
                principle("REAL", "Only verified sources. Not configured.")));
        ByxButton copyVersion = new ByxButton("Copy version info", ByxButton.Variant.SECONDARY, motion);
        copyVersion.setOnAction(e -> this.copy.accept(AppInfo.versionLine() + " · " + AppInfo.ENVIRONMENT));
        page.getChildren().add(section("Version and build", Kit.row("Version", AppInfo.VERSION, true), Kit.row("Build", AppInfo.build(), true),
                Kit.row("Environment", AppInfo.ENVIRONMENT, false), copyVersion, Kit.dim("Values are read from the build, never typed in.")));
        HBox docs = new HBox(8);
        if (!publicMode) {
            docs.getChildren().add(link("Product overview", "h-overview", navigate));
            docs.getChildren().add(link("What's new", "h-whats-new", navigate));
        }
        docs.getChildren().add(link("Terms of Use", "h-terms", navigate));
        docs.getChildren().add(link("Privacy", "h-privacy", navigate));
        docs.getChildren().add(ByxBadge.availability(ByxBadge.Availability.NOT_CONFIGURED));
        docs.getChildren().add(Fx.label("Open-source notices", "byx-desk-t3"));
        page.getChildren().add(section("Documents", docs, Kit.dim("© [YEAR] Buynnex. [OWNERSHIP_PLACEHOLDER]")));
        scroll = Kit.scroll(page);
    }

    private ByxButton link(String text, String route, Consumer<String> navigate) {
        ByxButton b = new ByxButton(text, ByxButton.Variant.SECONDARY, motion);
        b.setOnAction(e -> navigate.accept(route));
        return b;
    }

    private static Node principle(String title, String text) {
        VBox v = new VBox(2, Fx.label(title, "byx-section-title-sm"), Kit.muted(text));
        v.setPadding(new javafx.geometry.Insets(4, 0, 4, 0));
        return v;
    }

    private static Node section(String title, Node... body) {
        return Kit.panel(title, body);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }
}
