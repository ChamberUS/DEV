package byx.service.identity;

import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.List;

/**
 * Fachada PÚBLICA e estreita da verificação nativa de identidade de código (Security.framework): checa o código estático em um caminho antes de
 * executá-lo, identifica o peer de um socket Unix pelo token de auditoria do KERNEL e valida o código vivo por trás desse token. Nada vem do peer.
 */
public final class CodeIdentity {
    public enum Verdict { OK, UNAVAILABLE, REQUIREMENT_FAILED, SEAL_BROKEN, NO_CODE }

    public record Peer(long pid, long pidVersion, Verdict verdict, String codePath) { }

    private final MacSecurity sec;

    private CodeIdentity(MacSecurity sec) {
        this.sec = sec;
    }

    /** null se não houver macOS/JNA (nenhuma confiança é concedida nesse caso). */
    public static CodeIdentity load() {
        try {
            return new CodeIdentity(MacSecurity.load());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static String requirement(String identifier, String teamId) {
        return AppIdentity.requirement(identifier, teamId);
    }

    public String selfTeamId() {
        return sec.selfTeamId();
    }

    private static Verdict map(MacSecurity.Check c) {
        return switch (c) {
            case OK -> Verdict.OK;
            case NO_GUEST -> Verdict.NO_CODE;
            case BAD_REQUIREMENT -> Verdict.UNAVAILABLE;
            case REQUIREMENT_FAILED -> Verdict.REQUIREMENT_FAILED;
            case BUNDLE_MODIFIED -> Verdict.SEAL_BROKEN;
        };
    }

    /** Código estático em {@code bundlePath}: assinatura, requisito e selo estrito (recursos e código aninhado). */
    public Verdict checkBundle(String bundlePath, String requirement) {
        return map(sec.checkStaticPath(bundlePath, requirement));
    }

    /** Peer VIVO da conexão: o token vem do kernel; o código é validado contra o requisito e o selo; devolve também o caminho do bundle. */
    public Peer checkPeer(SocketChannel connection, String requirement) {
        int fd = fd(connection);
        byte[] token = fd < 0 ? null : sec.peerToken(fd);
        if (token == null) {
            return new Peer(-1, -1, Verdict.UNAVAILABLE, null);
        }
        long key = MacSecurity.pidAndVersion(token);
        String[] path = new String[1];
        Verdict v = map(sec.checkPeer(token, requirement, true, path));
        return new Peer(key >>> 32, key & 0xFFFFFFFFL, v, path[0]);
    }

    /** Ambiente de lançamento do processo, lido do kernel (null se ilegível). */
    public List<String> launchEnvironment(long pid) {
        return sec.launchEnvironment((int) pid);
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
