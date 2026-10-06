package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guardas de fonte: o produto não monta autenticação, não há provedor de OTP de desenvolvimento no artefato e não existe operação proibida. */
class AuthIsolationTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static Set<String> filesContaining(String needle) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains(needle)) {
                    out.add(MAIN.relativize(f).toString());
                }
            }
        }
        return out;
    }

    @Test
    void theProductEntryPointNeverComposesAuthentication() throws IOException {
        String main = Files.readString(MAIN.resolve("byx/service/ServiceMain.java"));
        assertFalse(main.contains("Auth") || main.contains("auth"), "ServiceMain (product) has no authentication");
        assertEquals(Set.of("byx/service/ServiceInstance.java", "byx/service/auth/AuthIpc.java", "byx/service/auth/AuthQaMain.java", "byx/service/Operations.java"),
                filesContaining("AuthIpc"), "only the QA composition builds an AuthIpc");
        assertEquals(Set.of("byx/service/auth/AuthQaMain.java"), filesContaining("AuthQaMain"), "nothing else references the QA main");
    }

    @Test
    void thereIsNoProductionOtpProviderOtherThanNotConfigured() throws IOException {
        assertEquals(Set.of("byx/service/auth/AuthQaMain.java", "byx/service/auth/NotConfiguredSecondFactor.java", "byx/service/auth/SecondFactorProvider.java", "byx/service/auth/AuthService.java"),
                filesContaining("SecondFactorProvider"), "implementations: the not-configured production one and the QA file one (QA main only)");
        assertEquals(Set.of(), filesContaining("api.resend.com"), "no real Resend call exists in the service");
        assertEquals(Set.of(), filesContaining("api.twilio.com"), "no real Twilio call exists in the service");
        String q = Files.readString(MAIN.resolve("byx/service/auth/AuthQaMain.java"));
        assertTrue(q.contains("FileSecondFactor"), "the file-based test provider lives only in the QA main");
    }

    @Test
    void forbiddenOperationNamesDoNotExistAnywhereInTheMainSources() throws IOException {
        for (String op : new String[] {"auth.execute", "auth.querySql", "auth.setRole", "auth.setMfa", "auth.impersonate", "auth.override", "auth.debugLogin"}) {
            // só pode aparecer em comentário/documentação do contrato (AuthIpc); nunca como chave de operação
            for (String f : filesContaining("\"" + op + "\"")) {
                throw new AssertionError(op + " defined as an operation in " + f);
            }
        }
        assertFalse(AuthIpc.OPERATIONS.stream().anyMatch(o -> o.matches("auth\\.(execute|querySql|setRole|setMfa|impersonate|override|debugLogin)")));
        assertEquals(Set.of("auth.password", "auth.beginSecondFactor", "auth.verifySecondFactor", "auth.sessionStatus", "auth.logout", "auth.adminElevation", "auth.changePassword"), AuthIpc.OPERATIONS);
    }

    @Test
    void noRuntimeFlagCanChangeTheAuthorityBehavior() throws IOException {
        for (String f : new String[] {"byx/service/auth/AuthService.java", "byx/service/auth/AuthIpc.java", "byx/service/auth/AuthPolicy.java", "byx/service/auth/AuthorityStore.java"}) {
            String src = Files.readString(MAIN.resolve(f));
            assertFalse(src.contains("System.getenv") || src.contains("System.getProperty") || src.contains("getBoolean"), f + " reads no environment or property");
        }
    }

    @Test
    void sessionsAreNeverPersistedAndTheTokenIsNeverStoredRaw() throws IOException {
        for (String f : new String[] {"byx/service/auth/Session.java", "byx/service/auth/SessionStore.java"}) {
            String src = Files.readString(MAIN.resolve(f));
            assertFalse(src.contains("Files.") || src.contains("FileChannel") || src.contains("FileOutputStream"), f + " touches no file: sessions live only in memory");
        }
        assertTrue(Files.readString(MAIN.resolve("byx/service/auth/SessionStore.java")).contains("SHA-256"), "only a hash of the token is stored");
    }
}
