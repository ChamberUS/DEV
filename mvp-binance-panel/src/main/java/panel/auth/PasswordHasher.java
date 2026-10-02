package panel.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Hash de senha Argon2id (parâmetros OWASP por padrão). Formato PHC: $argon2id$v=19$m=..,t=..,p=..$salt$hash. */
public class PasswordHasher {
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final int memoryKb;
    private final int iterations;
    private final int parallelism;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() {
        this(19_456, 2, 1);
    }

    public PasswordHasher(int memoryKb, int iterations, int parallelism) {
        this.memoryKb = memoryKb;
        this.iterations = iterations;
        this.parallelism = parallelism;
    }

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] out = derive(password, salt, memoryKb, iterations, parallelism);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + memoryKb + ",t=" + iterations + ",p=" + parallelism + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(out);
    }

    public boolean verify(char[] password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !parts[1].equals("argon2id")) {
                return false;
            }
            int m = 0, t = 0, p = 0;
            for (String kv : parts[3].split(",")) {
                String[] x = kv.split("=");
                switch (x[0]) {
                    case "m" -> m = Integer.parseInt(x[1]);
                    case "t" -> t = Integer.parseInt(x[1]);
                    case "p" -> p = Integer.parseInt(x[1]);
                    default -> { }
                }
            }
            Base64.Decoder d = Base64.getDecoder();
            byte[] expected = d.decode(parts[5]);
            return MessageDigest.isEqual(expected, derive(password, d.decode(parts[4]), m, t, p));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int m, int t, int p) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(m).withIterations(t).withParallelism(p).withSalt(salt).build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] pw = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)).array();
        byte[] out = new byte[HASH_BYTES];
        gen.generateBytes(pw, out);
        Arrays.fill(pw, (byte) 0);
        return out;
    }
}
