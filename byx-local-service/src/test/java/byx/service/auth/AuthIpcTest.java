package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.ServiceInstance;
import byx.service.TestClient;
import byx.service.identity.IdentityPolicy;
import byx.service.identity.PeerKeys;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IPC tipado de autenticação sobre o socket real do serviço (peer key injetada: o kernel real só existe no teste empacotado). */
class AuthIpcTest {
    private Path home;
    private AuthFixture f;
    private ServiceInstance service;
    private final AtomicLong peer = new AtomicLong(1111);
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final List<TestClient> clients = new ArrayList<>();

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        f = new AuthFixture();
        home = Files.createTempDirectory(Path.of("/tmp"), "ai");
        service = ServiceInstance.start(home, new ServiceInstance.Limits(8, 1_000, 1_000, 3_000, 1_000), null, IdentityPolicy.development(), new AuthIpc(f.auth), ch -> peer.get());
    }

    @AfterEach
    void down() throws Exception {
        clients.forEach(TestClient::close);
        service.close();
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

    private JsonNode call(TestClient c, String json) throws Exception {
        c.sendJson(json);
        return c.readJson();
    }

    private String login(TestClient c) throws Exception {
        JsonNode r = call(c, "{\"v\":1,\"id\":\"a1\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"" + f.userPw + "\"}");
        assertTrue(r.path("ok").asBoolean(), r.toString());
        return r.path("result").path("session").asText();
    }

    @Test
    void loginStatusAndLogoutOverTheRealSocket() throws Exception {
        TestClient c = client();
        String t = login(c);
        JsonNode st = call(c, "{\"v\":1,\"id\":\"a2\",\"op\":\"auth.sessionStatus\",\"session\":\"" + t + "\"}");
        assertTrue(st.path("ok").asBoolean());
        assertEquals("USER", st.path("result").path("role").asText());
        assertFalse(st.path("result").path("elevated").asBoolean());
        assertTrue(call(c, "{\"v\":1,\"id\":\"a3\",\"op\":\"auth.logout\",\"session\":\"" + t + "\"}").path("ok").asBoolean());
        assertEquals("AUTH_REQUIRED", call(c, "{\"v\":1,\"id\":\"a4\",\"op\":\"auth.sessionStatus\",\"session\":\"" + t + "\"}").path("error").path("code").asText());
    }

    @Test
    void aStolenTokenOnAnotherPeerIsRejectedOnTheWire() throws Exception {
        TestClient c = client();
        String t = login(c);
        peer.set(2222); // outro processo (kernel diz outro pid/pidversion)
        TestClient other = client();
        assertEquals("AUTH_REQUIRED", call(other, "{\"v\":1,\"id\":\"b1\",\"op\":\"auth.sessionStatus\",\"session\":\"" + t + "\"}").path("error").path("code").asText());
        peer.set(1111);
        assertTrue(call(c, "{\"v\":1,\"id\":\"b2\",\"op\":\"auth.sessionStatus\",\"session\":\"" + t + "\"}").path("ok").asBoolean(), "the owner is unaffected");
    }

    @Test
    void noKernelPeerKeyMeansNoAuthentication() throws Exception {
        peer.set(PeerKeys.NONE);
        TestClient c = client();
        assertEquals("peer_unavailable", call(c, "{\"v\":1,\"id\":\"n1\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"" + f.userPw + "\"}").path("error").path("code").asText());
    }

    @Test
    void forgedIdentityFieldsAndForbiddenOperationsAreRefused() throws Exception {
        TestClient c = client();
        String t = login(c);
        String[] forged = {
            "\"role\":\"ADMIN\"", "\"userId\":\"x\"", "\"username\":\"admin_user\"", "\"admin\":true", "\"adminElevated\":true", "\"mfa\":true", "\"sessionState\":\"ELEVATED\"", "\"expectedRole\":\"ADMIN\""
        };
        for (String field : forged) {
            JsonNode r = call(c, "{\"v\":1,\"id\":\"f1\",\"op\":\"auth.sessionStatus\",\"session\":\"" + t + "\"," + field + "}");
            assertEquals("bad_request", r.path("error").path("code").asText(), field);
        }
        for (String op : new String[] {"auth.execute", "auth.querySql", "auth.setRole", "auth.setMfa", "auth.impersonate", "auth.override", "auth.debugLogin", "auth.begin", "auth."}) {
            JsonNode r = call(c, "{\"v\":1,\"id\":\"f2\",\"op\":\"" + op + "\",\"session\":\"" + t + "\"}");
            assertEquals("unsupported_operation", r.path("error").path("code").asText(), op);
        }
        assertTrue(call(c, "{\"v\":1,\"id\":\"f3\",\"op\":\"auth.sessionStatus\",\"session\":\"" + t + "\"}").path("result").path("role").asText().equals("USER"), "nothing the UI sent changed the role");
        JsonNode caps = call(c, "{\"v\":1,\"id\":\"f4\",\"op\":\"capabilities\"}").path("result");
        assertTrue(caps.path("features").path("authentication").asBoolean());
        for (var n : caps.path("operations")) {
            assertFalse(n.asText().matches("auth\\.(execute|querySql|setRole|setMfa|impersonate|override|debugLogin)"), n.asText());
        }
        assertFalse(caps.path("privateGate").path("allowed").asBoolean());
    }

    @Test
    void malformedAndOversizedAuthFramesAreRejectedWithoutLeaks() throws Exception {
        String longPw = "p".repeat(300);
        String[] bad = {
            "{\"v\":1,\"id\":\"m1\",\"op\":\"auth.password\"}",
            "{\"v\":1,\"id\":\"m2\",\"op\":\"auth.password\",\"username\":5,\"password\":\"x\"}",
            "{\"v\":1,\"id\":\"m3\",\"op\":\"auth.password\",\"username\":\"a b\",\"password\":\"x\"}",
            "{\"v\":1,\"id\":\"m4\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"" + longPw + "\"}",
            "{\"v\":1,\"id\":\"m5\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"\"}",
            "{\"v\":1,\"id\":\"m6\",\"op\":\"auth.sessionStatus\",\"session\":\"short\"}",
            "{\"v\":1,\"id\":\"m7\",\"op\":\"auth.sessionStatus\",\"session\":null}",
            "{\"v\":1,\"id\":\"m8\",\"op\":\"auth.verifySecondFactor\",\"session\":\"" + "A".repeat(43) + "\",\"challenge\":\"A\",\"code\":\"12\"}",
            "{\"v\":1,\"id\":\"m9\",\"op\":\"auth.verifySecondFactor\",\"session\":\"" + "A".repeat(43) + "\",\"challenge\":\"" + "A".repeat(22) + "\",\"code\":\"12345a\"}",
            "{\"v\":1,\"id\":\"m10\",\"op\":\"auth.changePassword\",\"session\":\"" + "A".repeat(43) + "\",\"current\":\"x\"}",
        };
        for (String b : bad) {
            TestClient c = client();
            JsonNode r = call(c, b);
            assertEquals("bad_request", r.path("error").path("code").asText(), b.substring(0, Math.min(70, b.length())));
            c.close();
        }
        for (String garbage : new String[] {"[]", "\"x\"", "null", "{\"op\":\"auth.password\",\"op\":\"health\",\"v\":1,\"id\":\"d\"}", "{not json"}) {
            TestClient c = client();
            c.sendJson(garbage);
            JsonNode r = c.readJson();
            assertEquals("bad_request", r.path("code").asText(), garbage);
            c.close();
        }
        TestClient big = client(); // quadro acima do teto de 8 KiB: a conexão é fechada sem ler o corpo
        big.out.write(new byte[] {0, 0, 0x40, 0}); // 16 KiB declarados
        big.out.flush();
        assertTrue(big.closedWithin(2_000));
        String all = String.join("\n", logs);
        assertFalse(all.contains(longPw) || all.contains(f.userPw), "no typed password in logs");
    }

    @Test
    void errorsNeverCarryPasswordsTokensOrAccountNames() throws Exception {
        TestClient c = client();
        JsonNode wrong = call(c, "{\"v\":1,\"id\":\"e1\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"this-is-not-the-password\"}");
        JsonNode unknown = call(c, "{\"v\":1,\"id\":\"e2\",\"op\":\"auth.password\",\"username\":\"ghost_user\",\"password\":\"this-is-not-the-password\"}");
        assertEquals(wrong.path("error"), unknown.path("error"), "wrong password and unknown user are indistinguishable on the wire");
        assertEquals("INVALID_CREDENTIALS", wrong.path("error").path("code").asText());
        assertFalse(wrong.toString().contains("normal_user") || wrong.toString().contains("this-is-not"));
        String t = login(c);
        String all = String.join("\n", logs);
        assertFalse(all.contains(t) || all.contains(f.userPw) || all.contains("this-is-not-the-password"), "session/password canaries absent from logs");
    }

    @Test
    void theOtpIsNeverReturnedOverIpcAndTheProductionProviderIsNotConfigured() throws Exception {
        TestClient c = client();
        String t = login(c);
        JsonNode b = call(c, "{\"v\":1,\"id\":\"o1\",\"op\":\"auth.beginSecondFactor\",\"session\":\"" + t + "\"}");
        assertTrue(b.path("ok").asBoolean(), b.toString());
        assertFalse(b.toString().contains(f.second.last.get(f.userId)), "OTP never over IPC");
        String ch = b.path("result").path("challenge").asText();
        JsonNode v = call(c, "{\"v\":1,\"id\":\"o2\",\"op\":\"auth.verifySecondFactor\",\"session\":\"" + t + "\",\"challenge\":\"" + ch + "\",\"code\":\"" + f.second.last.get(f.userId) + "\"}");
        assertTrue(v.path("ok").asBoolean(), v.toString());
        JsonNode replay = call(c, "{\"v\":1,\"id\":\"o3\",\"op\":\"auth.verifySecondFactor\",\"session\":\"" + t + "\",\"challenge\":\"" + ch + "\",\"code\":\"" + f.second.last.get(f.userId) + "\"}");
        assertEquals("CHALLENGE_INVALID", replay.path("error").path("code").asText());
    }

    @Test
    void withoutTheAuthComposerTheAuthOperationsDoNotExist() throws Exception {
        try (ServiceInstance plain = ServiceInstance.start(Files.createTempDirectory(Path.of("/tmp"), "ap"), new ServiceInstance.Limits(8, 1_000, 1_000, 3_000, 1_000), null)) {
            TestClient c = new TestClient(plain.runtimeDir().socket());
            c.handshake(TestClient.readToken(plain.runtimeDir().socket().getParent().getParent()), true);
            JsonNode r = call(c, "{\"v\":1,\"id\":\"p1\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"x\"}");
            assertEquals("unsupported_operation", r.path("error").path("code").asText());
            assertFalse(call(c, "{\"v\":1,\"id\":\"p2\",\"op\":\"capabilities\"}").path("result").path("features").path("authentication").asBoolean());
            c.close();
        }
    }
}
