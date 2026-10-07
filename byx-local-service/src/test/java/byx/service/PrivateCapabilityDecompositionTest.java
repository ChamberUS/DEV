package byx.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.PrivateCapabilityGate.Config;
import byx.service.PrivateCapabilityGate.ConfigState;
import byx.service.PrivateCapabilityGate.Facts;
import byx.service.account.AccountAuditEvent;
import byx.service.account.AccountDtos;
import byx.service.account.AccountRequestPolicy;
import byx.service.account.BinanceReadEndpoint;
import byx.service.account.ReadOnlyCredentialPolicy;
import byx.service.account.ReadOnlyCredentialPolicy.Verdict;
import byx.service.auth.AuthIpc;
import byx.service.auth.AuthPolicy;
import byx.service.auth.Role;
import byx.service.secrets.SecretId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * V2.1K: o gate global foi decomposto em capacidades tipadas. Master=false nega tudo; habilitar uma capacidade nunca habilita outra; nada que o painel diga libera algo;
 * a credencial Binance futura é só do serviço, só leitura e sem endpoint genérico. Nenhuma capacidade está implementada nem habilitada: tudo continua desligado.
 */
class PrivateCapabilityDecompositionTest {
    private static final Facts ALL_FACTS = new Facts(true, true, true);
    private static final Predicate<PrivateCapability> ALL_IMPLEMENTED = c -> true;

    // ---- modelo --------------------------------------------------------------------------------------------------------------------------

    @Test
    void theCapabilityModelIsClosedAndDocumentsEveryRequirement() {
        assertEquals(Set.of("notifications", "accountData", "adminOperations", "secretIntegrations"), java.util.Arrays.stream(PrivateCapability.values()).map(PrivateCapability::wire).collect(java.util.stream.Collectors.toSet()));
        assertEquals(4, PrivateCapability.values().length, "a new private capability is a reviewed change");
        for (PrivateCapability c : PrivateCapability.values()) {
            assertTrue(c.requiresVerifiedPeer(), c + " always requires a verified peer");
            assertFalse(c.implemented(), c + " is not implemented: nothing is enabled");
            assertTrue(PrivateCapability.fromWire(c.wire()).orElseThrow() == c);
        }
        assertEquals(Role.ADMIN, PrivateCapability.ADMIN_OPERATIONS.minRole());
        assertTrue(PrivateCapability.ADMIN_OPERATIONS.recentMfa() && PrivateCapability.ADMIN_OPERATIONS.elevation());
        assertTrue(PrivateCapability.BINANCE_ACCOUNT_READ.ownership());
        assertEquals(SecretId.BINANCE_READONLY_CREDENTIAL, PrivateCapability.BINANCE_ACCOUNT_READ.requiredSecret().orElseThrow());
        assertTrue(PrivateCapability.BINANCE_ACCOUNT_READ.requiredSecret().isPresent() && PrivateCapability.NOTIFICATIONS.requiredSecret().isEmpty());
        assertEquals(Set.of("fapi.binance.com", "api.binance.com"), PrivateCapability.BINANCE_ACCOUNT_READ.hosts());
        assertTrue(PrivateCapability.NOTIFICATIONS.hosts().isEmpty() && PrivateCapability.ADMIN_OPERATIONS.hosts().isEmpty(), "no other capability may contact the network");
        assertFalse(PrivateCapability.SECRET_INTEGRATIONS.uiExposed(), "secret integrations are never exposed to the UI");
        assertTrue(PrivateCapability.fromWire("wallet").isEmpty() && PrivateCapability.fromWire(null).isEmpty() && PrivateCapability.fromWire("ACCOUNTDATA").isEmpty(), "unknown = empty = denied");
    }

    // ---- master + por capacidade + isolamento ---------------------------------------------------------------------------------------------

