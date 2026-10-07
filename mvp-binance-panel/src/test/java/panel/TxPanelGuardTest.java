package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import panel.localservice.AuthorityGateway;
import panel.shell.ShellRoutes;
import panel.ui.CommandPalette;

/** O painel só PEDE transações: sem chave, sem assinatura, sem rota genérica, sem Lab fora do LOCAL_QA, e tudo nega (TX_DISABLED) por padrão. */
class TxPanelGuardTest {
    private static final Path MAIN = Path.of("src/main/java/panel");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void thePanelNeverSignsBroadcastsOrHoldsKeyMaterial() throws IOException {
        // preexistentes e apenas de VERIFICAÇÃO/redação: prova de posse de endereço (ADR-036, só verifica assinatura de terceiros) e a lista de termos que o diagnóstico nunca imprime
        java.util.Set<String> verificationOnly = java.util.Set.of("CosmosWalletProof.java", "DiagnosticsReport.java");
        for (Path p : sources()) {
            if (verificationOnly.contains(p.getFileName().toString())) {
                String own = Files.readString(p);
                assertFalse(own.contains("PrivateKey") || own.contains("KeyPairGenerator") || own.contains("Signature.getInstance") && own.contains("initSign"), p + " only verifies");
                continue;
            }
            String src = Files.readString(p);
            for (String forbidden : new String[] {"\"tx.sign\"", "\"tx.broadcast\"", "\"tx.signBytes\"", "signBytes", "broadcastRawTx", "sendAnyMessage", "protobufAny", "genericCosmosRequest", "mnemonic", "seedPhrase",
                    "secp256k1", "SignDoc", "SIGN_MODE", "FakeSigner", "enableTx", "allowTx", "skipTxSecurity", "txTestMode"}) {
                assertFalse(src.contains(forbidden), p + " must not contain " + forbidden);
            }
        }
    }

    @Test
    void theOnlyTxOperationsTheClientCanSendAreTheFiveTypedOnes() throws IOException {
        String client = Files.readString(MAIN.resolve("localservice/AuthorityClient.java"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(tx\\.[A-Za-z]+)\"").matcher(client);
        java.util.Set<String> ops = new java.util.TreeSet<>();
        while (m.find()) {
            ops.add(m.group(1));
        }
        assertEquals(java.util.Set.of("tx.prepareBankSend", "tx.getQuote", "tx.confirm", "tx.getStatus", "tx.cancel"), ops);
    }

    @Test
    void gatewayDoublesAndTheDefaultAnswerTxDisabled() {
        AuthorityGateway g = new AuthorityGateway() {
            @Override public Reply login(String i, char[] p) { return null; }
            @Override public Reply sessionStatus() { return null; }
            @Override public Reply beginSecondFactor() { return null; }
            @Override public Reply verifySecondFactor(String c, String code) { return null; }
            @Override public Reply sendSecondFactorSms() { return null; }
            @Override public Reply verifySecondFactorSms(String code) { return null; }
            @Override public Reply adminElevation() { return null; }
            @Override public Reply changePassword(char[] c, char[] n) { return null; }
            @Override public Reply enrollTrustedDevice() { return null; }
            @Override public Reply listTrustedDevices() { return null; }
            @Override public Reply revokeTrustedDevice(String d) { return null; }
            @Override public Reply logout() { return null; }
            @Override public boolean hasSession() { return false; }
            @Override public void forgetSession() { }
        };
        assertEquals("TX_DISABLED", g.txPrepareBankSend("0".repeat(32), "primary", "byx1x", "1", "", "LOW").code());
        assertEquals("TX_DISABLED", g.txConfirm("0".repeat(32), "0".repeat(32)).code());
        assertEquals("TX_DISABLED", g.txGetStatus("0".repeat(32)).code());
        assertFalse(g.txCancel("0".repeat(32)).ok());
    }

    @Test
    void theLabExistsOnlyInLocalQaAndNeverInTheRailOrTheDefaultPalette() throws IOException {
        assertTrue(CommandPalette.commands(true, true, false).stream().noneMatch(c -> "t-tx-lab".equals(c.target())), "DEFAULT palette: no Transaction Lab");
        assertTrue(CommandPalette.commands(false, false, false).stream().noneMatch(c -> "t-tx-lab".equals(c.target())));
        assertTrue(CommandPalette.commands(true, true, true).stream().anyMatch(c -> "t-tx-lab".equals(c.target())), "LOCAL_QA palette has it");
        String app = Files.readString(MAIN.resolve("app/PanelApp.java"));
        int guard = app.indexOf("if (qaBuild()) { // Transaction Lab");
        int register = app.indexOf("TxLabBuild.register(views, ctx)");
        assertTrue(guard > 0 && register > guard && register - guard < 400, "registered only inside the LOCAL_QA guard");
        assertFalse(app.contains("panel.txview."));
        String qa = Files.readString(Path.of("src/build-local-qa/java/panel/app/TxLabBuild.java"));
        assertTrue(qa.contains("new panel.txview.TransactionLab("));
        if (panel.app.TxLabBuild.available()) {
            assertTrue(getClass().getClassLoader().getResource("panel/txview/TransactionLab.class") != null);
        } else {
            assertEquals(null, getClass().getClassLoader().getResource("panel/txview/TransactionLab.class"));
            String stub = Files.readString(Path.of("src/build-default/java/panel/app/TxLabBuild.java"));
            assertFalse(stub.contains("panel.txview"));
        }
        // fora do rail: a rota existe só para a navegação por command palette (ordem -1, sem rótulo de rail)
        String routes = Files.readString(MAIN.resolve("shell/ShellRoutes.java"));
        assertTrue(routes.contains("add(\"t-tx-lab\", ShellContext.BYX, \"Transaction Lab\", null, null, -1)"));
        assertTrue(ShellRoutes.class != null);
    }

    @Test
    void noProductionScreenOtherThanTheLabReferencesTheTxClientMethods() throws IOException {
        for (Path p : sources()) {
            String name = p.getFileName().toString();
            if (name.equals("AuthorityGateway.java") || name.equals("AuthorityClient.java") || name.equals("TxLabService.java")) {
                continue;
            }
            String src = Files.readString(p);
            assertFalse(src.contains("txPrepareBankSend") || src.contains("txConfirm("), p + " must not call the tx client outside the Lab adapter");
        }
    }
}
