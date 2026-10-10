package panel.security;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Windows NTFS policy for Panel runtime data, never credentials. No ACL repair on open.
 * The process token user owns the object. Only that SID, SYSTEM and Administrators may
 * have allow ACEs. These privileged OS principals and other processes of the same user
 * are outside the isolation boundary, just as root/the same uid are on POSIX.
 * Raw Windows descriptors are required: NIO does not expose inherited-ACE provenance,
 * protected-DACL control bits or every ACE kind. Unknown ACEs fail closed.
 */
final class WindowsStorage {
    private static final int READ_CONTROL = 0x20000, READ_ATTRIBUTES = 0x80;
    private static final int SHARE_READ_WRITE = 3, OPEN_EXISTING = 3, CREATE_NEW = 1;
    private static final int BACKUP_SEMANTICS = 0x02000000, OPEN_REPARSE_POINT = 0x00200000;
    private static final int DIRECTORY = 0x10, REPARSE_POINT = 0x400;
    private static final int FULL_CONTROL = 0x1f01ff;
    private static final String SYSTEM = "S-1-5-18", ADMINISTRATORS = "S-1-5-32-544";

    interface Kernel extends StdCallLibrary {
        Pointer GetCurrentProcess();
        int CloseHandle(Pointer handle);
        Pointer LocalFree(Pointer pointer);
        Pointer CreateFileW(WString path, int access, int share, SecurityAttributes attributes,
                int disposition, int flags, Pointer template);
        int CreateDirectoryW(WString path, SecurityAttributes attributes);
        int GetFileInformationByHandle(Pointer handle, Pointer info);
        int GetDriveTypeW(WString root);
        int GetWindowsDirectoryW(char[] path, int size);
        int GetVolumeInformationW(WString root, char[] name, int nameSize, IntByReference serial,
                IntByReference maxComponent, IntByReference flags, char[] filesystem, int filesystemSize);
    }
    interface Security extends StdCallLibrary {
        int OpenProcessToken(Pointer process, int access, PointerByReference token);
        int GetTokenInformation(Pointer token, int kind, Pointer info, int length, IntByReference required);
        int ConvertSidToStringSidW(Pointer sid, PointerByReference text);
        int IsValidSid(Pointer sid);
        int ConvertStringSecurityDescriptorToSecurityDescriptorW(WString text, int revision,
                PointerByReference descriptor, IntByReference size);
        int GetSecurityInfo(Pointer handle, int type, int information, PointerByReference owner,
                PointerByReference group, PointerByReference dacl, PointerByReference sacl, PointerByReference descriptor);
        int IsValidAcl(Pointer acl);
        int GetAce(Pointer acl, int index, PointerByReference ace);
    }
    interface Shell extends StdCallLibrary {
        int SHGetKnownFolderPath(Pointer folder, int flags, Pointer token, PointerByReference path);
    }
    interface Ole extends StdCallLibrary { void CoTaskMemFree(Pointer pointer); }

    @Structure.FieldOrder({"length", "descriptor", "inheritHandle"})
    public static class SecurityAttributes extends Structure {
        public int length;
        public Pointer descriptor;
        public int inheritHandle;
        public SecurityAttributes(Pointer descriptor) { this.length = size(); this.descriptor = descriptor; }
    }

    // Lazy: never load Windows libraries on macOS.
    private static final class Api {
        static final Kernel K = Native.load("kernel32", Kernel.class);
        static final Security S = Native.load("advapi32", Security.class);
    }
    static boolean supported() { return System.getProperty("os.name", "").startsWith("Windows"); }
    private static PrivateFiles.InsecureStorageException failure(String code) {
        return new PrivateFiles.InsecureStorageException(code);
    }

