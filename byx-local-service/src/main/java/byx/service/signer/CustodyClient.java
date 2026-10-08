package byx.service.signer;

import byx.service.identity.CodeIdentity;
import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.TxSignRequest;
import byx.service.tx.TxPorts.TxSignerException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service-side client of the SYNTHETIC custody QA signer (V2.1T-1). One signer process per call, private AF_UNIX transport, no TCP/HTTP/localhost, no shell, no PATH lookup:
 * <ol>
 *   <li>the helper bundle is validated STATICALLY (Apple chain, Team ID, exact identifier, strict nested seal) BEFORE anything is executed, and its real path must equal the
 *       fixed origin derived from this service's own bundle ({@code Contents/Helpers/byx-signer-helper-qa.app}); a copy elsewhere is refused;</li>
 *   <li>the helper is started with an EMPTY environment and only stdio; its launch environment and inherited descriptors are audited;</li>
 *   <li>on the connected socket the kernel audit token of the helper endpoint must name the very child we spawned, and its LIVE code must satisfy the signer requirement with a
 *       valid seal and the approved origin;</li>
 *   <li>the helper's fresh challenge is echoed in the single request; every signed result is verified independently (low-S, TxRaw, hash, bindings).</li>
 * </ol>
 * Inert unless a QA artifact contains the helper. The production signer remains {@code TxSigner.UNAVAILABLE}.
 */
public final class CustodyClient {
    public static final String SIGNER_ID = "com.buynnex.byx.signer.qa";
    public static final String HELPER_APP = "byx-signer-helper-qa.app";
    public static final String HELPER_EXE = "byx-signer-helper-qa";
    /** Compiled namespace; the helper refuses anything else. */
    public static final String NAMESPACE = "byx.signer.qa.synthetic.v1";
    private static final int MAX_FRAME = 8192;
    private static final long CALL_TIMEOUT_MS = 12_000;
    private static final List<String> BANNED_ENV = List.of("JAVA_TOOL_OPTIONS=", "_JAVA_OPTIONS=", "JDK_JAVA_OPTIONS=", "CLASSPATH=", "DYLD_", "LD_", "JAVA_OPTIONS=", "HOME=", "PATH=", "TMPDIR=");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** Local failure with a closed code; the message is a fixed reason code for QA output (never secret material). */
    public static final class CustodyException extends Exception {
        private final String code;

        CustodyException(String code, String reason) {
            super(reason, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }

        public String reason() {
            return getMessage();
        }
    }

    /** Helper reply (statuses are a closed set; unknown is treated as SIGNING_FAILED by callers). */
    public record Reply(String status, int keychainCalls, String publicKey, String address, int count, JsonNode response) { }

    private final Path launchApp;
    private final Path trustedOrigin;
    private final CodeIdentity identity;

    /** Production-shaped constructor: launches the signer at the fixed sibling origin only. */
    public CustodyClient() throws CustodyException {
        this(defaultHelper());
    }

    /** QA constructor: {@code launchApp} may differ from the fixed origin (copied/modified helper negative tests); it is then refused. */
    public CustodyClient(Path launchApp) throws CustodyException {
        this.launchApp = launchApp;
        this.trustedOrigin = defaultHelper();
        this.identity = CodeIdentity.load();
        if (identity == null) {
            throw new CustodyException("SIGNER_UNTRUSTED", "NO_NATIVE_IDENTITY");
        }
    }

