package panel.txview;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.v2.Kit;

/**
 * Transaction Lab: ferramenta de DESENVOLVIMENTO (LOCAL_QA, fora do rail, só pela command palette) para exercitar a arquitetura de transação SINTÉTICA do
 * serviço. É apresentação pura: monta um pedido tipado (quantia em ubyx inteiros), mostra a cotação que o serviço devolveu e pede a confirmação; quem valida,
 * calcula e autoriza é o serviço. Todo estado que não é real é rotulado "(fake)" e a tela inteira exibe SIMULATION / FAKE TX. Nenhuma chamada na thread FX:
 * tudo vai por um executor de trabalho e volta pelo executor da UI. No build DEFAULT a tela nem é registrada, e o serviço responde TX_DISABLED a tudo.
 */
public final class TransactionLab implements View {
    static final List<String> STEPS = List.of("Draft", "Simulating", "Quote ready", "Awaiting confirmation", "Confirmed", "Signing (fake)", "Broadcasting (fake)",
            "Submitted (fake)", "Confirmed on fake chain", "Failed", "Expired", "Unknown outcome");
    private static final String SENDER = "primary";

    private final TxLabService service;
    private final Executor work;
    private final Executor ui;
    private final LongSupplier clockMs;
    private final VBox root = new VBox(14);
    private final ScrollPane scroll;
    private final ByxField recipient = ByxField.text("Recipient (byx1…)");
    private final ByxField amount = ByxField.text("Amount (BYX)");
    private final ByxField memo = ByxField.text("Memo (optional)");
    private final Kit.Segmented feeMode;
    private final Label ubyxHelper = Fx.label("= — ubyx", "byx-desk-secondary", "mono");
    private final Label banner = Fx.label("", "byx-desk-secondary");
    private final Label message = Fx.label("", "byx-desk-secondary");
    private final FlowPane steps = new FlowPane(8, 8);
    private final ByxButton quoteButton;
    private final ByxButton confirmButton;
    private final ByxButton cancelButton;
    private final ByxButton newButton;
    private final VBox preview = new VBox(0);
    private final VBox confirmation = new VBox(0);
    private final List<KvRow> previewRows = new ArrayList<>();
    private final List<KvRow> confirmRows = new ArrayList<>();
    private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));

    private String operation = newOperation();
    private TxLabModel.View view;
    private String quotedFields;
    private boolean busy;
    private int generation;

    public TransactionLab(MotionService motion, TxLabService service, Executor work, Executor ui, LongSupplier clockMs) {
        this.service = service;
        this.work = work;
        this.ui = ui;
        this.clockMs = clockMs;
        feeMode = new Kit.Segmented(List.of("LOW", "STANDARD", "HIGH"), "STANDARD", m -> invalidateQuoteView());
        quoteButton = new ByxButton("Get quote", ByxButton.Variant.PRIMARY, motion);
        confirmButton = new ByxButton("Confirm (fake)", ByxButton.Variant.SECONDARY, motion);
        cancelButton = new ByxButton("Cancel quote", ByxButton.Variant.GHOST, motion);
        newButton = new ByxButton("New transaction", ByxButton.Variant.GHOST, motion);
        recipient.input().setId("tx-recipient");
        amount.input().setId("tx-amount");
        memo.input().setId("tx-memo");
        quoteButton.setId("tx-quote");
        confirmButton.setId("tx-confirm");
        cancelButton.setId("tx-cancel");
        newButton.setId("tx-new");
        message.setId("tx-message");
        message.setWrapText(true);
        banner.setId("tx-banner");
        ubyxHelper.setId("tx-ubyx");
        steps.setId("tx-steps");

        banner.setText("SIMULATION / FAKE TX — LOCAL_QA only. Nothing here is a real chain transaction: the signer, the chain transport and the funds are synthetic and live in the service's test composition.");
        banner.setWrapText(true);
        HBox bannerBox = Kit.environment("FAKE TX", "Transaction Lab", banner.getText());
        for (String s : STEPS) {
            Label l = Fx.label(s, "byx-desk-secondary");
            l.setUserData(s);
            steps.getChildren().add(l);
        }
        for (String k : new String[] {"Amount", "Estimated gas (simulated)", "Adjusted gas", "Gas price (ubyx/gas)", "Estimated fee", "Maximum fee", "Total debit"}) {
            previewRows.add(new KvRow(k));
        }
        for (String k : new String[] {"From", "To", "Amount", "Fee", "Total", "Network", "Memo", "Policy"}) {
            confirmRows.add(new KvRow(k));
        }
        preview.getChildren().addAll(previewRows);
        confirmation.getChildren().addAll(confirmRows);
        HBox actions = new HBox(10, quoteButton, confirmButton, cancelButton, newButton);
        root.getChildren().addAll(Kit.header("Transaction Lab", "Synthetic transaction pipeline (bank send). Amounts are exact integers in ubyx.", Kit.badge("FAKE TX", ByxBadge.Tone.WARNING)),
                bannerBox,
                Kit.panel("Draft", recipient, amount, ubyxHelper, memo, Kit.label("Fee mode"), feeMode, actions, message),
                Kit.panel("State", steps),
                Kit.panel("Fee preview", preview),
                Kit.panel("Confirmation", confirmation));
        root.getStyleClass().addAll("byx-desk", "byx-screen");
        root.setPadding(new javafx.geometry.Insets(22, 28, 24, 28));
        scroll = Kit.scroll(root);
        recipient.input().textProperty().addListener((o, a, b) -> fieldsChanged());
        amount.input().textProperty().addListener((o, a, b) -> fieldsChanged());
        memo.input().textProperty().addListener((o, a, b) -> fieldsChanged());
        quoteButton.setOnAction(e -> requestQuote());
        confirmButton.setOnAction(e -> confirm());
        cancelButton.setOnAction(e -> cancel());
        newButton.setOnAction(e -> reset());
        ticker.setCycleCount(Timeline.INDEFINITE);
        render();
    }

    private static String newOperation() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot s) { }

    @Override
    public void onShow() {
        ticker.play();
    }

    @Override
    public void onHide() {
        ticker.stop();
    }

    // ---- ações (a UI só pede; o serviço decide) -----------------------------------------------------------------------------------------

    private String currentFields() {
        return recipient.input().getText().trim() + "|" + amount.input().getText().trim() + "|" + memo.input().getText() + "|" + feeMode.selected();
    }

    private void fieldsChanged() {
        updateUbyxHelper();
        render();
    }

    private void invalidateQuoteView() {
        render();
    }

    private void updateUbyxHelper() {
        var ubyx = TxLabModel.parseByx(amount.input().getText().trim());
        Fx.text(ubyxHelper, ubyx.isPresent() ? "= " + ubyx.get() + " ubyx" : "= — ubyx");
    }

    void requestQuote() {
        if (busy) {
            return;
        }
        var ubyx = TxLabModel.parseByx(amount.input().getText().trim());
        if (ubyx.isEmpty()) {
            say("Enter a positive amount with at most 6 decimals.");
            return;
        }
        String fields = currentFields();
        String op = operation;
        int gen = ++generation;
        busy = true;
        view = view != null && TxLabModel.executionStarted(view.state()) ? view : null;
        render();
        String to = recipient.input().getText().trim();
        String m = memo.input().getText();
        String mode = feeMode.selected();
        work.execute(() -> {
            TxLabService.Reply r = safe(() -> service.prepare(op, SENDER, to, ubyx.get().toString(), m, mode));
            ui.execute(() -> {
                if (gen != generation) {
                    return;
                }
                busy = false;
                if (r.ok()) {
                    view = TxLabModel.parse(r.result()).orElse(null);
                    quotedFields = view == null ? null : fields;
                    say(view == null ? TxLabModel.errorText(null) : "");
                } else {
                    view = null;
                    quotedFields = null;
                    say(TxLabModel.errorText(r.code()));
                }
                render();
            });
        });
    }

    void confirm() {
        if (!TxLabModel.canConfirm(view, currentFields().equals(quotedFields), clockMs.getAsLong(), busy)) {
            return;
        }
        busy = true; // clique duplo: o botão já está desabilitado e esta guarda impede o segundo envio
        String op = operation;
        String quote = view.quote().quoteId();
        int gen = ++generation;
        render();
        work.execute(() -> {
            TxLabService.Reply r = safe(() -> service.confirm(op, quote));
            ui.execute(() -> {
                if (gen != generation) {
                    return;
                }
                busy = false;
                if (r.ok()) {
                    view = TxLabModel.parse(r.result()).orElse(view);
                    say(view != null && view.error() != null ? TxLabModel.errorText(view.error()) : "");
                } else {
                    say(TxLabModel.errorText(r.code()));
                    if (!"TX_DISABLED".equals(r.code()) && !"connection_closed".equals(r.code())) {
                        view = null; // cotação invalidada pelo serviço: nova cotação
                    }
                }
                render();
            });
        });
    }

    void cancel() {
        if (busy || view == null || TxLabModel.executionStarted(view.state())) {
            return;
        }
        String op = operation;
        int gen = ++generation;
        busy = true;
        work.execute(() -> {
            TxLabService.Reply r = safe(() -> service.cancel(op));
            ui.execute(() -> {
                if (gen != generation) {
                    return;
                }
                busy = false;
                view = r.ok() ? TxLabModel.parse(r.result()).orElse(null) : null;
                say(r.ok() ? "Quote cancelled." : TxLabModel.errorText(r.code()));
                render();
            });
        });
    }

    private void reset() {
        generation++;
        busy = false;
        view = null;
        quotedFields = null;
        operation = newOperation();
        say("");
        render();
    }

    private void tick() {
        if (view == null) {
            return;
        }
        if (!busy && ("SUBMITTED".equals(view.state()) || "UNKNOWN_OUTCOME".equals(view.state()))) {
            String op = operation;
            int gen = generation;
            busy = true;
            work.execute(() -> {
                TxLabService.Reply r = safe(() -> service.status(op));
                ui.execute(() -> {
                    busy = false;
                    if (gen == generation && r.ok()) {
                        view = TxLabModel.parse(r.result()).orElse(view);
                    }
                    render();
                });
            });
        }
        render(); // contagem/validade da cotação
    }

    private static TxLabService.Reply safe(java.util.function.Supplier<TxLabService.Reply> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return new TxLabService.Reply(false, "unavailable", null);
        }
    }

    private void say(String text) {
        Fx.text(message, text == null ? "" : text);
    }

    // ---- apresentação -------------------------------------------------------------------------------------------------------------------

    String currentStepLabel() {
        if (busy && (view == null || !TxLabModel.executionStarted(view.state()))) {
            return "Simulating";
        }
        if (view == null) {
            return "Draft";
        }
        String s = view.state();
        if ("EXPIRED".equals(s) || view.quote() != null && "AWAITING_CONFIRMATION".equals(s) && view.quote().expiredAt(clockMs.getAsLong())) {
            return "Expired";
        }
        return TxLabModel.stateLabel(s);
    }

    private void render() {
        String current = currentStepLabel();
        for (Node n : steps.getChildren()) {
            Label l = (Label) n;
            boolean on = current.equals(l.getUserData());
            Fx.cls(l, "tone-primary", on);
            l.setStyle(on ? "-fx-font-weight: bold;" : "");
        }
        TxLabModel.QuoteView q = view == null ? null : view.quote();
        boolean outdated = q != null && quotedFields != null && !currentFields().equals(quotedFields);
        if (q == null) {
            for (KvRow r : previewRows) {
                r.set("—", true, "dim");
            }
            for (KvRow r : confirmRows) {
                r.set("—", true, "dim");
            }
        } else {
            String tone = outdated ? "dim" : null;
            previewRows.get(0).set(TxLabModel.formatByx(q.amount()) + " BYX (" + q.amount() + " ubyx)", true, tone);
            previewRows.get(1).set(q.simulatedGas().toString(), true, tone);
            previewRows.get(2).set(q.adjustedGas().toString() + " (limit " + q.gasLimit() + ")", true, tone);
            previewRows.get(3).set(q.gasPrice(), true, tone);
            previewRows.get(4).set(TxLabModel.formatByx(q.fee()) + " BYX (" + q.fee() + " ubyx)", true, tone);
            previewRows.get(5).set(TxLabModel.formatByx(q.maximumFee()) + " BYX (" + q.maximumFee() + " ubyx)", true, tone);
            previewRows.get(6).set(TxLabModel.formatByx(q.totalDebit()) + " BYX (" + q.totalDebit() + " ubyx)", true, tone);
            confirmRows.get(0).set(q.sender(), true, tone);
            confirmRows.get(1).set(q.recipient(), true, tone);
            confirmRows.get(2).set(TxLabModel.formatByx(q.amount()) + " BYX", true, tone);
            confirmRows.get(3).set(TxLabModel.formatByx(q.fee()) + " BYX", true, tone);
            confirmRows.get(4).set(TxLabModel.formatByx(q.totalDebit()) + " BYX", true, tone);
            confirmRows.get(5).set(q.chainId() + " · generation " + q.chainGeneration() + " (FAKE)", true, tone);
            confirmRows.get(6).set(memo.input().getText().isEmpty() ? "(none)" : memo.input().getText() + " · digest " + q.memoDigest().substring(0, 12), false, tone);
            confirmRows.get(7).set(q.policy() + " " + q.policyVersion(), false, tone);
        }
        boolean live = q != null && !q.expiredAt(clockMs.getAsLong());
        if (q != null && !live && view != null && "AWAITING_CONFIRMATION".equals(view.state())) {
            say("The quote expired. Get a new quote.");
        } else if (outdated) {
            say("The fields changed since the quote. Get a new quote.");
        }
        boolean executed = view != null && TxLabModel.executionStarted(view.state());
        quoteButton.setDisable(busy || executed);
        confirmButton.setDisable(!TxLabModel.canConfirm(view, !outdated, clockMs.getAsLong(), busy));
        cancelButton.setDisable(busy || view == null || executed || view.quote() == null);
        newButton.setDisable(busy);
        recipient.setDisable(executed);
        amount.setDisable(executed);
        memo.setDisable(executed);
        quoteButton.setLoading(busy && !executed && "Simulating".equals(current));
    }

    // ---- teste ---------------------------------------------------------------------------------------------------------------------------

    TxLabModel.View viewForTest() {
        return view;
    }

    ByxButton confirmButtonForTest() {
        return confirmButton;
    }

    ByxButton quoteButtonForTest() {
        return quoteButton;
    }

    ByxField recipientForTest() {
        return recipient;
    }

    ByxField amountForTest() {
        return amount;
    }

    ByxField memoForTest() {
        return memo;
    }

    Kit.Segmented feeForTest() {
        return feeMode;
    }

    String bannerForTest() {
        return banner.getText();
    }

    String messageForTest() {
        return message.getText();
    }

    String ubyxHelperForTest() {
        return ubyxHelper.getText();
    }

    List<String> previewForTest() {
        List<String> out = new ArrayList<>();
        for (KvRow r : previewRows) {
            out.add(r.key() + "=" + r.valueLabel().getText());
        }
        return out;
    }

    List<String> confirmationForTest() {
        List<String> out = new ArrayList<>();
        for (KvRow r : confirmRows) {
            out.add(r.key() + "=" + r.valueLabel().getText());
        }
        return out;
    }
}