    static Path runtimeDirectory() {
        try {
            // FOLDERID_LocalAppData, queried for the real current account; no env/property override.
            Memory guid = new Memory(16);
            guid.setInt(0, 0xf1b32785); guid.setShort(4, (short)0x6fba); guid.setShort(6, (short)0x4fcf);
            guid.write(8, new byte[]{(byte)0x9d,0x55,0x7b,(byte)0x8e,0x7f,0x15,0x70,(byte)0x91}, 0, 8);
            PointerByReference value = new PointerByReference();
            try {
                int result = Native.load("shell32", Shell.class).SHGetKnownFolderPath(guid, 0, null, value);
                if (result != 0 || value.getValue() == null) throw failure("storage_location_unavailable");
                Path base = Path.of(value.getValue().getWideString(0));
                if (!base.isAbsolute()) throw failure("storage_location_unavailable");
                return base.resolve("BYX-MVP");
            } finally {
                if (value.getValue() != null) Native.load("ole32", Ole.class).CoTaskMemFree(value.getValue());
            }
        } catch (LinkageError | IllegalArgumentException e) { throw failure("windows_security_unavailable"); }
    }

    static String currentUserSid() {
        PointerByReference token = new PointerByReference();
        if (Api.S.OpenProcessToken(Api.K.GetCurrentProcess(), 8, token) == 0) throw failure("principal_unresolved");
        try {
            IntByReference size = new IntByReference();
            Api.S.GetTokenInformation(token.getValue(), 1, null, 0, size);
            if (size.getValue() <= 0 || size.getValue() > 65536) throw failure("principal_unresolved");
            Memory info = new Memory(size.getValue());
            if (Api.S.GetTokenInformation(token.getValue(), 1, info, (int)info.size(), size) == 0)
                throw failure("principal_unresolved");
            return sid(info.getPointer(0));
        } finally { Api.K.CloseHandle(token.getValue()); }
    }
    private static String sid(Pointer pointer) {
        if (pointer == null || Api.S.IsValidSid(pointer) == 0) throw failure("principal_unresolved");
        PointerByReference value = new PointerByReference();
        if (Api.S.ConvertSidToStringSidW(pointer, value) == 0) throw failure("principal_unresolved");
        try { return value.getValue().getWideString(0); }
        finally { Api.K.LocalFree(value.getValue()); }
    }

    private static Pointer descriptor(String user, boolean directory) {
        String flags = directory ? "OICI" : "";
        String sddl = "O:" + user + "D:P(A;" + flags + ";FA;;;" + user + ")"
                + "(A;" + flags + ";FA;;;SY)(A;" + flags + ";FA;;;BA)";
        PointerByReference value = new PointerByReference();
        if (Api.S.ConvertStringSecurityDescriptorToSecurityDescriptorW(new WString(sddl), 1, value, null) == 0)
            throw failure("security_descriptor_unavailable");
        return value.getValue();
    }

    private static Path absolute(Path path) {
        Path value = path.toAbsolutePath().normalize();
        // Device namespaces, remote paths and ADS are not approved storage.
        if (!value.getFileSystem().equals(java.nio.file.FileSystems.getDefault()) || value.getRoot() == null
                || value.toString().startsWith("\\\\") || value.toString().indexOf(':', 2) >= 0)
            throw failure("unsupported_storage_path");
        return value;
    }
    private static void volume(Path path) throws IOException {
        IntByReference flags = new IntByReference(); char[] fs = new char[32];
        WString root = new WString(path.getRoot().toString());
        if (Api.K.GetDriveTypeW(root) != 3 || Api.K.GetVolumeInformationW(root, null, 0, null, null, flags, fs, fs.length) == 0
                || !"NTFS".equals(Native.toString(fs)) || (flags.getValue() & 8) == 0)
            throw failure("filesystem_security_unsupported");
        if (!Files.getFileStore(path.getRoot()).supportsFileAttributeView(AclFileAttributeView.class))
            throw failure("acl_unavailable");
    }
    private static Pointer open(Path path) {
        Pointer handle = Api.K.CreateFileW(new WString(path.toString()), READ_CONTROL | READ_ATTRIBUTES,
                SHARE_READ_WRITE, null, OPEN_EXISTING, BACKUP_SEMANTICS | OPEN_REPARSE_POINT, null);
        if (handle == null || Pointer.nativeValue(handle) == -1) throw failure("storage_object_unverifiable");
        return handle;
    }
    private static void type(Pointer handle, boolean directory) {
        Memory info = new Memory(52);
        if (Api.K.GetFileInformationByHandle(handle, info) == 0) throw failure("storage_object_unverifiable");
        int attributes = info.getInt(0);
        if ((attributes & REPARSE_POINT) != 0) throw failure("storage_reparse_point");
        if (((attributes & DIRECTORY) != 0) != directory) throw failure("storage_object_wrong_type");
        if (!directory && info.getInt(40) != 1) throw failure("storage_hard_link");
    }

