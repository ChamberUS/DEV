package panel.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.ECDSASigner;
import panel.model.*;

/** ADR-036 Amino sign bytes; Cosmos secp256k1 SHA-256 / low-S / compressed public key. */
public final class CosmosWalletProof {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CosmosWalletProof() { }
    public static byte[] message(WalletChallenge c) {
        return json(new TreeMap<>(Map.of("nonce", c.nonce(), "user_id", Long.toString(c.userId()),
                "address", c.address(), "chain_id", c.chainId(), "genesis_fingerprint", c.genesisFingerprint(),
                "issued_at", c.issuedAt(), "expires_at", c.expiresAt(), "context", c.context())));
    }
    public static byte[] signBytes(WalletChallenge c) {
        return json(new TreeMap<>(Map.of("account_number", "0", "chain_id", "", "fee",
                new TreeMap<>(Map.of("amount", List.of(), "gas", "0")), "memo", "", "sequence", "0",
                "msgs", List.of(new TreeMap<>(Map.of("type", "sign/MsgSignData", "value",
                        new TreeMap<>(Map.of("signer", c.address(), "data", Base64.getEncoder().encodeToString(message(c))))))))));
    }
    private static byte[] json(Object value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid signing document", e); }
    }
    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String address(byte[] publicKey) {
        if (publicKey.length != 33 || (publicKey[0] != 2 && publicKey[0] != 3)) throw new IllegalArgumentException("Compressed secp256k1 key required");
        var curve = CustomNamedCurves.getByName("secp256k1");
        if (!curve.getCurve().decodePoint(publicKey).isValid()) throw new IllegalArgumentException("Invalid public key");
        byte[] sha = sha256(publicKey), hash = new byte[20];
        var ripemd = new RIPEMD160Digest(); ripemd.update(sha, 0, sha.length); ripemd.doFinal(hash, 0);
        // BIP-173 Bech32 encoding (20 bytes = exactly 32 five-bit groups).
        String alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
        List<Integer> words = new ArrayList<>(); int acc = 0, bits = 0;
        for (byte b : hash) { acc = (acc << 8) | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; words.add((acc >>> bits) & 31); } }
        List<Integer> values = new ArrayList<>(); String hrp = "byx";
        for (char c : hrp.toCharArray()) values.add(c >> 5); values.add(0);
        for (char c : hrp.toCharArray()) values.add(c & 31); values.addAll(words);
        values.addAll(Collections.nCopies(6, 0));
        int chk = 1; int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (int v : values) { int top = chk >>> 25; chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) if (((top >>> i) & 1) != 0) chk ^= gen[i]; }
        chk ^= 1; StringBuilder out = new StringBuilder("byx1");
        for (int v : words) out.append(alphabet.charAt(v));
        for (int i = 0; i < 6; i++) out.append(alphabet.charAt((chk >>> (5 * (5 - i))) & 31));
        return out.toString();
    }
    public static boolean verify(WalletProof proof) {
        try {
            if (proof.publicKey().length() > 64 || proof.signature().length() > 100) return false;
            byte[] key = Base64.getDecoder().decode(proof.publicKey()), sig = Base64.getDecoder().decode(proof.signature());
            if (sig.length != 64 || !address(key).equals(proof.challenge().address())) return false;
            var curve = CustomNamedCurves.getByName("secp256k1");
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
            if (r.signum() <= 0 || r.compareTo(curve.getN()) >= 0 || s.signum() <= 0 || s.compareTo(curve.getN().shiftRight(1)) > 0) return false;
            var domain = new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
            var verifier = new ECDSASigner();
            verifier.init(false, new ECPublicKeyParameters(curve.getCurve().decodePoint(key), domain));
            return verifier.verifySignature(sha256(signBytes(proof.challenge())), r, s);
        } catch (IllegalArgumentException | NullPointerException e) { return false; }
    }
}
