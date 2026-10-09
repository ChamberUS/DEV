package byx.service.signer;

import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.TxSignRequest;
import byx.service.tx.TxPorts.TxSignerException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only V2.1S stdio harness. Never included in a main/packaged JAR; custody is the sole runtime transport. */
final class LegacyStdioSignerFixture {
    public static final LegacyStdioSignerFixture UNAVAILABLE = new LegacyStdioSignerFixture();
    private static final int MAX_FRAME = 8192;
    private static final Set<String> RESPONSE = Set.of("protocolVersion", "requestId", "status", "publicKey", "signature", "txRaw", "txHash", "bindingDigest");
    private final Path binary;
    private final String expectedHash;
    private final byte[] publicKey;
    private final List<String> arguments;
    private final Duration timeout;
    private final Set<String> attempted = ConcurrentHashMap.newKeySet();
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private LegacyStdioSignerFixture() { binary = null; expectedHash = null; publicKey = null; arguments = List.of(); timeout = Duration.ZERO; }

    LegacyStdioSignerFixture(Path binary, String expectedHash, byte[] publicKey, List<String> fixedArguments, Duration timeout) {
        if (!binary.isAbsolute() || !binary.normalize().equals(binary) || expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")
                || timeout.toMillis() < 50 || timeout.toMillis() > 5000) throw new IllegalArgumentException("SIGNING_FAILED");
        this.binary = binary; this.expectedHash = expectedHash; this.publicKey = publicKey.clone();
        this.arguments = List.copyOf(fixedArguments); this.timeout = timeout;
    }

    public boolean available() { return binary != null; }

    public SignedTx sign(TxSignRequest request) throws TxSignerException {
        if (!available() || request == null || attempted.size() >= 1024 || !attempted.add(request.quote().id().value())) throw new TxSignerException();
        var child = new AtomicReference<Process>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> exchange(request, child));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new TxSignerException();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new TxSignerException();
        } finally {
            Process p = child.get();
            if (p != null) {
                p.destroyForcibly();
                try { p.getInputStream().close(); p.getOutputStream().close(); p.getErrorStream().close(); } catch (IOException ignored) { }
            }
            future.cancel(true); executor.shutdownNow();
        }
    }

    private SignedTx exchange(TxSignRequest request, AtomicReference<Process> child) throws Exception {
        verifyIdentity();
        var material = CosmosBankSend.material(request, publicKey);
        byte[] encoded = json.writeValueAsBytes(material.request());
        if (encoded.length == 0 || encoded.length > MAX_FRAME || Thread.currentThread().isInterrupted()) throw new IOException("SIGNING_FAILED");
        var argv = new java.util.ArrayList<String>(); argv.add(binary.toString()); argv.addAll(arguments);
        var builder = new ProcessBuilder(argv); builder.environment().clear(); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = builder.start(); child.set(p);
        if (Thread.currentThread().isInterrupted()) { p.destroyForcibly(); throw new IOException("SIGNING_FAILED"); }
        verifyIdentity();
        try (var out = new DataOutputStream(p.getOutputStream())) { out.writeInt(encoded.length); out.write(encoded); }
        byte[] response;
        try (var in = new DataInputStream(p.getInputStream())) {
            int n = in.readInt(); if (n <= 0 || n > MAX_FRAME) throw new IOException("SIGNING_FAILED");
            response = in.readNBytes(n); if (response.length != n || in.read() != -1) throw new IOException("SIGNING_FAILED");
        }
        if (p.waitFor() != 0) throw new IOException("SIGNING_FAILED");
        JsonNode root;
        try (var parser = json.getFactory().createParser(response)) {
            root = json.readTree(parser); if (parser.nextToken() != null) throw new IOException("SIGNING_FAILED");
        }
        return SignedResponseVerifier.verifySigned(root, request, material, publicKey);
    }

    private static int effectiveUid() throws IOException {
        var identity = byx.service.identity.CodeIdentity.load();
        if (identity == null) throw new IOException("SIGNING_FAILED");
        return identity.effectiveUid();
    }

    private void verifyIdentity() throws IOException {
        for (Path p = binary; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("SIGNING_FAILED");
        if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(binary) || Files.size(binary) > 16 * 1024 * 1024
                || ((Number) Files.getAttribute(binary, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue() != effectiveUid()
                || !Files.getOwner(binary).equals(Files.getOwner(binary.getParent()))) throw new IOException("SIGNING_FAILED");
        for (Path p : List.of(binary, binary.getParent())) {
            var perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
            if (perms.contains(PosixFilePermission.GROUP_WRITE) || perms.contains(PosixFilePermission.OTHERS_WRITE)) throw new IOException("SIGNING_FAILED");
        }
        if (!CosmosBankSend.hash(Files.readAllBytes(binary)).equals(expectedHash)) throw new IOException("SIGNING_FAILED");
    }
}
