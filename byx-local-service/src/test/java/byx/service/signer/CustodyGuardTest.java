package byx.service.signer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The custody QA client is inert in production: no caller, no network, no shell, no PATH, and an unsigned JVM (this test run) is never trusted as a service. */
class CustodyGuardTest {
    private static final Path MAIN = Path.of("src/main/java/byx/service");

    @Test
    void anUnsignedMavenJvmCannotEvenConstructTheClientFromARealBundle() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(CustodyClient.CustodyException.class, CustodyClient::new);
        assertEquals("SIGNER_UNTRUSTED", e.code());
        assertEquals("NOT_RUNNING_FROM_A_BUNDLE", e.reason());
    }

    @Test
    void noProductionClassReferencesTheCustodyClientOrTheQaPackages() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                if (name.equals("CustodyClient.java") || name.equals("CustodyAuthority.java")) {
                    continue;
                }
                String src = Files.readString(p);
                assertFalse(src.contains("CustodyClient"), p + " must not use the custody QA client");
                assertFalse(src.contains("CustodyQa"), p + " must not use the custody QA runner");
            }
        }
        for (String f : new String[] {"ServiceMain.java", "ServiceInstance.java", "tx/TxProduction.java", "tx/TxService.java"}) {
            assertFalse(Files.readString(MAIN.resolve(f)).contains("byx.service.signer"), f + " does not compose any signer package class");
        }
    }

    @Test
    void theCustodyClientHasNoIpNetworkNoShellNoPathLookupAndNoConfiguration() throws IOException {
        String src = Files.readString(MAIN.resolve("signer/CustodyClient.java"));
        for (String forbidden : new String[] {"java.net.http", "new Socket(", "ServerSocket", "InetSocketAddress", "InetAddress", "new URL(", "URI.create(\"http", "HttpClient", "/bin/sh", "/bin/zsh", "bash",
                "System.getenv", "System.getProperty", "Runtime.getRuntime", "sh -c", "cmd.exe"}) {
            assertFalse(src.contains(forbidden), "CustodyClient must not contain " + forbidden);
        }
        assertTrue(src.contains("StandardProtocolFamily.UNIX") && src.contains("builder.environment().clear()"));
        assertTrue(src.contains("redirectError(ProcessBuilder.Redirect.DISCARD)"));
    }

    @Test
    void theSignerQaGroupAndNamespaceAreExclusiveToTheSignerAndAbsentFromEveryOtherEntitlementSource() throws IOException {
        assertEquals("byx.signer.qa.synthetic.v1", CustodyClient.NAMESPACE);
        Path pkg = Path.of("..", "byx-packaging");
        for (String f : new String[] {"entitlements/app.entitlements", "entitlements/service.entitlements", "entitlements/service.keychain.entitlements.template"}) {
            Path p = pkg.resolve(f);
            if (Files.exists(p)) {
                String s = Files.readString(p);
                assertFalse(s.contains("signer.qa") || s.contains("<key>keychain-access-groups</key>"), f + " must not carry the signer QA group or any explicit Keychain group");
            }
        }
    }
}