    private static void validate(Path path, Pointer handle, boolean directory, String user) throws IOException {
        type(handle, directory);
        AclFileAttributeView nio = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (nio == null) throw failure("acl_unavailable");
        // Also require a readable NIO view. Raw validation below is authoritative and SID-based.
        try {
            if (nio.getOwner() == null || nio.getAcl().isEmpty()) throw failure("acl_unverifiable");
        } catch (IOException e) { throw failure("acl_unverifiable"); }
        PointerByReference owner = new PointerByReference(), dacl = new PointerByReference(), sd = new PointerByReference();
        int result = Api.S.GetSecurityInfo(handle, 1, 1 | 4, owner, null, dacl, null, sd);
        if (result != 0) throw failure("acl_unverifiable");
        try {
            if (!user.equals(sid(owner.getValue()))) throw failure("storage_wrong_owner");
            Pointer acl = dacl.getValue();
            if (acl == null || Api.S.IsValidAcl(acl) == 0) throw failure("acl_unverifiable");
            int count = Short.toUnsignedInt(acl.getShort(4));
            if (count == 0 || count > 1024) throw failure("acl_unverifiable");
            boolean currentAccess = false, inheritFiles = false, inheritDirectories = false;
            Set<String> permitted = Set.of(user, SYSTEM, ADMINISTRATORS);
            for (int i = 0; i < count; i++) {
                PointerByReference ref = new PointerByReference();
                if (Api.S.GetAce(acl, i, ref) == 0) throw failure("acl_unverifiable");
                Pointer ace = ref.getValue();
                // Only standard ACCESS_ALLOWED_ACE. Deny/object/callback/conditional ACEs are not guessed at.
                if (ace.getByte(0) != 0 || Short.toUnsignedInt(ace.getShort(2)) < 20) throw failure("acl_unsupported_entry");
                int flags = Byte.toUnsignedInt(ace.getByte(1));
                String principal = sid(ace.share(8));
                if (!permitted.contains(principal))
                    throw failure((flags & 0x10) != 0 ? "acl_unsafe_inherited" : "acl_principal_not_permitted");
                if ((flags & ~0x1f) != 0 || (flags & 4) != 0) throw failure("acl_unsupported_entry");
                int mask = ace.getInt(4);
                if ((mask & ~FULL_CONTROL) != 0) throw failure("acl_unsupported_rights");
                if (principal.equals(user) && (mask & FULL_CONTROL) == FULL_CONTROL) {
                    if ((flags & 8) == 0) currentAccess = true;
                    if ((flags & 1) != 0) inheritFiles = true;
                    if ((flags & 2) != 0) inheritDirectories = true;
                }
            }
            if (!currentAccess || (directory && (!inheritFiles || !inheritDirectories))) throw failure("acl_required_access_missing");
        } finally { if (sd.getValue() != null) Api.K.LocalFree(sd.getValue()); }
    }

