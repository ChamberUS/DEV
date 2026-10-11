package byx.demo;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import panel.design.ThemeMode;
import panel.i18n.Strings;
import panel.motion.MotionPreference;

/** Stores only the offline DEMO's presentation choices, independently of authentication. */
public final class DemoPreferencesStore {
    private static final long MAX_FILE_BYTES = 8192;
    private final Path file;

    public record Values(Strings.Lang language, ThemeMode theme, String density,
                         MotionPreference motion, boolean animatedIcons) {
        public Values {
            Objects.requireNonNull(language);
            Objects.requireNonNull(theme);
            Objects.requireNonNull(motion);
            if (theme == ThemeMode.SYSTEM || !Set.of("COMFORTABLE", "COMPACT").contains(density))
                throw new IllegalArgumentException("Only supported DEMO presentation choices may be stored");
        }
    }

    /** No operational modes, passwords, user identifiers or backend settings are accepted. */
    public DemoPreferencesStore(Path file) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        if (this.file.getParent() == null) throw new IllegalArgumentException("Presentation file requires a parent directory");
    }

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".byx-mvp-demo", "presentation.properties");
    }

    public static Values defaults() {
        return new Values(Strings.Lang.PT_BR, ThemeMode.DARK, "COMFORTABLE", MotionPreference.FULL, true);
    }

    public Path path() { return file; }

    /** Invalid or inaccessible files fall back safely; invalid fields do not discard valid choices. */
    public Values load() {
        Properties properties = new Properties();
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES) return defaults();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            return defaults();
        }
        Values defaults = defaults();
        String language = properties.getProperty("language", "");
        Strings.Lang lang = language.equals("en") ? Strings.Lang.EN
                : language.equals("pt-BR") ? Strings.Lang.PT_BR : defaults.language();
        ThemeMode theme = switch (properties.getProperty("theme", "")) {
            case "DARK" -> ThemeMode.DARK;
            case "LIGHT" -> ThemeMode.LIGHT;
            default -> defaults.theme();
        };
        String density = properties.getProperty("density", "");
        if (!Set.of("COMPACT", "COMFORTABLE").contains(density)) density = defaults.density();
        MotionPreference motion = switch (properties.getProperty("motion", "")) {
            case "FULL" -> MotionPreference.FULL;
            case "REDUCED" -> MotionPreference.REDUCED;
            case "OFF" -> MotionPreference.OFF;
            default -> defaults.motion();
        };
        boolean icons = switch (properties.getProperty("animatedIcons", "")) {
            case "true" -> true;
            case "false" -> false;
            default -> defaults.animatedIcons();
        };
        return new Values(lang, theme, density, motion, icons);
    }

    /** A small, explicit allowlist is written atomically; unrecognized input keys are never copied. */
    public void save(Values values) throws IOException {
        Objects.requireNonNull(values);
        if (Files.isSymbolicLink(file)) throw new IOException("Presentation file is a symbolic link");
        Files.createDirectories(file.getParent());
        String contents = "# BYX-MVP offline DEMO: presentation only\n"
                + "schemaVersion=1\n"
                + "language=" + values.language().tag + "\n"
                + "theme=" + values.theme().name() + "\n"
                + "density=" + values.density() + "\n"
                + "motion=" + values.motion().name() + "\n"
                + "animatedIcons=" + values.animatedIcons() + "\n";
        Path temporary = Files.createTempFile(file.getParent(), "presentation-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}