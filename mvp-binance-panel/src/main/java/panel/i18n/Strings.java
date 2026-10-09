package panel.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strings of the Package B surfaces (Home, entry, avatar, Benefits, navigation guard). This is groundwork for the later localization
 * package, NOT a complete translation of the application: English is the only locale the product uses and no language switch is exposed.
 * A missing key returns the key itself (visible in review) instead of an empty label. Placeholders are {@code {name}}.
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

    private Strings() {
    }

    /** Test/prototype hook only; production always runs in English (see class comment). */
    public static void useForTests(Lang lang) {
        current = lang;
    }

    public static Properties table(Lang lang) {
        return CACHE.computeIfAbsent(lang, Strings::load);
    }

    private static Properties load(Lang lang) {
        Properties p = new Properties();
        for (String base : new String[] {"package-b_", "package-b-extra_"}) {
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
        return v == null ? key : v;
    }

    /** {@code fmt("mk.unsT", "q", "BTC")}: replaces {@code {q}}. */
    public static String fmt(String key, Object... nameValuePairs) {
        String v = get(key);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            v = v.replace("{" + nameValuePairs[i] + "}", String.valueOf(nameValuePairs[i + 1]));
        }
        return v;
    }
}
