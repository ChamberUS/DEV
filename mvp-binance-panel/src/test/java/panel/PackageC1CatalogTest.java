package panel;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import panel.i18n.*;

class PackageC1CatalogTest {
    @AfterEach void reset() { Strings.useForTests(Strings.Lang.EN); }
    @Test void allKeysAndNamedParametersMatchAndPatternsAreValid() {
        Properties en=Strings.table(Strings.Lang.EN),pt=Strings.table(Strings.Lang.PT_BR);
        assertEquals("Settings",en.getProperty("rail.settings.short"));assertEquals("Config.",pt.getProperty("rail.settings.short"));assertEquals(en.stringPropertyNames(),pt.stringPropertyNames());assertTrue(en.size()>1800);
        Pattern parameter=Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");
        for(String key:en.stringPropertyNames()) {
            assertFalse(en.getProperty(key).isBlank(),key); assertFalse(pt.getProperty(key).isBlank(),key);
            Set<String> a=new TreeSet<>(),b=new TreeSet<>();parameter.matcher(en.getProperty(key)).results().forEach(m->a.add(m.group(1)));parameter.matcher(pt.getProperty(key)).results().forEach(m->b.add(m.group(1)));assertEquals(a,b,key);
            assertFalse(parameter.matcher(en.getProperty(key)).replaceAll("").contains("{"),key);assertFalse(parameter.matcher(pt.getProperty(key)).replaceAll("").contains("{"),key);
        }
    }
    @Test void missingTranslationFallsBackToEnglishAndIsDetected() {
        String key="guard.stay";Properties pt=Strings.table(Strings.Lang.PT_BR);String old=(String)pt.remove(key);
        try {Strings.useForTests(Strings.Lang.PT_BR);assertEquals("Stay here",Strings.get(key));assertTrue(Strings.missingTranslations().contains("pt-BR:"+key));}finally{pt.setProperty(key,old);}
    }
    @Test void missingKeyRemainsVisibleAndIsDetected() {assertEquals("c1.missing",Strings.get("c1.missing"));assertTrue(Strings.missingKeys().contains("c1.missing"));}
    @Test void parameterValuesRemainLiteralIncludingTechnicalAndUserIdentifiers() {
        Strings.useForTests(Strings.Lang.PT_BR);assertEquals("Bem-vindo, Home",Presentation.text("Welcome, Home"));
        assertTrue(Strings.fmt("mk.unsT","q","ETHUSDT").contains("ETHUSDT"));assertTrue(Strings.fmt("home.sub","n","{q}","q","changed").contains("{q}"));assertThrows(IllegalArgumentException.class,()->Strings.fmt("mk.unsT","q"));
    }
    @Test void technicalIdentifiersAndEnteredValuesRemainStable() {
        Strings.useForTests(Strings.Lang.PT_BR);
        for(String s:List.of("ETHUSDT","BTCUSDT","LOCALNET","DEVNET","MAINNET","ClientOrderId","SigningKeyRef","SERVER_AUTHORIZATION_REQUIRED","TX_DISABLED","@Home","/Users/Home","https://example.invalid/Research"))assertEquals(s,Presentation.text(s),s);
    }
    @Test void exactNumberFormattingPreservesAllDigitsAndCurrencyIdentifiers() {
        BigDecimal value=new BigDecimal("2480.840001");assertEquals("2,480.840001",DisplayFormats.exact(value));
        Strings.useForTests(Strings.Lang.PT_BR);assertEquals("2.480,840001",DisplayFormats.exact(value));assertEquals("2.480,84",Presentation.text("2,480.84"));assertEquals("0,001 BYX",Presentation.text("0.001 BYX"));assertEquals("12,34%",Presentation.text("12.34%"));assertEquals("1.000 / 2.000",Presentation.text("1,000 / 2,000"));assertEquals("$2.480,840001",Presentation.text("$2,480.840001"));assertEquals("R$ 2.480,84",Presentation.text("R$ 2,480.84"));assertEquals(new BigDecimal("2480.840001"),value);
    }
    @Test void datesPreserveInstantAndExplicitTimezone() {
        Instant time=Instant.parse("2026-10-09T01:02:03Z");ZoneId zone=ZoneId.of("America/Sao_Paulo");
        assertEquals("2026-10-08 22:02:03",DisplayFormats.dateTime(time,zone));Strings.useForTests(Strings.Lang.PT_BR);assertEquals("08/10/2026 22:02:03",DisplayFormats.dateTime(time,zone));assertEquals("09/10/2026 01:02:03 UTC",Presentation.text("2026-10-09 01:02:03 UTC"));assertEquals(Instant.parse("2026-10-09T01:02:03Z"),time);
    }
    @Test void relativeTimeAndAvailabilityHaveTranslations() {Strings.useForTests(Strings.Lang.PT_BR);assertEquals("3 min atrás",Presentation.text("3 min ago"));assertEquals("Real OFF",Presentation.text("Live trading OFF"));assertEquals("Nenhuma posição ativa",Presentation.text("No active positions"));}
    @Test void returningToEnglishRestoresApprovedCopy() {Strings.useForTests(Strings.Lang.PT_BR);String settings=Presentation.text("Settings");Strings.useForTests(Strings.Lang.EN);assertEquals("Settings",Presentation.text(settings));}
    @Test void composedCopyAndMascotStatesTranslateWithoutChangingNames() {
        Strings.useForTests(Strings.Lang.PT_BR);
        assertEquals("Menu da conta, Home, carregando",Presentation.text("Account menu, Home, loading"));
        assertEquals("Menu da conta, Home, uma operação não foi concluída",Presentation.text("Account menu, Home, an operation did not finish"));
        assertTrue(Presentation.text("FAQ · What do LOCALNET, TEST, PAPER and REAL mean?").contains("O que"));
        assertFalse(Presentation.text("This build has no support endpoint, so a report can not be sent and nothing leaves the app. Open Diagnostics, copy the report and share it manually.").contains("This build"));
        assertEquals("Menu da conta, Home",Presentation.text("Account menu, Home"));
    }
    @Test void financialBaseUnitSerializationRemainsCanonical() {Strings.useForTests(Strings.Lang.PT_BR);assertEquals("1.234567",panel.util.DenomFormat.format(new java.math.BigInteger("1234567"),6));}
}
