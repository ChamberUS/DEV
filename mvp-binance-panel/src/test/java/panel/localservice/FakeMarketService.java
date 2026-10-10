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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Serviço local de mentira para o cliente de mercado: pareamento real, assinatura e eventos roteirizados (bons e hostis). */
final class FakeMarketService implements AutoCloseable {
    enum Mode { GOOD, UNSUPPORTED, SILENT_AFTER_ACK, DROP_AFTER_FIRST_STATE, OVERSIZE_EVENT, BAD_TOPIC, CROSSED_BOOK, TOO_MANY_LEVELS, UNSORTED_BOOK, NON_INCREASING_SEQ,
        BOOK_WITH_LEVELS_WHILE_NOT_LIVE, NAN_PRICE, WRONG_SYMBOL, UNKNOWN_FEED, CANDLE_GAP_ORDER }

    private static final JsonMapper JSON = new JsonMapper();
    final Path home;
    final byte[] secret = new byte[32];
    volatile Mode mode;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger open = new AtomicInteger();
    final AtomicInteger maxOpen = new AtomicInteger();
    final AtomicInteger accepted = new AtomicInteger();
    final AtomicInteger disconnected = new AtomicInteger();
    private final ServerSocketChannel server;
    private volatile boolean closed;
    /** Permite ao teste empurrar um evento extra (JSON já pronto) na conexão ativa. */
    volatile Consumer<String> push = s -> { };

