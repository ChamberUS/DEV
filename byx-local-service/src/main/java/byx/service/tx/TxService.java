package byx.service.tx;

import byx.service.tx.TxIntent.BankSendIntent;
import byx.service.tx.TxPorts.ChainTxTransport;
import byx.service.tx.TxPorts.Decision;
import byx.service.tx.TxPorts.RawAccount;
import byx.service.tx.TxPorts.RawBroadcast;
import byx.service.tx.TxPorts.RawSimulation;
import byx.service.tx.TxPorts.RawTxStatus;
import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.SimulationRequest;
import byx.service.tx.TxPorts.Stage;
import byx.service.tx.TxPorts.TxAuthorizationPolicy;
import byx.service.tx.TxPorts.TxChain;
import byx.service.tx.TxPorts.TxChainView;
import byx.service.tx.TxPorts.TxKey;
import byx.service.tx.TxPorts.TxKeys;
import byx.service.tx.TxPorts.TxSession;
import byx.service.tx.TxPorts.TxSessions;
import byx.service.tx.TxPorts.TxSignRequest;
import byx.service.tx.TxPorts.TxSigner;
import byx.service.tx.TxPorts.TxSignerException;
import byx.service.tx.TxPorts.TxTransportException;
import byx.service.tx.TxValues.AccountNumber;
import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.ClientOperationId;
import byx.service.tx.TxValues.GasAmount;
import byx.service.tx.TxValues.Sequence;
import byx.service.tx.TxValues.TxQuoteId;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orquestrador do pipeline de transação, DONO de política, estado, cotação, taxa, assinante e transmissão. O painel só PEDE (preparar, ler cotação,
 * confirmar, consultar, cancelar); nunca decide "isto é permitido" e nunca recebe chave, assinante nem bytes assinados.
 * <p>
 * Ordem: gate mestre + política -> autorização -> chave do remetente -> snapshot de conta/sequence -> simulação -> gas/taxa (falha fechada) ->
 * saldo -> cotação vinculada -> [confirmação: reautoriza, revalida prazo/chain/sequence/orçamento/política] -> assinar -> gravar hash ->
 * transmitir -> acompanhar. Em PRODUÇÃO nesta fase tudo termina em TX_DISABLED antes de qualquer outra coisa.
 */
public final class TxService {
    /** Operações não terminais por sessão. */
    static final int MAX_LIVE_OPERATIONS_PER_SESSION = 32;
    /** Quanto tempo uma operação terminal fica consultável em memória. */
    static final long RETENTION_MS = 60 * 60 * 1000L;

    private static final class Operation {
        final String key;
        final String sessionId;
        final long peerKey;
        final String accountId;
        final ClientOperationId id;
        TxState state = TxState.NEW;
        BankSendIntent intent;
        FeeMode feeMode;
        TxQuote quote;
        String txHash;
        TxError error;
        long updatedAtMs;
        final List<TxState> history = new ArrayList<>(List.of(TxState.NEW));

        Operation(String key, TxSession s, ClientOperationId id) {
            this.key = key;
            this.sessionId = s.sessionId();
            this.peerKey = s.peerKey();
            this.accountId = s.accountId();
            this.id = id;
        }
    }

    /** Visão imutável de uma operação para o IPC/UI. */
    public record Status(TxState state, TxQuote quote, String txHash, TxError error) { }

    private final TxGate gate;
    private final TxPolicy policy;
    private final TxSessions sessions;
    private final TxChain chain;
    private final TxKeys keys;
    private final ChainTxTransport transport;
    private final TxSigner signer;
    private final TxAuthorizationPolicy authz;
    private final TxAudit audit;
    private final TxJournal journal;
    private final Clock clock;
    private final Map<String, Operation> operations = new ConcurrentHashMap<>();

    public TxService(TxGate gate, TxPolicy policy, TxSessions sessions, TxChain chain, TxKeys keys, ChainTxTransport transport, TxSigner signer,
            TxAuthorizationPolicy authz, TxAudit audit, TxJournal journal, Clock clock) {
        this.gate = gate;
        this.policy = policy;
        this.sessions = sessions;
        this.chain = chain;
        this.keys = keys;
        this.transport = transport;
        this.signer = signer;
        this.authz = authz;
        this.audit = audit;
        this.journal = journal;
        this.clock = clock;
    }

