package byx.service.chain;

import byx.service.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Fonte ÚNICA das leituras públicas de módulo dentro do serviço (o painel só consome snapshots tipados; nenhuma tela faz polling próprio). Reutiliza o transporte do conector, executa fora da
 * thread do IPC, com: validação de rede (só LIVE busca; NETWORK_MISMATCH domina e purga o cache), coalescência de requisições idênticas em voo (mesma operação + mesmos parâmetros normalizados),
 * cache LIMITADO por operação (TTL abaixo) sensível a geração, época de reconexão e chain id, dado antigo só como STALE explícito, concorrência máxima, taxa máxima e métricas locais.
 * TTLs: ver {@link ReadOp} e docs (estado mutável de pagamento 5 s; listas 15 s; registros quase imutáveis 30 s; params 5 min; saldo 5 s; não encontrado 3 s).
 */
final class ChainReader {
    interface Context {
        ChainStatus status();

        Optional<DenomModel> denom();

        int epoch();

        /** Chain id VERIFICADO (confere com o esperado) da geração atual; vazio se nunca verificado ou se houve divergência. */
        Optional<String> verifiedChainId();
    }

    record Limits(int maxConcurrent, int queue, int maxCache, int maxFetchesPerWindow, long windowMs, long maxStaleMs, long notFoundTtlMs, long waitMs) {
        static Limits production() {
            return new Limits(2, 8, 128, 40, 10_000, 600_000, 3_000, 4_000);
        }
    }

    private static final class Entry {
        ModuleParser.Parsed parsed; // null = não encontrado (cache negativo curto)
        String nextCursor;
        long at;
        int epoch;
        int generation;
        String chainId;
    }

    private final ChainConfig config;
    private final ChainTransport transport;
    private final Context ctx;
    private final LongSupplier clock;
    private final Limits limits;
    private final ThreadPoolExecutor pool;
    private final Map<String, Entry> cache;
    private final ConcurrentHashMap<String, CompletableFuture<ReadResult>> inflight = new ConcurrentHashMap<>();
    private final Map<ReadOp.Module, ModuleHealth> health = new EnumMap<>(ReadOp.Module.class);
    final AtomicLong fetches = new AtomicLong();
    final AtomicLong cacheHits = new AtomicLong();
    final AtomicLong coalesced = new AtomicLong();
    final AtomicLong rateLimited = new AtomicLong();
    private long windowStart;
    private int windowCount;

    ChainReader(ChainConfig config, ChainTransport transport, Context ctx, LongSupplier clock, Limits limits) {
        this.config = config;
        this.transport = transport;
        this.ctx = ctx;
        this.clock = clock;
        this.limits = limits;
        this.pool = new ThreadPoolExecutor(limits.maxConcurrent(), limits.maxConcurrent(), 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(limits.queue()), r -> {
            Thread t = new Thread(r, "byx-chain-read");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> e) {
                return size() > limits.maxCache();
            }
        };
        for (ReadOp.Module m : ReadOp.Module.values()) {
            health.put(m, new ModuleHealth());
        }
    }

