package byx.service.signer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustodyAuthorityTest {
    @TempDir Path directory;
    static final class Fake implements CustodyAuthority.ProcessControl {
        boolean alive = true, trusted = true, killWorks = true, observable = true, exits = true;
        int kills;
        long version = 42;
        public List<Object> discover() { return List.of(42L); }
        public Object identify(long pid) { return 42L; }
        public boolean trusted(Object i) { return trusted; }
        public boolean gone(Object i) { if (!observable) { throw new IllegalStateException(); } return !alive || version != (Long) i; }
        public boolean terminate(Object i) { kills++; if (killWorks && exits) { alive = false; } return killWorks; }
        public long pid(Object i) { return 100; }
        public long version(Object i) { return (Long) i; }
    }
    CustodyAuthority authority(Fake f) { return new CustodyAuthority(f, directory, null); }

    @Test void startupTerminatesTrustedStaleAndMarksPriorResultUnknown() throws Exception {
        Fake f = new Fake(); var a = authority(f); a.startup();
        assertEquals(1, f.kills); assertTrue(a.previousResultUnknown()); assertNotNull(a.begin());
    }
    @Test void absentNeedsNoSignal() throws Exception {
        Fake f = new Fake(); f.alive = false; var a = authority(f); a.startup(); assertEquals(0, f.kills); assertNotNull(a.begin());
    }
    @Test void killFailureBlocksEveryNewOperation() {
        Fake f = new Fake(); f.killWorks = false; var a = authority(f);
        assertThrows(CustodyClient.CustodyException.class, a::startup); assertThrows(CustodyClient.CustodyException.class, a::begin);
    }
    @Test void wrongIdentityNeverKilled() {
        Fake f = new Fake(); f.trusted = false; var a = authority(f);
        assertThrows(CustodyClient.CustodyException.class, a::startup); assertEquals(0, f.kills);
    }
    @Test void ambiguousStateFailsClosed() {
        Fake f = new Fake(); f.observable = false; var a = authority(f);
        assertThrows(CustodyClient.CustodyException.class, a::startup); assertEquals(0, f.kills); assertThrows(CustodyClient.CustodyException.class, a::begin);
    }
    @Test void signalAcknowledgedWithoutProvenDeathBlocks() {
        Fake f = new Fake(); f.exits = false; var a = authority(f);
        assertThrows(CustodyClient.CustodyException.class, a::startup); assertEquals(1, f.kills); assertThrows(CustodyClient.CustodyException.class, a::begin);
    }
    @Test void pidReuseProvesOldGoneAndNeverKillsReplacement() throws Exception {
        Fake f = new Fake(); f.version = 43; var a = authority(f); a.startup(); assertEquals(0, f.kills); assertNotNull(a.begin());
    }
    @Test void activeChildCannotBeForgotten() throws Exception {
        Fake f = new Fake(); var a = authority(f); a.attach(100);
        assertThrows(CustodyClient.CustodyException.class, a::begin); a.finish(); assertEquals(1, f.kills); assertNotNull(a.begin());
    }
    @Test void timeoutWithUnkillableChildBlocksNextOperation() throws Exception {
        Fake f = new Fake(); f.killWorks = false; var a = authority(f); a.attach(100);
        assertThrows(CustodyClient.CustodyException.class, a::finish); assertThrows(CustodyClient.CustodyException.class, a::begin);
    }
    @Test void peerMustMatchVersionAndPid() throws Exception {
        Fake f = new Fake(); var a = authority(f); a.attach(100);
        assertThrows(CustodyClient.CustodyException.class, () -> a.peer(100, 43)); assertThrows(CustodyClient.CustodyException.class, a::begin);
    }
    @Test void generationIsFresh() { assertNotEquals(authority(new Fake()).generation(), authority(new Fake()).generation()); }
    @Test void oldGenerationRejected() {
        var b = new CustodyAuthority.Binding("current", "operation");
        assertThrows(CustodyClient.CustodyException.class, () -> b.accept("old", "operation", "digest", "digest"));
    }
    @Test void otherOperationRejected() {
        var b = new CustodyAuthority.Binding("current", "operation");
        assertThrows(CustodyClient.CustodyException.class, () -> b.accept("current", "other", "digest", "digest"));
    }
    @Test void otherRequestRejected() {
        var b = new CustodyAuthority.Binding("current", "operation");
        assertThrows(CustodyClient.CustodyException.class, () -> b.accept("current", "operation", "digest", "other"));
    }
    @Test void duplicateResponseRejected() throws Exception {
        var b = new CustodyAuthority.Binding("current", "operation"); b.accept("current", "operation", "digest", "digest");
        assertThrows(CustodyClient.CustodyException.class, () -> b.accept("current", "operation", "digest", "digest"));
    }
    @Test void osLockContentionAndRelease() throws Exception {
        Path path = directory.resolve("authority");
        try (var first = CustodyAuthority.Lease.acquire(path)) { assertThrows(java.io.IOException.class, () -> CustodyAuthority.Lease.acquire(path)); }
        try (var next = CustodyAuthority.Lease.acquire(path)) { next.verify(); }
    }
    @Test void lockSymlinkRejected() throws Exception {
        Path target = directory.resolve("target"); java.nio.file.Files.createDirectory(target);
        Path link = directory.resolve("authority"); java.nio.file.Files.createSymbolicLink(link, target);
        assertThrows(java.io.IOException.class, () -> CustodyAuthority.Lease.acquire(link));
    }
    @Test void pendingOperationSurvivesRestartAsUnknown() throws Exception {
        Path path = directory.resolve("authority");
        try (var first = CustodyAuthority.Lease.acquire(path)) { first.record("IN_FLIGHT", CustodyAuthority.randomId(), CustodyAuthority.randomId(), false); }
        try (var next = CustodyAuthority.Lease.acquire(path)) { assertTrue(next.readUnknown()); }
    }
    @Test void unknownIsStickyAfterQuiescence() throws Exception {
        try (var lease = CustodyAuthority.Lease.acquire(directory.resolve("authority"))) {
            lease.record("QUIESCENT", CustodyAuthority.randomId(), "", true); assertTrue(lease.readUnknown());
        }
    }
    @Test void uncertaintyIsDurableBeforeAnyChildLaunch() throws Exception {
        Path path = directory.resolve("authority");
        try (var lease = CustodyAuthority.Lease.acquire(path)) {
            var a = new CustodyAuthority(new Fake(), path, lease); a.begin();
            var metadata = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readAllBytes(path.resolve("authority.lock")));
            assertTrue(metadata.path("unknownResult").asBoolean());
        }
    }
}
