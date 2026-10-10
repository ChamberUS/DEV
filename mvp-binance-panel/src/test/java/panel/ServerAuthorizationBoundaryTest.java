package panel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import panel.auth.AdminSession;
import panel.auth.AuthMethod;
import panel.repository.ByxPaymentRepository;
import panel.repository.ByxWalletRepository;
import panel.repository.GasGrantRepository;
import panel.security.AccessDeniedException;
import panel.security.ServerAuthorization;
import panel.security.ServerOperation;
import panel.service.ByxPaymentService;
import panel.service.ByxWalletIdentityService;
import panel.service.GasSponsorshipService;
import panel.service.JobManager;
import panel.service.TreasuryService;

/**
 * PRODUCTION BOUNDARY (categoria A): sem endpoint de autorização no serviço, toda operação sensível do painel é negada
 * ANTES de qualquer efeito, mesmo com sessão de usuário, papel ADMIN e elevação presentes no painel. Estes testes usam as
 * classes de produção reais; não há dublê de autorização aqui. A lógica de domínio é provada em outro contrato.
 */
class ServerAuthorizationBoundaryTest {
    private static final String ADDRESS = "byx1" + "q".repeat(38);
    final AuthFixture auth = AuthFixture.ready();

    @AfterEach void close() { auth.db.close(); }

    private static void assertDenied(ServerOperation operation, org.junit.jupiter.api.function.Executable call) {
        var e = assertThrows(AccessDeniedException.class, call);
        assertEquals(ServerAuthorization.REQUIRED + ": " + operation.wireName(), e.getMessage());
    }

    private ByxWalletIdentityService identity() {
        return new ByxWalletIdentityService(auth.sessions, new ByxWalletRepository(auth.db), () -> null, auth.clock);
    }

    private void loginAdmin() {
        auth.seedAdmin();
        auth.auth.login("boss", "correct-horse-1".toCharArray());
    }

    /** Papel e elevação existentes só na apresentação do painel: nem a elevação real concedida pela autoridade libera. */
    private void elevate() {
        loginAdmin();
        auth.authorize();
        assertTrue(auth.sessions.admin().isPresent());
    }

    @Test void walletMutationIsDenied() {
        elevate();
        var identity = identity();
        assertDenied(ServerOperation.WALLET_IDENTITY, () -> identity.challenge(ADDRESS));
        assertDenied(ServerOperation.WALLET_IDENTITY, () -> identity.verify(null));
        assertDenied(ServerOperation.WALLET_IDENTITY, () -> identity.revoke(ADDRESS));
        assertDenied(ServerOperation.WALLET_IDENTITY, identity::wallets);
        assertTrue(new ByxWalletRepository(auth.db).list(auth.sessions.user().orElseThrow().user().id()).isEmpty());
    }

    @Test void paymentMutationIsDenied() {
        elevate();
        var payments = new ByxPaymentService(auth.sessions, identity(), new ByxPaymentRepository(auth.db), null, auth.clock, () -> null);
        assertDenied(ServerOperation.WALLET_PAYMENT, () -> payments.create(ADDRESS));
        assertDenied(ServerOperation.WALLET_PAYMENT, () -> payments.confirm("intent", "AB".repeat(32)));
        assertDenied(ServerOperation.WALLET_PAYMENT, () -> payments.get("intent"));
        assertDenied(ServerOperation.WALLET_PAYMENT, payments::receipts);
    }

    @Test void gasOperationsAreDenied() {
        elevate();
        var gas = new GasSponsorshipService(auth.sessions, identity(), null, new GasGrantRepository(auth.db), null, () -> null, auth.clock);
        assertDenied(ServerOperation.WALLET_GAS_REQUEST, () -> gas.request(ADDRESS));
        assertDenied(ServerOperation.WALLET_GAS_REFRESH, () -> gas.refresh(ADDRESS));
        assertDenied(ServerOperation.WALLET_GAS_REVOKE, () -> gas.revoke(ADDRESS));
        assertDenied(ServerOperation.WALLET_GAS_JOURNAL, gas::journal);
        var treasury = new TreasuryService(identity(), null, null, new GasGrantRepository(auth.db), () -> null, auth.clock);
        assertDenied(ServerOperation.WALLET_TREASURY_READ, treasury::refresh);
    }