    @Test
    void masterFalseDeniesEveryCapabilityWhateverTheIndividualConfigurationOrFacts() {
        Set<PrivateCapability> all = EnumSet.allOf(PrivateCapability.class);
        for (PrivateCapability c : PrivateCapability.values()) {
            assertFalse(PrivateCapabilityGate.decide(new Config(false, all), c, ALL_FACTS, ALL_IMPLEMENTED), "master=false + everything else true: " + c);
        }
        assertFalse(PrivateCapabilityGate.PRIVATE_CAPABILITIES_ALLOWED);
        assertEquals(Config.PRODUCTION, new Config(false, Set.of()), "production: master false, nothing enabled");
        for (PrivateCapability c : PrivateCapability.values()) {
            assertFalse(PrivateCapabilityGate.decide(Config.PRODUCTION, c, ALL_FACTS, ALL_IMPLEMENTED), "production config denies even with hypothetical implementation: " + c);
            assertFalse(PrivateCapabilityGate.decide(Config.PRODUCTION, c, ALL_FACTS), "and with the real implementation status: " + c);
            assertFalse(PrivateCapabilityGate.available(c));
            assertFalse(PrivateCapabilityGate.allowed(c.wire()));
        }
    }

    @Test
    void theDecisionIsAnExactConjunctionOfEveryCondition() {
        // tabela-verdade: master, habilitada, implementada, peer, segredo, autorizada (BINANCE exige segredo); só 1 de 64 combinações permite
        int allowed = 0;
        for (int mask = 0; mask < 64; mask++) {
            boolean master = (mask & 1) != 0, enabled = (mask & 2) != 0, implemented = (mask & 4) != 0, peer = (mask & 8) != 0, secret = (mask & 16) != 0, authorized = (mask & 32) != 0;
            boolean got = PrivateCapabilityGate.decide(new Config(master, enabled ? Set.of(PrivateCapability.BINANCE_ACCOUNT_READ) : Set.of()), PrivateCapability.BINANCE_ACCOUNT_READ,
                    new Facts(peer, secret, authorized), c -> implemented);
            boolean expected = master && enabled && implemented && peer && secret && authorized;
            assertEquals(expected, got, "mask " + mask);
            if (got) allowed++;
        }
        assertEquals(1, allowed);
        assertFalse(PrivateCapabilityGate.decide(null, PrivateCapability.NOTIFICATIONS, ALL_FACTS, ALL_IMPLEMENTED));
        assertFalse(PrivateCapabilityGate.decide(new Config(true, EnumSet.allOf(PrivateCapability.class)), null, ALL_FACTS, ALL_IMPLEMENTED));
        assertFalse(PrivateCapabilityGate.decide(new Config(true, EnumSet.allOf(PrivateCapability.class)), PrivateCapability.NOTIFICATIONS, null, ALL_IMPLEMENTED));
        // sem segredo exigido, o fato "segredo" é irrelevante; com segredo exigido, ausente nega
        assertTrue(PrivateCapabilityGate.decide(new Config(true, Set.of(PrivateCapability.NOTIFICATIONS)), PrivateCapability.NOTIFICATIONS, new Facts(true, false, true), ALL_IMPLEMENTED));
        assertFalse(PrivateCapabilityGate.decide(new Config(true, Set.of(PrivateCapability.BINANCE_ACCOUNT_READ)), PrivateCapability.BINANCE_ACCOUNT_READ, new Facts(true, false, true), ALL_IMPLEMENTED));
    }

    @Test
    void enablingOneCapabilityNeverEnablesAnother() {
        for (PrivateCapability on : PrivateCapability.values()) {
            Config only = new Config(true, Set.of(on));
            for (PrivateCapability other : PrivateCapability.values()) {
                boolean got = PrivateCapabilityGate.decide(only, other, ALL_FACTS, ALL_IMPLEMENTED);
                assertEquals(on == other, got, on + " enabled → " + other);
            }
        }
        // casos nomeados da especificação
        Config notifications = new Config(true, Set.of(PrivateCapability.NOTIFICATIONS));
        assertFalse(PrivateCapabilityGate.decide(notifications, PrivateCapability.BINANCE_ACCOUNT_READ, ALL_FACTS, ALL_IMPLEMENTED), "notifications ⇏ account data");
        assertFalse(PrivateCapabilityGate.decide(notifications, PrivateCapability.ADMIN_OPERATIONS, ALL_FACTS, ALL_IMPLEMENTED), "notifications ⇏ admin operations");
        assertFalse(PrivateCapabilityGate.decide(notifications, PrivateCapability.SECRET_INTEGRATIONS, ALL_FACTS, ALL_IMPLEMENTED), "notifications ⇏ secret integrations");
        Config account = new Config(true, Set.of(PrivateCapability.BINANCE_ACCOUNT_READ));
        assertFalse(PrivateCapabilityGate.decide(account, PrivateCapability.ADMIN_OPERATIONS, ALL_FACTS, ALL_IMPLEMENTED), "account data ⇏ admin operations");
        assertFalse(PrivateCapabilityGate.decide(account, PrivateCapability.NOTIFICATIONS, ALL_FACTS, ALL_IMPLEMENTED));
        // não há capacidade "orders/wallet/payment/gas": nada disso é liberável por este gate
        assertTrue(java.util.Arrays.stream(PrivateCapability.values()).noneMatch(c -> c.name().matches("(?i).*(ORDER|TRADE|WALLET|PAYMENT|GAS|WITHDRAW|TRANSFER).*")));
    }

