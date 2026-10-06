package byx.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Verificador de senha Argon2id no formato PHC ($argon2id$v=19$m=..,t=..,p=..$salt$hash), a MESMA construção auditada do painel
 * (BouncyCastle 1.78.1; padrão OWASP m=19456 KiB, t=2, p=1, sal de 16 B, hash de 32 B). Sal individual; comparação em tempo constante
 * ({@link MessageDigest#isEqual}); parâmetros lidos de um hash armazenado são LIMITADOS (um registro adulterado não pode pedir memória
 * ou tempo arbitrários). Conta inexistente executa {@link #verifyDummy} para reduzir enumeração por tempo.
 */
public final class PasswordVerifier {
    public record Params(int memoryKb, int iterations, int parallelism) {
    }

    public static final Params DEFAULT = new Params(19_456, 2, 1);
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int MAX_MEMORY_KB = 262_144;
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_PARALLELISM = 4;

    private final Params params;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    private static final java.util.concurrent.atomic.AtomicLong DERIVATIONS = new java.util.concurrent.atomic.AtomicLong();

    public PasswordVerifier() {
        this(DEFAULT);
    }

    public PasswordVerifier(Params params) {
        this.params = params;
        char[] junk = "dummy-password-for-timing-equalization".toCharArray();
        this.dummyHash = hash(junk);
        Arrays.fill(junk, '\0');
    }

    public Params params() {
        return params;
    }

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] out = derive(password, salt, params.memoryKb(), params.iterations(), params.parallelism());
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        String phc = "$argon2id$v=19$m=" + params.memoryKb() + ",t=" + params.iterations() + ",p=" + params.parallelism() + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(out);
        Arrays.fill(out, (byte) 0);
        return phc;
    }

    /** O verificador PHC é utilizável por este serviço (algoritmo, parâmetros dentro dos limites, sal e hash decodificáveis)? Sem derivar nada. */
    public static boolean compatible(String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (encoded.length() > 256 || parts.length != 6 || !parts[1].equals("argon2id") || !parts[2].equals("v=19")) {
                return false;
            }
            int m = 0;
            int t = 0;
            int p = 0;
            for (String kv : parts[3].split(",")) {
                String[] x = kv.split("=");
                switch (x[0]) {
                    case "m" -> m = Integer.parseInt(x[1]);
                    case "t" -> t = Integer.parseInt(x[1]);
                    case "p" -> p = Integer.parseInt(x[1]);
                    default -> {
                        return false;
                    }
                }
            }
            if (m < 8 || m > MAX_MEMORY_KB || t < 1 || t > MAX_ITERATIONS || p < 1 || p > MAX_PARALLELISM) {
                return false;
            }
            Base64.Decoder d = Base64.getDecoder();
            byte[] salt = d.decode(parts[4]);
            return salt.length >= 8 && salt.length <= 64 && d.decode(parts[5]).length == HASH_BYTES;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Só o algoritmo e os parâmetros (nunca sal nem hash): "argon2id v=19 m=19456,t=2,p=1". */
    public static String algorithmOf(String encoded) {
        String[] parts = encoded.split("\\$");
        return parts.length == 6 ? parts[1] + " " + parts[2] + " " + parts[3] : "unknown";
    }

    public boolean verify(char[] password, String encoded) {
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !parts[1].equals("argon2id") || !parts[2].equals("v=19")) {
                return false;
            }
            int m = 0;
            int t = 0;
            int p = 0;
            for (String kv : parts[3].split(",")) {
                String[] x = kv.split("=");
                switch (x[0]) {
                    case "m" -> m = Integer.parseInt(x[1]);
                    case "t" -> t = Integer.parseInt(x[1]);
                    case "p" -> p = Integer.parseInt(x[1]);
                    default -> {
                        return false;
                    }
                }
            }
            if (m < 8 || m > MAX_MEMORY_KB || t < 1 || t > MAX_ITERATIONS || p < 1 || p > MAX_PARALLELISM) {
                return false;
            }
            Base64.Decoder d = Base64.getDecoder();
            byte[] expected = d.decode(parts[5]);
            byte[] salt = d.decode(parts[4]);
            if (salt.length < 8 || salt.length > 64 || expected.length != HASH_BYTES) {
                return false;
            }
            byte[] got = derive(password, salt, m, t, p);
            boolean ok = MessageDigest.isEqual(expected, got);
            Arrays.fill(got, (byte) 0);
            return ok;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Custo equivalente ao de uma verificação real (conta inexistente): sempre falso. */
    public boolean verifyDummy(char[] password) {
        verify(password, dummyHash);
        return false;
    }

    /** Quantas derivações Argon2 já ocorreram neste processo (teste: tentativas equivalentes custam o mesmo). */
    public static long derivations() {
        return DERIVATIONS.get();
    }

    private static byte[] derive(char[] password, byte[] salt, int m, int t, int p) {
        DERIVATIONS.incrementAndGet();
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m).withIterations(t).withParallelism(p).withSalt(salt).build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
        byte[] pw = new byte[bb.remaining()];
        bb.get(pw);
        if (bb.hasArray()) {
            Arrays.fill(bb.array(), (byte) 0);
        }
        byte[] out = new byte[HASH_BYTES];
        gen.generateBytes(pw, out);
        Arrays.fill(pw, (byte) 0);
        return out;
    }
}