    /** Composição de PRODUÇÃO desta fase: tudo desligado/ausente. {@code sessions} e {@code chain} são as reais (somente validação/leitura). */
    public static TxService disabled(TxSessions sessions, TxChain chain, Clock clock) {
        return new TxService(TxGate.PRODUCTION, TxPolicy.DISABLED, sessions, chain, TxKeys.UNAVAILABLE, ChainTxTransport.ABSENT, TxSigner.UNAVAILABLE,
                TxAuthorizationPolicy.DENY_ALL, TxAudit.LOG, TxJournal.memory(), clock);
    }

    /** Invólucro para a composição de produção (evita expor um construtor público com portas habilitáveis ao ServiceMain). */
    public record Disabled(TxService service) {
        public static Disabled of(TxSessions sessions, TxChain chain, Clock clock) {
            return new Disabled(TxService.disabled(sessions, chain, clock));
        }
    }

    public TxGate gate() {
        return gate;
    }

    public String policyName() {
        return policy.name();
    }

    /** Rótulo fixo do transporte para diagnóstico: ABSENT em produção. */
    public String transportName() {
        return transport == ChainTxTransport.ABSENT ? "ABSENT" : "PRESENT";
    }

    /** O pipeline só roda com gate ligado E política habilitada; qualquer dúvida nega. */
    public boolean enabled() {
        return gate != null && gate.mutationsAllowed() && policy != null && policy.enabled();
    }

    public boolean signerAvailable() {
        return signer != null && signer.available();
    }

    // ---- preparar --------------------------------------------------------------------------------------------------------------------

    public TxQuote prepareBankSend(long peerKey, String token, ClientOperationId opId, BankSendIntent intent, FeeMode mode) {
        requireEnabled();
        TxSession s = session(peerKey, token);
        authorize(s, intent, Stage.PREPARE, null);
        String key = s.sessionId() + "/" + opId.value();
        prune();
        if (countLive(s.sessionId()) >= MAX_LIVE_OPERATIONS_PER_SESSION && !operations.containsKey(key)) {
            throw new TxException(TxError.TOO_MANY_OPERATIONS);
        }
        Operation op = operations.computeIfAbsent(key, k -> new Operation(k, s, opId));
        synchronized (op) {
            if (op.peerKey != s.peerKey()) {
                throw new TxException(TxError.QUOTE_NOT_FOUND);
            }
            if (op.state.executionStarted()) {
                throw new TxException(TxError.QUOTE_MISMATCH); // depois de confirmar, a operação não é recotada: nova operação, novo id
            }
            // idempotência: o mesmo pedido (mesma intenção e modo) com cotação viva devolve a MESMA cotação, sem nova simulação
            if (op.quote != null && op.state == TxState.AWAITING_CONFIRMATION && op.intent.digest().equals(intent.digest()) && op.feeMode == mode
                    && !op.quote.expiredAt(clock.millis())) {
                return op.quote;
            }
            if (op.state == TxState.AWAITING_CONFIRMATION) {
                move(op, TxState.SIMULATING); // nova cotação (modo/memo/valor mudou ou a anterior venceu): a anterior deixa de valer
                op.quote = null;
            } else if (op.state == TxState.EXPIRED || op.state == TxState.FAILED) {
                move(op, TxState.SIMULATING);
                op.quote = null;
            }
            op.intent = intent;
            op.feeMode = mode;
            audit.event(TxAudit.Type.TX_PREPARE, prefix(opId.value()), "-", null);
            try {
                if (op.state == TxState.NEW) {
                    move(op, TxState.VALIDATED);
                    move(op, TxState.SIMULATING);
                }
                TxQuote q = buildQuote(s, op, intent, mode);
                op.quote = q;
                move(op, TxState.QUOTED);
                move(op, TxState.AWAITING_CONFIRMATION);
                audit.event(TxAudit.Type.TX_QUOTED, prefix(opId.value()), prefix(q.id().value()), null);
                return q;
            } catch (TxException e) {
                fail(op, e.error());
                throw e;
            } catch (RuntimeException e) {
                fail(op, TxError.INTERNAL);
                throw new TxException(TxError.INTERNAL);
            }
        }
    }

