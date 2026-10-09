package byx.service.migration;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Referência EXATA do hash de senha do painel LEGADO (cópia fiel do algoritmo antigo, só para QA/testes de regressão com dados SINTÉTICOS): o painel derivava o Argon2id
 * sobre {@code UTF_8.encode(CharBuffer.wrap(senha)).array()}, isto é, o array de apoio INTEIRO do ByteBuffer, que tem capacidade (int)(n*1,1) e por isso termina em bytes NUL
 * de preenchimento quando n >= 10. Não é usado em produção (só {@link MigrateQaMain} e testes referenciam esta classe).
 */
final class LegacyPanelHash {
    private LegacyPanelHash() {
    }

    static String hash(char[] password, int memoryKb, int iterations, int parallelism) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] out = derive(password, salt, memoryKb, iterations, parallelism);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + memoryKb + ",t=" + iterations + ",p=" + parallelism + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(out);
    }

    static byte[] derive(char[] password, byte[] salt, int m, int t, int p) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(m).withIterations(t)
                .withParallelism(p).withSalt(salt).build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] pw = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password)).array(); // <- o comportamento legado: array de apoio inteiro
        byte[] out = new byte[32];
        gen.generateBytes(pw, out);
        Arrays.fill(pw, (byte) 0);
        return out;
    }
}
