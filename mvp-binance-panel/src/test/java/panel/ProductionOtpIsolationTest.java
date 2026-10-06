package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import panel.app.AppContext;
import panel.auth.DevOtpProvider;

/**
 * L10: o OTP de desenvolvimento não faz parte do caminho normal. A classe só existe no código de teste, não há flag de runtime que a
 * ligue e a composição de produção só aceita provedores reais. (A prova dinâmica com o app real e security.dev.mode=true está em
 * FinalQa modo prodauth.) Não protege contra quem pode modificar os próprios binários.
 */
class ProductionOtpIsolationTest {
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void theDevelopmentProviderIsNotPartOfTheProductionArtifact() throws IOException {
        assertFalse(Files.exists(Path.of("target/classes/panel/auth/DevOtpProvider.class")), "the compiled production classes do not contain DevOtpProvider");
        assertFalse(Files.exists(MAIN.resolve("panel/auth/DevOtpProvider.java")), "and neither does the production source tree");
        assertTrue(Files.exists(Path.of("src/test/java/panel/auth/DevOtpProvider.java")), "it lives in test code only");
        Set<String> users = new TreeSet<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                if (src.contains("DevOtpProvider") || src.contains("devOtp")) {
                    users.add(MAIN.relativize(p).toString());
                }
            }
        }
        assertEquals(Set.of(), users, "no production class references the development provider");
    }

    @Test
    void noRuntimeSwitchSelectsTheOtpProviders() throws IOException {
        String ctx = Files.readString(MAIN.resolve("panel/app/AppContext.java"));
        // as linhas que escolhem os provedores não leem configuração, ambiente nem propriedades
        int start = ctx.indexOf("public final panel.auth.EmailOtpProvider emailProvider");
        String selection = ctx.substring(start, ctx.indexOf("public final panel.auth.TrustedDeviceService"));
        for (String banned : new String[] {"devMode", "getenv", "getProperty", "security."}) {
            assertFalse(selection.contains(banned), "provider selection must not depend on " + banned);
        }
        assertTrue(selection.contains("ResendEmailOtpProvider") && selection.contains("TwilioVerifySmsProvider"));
    }

    @Test
    void injectionIsExplicitCodeNotConfiguration() {
        AppContext.Providers p = new AppContext.Providers(new DevOtpProvider(), new DevOtpProvider(), "X");
        assertNotNull(p.developmentLabel());
        assertNull(new AppContext.Providers(null, null, null).developmentLabel());
    }
}
