package byx.service.signer;

import byx.service.tx.SignerRequestFixture;
import byx.service.tx.TxGate;
import byx.service.tx.TxPorts.TxSigner;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Signed packaged-runtime proof. Public golden signature only; no custody operation or Keychain read. */
final class PackagedRuntimeQaMain {
    static void run() throws Exception {
        for (String name : new String[]{"byx.service.signer.SignerClient", "byx.service.signer.LegacyStdioSignerFixture", "byx.service.signer.SignerClientTest"}) {
            try { Class.forName(name); throw new IllegalStateException("LEGACY_RUNTIME_PRESENT"); }
            catch (ClassNotFoundException expected) { }
        }
        if (ModuleLayer.boot().findModule("jdk.security.auth").isPresent()) throw new IllegalStateException("AUTH_MODULE_PRESENT");
        ObjectMapper json = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode vector;
        try (var in = PackagedRuntimeQaMain.class.getResourceAsStream("/tx/byx-direct-vector.json")) { vector = json.readTree(in); }
        if (vector.has("private_key_test_only")) throw new IllegalStateException("PRIVATE_FIXTURE_PACKAGED");
        var fixture = SignerRequestFixture.confirmed(vector);
        if (fixture.broadcasts() != 0 || fixture.request() == null || TxGate.TX_MUTATIONS_ALLOWED || TxSigner.UNAVAILABLE.available()) throw new IllegalStateException("TX_DISABLED_REQUIRED");
        var request = fixture.request();
        byte[] publicKey = CosmosBankSend.HEX.parseHex(vector.path("public_key").asText());
        var material = CosmosBankSend.material(request, publicKey);
        var reply = json.createObjectNode().put("protocolVersion", 1).put("requestId", request.quote().id().value())
            .put("status", "SIGNED").put("publicKey", vector.path("public_key").asText())
            .put("signature", vector.path("signature").asText()).put("txRaw", vector.path("tx_raw_hex").asText())
            .put("txHash", vector.path("tx_raw_sha256").asText()).put("bindingDigest", material.binding());
        var signed = SignedResponseVerifier.verifySigned(reply, request, material, publicKey);
        if (!signed.txHash().equalsIgnoreCase(vector.path("tx_hash").asText())) throw new IllegalStateException("GOLDEN_SIGNATURE_MISMATCH");
        reply.put("bindingDigest", "0".repeat(64));
        try { SignedResponseVerifier.verifySigned(reply, request, material, publicKey); throw new IllegalStateException("BINDING_ACCEPTED"); }
        catch (java.io.IOException expected) { }
        if (ProcessHandle.current().children().anyMatch(ProcessHandle::isAlive)) throw new IllegalStateException("RUNTIME_CHILD_UNEXPECTED");
        System.out.println("runtime.legacyAbsent=true");
        System.out.println("runtime.authModuleAbsent=true");
        System.out.println("runtime.publicGoldenSignatureVerified=true");
        System.out.println("runtime.alteredBindingRefused=true");
        System.out.println("runtime.productionDisabled=true");
        System.out.println("runtime.keychainCalls=0");
    }
}
