package byx.service.signer;

import byx.service.tx.TxPorts.TxSignRequest;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;

/** Independent closed MsgSend encoding and public-key verification. No private-key operations. */
final class CosmosBankSend {
    static final String KEY_TYPE = "/cosmos.crypto.secp256k1.PubKey";
    static final HexFormat HEX = HexFormat.of();
    record Material(Map<String, Object> request, byte[] body, byte[] auth, byte[] doc, String binding, byte[] publicKey) { }

    static Material material(TxSignRequest r, byte[] publicKey) {
        var q = r.quote();
        var i = r.intent();
        if (!"byx".equals(q.chainId()) || !r.key().address().equals(q.sender()) || !q.sender().value().equals(address(publicKey))
                || !i.digest().equals(q.intentDigest()) || !i.memo().digest().equals(q.memoDigest())
                || !i.recipient().equals(q.recipient()) || !i.amount().equals(q.amount())
                || q.accountNumber().value() > (1L << 62) - 1 || q.sequence().value() > (1L << 62) - 1 || q.gasLimit().value() > 1_000_000_000_000L) {
            throw new IllegalArgumentException("SIGNING_FAILED");
        }
        byte[] send = join(field(1, text(q.sender().value())), field(2, text(q.recipient().value())), field(3, coin(q.amount().value().toString())));
        byte[] body = field(1, any("/cosmos.bank.v1beta1.MsgSend", send));
        if (!i.memo().text().isEmpty()) body = join(body, field(2, text(i.memo().text())));
        byte[] info = join(field(1, any(KEY_TYPE, field(1, publicKey))), field(2, field(1, number(1, 1))), number(3, q.sequence().value()));
        byte[] auth = join(field(1, info), field(2, join(field(1, coin(q.fee().value().toString())), number(2, q.gasLimit().value()))));
        byte[] doc = join(field(1, body), field(2, auth), field(3, text(q.chainId())), number(4, q.accountNumber().value()));
        if (body.length > 1024 || auth.length > 512) throw new IllegalArgumentException("SIGNING_FAILED");
        String binding = hash(canonical(text("BYX-SIGNER-BINDING-V1"), text(q.id().value()), text(q.intentDigest().value()), body, auth, doc));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("protocolVersion", 1); req.put("requestId", q.id().value()); req.put("chainId", q.chainId());
        req.put("keyReference", r.key().keyId()); req.put("intentKeyReference", i.sender().label());
        req.put("publicKeyType", KEY_TYPE); req.put("publicKey", HEX.formatHex(publicKey));
        req.put("sender", q.sender().value()); req.put("recipient", q.recipient().value()); req.put("amount", q.amount().value().toString());
        req.put("memo", i.memo().text()); req.put("fee", q.fee().value().toString()); req.put("gasLimit", Long.toString(q.gasLimit().value()));
        req.put("accountNumber", Long.toString(q.accountNumber().value())); req.put("sequence", Long.toString(q.sequence().value()));
        req.put("expectedIntentDigest", q.intentDigest().value()); req.put("expectedBindingDigest", binding);
        return new Material(Map.copyOf(req), body, auth, doc, binding, publicKey.clone());
    }

    static boolean verify(Material m, byte[] signature) {
        if (signature.length != 64) return false;
        var c = SECNamedCurves.getByName("secp256k1");
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(signature, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(signature, 32, 64));
        if (r.signum() <= 0 || r.compareTo(c.getN()) >= 0 || s.signum() <= 0 || s.compareTo(c.getN().shiftRight(1)) > 0) return false;
        var verifier = new ECDSASigner();
        verifier.init(false, new ECPublicKeyParameters(c.getCurve().decodePoint(m.publicKey()), new ECDomainParameters(c.getCurve(), c.getG(), c.getN(), c.getH())));
        return verifier.verifySignature(digest(m.doc()), r, s);
    }
    static byte[] raw(Material m, byte[] sig) { return join(field(1, m.body()), field(2, m.auth()), field(3, sig)); }
    static byte[] text(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] join(byte[]... parts) { var out = new ByteArrayOutputStream(); for (byte[] p : parts) out.writeBytes(p); return out.toByteArray(); }
    static byte[] varint(long v) { var out = new ByteArrayOutputStream(); while (v >= 128) { out.write((int) (v & 127) | 128); v >>>= 7; } out.write((int) v); return out.toByteArray(); }
    static byte[] field(int n, byte[] b) { return join(varint(n * 8L + 2), varint(b.length), b); }
    static byte[] number(int n, long v) { return v == 0 ? new byte[0] : join(varint(n * 8L), varint(v)); }
    static byte[] any(String url, byte[] b) { return join(field(1, text(url)), field(2, b)); }
    static byte[] coin(String amount) { return join(field(1, text("ubyx")), field(2, text(amount))); }
    static byte[] canonical(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] p : parts) { out.write(p.length >>> 24); out.write(p.length >>> 16); out.write(p.length >>> 8); out.write(p.length); out.writeBytes(p); }
        return out.toByteArray();
    }
    static byte[] digest(byte[] b) { try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    static String hash(byte[] b) { return HEX.formatHex(digest(b)); }
    private static int polymod(int[] values) {
        int chk = 1; int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (int v : values) { int top = chk >>> 25; chk = (chk & 0x1ffffff) << 5 ^ v; for (int i = 0; i < 5; i++) if ((top >>> i & 1) != 0) chk ^= gen[i]; }
        return chk;
    }
    static String address(byte[] pub) {
        if (pub.length != 33 || pub[0] != 2 && pub[0] != 3) throw new IllegalArgumentException("SIGNING_FAILED");
        var c = SECNamedCurves.getByName("secp256k1");
        if (!c.getCurve().decodePoint(pub).isValid()) throw new IllegalArgumentException("SIGNING_FAILED");
        var ri = new RIPEMD160Digest(); byte[] sha = digest(pub), addr = new byte[20]; ri.update(sha, 0, sha.length); ri.doFinal(addr, 0);
        var words = new ByteArrayOutputStream(); int acc = 0, bits = 0;
        for (byte b : addr) { acc = acc << 8 | b & 255; bits += 8; while (bits >= 5) { bits -= 5; words.write(acc >>> bits & 31); } }
        byte[] data = words.toByteArray(); int[] values = new int[7 + data.length + 6]; int[] hrp = {3, 3, 3, 0, 2, 25, 24};
        System.arraycopy(hrp, 0, values, 0, 7); for (int n = 0; n < data.length; n++) values[7 + n] = data[n];
        int chk = polymod(values) ^ 1; String alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"; var out = new StringBuilder("byx1");
        for (byte b : data) out.append(alphabet.charAt(b)); for (int n = 5; n >= 0; n--) out.append(alphabet.charAt(chk >>> (n * 5) & 31));
        return out.toString();
    }
}
