package byx.service;

import byx.service.auth.AuthIpc;
import byx.service.identity.IdentityPolicy;
import byx.service.identity.PeerKeys;
import byx.service.identity.PeerVerifier;
import byx.service.market.MarketFeed;
import byx.service.market.MarketSubscriber;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serviço local: escuta SOMENTE num socket Unix (sem porta TCP, sem IPv4/IPv6, sem Host/Origin porque navegadores não falam este
 * transporte), num diretório privado. Cada conexão passa por prova mútua de pareamento antes de qualquer operação; só a allowlist
 * de {@link Protocol#OPERATIONS} é atendida. Limites: quadro 8 KiB, 8 conexões, 1 pedido por vez por conexão, timeouts por fase.
 */
public final class ServiceInstance implements AutoCloseable {
    public static final int MAX_CONNECTIONS = 8;
    public static final long HANDSHAKE_TIMEOUT_MS = 3_000;
    public static final long READ_TIMEOUT_MS = 5_000;
    public static final long IDLE_TIMEOUT_MS = 30_000;
    static final int MAX_REQUESTS_PER_CONNECTION = 1_000;
    /** Assinaturas de mercado simultâneas (uma por conexão). Um reconectar local não acumula: a conexão antiga é cancelada ao cair. */
    public static final int MAX_SUBSCRIBERS = 4;
    static final int FAILURES_BEFORE_THROTTLE = 5;
    static final long THROTTLE_WINDOW_MS = 10_000;
    static final long THROTTLE_PENALTY_MS = 2_000;

    /** Limites por instância (os padrões são os de produção; testes usam valores curtos para provar o comportamento sem esperar). */
    public record Limits(int maxConnections, long handshakeMs, long readMs, long idleMs, long writeMs) {
        public Limits(int maxConnections, long handshakeMs, long readMs, long idleMs) {
            this(maxConnections, handshakeMs, readMs, idleMs, readMs);
        }

        public static Limits defaults() {
            return new Limits(MAX_CONNECTIONS, HANDSHAKE_TIMEOUT_MS, READ_TIMEOUT_MS, IDLE_TIMEOUT_MS, 5_000);
        }
    }

    private final Limits limits;
    private final RuntimeDir dir;
    private final byte[] secret;
    private final String instanceId;
    private final Operations operations;
    private final MarketFeed market;
    private final IdentityPolicy identity;
    private final AuthIpc authIpc;
    private final PeerKeys.Provider peerKeys;
    private final AtomicInteger subscribers = new AtomicInteger();
    private final JsonMapper mapper = Protocol.mapper();
    private final ServerSocketChannel server;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "byx-local-conn");
        t.setDaemon(true);
        return t;
    });
    private final Thread acceptor;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "byx-local-watchdog");
        t.setDaemon(true);
        return t;
    });
    private final Semaphore slots;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Deque<Long> failures = new ArrayDeque<>();
    private volatile long throttledUntil;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile boolean closed;

    private static final class Connection {
        final SocketChannel channel;
        final OutputStream out;
        final Object writeLock = new Object();
        volatile long deadlineNanos = Long.MAX_VALUE; // leitura
        volatile long writeDeadlineNanos = Long.MAX_VALUE;
        volatile MarketSubscriber subscriber;
        volatile boolean subscribed;

        Connection(SocketChannel c) {
            this.channel = c;
            this.out = Channels.newOutputStream(c);
        }

        void within(long ms) {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        }

        void noReadDeadline() {
            deadlineNanos = Long.MAX_VALUE;
        }

        /** Escrita serializada (resposta e eventos usam a mesma conexão) e com prazo: cliente que não lê tem a conexão fechada pelo watchdog. */
        void write(byte[] body, int max, long ms) throws IOException {
            synchronized (writeLock) {
                writeDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
                try {
                    Frames.write(out, body, max);
                } finally {
                    writeDeadlineNanos = Long.MAX_VALUE;
                }
            }
        }
    }

    private final byx.service.chain.ChainConnector chain;

    private final byx.service.tx.TxIpc txIpc;

    /** Leitura SOMENTE de status da chain para as transações (id + geração + vivo). Fica aqui porque só este arquivo e o ServiceMain podem tocar o conector. */
    public static byx.service.tx.TxPorts.TxChain txChain(byx.service.chain.ChainConnector connector) {
        return () -> {
            byx.service.chain.ChainStatus st = connector.status();
            return new byx.service.tx.TxPorts.TxChainView(st.chainId() == null ? "" : st.chainId(), new byx.service.tx.TxValues.ChainGeneration(Math.max(0, st.generation())),
                    st.state() == byx.service.chain.ChainState.LIVE);
        };
    }

    private ServiceInstance(RuntimeDir dir, byte[] secret, ServerSocketChannel server, Limits limits, MarketFeed market, IdentityPolicy identity, AuthIpc authIpc,
            PeerKeys.Provider peerKeys, byx.service.chain.ChainConnector chain, byx.service.tx.TxIpc txIpc) {
        this.chain = chain;
        this.txIpc = txIpc;
        this.authIpc = authIpc;
        this.peerKeys = peerKeys;
        this.market = market;
        this.identity = identity;
        this.limits = limits;
        this.slots = new Semaphore(limits.maxConnections());
        this.dir = dir;
        this.secret = secret;
        this.server = server;
        byte[] id = new byte[9];
        new SecureRandom().nextBytes(id);
        this.instanceId = Pairing.encode(id);
        this.operations = new Operations(instanceId, Instant.now(), market, identity.mode(), authIpc != null, chain, txIpc.service());
        this.acceptor = new Thread(this::acceptLoop, "byx-local-accept");
        this.acceptor.setDaemon(true);
    }

    /** Sobe o serviço: valida o diretório privado, gera segredo novo (invalida qualquer pareamento antigo) e escuta no socket. */
    public static ServiceInstance start(Path home) throws IOException {
        return start(home, Limits.defaults());
    }

    public static ServiceInstance start(Path home, Limits limits) throws IOException {
        return start(home, limits, null);
    }

    /** market = feed público ETHUSDT (null = capacidade marketData ausente; as operações de mercado ficam não suportadas). O serviço passa a ser dono do feed e o fecha. */
    public static ServiceInstance start(Path home, Limits limits, MarketFeed market) throws IOException {
        return start(home, limits, market, IdentityPolicy.development());
    }

    /**
     * identity = política de identidade do peer. Em PACKAGED_VERIFIED toda conexão precisa vir do app BYX-MVP verificado pelo kernel e
     * pela assinatura de código (o pairing.token sozinho NÃO basta); em DEVELOPMENT_UNVERIFIED nada é verificado e nenhuma capacidade
     * privada existe de qualquer forma.
     */
    public static ServiceInstance start(Path home, Limits limits, MarketFeed market, IdentityPolicy identity) throws IOException {
        return start(home, limits, market, identity, null, null);
    }

    /**
     * authIpc != null só na composição de QA da autoridade (nunca no produto: {@code ServiceMain} não monta autenticação). peerKeys = chave
     * do peer pelo kernel (produção: {@link PeerKeys#kernel()}; testes injetam).
     */
    public static ServiceInstance start(Path home, Limits limits, MarketFeed market, IdentityPolicy identity, AuthIpc authIpc, PeerKeys.Provider peerKeys) throws IOException {
        return start(home, limits, market, identity, authIpc, peerKeys, byx.service.chain.ChainConnector.notConfigured());
    }

    /** chain = conector da chain pública local (somente leitura). O serviço passa a ser dono dele e o fecha. Produção: {@code ChainConnector.notConfigured()}. */
    public static ServiceInstance start(Path home, Limits limits, MarketFeed market, IdentityPolicy identity, AuthIpc authIpc, PeerKeys.Provider peerKeys,
            byx.service.chain.ChainConnector chain) throws IOException {
        return start(home, limits, market, identity, authIpc, peerKeys, chain, byx.service.tx.TxProduction.disabled(null, txChain(chain)));
    }

    /**
     * txIpc = superfície de transação. Produção: {@link byx.service.tx.TxProduction#disabled} (gate falso, política TX_DISABLED, sem assinante nem transporte).
     * Só código de teste passa um TxIpc com portas habilitadas (injeção de construtor; nenhum caminho de ambiente/propriedade/arquivo).
     */
    public static ServiceInstance start(Path home, Limits limits, MarketFeed market, IdentityPolicy identity, AuthIpc authIpc, PeerKeys.Provider peerKeys,
            byx.service.chain.ChainConnector chain, byx.service.tx.TxIpc txIpc) throws IOException {
        RuntimeDir dir = RuntimeDir.prepare(home);
        dir.removeStaleSocket();
        byte[] secret = dir.writeFreshToken();
        ServerSocketChannel ch = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            ch.bind(UnixDomainSocketAddress.of(dir.socket()));
            dir.restrictSocket();
        } catch (IOException e) {
            ch.close();
            dir.cleanup();
            throw e;
        }
        ServiceInstance s = new ServiceInstance(dir, secret, ch, limits, market, identity, authIpc, peerKeys, chain, txIpc);
        s.acceptor.start();
        s.watchdog.scheduleWithFixedDelay(s::enforceDeadlines, 100, 100, TimeUnit.MILLISECONDS);
        Log.event("started", "protocol=" + Protocol.VERSION + " identity=" + identity.mode().wire);
        return s;
    }

    public RuntimeDir runtimeDir() {
        return dir;
    }

    public String instanceId() {
        return instanceId;
    }

    public int activeConnections() {
        return connections.size();
    }

    public void awaitStop() throws InterruptedException {
        stopped.await();
    }

    private void enforceDeadlines() {
        long now = System.nanoTime();
        for (Connection c : connections) {
            if (now - c.deadlineNanos > 0 || now - c.writeDeadlineNanos > 0) {
                closeQuietly(c.channel);
            }
        }
    }

    private void acceptLoop() {
        while (!closed) {
            SocketChannel ch;
            try {
                ch = server.accept();
            } catch (IOException e) {
                break;
            }
            if (System.currentTimeMillis() < throttledUntil || !slots.tryAcquire()) {
                Log.event("rejected", System.currentTimeMillis() < throttledUntil ? "throttled" : "busy");
                closeQuietly(ch);
                continue;
            }
            Connection c = new Connection(ch);
            connections.add(c);
            try {
                workers.execute(() -> {
                    try {
                        handle(c);
                    } finally {
                        stopSubscription(c);
                        connections.remove(c);
                        slots.release();
                        closeQuietly(ch);
                    }
                });
            } catch (RuntimeException e) {
                connections.remove(c);
                slots.release();
                closeQuietly(ch);
            }
        }
    }

    private void noteFailure() {
        synchronized (failures) {
            long now = System.currentTimeMillis();
            failures.addLast(now);
            while (!failures.isEmpty() && now - failures.peekFirst() > THROTTLE_WINDOW_MS) {
                failures.pollFirst();
            }
            if (failures.size() >= FAILURES_BEFORE_THROTTLE) {
                throttledUntil = now + THROTTLE_PENALTY_MS;
                failures.clear();
            }
        }
    }

    private <T> T parse(byte[] frame, Class<T> type) throws JsonProcessingException {
        T value = mapper.readValue(new String(frame, StandardCharsets.UTF_8), type);
        if (value == null) { // o literal JSON null não é um pedido
            throw new com.fasterxml.jackson.databind.JsonMappingException(null, "null body");
        }
        return value;
    }

    private void send(Connection c, ObjectNode n) throws IOException {
        c.write(mapper.writeValueAsBytes(n), Protocol.MAX_FRAME, limits.writeMs());
    }

    private ObjectNode error(String code) {
        ObjectNode n = mapper.createObjectNode();
        n.put("v", Protocol.VERSION);
        n.put("type", "error");
        n.put("code", code);
        return n;
    }

    private void handle(Connection c) {
        try {
            InputStream in = Channels.newInputStream(c.channel);
            c.within(limits.handshakeMs());
            // 0. identidade do app (só no modo empacotado): o kernel diz QUEM é o peer; antes de ler qualquer byte do cliente
            if (identity.strict()) {
                PeerVerifier.Verdict v = identity.verifier().verify(c.channel);
                if (!v.verified()) {
                    Log.event("peer_rejected", v.reason());
                    noteFailure();
                    return;
                }
            }
            // 1. hello → challenge (o servidor prova que conhece o segredo antes de o cliente provar o dele)
            Protocol.Hello hello;
            try {
                hello = parse(Frames.read(in, Protocol.MAX_FRAME), Protocol.Hello.class);
            } catch (JsonProcessingException e) {
                send(c, error("bad_request"));
                noteFailure();
                return;
            }
            if (hello.v() != Protocol.VERSION || !"hello".equals(hello.type()) || hello.clientNonce() == null || !Protocol.NONCE.matcher(hello.clientNonce()).matches()) {
                send(c, error(hello.v() != Protocol.VERSION ? "unsupported_version" : "bad_request"));
                noteFailure();
                return;
            }
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String serverNonce = Pairing.encode(nonce);
            ObjectNode challenge = mapper.createObjectNode();
            challenge.put("v", Protocol.VERSION);
            challenge.put("type", "challenge");
            challenge.put("serverNonce", serverNonce);
            challenge.put("serverProof", Pairing.serverProof(secret, hello.clientNonce(), serverNonce));
            send(c, challenge);
            // 2. auth: prova do cliente
            Protocol.Auth auth;
            try {
                auth = parse(Frames.read(in, Protocol.MAX_FRAME), Protocol.Auth.class);
            } catch (JsonProcessingException e) {
                send(c, error("bad_request"));
                noteFailure();
                return;
            }
            if (auth.v() != Protocol.VERSION || !"auth".equals(auth.type()) || auth.clientProof() == null || !Protocol.PROOF.matcher(auth.clientProof()).matches()
                    || !Pairing.equal(auth.clientProof(), Pairing.clientProof(secret, hello.clientNonce(), serverNonce))) {
                send(c, error("auth_failed"));
                noteFailure();
                Log.event("auth_failed", null);
                return;
            }
            ObjectNode ready = mapper.createObjectNode();
            ready.put("v", Protocol.VERSION);
            ready.put("type", "ready");
            ready.put("instanceId", instanceId);
            send(c, ready);
            // 3. pedidos: um por vez, só a allowlist
            for (int i = 0; i < MAX_REQUESTS_PER_CONNECTION; i++) {
                if (c.subscribed) {
                    c.noReadDeadline();
                } else {
                    c.within(limits.idleMs());
                }
                byte[] frame = Frames.read(in, Protocol.MAX_FRAME);
                c.within(limits.readMs());
                Protocol.Request req;
                com.fasterxml.jackson.databind.JsonNode tree;
                try {
                    tree = mapper.readTree(new String(frame, StandardCharsets.UTF_8));
                    if (tree == null || !tree.isObject() || !tree.path("op").isTextual()) {
                        throw new com.fasterxml.jackson.databind.JsonMappingException(null, "not a request");
                    }
                    // auth.* tem DTOs tipados por operação (AuthIpc valida o conjunto fechado de campos); o resto segue o Request estrito
                    req = AuthIpc.handles(tree.path("op").asText()) || ChainReadIpc.handles(tree.path("op").asText()) || byx.service.tx.TxIpc.handles(tree.path("op").asText()) ? new Protocol.Request(tree.path("v").asInt(-1), tree.path("id").asText(null), tree.path("op").asText())
                            : mapper.treeToValue(tree, Protocol.Request.class);
                } catch (JsonProcessingException e) {
                    send(c, error("bad_request"));
                    return;
                }
                if (req.v() != Protocol.VERSION || req.id() == null || !Protocol.REQUEST_ID.matcher(req.id()).matches() || req.op() == null) {
                    send(c, error("bad_request"));
                    return;
                }
                ObjectNode resp = mapper.createObjectNode();
                resp.put("v", Protocol.VERSION);
                resp.put("id", req.id());
                if (AuthIpc.handles(req.op()) && authIpc != null) {
                    AuthIpc.Reply r = authIpc.handle(peerKeys == null ? PeerKeys.NONE : peerKeys.keyOf(c.channel), req.op(), tree);
                    AuthIpc.write(r, resp);
                } else if (byx.service.tx.TxIpc.handles(req.op())) {
                    // transações: superfície tipada e fechada; desligada em produção (TX_DISABLED antes de qualquer validação)
                    txIpc.handle(peerKeys == null ? PeerKeys.NONE : peerKeys.keyOf(c.channel), req.op(), tree, resp);
                } else if (ChainReadIpc.handles(req.op())) {
                    resp.put("ok", true);
                    ChainReadIpc.run(chain, req.op(), tree, resp.putObject("result"));
                } else if (!operations.supports(req.op())) {
                    resp.put("ok", false);
                    resp.putObject("error").put("code", "unsupported_operation");
                    Log.event("denied", "unsupported_operation");
                } else if ("market.subscribe".equals(req.op())) {
                    if (c.subscribed || subscribers.get() >= MAX_SUBSCRIBERS) {
                        resp.put("ok", false);
                        resp.putObject("error").put("code", c.subscribed ? "already_subscribed" : "too_many_subscribers");
                    } else {
                        resp.put("ok", true);
                        resp.putObject("result").put("streaming", true);
                        send(c, resp); // a resposta precede o primeiro evento
                        startSubscription(c);
                        c.noReadDeadline(); // assinante não precisa mandar nada; o serviço envia batimento a cada 2 s
                        continue;
                    }
                } else if ("market.unsubscribe".equals(req.op())) {
                    stopSubscription(c);
                    resp.put("ok", true);
                    resp.putObject("result").put("streaming", false);
                } else {
                    resp.put("ok", true);
                    operations.run(req.op(), resp.putObject("result"));
                }
                send(c, resp);
            }
        } catch (Frames.FrameException e) {
            Log.event("closed", e.code);
        } catch (IOException e) {
            // cliente saiu ou o watchdog fechou por timeout
        } catch (RuntimeException e) {
            Log.event("closed", "internal_error");
        }
    }

    private void startSubscription(Connection c) {
        subscribers.incrementAndGet();
        market.acquire();
        MarketSubscriber sub = new MarketSubscriber(market, body -> c.write(body, Protocol.MAX_MARKET_FRAME, limits.writeMs()), mapper);
        c.subscriber = sub;
        c.subscribed = true;
        Thread t = new Thread(sub, "byx-market-sub");
        t.setDaemon(true);
        t.start();
    }

    /** Idempotente: cancela o assinante e devolve a referência do feed (que para sozinho após o tempo de graça sem assinantes). */
    private void stopSubscription(Connection c) {
        MarketSubscriber sub;
        synchronized (c) {
            sub = c.subscriber;
            c.subscriber = null;
            c.subscribed = false;
        }
        if (sub != null) {
            sub.cancel();
            subscribers.decrementAndGet();
            market.release();
        }
    }

    public int activeSubscribers() {
        return subscribers.get();
    }

    private static void closeQuietly(java.nio.channels.Channel c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // nada a fazer
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(server);
        for (Connection c : connections) {
            closeQuietly(c.channel);
        }
        watchdog.shutdownNow();
        workers.shutdownNow();
        chain.close();
        if (market != null) {
            market.close();
        }
        dir.cleanup(); // remove socket e segredo: um cliente com o segredo antigo não conecta a lugar nenhum
        Log.event("stopped", null);
        stopped.countDown();
    }
}
