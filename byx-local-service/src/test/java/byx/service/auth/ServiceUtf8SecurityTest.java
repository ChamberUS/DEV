package byx.service.auth;

import static org.junit.jupiter.api.Assertions.*;
import byx.service.*;
import byx.service.identity.IdentityPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

/** Existing isolated authority fixture only: random fictional credentials, memory vault, fake second factor. */
class ServiceUtf8SecurityTest {
    enum Phase { HELLO, AUTH, REQUEST }
    private Path home;
    private ServiceInstance service;
    private AuthFixture fixture;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private String serverNonce;
    private static final String CLIENT_NONCE = "AAAAAAAAAAAAAAAAAAAAAA";

    @BeforeEach
    void start() throws Exception {
        fixture = new AuthFixture();
        home = Files.createTempDirectory(Path.of("/tmp"), "u8");
        Log.redirect(logs::add);
        service = ServiceInstance.start(home, new ServiceInstance.Limits(2, 1_500, 1_500, 3_000),
                null, IdentityPolicy.development(), new AuthIpc(fixture.auth), ch -> 1111L);
    }

    @AfterEach
    void stop() throws Exception {
        if (service != null) service.close();
        Log.redirect(null);
        if (fixture != null) fixture.close();
        if (home != null) try (var walk = Files.walk(home)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    static Stream<Arguments> malformedPhases() {
        byte[][] sequences = {{(byte)0x80}, {(byte)0xc0,(byte)0xaf}, {(byte)0xe2,(byte)0x82},
                {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xf4,(byte)0x90,(byte)0x80,(byte)0x80}, {(byte)0xff}};
        return Arrays.stream(Phase.values()).flatMap(p -> Arrays.stream(sequences).map(bytes -> Arguments.of(p, bytes)));
    }
    static Stream<Arguments> trailingPhases() {
        return Arrays.stream(Phase.values()).flatMap(p -> Stream.of("{}", " garbage").map(s -> Arguments.of(p, s)));
    }
    static Stream<Arguments> brokenFrames() {
        return Arrays.stream(Phase.values()).flatMap(p -> Stream.of("empty", "oversized", "truncated").map(s -> Arguments.of(p, s)));
    }
    static Stream<Arguments> unicodePhases() {
        return Arrays.stream(Phase.values()).flatMap(p -> Stream.of("ação 😀", "\uFFFD").map(s -> Arguments.of(p, s)));
    }

    private TestClient at(Phase phase) throws Exception {
        TestClient c = new TestClient(service.runtimeDir().socket());
        if (phase == Phase.AUTH) {
            c.sendJson(valid(Phase.HELLO));
            var challenge = c.readJson();
            assertEquals("challenge", challenge.path("type").asText());
            serverNonce = challenge.path("serverNonce").asText();
        } else if (phase == Phase.REQUEST) {
            assertEquals("ready", c.handshake(TestClient.readToken(home), true).path("type").asText());
        }
        return c;
    }
    private String valid(Phase p) throws Exception {
        return switch (p) {
            case HELLO -> "{\"v\":1,\"type\":\"hello\",\"clientNonce\":\""+CLIENT_NONCE+"\"}";
            case AUTH -> "{\"v\":1,\"type\":\"auth\",\"clientProof\":\""+Pairing.clientProof(TestClient.readToken(home),CLIENT_NONCE,serverNonce)+"\"}";
            case REQUEST -> "{\"v\":1,\"id\":\"probe\",\"op\":\"health\"}";
        };
    }
    private byte[] embedded(Phase p, byte[] bad) throws Exception {
        String field = p == Phase.HELLO ? "clientNonce" : p == Phase.AUTH ? "clientProof" : "op";
        var tree = Protocol.mapper().readTree(valid(p));
        String value = tree.path(field).asText();
        String json = tree.toString();
        int split = json.indexOf("\""+field+"\":\"") + field.length()+4;
        int end = split + value.length();
        var output = new java.io.ByteArrayOutputStream();
        output.write(json.substring(0,end).getBytes(StandardCharsets.UTF_8));
        output.write(bad); output.write(json.substring(end).getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }
    private void rejected(TestClient c) throws Exception {
        var response = c.readJson();
        assertEquals("error", response.path("type").asText());
        assertEquals("bad_request", response.path("code").asText());
        assertFalse(response.has("result"));
        assertFalse(response.has("session"));
        // Do not discard later frames while waiting for EOF: any byte after the error is a failure.
        c.channel.configureBlocking(false);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        var buffer = java.nio.ByteBuffer.allocate(1);
        while (System.nanoTime() < deadline) {
            final int read;
            try { read = c.channel.read(buffer); }
            catch (java.io.IOException closed) { return; }
            assertTrue(read <= 0, "no challenge, ready or dispatched response after rejection");
            if (read < 0) return;
            Thread.sleep(15);
        }
        fail("invalid connection must close within its deadline");
    }
    private void healthyAndReleased() throws Exception {
        TestClient.waitFor(() -> service.activeConnections()==0,2_000);
        assertEquals(0,service.activeConnections(),"cleanup released the rejected worker/connection");
        // Fill both slots after the rejection: a leaked semaphore permit must fail this check.
        try (TestClient first = at(Phase.REQUEST); TestClient second = at(Phase.REQUEST)) {
            assertTrue(first.call("health").path("ok").asBoolean());
            assertTrue(second.call("health").path("ok").asBoolean());
        }
        TestClient.waitFor(() -> service.activeConnections()==0,2_000);
        assertEquals(0,service.activeConnections());
        assertFalse(logs.stream().anyMatch(s -> s.contains("clientNonce") || s.contains("clientProof") || s.contains("probe") || s.contains(fixture.userPw)), "no raw payload/credential log");
    }

    @ParameterizedTest
    @MethodSource("malformedPhases")
    void malformedTextRejectsEachPhaseBeforeDispatch(Phase p, byte[] bad) throws Exception {
        long before = PasswordVerifier.derivations();
        try (TestClient c = at(p)) {
            byte[] body = embedded(p,bad);
            // Pipeline a legitimate later request: a bad connection must never deliver its result.
            Frames.write(c.out,body);
            try { c.sendJson("{\"v\":1,\"id\":\"later\",\"op\":\"health\"}"); } catch (java.io.IOException ignored) { }
            rejected(c);
        }
        assertEquals(before,PasswordVerifier.derivations(),"no user authentication work");
        healthyAndReleased();
    }

    @ParameterizedTest
    @MethodSource("trailingPhases")
    void trailingJsonOrGarbageRejectsEachPhase(Phase p, String tail) throws Exception {
        try (TestClient c = at(p)) { c.sendJson(valid(p)+tail); rejected(c); }
        healthyAndReleased();
    }

    @ParameterizedTest
    @MethodSource("unicodePhases")
    void validUnicodeRetainsEachPhasesFieldPolicy(Phase p, String unicode) throws Exception {
        try (TestClient c = at(p)) {
            Frames.write(c.out, embedded(p, unicode.getBytes(StandardCharsets.UTF_8)));
            var response = c.readJson();
            if (p == Phase.REQUEST) {
                assertEquals("unsupported_operation", response.path("error").path("code").asText());
                assertTrue(c.call("health").path("ok").asBoolean());
            } else {
                // The nonce/proof alphabets are ASCII: valid Unicode is decoded, then rejected by the original policy.
                assertEquals(p == Phase.AUTH ? "auth_failed" : "bad_request", response.path("code").asText());
                assertTrue(c.closedWithin(2_000));
            }
        }
        healthyAndReleased();
    }

    @ParameterizedTest
    @MethodSource("brokenFrames")
    void framingFailureClosesAndReleasesEachPhase(Phase p, String kind) throws Exception {
        try (TestClient c = at(p)) {
            int length = kind.equals("empty") ? 0 : kind.equals("oversized") ? 8193 : 40;
            c.out.write(new byte[]{(byte)(length>>>24),(byte)(length>>>16),(byte)(length>>>8),(byte)length});
            if (kind.equals("truncated")) c.out.write(new byte[]{'{','"'});
            c.out.flush(); c.channel.shutdownOutput();
            assertTrue(c.closedWithin(2_000));
        }
        healthyAndReleased();
    }

    @Test
    void malformedPasswordNeverInvokesTheConfiguredSyntheticAuthority() throws Exception {
        long before = PasswordVerifier.derivations();
        try (TestClient c = at(Phase.REQUEST)) {
            var bytes = new java.io.ByteArrayOutputStream();
            bytes.write("{\"v\":1,\"id\":\"probe\",\"op\":\"auth.password\",\"username\":\"normal_user\",\"password\":\"canary-".getBytes(StandardCharsets.UTF_8));
            bytes.write(0x80); bytes.write("\"}".getBytes(StandardCharsets.UTF_8));
            Frames.write(c.out,bytes.toByteArray()); rejected(c);
        }
        assertEquals(before,PasswordVerifier.derivations(),"even a configured authority cannot see the malformed password");
        assertTrue(fixture.second.history.isEmpty());
        healthyAndReleased();
    }

    @Test
    void validSequentialRequestsAndUnicodeRemainCompatible() throws Exception {
        try (TestClient c = at(Phase.REQUEST)) {
            for (String op : List.of("health", "version", "capabilities")) assertTrue(c.call(op).path("ok").asBoolean());
            for (String text : List.of("ação 日本語 😀", "\uFFFD")) {
                c.sendJson("{\"v\":1,\"id\":\"unicode\",\"op\":\""+text+"\"}");
                var r = c.readJson();
                assertEquals("unsupported_operation",r.path("error").path("code").asText(),"valid UTF-8 is decoded and retains the allowlist policy");
                assertTrue(c.call("health").path("ok").asBoolean(),"valid Unicode does not close the connection");
            }
        }
        healthyAndReleased();
    }
}
