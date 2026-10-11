package byx.demo;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import panel.i18n.Presentation;
import panel.i18n.Strings;
import static org.junit.jupiter.api.Assertions.*;

class DemoI18nTest {
    @AfterEach void resetLanguage() { Strings.useForTests(Strings.Lang.EN); }

    @Test void demoCatalogIsCompleteInBothLanguagesWithIdenticalNamedParameters() throws IOException {
        Properties en = read("demo_en.properties");
        Properties pt = read("demo_pt-BR.properties");
        assertEquals(en.stringPropertyNames(), pt.stringPropertyNames());
        assertTrue(en.size() >= 30);
        for (String key : en.stringPropertyNames()) {
            assertFalse(en.getProperty(key).isBlank(), key);
            assertFalse(pt.getProperty(key).isBlank(), key);
            assertEquals(parameters(en.getProperty(key)), parameters(pt.getProperty(key)), key);
            for (Strings.Lang language : Strings.Lang.values()) {
                Strings.useForTests(language);
                assertNotEquals(key, Strings.get(key), key);
            }
        }
    }

    @Test void approvedLoginAndNavigationPhrasesTranslateInBothDirections() {
        Strings.useForTests(Strings.Lang.PT_BR);
        assertEquals("Entrar", Presentation.text("Sign in"));
        assertEquals("E-MAIL OU USUÁRIO", Presentation.text("EMAIL OR USERNAME"));
        assertEquals("SENHA", Presentation.text("PASSWORD"));
        assertEquals("Digite sua senha", Presentation.text("Enter your password"));
        assertEquals("Salvar alterações", Presentation.text("Save changes"));
        assertEquals("Sair", Presentation.text("Sign out"));
        Strings.useForTests(Strings.Lang.EN);
        assertEquals("Sign in", Presentation.text("Entrar"));
        assertEquals("Enter your password", Presentation.text("Digite sua senha"));
        assertEquals("Sign out", Presentation.text("Sair"));
    }

    @Test void publicCredentialsRoutesAssetCodesAndEnteredValuesRemainExact() {
        for (Strings.Lang language : Strings.Lang.values()) {
            Strings.useForTests(language);
            for (String technical : java.util.List.of("teste-byx", "byx123123", "DEMO_USER", "ETHUSDT", "BTCUSDT", "FULL", "REDUCED", "OFF", "t-settings", "t-markets", "h-help", "sys-status", "https://github.com/ChamberUS/DEV", "person@example.invalid"))
                assertEquals(technical, Presentation.text(technical), technical);
            String notice = Presentation.text(Strings.table(Strings.Lang.EN).getProperty("demo.offlineNotice"));
            assertTrue(notice.startsWith("USER / DEMO ·"), notice);
            String credentials = Strings.get("demo.credentials");
            assertTrue(credentials.contains("teste-byx / byx123123"));
            var market = DemoFixtures.market("ETHUSDT");
            assertEquals("ETHUSDT", market.symbol);
            assertFalse(market.backendOnline);
        }
    }

    @Test void missingCatalogKeysUseTheExistingDiagnosticMechanism() {
        String key = "demo.test.key.that.is.not.a.presentation.message";
        assertEquals(key, Strings.get(key));
        assertTrue(Strings.missingKeys().contains(key));
    }

    @Test void themeHelpReflectsPersistenceWithoutClaimingSystemAppearanceSupport() {
        Strings.useForTests(Strings.Lang.EN);
        assertTrue(Strings.get("appearance.sessionBody").contains("remembers your choice after sign out and restart"));
        assertTrue(Strings.get("appearance.systemUnavailable").contains("unavailable"));
        Strings.useForTests(Strings.Lang.PT_BR);
        assertTrue(Strings.get("appearance.sessionBody").contains("lembra sua escolha após sair e reiniciar"));
    }

    private static Properties read(String filename) throws IOException {
        Properties result = new Properties();
        try (var stream = DemoI18nTest.class.getResourceAsStream("/panel/i18n/" + filename)) {
            assertNotNull(stream, filename);
            result.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return result;
    }
    private static Set<String> parameters(String text) {
        var matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}").matcher(text);
        Set<String> result = new TreeSet<>();
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }
}