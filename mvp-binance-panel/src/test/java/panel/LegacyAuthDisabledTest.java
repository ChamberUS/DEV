package panel;

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

/** Guarda: o caminho de autenticação ANTIGO (banco local, hash no painel, limitador, OTP local, keychain legado) não existe mais no produto e nenhuma flag o reabre. */
class LegacyAuthDisabledTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static Set<String> filesContaining(String regex) throws IOException {
        var p = java.util.regex.Pattern.compile(regex);
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (p.matcher(Files.readString(f)).find()) {
                    out.add(MAIN.resolve("panel").relativize(f).toString());
                }
            }
        }
        return out;
    }

    @Test
    void theLegacyAuthClassesAreGoneFromTheProduct() {
        for (String gone : new String[] {"auth/PasswordHasher", "auth/OtpService", "auth/PersistentRateLimiter", "auth/InMemoryRateLimiter", "auth/RateLimiter", "auth/ResendEmailOtpProvider",
            "auth/TwilioVerifySmsProvider", "auth/EmailOtpProvider", "auth/SmsOtpProvider", "auth/UnconfiguredEmailOtpProvider", "auth/UnconfiguredSmsOtpProvider", "security/MacOsKeychainSecretStore",
            "security/SecretStore", "security/ProviderConfig", "security/SecurityConfig", "security/Local2faSetup", "user/SqliteUserRepository", "user/UserRepository"}) {
            assertFalse(Files.exists(MAIN.resolve("panel/" + gone + ".java")), gone + " is back");
            assertFalse(Files.exists(Path.of("target/classes/panel/" + gone + ".class")), gone + " is still compiled");
        }
    }

    @Test
    void noProductCodeReadsOrWritesTheLegacyUserStoreOrItsAuthTables() throws IOException {
        // única leitura permitida: o leitor SOMENTE-LEITURA/imutável do HISTÓRICO legado (LegacyPanelDb) consulta nomes da tabela antiga para mascarar linhas antigas consultando nomes da tabela antiga (SELECT apenas; nunca autentica)
        assertEquals(Set.of("security/LegacyPanelDb.java"), filesContaining("(?i)(FROM|INTO|UPDATE)\\s+users\\b"), "only the legacy history reader looks at the users table");
        assertEquals(Set.of(), filesContaining("(?i)(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+users\\b"), "nothing ever WRITES the legacy users table");
        assertEquals(Set.of(), filesContaining("(?i)(FROM|INTO|UPDATE)\\s+(trusted_devices|rate_limits)\\b"), "no query on the legacy auth tables");
        assertEquals(Set.of(), filesContaining("\\b(UserRepository|SqliteUserRepository|PasswordHasher|OtpService|MacOsKeychainSecretStore|SecKeychain)\\b"));
        assertEquals(Set.of(), filesContaining("api\\.resend\\.com|verify\\.twilio\\.com|twilio|com\\.resend"), "no provider call or secret in the panel");
    }

    @Test
    void noRuntimeFlagCanReopenALegacyOrFallbackAuthPath() throws IOException {
        assertEquals(Set.of(), filesContaining("(?i)\\b(legacyAuth|devAuth|fallbackAuth|legacy\\.auth|auth\\.fallback|byx\\.legacy)\\b"));
        for (String f : new String[] {"auth/AuthService.java", "auth/AdminAccessService.java", "auth/TwoFactorFlow.java", "auth/TrustedDeviceService.java", "user/UserService.java", "localservice/AuthorityClient.java"}) {
            String src = Files.readString(MAIN.resolve("panel/" + f));
            assertFalse(src.contains("System.getenv") || src.contains("getBoolean") || src.replace("System.getProperty(\"user.home\")", "").contains("System.getProperty"), f + " reads no environment or property");
        }
    }

    @Test
    void theNormalAppAuthenticatesOnlyThroughTheServiceClient() throws IOException {
        String ctx = Files.readString(MAIN.resolve("panel/app/AppContext.java"));
        assertTrue(ctx.contains("new panel.localservice.AuthorityClient("), "the real composition uses the service client");
        assertFalse(ctx.contains("SqliteUserRepository") || ctx.contains("PasswordHasher") || ctx.contains("MacOsKeychain"));
        assertEquals(Set.of("app/AppContext.java", "localservice/AuthorityClient.java", "localservice/AuthorityQaCli.java"), filesContaining("\\bAuthorityClient\\b|AuthorityQaCli"),
                "only the composition root builds the client; the QA CLI is a separate launcher");
    }

    @Test
    void theSessionManagerIsPresentationOnlyAndNoRepositoryReachesTheAuthPackage() throws IOException {
        for (String f : new String[] {"auth/SessionManager.java", "auth/AdminSession.java", "auth/UserSession.java"}) {
            String src = Files.readString(MAIN.resolve("panel/" + f));
            assertFalse(src.contains("Repository") || src.contains("Database") || src.contains("Argon2") || src.contains("MessageDigest"), f + " decides nothing");
        }
    }
}
