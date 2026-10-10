package panel.localservice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Portable fixture setup; never supplies a substitute transport, identity or credential store. */
final class IpcTestFiles {
    private IpcTestFiles() { }
    static Path home(String prefix) throws IOException {
        // Keep the short Unix path required by existing AF_UNIX fixtures on macOS.
        Path base = System.getProperty("os.name", "").startsWith("Windows")
                ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of("/tmp");
        return Files.createTempDirectory(base, prefix);
    }
    static void requirePosixPairing(Path home) throws IOException {
        if (!Files.getFileStore(home).supportsFileAttributeView("posix")) {
            throw new IOException("native_ipc_fixture_unqualified: POSIX pairing and native peer identity required; no Windows substitute");
        }
    }
}