    @Test
    void anUnknownCapabilityNameIsDeniedEvenIfTheMasterWereOpen() {
        for (String name : new String[] {"wallet", "orders", "trading", "payment", "gas", "everything", "*", "", " ", "accountdata", "private", null}) {
            assertFalse(PrivateCapabilityGate.allowed(name), String.valueOf(name));
            assertTrue(PrivateCapability.fromWire(name).isEmpty(), String.valueOf(name));
        }
    }

    @Test
    void configuredIsNotAllowedAndAMissingSecretIsNotConfigured() {
        assertEquals(ConfigState.NOT_IMPLEMENTED, PrivateCapabilityGate.configState(PrivateCapability.BINANCE_ACCOUNT_READ, true), "real status");
        assertEquals(ConfigState.NOT_CONFIGURED, PrivateCapabilityGate.configState(PrivateCapability.BINANCE_ACCOUNT_READ, false, ALL_IMPLEMENTED), "implemented but the secret is missing");
        assertEquals(ConfigState.CONFIGURED, PrivateCapabilityGate.configState(PrivateCapability.BINANCE_ACCOUNT_READ, true, ALL_IMPLEMENTED));
        assertEquals(ConfigState.CONFIGURED, PrivateCapabilityGate.configState(PrivateCapability.NOTIFICATIONS, false, ALL_IMPLEMENTED), "no secret required");
        // configurada ≠ permitida: com o segredo e tudo, mestre fechado ainda nega
        assertFalse(PrivateCapabilityGate.decide(new Config(false, Set.of(PrivateCapability.BINANCE_ACCOUNT_READ)), PrivateCapability.BINANCE_ACCOUNT_READ, ALL_FACTS, ALL_IMPLEMENTED));
        assertFalse(PrivateCapabilityGate.configured(PrivateCapability.BINANCE_ACCOUNT_READ), "the credential id is UNUSABLE: it cannot exist yet");
    }

    // ---- operações tipadas, IPC e política ------------------------------------------------------------------------------------------------

    @Test
    void everyTypedOperationIsInThePolicyNeverWeakerThanItsCapabilityAndNeverInTheIpc() {
        AuthPolicy policy = AuthPolicy.standard();
        Set<String> ipc = new HashSet<>(Protocol.OPERATIONS);
        ipc.addAll(Protocol.MARKET_OPERATIONS);
        ipc.addAll(AuthIpc.OPERATIONS);
        for (PrivateOperation op : PrivateOperation.values()) {
            AuthPolicy.Rule r = policy.rule(op.wire());
            assertEquals(op.capability(), r.capability(), op.wire());
            assertEquals(op.minRole(), r.minRole());
            assertEquals(op.recentMfa(), r.recentMfa());
            assertEquals(op.elevation(), r.elevation());
            assertEquals(op.ownership(), r.ownership());
            assertTrue(r.minRole().atLeast(op.capability().minRole()), op.wire());
            assertFalse(ipc.contains(op.wire()), op.wire() + " is not exposed by any IPC protocol yet");
        }
        assertTrue(PrivateOperation.ACCOUNT_CREDENTIAL_CONFIGURE.recentMfa() && PrivateOperation.ACCOUNT_CREDENTIAL_REMOVE.recentMfa(), "configuring/removing needs recent MFA");
        assertFalse(PrivateOperation.ACCOUNT_BALANCES.recentMfa(), "a read needs authenticated session + ownership");
        assertTrue(PrivateOperation.ACCOUNT_BALANCES.ownership() && PrivateOperation.NOTIFICATIONS_SUBSCRIBE.ownership());
        assertTrue(PrivateOperation.ADMIN_OPERATION.recentMfa() && PrivateOperation.ADMIN_OPERATION.elevation() && PrivateOperation.ADMIN_OPERATION.minRole() == Role.ADMIN);
        assertTrue(java.util.Arrays.stream(PrivateOperation.values()).noneMatch(o -> o.capability() == PrivateCapability.SECRET_INTEGRATIONS), "no UI operation maps to secret integrations");
        assertEquals(null, policy.rule("secrets.get"));
        assertEquals(null, policy.rule("notification.send"));
    }

