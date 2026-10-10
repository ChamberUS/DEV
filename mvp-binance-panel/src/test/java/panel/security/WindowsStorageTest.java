package panel.security;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real non-elevated Windows filesystem integration, not mocked ACL success. */
@EnabledOnOs(OS.WINDOWS)
class WindowsStorageTest {
    interface PrincipalApi extends com.sun.jna.win32.StdCallLibrary {
        int ConvertStringSidToSidW(com.sun.jna.WString text, com.sun.jna.ptr.PointerByReference sid);
        int LookupAccountSidW(com.sun.jna.WString system, com.sun.jna.Pointer sid, char[] name,
                com.sun.jna.ptr.IntByReference nameSize, char[] domain,
                com.sun.jna.ptr.IntByReference domainSize, com.sun.jna.ptr.IntByReference use);
    }
    interface DescriptorApi extends WindowsStorage.Security {
        int SetFileSecurityW(com.sun.jna.WString path, int information, com.sun.jna.Pointer descriptor);
        int GetSecurityDescriptorControl(com.sun.jna.Pointer descriptor,
                com.sun.jna.ptr.ShortByReference control, com.sun.jna.ptr.IntByReference revision);
    }
    @TempDir Path root;
    private static AclFileAttributeView acl(Path path) {
        return Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    }
    private static String rejected(Runnable operation) {
        return assertThrows(PrivateFiles.InsecureStorageException.class, operation::run).code;
    }
    private Path dir(String name) throws Exception {
        Path path = root.resolve(name); PrivateFiles.prepareDirectory(path); return path;
    }
    static void grantEveryone(Path path, boolean inheritable) throws Exception {
        // Translate the well-known SID with Windows first: Java's lookup expects an account name.
        var api = com.sun.jna.Native.load("advapi32", PrincipalApi.class);
        var sid = new com.sun.jna.ptr.PointerByReference();
        assertNotEquals(0, api.ConvertStringSidToSidW(new com.sun.jna.WString("S-1-1-0"), sid));
        UserPrincipal everyone;
        try {
            char[] name = new char[256], domain = new char[256];
            var n = new com.sun.jna.ptr.IntByReference(name.length);
            var d = new com.sun.jna.ptr.IntByReference(domain.length);
            assertNotEquals(0, api.LookupAccountSidW(null, sid.getValue(), name, n, domain, d, new com.sun.jna.ptr.IntByReference()));
            String account = com.sun.jna.Native.toString(name), prefix = com.sun.jna.Native.toString(domain);
            everyone = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(prefix.isEmpty() ? account : prefix + "\\" + account);
        } finally { com.sun.jna.Native.load("kernel32", WindowsStorage.Kernel.class).LocalFree(sid.getValue()); }
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA);
        if (inheritable) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        var entries = new ArrayList<>(acl(path).getAcl()); entries.add(entry.build()); acl(path).setAcl(entries);
    }

    @Test void newDirectoryAndFileArePrivateAndCurrentUserOwned() throws Exception {
        Path dir = dir("new"); Path file = dir.resolve("runtime.db");
        PrivateFiles.prepareFile(file);
        assertEquals(acl(root).getOwner(), acl(dir).getOwner());
        assertEquals(acl(dir).getOwner(), acl(file).getOwner());
        assertEquals(3, acl(dir).getAcl().size()); assertEquals(3, acl(file).getAcl().size());
        assertEquals(List.of(), PrivateFiles.audit(dir, file));
        assertTrue(WindowsStorage.currentUserSid().startsWith("S-1-5-21-"));
        // Verify SE_DACL_PROTECTED on the real descriptor, not just its NIO projection.
        var security = com.sun.jna.Native.load("advapi32", DescriptorApi.class);
        try (var lease = WindowsStorage.database(file)) {
            var kernel = com.sun.jna.Native.load("kernel32", WindowsStorage.Kernel.class);
            var handle = kernel.CreateFileW(new com.sun.jna.WString(file.toString()), 0x20000, 3,
                    null, 3, 0x00200000, null);
            assertNotEquals(-1L, com.sun.jna.Pointer.nativeValue(handle));
            var descriptor = new com.sun.jna.ptr.PointerByReference();
            try {
                assertEquals(0, security.GetSecurityInfo(handle, 1, 4, null, null, null, null, descriptor));
                var control = new com.sun.jna.ptr.ShortByReference();
                assertNotEquals(0, security.GetSecurityDescriptorControl(descriptor.getValue(), control,
                        new com.sun.jna.ptr.IntByReference()));
                assertNotEquals(0, control.getValue() & 0x1000);
            } finally { kernel.CloseHandle(handle); if (descriptor.getValue() != null) kernel.LocalFree(descriptor.getValue()); }
        }
    }
    @Test void existingSecureStateIsValidatedWithoutChangingAclOrData() throws Exception {
        Path dir = dir("existing"); Path file = dir.resolve("data"); PrivateFiles.prepareFile(file);
        Files.writeString(file, "synthetic-public-runtime-data");
        var before = acl(file).getAcl(); var parent = acl(root).getAcl();
        PrivateFiles.prepareFile(file);
        assertEquals(before, acl(file).getAcl()); assertEquals(parent, acl(root).getAcl());
        assertEquals("synthetic-public-runtime-data", Files.readString(file));
    }
    @Test void permissiveDirectoryIsRejectedWithoutRepairOrFileCreation() throws Exception {
        Path dir = dir("loose"); grantEveryone(dir, false); var before = acl(dir).getAcl();
        assertEquals("acl_principal_not_permitted", rejected(() -> Database.openRuntime(dir.resolve("runtime.db"))));
        assertEquals(before, acl(dir).getAcl()); assertFalse(Files.exists(dir.resolve("runtime.db")));
    }
    @Test void permissiveExistingFileIsRejectedWithoutMutation() throws Exception {
        Path dir = dir("loose-file"); Path file = dir.resolve("data"); PrivateFiles.prepareFile(file);
        Files.writeString(file, "untouched"); grantEveryone(file, false); var before = acl(file).getAcl();
        assertEquals("acl_principal_not_permitted", rejected(() -> {
            try { PrivateFiles.prepareFile(file); } catch (java.io.IOException e) { throw new AssertionError(e); }
        }));
        assertEquals(before, acl(file).getAcl()); assertEquals("untouched", Files.readString(file));
    }
    @Test void unsafeInheritedPermissionsAreRejected() throws Exception {
        Path parent = dir("inherited"); grantEveryone(parent, true);
        Path child = Files.createDirectory(parent.resolve("child"));
        assertEquals("acl_unsafe_inherited", rejected(() -> Database.openRuntime(child.resolve("runtime.db"))));
        assertFalse(Files.exists(child.resolve("runtime.db")));
    }
    @Test void secureInheritedAclIsAcceptedAndSqliteSidecarsStayPrivate() throws Exception {
        Path dir = dir("sqlite"); Path file = dir.resolve("runtime.db");
        try (Database db = Database.openRuntime(file)) {
            db.with(c -> { try (var s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL"); s.execute("CREATE TABLE probe(value TEXT)");
            } return null; });
            assertTrue(Files.exists(dir.resolve("runtime.db-wal")));
            assertTrue(Files.exists(dir.resolve("runtime.db-shm")));
            for (String suffix : List.of("", "-wal", "-shm")) {
                Path sidecar = dir.resolve("runtime.db" + suffix);
                PrivateFiles.prepareFile(sidecar); assertEquals(List.of(), PrivateFiles.audit(dir, sidecar));
            }
        }
        assertEquals(List.of(), PrivateFiles.audit(dir, file));
    }
    @Test void anUnsafeExistingSidecarBlocksOpen() throws Exception {
        Path dir = dir("sidecar"); Path file = dir.resolve("runtime.db");
        try (Database ignored = Database.openRuntime(file)) { }
        Path sidecar = dir.resolve("runtime.db-wal"); PrivateFiles.prepareFile(sidecar); grantEveryone(sidecar, false);
        assertEquals("acl_principal_not_permitted", rejected(() -> Database.openRuntime(file)));
    }
    @Test void concurrentInitializationNeverRelaxesAcl() throws Exception {
        Path dir = root.resolve("concurrent"); Path file = dir.resolve("runtime.db");
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(() -> { PrivateFiles.prepareFile(file); return true; });
            for (var result : executor.invokeAll(calls)) assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(), PrivateFiles.audit(dir, file));
    }
    @Test void pinnedStorageCannotBeRenamedOrReplacedWhileTheDatabaseIsOpen() throws Exception {
        Path dir = dir("pinned"); Path file = dir.resolve("runtime.db");
        try (Database ignored = Database.openRuntime(file)) {
            assertThrows(java.io.IOException.class, () -> Files.move(dir, root.resolve("replaced")));
            assertThrows(java.io.IOException.class, () -> Files.delete(file));
        }
        Files.move(dir, root.resolve("released"));
    }
    @Test void junctionAndAncestorReparseTraversalAreRejectedWithoutTouchingTarget() throws Exception {
        Path target = dir("target"); var before = acl(target).getAcl();
        Path junction = root.resolve("junction");
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), target.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), output);
        try {
            assertEquals("storage_reparse_point", rejected(() -> Database.openRuntime(junction.resolve("runtime.db"))));
            assertEquals("storage_reparse_point", rejected(() -> Database.openRuntime(junction.resolve("nested/runtime.db"))));
            assertFalse(Files.exists(target.resolve("runtime.db"))); assertFalse(Files.exists(target.resolve("nested")));
            assertEquals(before, acl(target).getAcl());
        } finally { Files.delete(junction); }
    }
    @Test void hardLinkedFilesAreRejected() throws Exception {
        Path dir = dir("hardlink"); Path original = dir.resolve("original"); PrivateFiles.prepareFile(original);
        Path link = dir.resolve("runtime.db"); Files.createLink(link, original);
        assertEquals("storage_hard_link", rejected(() -> Database.openRuntime(link)));
    }
    @Test void unsupportedProviderWithoutAclSupportFailsClosed() throws Exception {
        var uri = java.net.URI.create("jar:" + root.resolve("no-acl.zip").toUri());
        try (var zip = java.nio.file.FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            assertFalse(zip.supportedFileAttributeViews().contains("acl"));
            assertEquals("unsupported_storage_path", rejected(() -> Database.openRuntime(zip.getPath("/runtime.db"))));
        }
    }
    @Test void arbitraryOrUnresolvedPrincipalDoesNotBecomeAnAllowedOwner() throws Exception {
        Path dir = dir("principal");
        assertThrows(java.nio.file.attribute.UserPrincipalNotFoundException.class,
                () -> dir.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("BYX-no-such-principal-57f4d211"));
        // An actual orphan SID ACE must fail the storage policy, not only a name lookup test.
        var security = com.sun.jna.Native.load("advapi32", DescriptorApi.class);
        var descriptor = new com.sun.jna.ptr.PointerByReference();
        String sddl = "D:P(A;OICI;FA;;;" + WindowsStorage.currentUserSid()
                + ")(A;OICI;FA;;;S-1-5-21-111111111-222222222-333333333-4444)";
        assertNotEquals(0, security.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                new com.sun.jna.WString(sddl), 1, descriptor, null));
        try {
            assertNotEquals(0, security.SetFileSecurityW(new com.sun.jna.WString(dir.toString()), 4 | 0x80000000,
                    descriptor.getValue()));
        } finally { com.sun.jna.Native.load("kernel32", WindowsStorage.Kernel.class).LocalFree(descriptor.getValue()); }
        var before = acl(dir).getAcl();
        assertEquals("acl_principal_not_permitted", rejected(() -> Database.openRuntime(dir.resolve("runtime.db"))));
        assertEquals(before, acl(dir).getAcl());
        assertFalse(Files.exists(dir.resolve("runtime.db")));
    }
    @Test void diagnosticsDoNotLeakPathsAndAuditDoesNotCreateState() throws Exception {
        Path dir = root.resolve("not-created-sensitive-name");
        assertEquals(List.of("storage_object_unverifiable"), PrivateFiles.audit(dir, dir.resolve("runtime.db")));
        assertFalse(Files.exists(dir));
        Path bad = dir("denied-sensitive-name"); grantEveryone(bad, false);
        var error = assertThrows(PrivateFiles.InsecureStorageException.class, () -> Database.openRuntime(bad.resolve("runtime.db")));
        assertEquals("Insecure local storage: acl_principal_not_permitted", error.getMessage());
        assertFalse(error.getMessage().contains(root.toString()));
    }
    @Test void separateAppDirectoriesDoNotShareRuntimeData() throws Exception {
        Path a = dir("account-a"), b = dir("account-b");
        PrivateFiles.prepareFile(a.resolve("runtime.db")); PrivateFiles.prepareFile(b.resolve("runtime.db"));
        Files.writeString(a.resolve("runtime.db"), "synthetic-A");
        assertEquals(0, Files.size(b.resolve("runtime.db")));
        assertFalse(Files.isSameFile(a.resolve("runtime.db"), b.resolve("runtime.db")));
    }
    @Test void defaultLocationUsesTheRealKnownFolderNotASyntheticUserHome() {
        String before = System.getProperty("user.home");
        try {
            Path real = RuntimeStorage.directory(); System.setProperty("user.home", root.resolve("fake-home").toString());
            assertEquals(real, RuntimeStorage.directory()); assertEquals("BYX-MVP", real.getFileName().toString());
            assertFalse(real.startsWith(root));
        } finally { System.setProperty("user.home", before); }
    }
}
