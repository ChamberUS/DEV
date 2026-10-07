package byx.service.chain;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Nó BYX FALSO em 127.0.0.1 (porta efêmera), controlado pelo teste. Nunca é o nó real. Um servidor serve RPC (/status) e REST (bank). */
public final class FakeNode implements AutoCloseable {
    public record Reply(int status, String body, long delayMs, String location) {
        public static Reply ok(String body) { return new Reply(200, body, 0, null); }
    }

    public volatile String chainId = "byx";
    public volatile long height = 100;
    public volatile Instant blockTime = Instant.now();
    public volatile boolean catchingUp = false;
    public volatile String base = "ubyx";
    public volatile String display = "BYX";
    public volatile int displayExponent = 6;
    public volatile String supply = "1500000";
    public volatile Map<String, Reply> override = new ConcurrentHashMap<>();
    public final AtomicInteger requests = new AtomicInteger();
    public final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final HttpServer server;

    public FakeNode() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            String key = ex.getRequestURI().getRawPath();
            paths.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            Reply r = override.get(key);
            if (r == null) {
                r = switch (key) {
                    case "/status" -> Reply.ok(status());
                    case "/cosmos/bank/v1beta1/denoms_metadata/ubyx" -> Reply.ok(metadata());
                    case "/cosmos/bank/v1beta1/supply/by_denom" -> Reply.ok("{\"amount\":{\"denom\":\"" + base + "\",\"amount\":\"" + supply + "\"}}");
                    default -> new Reply(404, "{}", 0, null);
                };
            }
            if (r.delayMs() > 0) {
                try { Thread.sleep(r.delayMs()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            if (r.location() != null) {
                ex.getResponseHeaders().add("Location", r.location());
            }
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public String origin() { return "http://127.0.0.1:" + port(); }

    public String status() {
        return "{\"jsonrpc\":\"2.0\",\"id\":-1,\"result\":{\"node_info\":{\"id\":\"abc\",\"network\":\"" + chainId + "\",\"version\":\"0.1\"},\"sync_info\":{\"latest_block_hash\":\"AB\","
                + "\"latest_block_height\":\"" + height + "\",\"latest_block_time\":\"" + blockTime + "\",\"catching_up\":" + catchingUp + "}}}";
    }

    public String metadata() {
        return "{\"metadata\":{\"description\":\"x\",\"denom_units\":[{\"denom\":\"" + base + "\",\"exponent\":0,\"aliases\":[]},{\"denom\":\"" + display + "\",\"exponent\":" + displayExponent
                + ",\"aliases\":[]}],\"base\":\"" + base + "\",\"display\":\"" + display + "\",\"name\":\"BYX\",\"symbol\":\"BYX\"}}";
    }

    public ChainConfig config() {
        return ChainConfig.of(origin(), origin(), "byx", new DenomModel("ubyx", "BYX", 6));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