    ReadResult read(ReadRequest rq) {
        ChainStatus st = ctx.status(); // dispara a atualização de status se vencida (única fonte do estado do nó)
        long now = clock.getAsLong();
        if (st.state() == ChainState.NETWORK_MISMATCH) {
            invalidate(); // nenhum dado da rede anterior é apresentado para a rede errada
            return ReadResult.failure(st.reason() == ChainReason.DENOM_MISMATCH ? ReadFailure.DENOM_MISMATCH : ReadFailure.NETWORK_MISMATCH, st.generation());
        }
        Optional<DenomModel> denom = ctx.denom();
        if (st.state() != ChainState.LIVE || denom.isEmpty()) {
            ReadResult stale = stale(rq, st, now);
            return stale != null ? stale : ReadResult.failure(gateFailure(st), st.generation());
        }
        String key = rq.key();
        synchronized (cache) {
            Entry e = cache.get(key);
            if (e != null && valid(e, st) && e.epoch == ctx.epoch() && now - e.at < (e.parsed == null ? limits.notFoundTtlMs() : rq.op().ttlMs())) {
                cacheHits.incrementAndGet();
                return e.parsed == null ? ReadResult.failure(ReadFailure.NOT_FOUND, st.generation()) : from(e, ReadResult.Freshness.CACHED, now, st);
            }
        }
        CompletableFuture<ReadResult> f = inflight.get(key);
        if (f != null) {
            coalesced.incrementAndGet();
        } else {
            if (!allowFetch(now)) {
                rateLimited.incrementAndGet();
                return ReadResult.failure(ReadFailure.RATE_LIMITED, st.generation());
            }
            CompletableFuture<ReadResult> mine = new CompletableFuture<>();
            CompletableFuture<ReadResult> prev = inflight.putIfAbsent(key, mine);
            if (prev != null) {
                coalesced.incrementAndGet();
                f = prev;
            } else {
                f = mine;
                try {
                    DenomModel d = denom.get();
                    pool.execute(() -> {
                        try {
                            mine.complete(fetch(rq, key, st, d));
                        } catch (RuntimeException ex) {
                            mine.complete(ReadResult.failure(ReadFailure.MODULE_UNAVAILABLE, st.generation()));
                        } finally {
                            inflight.remove(key, mine);
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    inflight.remove(key, mine);
                    rateLimited.incrementAndGet();
                    mine.complete(ReadResult.failure(ReadFailure.RATE_LIMITED, st.generation()));
                }
            }
        }
        try {
            return f.get(limits.waitMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ReadResult.failure(ReadFailure.TIMEOUT, st.generation());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReadResult.failure(ReadFailure.UNREACHABLE, st.generation());
        } catch (java.util.concurrent.ExecutionException e) {
            return ReadResult.failure(ReadFailure.MODULE_UNAVAILABLE, st.generation());
        }
    }

    private ReadResult fetch(ReadRequest rq, String key, ChainStatus st, DenomModel denom) {
        fetches.incrementAndGet();
        ModuleHealth h = health.get(rq.op().module());
        long t0 = clock.getAsLong();
        try {
            byte[] body = transport.get(config.rest(), rq.pathAndQuery(denom.base()), rq.op().maxBytes());
            ModuleParser.Parsed p = ModuleParser.parse(rq, body, denom);
            long now = clock.getAsLong();
            Entry e = new Entry();
            e.parsed = p;
            e.nextCursor = p.nextKey() == null ? null : ReadRequest.cursorFor(rq.op(), p.nextKey());
            e.at = now;
            e.epoch = ctx.epoch();
            e.generation = st.generation();
            e.chainId = ctx.verifiedChainId().orElse(null);
            synchronized (cache) {
                cache.put(key, e);
            }
            h.record(true, null, now - t0, now);
            return from(e, ReadResult.Freshness.LIVE, now, st);
        } catch (ChainException ex) {
            long now = clock.getAsLong();
            ReadFailure f = map(ex, rq.op());
            h.record(f == ReadFailure.NOT_FOUND || f == ReadFailure.INVALID_REQUEST, f, now - t0, now);
            if (f == ReadFailure.NOT_FOUND) {
                Entry e = new Entry();
                e.at = now;
                e.epoch = ctx.epoch();
                e.generation = st.generation();
                e.chainId = ctx.verifiedChainId().orElse(null);
                synchronized (cache) {
                    cache.put(key, e);
                }
            } else if (f == ReadFailure.MALFORMED_RESPONSE || f == ReadFailure.RESPONSE_TOO_LARGE) {
                Log.event("chain_read_rejected", "module=" + rq.op().module() + " reason=" + ex.reason); // só razão fixa: nunca corpo, parâmetro ou URL
            }
            if (f == ReadFailure.UNREACHABLE || f == ReadFailure.TIMEOUT || f == ReadFailure.MODULE_UNAVAILABLE) {
                ReadResult stale = stale(rq, st, now);
                if (stale != null) {
                    return stale;
                }
            }
            return ReadResult.failure(f, st.generation());
        }
    }

    // ---- regras -----------------------------------------------------------------------------------------------------------------------

    private boolean valid(Entry e, ChainStatus st) {
        return e.generation == st.generation() && ctx.verifiedChainId().map(c -> c.equals(e.chainId)).orElse(false);
    }

    /** Dado ANTERIOR da mesma geração e do mesmo chain id, dentro do teto, marcado STALE; só dado (nunca "não encontrado"). */
    private ReadResult stale(ReadRequest rq, ChainStatus st, long now) {
        synchronized (cache) {
            Entry e = cache.get(rq.key());
            if (e != null && e.parsed != null && valid(e, st) && now - e.at <= limits.maxStaleMs()) {
                return from(e, ReadResult.Freshness.STALE, now, st);
            }
        }
        return null;
    }

    private static ReadResult from(Entry e, ReadResult.Freshness f, long now, ChainStatus st) {
        return new ReadResult(true, null, f, e.parsed.data(), e.nextCursor, Math.max(0, now - e.at), st.generation(), e.chainId);
    }

    private static ReadFailure gateFailure(ChainStatus st) {
        return switch (st.state()) {
            case NOT_CONFIGURED -> ReadFailure.NOT_CONFIGURED;
            case SYNCING, STALE -> ReadFailure.STALE_CHAIN;
            case ERROR -> ReadFailure.MODULE_UNAVAILABLE;
            case LIVE, CONNECTING, OFFLINE, NETWORK_MISMATCH -> ReadFailure.UNREACHABLE;
        };
    }

    private synchronized boolean allowFetch(long now) {
        if (now - windowStart >= limits.windowMs() || now < windowStart) {
            windowStart = now;
            windowCount = 0;
        }
        return ++windowCount <= limits.maxFetchesPerWindow();
    }

    private static ReadFailure map(ChainException e, ReadOp op) {
        return switch (e.reason) {
            case UNREACHABLE -> ReadFailure.UNREACHABLE;
            case TIMEOUT -> ReadFailure.TIMEOUT;
            case RESPONSE_TOO_LARGE -> ReadFailure.RESPONSE_TOO_LARGE;
            case DENOM_MISMATCH -> ReadFailure.DENOM_MISMATCH;
            case HTTP_STATUS -> switch (e.httpStatus) {
                case 404 -> isNotFound(e.errorBody, op) ? ReadFailure.NOT_FOUND : ReadFailure.UNSUPPORTED_QUERY;
                case 400 -> ReadFailure.INVALID_REQUEST;
                case 405, 501 -> ReadFailure.UNSUPPORTED_QUERY;
                case 429 -> ReadFailure.RATE_LIMITED;
                default -> ReadFailure.MODULE_UNAVAILABLE;
            };
            default -> ReadFailure.MALFORMED_RESPONSE;
        };
    }

    /** 404 só é "não encontrado" se o corpo for o erro gRPC NotFound (code 5) deste registro; qualquer outro 404 é rota ausente/alterada (UNSUPPORTED_QUERY). */
    private static boolean isNotFound(byte[] body, ReadOp op) {
        if (body == null || op.notFoundNoun() == null) {
            return false;
        }
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return n != null && n.isObject() && n.path("code").isInt() && n.path("code").asInt() == 5 && n.path("message").isTextual()
                    && n.path("message").asText().contains(op.notFoundNoun() + " not found");
        } catch (java.io.IOException e) {
            return false;
        }
    }

    void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    java.util.List<ModuleHealth.Snapshot> health() {
        long now = clock.getAsLong();
        java.util.List<ModuleHealth.Snapshot> r = new java.util.ArrayList<>();
        for (ReadOp.Module m : ReadOp.Module.values()) {
            r.add(health.get(m).snapshot(m, now));
        }
        return r;
    }

    void close() {
        pool.shutdownNow();
    }
}
