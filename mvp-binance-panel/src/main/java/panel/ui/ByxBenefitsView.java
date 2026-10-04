package panel.ui;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.model.Snapshot;

public final class ByxBenefitsView implements View {
    private final VBox body = Ui.page();
    private final ScrollPane root = Ui.scroll(body);
    private final AppContext ctx;
    private final VBox current = new VBox(8);
    private final javafx.scene.control.ComboBox<String> selected = new javafx.scene.control.ComboBox<>();
    public ByxBenefitsView(AppContext ctx) {
        this.ctx = ctx;
        var benefits = ctx.byxBenefits;
        selected.valueProperty().addListener((o,a,b) -> refresh());
        var refresh = Ui.button("Refresh benefits", "ghost"); refresh.setOnAction(e -> refresh());
        var timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> refresh()));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        root.visibleProperty().addListener((o,a,b) -> { if (b) { refresh(); timer.play(); } else timer.stop(); });
        root.sceneProperty().addListener((o,a,b) -> { if (b == null) timer.stop(); });
        VBox methods = new VBox(8);
        benefits.payments().methods().forEach(method -> methods.getChildren().add(
                Ui.kv(method.name(), "Não conectado")));
        body.getChildren().addAll(Ui.label("Plano e benefícios BYX", "h1"),
                Ui.label("LOCALNET / TEST ASSETS / NO FINANCIAL VALUE", "muted"), Ui.kvNode("Wallet", selected), refresh, current,
                Ui.card("Acesso normal", Ui.label("Use o aplicativo sem carteira. BYX é opcional; pagamentos não serão exclusivos em BYX.", "muted")),
                Ui.card("Métodos de pagamento independentes", methods),
                Ui.card("Descontos e recursos do aplicativo",
                        Ui.kv("Desconto", "Não definido / sem concessão"),
                        Ui.kv("Limites adicionais", "Não definidos / acesso normal preservado")),
                Ui.card("Três custos distintos",
                        Ui.kv("Taxas do aplicativo", "Política futura; sem preços ou percentuais contratados"),
                        Ui.kv("Taxas da rede BYX", "Subsídio opcional futuro; não concedido"),
                        Ui.kv("Taxas da exchange", "Independentes; custos Binance preservados")),
                Ui.card("Prova de controle obrigatória",
                        Ui.label("Digitar um endereço não comprova propriedade. Abra BYX → Wallet para verificar uma assinatura ADR-036.", "muted"),
                        Ui.label("Benefícios não concedem ADMIN, acesso a dados reservados ou execução de estratégias.", "muted")),
                Ui.card("Tesouraria", Ui.label("Saldos LOCALNET são ATIVOS DE TESTE / SEM VALOR FINANCEIRO.", "muted"),
                        Ui.label("Sem reservas verificadas, promessa de lastro, resgate ou rendimento.", "muted")));
    }
    private void refresh() {
        try { ctx.byxBenefits.refresh(selected.getValue()).whenComplete((s,e) -> javafx.application.Platform.runLater(() -> onSnapshot(null))); }
        catch (RuntimeException e) { onSnapshot(null); }
    }
    public Node node() { return root; }
    public void onSnapshot(Snapshot ignored) {
        try {
            var wallets = ctx.byxWallets.wallets();
            var addresses = wallets.stream().map(panel.model.VerifiedWallet::address).toList();
            if (!selected.getItems().equals(addresses)) selected.getItems().setAll(addresses);
            if (selected.getValue() == null && !addresses.isEmpty()) selected.setValue(addresses.get(0));
            String address = selected.getValue();
            var s = ctx.byxBenefits.snapshot(address);
            current.getChildren().setAll(Ui.card("Experimental benefits", Ui.kv("Current tier", s.tier()),
                    Ui.kv("Verified wallet", s.walletStatus() + " · " + (address == null ? "—" : address)), Ui.kv("BYX balance", s.formattedBalance()),
                    Ui.kv("Available benefits", String.join(" · ", s.availableBenefits())), Ui.kv("Next tier", s.nextTier()),
                    Ui.kv("Last chain update", s.lastChainUpdate() == null ? "UNKNOWN" : s.lastChainUpdate().toString())));
        } catch (RuntimeException e) { current.getChildren().setAll(Ui.kv("Current tier", "FREE · LOCALNET configuration required")); }
    }
}