    /** {@code <this helper bundle>/../byx-signer-helper-qa.app}, derived from the sealed jar this class was loaded from. Never PATH, never configuration. */
    public static Path defaultHelper() throws CustodyException {
        try {
            Path jar = Path.of(CustodyClient.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            Path app = jar.getParent() == null ? null : jar.getParent().getParent() == null ? null : jar.getParent().getParent().getParent();
            if (app == null || !app.getFileName().toString().endsWith(".app") || !jar.getParent().getFileName().toString().equals("app")
                    || !jar.getParent().getParent().getFileName().toString().equals("Contents")) {
                throw new CustodyException("SIGNER_UNTRUSTED", "NOT_RUNNING_FROM_A_BUNDLE");
            }
            return app.getParent().resolve(HELPER_APP);
        } catch (java.net.URISyntaxException | IOException | RuntimeException e) {
            throw new CustodyException("SIGNER_UNTRUSTED", "NOT_RUNNING_FROM_A_BUNDLE");
        }
    }

    /** Synthetic signing through the custody helper; every verification of V2.1S is preserved. */
    public SignedTx sign(TxSignRequest request, byte[] publicKey) throws TxSignerException {
        try {
            var material = CosmosBankSend.material(request, publicKey);
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("request", material.request());
            Reply r = exchange("sign", request.key().keyId(), null, outer);
            if (!"SIGNED".equals(r.status()) || r.response() == null) {
                throw new TxSignerException();
            }
            return SignerClient.verifySigned(r.response(), request, material, publicKey);
        } catch (CustodyException | IOException | RuntimeException e) {
            throw new TxSignerException();
        }
    }

    /** provision | lookup | delete | count | cleanup (QA operations on the synthetic namespace only). */
    public Reply call(String op, String keyRef, String namespace) throws CustodyException {
        try {
            return exchange(op, keyRef, namespace, Map.of());
        } catch (IOException e) {
            throw new CustodyException("SIGNING_FAILED", "IO");
        }
    }

    private Reply exchange(String op, String keyRef, String namespace, Map<String, Object> extra) throws CustodyException, IOException {
        verifyHelperOnDisk();
        Path exe = launchApp.resolve("Contents").resolve("MacOS").resolve(HELPER_EXE);
        String invocation = HEX.formatHex(randomBytes(16));
        var builder = new ProcessBuilder(exe.toString());
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Future<Reply> work = pool.submit(() -> converse(child, invocation, op, keyRef, namespace, extra));
        try {
            return work.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof CustodyException c) {
                throw c;
            }
            throw new CustodyException("SIGNING_FAILED", "EXCHANGE:" + e.getCause().getClass().getSimpleName());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new CustodyException("SIGNING_FAILED", "TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustodyException("SIGNING_FAILED", "INTERRUPTED");
        } finally {
            work.cancel(true);
            child.destroyForcibly();
            pool.shutdownNow();
        }
    }

    private Reply converse(Process child, String invocation, String op, String keyRef, String namespace, Map<String, Object> extra) throws Exception {
        // 1. hand over the (public, non-secret) invocation id and read the READY frame
        try (var stdin = child.getOutputStream()) {
            stdin.write((invocation + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        JsonNode ready;
        try (var stdout = new DataInputStream(child.getInputStream())) {
            ready = readFrame(stdout);
            if (!ready.path("ready").asBoolean(false)) {
                throw new CustodyException("SIGNING_FAILED", "NOT_READY");
            }
            // descriptor / environment hygiene reported by the helper AND checked from the outside by the kernel
            if (ready.path("fds").asInt(-1) != 0 || ready.path("socketFds").asInt(-1) != 0 || ready.path("envCount").asInt(-1) != 0
                    || ready.path("pid").asLong(-1) != child.pid()) {
                throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_HYGIENE");
            }
            // the kernel's record of the launch block also carries the Apple vector (executable_path=…); what matters is that no injection/loader variable is there.
            // The helper itself reported envCount=0 (its own os.Environ()), which is the strict "empty environment" proof.
            List<String> env = identity.launchEnvironment(child.pid());
            if (env == null) {
                throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_ENVIRONMENT_UNREADABLE");
            }
            for (String e : env) {
                if (BANNED_ENV.stream().anyMatch(e::startsWith)) {
                    throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_ENVIRONMENT");
                }
            }
            Path socket = Path.of(ready.path("path").asText(""));
            checkPrivateEndpoint(socket);
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(socket));
                // 2. the other endpoint must be the very child we spawned, running the genuine sealed signer code from the approved origin
                var peer = identity.checkPeer(ch, CodeIdentity.requirement(SIGNER_ID, identity.selfTeamId()));
                if (peer.verdict() == CodeIdentity.Verdict.UNAVAILABLE) {
                    // A helper that REFUSES its caller answers and exits at once, so its endpoint token can be gone before we read it. The helper is the on-disk bundle we validated
                    // and the child we spawned; a plain refusal carries no data and nothing was sent to it, so it is reported as such. Anything else stays an untrusted peer.
                    try {
                        JsonNode refusal = readFrame(new DataInputStream(Channels.newInputStream(ch)));
                        if ("reply".equals(refusal.path("type").asText()) && "CALLER_UNTRUSTED".equals(refusal.path("status").asText())) {
                            return parseReply(refusal);
                        }
                    } catch (IOException ignored) {
                        // fall through
                    }
                    throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_PEER_UNAVAILABLE");
                }
                if (peer.verdict() != CodeIdentity.Verdict.OK || peer.pid() != child.pid() || peer.codePath() == null
                        || !Path.of(peer.codePath()).toRealPath().equals(trustedOrigin.toRealPath())) {
                    throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_PEER_" + peer.verdict());
                }
                var in = new DataInputStream(Channels.newInputStream(ch));
                var out = new DataOutputStream(Channels.newOutputStream(ch));
                JsonNode first = readFrame(in);
                if ("reply".equals(first.path("type").asText()) && "CALLER_UNTRUSTED".equals(first.path("status").asText())) {
                    return parseReply(first); // the helper refused US (before any Keychain access)
                }
                if (first.path("protocolVersion").asInt() != 2 || !"challenge".equals(first.path("type").asText()) || !invocation.equals(first.path("invocationId").asText())
                        || !first.path("challenge").asText().matches("[0-9a-f]{64}")) {
                    throw new CustodyException("SIGNING_FAILED", "CHALLENGE");
                }
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("protocolVersion", 2);
                req.put("type", "request");
                req.put("invocationId", invocation);
                req.put("challenge", first.path("challenge").asText());
                req.put("op", op);
                req.put("keyRef", keyRef == null ? "" : keyRef);
                if (namespace != null) {
                    req.put("namespace", namespace);
                }
                req.putAll(extra);
                byte[] bytes = JSON.writeValueAsBytes(req);
                if (bytes.length == 0 || bytes.length > MAX_FRAME) {
                    throw new CustodyException("SIGNING_FAILED", "REQUEST_SIZE");
                }
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
                Reply reply = parseReply(readFrame(in));
                if (!child.waitFor(3, TimeUnit.SECONDS)) {
                    throw new CustodyException("SIGNING_FAILED", "HELPER_DID_NOT_EXIT");
                }
                return reply;
            }
        }
    }

    private static Reply parseReply(JsonNode n) throws CustodyException {
        if (!n.isObject() || n.path("protocolVersion").asInt() != 2 || !"reply".equals(n.path("type").asText()) || !n.path("status").isTextual()) {
            throw new CustodyException("SIGNING_FAILED", "REPLY");
        }
        return new Reply(n.path("status").asText(), n.path("keychainCalls").asInt(-1), n.path("publicKey").asText(null), n.path("address").asText(null), n.path("count").asInt(0),
                n.has("response") ? n.get("response") : null);
    }

    private static JsonNode readFrame(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n <= 0 || n > MAX_FRAME) {
            throw new IOException("frame");
        }
        byte[] b = in.readNBytes(n);
        if (b.length != n) {
            throw new IOException("frame");
        }
        return JSON.readTree(b);
    }

    /** Static check before executing anything: real path, no symlinks, exact origin, Apple chain + Team + identifier + strict nested seal. */
    private void verifyHelperOnDisk() throws CustodyException {
        try {
            if (!launchApp.isAbsolute() || !launchApp.getFileName().toString().equals(HELPER_APP)) {
                throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_PATH");
            }
            for (Path p = launchApp; p != null; p = p.getParent()) {
                if (Files.isSymbolicLink(p)) {
                    throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_SYMLINK");
                }
            }
            if (!launchApp.toRealPath().equals(trustedOrigin.toRealPath())) {
                throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_ORIGIN");
            }
        } catch (IOException e) {
            throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_MISSING");
        }
        String team = identity.selfTeamId();
        if (team == null) {
            throw new CustodyException("SIGNER_UNTRUSTED", "NO_TEAM");
        }
        CodeIdentity.Verdict v = identity.checkBundle(launchApp.toString(), CodeIdentity.requirement(SIGNER_ID, team));
        if (v != CodeIdentity.Verdict.OK) {
            throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_BUNDLE_" + v);
        }
    }

    /** The helper's endpoint directory must be ours, private (0700) and not a symlink; the kernel token check is what actually authenticates the peer. */
    private static void checkPrivateEndpoint(Path socket) throws CustodyException {
        try {
            Path dir = socket.getParent();
            if (dir == null || Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).getName().equals(ProcessHandle.current().info().user().orElse("?"))) {
                throw new CustodyException("SIGNER_UNTRUSTED", "ENDPOINT_DIR");
            }
            var perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.GROUP_EXECUTE)
                    || perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.OTHERS_WRITE) || perms.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                throw new CustodyException("SIGNER_UNTRUSTED", "ENDPOINT_MODE");
            }
        } catch (IOException e) {
            throw new CustodyException("SIGNER_UNTRUSTED", "ENDPOINT_DIR");
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
