package panel.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;

/**
 * Native, offline presentation catalog. Stable keys and named parameters; English fallback.
 * Locale is session UI state only: it never writes Settings or participates in authority decisions.
 */
public final class Strings {
    public enum Lang {
        EN("en"), PT_BR("pt-BR");

        public final String tag;

        Lang(String tag) {
            this.tag = tag;
        }
    }

    private static final Map<Lang, Properties> CACHE = new ConcurrentHashMap<>();
    private static volatile Lang current = Lang.EN;
    private static final ReadOnlyObjectWrapper<Lang> LANGUAGE = new ReadOnlyObjectWrapper<>(Lang.EN);
    private static final Set<String> MISSING_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISSING_TRANSLATIONS = ConcurrentHashMap.newKeySet();

    private Strings() {
    }

    public static void use(Lang lang) {
        current = Objects.requireNonNull(lang);
        LANGUAGE.set(lang);
    }

    public static Lang language() { return current; }
    public static Locale locale() { return Locale.forLanguageTag(current.tag); }
    public static ReadOnlyObjectProperty<Lang> languageProperty() { return LANGUAGE.getReadOnlyProperty(); }
    public static Set<String> missingKeys() { return Set.copyOf(MISSING_KEYS); }
    public static Set<String> missingTranslations() { return Set.copyOf(MISSING_TRANSLATIONS); }
    public static void resetSession() { use(Lang.EN); }

    /** Compatibility hook retained for existing deterministic tests. */
    public static void useForTests(Lang lang) {
        use(lang);
    }

    public static Properties table(Lang lang) {
        return CACHE.computeIfAbsent(lang, Strings::load);
    }

    private static Properties load(Lang lang) {
        Properties p = new Properties();
        for (String base : new String[] {"package-b_", "package-b-extra_", "ui_", "appearance_", "notifications_"}) {
            String path = "/panel/i18n/" + base + lang.tag + ".properties";
            try (InputStream in = Strings.class.getResourceAsStream(path)) {
                if (in != null) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + path, e);
            }
        }
        return p;
    }

    public static String get(String key) {
        String v = table(current).getProperty(key);
        if (v != null) return v;
        v = table(Lang.EN).getProperty(key);
        if (v != null) { MISSING_TRANSLATIONS.add(current.tag + ":" + key); return v; }
        MISSING_KEYS.add(key);
        return key;
    }

    /** {@code fmt("mk.unsT", "q", "BTC")}: replaces {@code {q}}. */
    public static String fmt(String key, Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) throw new IllegalArgumentException("named parameters require pairs");
        Map<String,String> values = new java.util.HashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            values.put(String.valueOf(nameValuePairs[i]), String.valueOf(nameValuePairs[i + 1]));
        }
        return java.util.regex.Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}").matcher(get(key)).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }
}