    @Test void networkJobMutationsAreDeniedBeforeAnyEffect() {
        elevate();
        var gateRan = new AtomicBoolean();
        // adapter null: reaching it would be an NPE, so a pass proves the denial happens before any effect
        var jobs = new JobManager(null, () -> Path.of("."), () -> { }, () -> gateRan.set(true));
        assertDenied(ServerOperation.RESEARCH_JOB_SUBMIT, () -> jobs.submit(null, "session"));
        assertDenied(ServerOperation.RESEARCH_JOB_CANCEL, () -> jobs.cancel(null));
        assertFalse(gateRan.get(), "the presentation gate must not be what decides");
    }

    @Test void settingsAndLegacyAuditMutationsAreDenied() {
        var e = assertThrows(IOException.class, () -> ServerAuthorization.requirePersistence(ServerOperation.SETTINGS_PERSIST));
        assertEquals(ServerAuthorization.REQUIRED + ": settings.persist", e.getMessage());
        assertDenied(ServerOperation.SETTINGS_PREFERENCES_PERSIST, () -> ServerAuthorization.require(ServerOperation.SETTINGS_PREFERENCES_PERSIST));
        elevate();
        var audit = new panel.security.SecurityAuditService(panel.security.LegacyAuditHistory.UNAVAILABLE, auth.clock);
        assertDenied(ServerOperation.LEGACY_SECURITY_AUDIT_WRITE, () -> audit.record(panel.security.AuditEvent.values()[0], "actor", "detail"));
    }

    // ---- production guards -----------------------------------------------------------------------------------------------------------

    @Test void productionAuthorizerDeniesEveryOperationAndNullAndInvalid() {
        for (ServerOperation op : ServerOperation.values()) {
            assertDenied(op, () -> ServerAuthorization.DENY_ALL.require(op));
            assertThrows(IOException.class, () -> ServerAuthorization.DENY_ALL.requirePersistence(op));
        }
        var nul = assertThrows(AccessDeniedException.class, () -> ServerAuthorization.DENY_ALL.require(null));
        assertEquals(ServerAuthorization.REQUIRED + ": unknown", nul.getMessage());
        assertThrows(IOException.class, () -> ServerAuthorization.DENY_ALL.requirePersistence(null));
        assertThrows(IllegalArgumentException.class, () -> ServerOperation.valueOf("UNKNOWN_SENSITIVE_OPERATION"));
        assertThrows(IllegalArgumentException.class, () -> ServerOperation.valueOf("wallet.identity"));
        assertEquals(12, ServerOperation.values().length, "the closed operation list changed: review the new operation");
    }

