package byx.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Identidade NATIVA do peer contra processos REAIS (kernel + Security.framework): o próprio JDK de teste (assinado pela Eclipse
 * Foundation) e um processo separado assinado pela Apple. Nada aqui depende de PID, nome ou caminho enviados pelo peer.
 */
class PeerIdentityTest {
    private Path dir;
    private ServerSocketChannel server;
    private Path socket;

    @BeforeEach
    void up() throws Exception {
        dir = Files.createTempDirectory(Path.of("/tmp"), "pi");
        socket = dir.resolve("s.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
    }

    @AfterEach
    void down() throws Exception {
        server.close();
        Files.deleteIfExists(socket);
        Files.deleteIfExists(dir);
    }

    private SocketChannel connectSelf() throws Exception {
        SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX);
        client.connect(UnixDomainSocketAddress.of(socket));
        SocketChannel accepted = server.accept();
        accepted.setOption(java.net.StandardSocketOptions.SO_RCVBUF, 8192); // sem efeito esperado; só garante canal normal
        clients.add(client);
        return accepted;
    }

    private final java.util.List<SocketChannel> clients = new java.util.ArrayList<>();

    private static String jdkRequirement() {
        // identidade do binário `java` que roda este teste, lida do próprio processo (Team ID dinâmico; identificador conhecido do Temurin)
        String team = PeerIdentity.selfTeamId();
        assertNotNull(team, "the test JDK is team-signed");
        return AppIdentity.requirement("net.java.openjdk.java", team);
    }

    @Test
    void theTestJvmIsNotMistakenForThePackagedService() {
        assertEquals(IdentityPolicy.Mode.DEVELOPMENT_UNVERIFIED, IdentityPolicy.detect(AppIdentity.SERVICE_ID, AppIdentity.APP_ID).mode(),
                "an IDE/Maven JVM (generic JDK, someone else's team) is development_unverified");
        assertFalse(IdentityPolicy.detect(AppIdentity.SERVICE_ID, AppIdentity.APP_ID).strict());
    }

    @Test
    void theKernelIdentifiesTheRealPeerAndItsSignatureIsCheckedAgainstTheRequirement() throws Exception {
        try (SocketChannel accepted = connectSelf()) {
            assertTrue(PeerIdentity.fd(accepted) >= 0, "the socket fd is readable with the service's --add-opens");
            PeerVerifier.Verdict ok = PeerIdentity.forRequirement(jdkRequirement()).verify(accepted);
            assertTrue(ok.verified(), "peer = this JVM, signed by the JDK vendor: " + ok.reason());
        }
    }

    @Test
    void aDifferentIdentifierOrTeamIsRejectedEvenForTheSameRealPeer() throws Exception {
        String team = PeerIdentity.selfTeamId();
        try (SocketChannel accepted = connectSelf()) {
            assertEquals("peer_requirement_failed", PeerIdentity.forRequirement(AppIdentity.requirement(AppIdentity.APP_ID, team)).verify(accepted).reason(),
                    "right team, wrong identifier");
            assertEquals("peer_requirement_failed", PeerIdentity.forRequirement(AppIdentity.requirement("net.java.openjdk.java", "ZZZZZZZZZZ")).verify(accepted).reason(),
                    "right identifier, wrong team");
            assertEquals("peer_requirement_failed", PeerIdentity.forRequirement("identifier \"net.java.openjdk.java\" and anchor apple").verify(accepted).reason(),
                    "the JDK is not an Apple platform binary");
            assertEquals("requirement_invalid", PeerIdentity.forRequirement("this is not a requirement").verify(accepted).reason(), "a malformed requirement fails closed");
        }
    }

    @Test
    void requirementsAreBuiltOnlyFromWellFormedComponents() {
        assertEquals("identifier \"network.byx.mvp\" and anchor apple generic and certificate leaf[subject.OU] = \"W5Z65G9UP2\"",
                AppIdentity.requirement(AppIdentity.APP_ID, "W5Z65G9UP2"));
        for (String bad : List.of("a\" or anything or \"b", "x y", "", "a/b\"")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AppIdentity.requirement(bad, "W5Z65G9UP2"), bad);
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AppIdentity.requirement("ok.id", "short"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> AppIdentity.requirement("ok.id", "w5z65g9up2"));
    }

    /** Processo SEPARADO e assinado pela Apple (python3) conectando como o mesmo usuário: identidade diferente da nossa, e ambiente lido do kernel. */
    @Test
    void aSeparateSameUserProcessIsIdentifiedByTheKernelNotByWhatItClaims() throws Exception {
        String code = "import socket,sys,time\ns=socket.socket(socket.AF_UNIX)\ns.connect('" + socket + "')\ntime.sleep(8)\n";
        for (Map<String, String> env : List.of(Map.<String, String>of(), Map.of("JAVA_TOOL_OPTIONS", "-javaagent:/tmp/evil.jar"))) {
            ProcessBuilder pb = new ProcessBuilder("/usr/bin/python3", "-c", code);
            pb.environment().putAll(env);
            Process child = pb.start();
            try (SocketChannel accepted = server.accept()) {
                // 1. contra o requisito do PRODUTO (nosso identificador + nosso time): um programa Apple qualquer NÃO é o app
                String ours = AppIdentity.requirement(AppIdentity.APP_ID, "W5Z65G9UP2");
                assertEquals("peer_requirement_failed", PeerIdentity.forRequirement(ours).verify(accepted).reason());
                // 2. controle positivo da mecânica: como "qualquer binário da Apple", ele passa o requisito...
                PeerVerifier.Verdict v = PeerIdentity.forRequirement("anchor apple").verify(accepted);
                if (env.isEmpty()) {
                    assertTrue(v.verified(), "clean launch environment: " + v.reason());
                } else {
                    // 3. ...mas um ambiente de lançamento com vetor de injeção na JVM (lido do KERNEL) é recusado
                    assertEquals("peer_env_unsafe", v.reason());
                }
            } finally {
                child.destroyForcibly();
                child.waitFor();
            }
        }
    }

    @Test
    void aPeerWithoutAReadableSocketFdIsNeverVerified() {
        SocketChannel notUnix = null;
        try {
            notUnix = SocketChannel.open(); // canal TCP não conectado: não é o peer de nada
            assertFalse(PeerIdentity.forRequirement("anchor apple").verify(notUnix).verified());
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (notUnix != null) {
                try {
                    notUnix.close();
                } catch (Exception ignored) {
                    // nada
                }
            }
        }
    }

    @Test
    void theModeAndTheRequirementNeverReadEnvironmentOrProperties() throws Exception {
        for (String f : List.of("IdentityPolicy.java", "PeerIdentity.java", "AppIdentity.java")) {
            String src = Files.readString(Path.of("src/main/java/byx/service/identity", f));
            String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
            assertFalse(code.contains("getenv") || code.contains("getProperty") || code.contains("getBoolean"), f + " reads no environment or property");
        }
        String main = Files.readString(Path.of("src/main/java/byx/service/ServiceMain.java"));
        assertTrue(main.contains("IdentityPolicy.detect("), "the mode comes from the process's own signature");
    }
}
