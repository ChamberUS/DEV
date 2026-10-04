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
    private final VBox payment = new VBox(8);
    private final javafx.scene.control.TextField txHash = new javafx.scene.control.TextField();
    private panel.model.PaymentIntent intent;
    private boolean confirming;
    private String paymentMessage = "LOCALNET / TEST PAYMENT / TEST ASSETS / NO FINANCIAL VALUE";
    private final javafx.scene.control.ComboBox<String> selected = new javafx.scene.control.ComboBox<>();
    public ByxBenefitsView(AppContext ctx) {
        this.ctx = ctx;
        txHash.setPrefColumnCount(64);
        var benefits = ctx.byxBenefits;
        selected.valueProperty().addListener((o,a,b) -> {
            if(intent!=null&&!intent.verifiedWallet().equals(b)){intent=null;txHash.clear();}
            refresh();
        });
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
                Ui.card("Advanced Analytics Pass — TEST",payment),
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
    private void renderPayment(String address) {
        var entitlement=ctx.byxEntitlements.snapshot(address).stream().filter(e->e.id().equals("advanced_analytics")).findFirst().orElseThrow();
        var create=Ui.button("Unlock with BYX (TEST)","ghost");create.setId("byx-payment-create");
        create.setDisable(confirming||entitlement.enabled()||ctx.byxWallets.verified(address).isEmpty());
        create.setOnAction(e -> {
            try {intent=ctx.byxPayments.create(address);txHash.clear();paymentMessage="AWAITING PAYMENT — sign externally; never enter a seed/private key";}
            catch(RuntimeException failure){paymentMessage="TEST intent unavailable: configure LOCALNET service wallet / verify ownership";}
            onSnapshot(null);
        });
        var copy=Ui.button("Copy tx hash","ghost");copy.setOnAction(e->{var data=new javafx.scene.input.ClipboardContent();data.putString(txHash.getText());javafx.scene.input.Clipboard.getSystemClipboard().setContent(data);});
        var confirm=Ui.button("Confirm transaction","ghost");confirm.setId("byx-payment-confirm");confirm.setDisable(confirming||intent==null);
        confirm.setOnAction(e->{
            if(intent==null)return;
            var session=ctx.sessions.user().orElseThrow().id();
            String id=intent.id(),hash=txHash.getText();confirming=true;paymentMessage="CONFIRMING — awaiting on-chain checks";onSnapshot(null);
            java.util.concurrent.CompletableFuture.supplyAsync(()->{
                try{return ctx.byxPayments.confirm(id,hash);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
            }).whenComplete((receipt,error)->javafx.application.Platform.runLater(()->{
                confirming=false;
                if(ctx.sessions.user().isEmpty()||!ctx.sessions.user().orElseThrow().id().equals(session)){intent=null;txHash.clear();paymentMessage="Session changed; verify current wallet";onSnapshot(null);return;}
                paymentMessage=error==null?"PAYMENT CONFIRMED — entitlement activated (TEST)":"Not confirmed — no entitlement granted";
                try{intent=ctx.byxPayments.get(id);}catch(RuntimeException changedSession){intent=null;}
                if(error==null)refresh();
                onSnapshot(null);
            }));
        });
        String status=entitlement.enabled()?("BYX_PAYMENT".equals(entitlement.source())?"UNLOCKED BY TEST PAYMENT until "+entitlement.expiresAt():"UNLOCKED BY TIER"):"LOCKED";
        payment.getChildren().setAll(Ui.label("LOCALNET / TEST PAYMENT / TEST ASSETS / NO FINANCIAL VALUE","muted"),Ui.kv("Advanced Analytics",status),create,Ui.label(paymentMessage,"muted"));
        if(intent!=null){
            try{intent=ctx.byxPayments.get(intent.id());}catch(RuntimeException changedSession){intent=null;return;}
            confirm.setDisable(confirming||!java.util.Set.of(panel.model.PaymentIntent.Status.CREATED,panel.model.PaymentIntent.Status.AWAITING_PAYMENT).contains(intent.status()));
            String recipient=intent.recipient();
            payment.getChildren().addAll(Ui.kv("Intent status",intent.status().name()),Ui.kv("Amount BYX",format(intent.amountUbyx())),
                    Ui.kv("Recipient",recipient.substring(0,12)+"…"+recipient.substring(recipient.length()-6)),
                    Ui.kv("Intent expiration",intent.expiresAt().toString()));
            var reference=new javafx.scene.control.TextField(intent.reference());reference.setEditable(false);reference.setPrefColumnCount(75);
            var recipientField=new javafx.scene.control.TextField(recipient);recipientField.setEditable(false);recipientField.setPrefColumnCount(46);
            payment.getChildren().addAll(Ui.kvNode("Recipient (copy)",recipientField),Ui.kvNode("Reference / exact memo",reference),
                    Ui.kvNode("Transaction hash",txHash),confirm,copy);
        }
    }
    private void refresh() {
        try { ctx.byxBenefits.refresh(selected.getValue()).whenComplete((s,e) -> javafx.application.Platform.runLater(() -> onSnapshot(null))); }
        catch (RuntimeException e) { onSnapshot(null); }
    }
    private static String format(java.math.BigInteger amount) {
        return amount == null ? "UNKNOWN / NOT APPLICABLE" : new java.math.BigDecimal(amount, 6).toPlainString() + " BYX";
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
            var rows = new VBox(8);
            ctx.byxEntitlements.snapshot(address).forEach(e -> rows.getChildren().add(
                    Ui.kv(e.displayName(), e.requiredTier() + " · " + e.status())));
            var history = new VBox(8);
            var openHistory = Ui.button("Open Extended History", "ghost");
            openHistory.setId("byx-extended-history");
            openHistory.setDisable(!ctx.byxEntitlements.allows(address, "extended_history"));
            openHistory.setOnAction(event -> {
                history.getChildren().clear();
                try {
                    ctx.byxEntitlements.extendedHistory(address).forEach(entry -> history.getChildren().add(
                            Ui.kv(entry.refreshedAt().toString(), format(entry.balanceUbyx()) + " · " + entry.tier())));
                } catch (panel.security.AccessDeniedException denied) {
                    history.getChildren().add(Ui.label("UNAVAILABLE — verified wallet and fresh chain required", "muted"));
                }
            });
            var progress = ctx.byxEntitlements.progress(address);
            var analytics = new VBox(8);
            var analyticsButton=Ui.button("Open Advanced Analytics (TEST)","ghost");
            analyticsButton.setId("byx-analytics-open");
            analyticsButton.setDisable(!ctx.byxEntitlements.allows(address,"advanced_analytics"));
            analyticsButton.setOnAction(event -> {
                analytics.getChildren().clear();
                try {ctx.byxEntitlements.analyticsPreview(address).forEach(text -> analytics.getChildren().add(Ui.label(text,"muted")));}
                catch(panel.security.AccessDeniedException denied){analytics.getChildren().add(Ui.label("UNAVAILABLE — pass/tier no longer valid","muted"));}
            });
            renderPayment(address);
            current.getChildren().setAll(Ui.card("Experimental benefits", Ui.kv("Current tier", s.tier()),
                    Ui.kv("Verified wallet", s.walletStatus() + " · " + (address == null ? "—" : address)),
                    Ui.kv("BYX balance", s.formattedBalance()), Ui.kv("Network", "LOCALNET · " + s.chainState()),
                    Ui.kv("Last refresh", s.lastChainUpdate() == null ? "UNKNOWN" : s.lastChainUpdate().toString())),
                    Ui.card("YOUR BENEFITS", rows),
                    Ui.card("Extended History · LOCALNET preview", openHistory, history,
                            Ui.label("Public wallet balance refresh history, up to 20 entries in this app session. No research data or trading execution.", "muted")),
                    Ui.card("Advanced Analytics — synthetic demo",analyticsButton,analytics),
                    Ui.card("Next tier", Ui.kv("Next tier", progress.nextTier()),
                            Ui.kv("Required BYX", format(progress.requiredUbyx())),
                            Ui.kv("Current BYX", format(progress.currentUbyx())),
                            Ui.kv("Remaining BYX", format(progress.remainingUbyx()))));
        } catch (RuntimeException e) { current.getChildren().setAll(Ui.kv("Current tier", "FREE · LOCALNET configuration required")); }
    }
}
