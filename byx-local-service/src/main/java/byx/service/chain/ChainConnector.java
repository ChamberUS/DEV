package byx.service.chain;

import byx.service.Log;
import java.math.BigInteger;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Conector SOMENTE LEITURA da chain pública local. Dono único do endpoint, do parsing e das regras: o painel só recebe {@link ChainStatus} e DTOs mínimos pelo IPC tipado.
 * Produção: não configurado (nenhuma thread, nenhuma rede). Quando configurado, a atualização é conduzida por demanda (uma por vez, intervalo mínimo), fora da thread do IPC.
 *
 * Regras (falha fechada): só LIVE é saudável. Rede/chain id divergente → NETWORK_MISMATCH (sem expor altura como saudável); nó alcançável mas catching_up → SYNCING; bloco velho → STALE;
 * altura que REGRIDE dentro da mesma geração → STALE (razão HEIGHT_REGRESSION), nunca LIVE até superar o máximo já visto; resposta inválida → ERROR; inalcançável/prazo → OFFLINE.
 * Uma NOVA geração (reinício, mudança de configuração) zera o máximo de altura.
 */
public final class ChainConnector implements AutoCloseable {
    /** Tempos (ms). Produção segue os padrões; testes passam valores curtos. */
    public record Timing(long minIntervalMs, long staleAfterMs, long futureToleranceMs, long metadataRefreshMs) {
        public static Timing production() {
            return new Timing(5_000, 60_000, 30_000, 600_000);
        }
    }

    private final Optional<ChainConfig> config;
    private final ChainTransport transport;
    private final Timing timing;
    private final LongSupplier clock;
    private final ExecutorService worker;
    private final AtomicBoolean inflight = new AtomicBoolean();
    /** Teto de leituras de módulo em voo (conexões do IPC presas): 3 de 8, deixando sempre slots para auth, mercado e status. */
    static final int MAX_CONCURRENT_READS = 3;
    private final java.util.concurrent.Semaphore readSlots = new java.util.concurrent.Semaphore(MAX_CONCURRENT_READS);
    private final ChainReader reader;

    private volatile ChainStatus status;
    private volatile ChainOkMetadata metadata; // verificado na geração atual
    private volatile BigInteger supply;
    private volatile String verifiedChain; // chain id observado == esperado nesta geração (base de validade do cache de módulos)
    private volatile boolean closed;
    private volatile int generation = 1;
    private volatile int epoch = 1; // muda a cada (re)conexão e nova geração: dado de época anterior nunca é servido como fresco
    private long maxHeight = -1;
    private long lastStartMs;
    private long metadataAtMs;
    private boolean everReachable;

    record ChainOkMetadata(String base, String display, int exponent) { }

    public ChainConnector(Optional<ChainConfig> config, ChainTransport transport, Timing timing, LongSupplier clock) {
        this(config, transport, timing, clock, ChainReader.Limits.production());
    }

    ChainConnector(Optional<ChainConfig> config, ChainTransport transport, Timing timing, LongSupplier clock, ChainReader.Limits readLimits) {
        this.config = config;
        this.transport = transport;
        this.timing = timing;
        this.clock = clock;
        this.status = config.isPresent() ? connecting() : ChainStatus.notConfigured(clock.getAsLong());
        this.reader = config.isPresent() ? new ChainReader(config.get(), transport, new ChainReader.Context() {
            @Override
            public ChainStatus status() {
                return ChainConnector.this.status();
            }

            @Override
            public Optional<DenomModel> denom() {
                ChainOkMetadata m = metadata;
                return m == null ? Optional.empty() : Optional.of(new DenomModel(m.base(), m.display(), m.exponent()));
            }

            @Override
            public int epoch() {
                return epoch;
            }

            @Override
            public Optional<String> verifiedChainId() {
                return Optional.ofNullable(verifiedChain);
            }
        }, clock, readLimits) : null;
        this.worker = config.isPresent() ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "byx-chain");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    /** Explicitamente DESABILITADO (sem configuração, sem transporte, sem thread): perfil PRODUCTION_DISABLED. */
    public static ChainConnector notConfigured() {
        return new ChainConnector(Optional.empty(), null, Timing.production(), System::currentTimeMillis);
    }

