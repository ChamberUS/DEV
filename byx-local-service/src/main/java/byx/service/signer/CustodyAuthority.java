package byx.service.signer;

import byx.service.identity.CodeIdentity;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** QA-only authority: OS-held exclusion, external restart quiescence and one exact child at a time. */
final class CustodyAuthority {
    static final String UNPROVEN = "CUSTODY_QUIESCENCE_UNPROVEN";
    private static CustodyAuthority singleton;
    private final ProcessControl control;
    private final Path origin;
    private final Lease lease;
    private final String generation = randomId();
    private Object active;
    private boolean blocked;
    private boolean previousResultUnknown;
    private Binding pending;

    /** One monotonic operation deadline; nested work never renews the caller's budget. */
    static final class Deadline {
        static final long EXIT_PROOF_NANOS = TimeUnit.SECONDS.toNanos(3);
        private final LongSupplier clock;
        private final long end;

        private Deadline(LongSupplier clock, long end) { this.clock = clock; this.end = end; }
        static Deadline operation() { return after(System::nanoTime, TimeUnit.MILLISECONDS.toNanos(CustodyClient.CALL_TIMEOUT_MS)); }
        static Deadline after(LongSupplier clock, long nanos) {
            if (nanos <= 0) { throw new IllegalArgumentException("INVALID_DEADLINE"); }
            return new Deadline(clock, clock.getAsLong() + nanos);
        }
        long remaining() { return Math.max(0, end - clock.getAsLong()); }
        void require() throws CustodyClient.CustodyException { if (remaining() == 0) { throw CustodyAuthority.failure(); } }
        long exchangeWait() throws CustodyClient.CustodyException {
            long available = remaining() - EXIT_PROOF_NANOS;
            if (available <= 0) { throw CustodyAuthority.failure(); }
            return available;
        }
        Deadline bootstrap() throws CustodyClient.CustodyException {
            long available = exchangeWait();
            long now = clock.getAsLong();
            long remainingForRequest = Math.max(0, end - now - EXIT_PROOF_NANOS);
            long slice = Math.min(TimeUnit.MILLISECONDS.toNanos(CustodyClient.CALL_TIMEOUT_MS / 2), Math.min(available, remainingForRequest));
            if (slice == 0) { throw CustodyAuthority.failure(); }
            return new Deadline(clock, now + slice);
        }
        Deadline exitProof() throws CustodyClient.CustodyException {
            require();
            long now = clock.getAsLong();
            return new Deadline(clock, now + Math.min(EXIT_PROOF_NANOS, Math.max(0, end - now)));
        }
    }

    interface ProcessControl {
        List<Object> discover() throws Exception;
        Object identify(long pid) throws Exception;
        boolean trusted(Object instance) throws Exception;
        boolean gone(Object instance) throws Exception;
        boolean terminate(Object instance) throws Exception;
        default boolean terminate(Object instance, Deadline deadline) throws Exception {
            deadline.require();
            return terminate(instance);
        }
        long pid(Object instance);
        long version(Object instance);
    }

    static CustodyAuthority current(CodeIdentity identity, Path origin) throws CustodyClient.CustodyException {
        return current(identity, origin, Deadline.operation());
    }

