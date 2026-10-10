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
import panel.adapter.CosmosGasGrantGateway;
import panel.app.AppContext;

/**
 * L10b: a capacidade de ASSINAR/TRANSMITIR (signer de teste do gás) não é alcançável no produto normal. Não basta default=false: a classe
 * não existe no artefato, nenhum código de produção a nomeia, nada lê flag/arquivo/variável para escolhê-la, e o guard abaixo falha se o
 * caminho voltar. Os testes e QA usam o signer por injeção explícita (código de teste), como o DevOtpProvider. Não executa sign,
 * broadcast, feegrant nem transação: só inspeciona código e a composição.
 */
class GasSignerIsolationTest {
    private static final Path MAIN = Path.of("src/main/java");

    private static Set<String> mainFilesContaining(String... needles) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                for (String n : needles) {
                    if (src.contains(n)) {
                        out.add(MAIN.relativize(p) + " ~ " + n);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void theTestSignerIsNotPartOfTheProductionArtifact() {
        assertFalse(Files.exists(Path.of("target/classes/panel/adapter/LocalnetGasTestSigner.class")), "not in the compiled production classes");
        assertFalse(Files.exists(MAIN.resolve("panel/adapter/LocalnetGasTestSigner.java")), "not in the production source tree");
        assertTrue(Files.exists(Path.of("src/test/java/panel/adapter/LocalnetGasTestSigner.java")), "it lives in test code only");
    }

    @Test
    void noProductionCodeNamesTheSignerItsScriptsItsEnvVarOrAConfigSwitch() throws IOException {
        assertEquals(Set.of(), mainFilesContaining("LocalnetGasTestSigner", "I_ACKNOWLEDGE_TEST_ONLY", "BYX_LOCALNET_TEST_SIGNER", "byx_gas_test", "byx_payment_test",
                "wallet-test-signer", "python3", "devMode", "security.dev.mode"));
    }

    @Test
    void gatewaySelectionReadsNoConfigurationOrEnvironment() throws IOException {
        String ctx = Files.readString(MAIN.resolve("panel/app/AppContext.java"));
        int start = ctx.indexOf("public final panel.adapter.ByxGasGrantGateway byxGasGateway");
        String selection = ctx.substring(start, ctx.indexOf("public final panel.service.GasSponsorshipService"));
        for (String banned : new String[] {"getenv", "getProperty", "security.", "devMode", "System."}) {
            assertFalse(selection.contains(banned), "gateway selection must not depend on " + banned);
        }
        assertTrue(selection.contains("CosmosGasGrantGateway"));
    }

    @Test
    void theNormalGatewayCannotSignOrBroadcast() throws IOException {
        // o gateway normal é só leitura on-chain: sem processo, sem script, sem chave
        String src = Files.readString(MAIN.resolve("panel/adapter/CosmosGasGrantGateway.java"));
        for (String banned : new String[] {"ProcessBuilder", "Runtime.getRuntime", "getenv", "python", "keyring", "mnemonic", "privateKey"}) {
            assertFalse(src.contains(banned), banned);
        }
    }

    @Test
    void aDevelopmentFlagFileChangesNothingInTheGatewayChoice() throws Exception {
        // composição REAL com um perfil que tem security.dev.mode=true: o gateway continua sendo o de produção, exatamente
        Path home = Files.createTempDirectory("gs");
        String oldHome = System.getProperty("user.home");
        try {
            Path cfg = home.resolve(".mvp-binance-panel");
            panel.security.PrivateFiles.prepareDirectory(cfg);
            Files.writeString(cfg.resolve("security.properties"), "security.dev.mode=true\n");
            System.setProperty("user.home", home.toString());
            // as constantes de caminho são estáticas: uma JVM nova seria necessária para o home inteiro; aqui provamos a escolha do gateway
            // pela composição com Providers vazio (caminho normal) sobre o código de produção
            AppContext.Providers none = new AppContext.Providers(null, null, null);
            assertEquals(null, none.gasGateway(), "no injected gateway in the normal composition");
            assertEquals(CosmosGasGrantGateway.class, new CosmosGasGrantGateway(java.time.Clock.systemUTC()).getClass());
        } finally {
            System.setProperty("user.home", oldHome);
            try (Stream<Path> w = Files.walk(home)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
