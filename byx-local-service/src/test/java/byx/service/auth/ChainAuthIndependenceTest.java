package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.ServiceInstance;
import byx.service.TestClient;
import byx.service.chain.ChainConnector;
import byx.service.chain.FakeNode;
import byx.service.chain.LoopbackHttp;
import byx.service.chain.ModuleFixtures;
import byx.service.identity.IdentityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V2.1N-1A: a leitura PÚBLICA da chain nunca pode impedir a autenticação. Nó lento, offline, malformado, tempestade de leituras de módulo e fila saturada: o login válido continua funcionando
 * sobre o socket real do serviço (limite total de 8 conexões; leituras de módulo ocupam no máximo 3).
 */
class ChainAuthIndependenceTest {
    private Path home;
    private AuthFixture f;
    private ServiceInstance service;
    private FakeNode node;
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void up() throws Exception {
        Log.redirect(s -> { });
        f = new AuthFixture();
        node = new FakeNode();
        home = Files.createTempDirectory(Path.of("/tmp"), "ca");
        ChainConnector chain = new ChainConnector(Optional.of(node.config()), new LoopbackHttp(Duration.ofMillis(400), Duration.ofSeconds(3)), new ChainConnector.Timing(10, 60_000, 30_000, 600_000),
                System::currentTimeMillis);
        service = ServiceInstance.start(home, new ServiceInstance.Limits(8, 1_000, 5_000, 6_000, 2_000), null, IdentityPolicy.development(), new AuthIpc(f.auth), ch -> 1111L, chain);
    }

    @AfterEach
    void down() throws Exception {
        clients.forEach(TestClient::close);
        service.close();
        node.close();
        f.close();
        Log.redirect(null);
        try (var w = Files.walk(home)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private TestClient client() throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        clients.add(c);
        assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        return c;
    }

    private void waitLive() throws Exception {
        TestClient c = client();
        long end = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < end && !"LIVE".equals(c.call("byx.status").path("result").path("state").asText())) {
            Thread.sleep(25);
        }
        c.close();
    }

    private long loginMs() throws Exception {
        long t0 = System.nanoTime();
        TestClient c = client();
        c.sendJson("{\"v\":1,\"id\":\"a1\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"" + f.userPw + "\"}");
        JsonNode r = c.readJson();
        assertTrue(r.path("ok").asBoolean(), r.toString());
        assertEquals(43, r.path("result").path("session").asText().length());
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private Future<JsonNode> read(ExecutorService ex, int id) {
        return ex.submit(() -> {
            TestClient c = client();
            try {
                c.sendJson("{\"v\":1,\"id\":\"m" + id + "\",\"op\":\"byx.lojas.getMerchant\",\"args\":{\"id\":\"" + id + "\"}}");
                return c.readJson().path("result");
            } finally {
                c.close(); // como o painel: um canal por leitura, fechado ao terminar
            }
        });
    }

    @Test
    void loginWorksWhileTheNodeIsSlowAndAStormOfModuleReadsIsInFlight() throws Exception {
        for (int i = 1; i <= 7; i++) { // 7 leitores simultâneos + o login = os 8 slots de conexão do serviço
            node.byUri.put("/byx/lojas/v1/merchant/" + i, new FakeNode.Reply(200, "{\"merchant\":" + "{}" + "}", 2_000, null));
        }
        waitLive();
        ExecutorService ex = Executors.newFixedThreadPool(7);
        List<Future<JsonNode>> reads = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            reads.add(read(ex, i));
        }
        Thread.sleep(300); // a tempestade já está presa no nó lento
        long ms = loginMs();
        assertTrue(ms < 1_500, "login during a read storm took " + ms + " ms");
        int limited = 0;
        for (Future<JsonNode> r : reads) {
            JsonNode res = r.get(15, TimeUnit.SECONDS);
            if ("RATE_LIMITED".equals(res.path("failure").asText())) {
                limited++;
            }
        }
        ex.shutdownNow();
        assertTrue(limited >= 3, "excess reads are refused immediately instead of holding connections: " + limited);
    }

    @Test
    void loginWorksWhileTheNodeIsOfflineOrMalformed() throws Exception {
        node.override.put("/status", new FakeNode.Reply(500, "{}", 0, null));
        assertTrue(loginMs() < 1_500);
        node.override.put("/status", new FakeNode.Reply(200, "not json at all", 0, null));
        assertTrue(loginMs() < 1_500);
        node.override.put("/status", new FakeNode.Reply(200, "{\"result\":{}}", 3_000, null)); // lento
        assertTrue(loginMs() < 1_500);
        ModuleFixtures.payment("1", "1", "1", "PAYMENT_STATUS_PENDING");
    }

    @Test
    void aSaturatedReaderQueueNeverBlocksAuth() throws Exception {
        waitLive();
        node.byUri.put("/byx/lojas/v1/merchant/1", new FakeNode.Reply(200, "{\"merchant\":{}}", 3_000, null));
        ExecutorService ex = Executors.newFixedThreadPool(6);
        List<Future<JsonNode>> reads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reads.add(read(ex, 1)); // idênticas: coalescem num único pedido lento
        }
        Thread.sleep(300);
        assertTrue(loginMs() < 1_500);
        for (Future<JsonNode> r : reads) {
            r.get(15, TimeUnit.SECONDS);
        }
        ex.shutdownNow();
    }

    @Test
    void aConnectingMarketFeedDoesNotBlockLogin() throws Exception {
        service.close();
        service = ServiceInstance.start(home, new ServiceInstance.Limits(8, 1_000, 5_000, 6_000, 2_000),
                new byx.service.market.MarketFeed(new byx.service.market.FakeMarket.Ws(), new byx.service.market.FakeMarket.Http(), byx.service.market.FakeMarket.fast()),
                IdentityPolicy.development(), new AuthIpc(f.auth), ch -> 1111L, ChainConnector.notConfigured());
        TestClient m = client();
        m.sendJson("{\"v\":1,\"id\":\"s1\",\"op\":\"market.subscribe\"}");
        m.readJson();
        assertTrue(loginMs() < 1_500, "login while a market subscription is connecting");
    }
}