    /** Pins every ancestor without FILE_SHARE_DELETE, so paths cannot be replaced during use. */
    static final class Lease implements AutoCloseable {
        private final List<Pointer> handles = new ArrayList<>();
        private final String user;
        private Path directory, file;
        private Pointer directoryHandle, fileHandle;
        private Lease() { user = currentUserSid(); }
        void check() throws IOException {
            validate(directory, directoryHandle, true, user);
            if (file != null) {
                validate(file, fileHandle, false, user);
                for (String suffix : List.of("-journal", "-wal", "-shm")) {
                    Path sidecar = file.resolveSibling(file.getFileName() + suffix);
                    if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                        Pointer h = open(sidecar);
                        try { validate(sidecar, h, false, user); } finally { Api.K.CloseHandle(h); }
                    }
                }
            }
        }
        @Override public void close() {
            for (int i = handles.size() - 1; i >= 0; i--) Api.K.CloseHandle(handles.get(i));
            handles.clear();
        }
    }

    static Lease directory(Path requested) throws IOException {
        return directory(requested, true);
    }
    private static Lease directory(Path requested, boolean create) throws IOException {
        try {
            Path dir = absolute(requested); volume(dir);
            Lease lease = new Lease();
            try {
                Path cursor = dir.getRoot();
                Pointer root = open(cursor); lease.handles.add(root); type(root, true);
                for (Path segment : dir) {
                    cursor = cursor.resolve(segment);
                    if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        if (!create) throw failure("storage_object_unverifiable");
                        Pointer sd = descriptor(lease.user, true);
                        try {
                            if (Api.K.CreateDirectoryW(new WString(cursor.toString()), new SecurityAttributes(sd)) == 0) {
                                // Another initializer may have won. Never accept anything without full validation.
                                if (Native.getLastError() != 183) throw failure("secure_directory_creation_failed");
                            }
                        } finally { Api.K.LocalFree(sd); }
                    }
                    Pointer h = open(cursor); lease.handles.add(h); type(h, true);
                    if (cursor.equals(dir)) lease.directoryHandle = h;
                }
                if (lease.directoryHandle == null) throw failure("unsupported_storage_path");
                lease.directory = dir; lease.check(); return lease;
            } catch (IOException | RuntimeException | Error e) { lease.close(); throw e; }
        } catch (LinkageError e) { throw failure("windows_security_unavailable"); }
        catch (IOException e) { throw failure("storage_unverifiable"); }
    }
    static Lease database(Path requested) throws IOException {
        Path file = absolute(requested);
        Lease lease = directory(file.getParent());
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Pointer sd = descriptor(lease.user, false);
                try {
                    Pointer h = Api.K.CreateFileW(new WString(file.toString()), READ_CONTROL | READ_ATTRIBUTES,
                            SHARE_READ_WRITE, new SecurityAttributes(sd), CREATE_NEW, OPEN_REPARSE_POINT, null);
                    if (h == null || Pointer.nativeValue(h) == -1) {
                        if (Native.getLastError() != 80 && Native.getLastError() != 183) throw failure("secure_file_creation_failed");
                    } else { lease.handles.add(h); lease.fileHandle = h; }
                } finally { Api.K.LocalFree(sd); }
            }
            if (lease.fileHandle == null) { lease.fileHandle = open(file); lease.handles.add(lease.fileHandle); }
            lease.file = file; lease.check(); return lease;
        } catch (IOException | RuntimeException | Error e) { lease.close(); throw e; }
    }
    static void prepareDirectory(Path path) throws IOException { try (Lease ignored = directory(path)) { } }
    static void prepareFile(Path path) throws IOException { try (Lease ignored = database(path)) { } }
    static List<String> audit(Path dir, Path file) {
        // Read-only: unlike prepare methods, audit must never create objects.
        try (Lease lease = existingDirectory(dir)) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Path target = absolute(file);
                if (!target.getParent().equals(lease.directory)) throw failure("unsupported_storage_path");
                lease.file = target; lease.fileHandle = open(target); lease.handles.add(lease.fileHandle);
                lease.check();
            }
            return List.of();
        } catch (PrivateFiles.InsecureStorageException e) { return List.of(e.code); }
        catch (IOException | LinkageError e) { return List.of("storage_unverifiable"); }
    }
    private static Lease existingDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw failure("storage_object_unverifiable");
        return directory(dir, false);
    }
}
