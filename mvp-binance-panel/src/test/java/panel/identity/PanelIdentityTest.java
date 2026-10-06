package panel.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** O painel só confia no serviço de identidade verificada; e as constantes/cópias de identidade não divergem das do empacotamento e do serviço. */
class PanelIdentityTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void thePanelJvmInTestsIsDevelopmentUnverified() {
        assertEquals(IdentityPolicy.Mode.DEVELOPMENT_UNVERIFIED, IdentityPolicy.detect(AppIdentity.APP_ID, AppIdentity.SERVICE_ID).mode());
    }

    @Test
    void identifiersMatchThePackagingSourceOfTruth() throws Exception {
        Path env = ROOT.resolve("byx-packaging/identity.env");
        assumeTrue(Files.exists(env), "sibling packaging project not present");
        Map<String, String> kv = new java.util.HashMap<>();
        for (String line : Files.readAllLines(env)) {
            if (line.matches("[A-Z_]+=.*")) {
                kv.put(line.substring(0, line.indexOf('=')), line.substring(line.indexOf('=') + 1).trim());
            }
        }
        assertEquals(kv.get("BYX_APP_ID"), AppIdentity.APP_ID);
        assertEquals(kv.get("BYX_SERVICE_ID"), AppIdentity.SERVICE_ID);
    }

    @Test
    void theIdentityLayerIsTheSameCodeAsTheServiceCopy() throws Exception {
        Path svc = ROOT.resolve("byx-local-service/src/main/java/byx/service/identity");
        assumeTrue(Files.isDirectory(svc), "sibling service project not present");
        for (String f : List.of("AppIdentity.java", "PeerVerifier.java", "IdentityPolicy.java", "MacSecurity.java", "PeerIdentity.java")) {
            assertEquals(normalize(Files.readString(svc.resolve(f))), normalize(Files.readString(Path.of("src/main/java/panel/identity", f))), f + " diverged between service and panel");
        }
    }

    private static String normalize(String s) {
        return s.replaceAll("(?m)^package .*;$", "").replaceAll("(?m)^import byx\\.service\\.Log;$", "").replaceAll("(?m)^\\s*(Log\\.event|// falha de verificação).*$", "")
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("\\s+", " ").replaceAll("Identidade do produto[^.]*\\.", "");
    }

    @Test
    void theIdentityModeNeverReadsConfigurationOrEnvironment() throws Exception {
        for (String f : List.of("IdentityPolicy.java", "PeerIdentity.java", "AppIdentity.java")) {
            String code = Files.readString(Path.of("src/main/java/panel/identity", f)).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
            assertFalse(code.contains("getenv") || code.contains("getProperty") || code.contains("getBoolean"), f);
        }
        Properties none = new Properties();
        assertTrue(none.isEmpty());
    }
}