    @Test void productionAuthorizationHasNoSwitchOrStateToFlip() throws Exception {
        var type = ServerAuthorization.class;
        assertTrue(Modifier.isFinal(type.getModifiers()));
        for (var f : type.getDeclaredFields()) {
            assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()), "mutable state: " + f.getName());
        }
        var methods = new TreeSet<String>();
        for (var m : type.getDeclaredMethods()) {
            if (!m.isSynthetic()) {
                methods.add(m.getName());
                assertEquals(void.class, m.getReturnType(), "a method that returns could be read as permission: " + m.getName());
            }
        }
        assertEquals(Set.of("require", "requirePersistence"), methods);
        assertEquals(1, type.getDeclaredConstructors().length);
        assertTrue(Modifier.isPrivate(type.getDeclaredConstructors()[0].getModifiers()));
    }

    @Test void panelSessionRoleAndElevationNeverReleaseASensitiveOperation() {
        elevate();
        assertTrue(auth.sessions.admin().isPresent());
        assertDenied(ServerOperation.WALLET_IDENTITY, identity()::wallets);
        auth.sessions.revokeAdmin();
        assertDenied(ServerOperation.WALLET_IDENTITY, identity()::wallets);
    }

    @Test void forgedAdminInThePanelDoesNotReleaseASensitiveOperation() {
        loginAdmin();
        auth.sessions.grantAdmin(new AdminSession(auth.clock.instant(), AuthMethod.TWO_FACTOR, Duration.ofHours(24)));
        assertTrue(auth.sessions.admin().isPresent());
        assertDenied(ServerOperation.WALLET_IDENTITY, () -> identity().challenge(ADDRESS));
        var payments = new ByxPaymentService(auth.sessions, identity(), new ByxPaymentRepository(auth.db), null, auth.clock, () -> null);
        assertDenied(ServerOperation.WALLET_PAYMENT, () -> payments.create(ADDRESS));
        assertDenied(ServerOperation.RESEARCH_JOB_SUBMIT, () -> new JobManager(null, () -> Path.of("."), () -> { }, () -> { }).submit(null, "s"));
    }

    @Test void forgedPanelStateDoesNotReleaseGasTreasurySettingsOrAuditEither() {
        loginAdmin();
        auth.sessions.grantAdmin(new AdminSession(auth.clock.instant(), AuthMethod.TWO_FACTOR, Duration.ofHours(24)));
        var gas = new GasSponsorshipService(auth.sessions, identity(), null, new GasGrantRepository(auth.db), null, () -> null, auth.clock);
        assertDenied(ServerOperation.WALLET_GAS_REQUEST, () -> gas.request(ADDRESS));
        assertDenied(ServerOperation.WALLET_GAS_REVOKE, () -> gas.revoke(ADDRESS));
        assertDenied(ServerOperation.WALLET_TREASURY_READ, () -> new TreasuryService(identity(), null, null, new GasGrantRepository(auth.db), () -> null, auth.clock).refresh());
        assertThrows(IOException.class, () -> new panel.model.Settings().save());
        assertDenied(ServerOperation.LEGACY_SECURITY_AUDIT_WRITE, () -> new panel.security.SecurityAuditService(panel.security.LegacyAuditHistory.UNAVAILABLE, auth.clock).record(panel.security.AuditEvent.values()[0], "a", "d"));
    }

    @Test void benefitsAndEntitlementsReadsAreAlsoBoundByTheProductionAuthorizer() {
        elevate();
        var benefits = new panel.service.ByxBenefitsService(identity(), null, auth.clock, panel.service.ByxBenefitsService.defaults());
        assertDenied(ServerOperation.WALLET_IDENTITY, () -> benefits.snapshot(ADDRESS));
        assertDenied(ServerOperation.WALLET_IDENTITY, () -> new panel.service.EntitlementService(benefits).extendedHistory(ADDRESS));
        benefits.close();
    }

    @Test void defaultCompositionOfEveryServiceUsesDenyAll() throws Exception {
        Object[] services = {
            identity(),
            new ByxPaymentService(auth.sessions, identity(), new ByxPaymentRepository(auth.db), null, auth.clock, () -> null),
            new GasSponsorshipService(auth.sessions, identity(), null, new GasGrantRepository(auth.db), null, () -> null, auth.clock),
            new TreasuryService(identity(), null, null, new GasGrantRepository(auth.db), () -> null, auth.clock),
            new JobManager(null, () -> Path.of("."), () -> { }, () -> { })
        };
        for (Object service : services) {
            var field = service.getClass().getDeclaredField("authorizer");
            field.setAccessible(true);
            assertSame(ServerAuthorization.DENY_ALL, field.get(service), service.getClass().getSimpleName());
        }
    }

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Set<String> AUTHORIZER_AWARE = Set.of("security/ServerAuthorizer.java", "security/ServerAuthorization.java", "service/ByxWalletIdentityService.java",
            "service/ByxPaymentService.java", "service/GasSponsorshipService.java", "service/TreasuryService.java", "service/JobManager.java");

    @Test void noMainSourceBuildsOrInjectsAPermissiveAuthorizer() throws IOException {
        Set<String> awareFound = new TreeSet<>();
        Set<String> offenders = new TreeSet<>();
        var impl = java.util.regex.Pattern.compile("(implements|extends)\\s+[^{]*\\bServerAuthorizer\\b|new\\s+ServerAuthorizer\\s*\\(|ServerAuthorizer\\s+\\w+\\s*=\\s*(?!ServerAuthorization\\.DENY_ALL)|\\(\\s*ServerAuthorizer\\s*\\)");
        try (Stream<Path> s = Files.walk(MAIN_JAVA)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String rel = MAIN_JAVA.resolve("panel").relativize(f).toString().replace('\\', '/');
                String text = Files.readString(f);
                if (text.contains("ServerAuthorizer")) awareFound.add(rel);
                if (rel.equals("security/ServerAuthorizer.java")) continue;
                if (impl.matcher(text).find() && !rel.equals("security/ServerAuthorization.java")) offenders.add(rel + " (implements/creates an authorizer)");
                // an authorizer argument passed to a service constructor anywhere in src/main other than the declaring constructors
                var call = java.util.regex.Pattern.compile("new\\s+(ByxWalletIdentityService|ByxPaymentService|GasSponsorshipService|TreasuryService|JobManager)\\s*\\(([^;]*)\\)\\s*;").matcher(text);
                while (call.find()) {
                    if (call.group(2).contains("uthoriz")) offenders.add(rel + " passes an authorizer to " + call.group(1));
                }
            }
        }
        assertEquals(Set.of(), offenders);
        assertEquals(AUTHORIZER_AWARE, awareFound, "an unexpected production file uses ServerAuthorizer");
        String authorization = Files.readString(MAIN_JAVA.resolve("panel/security/ServerAuthorization.java"));
        assertEquals(1, authorization.split("implements ServerAuthorizer", -1).length - 1, "exactly one production implementation");
        assertTrue(authorization.contains("DENY_ALL = new DenyAll()"));
    }

    @Test void thereIsNoPermissiveAuthorizerImplementationInTheProductionOutput() throws IOException {
        for (String name : classFiles(CLASSES)) {
            String simple = name.substring(name.lastIndexOf('/') + 1);
            assertFalse(simple.matches("(?i).*(Fake|Allow|Permit|Dev|Bypass|Test)\\w*Authoriz.*|.*Authoriz\\w*(Fake|Allow|Permit|Dev|Bypass|Test).*"), name);
        }
        Set<String> implementers = new TreeSet<>();
        for (String name : classFiles(CLASSES)) {
            if (new String(Files.readAllBytes(CLASSES.resolve(name)), StandardCharsets.ISO_8859_1).contains("panel/security/ServerAuthorizer")
                    && (name.contains("$") || name.contains("Authoriz"))) implementers.add(name);
        }
        assertEquals(Set.of("panel/security/ServerAuthorization$DenyAll.class", "panel/security/ServerAuthorizer.class", "panel/security/ServerAuthorization.class"), implementers);
        assertFalse(classFiles(CLASSES).contains("panel/FakeServerAuthorizer.class"));
    }

    private static final Path CLASSES = Path.of("target/classes");
    private static final Path TEST_CLASSES = Path.of("target/test-classes");

    @Test void noTestAuthorizationOrShadowClassIsInTheProductionOutput() throws IOException {
        assertTrue(Files.isDirectory(CLASSES) && Files.isDirectory(TEST_CLASSES));
        Set<String> production = classFiles(CLASSES);
        Set<String> test = classFiles(TEST_CLASSES);
        assertFalse(production.isEmpty());
        Set<String> shadowed = new TreeSet<>(production);
        shadowed.retainAll(test);
        assertEquals(Set.of(), shadowed, "a test class would shadow/duplicate a production class");
        for (String name : production) {
            String simple = name.substring(name.lastIndexOf('/') + 1);
            assertFalse(simple.matches("(?i).*(AllowAll|PermitAll|TestAuthoriz|TestDouble).*"), "authorization test double in production output: " + name);
            assertFalse(simple.matches("(?i).*(Fake|Stub|Dummy|Mock).*(Author|Permit|Guard|Gate|Session|Admin|Policy).*|(?i).*(Author|Permit|Guard|Gate|Session|Admin|Policy).*(Fake|Stub|Dummy|Mock).*"), "authorization test double in production output: " + name);
        }
    }

    @Test void noRuntimeBypassSwitchExistsInTheProductionOutput() throws IOException {
        var forbidden = new String[] {"allowAll", "bypassAuthorization", "devAuth", "disableSecurity"};
        Set<String> hits = new TreeSet<>();
        for (String name : classFiles(CLASSES)) {
            String content = new String(Files.readAllBytes(CLASSES.resolve(name)), StandardCharsets.ISO_8859_1);
            for (String needle : forbidden) {
                if (content.contains(needle)) hits.add(name + " -> " + needle);
            }
        }
        assertEquals(Set.of(), hits);
        Set<String> sourceHits = new TreeSet<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(f, StandardCharsets.ISO_8859_1);
                for (String needle : forbidden) {
                    if (text.contains(needle)) sourceHits.add(f + " -> " + needle);
                }
            }
        }
        assertEquals(Set.of(), sourceHits);
    }

    @Test void productionDoesNotSelectAnAuthorizationImplementationFromEnvironmentOrProperties() throws IOException {
        Set<String> hits = new TreeSet<>();
        var pattern = java.util.regex.Pattern.compile("(System\\.(getenv|getProperty)\\([^)]*(?i:author|bypass|allow|security)[^)]*\\))");
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            for (Path f : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                var m = pattern.matcher(Files.readString(f));
                while (m.find()) hits.add(f + " -> " + m.group(1));
            }
        }
        assertEquals(Set.of(), hits);
    }

    private static Set<String> classFiles(Path root) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".class")).toList()) out.add(root.relativize(p).toString().replace('\\', '/'));
        }
        return out;
    }
}