    @Test
    void thereIsNoGenericPrivateOrProxyOperationAnywhere() throws Exception {
        List<String> banned = List.of("http.request", "binance.request", "proxy", "rawQuery", "signedRequest", "execute", "dumpSecret", "notification.send", "notifications.send", "order.create", "order.cancel",
                "leverage", "margin.set", "withdraw", "transfer", "listenKey", "stream.private", "credential.read", "credential.get", "secrets.");
        Set<String> names = new HashSet<>(AuthPolicy.standard().operations());
        names.addAll(Protocol.OPERATIONS);
        names.addAll(Protocol.MARKET_OPERATIONS);
        names.addAll(AuthIpc.OPERATIONS);
        for (String name : names) {
            for (String b : banned) {
                assertFalse(name.toLowerCase().contains(b.toLowerCase()), name + " contains " + b);
            }
        }
        for (PrivateOperation op : PrivateOperation.values()) {
            assertTrue(op.wire().matches("(notifications|account|admin)\\.[a-z.]+"), op.wire());
        }
    }

    // ---- allowlist e política Binance (design) ------------------------------------------------------------------------------------------

    @Test
    void theFutureBinanceAllowlistIsClosedGetOnlyReadOnlyAndSignedOnlyByTheService() {
        Set<String> seen = new HashSet<>();
        for (BinanceReadEndpoint e : BinanceReadEndpoint.values()) {
            assertEquals("GET", e.method(), e.name());
            assertTrue(BinanceReadEndpoint.HOSTS.contains(e.host()), e.name());
            assertTrue(e.path().matches("/(fapi/v3|sapi/v1/account)/[A-Za-z]+"), "exact fixed path: " + e.path());
            assertTrue(e.signed());
            assertTrue(e.weight() > 0 && e.weight() <= 5);
            assertTrue(seen.add(e.host() + e.path()), "no duplicate");
            assertTrue(PrivateOperation.fromWire(e.operation().wire()).isPresent());
            assertTrue(e.operation().capability() == PrivateCapability.BINANCE_ACCOUNT_READ);
            for (String forbidden : List.of("order", "leverage", "margin", "withdraw", "transfer", "listen", "userDataStream", "capital", "sub-account", "futures/transfer")) {
                assertFalse(e.path().toLowerCase().contains(forbidden.toLowerCase()), e.path() + " has " + forbidden);
            }
        }
        assertEquals(Set.of("/fapi/v3/balance", "/fapi/v3/positionRisk", "/fapi/v3/account", "/sapi/v1/account/apiRestrictions"),
                java.util.Arrays.stream(BinanceReadEndpoint.values()).map(BinanceReadEndpoint::path).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("fapi.binance.com", "api.binance.com"), BinanceReadEndpoint.HOSTS);
        assertEquals(PrivateCapability.BINANCE_ACCOUNT_READ.hosts(), BinanceReadEndpoint.HOSTS, "the capability and the allowlist agree on hosts");
    }

