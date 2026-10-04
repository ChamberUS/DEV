package panel.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.app.AppContext;
import panel.model.*;

public final class ByxWalletView implements View {
    private final AppContext ctx;
    private final VBox body = Ui.page(), summary = new VBox(8);
    private final ScrollPane root = Ui.scroll(body);
    private final TextField address = new TextField();
    private final ComboBox<String> linked = new ComboBox<>();
    private final TextArea challengeText = new TextArea(), proofText = new TextArea();
    private final Label status = Ui.label("NO WALLET", "muted");
    private final ObjectMapper json = new ObjectMapper();
    private WalletChallenge challenge;
    private int ticks;
    private boolean verificationRequired;
    public ByxWalletView(AppContext ctx) {
        this.ctx = ctx;
        address.setPrefColumnCount(46);
        linked.setPromptText("Select a linked wallet");
        address.setPromptText("byx1… (watch-only until proof verified)");
        address.textProperty().addListener((o,a,b) -> { challenge = null; verificationRequired = false; challengeText.clear(); render(); });
        linked.valueProperty().addListener((o,a,b) -> { if (b != null) address.setText(b); });
        challengeText.getStyleClass().add("text-field"); proofText.getStyleClass().add("text-field");
        challengeText.setEditable(false); challengeText.setPrefRowCount(5); challengeText.setWrapText(true);
        proofText.setPrefRowCount(4); proofText.setPromptText("WalletProof JSON: challenge, publicKey (base64), signature (base64)");
        Button link = Ui.button("Link BYX Wallet / Reverify", "primary");
        link.setOnAction(e -> {
            try { challenge = ctx.byxWallets.challenge(address.getText().trim()); verificationRequired = true;
                challengeText.setText(new String(panel.security.CosmosWalletProof.message(challenge), java.nio.charset.StandardCharsets.UTF_8)); status.setText("VERIFICATION REQUIRED · expires in 5 minutes");
            } catch (Exception error) { status.setText(error.getMessage()); }
        });
        Button copyChallenge = Ui.button("Copy DEV challenge JSON", "ghost");
        copyChallenge.setOnAction(e -> {
            try { if (challenge != null) { var content = new javafx.scene.input.ClipboardContent();
                content.putString(json.writeValueAsString(challenge)); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); }
            } catch (java.io.IOException error) { status.setText("Cannot encode challenge"); }
        });
        Button verify = Ui.button("Verify signature", "primary");
        verify.setOnAction(e -> {
            try {
                if (challenge == null || proofText.getText().length() > 8192) throw new IllegalArgumentException("Generate a challenge first; proof limit 8 KiB");
                WalletProof proof = json.readValue(proofText.getText(), WalletProof.class);
                if (!challenge.equals(proof.challenge())) throw new IllegalArgumentException("Proof does not match the displayed challenge");
                var session = ctx.sessions.user().orElseThrow().id();
                status.setText("VERIFYING"); verify.setDisable(true);
                java.util.concurrent.CompletableFuture.supplyAsync(() -> ctx.byxWallets.verify(proof)).whenComplete((wallet,error) -> Platform.runLater(() -> {
                    verify.setDisable(false);
                    if (ctx.sessions.user().filter(u -> u.id().equals(session)).isEmpty()) return;
                    challenge = null; challengeText.clear(); proofText.clear();
                    if (error != null) { verificationRequired = true; status.setText("VERIFICATION REQUIRED · invalid, expired or used proof"); }
                    else { verificationRequired = false; status.setText("VERIFIED · wallet linked"); refresh(); }
                    render();
                }));
            } catch (Exception error) { status.setText(error.getMessage()); }
        });
        Button revoke = Ui.button("Unlink / Revoke", "danger");
        revoke.setOnAction(e -> {
            try { ctx.byxWallets.revoke(address.getText().trim()); verificationRequired = false; challenge = null; challengeText.clear(); proofText.clear(); render(); }
            catch (RuntimeException error) { status.setText(error.getMessage()); }
        });
        Button refresh = Ui.button("Refresh balance and tier", "ghost"); refresh.setOnAction(e -> refresh());
        body.getChildren().addAll(Ui.label("BYX → Wallet", "h1"), Ui.label("LOCALNET / TEST ASSETS / NO FINANCIAL VALUE", "muted"),
                Ui.label("No private keys. Normal app access does not require a wallet.", "muted"), status,
                Ui.kvNode("Linked wallets", linked), Ui.kvNode("Address", address), link,
                Ui.card("Exact UTF-8 data to sign using ADR-036", challengeText, copyChallenge,
                        Ui.label("Sign externally. Paste only the public proof below; never paste a seed/private key.", "muted")),
                proofText, verify, revoke, refresh, summary);
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> { render(); if (++ticks % 30 == 0) refresh(); }));
        timer.setCycleCount(Timeline.INDEFINITE);
        root.visibleProperty().addListener((o,a,b) -> { if (b) { render(); timer.play(); } else timer.stop(); });
        root.sceneProperty().addListener((o,a,b) -> { if (b == null) timer.stop(); });
    }
    private void refresh() {
        try { ctx.byxBenefits.refresh(address.getText().trim()).whenComplete((value,error) -> Platform.runLater(() -> {
            if (root.getScene() != null) { if (error != null) status.setText("CHAIN OFFLINE / authentication required"); render(); }
        })); } catch (RuntimeException error) { status.setText(error.getMessage()); }
    }
    private void render() {
        try {
            var wallets = ctx.byxWallets.wallets().stream().map(VerifiedWallet::address).toList();
            if (!linked.getItems().equals(wallets)) linked.getItems().setAll(wallets);
            if (wallets.contains(address.getText()) && !address.getText().equals(linked.getValue())) linked.setValue(address.getText());
            var s = ctx.byxBenefits.snapshot(address.getText().trim());
            if (!status.getText().equals("VERIFYING")) {
                if (challenge != null && !java.time.Instant.now().isBefore(java.time.Instant.parse(challenge.expiresAt()))) status.setText("EXPIRED/REVOKED · generate a new challenge");
                else if (verificationRequired) status.setText("VERIFICATION REQUIRED");
                else status.setText(s.walletStatus());
            }
            summary.getChildren().setAll(Ui.kv("Balance", s.formattedBalance()), Ui.kv("Current tier", s.tier()),
                    Ui.kv("Benefits enabled (TEST)", Boolean.toString(s.benefitsEnabled())), Ui.kv("Chain", s.chainState()),
                    Ui.kv("Last chain update", s.lastChainUpdate() == null ? "UNKNOWN" : s.lastChainUpdate().toString()), Ui.kv("Next tier", s.nextTier()));
        } catch (RuntimeException error) { status.setText("NO WALLET · ADMIN must configure LOCALNET in BYX Network"); summary.getChildren().clear(); }
    }
    public Node node() { return root; }
    public void onSnapshot(Snapshot ignored) { render(); }
}
