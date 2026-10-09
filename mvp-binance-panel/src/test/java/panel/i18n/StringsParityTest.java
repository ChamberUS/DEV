package panel.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Package B strings: EN and PT-BR have the same keys and placeholders. This is groundwork, not a translated app. */
class StringsParityTest {
    private static final Pattern PH = Pattern.compile("\\{[a-zA-Z]+\\}");

    private static Set<String> placeholders(String s) {
        Set<String> out = new TreeSet<>();
        Matcher m = PH.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    @Test
    void keysAndPlaceholdersAreSymmetric() {
        Properties en = Strings.table(Strings.Lang.EN);
        Properties pt = Strings.table(Strings.Lang.PT_BR);
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(pt.stringPropertyNames()));
        for (String k : en.stringPropertyNames()) {
            assertEquals(placeholders(en.getProperty(k)), placeholders(pt.getProperty(k)), k);
            assertFalse(en.getProperty(k).isBlank(), k);
        }
        assertTrue(en.size() > 350);
    }

    @Test
    void missingKeyIsVisibleNotBlank() {
        assertEquals("no.such.key", Strings.get("no.such.key"));
    }

    @Test
    void defaultIsEnglishAndPlaceholdersFill() {
        assertEquals("Stay here", Strings.get("guard.stay"));
        assertEquals("“BTC” isn’t supported in this beta", Strings.fmt("mk.unsT", "q", "BTC"));
    }
}