    static synchronized CustodyAuthority current(CodeIdentity identity, Path origin, Deadline deadline) throws CustodyClient.CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.current")) {
        deadline.require();
        if (!identity.selfSatisfies(CodeIdentity.requirement("com.buynnex.byx.service", identity.selfTeamId()))) {
            throw new CustodyClient.CustodyException("CALLER_UNTRUSTED", "CALLER_UNTRUSTED");
        }
        try {
            Path real = origin.toRealPath();
            if (singleton != null) {
                if (!singleton.origin.equals(real)) { throw new IOException("AUTHORITY_ORIGIN_MISMATCH"); }
                deadline.require();
                return singleton;
            }
            Path base = Path.of(identity.custodyTempRoot()).toRealPath();
            requirePrivate(base, true);
            Lease lease = Lease.acquire(base.resolve("byx-custody-qa-authority"));
            try {
                var authority = new CustodyAuthority(new NativeControl(identity, real), real, lease);
                authority.startup(deadline);
                singleton = authority;
                Runtime.getRuntime().addShutdownHook(new Thread(authority::shutdown, "custody-quiescence"));
                return authority;
            } catch (Exception e) {
                lease.close();
                throw e;
            }
        } catch (Exception e) {
            throw failure();
        }








        }
    }

    CustodyAuthority(ProcessControl control, Path origin, Lease lease) {
        this.control = control;
        this.origin = origin;
        this.lease = lease;
    }

    synchronized void startup() throws CustodyClient.CustodyException { startup(Deadline.operation()); }

    synchronized void startup(Deadline deadline) throws CustodyClient.CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.startup")) {
        try {
            deadline.require();
            if (lease != null) { previousResultUnknown = lease.readUnknown(); }
            deadline.require();
            var discovered = control.discover();
            deadline.require();
            for (Object instance : discovered) {
                deadline.require();
                if (!control.gone(instance)) {
                    previousResultUnknown = true;
                    fence(instance, deadline);
                }
            }
            deadline.require();
            if (lease != null) { lease.record("QUIESCENT", generation, "", previousResultUnknown); }
            deadline.require();
        } catch (Exception e) {
            blocked = true;
            throw failure();
        }



        }
    }

    synchronized Binding begin() throws CustodyClient.CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.begin")) {
        if (blocked || active != null) { throw failure(); }
        try {
            if (lease != null) { lease.verify(); }
        } catch (IOException e) {
            blocked = true;
            throw failure();
        }
        pending = new Binding(generation, randomId());
        try {
            if (lease != null) { lease.record("IN_FLIGHT", generation, pending.operationId, true); }
        } catch (IOException e) { blocked = true; throw failure(); }
        return pending;



        }
    }

    synchronized void attach(long pid) throws CustodyClient.CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.attach")) {
        if (blocked || active != null) { throw failure(); }
        try {
            active = control.identify(pid);
            if (active == null || !control.trusted(active)) { throw new IOException(); }
        } catch (Exception e) {
            // Never forget an unidentified spawned instance or permit another child.
            blocked = true;
            throw failure();
        }



        }
    }

    void peer(long pid, long version) throws CustodyClient.CustodyException {
        if (active == null || control.pid(active) != pid || control.version(active) != version) {
            blocked = true;
            throw failure();
        }
    }

    synchronized void finish() throws CustodyClient.CustodyException {
        finish(true);

    }

    synchronized void finish(boolean uncertain) throws CustodyClient.CustodyException {
        finish(uncertain, Deadline.operation());
    }

    synchronized void finish(boolean uncertain, Deadline deadline) throws CustodyClient.CustodyException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.finish")) {
        if (active == null) {
            if (blocked) { throw failure(); }
            return;
        }
        try {
            fence(active, deadline);
            previousResultUnknown |= uncertain;
            if (lease != null) { lease.record("QUIESCENT", generation, pending == null ? "" : pending.operationId, previousResultUnknown); }
            deadline.require();
            active = null;
        } catch (Exception e) {
            blocked = true;
            throw failure();
        }



        }
    }

    private void fence(Object instance, Deadline operationDeadline) throws Exception {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.fence")) {
            Deadline deadline = operationDeadline.exitProof();
            deadline.require();
            boolean absent = control.gone(instance);
            deadline.require();
            if (absent) { return; }
            if (!control.trusted(instance)) { throw failure(); }
            deadline.require();
            if (!control.terminate(instance, deadline)) { throw failure(); }
            deadline.require();
            if (!control.gone(instance)) { throw failure(); }
            deadline.require();
        }
    }

    private synchronized void shutdown() {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.shutdown")) {
        try {
            finish();
            lease.close();
        } catch (Exception e) {
            // Keep the OS-held lock until actual process death; restart must re-establish quiescence.
            blocked = true;
        }



        }
    }

    boolean previousResultUnknown() { return previousResultUnknown; }
    String generation() { return generation; }
    static CustodyClient.CustodyException failure() { return new CustodyClient.CustodyException(UNPROVEN, UNPROVEN); }

    static String randomId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static final class Binding {
        final String generation;
        final String operationId;
        private boolean accepted;

        Binding(String generation, String operationId) { this.generation = generation; this.operationId = operationId; }

        synchronized void accept(String epoch, String operation, String expectedDigest, String actualDigest) throws CustodyClient.CustodyException {
            if (accepted || !generation.equals(epoch) || !operationId.equals(operation) || !expectedDigest.equals(actualDigest)) {
                throw new CustodyClient.CustodyException("SIGNING_FAILED", "STALE_OR_UNBOUND_RESPONSE");
            }
            accepted = true;
        }
    }

    static final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path path;
        private final Object fileKey;

        private Lease(FileChannel channel, FileLock lock, Path path, Object fileKey) {
            this.channel = channel; this.lock = lock; this.path = path; this.fileKey = fileKey;
        }

        static Lease acquire(Path directory) throws IOException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.acquire")) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
                catch (java.nio.file.FileAlreadyExistsException e) { /* competing startup: verify below */ }
            }
            requirePrivate(directory, true);
            Path path = directory.resolve("authority.lock");
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
                catch (java.nio.file.FileAlreadyExistsException e) { /* stable inode is retained across authorities */ }
            }
            requirePrivate(path, false);
            Object before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) { throw new IOException("AUTHORITY_LOCK_BUSY"); }
                var lease = new Lease(channel, lock, path, before);
                lease.verify();
                return lease;
            } catch (IOException | OverlappingFileLockException e) {
                channel.close();
                throw new IOException("AUTHORITY_LOCK_UNPROVEN", e);
            }



        }
    }

        void verify() throws IOException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.verify")) {
            requirePrivate(path.getParent(), true);
            requirePrivate(path, false);
            Object now = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            if (!lock.isValid() || fileKey == null || !fileKey.equals(now)) { throw new IOException("LOCK_INODE_CHANGED"); }



        }
    }

        boolean readUnknown() throws IOException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.readUnknown")) {
            if (channel.size() == 0) { return false; }
            if (channel.size() > 1024) { throw new IOException("INVALID_CUSTODY_CHECKPOINT"); }
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate((int) channel.size());
            channel.position(0);
            while (b.hasRemaining()) { if (channel.read(b) < 0) { throw new IOException(); } }
            var n = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(b.array());
            if (n == null || !n.isObject() || n.size() != 5 || !n.path("version").isIntegralNumber() || n.path("version").asInt() != 1
                    || !n.path("generation").isTextual() || !n.path("generation").asText().matches("[0-9a-f]{32}")
                    || !n.path("operationId").isTextual() || !n.path("operationId").asText().matches("(?:|[0-9a-f]{32})")
                    || "IN_FLIGHT".equals(n.path("status").asText()) && n.path("operationId").asText().isEmpty()
                    || !n.path("unknownResult").isBoolean() || !java.util.Set.of("IN_FLIGHT", "QUIESCENT").contains(n.path("status").asText())) {
                throw new IOException("INVALID_CUSTODY_CHECKPOINT");
            }
            return n.path("unknownResult").asBoolean() || "IN_FLIGHT".equals(n.path("status").asText());



        }
    }

        void record(String status, String generation, String operationId, boolean unknown) throws IOException {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.record")) {
            verify();
            byte[] b = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(java.util.Map.of(
                    "version", 1, "status", status, "generation", generation, "operationId", operationId, "unknownResult", unknown));
            var buffer = java.nio.ByteBuffer.wrap(b);
            channel.position(0);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.truncate(b.length);
            channel.force(true);



        }
    }

        public void close() throws IOException { lock.release(); channel.close(); }
    }

    private static void requirePrivate(Path path, boolean directory) throws IOException {
        var attrs = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(path) || (directory ? !attrs.isDirectory() : !attrs.isRegularFile())
                || !attrs.permissions().equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
                || ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).intValue()
                    != com.sun.jna.NativeLibrary.getInstance("c").getFunction("geteuid").invokeInt(new Object[0])) {
            throw new IOException("AUTHORITY_PATH_UNTRUSTED");
        }
    }

    private static final class NativeControl implements ProcessControl {
        private final CodeIdentity identity;
        private final Path bundle;
        private final String executable;
        private final String requirement;

        NativeControl(CodeIdentity identity, Path bundle) {
            this.identity = identity; this.bundle = bundle;
            this.executable = bundle.resolve("Contents/MacOS/" + CustodyClient.HELPER_EXE).toString();
            this.requirement = CodeIdentity.requirement(CustodyClient.SIGNER_ID, identity.selfTeamId());
        }

        public List<Object> discover() throws Exception {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.discover")) {
            var found = new java.util.ArrayList<Object>();
            for (int pid : identity.processIds()) {
                if (pid <= 0) { continue; }
                int uid = identity.processUid(pid);
                if (uid == -1 || uid >= 0 && uid != identity.effectiveUid()) { continue; }
                if (uid < 0) { throw failure(); }
                String path = identity.processPath(pid);
                if (path == null) {
                    if (identity.processUid(pid) == -1) { continue; }
                    throw failure();
                }
                if (!Path.of(path).getFileName().toString().equals(CustodyClient.HELPER_EXE)) { continue; }
                Object instance = identify(pid);
                if (instance == null) {
                    if (identity.processUid(pid) == -1) { continue; }
                    throw failure();
                }
                if (!trusted(instance)) {
                    if (gone(instance)) { continue; }
                    throw failure();
                }
                found.add(instance);
            }
            return found;


        }
    }

        public Object identify(long pid) {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.identify")) { return identity.instance(pid);

        }
    }
        public boolean trusted(Object instance) {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.trusted")) {
            var i = (CodeIdentity.Instance) instance;
            return executable.equals(identity.instancePath(i))
                    && identity.checkInstance(i, requirement, bundle.toString()) == CodeIdentity.Verdict.OK;


        }
    }
        public boolean gone(Object instance) {
        try (var timing = byx.service.identity.FencingTiming.phase("authority.gone")) { return identity.instancePath((CodeIdentity.Instance) instance) == null;

        }
    }
        public boolean terminate(Object instance) throws InterruptedException {
            return terminate(instance, Deadline.operation());
        }
        public boolean terminate(Object instance, Deadline deadline) throws InterruptedException {
            try (var timing = byx.service.identity.FencingTiming.phase("authority.terminate")) {
                if (deadline.remaining() == 0) { return false; }
                int rc = identity.signalInstance((CodeIdentity.Instance) instance, 9);
                if (rc != 0 && rc != 3) { return false; }
                while (deadline.remaining() > 0) {
                    if (gone(instance)) { return deadline.remaining() > 0; }
                    long remaining = deadline.remaining();
                    if (remaining == 0) { break; }
                    TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10), remaining));
                }
                return false;
            }
        }
        public long pid(Object instance) { return ((CodeIdentity.Instance) instance).pid(); }
        public long version(Object instance) { return ((CodeIdentity.Instance) instance).pidVersion(); }
    }
}
