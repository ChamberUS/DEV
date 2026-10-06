package byx.service;

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
    static final int FAILURES_BEFORE_THROTTLE = 5;
    static final long THROTTLE_WINDOW_MS = 10_000;
    static final long THROTTLE_PENALTY_MS = 2_000;

    /** Limites por instância (os padrões são os de produção; testes usam valores curtos para provar o comportamento sem esperar). */
    public record Limits(int maxConnections, long handshakeMs, long readMs, long idleMs) {
        public static Limits defaults() {
            return new Limits(MAX_CONNECTIONS, HANDSHAKE_TIMEOUT_MS, READ_TIMEOUT_MS, IDLE_TIMEOUT_MS);
        }
    }

    private final Limits limits;
    private final RuntimeDir dir;
    private final byte[] secret;
    private final String instanceId;
    private final Operations operations;
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
        volatile long deadlineNanos = Long.MAX_VALUE;

        Connection(SocketChannel c) {
            this.channel = c;
        }

        void within(long ms) {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        }
    }

    private ServiceInstance(RuntimeDir dir, byte[] secret, ServerSocketChannel server, Limits limits) {
        this.limits = limits;
        this.slots = new Semaphore(limits.maxConnections());
        this.dir = dir;
        this.secret = secret;
        this.server = server;
        byte[] id = new byte[9];
        new SecureRandom().nextBytes(id);
        this.instanceId = Pairing.encode(id);
        this.operations = new Operations(instanceId, Instant.now());
        this.acceptor = new Thread(this::acceptLoop, "byx-local-accept");
        this.acceptor.setDaemon(true);
    }

    /** Sobe o serviço: valida o diretório privado, gera segredo novo (invalida qualquer pareamento antigo) e escuta no socket. */
    public static ServiceInstance start(Path home) throws IOException {
        return start(home, Limits.defaults());
    }

    public static ServiceInstance start(Path home, Limits limits) throws IOException {
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
        ServiceInstance s = new ServiceInstance(dir, secret, ch, limits);
        s.acceptor.start();
        s.watchdog.scheduleWithFixedDelay(s::enforceDeadlines, 100, 100, TimeUnit.MILLISECONDS);
        Log.event("started", "protocol=" + Protocol.VERSION);
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
            if (now - c.deadlineNanos > 0) {
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

    private void send(OutputStream out, ObjectNode n) throws IOException {
        Frames.write(out, mapper.writeValueAsBytes(n));
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
            OutputStream out = Channels.newOutputStream(c.channel);
            // 1. hello → challenge (o servidor prova que conhece o segredo antes de o cliente provar o dele)
            c.within(limits.handshakeMs());
            Protocol.Hello hello;
            try {
                hello = parse(Frames.read(in, Protocol.MAX_FRAME), Protocol.Hello.class);
            } catch (JsonProcessingException e) {
                send(out, error("bad_request"));
                noteFailure();
                return;
            }
            if (hello.v() != Protocol.VERSION || !"hello".equals(hello.type()) || hello.clientNonce() == null || !Protocol.NONCE.matcher(hello.clientNonce()).matches()) {
                send(out, error(hello.v() != Protocol.VERSION ? "unsupported_version" : "bad_request"));
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
            send(out, challenge);
            // 2. auth: prova do cliente
            Protocol.Auth auth;
            try {
                auth = parse(Frames.read(in, Protocol.MAX_FRAME), Protocol.Auth.class);
            } catch (JsonProcessingException e) {
                send(out, error("bad_request"));
                noteFailure();
                return;
            }
            if (auth.v() != Protocol.VERSION || !"auth".equals(auth.type()) || auth.clientProof() == null || !Protocol.PROOF.matcher(auth.clientProof()).matches()
                    || !Pairing.equal(auth.clientProof(), Pairing.clientProof(secret, hello.clientNonce(), serverNonce))) {
                send(out, error("auth_failed"));
                noteFailure();
                Log.event("auth_failed", null);
                return;
            }
            ObjectNode ready = mapper.createObjectNode();
            ready.put("v", Protocol.VERSION);
            ready.put("type", "ready");
            ready.put("instanceId", instanceId);
            send(out, ready);
            // 3. pedidos: um por vez, só a allowlist
            for (int i = 0; i < MAX_REQUESTS_PER_CONNECTION; i++) {
                c.within(limits.idleMs());
                byte[] frame = Frames.read(in, Protocol.MAX_FRAME);
                c.within(limits.readMs());
                Protocol.Request req;
                try {
                    req = parse(frame, Protocol.Request.class);
                } catch (JsonProcessingException e) {
                    send(out, error("bad_request"));
                    return;
                }
                if (req.v() != Protocol.VERSION || req.id() == null || !Protocol.REQUEST_ID.matcher(req.id()).matches() || req.op() == null) {
                    send(out, error("bad_request"));
                    return;
                }
                ObjectNode resp = mapper.createObjectNode();
                resp.put("v", Protocol.VERSION);
                resp.put("id", req.id());
                if (!Protocol.OPERATIONS.contains(req.op())) {
                    resp.put("ok", false);
                    resp.putObject("error").put("code", "unsupported_operation");
                    Log.event("denied", "unsupported_operation");
                } else {
                    resp.put("ok", true);
                    operations.run(req.op(), resp.putObject("result"));
                }
                send(out, resp);
            }
        } catch (Frames.FrameException e) {
            Log.event("closed", e.code);
        } catch (IOException e) {
            // cliente saiu ou o watchdog fechou por timeout
        } catch (RuntimeException e) {
            Log.event("closed", "internal_error");
        }
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
        dir.cleanup(); // remove socket e segredo: um cliente com o segredo antigo não conecta a lugar nenhum
        Log.event("stopped", null);
        stopped.countDown();
    }
}
