package panel.byxview;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxField;
import panel.mascot.MascotActivity;
import panel.mascot.MascotAnchor;
import panel.mascot.MascotContext;
import panel.mascot.MascotGuide;
import panel.mascot.MascotHint;
import panel.mascot.MascotHintBubble;
import panel.mascot.MascotPlacement;
import panel.mascot.MascotPriority;
import panel.mascot.MascotSize;
import panel.mascot.MascotState;
import panel.mascot.MascotUsage;
import panel.mascot.MascotView;
import panel.model.ChainModules;
import panel.model.ChainModules.Reply;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.v2.Kit;

/**
 * BYX Chain Data: leitura PÚBLICA dos módulos da chain (lojas, payments, feesplit, certificados e saldo público de um endereço), SOMENTE LEITURA. Os dados vêm do serviço local por IPC verificado como DTOs
 * tipados; esta tela nunca conhece endpoint, URL ou rota e não tem nenhuma ação de escrita (sem assinar, enviar, criar, pagar). Todo I/O roda fora da thread FX; só a atualização final do modelo de
 * visão roda nela. Sem polling próprio nem timers: a tela pede ao serviço (que coalesce, aplica cache e limite de taxa) ao entrar, ao clicar e ao atualizar manualmente; ao sair, resultados em voo
 * são descartados. O dado dos módulos é PÚBLICO; a tela aparece depois do login só porque o app inteiro exige sessão (arquitetura geral), não porque o dado seja privado.
 */
public final class ChainDataScreen implements View {
    static final int ROW_CAP = 50;
    private static final long REFRESH_DEBOUNCE_MS = 1_000;

    private final ChainModules.Reader reader;
    private final ByxData network;
    private final KvRow hNode = new KvRow("Node state");
    private final KvRow hChain = new KvRow("Chain ID");
    private final KvRow hHeight = new KvRow("Height");
    private final KvRow hAge = new KvRow("Block age");
    private final KvRow hDenom = new KvRow("Denom (base / display)");
    private final MascotView mascot;
    private final MascotHintBubble bubble;
    private final MascotGuide guide = MascotGuide.session();
    private int emptySections;
    private final MascotActivity activity;
    private String lastChainState;
    private MascotUsage.Plan chainPlan;
    private final MotionService motion;
    private final Clock clock;
    private final Executor io;
    private final Executor ui;
    private final ExecutorService ownedIo;
    private final ScrollPane scroll;
    private final FlowPane moduleBadges = new FlowPane(8, 8);
    private final Label readStats = Kit.dim("");
    private final HBox healthState = new HBox();
    private final Section economics;
    private final List<Section> sections = new ArrayList<>();
    private volatile int epoch;
    private volatile boolean visible;
    private long lastRefreshMs;
    private boolean everShown;

    public ChainDataScreen(MotionService motion, Clock clock, ChainModules.Reader reader, ByxData network) {
        this(motion, clock, reader, network, null, Platform::runLater);
    }