    FakeMarketService(Path home, Mode mode) throws IOException {
        IpcTestFiles.requirePosixPairing(home);
        this.home = home;
        this.mode = mode;
        Path run = home.resolve("run");
        Files.createDirectories(run);
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwx------"));
        new SecureRandom().nextBytes(secret);
        Path t = run.resolve("pairing.token");
        Files.deleteIfExists(t);
        Files.createFile(t, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(t, Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        Path sock = run.resolve("service.sock");
        Files.deleteIfExists(sock);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(sock));
        Files.setPosixFilePermissions(sock, PosixFilePermissions.fromString("rw-------"));
        Thread th = new Thread(() -> {
            while (!closed) {
                try {
                    SocketChannel ch = server.accept();
                    Thread c = new Thread(() -> serve(ch), "fake-market-conn");
                    c.setDaemon(true);
                    c.start();
                } catch (IOException e) {
                    return;
                }
            }
        }, "fake-market-accept");
        th.setDaemon(true);
        th.start();
    }

    private static String proof(byte[] secret, String label, String cn, String sn) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("byx-ipc-v1|" + label + "|" + cn + "|" + sn).getBytes(StandardCharsets.UTF_8)));
    }

    private static synchronized void send(OutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[4 + b.length];
        frame[0] = (byte) (b.length >>> 24);
        frame[1] = (byte) (b.length >>> 16);
        frame[2] = (byte) (b.length >>> 8);
        frame[3] = (byte) b.length;
        System.arraycopy(b, 0, frame, 4, b.length);
        out.write(frame);
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

    private long seq;

    private String ev(String topic, String body) {
        return "{\"v\":1,\"type\":\"event\",\"topic\":\"" + topic + "\",\"seq\":" + (++seq) + (body.isEmpty() ? "" : "," + body) + "}";
    }

    static String state(String feed, String book, String extra) {
        return "\"symbol\":\"ETHUSDT\",\"market\":\"USD-M\",\"feed\":\"" + feed + "\",\"reason\":\"ok\",\"book\":\"" + book + "\",\"updatedAtMs\":" + System.currentTimeMillis()
                + ",\"nowMs\":" + System.currentTimeMillis() + ",\"last\":2699.5,\"mark\":2699.41,\"index\":2700.91,\"ticker24h\":{\"changePct\":-0.114,\"high\":2734.0,\"low\":2676.97,\"volumeBase\":2346638.29,\"volumeQuote\":6356094948.14}"
                + extra;
    }

    static String candles(int n) {
        StringBuilder sb = new StringBuilder("\"interval\":\"1m\",\"candles\":[");
        long t0 = 1791265800000L;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(t0 + i * 60_000L).append(",2700.0,2701.0,2699.0,2700.5,10.5]");
        }
        return sb.append(']').toString();
    }

    private void serve(SocketChannel ch) {
        int now = open.incrementAndGet();
        maxOpen.accumulateAndGet(now, Math::max);
        accepted.incrementAndGet();
        try (ch) {
            InputStream in = Channels.newInputStream(ch);
            OutputStream out = Channels.newOutputStream(ch);
            JsonNode hello = read(in);
            String cn = hello.path("clientNonce").asText();
            String sn = "SERVERNONCE0123456789ab";
            send(out, "{\"v\":1,\"type\":\"challenge\",\"serverNonce\":\"" + sn + "\",\"serverProof\":\"" + proof(secret, "server", cn, sn) + "\"}");
            read(in);
            send(out, "{\"v\":1,\"type\":\"ready\",\"instanceId\":\"inst1\"}");
            JsonNode req = read(in);
            requests.add(req.toString());
            String id = req.path("id").asText();
            if (!"market.subscribe".equals(req.path("op").asText()) || mode == Mode.UNSUPPORTED) {
                send(out, "{\"v\":1,\"id\":\"" + id + "\",\"ok\":false,\"error\":{\"code\":\"unsupported_operation\"}}");
                return;
            }
            send(out, "{\"v\":1,\"id\":\"" + id + "\",\"ok\":true,\"result\":{\"streaming\":true}}");
            seq = 0;
            push = s -> {
                try {
                    send(out, s);
                } catch (IOException ignored) {
                    // cliente saiu
                }
            };
            script(out);
            // depois do roteiro: batimento até o cliente sair ou o serviço fechar
            while (!closed && mode != Mode.SILENT_AFTER_ACK && mode != Mode.DROP_AFTER_FIRST_STATE) {
                Thread.sleep(100);
                send(out, ev("state", state("LIVE", "LIVE", "")));
            }
            if (mode == Mode.SILENT_AFTER_ACK) {
                Thread.sleep(20_000);
            }
        } catch (Exception e) {
            // o cliente fechou ou o roteiro terminou
        } finally {
            open.decrementAndGet();
            disconnected.incrementAndGet();
        }
    }

    private void script(OutputStream out) throws Exception {
        switch (mode) {
            case SILENT_AFTER_ACK -> { }
            case DROP_AFTER_FIRST_STATE -> {
                send(out, ev("state", state("LIVE", "LIVE", "")));
                Thread.sleep(150);
            }
            case OVERSIZE_EVENT -> {
                out.write(new byte[] {0, 0, 0x50, 0}); // 20 KiB declarados (> teto de 16 KiB)
                out.flush();
                Thread.sleep(300);
            }
            case BAD_TOPIC -> send(out, ev("rawJson", "\"x\":1"));
            case CROSSED_BOOK -> send(out, ev("book", "\"live\":true,\"bids\":[[2701.0,1.0]],\"asks\":[[2700.0,1.0]]"));
            case TOO_MANY_LEVELS -> {
                StringBuilder b = new StringBuilder("\"live\":true,\"bids\":[");
                for (int i = 0; i < 21; i++) {
                    b.append(i > 0 ? "," : "").append('[').append(2700 - i).append(".0,1.0]");
                }
                send(out, ev("book", b + "],\"asks\":[]"));
            }
            case UNSORTED_BOOK -> send(out, ev("book", "\"live\":true,\"bids\":[[2699.0,1.0],[2700.0,1.0]],\"asks\":[]"));
            case NON_INCREASING_SEQ -> {
                send(out, ev("state", state("LIVE", "LIVE", "")));
                seq -= 1; // repete o seq anterior
                send(out, ev("state", state("LIVE", "LIVE", "")));
            }
            case BOOK_WITH_LEVELS_WHILE_NOT_LIVE -> send(out, ev("book", "\"live\":false,\"bids\":[[2699.0,1.0]],\"asks\":[[2700.0,1.0]]"));
            case NAN_PRICE -> send(out, ev("state", state("LIVE", "LIVE", "").replace("\"last\":2699.5", "\"last\":-5")));
            case WRONG_SYMBOL -> send(out, ev("state", state("LIVE", "LIVE", "").replace("ETHUSDT", "BTCUSDT")));
            case UNKNOWN_FEED -> send(out, ev("state", state("LIVE", "LIVE", "").replace("\"feed\":\"LIVE\"", "\"feed\":\"HYPER\"")));
            case CANDLE_GAP_ORDER -> send(out, ev("candles", "\"interval\":\"1m\",\"candles\":[[1791265860000,1,2,1,2,1],[1791265800000,1,2,1,2,1]]"));
            default -> {
                send(out, ev("state", state("LIVE", "LIVE", "")));
                send(out, ev("book", "\"live\":true,\"bids\":[[2699.5,2.0],[2699.0,1.0]],\"asks\":[[2699.6,3.0],[2700.0,4.0]]"));
                send(out, ev("trades", "\"trades\":[[1791265933318,2699.5,0.5,1],[1791265933000,2699.4,0.2,0]]"));
                send(out, ev("candles", candles(5)));
            }
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
