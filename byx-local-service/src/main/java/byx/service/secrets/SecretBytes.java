package byx.service.secrets;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Valor secreto em byte[] (nunca String), de vida curta: {@link #close()} zera o buffer que NÓS controlamos. Sem toString/equals/hashCode
 * úteis (não vira chave de mapa, log nem mensagem). Limite honesto: a JVM pode manter outras cópias (GC que move objetos, buffers
 * internos do JNA/CoreFoundation, swap); isto reduz a janela, não GARANTE a eliminação.
 */
public final class SecretBytes implements AutoCloseable {
    public static final int MAX_BYTES = 4096;
    private byte[] value;

    private SecretBytes(byte[] value) {
        this.value = value;
    }

    /** Copia e valida (1..4096 bytes). O chamador deve zerar o array dele. */
    public static SecretBytes copyOf(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_BYTES) {
            throw new IllegalArgumentException("secret size"); // sem o tamanho nem o conteúdo
        }
        return new SecretBytes(source.clone());
    }

    /** Segredo aleatório de teste. */
    public static SecretBytes random(int length) {
        byte[] b = new byte[length];
        new SecureRandom().nextBytes(b);
        SecretBytes s = copyOf(b);
        Arrays.fill(b, (byte) 0);
        return s;
    }

    public int length() {
        return value == null ? 0 : value.length;
    }

    /** Comparação em tempo constante. */
    public boolean contentEquals(SecretBytes other) {
        return value != null && other.value != null && MessageDigest.isEqual(value, other.value);
    }

    /** Acesso interno ao buffer (só o backend do armazenamento). Não copie nem retenha. */
    byte[] bytes() {
        if (value == null) {
            throw new IllegalStateException("closed");
        }
        return value;
    }

    @Override
    public void close() {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
            value = null;
        }
    }

    @Override
    public String toString() {
        return "SecretBytes[redacted]";
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this); // nunca derivado do conteúdo
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }
}