    /** Testes injetam executores síncronos. {@code network}: o MESMO snapshot da tela Network (nenhuma fonte de requisição nova). */
    ChainDataScreen(MotionService motion, Clock clock, ChainModules.Reader reader, ByxData network, Executor io, Executor ui) {
        this.network = network;
        this.motion = motion;
        this.clock = clock;
        this.reader = reader;
        java.util.concurrent.ThreadPoolExecutor own = null;
        if (io == null) { // uma thread, sem fila ilimitada, que morre ociosa (nenhum thread fantasma entre sessões)
            own = new java.util.concurrent.ThreadPoolExecutor(1, 1, 10, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(32), r -> {
                Thread t = new Thread(r, "chain-data-io");
                t.setDaemon(true);
                return t;
            });
            own.allowCoreThreadTimeOut(true);
        }
        this.ownedIo = own;
        this.io = io == null ? ownedIo : io;
        this.ui = ui;

        badge(healthState, "READY", ByxBadge.Tone.NEUTRAL);
        ByxButton refresh = new ByxButton("Refresh", ByxButton.Variant.SECONDARY, motion).small();
        refresh.setId("chain-refresh");
        refresh.setOnAction(e -> refreshAll(true));
        // UMA presença do mascote nesta tela (identidade, não spinner): estado da chain + espera de consultas > 250 ms. Só começa em onShow (nada no login/startup).
        mascot = new MascotView(motion, MascotSize.MEDIUM);
        bubble = new MascotHintBubble(mascot);
        mascot.setInteractive(true, this::onMascotClick);
        activity = new MascotActivity(MascotActivity.fx(), shown -> {
            if (shown.isPresent()) {
                mascot.setState(shown.get());
            } else if (chainPlan != null) {
                MascotUsage.apply(mascot, chainPlan, false);
            } else {
                mascot.setState(MascotState.IDLE);
            }
        });
        VBox facts = new VBox(0, hNode, hChain, hHeight, hAge, hDenom);
        HBox.setHgrow(facts, javafx.scene.layout.Priority.ALWAYS);
        Node factsAndMascot = MascotPlacement.place(facts, mascot, MascotAnchor.TOP_RIGHT);
        VBox nodeHeader = Kit.panel(null, Kit.titled("Network", ByxBadge.of("READ ONLY", ByxBadge.Tone.INFO)), factsAndMascot,
                Kit.dim("Public data from the local BYX node. This page only reads data and cannot change anything."));
        nodeHeader.setId("chain-header");
        VBox health = Kit.panel(null, Kit.titled("Module status", healthState), moduleBadges, readStats,
                Kit.muted("The node can be live while one module's query is unavailable; each module is shown on its own. Refresh asks the local service, which applies its cache and rate limits."), refresh);
        health.setId("chain-health");

        economics = new Section("Economics", "chain-economics");
        economics.body.getChildren().add(Kit.muted("Fee split allocation is shown as the module's documented design. The chain does not expose a query for it, so it is not read from the node."));

        Lookup<ChainModules.Merchant> merchant = new Lookup<>("Merchant", "chain-merchant", "Merchant ID", reader::merchant, ChainDataScreen::merchantRows).notFound(MascotContext.MERCHANT_NOT_FOUND);
        Pager<ChainModules.Merchant> merchants = new Pager<>("Merchants", "chain-merchants", null, (id, cursor) -> reader.merchants(5, cursor), m -> m.id(), m -> m.name()).empty(MascotContext.EMPTY_MERCHANTS);
        Lookup<ChainModules.Payment> payment = new Lookup<>("Payment", "chain-payment", "Payment ID", reader::payment, ChainDataScreen::paymentRows).notFound(MascotContext.PAYMENT_NOT_FOUND);
        Pager<ChainModules.Payment> payments = new Pager<>("Payments by store", "chain-payments", "Store ID", (id, cursor) -> reader.paymentsByStore(id, 5, cursor), p -> p.id(),
                p -> p.amountDisplay() + " · " + p.status()).empty(MascotContext.EMPTY_PAYMENTS);
        Lookup<ChainModules.Certificate> cert = new Lookup<>("Certificate", "chain-certificate", "Certificate ID", reader::certificate, ChainDataScreen::certificateRows).notFound(MascotContext.CERTIFICATE_NOT_FOUND);
        Pager<ChainModules.Certificate> certs = new Pager<>("Certificates by merchant", "chain-certificates", "Merchant ID", (id, cursor) -> reader.certificatesByMerchant(id, 5, cursor), c -> c.id(),
                c -> (c.revoked() ? "REVOKED · " : "") + c.category() + " " + c.brand()).empty(MascotContext.EMPTY_CERTIFICATES);
        Lookup<ChainModules.Balance> balance = new Lookup<>("Address inspector", "chain-balance", "BYX address", reader::balance, ChainDataScreen::balanceRows);
        balance.note.setText("Reads a public balance only. Nothing is connected, stored or signed.");

        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Chain data", "Public, read-only data from the local BYX node, served by the local service."),
                Kit.environment("LOCALNET", "Development environment", "TEST assets only. Nothing here has real value. This area cannot change anything."),
                nodeHeader, health, economics.box, merchant.box, merchants.box, payment.box, payments.box, cert.box, certs.box, balance.box);
        scroll = Kit.scroll(page);
        sections.addAll(List.of(economics, merchant, merchants, payment, payments, cert, certs, balance));
    }

    private static void badge(HBox slot, String text, ByxBadge.Tone tone) {
        slot.getChildren().setAll(ByxBadge.of(text, tone));
    }

    @Override
    public void onSnapshot(panel.model.Snapshot ignored) {
        // sem assinatura: esta tela só pede dados ao serviço
    }

    // ---- ciclo de vida -----------------------------------------------------------------------------------------------------------------

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onShow() {
        visible = true;
        epoch++;
        lastChainState = null;
        chainPlan = null;
        if (network != null) {
            network.refreshNetwork(); // o mesmo caminho assíncrono e limitado da tela Network (o serviço coalesce)
        }
        renderHeader();
        refreshAll(false); // reaproveita o cache do serviço e atualiza em segundo plano
        sections.forEach(Section::shown);
        javafx.animation.PauseTransition firstVisit = new javafx.animation.PauseTransition(javafx.util.Duration.millis(900));
        firstVisit.setOnFinished(e -> { // primeira visita nesta sessão: uma dica curta, só se nada mais importante estiver acontecendo
            if (visible && mascot.getScene() != null && mascot.isVisible() && mascot.accepts(MascotPriority.CONTEXT_GUIDE)) {
                guide.offer(MascotContext.FIRST_VISIT_CHAIN_DATA).ifPresent(this::showHint);
            }
        });
        firstVisit.play();
    }

    private void onMascotClick() {
        Optional<MascotContext> c = MascotContext.forChain(lastChainState);
        if (c.isEmpty()) {
            return;
        }
        guide.ask(c.get()).ifPresent(bubble::toggle); // pedido do usuário: sempre responde (CHAIN_MISMATCH não tem dica: a UI de erro manda)
    }

    private void showHint(MascotHint h) {
        if (h.reaction() != null && mascot.accepts(MascotPriority.ATTENTION_NOTIFICATION)) {
            mascot.play(h.reaction());
        }
        bubble.show(h);
    }

    private void reactNotFound(MascotContext ctx) {
        if (mascot.isVisible() && mascot.accepts(MascotPriority.CONTEXT_GUIDE)) {
            guide.offer(ctx).ifPresent(this::showHint);
        }
    }

    private void reactInvalidSubmit() {
        if (mascot.isVisible() && mascot.accepts(MascotPriority.ATTENTION_NOTIFICATION)) {
            mascot.play(MascotState.ATTENTION); // só no submit, nunca enquanto digita; sem texto
        }
    }

    /** Uma presença por tela: com um estado vazio na tela, o mascote ocupa o estado vazio e sai do cabeçalho. */
    private void suppressHeaderMascot(boolean suppress) {
        emptySections = Math.max(0, emptySections + (suppress ? 1 : -1));
        boolean hide = emptySections > 0;
        mascot.setVisible(!hide);
        mascot.setManaged(!hide);
    }

    @Override
    public void onHide() {
        visible = false;
        epoch++; // resultados em voo de uma visita anterior são descartados; nada fica rodando
        mascot.stop();
        bubble.hide();
        sections.forEach(Section::hidden);
    }

    public void dispose() {
        onHide();
        sections.forEach(Section::disposeMascot);
        mascot.dispose();
        if (ownedIo != null) {
            ownedIo.shutdownNow();
        }
    }

    /** Testes: o mascote do cabeçalho. */
    MascotView headerMascot() {
        return mascot;
    }

    boolean idle() {
        return !visible;
    }

    private void refreshAll(boolean manual) {
        long now = clock.millis();
        if (manual && everShown && now - lastRefreshMs < REFRESH_DEBOUNCE_MS) {
            return; // não ignora a política do serviço, só evita pedidos repetidos da UI
        }
        lastRefreshMs = now;
        everShown = true;
        int mine = epoch;
        badge(healthState, "LOADING", ByxBadge.Tone.INFO);
        io.execute(() -> {
            Reply<ChainModules.Health> h = reader.health();
            Reply<ChainModules.Feesplit> f = reader.feesplit();
            Reply<ChainModules.PaymentParams> p = reader.paymentParams();
            ui.execute(() -> {
                if (mine != epoch) {
                    return;
                }
                showHealth(h);
                showEconomics(f, p);
            });
        });
        for (Section s : sections) {
            if (s != economics) {
                s.rerunIfAny();
            }
        }
    }

    /** Resumo da rede a partir do snapshot que a tela Network já mantém: nenhuma chamada nova. */
    private void renderHeader() {
        panel.model.ByxSnapshot s = network == null ? null : network.network();
        if (s == null) {
            return;
        }
        NetworkModel.State st = NetworkModel.state(s);
        hNode.set(st.text, false, st == NetworkModel.State.HEALTHY ? "pos" : st == NetworkModel.State.OFFLINE || st == NetworkModel.State.IDENTITY_MISMATCH || st == NetworkModel.State.ERROR ? "neg" : null);
        hChain.set(NetworkModel.value(s.chainId()), true, null);
        hHeight.set(NetworkModel.value(s.height()), true, null);
        hAge.set(NetworkModel.age(s, clock), true, st == NetworkModel.State.STALE ? "warn" : null);
        hDenom.set(NetworkModel.baseDenom(s) + " / " + NetworkModel.displayDenom(s), true, null);
        String cs = s.chainState();
        if (!java.util.Objects.equals(cs, lastChainState) || chainPlan == null && lastChainState == null) {
            boolean entering = lastChainState != null;
            lastChainState = cs;
            chainPlan = MascotUsage.forChain(cs).orElse(null);
            if (chainPlan == null) {
                mascot.setStaticState(MascotState.IDLE); // erro/mismatch: sem brincadeira; só o poster neutro
            } else if (activity.shown().isEmpty()) {
                MascotUsage.apply(mascot, chainPlan, entering);
            }
        }
    }

    private void showHealth(Reply<ChainModules.Health> h) {
        renderHeader();
        moduleBadges.getChildren().clear();
        if (!h.ok()) {
            ChainDataModel.State st = ChainDataModel.of(h, false);
            badge(healthState, st.view().text, st.view().tone);
            moduleBadges.getChildren().add(Kit.muted(st.note()));
            Fx.text(readStats, "");
            return;
        }
        badge(healthState, "OK", ByxBadge.Tone.POSITIVE);
        ByxBadge.Tone nodeTone = switch (h.data().node()) {
            case "LIVE" -> ByxBadge.Tone.POSITIVE;
            case "NOT_CONFIGURED", "CONNECTING" -> ByxBadge.Tone.NEUTRAL;
            case "SYNCING", "STALE" -> ByxBadge.Tone.WARNING;
            default -> ByxBadge.Tone.NEGATIVE;
        };
        moduleBadges.getChildren().add(ByxBadge.of("NODE · " + h.data().node().replace('_', ' '), nodeTone));
        for (ChainModules.ModuleStatus m : h.data().modules()) {
            if (m.module().equals("BANK")) {
                continue; // o saldo público aparece no inspetor de endereço; os cards são os quatro módulos
            }
            ByxBadge.Tone tone = switch (m.state()) {
                case AVAILABLE -> ByxBadge.Tone.POSITIVE;
                case DEGRADED -> ByxBadge.Tone.WARNING;
                case UNAVAILABLE -> ByxBadge.Tone.NEGATIVE;
                case NOT_EXPOSED, UNKNOWN -> ByxBadge.Tone.NEUTRAL;
            };
            String title = switch (m.module()) {
                case "LOJAS" -> "Lojas";
                case "PAYMENTS" -> "Payments";
                case "CERTIFICADOS" -> "Certificates";
                case "FEESPLIT" -> "Feesplit";
                default -> m.module();
            };
            VBox card = new VBox(4, Fx.label(title, "byx-section-title-sm"), ByxBadge.of(m.state().name().replace('_', ' '), tone));
            card.getStyleClass().add("byx-panel");
            card.setId("chain-card-" + m.module().toLowerCase());
            card.setAccessibleText(title + ": " + m.state().name().replace('_', ' '));
            if (m.module().equals("FEESPLIT")) {
                card.getChildren().add(Kit.dim("Documented policy · not read from chain"));
            }
            moduleBadges.getChildren().add(card);
        }
        ChainModules.Health d = h.data();
        Fx.text(readStats, "Reads: " + d.fetches() + " from the node · " + d.cacheHits() + " from cache · " + d.coalesced() + " shared · " + d.rateLimited() + " limited");
    }

    private void showEconomics(Reply<ChainModules.Feesplit> f, Reply<ChainModules.PaymentParams> p) {
        economics.body.getChildren().remove(1, economics.body.getChildren().size());
        if (f.ok()) {
            ChainModules.Feesplit x = f.data();
            economics.body.getChildren().add(ByxBadge.of("DOCUMENTED POLICY · NOT READ FROM CHAIN", ByxBadge.Tone.NEUTRAL));
            economics.body.getChildren().addAll(Kit.row("Distribution allocation (staking ecosystem)", ChainDataModel.percent(x.distributionBps()), true), Kit.row("Treasury allocation", ChainDataModel.percent(x.treasuryBps()), true),
                    Kit.row("Burn allocation", ChainDataModel.percent(x.burnBps()), true),
                    Kit.dim("Documented module design, not read from the chain. This is an allocation of collected fees, not a net share paid to validators."));
        } else {
            economics.body.getChildren().add(Kit.muted(ChainDataModel.failure(f.failure()).note()));
        }
        if (p.ok()) {
            economics.set(ChainDataModel.of(p, false));
            economics.body.getChildren().addAll(Kit.row("Payment request expiry · default", p.data().defaultExpiresInSeconds() + " s", true), Kit.row("Payment request expiry · minimum", p.data().minExpiresInSeconds() + " s", true),
                    Kit.row("Payment request expiry · maximum", p.data().maxExpiresInSeconds() + " s", true));
        } else {
            economics.set(ChainDataModel.of(p, false));
        }
    }

    // ---- linhas por tipo (somente texto; identificadores completos ficam no modelo e são o que se copia) -------------------------------------------------

    private static List<KvRow> merchantRows(ChainModules.Merchant m) {
        return List.of(row("Merchant ID", m.id(), true), row("Name", m.name(), false), row("Address", m.address().isEmpty() ? "—" : m.address(), false), copyable("Creator", m.creator()),
                copyable("Operator", m.operator()), row("KYC status", m.kycStatus().isEmpty() ? "—" : m.kycStatus(), false));
    }

    private static List<KvRow> paymentRows(ChainModules.Payment p) {
        List<KvRow> r = new ArrayList<>(List.of(row("Payment ID", p.id(), true), row("Store ID", p.storeId(), true), row("Amount", p.amountDisplay(), true), row("Amount (base units)", p.amountUbyx() + " ubyx", true),
                row("Status", p.status().name(), false), row("Created", ChainDataModel.time(p.createdAt()) + " UTC", true), row("Expires", ChainDataModel.time(p.expiresAt()) + " UTC", true)));
        if (p.paidAt() != null) {
            r.add(row("Paid", ChainDataModel.time(p.paidAt()) + " UTC", true));
        }
        if (p.payer() != null) {
            r.add(copyable("Payer", p.payer()));
        }
        if (!p.memo().isEmpty()) {
            r.add(row("Memo", p.memo(), false));
        }
        return r;
    }

    private static List<KvRow> certificateRows(ChainModules.Certificate c) {
        List<KvRow> r = new ArrayList<>(List.of(row("Certificate ID", c.id(), true), row("Merchant ID", c.merchantId(), true), row("Status", c.revoked() ? "REVOKED" : "ACTIVE", false)));
        if (c.revoked() && !c.revokedReason().isEmpty()) {
            r.add(row("Revocation reason", c.revokedReason(), false));
        }
        r.addAll(List.of(row("Category", c.category(), false), row("Brand / model", (c.brand() + " " + c.model()).trim(), false), copyable("Serial hash", c.serialHash()), row("Condition", c.condition(), false),
                copyable("Owner", c.owner()), copyable("Issuer", c.issuer()), row("Issued", ChainDataModel.time(c.createdAt()) + " UTC", true)));
        if (!c.notes().isEmpty()) {
            r.add(row("Notes", c.notes(), false));
        }
        return r;
    }

    private static List<KvRow> balanceRows(ChainModules.Balance b) {
        return List.of(copyable("Address", b.address()), row("Balance", b.amountDisplay(), true), row("Balance (base units)", b.amountUbyx() + " ubyx", true));
    }

    private static KvRow row(String k, String v, boolean mono) {
        return Kit.row(k, v, mono);
    }

    /** Valor longo: a tela mostra abreviado, a dica mostra o completo e o clique COPIA o completo. */
    private static KvRow copyable(String key, String full) {
        KvRow r = Kit.row(key, ChainDataModel.shortId(full), true);
        r.valueLabel().setTooltip(new Tooltip(full + "  (click to copy)"));
        r.valueLabel().setOnMouseClicked(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(full);
            Clipboard.getSystemClipboard().setContent(c);
        });
        r.setAccessibleText(key + ": " + full);
        return r;
    }

    // ---- blocos ----------------------------------------------------------------------------------------------------------------------

    private class Section {
        final VBox box;
        final VBox body = new VBox(0);
        final HBox badge = new HBox();
        final Label note = Kit.muted("");

        Section(String title, String id) {
            badge(badge, "READY", ByxBadge.Tone.NEUTRAL);
            body.getChildren().add(note);
            note.setManaged(false);
            note.setVisible(false);
            box = Kit.panel(null, Kit.titled(title, badge), body);
            box.setId(id);
        }

        void set(ChainDataModel.State s) {
            badge(badge, s.view().text, s.view().tone);
        }

        void rerunIfAny() { }

        void shown() { }

        void hidden() { }

        void disposeMascot() { }
    }

    /** Consulta por ID (um registro). */
    private final class Lookup<T> extends Section {
        private final ByxField field;
        private final Function<String, Reply<T>> fetch;
        private final Function<T, List<KvRow>> render;
        private String last;
        private boolean pending;
        private MascotContext notFoundContext;

        Lookup<T> notFound(MascotContext c) {
            this.notFoundContext = c;
            return this;
        }

        Lookup(String title, String id, String label, Function<String, Reply<T>> fetch, Function<T, List<KvRow>> render) {
            super(title, id);
            this.fetch = fetch;
            this.render = render;
            field = ByxField.text(label);
            field.input().setId(id + "-input");
            ByxButton go = new ByxButton("Look up", ByxButton.Variant.SECONDARY, motion).small();
            go.setId(id + "-go");
            go.setOnAction(e -> run(field.input().getText().trim()));
            field.input().setOnAction(e -> run(field.input().getText().trim()));
            HBox controls = new HBox(8, field, go);
            controls.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
            body.getChildren().add(0, controls);
        }

        @Override
        void rerunIfAny() {
            if (last != null) {
                run(last);
            }
        }

        void run(String value) {
            if (pending || value.isEmpty()) {
                return;
            }
            last = value;
            pending = true;
            set(new ChainDataModel.State(ChainDataModel.View.LOADING, ""));
            int mine = epoch;
            MascotActivity.Token token = activity.begin(MascotActivity.Kind.QUERY);
            io.execute(() -> {
                Reply<T> r = fetch.apply(value);
                ui.execute(() -> {
                    activity.end(token); // o resultado nunca espera a animação
                    pending = false;
                    if (mine != epoch && !visible) {
                        return;
                    }
                    show(r);
                });
            });
        }

        private void show(Reply<T> r) {
            ChainDataModel.State st = ChainDataModel.of(r, false);
            set(st);
            body.getChildren().removeIf(n -> n instanceof KvRow);
            Fx.text(note, st.note());
            note.setManaged(true);
            note.setVisible(true);
            field.setError(st.view() == ChainDataModel.View.INVALID_INPUT ? st.note() : null);
            if (st.view() == ChainDataModel.View.NOT_FOUND && notFoundContext != null) {
                reactNotFound(notFoundContext);
            } else if (st.view() == ChainDataModel.View.INVALID_INPUT) {
                reactInvalidSubmit();
            }
            if (r.ok() && st.view().showsData()) {
                body.getChildren().addAll(render.apply(r.data()));
            }
        }
    }

    /** Lista paginada (5 por página, "Load more", teto de linhas desenhadas). */
    private final class Pager<T> extends Section {
        private final ByxField field;
        private final BiConsumerFetch<T> fetch;
        private final Function<T, String> keyOf;
        private final Function<T, String> textOf;
        private final ByxButton more;
        private final VBox rows = new VBox(0);
        private String cursor;
        private String lastId;
        private boolean pending;
        private int shown;
        private MascotContext emptyContext;
        private MascotView emptyMascot;
        private MascotHintBubble emptyBubble;

        Pager<T> empty(MascotContext c) {
            this.emptyContext = c;
            return this;
        }

        private void enterEmpty() {
            if (emptyMascot != null || emptyContext == null) {
                return;
            }
            emptyMascot = new MascotView(motion, MascotSize.MEDIUM);
            emptyBubble = new MascotHintBubble(emptyMascot);
            MascotContext ctx = emptyContext;
            emptyMascot.setInteractive(true, () -> guide.ask(ctx).ifPresent(emptyBubble::toggle));
            rows.getChildren().setAll(MascotPlacement.place(null, emptyMascot, MascotAnchor.CENTER_EMPTY_STATE));
            emptyMascot.setState(MascotState.IDLE);
            suppressHeaderMascot(true); // uma presença por tela
        }

        private void leaveEmpty() {
            if (emptyMascot == null) {
                return;
            }
            emptyBubble.hide();
            emptyMascot.dispose();
            emptyMascot = null;
            emptyBubble = null;
            suppressHeaderMascot(false);
        }

        @Override
        void hidden() {
            if (emptyMascot != null) {
                emptyBubble.hide();
                emptyMascot.stop();
            }
        }

        @Override
        void shown() {
            if (emptyMascot != null) {
                emptyMascot.setState(MascotState.IDLE);
            }
        }

        @Override
        void disposeMascot() {
            leaveEmpty();
        }

        Pager(String title, String id, String idLabel, BiConsumerFetch<T> fetch, Function<T, String> keyOf, Function<T, String> textOf) {
            super(title, id);
            this.fetch = fetch;
            this.keyOf = keyOf;
            this.textOf = textOf;
            ByxButton load = new ByxButton(idLabel == null ? "Load" : "List", ByxButton.Variant.SECONDARY, motion).small();
            load.setId(id + "-go");
            more = new ByxButton("Load more", ByxButton.Variant.GHOST, motion).small();
            more.setId(id + "-more");
            more.setManaged(false);
            more.setVisible(false);
            HBox controls;
            if (idLabel != null) {
                field = ByxField.text(idLabel);
                field.input().setId(id + "-input");
                controls = new HBox(8, field, load);
                load.setOnAction(e -> start(field.input().getText().trim()));
                field.input().setOnAction(e -> start(field.input().getText().trim()));
            } else {
                field = null;
                controls = new HBox(8, load);
                load.setOnAction(e -> start(""));
            }
            controls.setAlignment(javafx.geometry.Pos.BOTTOM_LEFT);
            more.setOnAction(e -> fetchPage(lastId, cursor, true));
            body.getChildren().addAll(0, List.of(controls));
            body.getChildren().addAll(rows, more);
        }

        @Override
        void rerunIfAny() {
            if (lastId != null) {
                start(lastId);
            }
        }

        private void start(String id) {
            if (field != null && id.isEmpty()) {
                return;
            }
            cursor = null;
            lastId = id;
            fetchPage(id, null, false);
        }

        private void fetchPage(String id, String cur, boolean append) {
            if (pending) {
                return;
            }
            pending = true;
            set(new ChainDataModel.State(ChainDataModel.View.LOADING, ""));
            int mine = epoch;
            MascotActivity.Token token = activity.begin(MascotActivity.Kind.QUERY);
            io.execute(() -> {
                Reply<ChainModules.Page<T>> r = fetch.get(id, cur);
                ui.execute(() -> {
                    activity.end(token);
                    pending = false;
                    if (mine != epoch && !visible) {
                        return;
                    }
                    show(r, append);
                });
            });
        }

        private void show(Reply<ChainModules.Page<T>> r, boolean append) {
            boolean empty = r.ok() && r.data().items().isEmpty() && !append;
            ChainDataModel.State st = ChainDataModel.of(r, empty);
            set(st);
            Fx.text(note, st.note());
            note.setManaged(true);
            note.setVisible(true);
            if (field != null) {
                field.setError(st.view() == ChainDataModel.View.INVALID_INPUT ? st.note() : null);
            }
            if (!append) {
                leaveEmpty();
                rows.getChildren().clear();
                shown = 0;
            }
            if (empty) {
                enterEmpty();
            }
            if (r.ok()) {
                for (T item : r.data().items()) {
                    if (shown >= ROW_CAP) {
                        break;
                    }
                    rows.getChildren().add(Kit.row("#" + keyOf.apply(item), textOf.apply(item), true));
                    shown++;
                }
                cursor = shown >= ROW_CAP ? null : r.nextCursor();
            } else if (!append) {
                cursor = null;
            }
            Fx.shown(more, cursor != null);
        }
    }

    @FunctionalInterface
    private interface BiConsumerFetch<T> {
        Reply<ChainModules.Page<T>> get(String id, String cursor);
    }
}