    private TxQuote buildQuote(TxSession s, Operation op, BankSendIntent intent, FeeMode mode) {
        TxKey key = keys.find(s.accountId(), intent.sender()).orElseThrow(() -> new TxException(TxError.SIGNER_UNAVAILABLE));
        if (!signerAvailable()) {
            throw new TxException(TxError.SIGNER_UNAVAILABLE);
        }
        TxChainView view = chain.current();
        if (view == null || !view.live() || view.chainId() == null) {
            throw new TxException(TxError.CHAIN_CHANGED);
        }
        Account acc = account(key.address());
        if (!acc.chainId().equals(view.chainId())) {
            throw new TxException(TxError.CHAIN_CHANGED);
        }
        GasAmount simulated = simulate(key.address(), intent, acc, view.chainId());
        FeeEngine.Fee fee = FeeEngine.compute(policy, mode, simulated);
        BigInteger need = intent.amount().value().add(fee.estimatedFee().value());
        if (acc.spendable().compareTo(need) < 0) {
            throw new TxException(TxError.INSUFFICIENT_FUNDS);
        }
        TxChainView after = chain.current();
        if (after == null || !after.generation().equals(view.generation()) || !view.chainId().equals(after.chainId())) {
            throw new TxException(TxError.CHAIN_CHANGED);
        }
        long now = clock.millis();
        return new TxQuote(TxQuoteId.random(), intent.digest(), mode, view.chainId(), view.generation(), key.address(), intent.recipient(), intent.amount(),
                intent.memo().digest(), acc.number(), acc.sequence(), fee.simulatedGas(), fee.adjustedGas(), fee.gasLimit(), fee.gasPrice(), fee.estimatedFee(),
                fee.maximumFee(), policy.name(), policy.version(), now, now + policy.quoteTtl().toMillis());
    }

    private record Account(AccountNumber number, Sequence sequence, String chainId, BigInteger spendable) { }

    /** Parse ESTRITO de dados não confiáveis do transporte: qualquer anomalia falha fechado. */
    private Account account(BankAddress address) {
        RawAccount raw;
        try {
            raw = transport.getAccount(address.value());
        } catch (TxTransportException e) {
            throw new TxException(TxError.ACCOUNT_INVALID);
        } catch (RuntimeException e) {
            throw new TxException(TxError.ACCOUNT_INVALID);
        }
        if (raw == null || raw.address() == null || !raw.address().equals(address.value()) || raw.chainId() == null || raw.chainId().isEmpty() || raw.chainId().length() > 64) {
            throw new TxException(TxError.ACCOUNT_INVALID);
        }
        return new Account(AccountNumber.parse(raw.accountNumber()), Sequence.parse(raw.sequence()), raw.chainId(),
                TxValues.decimal(raw.spendableUbyx(), TxError.ACCOUNT_INVALID));
    }

    private GasAmount simulate(BankAddress sender, BankSendIntent intent, Account acc, String chainId) {
        RawSimulation raw;
        try {
            raw = transport.simulate(new SimulationRequest(sender, intent.recipient(), intent.amount(), intent.memo(), acc.number(), acc.sequence(), chainId));
        } catch (TxTransportException e) {
            throw new TxException(TxError.SIMULATION_FAILED);
        } catch (RuntimeException e) {
            throw new TxException(TxError.SIMULATION_FAILED);
        }
        if (raw == null) {
            throw new TxException(TxError.SIMULATION_FAILED);
        }
        return GasAmount.parse(raw.gasUsed()); // zero, negativo, enorme, estouro, ausente: GAS_ESTIMATE_INVALID
    }

    // ---- ler / cancelar / consultar --------------------------------------------------------------------------------------------------

    public Status quote(long peerKey, String token, ClientOperationId opId, TxQuoteId quoteId) {
        TxSession s = enabledSession(peerKey, token);
        Operation op = owned(s, opId);
        synchronized (op) {
            if (op.quote == null || !op.quote.id().equals(quoteId)) {
                throw new TxException(TxError.QUOTE_NOT_FOUND);
            }
            expireIfNeeded(op);
            return snapshot(op);
        }
    }

