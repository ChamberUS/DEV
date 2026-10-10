package panel.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L12: permissões dos arquivos sensíveis, só em homes temporários (o home real nunca é aberto nem alterado por estes testes). */
class PrivateFilesTest {
    private Path root;

    @BeforeEach
    void up() throws Exception {
        root = Files.createTempDirectory("pf");
    }

    @AfterEach
    void down() throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
                } catch (Exception ignored) {
                    // symlink
                }
                p.toFile().delete();
            });
        }
    }

    private static String mode(Path p) throws Exception {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    private static String code(Runnable r) {
        return assertThrows(PrivateFiles.InsecureStorageException.class, r::run).code;
    }

    private static boolean windows() { return WindowsStorage.supported(); }
    private static void privateFile(Path directory, Path file) throws Exception {
        if (windows()) assertEquals(List.of(), PrivateFiles.audit(directory, file));
        else assertEquals("rw-------", mode(file));
    }
    private static Path windowsDirectory() {
        char[] path = new char[32768];
        int length = com.sun.jna.Native.load("kernel32", WindowsStorage.Kernel.class).GetWindowsDirectoryW(path, path.length);
        assertTrue(length > 0 && length < path.length);
        return Path.of(com.sun.jna.Native.toString(path));
    }
    private static void junction(Path link, Path target) throws Exception {
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), output);
    }

    @Test
    void newHomeIsCreated0700AndTheDatabase0600() throws Exception {
        Path dir = root.resolve("home");
        Path db = dir.resolve("runtime.db");
        try (Database d = Database.openRuntime(db)) {
            d.with(c -> {
                c.createStatement().executeUpdate("CREATE TABLE IF NOT EXISTS probe(x TEXT)");
                return null;
            });
        }
        if (!windows()) assertEquals("rwx------", mode(dir));
        privateFile(dir, db);
    }

    @Test
    void sqliteAuxiliaryFilesInheritTheMainFileMode() throws Exception {
        Path dir = root.resolve("home");
        Path db = dir.resolve("runtime.db");
        try (Database d = Database.openRuntime(db)) {
            d.with(c -> { // WAL liga -wal e -shm
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS probe(x TEXT)");
                }
                return null;
            });
            List<String> names;
            try (var l = Files.list(dir)) {
                names = l.map(p -> p.getFileName().toString()).sorted().toList();
            }
            assertTrue(names.contains("runtime.db-wal") && names.contains("runtime.db-shm"), "WAL files exist while open: " + names);
            for (String n : names) {
                privateFile(dir, dir.resolve(n));
            }
            // diário de rollback (modo padrão) durante uma transação
            d.with(c -> {
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA journal_mode=DELETE");
                    c.setAutoCommit(false);
                    s.executeUpdate("INSERT INTO probe VALUES('t2')");
                    try {
                        privateFile(dir, dir.resolve("runtime.db-journal"));
                    } catch (Exception e) {
                        throw new java.sql.SQLException(e);
                    }
                    c.rollback();
                    c.setAutoCommit(true);
                }
                return null;
            });
        }
    }

    @Test
    void existingDirectoryWritableByGroupOrOthersFailsClosedWithoutCreatingAnything() throws Exception {
        for (String perm : List.of("rwxrwx---", "rwx-w----", "rwx---rwx", "rwx----w-")) {
            Path dir = root.resolve("d-" + perm);
            if (windows()) {
                PrivateFiles.prepareDirectory(dir); WindowsStorageTest.grantEveryone(dir, false);
                assertEquals("acl_principal_not_permitted", code(() -> Database.openRuntime(dir.resolve("runtime.db"))), perm);
                assertFalse(Files.exists(dir.resolve("runtime.db"))); continue;
            }
            Files.createDirectory(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(perm));
            assertEquals("directory_writable_by_others", code(() -> Database.openRuntime(dir.resolve("runtime.db"))), perm);
            assertFalse(Files.exists(dir.resolve("runtime.db")), "nothing was created in an unsafe directory");
        }
    }

    @Test
    void symlinkedDirectoryIsRefused() throws Exception {
        Path real = root.resolve("real");
        if (windows()) {
            PrivateFiles.prepareDirectory(real); Path link = root.resolve("link"); junction(link, real);
            try {
                assertEquals("storage_reparse_point", code(() -> Database.openRuntime(link.resolve("runtime.db"))));
                assertFalse(Files.exists(real.resolve("runtime.db")));
            } finally { Files.delete(link); }
            return;
        }
        Files.createDirectory(real, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, real);
        assertEquals("directory_is_symlink", code(() -> Database.openRuntime(link.resolve("runtime.db"))));
        assertFalse(Files.exists(real.resolve("runtime.db")));
    }

    @Test
    void directoryOwnedBySomeoneElseIsRefused() {
        if (windows()) {
            Path file = windowsDirectory().resolve("byx-test-runtime.db");
            assertEquals("storage_wrong_owner", code(() -> Database.openRuntime(file)));
            assertFalse(Files.exists(file)); return;
        }
        // /Library pertence ao root: a verificação é só de metadados e recusa ANTES de criar qualquer coisa
        assertEquals("directory_wrong_owner", code(() -> Database.openRuntime(Path.of("/Library/byx-test-runtime.db"))));
        assertFalse(Files.exists(Path.of("/Library/byx-test-runtime.db")));
    }

    @Test
    void symlinkedOrNonRegularDatabaseFileIsRefused() throws Exception {
        Path dir = root.resolve("home");
        if (windows()) {
            PrivateFiles.prepareDirectory(dir); Path target = root.resolve("elsewhere"); PrivateFiles.prepareDirectory(target);
            Path link = dir.resolve("runtime.db"); junction(link, target);
            try { assertEquals("storage_reparse_point", code(() -> Database.openRuntime(link))); }
            finally { Files.delete(link); }
            Path sub = dir.resolve("sub.db"); Files.createDirectory(sub);
            assertEquals("storage_object_wrong_type", code(() -> Database.openRuntime(sub))); return;
        }
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path target = root.resolve("elsewhere.db");
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.createSymbolicLink(dir.resolve("runtime.db"), target);
        assertEquals("file_not_regular", code(() -> Database.openRuntime(dir.resolve("runtime.db"))));
        Path sub = dir.resolve("sub.db");
        Files.createDirectory(sub);
        assertEquals("file_not_regular", code(() -> Database.openRuntime(sub)));
    }

    @Test
    void anExistingDatabaseIsNeverModifiedByOpenButIsReportedAndCanBeTightenedExplicitly() throws Exception {
        Path dir = root.resolve("home");
        if (windows()) {
            PrivateFiles.prepareDirectory(dir); Path db = dir.resolve("runtime.db"); PrivateFiles.prepareFile(db);
            WindowsStorageTest.grantEveryone(db, false);
            var view = Files.getFileAttributeView(db, java.nio.file.attribute.AclFileAttributeView.class);
            var before = view.getAcl();
            assertEquals("acl_principal_not_permitted", code(() -> Database.openRuntime(db)));
            assertEquals(before, view.getAcl(), "no silent repair of existing unsafe state");
            assertEquals(List.of("acl_principal_not_permitted"), PrivateFiles.audit(dir, db));
            assertEquals("windows_explicit_acl_migration_required", code(() -> {
                try { PrivateFiles.tighten(db); } catch (java.io.IOException e) { throw new AssertionError(e); }
            })); return;
        }
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path db = dir.resolve("runtime.db");
        Files.createFile(db, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        Path bystander = dir.resolve("settings.properties"); // arquivo que NÃO é do banco
        Files.createFile(bystander, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        try (Database ignored = Database.openRuntime(db)) {
            assertEquals("rw-r--r--", mode(db), "open does not change an existing database");
        }
        assertEquals("rw-r--r--", mode(bystander), "unrelated files are never touched");
        assertEquals(List.of("file_open_bits"), PrivateFiles.audit(dir, db), "but the loose mode is reported (metadata only)");
        PrivateFiles.tighten(db); // decisão explícita e separada
        assertEquals("rw-------", mode(db));
        assertEquals("rw-r--r--", mode(bystander));
        assertEquals(List.of(), PrivateFiles.audit(dir, db));
    }

    @Test
    void existingDirectoryWithOpenReadBitsIsAcceptedButReportedNotSilentlyChanged() throws Exception {
        Path dir = root.resolve("home");
        if (windows()) {
            PrivateFiles.prepareDirectory(dir); WindowsStorageTest.grantEveryone(dir, false);
            var view = Files.getFileAttributeView(dir, java.nio.file.attribute.AclFileAttributeView.class);
            var before = view.getAcl();
            assertEquals("acl_principal_not_permitted", code(() -> Database.openRuntime(dir.resolve("runtime.db"))));
            assertEquals(before, view.getAcl(), "Windows has a stricter ACL policy; it must refuse, never silently repair");
            assertFalse(Files.exists(dir.resolve("runtime.db"))); return;
        }
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        try (Database ignored = Database.openRuntime(dir.resolve("runtime.db"))) {
            assertEquals("rwxr-xr-x", mode(dir), "an existing directory is not chmod-ed behind the user's back");
            assertEquals("rw-------", mode(dir.resolve("runtime.db")), "the NEW database is private regardless");
        }
        assertEquals(List.of("directory_open_bits"), PrivateFiles.audit(dir, dir.resolve("runtime.db")));
    }

    @Test
    void errorMessagesNeverCarryPaths() {
        Path target = windows() ? windowsDirectory().resolve("byx-test-runtime.db") : Path.of("/Library/x.db");
        var e = assertThrows(PrivateFiles.InsecureStorageException.class, () -> Database.openRuntime(target));
        assertFalse(e.getMessage().contains("Library"));
    }
}
