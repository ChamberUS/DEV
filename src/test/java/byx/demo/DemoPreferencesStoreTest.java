package byx.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import panel.design.ThemeMode;
import panel.i18n.Strings;
import panel.motion.MotionPreference;
import static org.junit.jupiter.api.Assertions.*;

class DemoPreferencesStoreTest {
    @TempDir Path directory;
    private DemoPreferencesStore store() { return new DemoPreferencesStore(directory.resolve("demo/presentation.properties")); }

    @Test void missingFileUsesPortugueseDarkAndSafeDefaultsWithoutCreatingFiles() {
        DemoPreferencesStore store = store();
        assertEquals(new DemoPreferencesStore.Values(Strings.Lang.PT_BR, ThemeMode.DARK, "COMFORTABLE", MotionPreference.FULL, true), store.load());
        assertFalse(Files.exists(store.path()));
    }

    @Test void independentInstancesRememberAllPresentationChoices() throws IOException {
        DemoPreferencesStore store = store();
        var desired = new DemoPreferencesStore.Values(Strings.Lang.EN, ThemeMode.LIGHT, "COMPACT", MotionPreference.REDUCED, false);
        store.save(desired);
        assertEquals(desired, new DemoPreferencesStore(store.path()).load());
        var next = new DemoPreferencesStore.Values(Strings.Lang.PT_BR, ThemeMode.DARK, "COMFORTABLE", MotionPreference.OFF, true);
        store.save(next);
        assertEquals(next, new DemoPreferencesStore(store.path()).load());
        try (var children = Files.list(store.path().getParent())) { assertEquals(1, children.count()); }
    }

    @Test void invalidFieldsFallbackIndependentlyAndUnknownKeysCannotControlAuthority() throws IOException {
        DemoPreferencesStore store = store();
        Files.createDirectories(store.path().getParent());
        Files.writeString(store.path(), "language=pt-PT\ntheme=LIGHT\ndensity=HUGE\nmotion=OFF\nanimatedIcons=yes\nrole=ADMIN\nbackend=true\nworkspace=BYX\n", StandardCharsets.UTF_8);
        assertEquals(new DemoPreferencesStore.Values(Strings.Lang.PT_BR, ThemeMode.LIGHT, "COMFORTABLE", MotionPreference.OFF, true), store.load());
        DemoAuthProvider auth = new DemoAuthProvider();
        assertNull(auth.session());
        assertTrue(auth.login("teste-byx", "byx123123".toCharArray()));
        assertEquals("USER", auth.session().role());
    }

    @Test void onlyAnExplicitPresentationAllowlistIsEverSaved() throws IOException {
        DemoPreferencesStore store = store();
        store.save(DemoPreferencesStore.defaults());
        Properties saved = new Properties();
        try (var reader = Files.newBufferedReader(store.path(), StandardCharsets.UTF_8)) { saved.load(reader); }
        assertEquals(java.util.Set.of("schemaVersion", "language", "theme", "density", "motion", "animatedIcons"), saved.stringPropertyNames());
        String contents = Files.readString(store.path());
        for (String prohibited : java.util.List.of("teste-byx", "byx123123", "password", "token", "credential", "backend", "service", "role", "wallet"))
            assertFalse(contents.contains(prohibited), prohibited);
    }

    @Test void corruptedAndOversizedFilesFallbackWithoutCrashingOrDeletingThem() throws IOException {
        DemoPreferencesStore store = store();
        Files.createDirectories(store.path().getParent());
        String invalid = "language=\\uBAD!\n";
        Files.writeString(store.path(), invalid);
        assertEquals(DemoPreferencesStore.defaults(), store.load());
        assertEquals(invalid, Files.readString(store.path()));
        String oversized = "x".repeat(8193);
        Files.writeString(store.path(), oversized);
        assertEquals(DemoPreferencesStore.defaults(), store.load());
        assertEquals(oversized, Files.readString(store.path()));
    }

    @Test void systemAppearanceAndUnknownDensityCannotBePersisted() {
        assertThrows(IllegalArgumentException.class, () -> new DemoPreferencesStore.Values(Strings.Lang.PT_BR, ThemeMode.SYSTEM, "COMFORTABLE", MotionPreference.FULL, true));
        assertThrows(IllegalArgumentException.class, () -> new DemoPreferencesStore.Values(Strings.Lang.PT_BR, ThemeMode.DARK, "REAL", MotionPreference.FULL, true));
    }

    @Test void failedSaveDoesNotMutateTheDestinationOrOtherProfiles() throws IOException {
        Path blocker = directory.resolve("occupied");
        Files.writeString(blocker, "preserved original");
        DemoPreferencesStore store = new DemoPreferencesStore(blocker.resolve("presentation.properties"));
        assertThrows(IOException.class, () -> store.save(DemoPreferencesStore.defaults()));
        assertEquals("preserved original", Files.readString(blocker));
        assertEquals(DemoPreferencesStore.defaults(), store.load());
    }
}