package byx.service.signer;

import byx.service.tx.TxPorts.SignedTx;
import byx.service.tx.TxPorts.TxSignRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/** Pure, independently checked public signature response. No process, key or custody authority. */
final class SignedResponseVerifier {
    private SignedResponseVerifier() { }
    private static final Set<String> RESPONSE = Set.of("protocolVersion", "requestId", "status", "publicKey", "signature", "txRaw", "txHash", "bindingDigest");
    /** Independent verification of a V2.1S response: every binding, low-S signature, TxRaw and tx hash. */
    static SignedTx verifySigned(JsonNode root, TxSignRequest request, CosmosBankSend.Material material, byte[] publicKey) throws IOException {
        if (root == null || !root.isObject() || root.size() != RESPONSE.size()) throw new IOException("SIGNING_FAILED");
        var names = root.fieldNames(); while (names.hasNext()) if (!RESPONSE.contains(names.next())) throw new IOException("SIGNING_FAILED");
        if (!root.get("protocolVersion").isIntegralNumber() || !root.get("protocolVersion").canConvertToInt() || root.get("protocolVersion").intValue() != 1) throw new IOException("SIGNING_FAILED");
        for (String key : RESPONSE) if (!key.equals("protocolVersion") && !root.get(key).isTextual()) throw new IOException("SIGNING_FAILED");
        if (!root.get("requestId").textValue().equals(request.quote().id().value()) || !root.get("status").textValue().equals("SIGNED")
                || !root.get("bindingDigest").textValue().equals(material.binding()) || !root.get("publicKey").textValue().equals(CosmosBankSend.HEX.formatHex(publicKey))) throw new IOException("SIGNING_FAILED");
        String signature = root.get("signature").textValue(), rawHex = root.get("txRaw").textValue();
        if (!signature.matches("[0-9a-f]{128}") || !rawHex.matches("[0-9a-f]{2,4096}") || rawHex.length() % 2 != 0) throw new IOException("SIGNING_FAILED");
        byte[] sig = CosmosBankSend.HEX.parseHex(signature), raw = CosmosBankSend.HEX.parseHex(rawHex);
        if (!Arrays.equals(raw, CosmosBankSend.raw(material, sig)) || !root.get("txHash").textValue().equals(CosmosBankSend.hash(raw)) || !CosmosBankSend.verify(material, sig)) throw new IOException("SIGNING_FAILED");
        return new SignedTx(raw, root.get("txHash").textValue().toUpperCase(java.util.Locale.ROOT));
    }

}