    public Status cancel(long peerKey, String token, ClientOperationId opId) {
        TxSession s = enabledSession(peerKey, token);
        Operation op = owned(s, opId);
        synchronized (op) {
            if (op.state.executionStarted()) {
                throw new TxException(TxError.QUOTE_MISMATCH); // depois de confirmar não se cancela por aqui
            }
            if (op.state == TxState.AWAITING_CONFIRMATION || op.state == TxState.QUOTED) {
                move(op, TxState.EXPIRED);
                op.error = null;
                audit.event(TxAudit.Type.TX_CANCEL, prefix(opId.value()), op.quote == null ? "-" : prefix(op.quote.id().value()), null);
            }
            return snapshot(op);
        }
    }

    public Status status(long peerKey, String token, ClientOperationId opId) {
        TxSession s = enabledSession(peerKey, token);
        Operation op = owned(s, opId);
        synchronized (op) {
            expireIfNeeded(op);
            if ((op.state == TxState.SUBMITTED || op.state == TxState.UNKNOWN_OUTCOME) && op.txHash != null) {
                follow(op);
            }
            return snapshot(op);
        }
    }

    /** Sessão encerrada/revogada: tudo o que não foi executado fica inutilizável (a sessão também é revalidada a cada uso). */
    public void sessionEnded(String sessionId) {
        for (Operation op : operations.values()) {
            if (op.sessionId.equals(sessionId)) {
                synchronized (op) {
                    if (!op.state.executionStarted() && op.state != TxState.EXPIRED && op.state != TxState.FAILED && op.state.canGoTo(TxState.EXPIRED)) {
                        move(op, TxState.EXPIRED);
                    }
                }
            }
        }
    }

    // ---- confirmar -------------------------------------------------------------------------------------------------------------------

    public Status confirm(long peerKey, String token, ClientOperationId opId, TxQuoteId quoteId) {
        requireEnabled();
        TxSession s = session(peerKey, token);
        Operation op = owned(s, opId);
        synchronized (op) {
            if (op.quote == null || !op.quote.id().equals(quoteId)) {
                throw new TxException(TxError.QUOTE_NOT_FOUND);
            }
            if (op.state.executionStarted()) {
                return snapshot(op); // clique duplo / repetição: NENHUMA segunda execução
            }
            if (op.state == TxState.EXPIRED) {
                throw new TxException(TxError.QUOTE_EXPIRED);
            }
            if (op.state != TxState.AWAITING_CONFIRMATION) {
                throw new TxException(TxError.QUOTE_MISMATCH);
            }
            TxQuote q = op.quote;
            audit.event(TxAudit.Type.TX_CONFIRM, prefix(opId.value()), prefix(q.id().value()), null);
            try {
                revalidate(s, op, q);
            } catch (TxException e) {
                if (e.error() != TxError.MFA_REQUIRED && e.error() != TxError.ELEVATION_REQUIRED) {
                    invalidateQuote(op, e.error()); // exigir MFA/elevação não estraga a cotação: o usuário cumpre e confirma de novo
                }
                throw e;
            }
            move(op, TxState.CONFIRMED);
            return execute(s, op, q);
        }
    }

    private void revalidate(TxSession s, Operation op, TxQuote q) {
        authorize(s, op.intent, Stage.CONFIRM, op);
        if (q.expiredAt(clock.millis())) {
            throw new TxException(TxError.QUOTE_EXPIRED);
        }
        if (!q.policyName().equals(policy.name()) || !q.policyVersion().equals(policy.version())) {
            throw new TxException(TxError.QUOTE_MISMATCH);
        }
        if (!q.intentDigest().equals(op.intent.digest()) || !q.memoDigest().equals(op.intent.memo().digest())) {
            throw new TxException(TxError.QUOTE_MISMATCH);
        }
        TxChainView view = chain.current();
        if (view == null || !view.live() || !q.chainGeneration().equals(view.generation()) || !q.chainId().equals(view.chainId())) {
            throw new TxException(TxError.CHAIN_CHANGED);
        }
        if (q.fee().value().compareTo(policy.maximumFeeBudget().value()) > 0 || q.gasLimit().value() > policy.globalGasLimit().value()) {
            throw new TxException(TxError.MAX_FEE_EXCEEDED);
        }
        Account now = account(q.sender());
        if (now.sequence().value() != q.sequence().value() || now.number().value() != q.accountNumber().value()) {
            throw new TxException(TxError.STALE_SEQUENCE); // nunca re-assina com outra sequence: pede nova cotação e nova confirmação
        }
        if (!now.chainId().equals(q.chainId())) {
            throw new TxException(TxError.CHAIN_CHANGED);
        }
        if (now.spendable().compareTo(q.totalDebit()) < 0) {
            throw new TxException(TxError.INSUFFICIENT_FUNDS);
        }
        if (!signerAvailable()) {
            throw new TxException(TxError.SIGNER_UNAVAILABLE);
        }
    }

