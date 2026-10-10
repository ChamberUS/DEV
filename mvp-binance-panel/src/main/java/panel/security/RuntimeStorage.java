package panel.security;

import java.nio.file.Path;

/** Approved per-user Panel runtime location; no credential store or runtime override. */
public final class RuntimeStorage {
    private RuntimeStorage() { }
    public static Path directory() {
        if (WindowsStorage.supported()) return WindowsStorage.runtimeDirectory();
        return legacyDirectory();
    }
    /** The rollback-only legacy history keeps its original location on every platform. */
    public static Path legacyDirectory() {
        return Path.of(System.getProperty("user.home"), ".mvp-binance-panel");
    }
}
