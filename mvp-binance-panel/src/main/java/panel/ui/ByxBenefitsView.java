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
    private final VBox gas = new VBox(8);
    private String gasMessage = "Native feegrant; explicit DEV TEST signer required";
    private boolean gasBusy;
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
        timer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(30), e -> refresh()));
        timer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        root.sceneProperty().addListener((o,a,b) -> { if (b == null) timer.stop(); });
        VBox methods = new VBox(8);
        benefits.payments().methods().forEach(method -> methods.getChildren().add(
                Ui.kv(method.name(), "Não conectado")));
        body.getChildren().addAll(Ui.pageHeader("BYX Benefits", "TEST POLICY · NOT FINAL TOKENOMICS", refresh),
                Ui.testEnvironment(), Ui.kvNode("Wallet", selected), current,
                Ui.columns(Ui.card("Pay to unlock · TEST payment intent", payment),
                        Ui.card("Gas sponsorship · LOCALNET TEST", gas)),
                Ui.card("BYX never grants", Ui.kv("ADMIN", "NEVER"), Ui.kv("VALIDATION", "NEVER"),
                        Ui.kv("FINAL_HOLDOUT", "NEVER"), Ui.kv("Unapproved live trading", "NEVER")),
                Ui.card("Independent payment methods", methods,
                        Ui.label("Normal app access does not require a wallet. Application fees, BYX gas and Binance fees remain separate.", "muted")));

    }
    private void renderGas(String address) {
        var request=Ui.button("Request TEST gas allowance", "ghost");
        var refresh=Ui.button("Refresh / reconcile grant", "ghost");
        var revoke=Ui.button("Revoke TEST allowance", "ghost");
        request.setDisable(gasBusy || address==null || ctx.byxWallets.verified(address).isEmpty());
        refresh.setDisable(gasBusy || address==null); revoke.setDisable(gasBusy || address==null);
        request.setOnAction(e -> gasAction(address, "request"));
        refresh.setOnAction(e -> gasAction(address, "refresh"));
        revoke.setOnAction(e -> gasAction(address, "revoke"));
        gas.getChildren().setAll(Ui.label("LOCALNET / TEST ASSETS / NO FINANCIAL VALUE", "muted"),
                Ui.label("One bounded V1 quota; no automatic replenishment. Only native MsgSend fees.", "muted"),
                Ui.label(gasMessage,"muted"),request,refresh,revoke);
    }
    private void gasAction(String address, String action) {
        var session=ctx.sessions.user().orElseThrow().id(); gasBusy=true;
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                if (action.equals("revoke")) {ctx.byxGas.revoke(address); return "REVOKED on-chain";}
                var g=action.equals("request")?ctx.byxGas.request(address):ctx.byxGas.refresh(address);
                return g.state()+" · remaining "+g.remaining()+" ubyx · limit "+g.spendLimit()+" ubyx · expires "+g.expiration();
            } catch(Exception failure) {return "Unavailable / blocked: "+failure.getMessage();}
        }).whenComplete((message,error) -> javafx.application.Platform.runLater(() -> {
            gasBusy=false;
            if (ctx.sessions.user().filter(u -> u.id().equals(session)).isEmpty()) {gasMessage="Session changed";return;}
            gasMessage=message;onSnapshot(null);
        }));
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
    private static javafx.scene.layout.HBox tierTrack(String currentTier) {
        var track = new javafx.scene.layout.HBox(8);
        for (String tier : java.util.List.of("FREE", "HOLDER", "PLUS", "PRO")) {
            var label = Ui.label(tier, "tier-step");
            if (tier.equals(currentTier)) label.getStyleClass().add("tier-current");
            track.getChildren().add(label);
        }
        return track;
    }
    private static String format(java.math.BigInteger amount) {
        return amount == null ? "UNKNOWN / NOT APPLICABLE" : new java.math.BigDecimal(amount, 6).toPlainString() + " BYX";
    }
    private final javafx.animation.Timeline timer;
    public Node node() { return root; }
    @Override public void onShow() { refresh(); timer.play(); }
    @Override public void onHide() { timer.stop(); }
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
            renderGas(address);
            current.getChildren().setAll(Ui.columns(Ui.card("Current tier · HOLD_TO_UNLOCK", Ui.metric("Current tier", s.tier(), "info"), tierTrack(s.tier()),
                    Ui.kv("Verified wallet", s.walletStatus() + " · " + (address == null ? "—" : address)),
                    Ui.kv("BYX balance", s.formattedBalance()), Ui.kv("Network", "LOCALNET · " + s.chainState()),
                    Ui.kv("Last refresh", s.lastChainUpdate() == null ? "UNKNOWN" : s.lastChainUpdate().toString())),
                    Ui.card("YOUR BENEFITS", rows)),
                    Ui.columns(
                    Ui.card("Extended History · LOCALNET preview", openHistory, history,
                            Ui.label("Public wallet balance refresh history, up to 20 entries in this app session. No research data or trading execution.", "muted")),
                    Ui.card("Advanced Analytics — synthetic demo",analyticsButton,analytics)),
                    Ui.card("Next tier", Ui.kv("Next tier", progress.nextTier()),
                            Ui.kv("Required BYX", format(progress.requiredUbyx())),
                            Ui.kv("Current BYX", format(progress.currentUbyx())),
                            Ui.kv("Remaining BYX", format(progress.remainingUbyx()))));
        } catch (RuntimeException e) {
            current.getChildren().setAll(Ui.columns(Ui.card("Current tier", Ui.metric("No verified wallet", "FREE", "muted"), tierTrack("FREE"),
                    Ui.kv("BYX balance", "N/A"), Ui.label("LOCALNET configuration required", "muted")),
                    Ui.card("HOLD_TO_UNLOCK", Ui.label("Verify wallet ownership to evaluate tier and entitlements.", "muted"),
                            Ui.kv("Next tier / remaining", "N/A"))));
            var create = Ui.button("Unlock with BYX (TEST)", "ghost"); create.setId("byx-payment-create"); create.setDisable(true);
            payment.getChildren().setAll(Ui.label("No intent open · verified wallet and LOCALNET required", "muted"), create);
            gas.getChildren().setAll(Ui.kv("Eligibility", "Unavailable · verified wallet required"), Ui.kv("Quota / remaining / expiration", "N/A"));
        }
    }
}