    private Status execute(TxSession s, Operation op, TxQuote q) {
        TxKey key = keys.find(op.accountId, op.intent.sender()).orElse(null);
        if (key == null || !key.address().equals(q.sender())) {
            fail(op, TxError.SIGNER_UNAVAILABLE);
            return snapshot(op);
        }
        move(op, TxState.SIGNING);
        audit.event(TxAudit.Type.TX_SIGN_REQUEST, prefix(op.id.value()), prefix(q.id().value()), null);
        SignedTx signed;
        try {
            signed = signer.sign(new TxSignRequest(key, q)); // a requisição é montada AQUI, só com valores da cotação guardada
            if (signed == null || signed.bytes() == null || signed.txHash() == null || !signed.txHash().matches("[0-9A-F]{64}")) {
                throw new TxSignerException();
            }
        } catch (TxSignerException | RuntimeException e) {
            fail(op, TxError.SIGNING_FAILED);
            return snapshot(op);
        }
        op.txHash = signed.txHash(); // o hash é gravado ANTES de transmitir
        move(op, TxState.BROADCASTING);
        audit.event(TxAudit.Type.TX_BROADCAST_REQUEST, prefix(op.id.value()), prefix(q.id().value()), null);
        RawBroadcast raw;
        try {
            raw = transport.broadcast(signed);
        } catch (TxTransportException e) {
            if (e.kind() == TxTransportException.Kind.OUTCOME_UNKNOWN) {
                unknown(op);
            } else {
                fail(op, TxError.BROADCAST_FAILED);
            }
            return snapshot(op);
        } catch (RuntimeException e) {
            unknown(op); // exceção inesperada depois de enviar: pode ter sido aplicada
            return snapshot(op);
        }
        if (raw == null || raw.code() == null) {
            unknown(op); // resposta ilegível: não sabemos
            return snapshot(op);
        }
        if (raw.txHash() != null && !raw.txHash().equals(signed.txHash())) {
            unknown(op); // o nó diz outro hash: nunca tratar como sucesso nem reenviar
            return snapshot(op);
        }
        if (raw.code().equals("0")) {
            move(op, TxState.SUBMITTED);
            audit.event(TxAudit.Type.TX_SUBMITTED, prefix(op.id.value()), prefix(q.id().value()), null);
        } else {
            fail(op, mapCode(raw.code()));
        }
        return snapshot(op);
    }

    /** Acompanha uma transação já transmitida (ou de resultado desconhecido) pelo HASH; nunca reenvia. */
    private void follow(Operation op) {
        RawTxStatus st;
        try {
            st = transport.getTxByHash(op.txHash);
        } catch (TxTransportException | RuntimeException e) {
            return; // sem informação: o estado não muda
        }
        if (st == null || !st.found()) {
            return;
        }
        if (st.txHash() != null && !st.txHash().equals(op.txHash)) {
            return;
        }
        if ("0".equals(st.code())) {
            move(op, TxState.CONFIRMED_ON_CHAIN);
        } else if (st.code() != null) {
            fail(op, mapCode(st.code()));
        }
    }

    /** Códigos de resultado do nó -> taxonomia fechada (desconhecido = BROADCAST_FAILED). */
    static TxError mapCode(String code) {
        return switch (code) {
            case "5" -> TxError.INSUFFICIENT_FUNDS; // sdkerrors.ErrInsufficientFunds
            case "13" -> TxError.FEE_TOO_LOW; // ErrInsufficientFee
            case "11" -> TxError.OUT_OF_GAS; // ErrOutOfGas
            case "32" -> TxError.STALE_SEQUENCE; // ErrWrongSequence
            default -> TxError.BROADCAST_FAILED;
        };
    }

    // ---- infraestrutura --------------------------------------------------------------------------------------------------------------

