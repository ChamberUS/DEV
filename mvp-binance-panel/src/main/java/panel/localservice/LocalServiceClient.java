package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cliente mínimo do serviço local (protocolo 1: socket Unix privado + prova mútua de pareamento por HMAC-SHA256). Cada sondagem abre
 * uma conexão nova, lê o segredo do arquivo privado de novo (um reinício do serviço invalida o pareamento antigo sem estado velho
 * no cliente), confere a prova do SERVIDOR antes de mandar a nossa, e só pede as três operações da allowlist.
 *
 * Falha fechada: arquivo de pareamento fora da política, servidor que não prova o segredo, quadro fora do limite, resposta fora do
 * contrato ou prazo estourado viram um estado de falha, nunca dado. Retentativas são poucas e só para "indisponível". Rodar fora da
 * thread FX. O que isto NÃO garante: ver SECURITY_FOUNDATION.md do serviço (processos da mesma conta).
 */
public final class LocalServiceClient {
    public static final int MAX_FRAME = 8 * 1024;
    public static final int SUPPORTED_PROTOCOL = 1;
    static final long TOTAL_TIMEOUT_MS = 3_000;
    static final long[] RETRY_DELAYS_MS = {150, 500}; // até 3 tentativas, só para UNAVAILABLE

    private static final Pattern INSTANCE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z.+_-]{1,32}");
    private static final Set<String> OPERATIONS = Set.of("health", "version", "capabilities");
    /** Operações PÚBLICAS e somente leitura da chain, sem argumentos: o painel nunca envia host, porta, caminho nem consulta. */
    static final Set<String> CHAIN_OPERATIONS = Set.of("byx.status", "byx.denomMetadata", "byx.supply");
    /** Leituras PÚBLICAS de módulo (lista fechada; cada uma só aceita os argumentos tipados validados por {@link ModuleReadClient}). */
    static final Set<String> MODULE_OPERATIONS = Set.of("byx.lojas.getMerchant", "byx.lojas.listMerchants", "byx.payments.getPayment", "byx.payments.listByStore", "byx.payments.params",
            "byx.certificados.getCertificate", "byx.certificados.listByMerchant", "byx.bank.balance", "byx.feesplit.params", "byx.moduleHealth");
    private static final java.util.concurrent.atomic.AtomicInteger MODULE_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    private static final JsonMapper JSON = new JsonMapper();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-service-timeout");
        t.setDaemon(true);
        return t;
    });

    private final Path run;
    private final panel.identity.IdentityPolicy identity;

    /** home = diretório do serviço (contém run/). */
    public LocalServiceClient(Path home) {
        this(home, DetectedPolicy.POLICY);
    }

    /** Modo de identidade derivado da assinatura do PRÓPRIO processo (uma vez); nunca de configuração. */
    private static final class DetectedPolicy {
        static final panel.identity.IdentityPolicy POLICY = panel.identity.IdentityPolicy.detect(panel.identity.AppIdentity.APP_ID, panel.identity.AppIdentity.SERVICE_ID);
    }

    /** identity: política de identidade (testes injetam a sua). Empacotado, o painel só fala com o serviço de identidade verificada. */
    public LocalServiceClient(Path home, panel.identity.IdentityPolicy identity) {
        this.run = home.resolve("run");
        this.identity = identity;
    }

    public panel.identity.IdentityPolicy.Mode identityMode() {
        return identity.mode();
    }

    public static Path defaultHome() {
        String env = System.getenv("BYX_LOCAL_SERVICE_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".byx-local-service");
    }

    static final class Fail extends Exception {
        final LocalServiceStatus.State state;
        final String code;

        Fail(LocalServiceStatus.State state, String code) {
            super(code);
            this.state = state;
            this.code = code;
        }
    }

    /** Sondagem completa (health + version + capabilities), com retentativas limitadas para indisponibilidade. */
    public LocalServiceStatus probe(boolean everConnected) {
        LocalServiceStatus last = null;
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            last = once(everConnected);
            // retenta só "ninguém escutando" (reinício em curso); prazo estourado, pareamento recusado ou contrato violado não se repetem
            boolean retriable = last.state() == LocalServiceStatus.State.UNAVAILABLE && ("not_started".equals(last.code()) || "refused".equals(last.code()));
            if (!retriable || attempt == RETRY_DELAYS_MS.length) {
                return last;
            }
            try {
                Thread.sleep(RETRY_DELAYS_MS[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    LocalServiceStatus once(boolean everConnected) {
        Instant at = Instant.now();
        byte[] secret = null;
        try {
            Path socket = run.resolve("service.sock");
            secret = loadSecret(socket);
            java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ScheduledFuture<?> guard = TIMER.schedule(() -> {
                    timedOut.set(true);
                    try {
                        ch.close(); // prazo total: fecha o canal e destrava qualquer leitura
                    } catch (IOException ignored) {
                        // nada
                    }
                }, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                try {
                    return talk(ch, socket, secret, everConnected, at);
                } catch (java.net.ConnectException refused) {
                    return LocalServiceStatus.failed(LocalServiceStatus.State.UNAVAILABLE, "refused", everConnected, at);
                } catch (IOException io) {
                    return LocalServiceStatus.failed(LocalServiceStatus.State.UNAVAILABLE, timedOut.get() ? "timeout" : "no_answer", everConnected, at);
                } finally {
                    guard.cancel(false);
                }
            }
        } catch (Fail f) {
            return LocalServiceStatus.failed(f.state, f.code, everConnected, at);
        } catch (IOException e) {
            // arquivo ausente, conexão recusada, prazo estourado (canal fechado pelo guarda) ou fim de fluxo
            return LocalServiceStatus.failed(LocalServiceStatus.State.UNAVAILABLE, "no_answer", everConnected, at);
        } finally {
            if (secret != null) {
                java.util.Arrays.fill(secret, (byte) 0);
            }
        }
    }

    private LocalServiceStatus talk(SocketChannel ch, Path socket, byte[] secret, boolean everConnected, Instant at) throws IOException, Fail {
        Paired paired = pair(ch, socket, secret);
        InputStream in = paired.in();
        OutputStream out = paired.out();
        JsonNode health = call(in, out, "health");
        JsonNode version = call(in, out, "version");
        JsonNode caps = call(in, out, "capabilities");
        String instance = text(health, "instanceId");
        String ver = text(version, "version");
        if (!"ok".equals(text(health, "status")) || instance == null || !INSTANCE.matcher(instance).matches() || ver == null || !VERSION.matcher(ver).matches()
                || !version.path("protocolMin").isInt() || !version.path("protocolMax").isInt() || !health.path("uptimeSeconds").isNumber()) {
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
        }
        int min = version.path("protocolMin").asInt();
        int max = version.path("protocolMax").asInt();
        if (SUPPORTED_PROTOCOL < min || SUPPORTED_PROTOCOL > max) {
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "protocol_out_of_range");
        }
        // Só o dado PÚBLICO de mercado é honrado quando o serviço o declara (capacidade, distinta do estado do feed). As capacidades
        // privadas continuam falsas aqui, qualquer que seja o que o serviço declare: o painel só as honrará em lote posterior, depois que
        // identidade do usuário e autorização no serviço forem demonstradas.
        Map<String, Boolean> features = new LinkedHashMap<>();
        for (String name : new String[] {"marketData", "notifications", "accountData", "adminOperations"}) {
            features.put(name, false);
        }
        JsonNode declared = caps.path("features").path("marketData");
        features.put("marketData", declared.isBoolean() && declared.asBoolean());
        return new LocalServiceStatus(LocalServiceStatus.State.CONNECTED, "ok", true, ver, SUPPORTED_PROTOCOL, Math.max(0, health.path("uptimeSeconds").asLong()),
                instance, Map.copyOf(features), at);
    }

    /**
     * Abre um canal NOVO e pareado (mesma política de arquivos privados e mesma prova mútua da sondagem). Quem chama fecha o canal e
     * impõe o prazo. O segredo é lido de novo a cada chamada e zerado em seguida.
     */
    Paired openPaired(java.util.function.Consumer<SocketChannel> created) throws IOException, Fail {
        Path socket = run.resolve("service.sock");
        byte[] secret = loadSecret(socket);
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        created.accept(ch); // o chamador arma o prazo do handshake sobre este canal
        try {
            return pair(ch, socket, secret);
        } catch (IOException | Fail | RuntimeException e) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // nada
            }
            throw e;
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    /** Canal já pareado: handshake concluído (o servidor provou o segredo e aceitou a nossa prova). */
    record Paired(SocketChannel channel, InputStream in, OutputStream out) {
    }

    /** Conecta, confere a prova do SERVIDOR antes de mandar a nossa e devolve os fluxos prontos para pedidos. */
    private Paired pair(SocketChannel ch, Path socket, byte[] secret) throws IOException, Fail {
        ch.connect(UnixDomainSocketAddress.of(socket));
        if (identity.strict()) {
            // o kernel diz QUEM é o outro lado: um processo do mesmo usuário que leu o pairing.token não é o serviço do produto
            if (!identity.verifier().verify(ch).verified()) {
                throw new Fail(LocalServiceStatus.State.AUTH_FAILED, "service_identity_not_verified");
            }
        }
        InputStream in = Channels.newInputStream(ch);
        OutputStream out = Channels.newOutputStream(ch);
        byte[] n = new byte[16];
        new SecureRandom().nextBytes(n);
        String cn = Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        send(out, "{\"v\":1,\"type\":\"hello\",\"clientNonce\":\"" + cn + "\"}");
        JsonNode challenge = read(in);
        if (!"challenge".equals(text(challenge, "type")) || challenge.path("v").asInt(-1) != SUPPORTED_PROTOCOL) {
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "unexpected_handshake");
        }
        String sn = text(challenge, "serverNonce");
        String serverProof = text(challenge, "serverProof");
        if (sn == null || serverProof == null || sn.length() > 64 || !constantTimeEquals(serverProof, proof(secret, "server", cn, sn))) {
            // um servidor que não conhece o segredo (substituto) é recusado ANTES de lhe entregarmos qualquer prova ou pedido
            throw new Fail(LocalServiceStatus.State.AUTH_FAILED, "server_not_verified");
        }
        send(out, "{\"v\":1,\"type\":\"auth\",\"clientProof\":\"" + proof(secret, "client", cn, sn) + "\"}");
        JsonNode ready = read(in);
        if ("error".equals(text(ready, "type"))) {
            throw new Fail(LocalServiceStatus.State.AUTH_FAILED, "pairing_rejected");
        }
        if (!"ready".equals(text(ready, "type"))) {
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "unexpected_handshake");
        }
        return new Paired(ch, in, out);
    }

    private static JsonNode call(InputStream in, OutputStream out, String op) throws IOException, Fail {
        if (!OPERATIONS.contains(op)) {
            throw new IllegalArgumentException("not allowlisted");
        }
        return callWithId(in, out, op, "p-" + op);
    }

    private static JsonNode callWithId(InputStream in, OutputStream out, String op, String id) throws IOException, Fail {
        send(out, "{\"v\":1,\"id\":\"" + id + "\",\"op\":\"" + op + "\"}");
        JsonNode r = read(in);
        if (!r.path("ok").isBoolean() || !r.path("ok").asBoolean() || !id.equals(text(r, "id")) || !r.path("result").isObject()) {
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
        }
        return r.path("result");
    }

    /** Uma operação PÚBLICA da chain: canal novo e pareado, prazo total, sem argumentos. A lista é fechada; qualquer outra operação é recusada aqui. */
    JsonNode chainCall(String op) throws IOException, Fail {
        if (!CHAIN_OPERATIONS.contains(op)) {
            throw new IllegalArgumentException("not allowlisted");
        }
        Path socket = run.resolve("service.sock");
        byte[] secret = loadSecret(socket);
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ScheduledFuture<?> guard = TIMER.schedule(() -> {
                try {
                    ch.close(); // prazo total: fecha o canal e destrava qualquer leitura
                } catch (IOException ignored) {
                    // nada
                }
            }, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                Paired paired = pair(ch, socket, secret);
                return callWithId(paired.in(), paired.out(), op, "c-" + op.replace('.', '_'));
            } finally {
                guard.cancel(false);
            }
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * Uma leitura PÚBLICA de módulo: canal novo e pareado, prazo total, operação da lista fechada e {@code args} já validados e serializados pelo chamador tipado (só id decimal, endereço, limite e
     * cursor opaco). O painel nunca monta URL nem escolhe host/porta/rota. Bloqueante: chame fora da thread FX.
     */
    JsonNode moduleCall(String op, String validatedArgsJson) throws IOException, Fail {
        if (!MODULE_OPERATIONS.contains(op) || validatedArgsJson != null && (validatedArgsJson.length() > 256 || !validatedArgsJson.startsWith("{") || !validatedArgsJson.endsWith("}"))) {
            throw new IllegalArgumentException("not allowlisted");
        }
        Path socket = run.resolve("service.sock");
        byte[] secret = loadSecret(socket);
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ScheduledFuture<?> guard = TIMER.schedule(() -> {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // nada
                }
            }, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try {
                Paired paired = pair(ch, socket, secret);
                String id = "m-" + MODULE_SEQ.incrementAndGet();
                send(paired.out(), "{\"v\":1,\"id\":\"" + id + "\",\"op\":\"" + op + "\"" + (validatedArgsJson == null ? "" : ",\"args\":" + validatedArgsJson) + "}");
                JsonNode r = read(paired.in());
                if (!r.path("ok").isBoolean() || !r.path("ok").asBoolean() || !id.equals(text(r, "id")) || !r.path("result").isObject()) {
                    throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
                }
                return r.path("result");
            } finally {
                guard.cancel(false);
            }
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    // ---- arquivos de pareamento (política: privados, do usuário atual, sem symlink) ----------------------------------

    private byte[] loadSecret(Path socket) throws IOException, Fail {
        // Windows native pairing/peer identity has not been qualified. Do not read a token or open IPC.
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            throw new Fail(LocalServiceStatus.State.UNAVAILABLE, "native_service_unsupported");
        }
        UserPrincipal me = java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        Path home = run.getParent();
        if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) {
            throw new Fail(LocalServiceStatus.State.UNAVAILABLE, "not_started");
        }
        requirePrivate(home, me, true);
        requirePrivate(run, me, true);
        Path token = run.resolve("pairing.token");
        if (!Files.exists(token, LinkOption.NOFOLLOW_LINKS) || !Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            throw new Fail(LocalServiceStatus.State.UNAVAILABLE, "not_started");
        }
        requirePrivate(token, me, false);
        PosixFileAttributes s = Files.readAttributes(socket, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (s.isSymbolicLink() || s.isRegularFile() || s.isDirectory() || !s.owner().equals(me)) {
            throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "socket_not_ours");
        }
        if (Files.size(token) > 128) {
            throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "token_malformed");
        }
        try {
            byte[] secret = Base64.getUrlDecoder().decode(Files.readString(token, StandardCharsets.UTF_8).trim());
            if (secret.length != 32) {
                throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "token_malformed");
            }
            return secret;
        } catch (IllegalArgumentException e) {
            throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "token_malformed");
        }
    }

    private static void requirePrivate(Path p, UserPrincipal me, boolean directory) throws IOException, Fail {
        PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (a.isSymbolicLink() || (directory ? !a.isDirectory() : !a.isRegularFile())) {
            throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "pairing_path_not_real");
        }
        if (!a.owner().equals(me)) {
            throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "pairing_wrong_owner");
        }
        for (PosixFilePermission perm : a.permissions()) {
            switch (perm) {
                case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE ->
                        throw new Fail(LocalServiceStatus.State.INSECURE_PAIRING, "pairing_not_private");
                default -> { }
            }
        }
    }

    // ---- quadros ---------------------------------------------------------------------------------------------------------

    static void send(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length == 0 || body.length > MAX_FRAME) {
            throw new IOException("frame_size");
        }
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) (body.length >>> 24);
        frame[1] = (byte) (body.length >>> 16);
        frame[2] = (byte) (body.length >>> 8);
        frame[3] = (byte) body.length;
        System.arraycopy(body, 0, frame, 4, body.length);
        out.write(frame);
        out.flush();
    }

    private static JsonNode read(InputStream in) throws IOException, Fail {
        return read(in, MAX_FRAME);
    }

    /** Leitura de quadro com teto explícito (o tamanho declarado é validado ANTES de alocar o corpo). */
    static JsonNode read(InputStream in, int maxFrame) throws IOException, Fail {
        byte[] header = in.readNBytes(4);
        if (header.length < 4) {
            throw new EOFException("closed");
        }
        int len = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (len <= 0 || len > maxFrame) { // tamanho validado antes de alocar: resposta hostil não consome memória
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "frame_size");
        }
        byte[] body = in.readNBytes(len);
        if (body.length < len) {
            throw new EOFException("truncated");
        }
        try {
            JsonNode n = JSON.readTree(new String(body, StandardCharsets.UTF_8));
            if (n == null || !n.isObject()) {
                throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
            }
            return n;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new Fail(LocalServiceStatus.State.INCOMPATIBLE, "contract_violation");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() && v.asText().length() <= 128 ? v.asText() : null;
    }

    // ---- prova (HMAC-SHA256, idêntica à do serviço) ------------------------------------------------------------------------

    private static String proof(byte[] secret, String label, String cn, String sn) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("byx-ipc-v1|" + label + "|" + cn + "|" + sn).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
