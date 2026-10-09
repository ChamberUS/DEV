package byx.service.signer;

import byx.service.identity.CodeIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CustodyInstallTest {
    @TempDir Path root;
    private Path file() throws IOException {
        root = root.toRealPath();
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        Path file = root.resolve("helper"); Files.writeString(file, "public-test-data");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }
    private int uid() { var identity = CodeIdentity.load(); assertNotNull(identity); return identity.effectiveUid(); }
    @Test void ownedPrivateFileUnderSystemStickyTmpAccepted() throws Exception { CustodyClient.requireInstallPath(file(), uid()); }
    @Test void foreignOwnerAndWritableExecutableRefused() throws Exception {
        Path file = file();
        assertThrows(IOException.class, () -> CustodyClient.requireInstallPath(file, uid() + 1));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw----"));
        assertThrows(IOException.class, () -> CustodyClient.requireInstallPath(file, uid()));
    }
    @Test void writableAncestryRefused() throws Exception {
        Path file = file(); Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwx---"));
        assertThrows(IOException.class, () -> CustodyClient.requireInstallPath(file, uid()));
    }
    @Test void symlinksAndNoncanonicalPathsRefused() throws Exception {
        Path file = file(); Path link = root.resolve("alias"); Files.createSymbolicLink(link, file);
        assertThrows(IOException.class, () -> CustodyClient.requireInstallPath(link, uid()));
        assertThrows(IOException.class, () -> CustodyClient.requireInstallPath(Path.of("relative"), uid()));
        assertThrows(IOException.class, () -> CustodyClient.requireInstallPath(root.resolve("../").resolve(root.getFileName()).resolve("helper"), uid()));
    }
}
