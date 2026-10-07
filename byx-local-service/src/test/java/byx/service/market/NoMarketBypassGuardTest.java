package byx.service.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** O produto não tem como desligar/substituir o mercado: só código de teste troca a implementação (FakeMarket), e nenhum env/propriedade existe para isso. */
class NoMarketBypassGuardTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noBypassNamesAnywhereInProductionSources() throws IOException {
        for (Path p : sources()) {
            String src = Files.readString(p);
            for (String forbidden : new String[] {"disableMarketForTest", "skipMarket", "offlineTestMode", "offlineMode", "noMarket", "BYX_NO_MARKET",
                    "BYX_OFFLINE", "FakeMarket", "market.disabled"}) {
                assertFalse(src.contains(forbidden), p + " must not contain " + forbidden);
            }
        }
    }

    @Test
    void marketPackageReadsNoEnvironmentOrSystemProperties() throws IOException {
        for (Path p : sources()) {
            if (p.toString().contains("/market/")) {
                String src = Files.readString(p);
                for (String forbidden : new String[] {"System.getenv", "System.getProperty", "Boolean.getBoolean", "Integer.getInteger", "Long.getLong"}) {
                    assertFalse(src.contains(forbidden), p + " must not read " + forbidden + ": there is no switch for the market");
                }
            }
        }
    }

    @Test
    void theOnlyEnvironmentVariableTheServiceReadsIsItsHomeDirectory() throws IOException {
        List<String> envReads = new ArrayList<>();
        for (Path p : sources()) {
            String src = Files.readString(p);
            int i = 0;
            while ((i = src.indexOf("System.getenv(", i)) >= 0) {
                int end = src.indexOf(')', i);
                envReads.add(src.substring(i + 14, end));
                i = end;
            }
        }
        assertTrue(envReads.size() >= 1);
        for (String r : envReads) {
            assertEquals("\"BYX_LOCAL_SERVICE_HOME\"", r, "no other environment switch may exist in production code");
        }
    }

    @Test
    void productionCompositionUsesTheRealTransportsAndOnlyTestsUseTheFake() throws IOException {
        String main = Files.readString(MAIN.resolve("byx/service/ServiceMain.java"));
        assertTrue(main.contains("JdkWsTransport") && main.contains("BoundedHttp"), "ServiceMain composes the real, allowlisted transports");
    }
}