    @Test
    void theReadOnlyCredentialPolicyRefusesAnyKeyThatCouldMoveFundsOrTrade() {
        var ok = new AccountDtos.CredentialPermissions(true, false, false, false, false, false, false, false, false);
        assertEquals(Verdict.ACCEPTABLE, ReadOnlyCredentialPolicy.evaluate(ok));
        assertEquals(Verdict.REJECTED_NO_READING, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(false, false, false, false, false, false, false, false, false)));
        assertEquals(Verdict.REJECTED_NO_READING, ReadOnlyCredentialPolicy.evaluate(null));
        assertEquals(Verdict.REJECTED_WITHDRAWALS, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, true, false, false, false, false, false, false, false)));
        assertEquals(Verdict.REJECTED_TRANSFER, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, true, false, false, false, false, false, false)));
        assertEquals(Verdict.REJECTED_TRANSFER, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, false, false, false, false, false, false, true)));
        assertEquals(Verdict.REJECTED_MARGIN, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, false, true, false, false, false, false, false)));
        assertEquals(Verdict.REJECTED_MARGIN, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, false, false, false, false, false, true, false)));
        assertEquals(Verdict.REJECTED_TRADING, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, false, false, false, true, false, false, false)));
        assertEquals(Verdict.REJECTED_OTHER, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, false, false, false, false, true, false, false)));
        assertTrue(ReadOnlyCredentialPolicy.FUTURES_PERMISSION_UNRESOLVED, "BLOCKER CANDIDATE: futures reads without 'Enable Futures' are unproven until a real read-only key exists");
        assertEquals(Verdict.REJECTED_FUTURES, ReadOnlyCredentialPolicy.evaluate(new AccountDtos.CredentialPermissions(true, false, false, false, true, false, false, false, false)),
                "a futures-trading-capable key is refused by default, never accepted silently");
    }

    @Test
    void requestPolicyIsBoundedAndNeverRetriesAStormOrAClientError() {
        assertTrue(AccountRequestPolicy.MAX_IN_FLIGHT == 1 && AccountRequestPolicy.MAX_ATTEMPTS_ON_5XX <= 3);
        assertTrue(AccountRequestPolicy.blocksAllActivity(429) && AccountRequestPolicy.blocksAllActivity(418) && !AccountRequestPolicy.blocksAllActivity(500));
        assertFalse(AccountRequestPolicy.retryable(429, 0) || AccountRequestPolicy.retryable(418, 0), "429/418 block, they do not retry");
        assertFalse(AccountRequestPolicy.retryable(400, 0) || AccountRequestPolicy.retryable(401, 0) || AccountRequestPolicy.retryable(403, 0), "4xx never repeats");
        assertTrue(AccountRequestPolicy.retryable(503, 0) && AccountRequestPolicy.retryable(500, AccountRequestPolicy.MAX_ATTEMPTS_ON_5XX - 1));
        assertFalse(AccountRequestPolicy.retryable(503, AccountRequestPolicy.MAX_ATTEMPTS_ON_5XX), "bounded");
        assertTrue(AccountRequestPolicy.backoff(1).compareTo(AccountRequestPolicy.backoff(3)) < 0);
        assertTrue(AccountRequestPolicy.backoff(40).compareTo(AccountRequestPolicy.BACKOFF_CAP) <= 0, "capped");
        assertTrue(AccountRequestPolicy.RATE_LIMIT_BLOCK_MIN.getSeconds() >= 60);
        assertTrue(AccountRequestPolicy.RECV_WINDOW.toMillis() <= 5_000);
    }

    @Test
    void dtosAndAuditEventsCarryNoSecretSignatureOrProviderDump() {
        for (Class<?> dto : AccountDtos.class.getDeclaredClasses()) {
            for (var comp : dto.getRecordComponents()) {
                assertFalse(comp.getName().equalsIgnoreCase("uid"), "no external user id");
                assertFalse(comp.getName().toLowerCase().matches(".*(key|secret|signature|token|password|body|payload|response|accountid|email|address).*"), dto.getSimpleName() + "." + comp.getName());
            }
        }
        for (AccountAuditEvent e : AccountAuditEvent.values()) {
            assertFalse(e.name().toLowerCase().matches(".*(key|secret|signature|balance|query|token).*"), e.name());
        }
        assertEquals(8, AccountAuditEvent.values().length);
    }

    // ---- credencial: modelo e isolamento ------------------------------------------------------------------------------------------------

    @Test
    void theBinanceCredentialIsATypedUnusableIdOnlyTheServiceOwnsAndNothingElseUsesIt() throws Exception {
        assertFalse(SecretId.BINANCE_READONLY_CREDENTIAL.usable(), "no real item can be created this round");
        assertEquals(SecretId.Scope.UNUSABLE, SecretId.BINANCE_READONLY_CREDENTIAL.scope());
        for (SecretId other : new SecretId[] {SecretId.RESEND_API_KEY, SecretId.TWILIO_API_SECRET, SecretId.AUTHORITY_ENCRYPTION_KEY, SecretId.AUTHORITY_ROLLBACK_ANCHOR}) {
            assertTrue(SecretId.BINANCE_READONLY_CREDENTIAL.wireName() != other.wireName(), "never reuses an auth/provider item");
        }
        Set<String> users = new HashSet<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(f).contains("BINANCE_READONLY_CREDENTIAL")) users.add(Path.of("src/main/java/byx/service").relativize(f).toString());
            }
        }
        assertEquals(Set.of("secrets/SecretId.java", "PrivateCapability.java"), users, "only the id declaration and the capability contract mention it; no code reads it");
        // o painel nunca recebe chave/segredo: nenhuma operação de IPC ou DTO tem campo de credencial
        assertTrue(AuthIpc.OPERATIONS.stream().noneMatch(o -> o.toLowerCase().contains("credential") || o.toLowerCase().contains("secret")));
    }

    @Test
    void theNetworkAndSecretSurfaceDidNotGrow() throws Exception {
        // nenhuma chamada Binance privada: os únicos hosts de rede no serviço são os públicos de mercado e os dois provedores de autenticação
        Set<String> hostsMentioned = new HashSet<>();
        var host = java.util.regex.Pattern.compile("\"(?:https|wss)://([a-z0-9.-]+)|\"((?:fapi|fstream|api)\\.[a-z.]+\\.com)\"");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var m = host.matcher(Files.readString(f));
                while (m.find()) hostsMentioned.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        assertEquals(Set.of("fstream.binance.com", "api.resend.com", "verify.twilio.com", "fapi.binance.com", "api.binance.com"),
                hostsMentioned.stream().map(h -> h.replaceAll("^\" ?", "")).collect(java.util.stream.Collectors.toSet()),
                "public market hosts, the two auth providers and the (unused) future read-only account allowlist: nothing else");
        // o caminho "/fapi/v3/*" privado só existe como entrada da allowlist (enum), nunca em código de rede
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f);
                if (text.contains("/fapi/v3/") || text.contains("apiRestrictions")) {
                    assertEquals("account/BinanceReadEndpoint.java", Path.of("src/main/java/byx/service").relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        // ninguém usa a allowlist para abrir conexão: nenhum outro arquivo a referencia
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!f.getFileName().toString().equals("BinanceReadEndpoint.java")) {
                    assertFalse(Files.readString(f).contains("BinanceReadEndpoint"), f + " must not use the future allowlist yet");
                }
            }
        }
    }

    @Test
    void productionComposesOnlyTheProductionGateConfigAndNoCodePathCanBuildAnother() throws Exception {
        var construct = java.util.regex.Pattern.compile("new\\s+(PrivateCapabilityGate\\.)?Config\\s*\\(|Config\\.of\\(");
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = Path.of("src/main/java/byx/service").relativize(f).toString();
                String text = Files.readString(f);
                if (!rel.equals("PrivateCapabilityGate.java") && text.contains("PrivateCapabilityGate")) {
                    assertFalse(construct.matcher(text).find(), rel + " must not build a gate configuration");
                    assertFalse(text.matches("(?s).*PrivateCapabilityGate\\.decide\\((?!PrivateCapabilityGate\\.Config\\.PRODUCTION).*") && text.contains("PrivateCapabilityGate.decide("), rel + " decides only with Config.PRODUCTION");
                }
            }
        }
        String gate = Files.readString(Path.of("src/main/java/byx/service/PrivateCapabilityGate.java"));
        assertEquals(1, gate.split("new Config\\(", -1).length - 1, "exactly one Config built in main: PRODUCTION");
        assertTrue(gate.contains("PRIVATE_CAPABILITIES_ALLOWED = false;"));
        String stripped = gate.replaceAll("(?s)/\\*.*?\\*/", "");
        assertFalse(stripped.contains("getenv") || stripped.contains("getProperty") || stripped.contains("getBoolean"), "no runtime switch");
    }
}
