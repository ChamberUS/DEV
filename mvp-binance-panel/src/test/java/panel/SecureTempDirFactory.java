package panel;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** Creates private synthetic runtime fixtures from the first filesystem operation. */
public final class SecureTempDirFactory implements TempDirFactory {
    @Override public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
        Path path = Path.of(System.getProperty("java.io.tmpdir")).resolve("byx-private-junit-" + UUID.randomUUID());
        return directory(path);
    }
    public static Path directory(Path path) throws java.io.IOException {
        panel.security.PrivateFiles.prepareDirectory(path);
        return path;
    }
}
