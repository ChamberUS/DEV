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
        root = Files.createTempDirectory(Path.of("/tmp"), "pf");
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

    @Test
    void newHomeIsCreated0700AndTheDatabase0600() throws Exception {
        Path dir = root.resolve("home");
        Path db = dir.resolve("panel.db");
        try (Database d = Database.open(db)) {
            d.with(c -> {
                c.createStatement().executeUpdate("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('t','e','a','d')");
                return null;
            });
        }
        assertEquals("rwx------", mode(dir));
        assertEquals("rw-------", mode(db));
    }

    @Test
    void sqliteAuxiliaryFilesInheritTheMainFileMode() throws Exception {
        Path dir = root.resolve("home");
        Path db = dir.resolve("panel.db");
        try (Database d = Database.open(db)) {
            d.with(c -> { // WAL liga -wal e -shm
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.executeUpdate("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('t','e','a','d')");
                }
                return null;
            });
            List<String> names;
            try (var l = Files.list(dir)) {
                names = l.map(p -> p.getFileName().toString()).sorted().toList();
            }
            assertTrue(names.contains("panel.db-wal") && names.contains("panel.db-shm"), "WAL files exist while open: " + names);
            for (String n : names) {
                assertEquals("rw-------", mode(dir.resolve(n)), n);
            }
            // diário de rollback (modo padrão) durante uma transação
            d.with(c -> {
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA journal_mode=DELETE");
                    c.setAutoCommit(false);
                    s.executeUpdate("INSERT INTO audit_log(ts,event,actor,detail) VALUES ('t2','e','a','d')");
                    try {
                        assertEquals("rw-------", mode(dir.resolve("panel.db-journal")));
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
            Files.createDirectory(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(perm));
            assertEquals("directory_writable_by_others", code(() -> Database.open(dir.resolve("panel.db"))), perm);
            assertFalse(Files.exists(dir.resolve("panel.db")), "nothing was created in an unsafe directory");
        }
    }

    @Test
    void symlinkedDirectoryIsRefused() throws Exception {
        Path real = root.resolve("real");
        Files.createDirectory(real, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, real);
        assertEquals("directory_is_symlink", code(() -> Database.open(link.resolve("panel.db"))));
        assertFalse(Files.exists(real.resolve("panel.db")));
    }

    @Test
    void directoryOwnedBySomeoneElseIsRefused() {
        // /Library pertence ao root: a verificação é só de metadados e recusa ANTES de criar qualquer coisa
        assertEquals("directory_wrong_owner", code(() -> Database.open(Path.of("/Library/byx-test-panel.db"))));
        assertFalse(Files.exists(Path.of("/Library/byx-test-panel.db")));
    }

    @Test
    void symlinkedOrNonRegularDatabaseFileIsRefused() throws Exception {
        Path dir = root.resolve("home");
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path target = root.resolve("elsewhere.db");
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.createSymbolicLink(dir.resolve("panel.db"), target);
        assertEquals("file_not_regular", code(() -> Database.open(dir.resolve("panel.db"))));
        Path sub = dir.resolve("sub.db");
        Files.createDirectory(sub);
        assertEquals("file_not_regular", code(() -> Database.open(sub)));
    }

    @Test
    void anExistingDatabaseIsNeverModifiedByOpenButIsReportedAndCanBeTightenedExplicitly() throws Exception {
        Path dir = root.resolve("home");
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path db = dir.resolve("panel.db");
        Files.createFile(db, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        Path bystander = dir.resolve("settings.properties"); // arquivo que NÃO é do banco
        Files.createFile(bystander, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")));
        try (Database ignored = Database.open(db)) {
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
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
        try (Database ignored = Database.open(dir.resolve("panel.db"))) {
            assertEquals("rwxr-xr-x", mode(dir), "an existing directory is not chmod-ed behind the user's back");
            assertEquals("rw-------", mode(dir.resolve("panel.db")), "the NEW database is private regardless");
        }
        assertEquals(List.of("directory_open_bits"), PrivateFiles.audit(dir, dir.resolve("panel.db")));
    }

    @Test
    void errorMessagesNeverCarryPaths() {
        var e = assertThrows(PrivateFiles.InsecureStorageException.class, () -> Database.open(Path.of("/Library/x.db")));
        assertFalse(e.getMessage().contains("Library"));
    }
}
