package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Serviço de mentira para provar o comportamento do CLIENTE do painel diante de peers hostis. Fala o mesmo protocolo, com defeitos escolhidos. */
final class FakeService implements AutoCloseable {
    enum Mode { GOOD, REPLAYED_SERVER_PROOF, IMPOSTOR, REJECTS_CLIENT, OVERSIZE, STALL, GARBAGE, INCOMPATIBLE_PROTOCOL, HOSTILE_STRINGS }

    private static final JsonMapper JSON = new JsonMapper();
    final Path home;
    final byte[] tokenSecret;
    volatile Mode mode;
    final AtomicInteger authFramesSeen = new AtomicInteger();
    private final ServerSocketChannel server;
    private final Thread thread;
    private volatile boolean closed;

    FakeService(Path home, Mode mode) throws IOException {
        this.home = home;
        this.mode = mode;
        Path run = home.resolve("run");
        Files.createDirectories(run);
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"));
        tokenSecret = new byte[32];
        new SecureRandom().nextBytes(tokenSecret);
        writeToken(Base64.getUrlEncoder().withoutPadding().encodeToString(tokenSecret));
        Path sock = run.resolve("service.sock");
        Files.deleteIfExists(sock);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock));
        Files.setPosixFilePermissions(sock, PosixFilePermissions.fromString("rw-------"));
        thread = new Thread(this::loop, "fake-service");
        thread.setDaemon(true);
        thread.start();
    }

    void writeToken(String content) throws IOException {
        Path t = home.resolve("run").resolve("pairing.token");
        Files.deleteIfExists(t);
        Files.createFile(t, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(t, content);
    }

    private void loop() {
        while (!closed) {
            try {
                SocketChannel ch = server.accept();
                Thread t = new Thread(() -> serve(ch), "fake-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private static String proof(byte[] secret, String label, String cn, String sn) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("byx-ipc-v1|" + label + "|" + cn + "|" + sn).getBytes(StandardCharsets.UTF_8)));
    }

    private static void send(OutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.write(new byte[] {(byte) (b.length >>> 24), (byte) (b.length >>> 16), (byte) (b.length >>> 8), (byte) b.length});
        out.write(b);
        out.flush();
    }

    private static JsonNode read(InputStream in) throws IOException {
        byte[] h = in.readNBytes(4);
        if (h.length < 4) {
            throw new IOException("eof");
        }
        int len = ((h[0] & 0xFF) << 24) | ((h[1] & 0xFF) << 16) | ((h[2] & 0xFF) << 8) | (h[3] & 0xFF);
        return JSON.readTree(new String(in.readNBytes(len), StandardCharsets.UTF_8));
    }

    private void serve(SocketChannel ch) {
        try (ch) {
            InputStream in = Channels.newInputStream(ch);
            OutputStream out = Channels.newOutputStream(ch);
            JsonNode hello = read(in);
            if (mode == Mode.STALL) {
                Thread.sleep(30_000);
                return;
            }
            String cn = hello.path("clientNonce").asText();
            String sn = "SERVERNONCE0123456789ab";
            byte[] secret = mode == Mode.IMPOSTOR ? new byte[32] : tokenSecret; // o impostor não conhece o segredo do arquivo
            if (mode == Mode.GARBAGE) {
                byte[] junk = "this is not json at all".getBytes(StandardCharsets.UTF_8);
                out.write(new byte[] {0, 0, 0, (byte) junk.length});
                out.write(junk);
                return;
            }
            // REPLAYED_SERVER_PROOF: prova VÁLIDA (segredo real) mas de uma conversa anterior, calculada sobre outro nonce de cliente
            String proofCn = mode == Mode.REPLAYED_SERVER_PROOF ? "AAAAAAAAAAAAAAAAAAAAAA" : cn;
            send(out, "{\"v\":1,\"type\":\"challenge\",\"serverNonce\":\"" + sn + "\",\"serverProof\":\"" + proof(secret, "server", proofCn, sn) + "\"}");
            JsonNode auth = read(in);
            authFramesSeen.incrementAndGet();
            if (mode == Mode.REJECTS_CLIENT) {
                send(out, "{\"v\":1,\"type\":\"error\",\"code\":\"auth_failed\"}");
                return;
            }
            send(out, "{\"v\":1,\"type\":\"ready\",\"instanceId\":\"inst1\"}");
            for (int i = 0; i < 3; i++) {
                JsonNode req = read(in);
                String id = req.path("id").asText();
                String op = req.path("op").asText();
                if (mode == Mode.OVERSIZE) {
                    out.write(new byte[] {0, (byte) 0xA0, 0, 0}); // 10 MiB declarados, nenhum byte do corpo
                    out.flush();
                    Thread.sleep(500);
                    return;
                }
                String result = switch (op) {
                    case "health" -> "{\"status\":\"ok\",\"uptimeSeconds\":7,\"instanceId\":\"inst1\"}";
                    case "version" -> mode == Mode.INCOMPATIBLE_PROTOCOL
                            ? "{\"service\":\"x\",\"version\":\"0.1.0\",\"protocolMin\":2,\"protocolMax\":3}"
                            : mode == Mode.HOSTILE_STRINGS
                            ? "{\"service\":\"x\",\"version\":\"/Users/victim/.ssh/id_rsa\",\"protocolMin\":1,\"protocolMax\":1}"
                            : "{\"service\":\"byx-local-service\",\"version\":\"0.1.0\",\"protocolMin\":1,\"protocolMax\":1}";
                    default -> "{\"features\":{\"marketData\":false,\"notifications\":false,\"accountData\":true,\"adminOperations\":\"yes\"},\"extra\":\"ignored\"}";
                };
                send(out, "{\"v\":1,\"id\":\"" + id + "\",\"ok\":true,\"result\":" + result + "}");
            }
        } catch (Exception e) {
            // o cliente de teste fechou ou o modo encerrou a conversa
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // fim do teste
        }
        try {
            Files.deleteIfExists(home.resolve("run").resolve("service.sock"));
        } catch (IOException ignored) {
            // limpeza
        }
    }
}
