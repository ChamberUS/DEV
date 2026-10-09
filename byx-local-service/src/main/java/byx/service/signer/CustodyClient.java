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
    static final long CALL_TIMEOUT_MS = 12_000;
    private static final List<String> BANNED_ENV = List.of("JAVA_TOOL_OPTIONS=", "_JAVA_OPTIONS=", "JDK_JAVA_OPTIONS=", "CLASSPATH=", "DYLD_", "LD_", "JAVA_OPTIONS=", "HOME=", "PATH=", "TMPDIR=");
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
    public record Reply(String status, int keychainCalls, String publicKey, String address, int count, JsonNode response, JsonNode lifecycle) {
        public Reply(String status,int keychainCalls,String publicKey,String address,int count,JsonNode response) {
            this(status,keychainCalls,publicKey,address,count,response,null);
        }
    }

    public record LifecycleRequest(WalletCatalog.Binding binding,String operationId,String requestDigest,long expectedVersion,
            String publicKey,String address,int offset,String snapshotDigest,String probePoint) {
        public LifecycleRequest(WalletCatalog.Binding binding,String operationId,String requestDigest,long expectedVersion,
                String publicKey,String address,int offset,String snapshotDigest) {
            this(binding,operationId,requestDigest,expectedVersion,publicKey,address,offset,snapshotDigest,"");
        }
    }

    Reply lifecycle(String op,LifecycleRequest request,TxSignRequest sign) throws CustodyException {
        var extra=new LinkedHashMap<String,Object>();
        extra.put("lifecycle",request);
        if(sign!=null) extra.put("request",CosmosBankSend.material(sign,HEX.parseHex(request.publicKey())).request());
        try { return exchange(op,request.binding()==null?"":request.binding().signingKeyRef(),null,extra); }
        catch(IOException e) { throw new CustodyException("TIMEOUT_UNKNOWN_RESULT","EXCHANGE_UNCERTAIN"); }
    }


    private final Path launchApp;
    private final Path trustedOrigin;
    private final CodeIdentity identity;
    private java.util.function.LongConsumer childObserver = pid -> { };
    private java.util.function.Consumer<JsonNode> probeObserver;
    private java.util.function.UnaryOperator<byte[]> requestProbe;
    private java.util.function.Consumer<DataOutputStream> afterFlushProbe;
    private java.util.function.Consumer<JsonNode> replyProbe;

    void observeChild(java.util.function.LongConsumer observer) { this.childObserver = observer; }
    void observeProbe(java.util.function.Consumer<JsonNode> observer) { this.probeObserver = observer; }
    void observeWire(java.util.function.UnaryOperator<byte[]> request,java.util.function.Consumer<DataOutputStream> afterFlush,java.util.function.Consumer<JsonNode> reply) {
        requestProbe=request;afterFlushProbe=afterFlush;replyProbe=reply;
    }

    CustodyAuthority authority() throws CustodyException { return authority(CustodyAuthority.Deadline.operation()); }

    private CustodyAuthority authority(CustodyAuthority.Deadline deadline) throws CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("client.authority")) {
        verifyServiceOrigin(deadline);
        deadline.require();
        return CustodyAuthority.current(identity, trustedOrigin, deadline);



        }
    }

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
        try (var timing = byx.service.identity.FencingTiming.phase("client.defaultHelper")) {
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
            return SignedResponseVerifier.verifySigned(r.response(), request, material, publicKey);
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
        try (var timing = byx.service.identity.FencingTiming.phase("client.exchange")) {
        CustodyAuthority.Deadline deadline = CustodyAuthority.Deadline.operation();
        verifyHelperOnDisk();
        deadline.require();
        if (!identity.selfSatisfies(CodeIdentity.requirement("com.buynnex.byx.service", identity.selfTeamId()))) {
            return new Reply("CALLER_UNTRUSTED", 0, null, null, 0, null);

    }
        CustodyAuthority authority = authority(deadline);
        synchronized (authority) {
            deadline.require();
            return fencedExchange(authority, op, keyRef, namespace, extra, deadline);
        }


        }
    }

    private Reply fencedExchange(CustodyAuthority authority, String op, String keyRef, String namespace, Map<String, Object> extra, CustodyAuthority.Deadline deadline) throws CustodyException, IOException {
        try (var timing = byx.service.identity.FencingTiming.phase("client.fencedExchange")) {
        deadline.exchangeWait();
        CustodyAuthority.Binding binding = authority.begin();
        Path exe = launchApp.resolve("Contents").resolve("MacOS").resolve(HELPER_EXE);
        String invocation = binding.operationId;
        var builder = new ProcessBuilder(exe.toString());
        builder.environment().clear();
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        CustodyAuthority.Deadline bootstrapDeadline = deadline.bootstrap();
        var readyObserved = new java.util.concurrent.CompletableFuture<Void>();
        Process child;
        try (var launchTiming = byx.service.identity.FencingTiming.phase("client.subprocessLaunch")) {
            child = builder.start();
        }
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        Future<Reply> work = null;
        boolean interrupted = false;
        boolean resultKnown = false;
        var dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            authority.attach(child.pid());
            childObserver.accept(child.pid());
            deadline.exchangeWait();
            work = pool.submit(() -> {
                try { return converse(child, invocation, op, keyRef, namespace, extra, authority, binding, dispatched, deadline, readyObserved); }
                catch (Exception | Error e) { readyObserved.completeExceptionally(e); throw e; }
            });
            try (var readyTiming = byx.service.identity.FencingTiming.phase("client.bootstrapWait")) {
                long readyBudget = bootstrapDeadline.remaining();
                if (readyBudget == 0) { throw new java.util.concurrent.TimeoutException(); }
                readyObserved.get(readyBudget, TimeUnit.NANOSECONDS);
            }
            Reply result;
            try (var waitTiming = byx.service.identity.FencingTiming.phase("client.exchangeWait")) {
                result = work.get(deadline.exchangeWait(), TimeUnit.NANOSECONDS);
            }
            resultKnown = true;
            return result;
        } catch (java.util.concurrent.ExecutionException e) {
            if (!dispatched.get() && e.getCause() instanceof CustodyException c) {
                throw c;
            }
            throw new CustodyException("TIMEOUT_UNKNOWN_RESULT", "EXCHANGE_UNCERTAIN");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new CustodyException("TIMEOUT_UNKNOWN_RESULT", "TIMEOUT_UNKNOWN_RESULT");
        } catch (InterruptedException e) {
            interrupted = true;
            throw new CustodyException("TIMEOUT_UNKNOWN_RESULT", "CANCELLED_UNKNOWN_RESULT");
        } finally {
            if (work != null) { work.cancel(true); }
            pool.shutdownNow();
            // Cancellation must not skip verification because the caller thread is interrupted.
            interrupted |= Thread.interrupted();
            try { authority.finish(!resultKnown, deadline); }
            finally { if (interrupted) { Thread.currentThread().interrupt(); } }
        }



        }
    }

    private Reply converse(Process child, String invocation, String op, String keyRef, String namespace, Map<String, Object> extra,
            CustodyAuthority authority, CustodyAuthority.Binding binding, java.util.concurrent.atomic.AtomicBoolean dispatched, CustodyAuthority.Deadline deadline, java.util.concurrent.CompletableFuture<Void> readyObserved) throws Exception {
        try (var timing = byx.service.identity.FencingTiming.phase("client.converse")) {
        // 1. hand over the (public, non-secret) invocation id and read the READY frame
        try (var stdin = child.getOutputStream()) {
            stdin.write((invocation + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        JsonNode ready;
        try (var stdout = new DataInputStream(child.getInputStream())) {
            try (var phase = byx.service.identity.FencingTiming.phase("client.readReadyFrame")) {
                ready = readFrame(stdout);
            }
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
            readyObserved.complete(null);
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
                authority.peer(peer.pid(), peer.pidVersion());
                var in = new DataInputStream(Channels.newInputStream(ch));
                var out = new DataOutputStream(Channels.newOutputStream(ch));
                JsonNode first = readFrame(in);
                if ("reply".equals(first.path("type").asText()) && "CALLER_UNTRUSTED".equals(first.path("status").asText())) {
                    return parseReply(first); // the helper refused US (before any Keychain access)
                }
                if (first.path("protocolVersion").asInt() != 3 || !"challenge".equals(first.path("type").asText()) || !invocation.equals(first.path("invocationId").asText())
                        || !first.path("challenge").asText().matches("[0-9a-f]{64}")) {
                    throw new CustodyException("SIGNING_FAILED", "CHALLENGE");
                }
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("protocolVersion", 3);
                req.put("type", "request");
                req.put("invocationId", invocation);
                req.put("challenge", first.path("challenge").asText());
                req.put("op", op);
                req.put("keyRef", keyRef == null ? "" : keyRef);
                if (namespace != null) {
                    req.put("namespace", namespace);
                }
                req.putAll(extra);
                req.put("generation", binding.generation);
                req.put("operationId", binding.operationId);
                String digest = HEX.formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(req)));
                req.put("requestDigest", digest);
                byte[] bytes = JSON.writeValueAsBytes(req);
                if(requestProbe!=null) bytes=requestProbe.apply(bytes);
                if (bytes.length == 0 || bytes.length > MAX_FRAME) {
                    throw new CustodyException("SIGNING_FAILED", "REQUEST_SIZE");
                }
                // No request may be dispatched after its budget or cancellation boundary.
                deadline.exchangeWait();
                if (Thread.currentThread().isInterrupted()) { throw CustodyAuthority.failure(); }
                dispatched.set(true);
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
                if(afterFlushProbe!=null) afterFlushProbe.accept(out);
                if(extra.containsKey("lifecycle")) ch.shutdownOutput();
                if (probeObserver != null && (!(extra.get("lifecycle") instanceof LifecycleRequest lifecycle)
                        || lifecycle.probePoint()!=null && !lifecycle.probePoint().isEmpty())) {
                    probeObserver.accept(readFrame(stdout));
                }
                JsonNode response = readFrame(in);
                if(replyProbe!=null) replyProbe.accept(response);
                if(!op.equals(response.path("op").asText()) || !invocation.equals(response.path("invocationId").asText())
                        || !first.path("challenge").asText().equals(response.path("challenge").asText())) {
                    throw new CustodyException("TIMEOUT_UNKNOWN_RESULT","REPLY_BINDING");
                }
                binding.accept(response.path("generation").asText(), response.path("operationId").asText(), digest, response.path("requestDigest").asText());
                Reply reply = parseReply(response);
                long exitWait = Math.min(CustodyAuthority.Deadline.EXIT_PROOF_NANOS, deadline.remaining());
                if (exitWait == 0 || !child.waitFor(exitWait, TimeUnit.NANOSECONDS)) {
                    throw new CustodyException("SIGNING_FAILED", "HELPER_DID_NOT_EXIT");
                }
                return reply;

    }
        }



        }
    }

    private static Reply parseReply(JsonNode n) throws CustodyException {
        var fields=java.util.Set.of("protocolVersion","type","status","keychainCalls","publicKey","address","count","response","generation","operationId","requestDigest","op","invocationId","challenge","lifecycle");
        var statuses=java.util.Set.of("CALLER_UNTRUSTED","SIGNER_SELF_UNTRUSTED","KEY_NOT_FOUND","ALREADY_EXISTS","PROVISIONED","SIGNED","FOUND","DELETED","COUNT","SIGNING_FAILED","NAMESPACE_REFUSED",
                "PREPARING","LIVE","REVOKED","KEY_MISMATCH","KEY_REVOKED","KEY_CORRUPT","KEYCHAIN_UNAVAILABLE","INVENTORY_INCOMPLETE","INVENTORY");
        if (!n.isObject() || !n.path("protocolVersion").isIntegralNumber() || !n.path("protocolVersion").canConvertToInt() || n.path("protocolVersion").asInt() != 3 || !n.path("type").isTextual() || !"reply".equals(n.path("type").asText())
                || !n.path("status").isTextual() || !statuses.contains(n.path("status").asText()) || !n.path("keychainCalls").isIntegralNumber() || !n.path("keychainCalls").canConvertToInt() || n.path("keychainCalls").asLong()<0) {
            throw new CustodyException("SIGNING_FAILED", "REPLY");
        }
        for(var it=n.fieldNames();it.hasNext();) if(!fields.contains(it.next())) throw new CustodyException("SIGNING_FAILED","REPLY_SCHEMA");
        for(String f:List.of("publicKey","address","generation","operationId","requestDigest","op","invocationId","challenge")) if(n.has(f) && !n.get(f).isTextual()) throw new CustodyException("SIGNING_FAILED","REPLY_SCHEMA");
        if(n.has("count") && (!n.get("count").isIntegralNumber() || n.get("count").asLong()<0 || !n.get("count").canConvertToInt())) throw new CustodyException("SIGNING_FAILED","REPLY_SCHEMA");
        if(n.path("op").isTextual()) {
            String status=n.get("status").asText();
            var successes=java.util.Set.of("PROVISIONED","ALREADY_EXISTS","SIGNED","FOUND","DELETED","COUNT","PREPARING","LIVE","REVOKED","INVENTORY");
            var expected=switch(n.get("op").asText()) {
                case "provision" -> java.util.Set.of("PROVISIONED","ALREADY_EXISTS");
                case "sign","signBound" -> java.util.Set.of("SIGNED");
                case "lookup" -> java.util.Set.of("FOUND");
                case "delete","cleanup","purgeQaBound" -> java.util.Set.of("DELETED");
                case "count","probeBeforeMutation","probeAfterMutation" -> java.util.Set.of("COUNT");
                case "provisionBound" -> java.util.Set.of("LIVE");
                case "inspectBound" -> java.util.Set.of("PREPARING","LIVE","REVOKED");
                case "revokeDeleteBound" -> java.util.Set.of("REVOKED");
                case "inventoryPage" -> java.util.Set.of("INVENTORY");
                default -> java.util.Set.<String>of();
            };
            if(successes.contains(status) && !expected.contains(status)) throw new CustodyException("SIGNING_FAILED","REPLY_OPERATION");
            if(expected.contains(status) && java.util.Set.of("provisionBound","inspectBound","revokeDeleteBound","signBound","inventoryPage").contains(n.get("op").asText())
                    && !n.path("lifecycle").isObject()) throw new CustodyException("SIGNING_FAILED","REPLY_SCHEMA");
        }
        return new Reply(n.path("status").asText(), n.path("keychainCalls").asInt(-1), n.path("publicKey").asText(null), n.path("address").asText(null), n.path("count").asInt(0),
                n.has("response") ? n.get("response") : null,n.has("lifecycle") ? n.get("lifecycle") : null);
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
    public void verifyAvailability() throws CustodyException {
        verifyHelperOnDisk();
        if (!Files.isExecutable(launchApp.resolve("Contents/MacOS/byx-signer-helper-qa")))
            throw new CustodyException("SIGNER_UNAVAILABLE", "HELPER_NOT_EXECUTABLE");
    }

    private void verifyHelperOnDisk() throws CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("client.verifyHelperOnDisk")) {
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
        Path executable = launchApp.resolve("Contents/MacOS").resolve(HELPER_EXE);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(executable) || Files.isSymbolicLink(executable.getParent()))
            throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_EXECUTABLE");
        String team = identity.selfTeamId();
        if (team == null) {
            throw new CustodyException("SIGNER_UNTRUSTED", "NO_TEAM");
        }
        CodeIdentity.Verdict v = identity.checkBundle(launchApp.toString(), CodeIdentity.requirement(SIGNER_ID, team));
        if (v != CodeIdentity.Verdict.OK) {
            throw new CustodyException("SIGNER_UNTRUSTED", "HELPER_BUNDLE_" + v);
        }


        }
    }

    /** Bind the loaded JAR to this exact live signed Service, its sealed parent app and approved install metadata. */
    private void verifyServiceOrigin(CustodyAuthority.Deadline deadline) throws CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("client.verifyServiceOrigin")) {
        try {
            Path source = Path.of(CustodyClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path ownApp = source.getParent().getParent().getParent();
            Path helpers = ownApp.getParent();
            Path parent = helpers.getParent().getParent();
            if (!source.getFileName().toString().equals("byx-local-service-0.1.0.jar")
                    || !source.getParent().getFileName().toString().equals("app")
                    || !source.getParent().getParent().getFileName().toString().equals("Contents")
                    || !helpers.getFileName().toString().equals("Helpers")
                    || !helpers.getParent().getFileName().toString().equals("Contents")
                    || !parent.getFileName().toString().endsWith(".app")) throw new IOException();
            requireInstallPath(source, identity.effectiveUid());
            requireInstallPath(launchApp.resolve("Contents/MacOS").resolve(HELPER_EXE), identity.effectiveUid());
            var self = identity.instance(ProcessHandle.current().pid());
            String team = identity.selfTeamId();
            if (self == null || !verifyBoth(
                    () -> {
                        try (var phase = byx.service.identity.FencingTiming.phase("client.liveServiceIdentityAndSeal")) {
                            return identity.checkInstance(self, CodeIdentity.requirement("com.buynnex.byx.service", team), ownApp.toString()) == CodeIdentity.Verdict.OK;
                        }
                    },
                    () -> {
                        try (var phase = byx.service.identity.FencingTiming.phase("client.parentIdentityAndNestedSeal")) {
                            return identity.checkBundle(parent.toString(), CodeIdentity.requirement("com.buynnex.byx", team)) == CodeIdentity.Verdict.OK;
                        }
                    }, deadline)) throw new IOException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CustodyAuthority.failure();
        } catch (java.util.concurrent.TimeoutException e) {
            throw CustodyAuthority.failure();
        } catch (java.net.URISyntaxException | IOException | java.util.concurrent.ExecutionException | RuntimeException e) {
            throw new CustodyException("SIGNER_UNTRUSTED", "SERVICE_ORIGIN_OR_INSTALL");
        }



        }
    }

    /** Independent fresh checks; neither result authorizes anything until BOTH finish successfully. */
    static boolean verifyBoth(java.util.concurrent.Callable<Boolean> liveIdentity,
            java.util.concurrent.Callable<Boolean> enclosingSeal, CustodyAuthority.Deadline deadline)
            throws InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException, CustodyException {
        deadline.require();
        try (var checks = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> live = checks.submit(liveIdentity);
            Future<Boolean> parent = checks.submit(enclosingSeal);
            try {
                long liveBudget = deadline.remaining();
                if (liveBudget == 0) { throw CustodyAuthority.failure(); }
                boolean callerOk = Boolean.TRUE.equals(live.get(liveBudget, TimeUnit.NANOSECONDS));
                deadline.require();
                long parentBudget = deadline.remaining();
                if (parentBudget == 0) { throw CustodyAuthority.failure(); }
                boolean parentOk = Boolean.TRUE.equals(parent.get(parentBudget, TimeUnit.NANOSECONDS));
                deadline.require();
                return callerOk && parentOk;
            } finally {
                live.cancel(true);
                parent.cancel(true);
            }
        }
    }

    /** No symlink components, foreign owners, group/other writable ancestry, or executable substitution. Root-owned sticky temp ancestry is allowed. */
    static void requireInstallPath(Path file, int uid) throws IOException {
        try (var timing = byx.service.identity.FencingTiming.phase("client.requireInstallPath")) {
        if (!file.isAbsolute() || !file.normalize().equals(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        for (Path p = file; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException();
            long owner = ((Number) Files.getAttribute(p, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
            int mode = ((Number) Files.getAttribute(p, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
            boolean systemSticky = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && owner == 0 && (mode & 01000) != 0;
            if (owner != 0 && owner != uid || (mode & 0022) != 0 && !systemSticky) throw new IOException();
        }



        }
    }

    /** The helper's endpoint directory must be ours, private (0700) and not a symlink; the kernel token check is what actually authenticates the peer. */
    private static void checkPrivateEndpoint(Path socket) throws CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("client.checkPrivateEndpoint")) {
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
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }
}
