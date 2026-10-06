package byx.service.identity;

import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;

/**
 * Chave do PEER de uma conexão, vinda só do kernel: pid e pidversion do token de auditoria (LOCAL_PEERTOKEN). Dois processos nunca
 * compartilham a chave, e um pid reaproveitado tem outra pidversion. Nada vem do cliente. Retorna {@link #NONE} se o kernel não informa
 * (então nenhuma autenticação é aceita nessa conexão).
 */
public final class PeerKeys {
    public static final long NONE = 0L;

    @FunctionalInterface
    public interface Provider {
        long keyOf(SocketChannel connection);
    }

    private PeerKeys() {
    }

    /** Provedor nativo (macOS). Falha ⇒ NONE. */
    public static Provider kernel() {
        MacSecurity sec;
        try {
            sec = MacSecurity.load();
        } catch (RuntimeException | LinkageError e) {
            return ch -> NONE;
        }
        return ch -> {
            try {
                int fd = fd(ch);
                if (fd < 0) {
                    return NONE;
                }
                byte[] token = sec.peerToken(fd);
                return token == null ? NONE : MacSecurity.pidAndVersion(token);
            } catch (RuntimeException | LinkageError e) {
                return NONE;
            }
        };
    }

    private static int fd(SocketChannel ch) {
        try {
            Field f = ch.getClass().getDeclaredField("fd");
            f.setAccessible(true);
            Object fdo = f.get(ch);
            Field n = fdo.getClass().getDeclaredField("fd");
            n.setAccessible(true);
            return n.getInt(fdo);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