    /** Conector do artefato: usa o perfil tipado {@link ChainProfile#ACTIVE} e o transporte de loopback (sem nenhuma entrada externa). */
    public static ChainConnector production() {
        Optional<ChainConfig> config = ChainConfig.production();
        return new ChainConnector(config, config.isPresent() ? LoopbackHttp.production() : null, Timing.production(), System::currentTimeMillis);
    }

    private ChainStatus connecting() {
        return new ChainStatus(ChainState.CONNECTING, true, false, null, null, null, null, false, ChainReason.NONE, generation, clock.getAsLong(), null);
    }

    public boolean configured() {
        return config.isPresent();
    }

    /** Status atual (nunca bloqueia: dispara uma atualização única em segundo plano se o intervalo mínimo passou). */
    public ChainStatus status() {
        if (config.isEmpty()) {
            return status;
        }
        long now = clock.getAsLong();
        if (!closed && now - lastStartMs >= timing.minIntervalMs() && inflight.compareAndSet(false, true)) {
            lastStartMs = now;
            try {
                worker.execute(() -> {
                    try {
                        cycle();
                    } finally {
                        inflight.set(false);
                    }
                });
            } catch (RejectedExecutionException e) {
                inflight.set(false);
            }
        }
        return status;
    }

    /** Denom verificado nesta geração; vazio se não configurado, não verificado ou divergente. */
    public Optional<DenomModel> denomMetadata() {
        ChainOkMetadata m = metadata;
        ChainStatus s = status;
        if (m == null || s.state() == ChainState.NETWORK_MISMATCH || s.state() == ChainState.OFFLINE || s.state() == ChainState.ERROR) {
            return Optional.empty();
        }
        return Optional.of(new DenomModel(m.base(), m.display(), m.exponent()));
    }

    /** Suprimento total (unidades-base) lido na geração atual; vazio se indisponível. Dado público, sem conversão em ponto flutuante. */
    public Optional<BigInteger> supply() {
        ChainStatus s = status;
        return s.state() == ChainState.LIVE || s.state() == ChainState.SYNCING || s.state() == ChainState.STALE ? Optional.ofNullable(supply) : Optional.empty();
    }

    /** Nova geração (reinício/mudança de configuração): zera máximo de altura e verificação de denom; o estado volta a CONNECTING. */
    public synchronized void newGeneration() {
        generation++;
        epoch++;
        if (reader != null) {
            reader.invalidate();
        }
        maxHeight = -1;
        metadata = null;
        verifiedChain = null;
        supply = null;
        metadataAtMs = 0;
        everReachable = false;
        lastStartMs = 0;
        status = config.isPresent() ? connecting() : ChainStatus.notConfigured(clock.getAsLong());
    }

    /** Um ciclo SÍNCRONO (a thread de trabalho e os testes chamam este). */
    synchronized void cycle() {
        if (config.isEmpty() || closed) {
            return;
        }
        ChainConfig c = config.get();
        long now = clock.getAsLong();
        try {
            ChainParser.NodeStatus n = ChainParser.nodeStatus(transport.get(c.rpc(), ChainRoute.RPC_STATUS.path(c.denom().base()), ChainRoute.RPC_STATUS.maxBytes()));
            boolean wasReachable = everReachable;
            everReachable = true;
            if (!wasReachable) {
                epoch++;
                Log.event("chain_connect", "gen=" + generation);
            }
            if (!c.expectedChainId().equals(n.chainId())) {
                verifiedChain = null;
                mismatch(n.chainId(), ChainReason.NETWORK_MISMATCH, now);
                return;
            }
            verifiedChain = n.chainId();
            if (metadata == null || now - metadataAtMs >= timing.metadataRefreshMs()) {
                ChainParser.Metadata m = ChainParser.denomMetadata(transport.get(c.rest(), ChainRoute.REST_DENOM_METADATA.path(c.denom().base()), ChainRoute.REST_DENOM_METADATA.maxBytes()));
                if (!m.base().equals(c.denom().base()) || !m.display().equals(c.denom().display()) || m.displayExponent() != c.denom().exponent()) {
                    metadata = null;
                    mismatch(n.chainId(), ChainReason.DENOM_MISMATCH, now);
                    return;
                }
                metadata = new ChainOkMetadata(m.base(), m.display(), m.displayExponent());
                metadataAtMs = now;
            }
            BigInteger s = ChainParser.supply(transport.get(c.rest(), ChainRoute.REST_SUPPLY.path(c.denom().base()), ChainRoute.REST_SUPPLY.maxBytes()), c.denom().base());
            supply = s;
            if (n.blockTimeMs() > now + timing.futureToleranceMs()) {
                publish(ChainState.ERROR, true, n, ChainReason.BLOCK_TIME_IN_FUTURE, now);
                return;
            }
            if (n.height() < maxHeight) {
                publish(ChainState.STALE, true, n, ChainReason.HEIGHT_REGRESSION, now); // não regride em silêncio e não finge LIVE
                return;
            }
            maxHeight = Math.max(maxHeight, n.height());
            if (n.catchingUp()) {
                publish(ChainState.SYNCING, true, n, ChainReason.CATCHING_UP, now);
            } else if (now - n.blockTimeMs() > timing.staleAfterMs()) {
                publish(ChainState.STALE, true, n, ChainReason.NODE_STALE, now);
            } else {
                publish(ChainState.LIVE, true, n, ChainReason.NONE, now);
            }
        } catch (ChainException e) {
            failed(e.reason, now);
        }
    }