    private void requireEnabled() {
        if (!enabled()) {
            throw new TxException(TxError.TX_DISABLED);
        }
    }

    private TxSession enabledSession(long peerKey, String token) {
        requireEnabled();
        return session(peerKey, token);
    }

    private TxSession session(long peerKey, String token) {
        return sessions.resolve(peerKey, token).orElseThrow(() -> new TxException(TxError.UNAUTHORIZED));
    }

    private void authorize(TxSession s, TxIntent intent, Stage stage, Operation op) {
        Decision d = authz.decide(s, intent, stage);
        switch (d) {
            case ALLOW -> { }
            case REQUIRE_MFA -> throw new TxException(TxError.MFA_REQUIRED);
            case REQUIRE_ELEVATION -> throw new TxException(TxError.ELEVATION_REQUIRED);
            default -> {
                audit.event(TxAudit.Type.TX_DENIED, op == null ? "-" : prefix(op.id.value()), "-", TxError.UNAUTHORIZED.name());
                throw new TxException(TxError.UNAUTHORIZED);
            }
        }
    }

    /** A operação é da SESSÃO e do PEER que a criaram; qualquer outro recebe o mesmo "não encontrada". */
    private Operation owned(TxSession s, ClientOperationId opId) {
        Operation op = operations.get(s.sessionId() + "/" + opId.value());
        if (op == null || op.peerKey != s.peerKey() || !op.accountId.equals(s.accountId())) {
            throw new TxException(TxError.QUOTE_NOT_FOUND);
        }
        return op;
    }

    /** Operações terminais antigas saem da memória (o diário durável, quando existir, é quem guarda histórico). */
    private void prune() {
        long limit = clock.millis() - RETENTION_MS;
        operations.values().removeIf(op -> op.updatedAtMs < limit && (op.state == TxState.EXPIRED || op.state == TxState.FAILED || op.state == TxState.CONFIRMED_ON_CHAIN));
    }

    private int countLive(String sessionId) {
        int n = 0;
        for (Operation op : operations.values()) {
            if (op.sessionId.equals(sessionId) && op.state != TxState.EXPIRED && op.state != TxState.FAILED && op.state != TxState.CONFIRMED_ON_CHAIN) {
                n++;
            }
        }
        return n;
    }

    private void expireIfNeeded(Operation op) {
        if (op.state == TxState.AWAITING_CONFIRMATION && op.quote != null && op.quote.expiredAt(clock.millis())) {
            move(op, TxState.EXPIRED);
        }
    }

    private void invalidateQuote(Operation op, TxError why) {
        op.error = why;
        if (op.state.canGoTo(TxState.EXPIRED)) {
            move(op, TxState.EXPIRED);
        }
    }

    private void unknown(Operation op) {
        op.error = TxError.UNKNOWN_OUTCOME;
        move(op, TxState.UNKNOWN_OUTCOME);
        audit.event(TxAudit.Type.TX_UNKNOWN_OUTCOME, prefix(op.id.value()), op.quote == null ? "-" : prefix(op.quote.id().value()), TxError.UNKNOWN_OUTCOME.name());
    }

    private void fail(Operation op, TxError error) {
        op.error = error;
        if (op.state.canGoTo(TxState.FAILED)) {
            move(op, TxState.FAILED);
        }
        audit.event(TxAudit.Type.TX_FAILED, prefix(op.id.value()), op.quote == null ? "-" : prefix(op.quote.id().value()), error.name());
    }

    private void move(Operation op, TxState to) {
        op.state = op.state.require(to);
        op.history.add(to);
        op.updatedAtMs = clock.millis();
        journal.save(new TxJournal.Entry(op.id.value(), op.sessionId, to, op.quote == null ? null : op.quote.id().value(),
                op.intent == null ? null : op.intent.digest().value(), op.txHash, op.error == null ? null : op.error.name(), op.updatedAtMs));
    }

    private Status snapshot(Operation op) {
        return new Status(op.state, op.quote, op.txHash, op.error);
    }

    private static String prefix(String hex) {
        return hex.substring(0, 8);
    }

    /** Histórico de estados de uma operação (teste/diagnóstico). */
    List<TxState> historyOf(String sessionId, String operationId) {
        Operation op = operations.get(sessionId + "/" + operationId);
        return op == null ? List.of() : List.copyOf(op.history);
    }
}
