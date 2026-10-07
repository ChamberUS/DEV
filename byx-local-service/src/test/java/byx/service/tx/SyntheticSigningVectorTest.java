package byx.service.tx;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.junit.jupiter.api.Test;

/** TEST VECTOR — NOT A REAL WALLET. Independent wire encoder and crypto, test classpath only. */
class SyntheticSigningVectorTest {
    private static final HexFormat HEX = HexFormat.of();
    private static byte[] text(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] bytes : arrays) out.writeBytes(bytes);
        return out.toByteArray();
    }
    private static byte[] varint(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (n >= 128) { out.write((int) (n & 127) | 128); n >>>= 7; }
        out.write((int) n); return out.toByteArray();
    }
    private static byte[] field(int id, byte[] bytes) { return concat(varint(id * 8L + 2), varint(bytes.length), bytes); }
    private static byte[] integer(int id, long n) { return concat(varint(id * 8L), varint(n)); }
    private static byte[] any(String url, byte[] value) { return concat(field(1, text(url)), field(2, value)); }
    private static byte[] hash(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static byte[] fixed(BigInteger n) {
        byte[] b = n.toByteArray(), result = new byte[32];
        System.arraycopy(b, Math.max(0, b.length - 32), result, Math.max(0, 32 - b.length), Math.min(32, b.length));
        return result;
    }
    private static int polymod(int[] values) {
        int chk = 1;
        int[] generators = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (int v : values) {
            int top = chk >>> 25; chk = (chk & 0x1ffffff) << 5 ^ v;
            for (int i = 0; i < 5; i++) if (((top >>> i) & 1) != 0) chk ^= generators[i];
        }
        return chk;
    }
    private static String bech32(byte[] address) {
        ByteArrayOutputStream words = new ByteArrayOutputStream(); int acc = 0, bits = 0;
        for (byte b : address) { acc = (acc << 8) | (b & 255); bits += 8; while (bits >= 5) { bits -= 5; words.write((acc >>> bits) & 31); } }
        if (bits > 0) words.write((acc << (5 - bits)) & 31);
        byte[] data = words.toByteArray(); String hrp = "byx", alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        int[] values = new int[hrp.length() * 2 + 1 + data.length + 6]; int j = 0;
        for (char c : hrp.toCharArray()) values[j++] = c >>> 5;
        values[j++] = 0; for (char c : hrp.toCharArray()) values[j++] = c & 31;
        for (byte b : data) values[j++] = b;
        int checksum = polymod(values) ^ 1;
        StringBuilder result = new StringBuilder(hrp + "1");
        for (byte b : data) result.append(alphabet.charAt(b));
        for (int i = 0; i < 6; i++) result.append(alphabet.charAt((checksum >>> (5 * (5 - i))) & 31));
        return result.toString();
    }
    private static void matches(JsonNode v, String name, byte[] bytes) throws Exception {
        assertEquals(v.path(name + "_hex").asText(), HEX.formatHex(bytes), name);
        assertEquals(v.path(name + "_sha256").asText(), HEX.formatHex(hash(bytes)), name + " SHA-256");
    }

    @Test void independentEncodingAndRfc6979SignatureMatchCosmosSdkByteForByte() throws Exception {
        JsonNode v;
        try (var in = getClass().getResourceAsStream("/tx/byx-direct-vector.json")) {
            assertNotNull(in); v = new ObjectMapper().readTree(in);
        }
        assertEquals("TEST VECTOR — NOT A REAL WALLET", v.path("warning").asText());
        assertEquals("secp256k1", v.path("key_type").asText());
        assertEquals("m/44'/118'/0'/0/0", v.path("hd_path").asText());
        var curve = SECNamedCurves.getByName("secp256k1");
        var domain = new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
        BigInteger scalar = new BigInteger(1, HEX.parseHex(v.path("private_key_test_only").asText()));
        assertEquals(BigInteger.ONE, scalar);
        var point = curve.getG().multiply(scalar).normalize(); byte[] publicKey = point.getEncoded(true);
        assertEquals(v.path("public_key").asText(), HEX.formatHex(publicKey));
        RIPEMD160Digest ripemd = new RIPEMD160Digest(); byte[] sha = hash(publicKey), address = new byte[20];
        ripemd.update(sha, 0, sha.length); ripemd.doFinal(address, 0);
        assertEquals(v.path("address_hex").asText(), HEX.formatHex(address));
        assertEquals(v.path("address").asText(), bech32(address));
        byte[] amount = concat(field(1, text("ubyx")), field(2, text(v.path("amount_ubyx").asText())));
        byte[] send = concat(field(1, text(v.path("address").asText())), field(2, text(v.path("recipient").asText())), field(3, amount));
        byte[] body = concat(field(1, any("/cosmos.bank.v1beta1.MsgSend", send)), field(2, text(v.path("memo").asText())));
        byte[] key = any("/cosmos.crypto.secp256k1.PubKey", field(1, publicKey));
        byte[] signer = concat(field(1, key), field(2, field(1, integer(1, 1))), integer(3, v.path("sequence").asLong()));
        byte[] fee = concat(field(1, concat(field(1, text("ubyx")), field(2, text(v.path("fee_ubyx").asText())))), integer(2, v.path("gas_limit").asLong()));
        byte[] auth = concat(field(1, signer), field(2, fee));
        byte[] doc = concat(field(1, body), field(2, auth), field(3, text(v.path("chain_id").asText())), integer(4, v.path("account_number").asLong()));
        matches(v, "tx_body", body); matches(v, "auth_info", auth); matches(v, "sign_doc", doc);
        var signerAlgorithm = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signerAlgorithm.init(true, new ECPrivateKeyParameters(scalar, domain));
        byte[] digest = hash(doc); BigInteger[] rs = signerAlgorithm.generateSignature(digest);
        if (rs[1].compareTo(curve.getN().shiftRight(1)) > 0) rs[1] = curve.getN().subtract(rs[1]);
        byte[] signature = concat(fixed(rs[0]), fixed(rs[1]));
        assertEquals(v.path("signature").asText(), HEX.formatHex(signature));
        var verifier = new ECDSASigner(); verifier.init(false, new ECPublicKeyParameters(point, domain));
        assertTrue(verifier.verifySignature(digest, rs[0], rs[1]));
        byte[] altered = Arrays.copyOf(doc, doc.length); altered[altered.length - 1] ^= 1;
        assertFalse(verifier.verifySignature(hash(altered), rs[0], rs[1]));
        byte[] raw = concat(field(1, body), field(2, auth), field(3, signature));
        matches(v, "tx_raw", raw); assertEquals(v.path("tx_hash").asText(), HEX.formatHex(hash(raw)));
        assertTrue(v.path("sdk_verified").asBoolean()); assertTrue(v.path("sdk_tx_roundtrip").asBoolean());
    }
}