    private void publish(ChainState state, boolean match, ChainParser.NodeStatus n, ChainReason reason, long now) {
        status = new ChainStatus(state, true, true, n.chainId(), n.height(), n.catchingUp(), n.blockTimeMs(), match, reason, generation, now, n.blockHash());
    }

    private void mismatch(String observedChain, ChainReason reason, long now) {
        supply = null;
        if (reader != null) {
            reader.invalidate(); // nada da rede anterior sobrevive a uma rede errada
        }
        Log.event("chain_network_mismatch", "gen=" + generation + " reason=" + reason);
        // a altura NÃO é exposta: a rede não é a esperada, então nada dela é apresentado como saudável
        status = new ChainStatus(ChainState.NETWORK_MISMATCH, true, true, observedChain, null, null, null, false, reason, generation, now, null);
    }

    private void failed(ChainReason reason, long now) {
        boolean unreachable = reason == ChainReason.UNREACHABLE || reason == ChainReason.TIMEOUT || reason == ChainReason.HTTP_STATUS || reason == ChainReason.REDIRECT_REFUSED; // resposta grande/inválida NÃO é "inalcançável": é ERROR (o nó respondeu algo fora do contrato)
        if (unreachable) {
            if (everReachable) {
                Log.event("chain_disconnect", "gen=" + generation + " reason=" + reason);
            }
            everReachable = false;
            supply = null;
            status = new ChainStatus(ChainState.OFFLINE, true, false, null, null, null, null, false, reason, generation, now, null);
        } else {
            Log.event("chain_parse_rejected", "gen=" + generation + " reason=" + reason);
            supply = null;
            status = new ChainStatus(ChainState.ERROR, true, true, null, null, null, null, false, reason, generation, now, null);
        }
    }

    /**
     * Leitura PÚBLICA de módulo (tipada, somente leitura). Não configurado: NOT_CONFIGURED sem nenhuma rede nem thread. Bloqueia só a thread do IPC chamadora (limitada por prazo), nunca a UI.
     */
    public ReadResult read(ReadRequest request) {
        if (reader == null) {
            return ReadResult.failure(ReadFailure.NOT_CONFIGURED, 0);
        }
        // no máximo MAX_CONCURRENT_READS conexões do IPC podem estar presas em leituras de módulo: o resto recebe RATE_LIMITED NA HORA (nunca espera). Assim a leitura pública da chain jamais
        // consome os slots de conexão do serviço de que a autenticação e o mercado precisam (limite total de conexões: 8)
        if (!readSlots.tryAcquire()) {
            return ReadResult.failure(ReadFailure.RATE_LIMITED, generation);
        }
        try {
            return reader.read(request);
        } finally {
            readSlots.release();
        }
    }

    public java.util.List<ModuleHealth.Snapshot> moduleHealth() {
        return reader == null ? java.util.List.of() : reader.health();
    }

    /** Contadores locais (sem parâmetros): fetches reais, hits de cache, requisições coalescidas e recusadas por limite. */
    public long[] readCounters() {
        return reader == null ? new long[4] : new long[] {reader.fetches.get(), reader.cacheHits.get(), reader.coalesced.get(), reader.rateLimited.get()};
    }

    ChainReader readerForTest() {
        return reader;
    }

    @Override
    public void close() {
        closed = true;
        if (reader != null) {
            reader.close();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
    }
}
