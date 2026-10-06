package byx.service.identity;

import byx.service.Log;
import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identidade REAL do peer de uma conexão Unix no macOS (mecanismo B: socket Unix + Security.framework):
 * <ol>
 *   <li>o kernel entrega o token de auditoria do peer ({@code LOCAL_PEERTOKEN}); nada vem do cliente;</li>
 *   <li>o guest de código é obtido pelo token (sem a corrida de PID) e confrontado com o requisito do componente legítimo
 *       (identificador + cadeia Apple + o mesmo Team ID);</li>
 *   <li>o ambiente de lançamento do peer, lido do kernel, não pode trazer vetores de injeção na JVM
 *       ({@code JAVA_TOOL_OPTIONS}, {@code _JAVA_OPTIONS}, {@code JDK_JAVA_OPTIONS}, {@code CLASSPATH}, {@code DYLD_*}): um lançador assinado
 *       com um agente injetado teria a identidade legítima mas código alheio;</li>
 *   <li>o selo do bundle (jars, configuração, bibliotecas) é validado de forma estrita, uma vez por processo (cache por pid+pidversion).</li>
 * </ol>
 * Qualquer falha fecha a conexão. Pid, bundleId, caminho ou nome enviados pelo cliente nunca entram. Limite: não protege contra quem
 * troca o bundle no disco ENTRE o lançamento e a conexão (TOCTOU) — instale em local que o usuário comum não grave (ex.: /Applications).
 */
public final class PeerIdentity implements PeerVerifier {
    private static final Set<String> BANNED_ENV_PREFIXES = Set.of("JAVA_TOOL_OPTIONS=", "_JAVA_OPTIONS=", "JDK_JAVA_OPTIONS=", "CLASSPATH=", "DYLD_", "JAVA_OPTIONS=");
    private final MacSecurity sec;
    private final String requirement;
    private final ConcurrentHashMap<Long, Boolean> sealCache = new ConcurrentHashMap<>();

    PeerIdentity(MacSecurity sec, String requirement) {
        this.sec = sec;
        this.requirement = requirement;
    }

    /** Verificador nativo para um requisito arbitrário (testes verificam o próprio JDK como peer real). */
    public static PeerIdentity forRequirement(String requirement) {
        return new PeerIdentity(MacSecurity.load(), requirement);
    }

    /** Team ID do próprio processo (null se ele não é assinado por um time). */
    public static String selfTeamId() {
        return MacSecurity.load().selfTeamId();
    }

    @Override
    public Verdict verify(SocketChannel connection) {
        try {
            int fd = fd(connection);
            if (fd < 0) {
                return Verdict.no("fd_unavailable");
            }
            byte[] token = sec.peerToken(fd);
            if (token == null) {
                return Verdict.no("peer_token_unavailable");
            }
            long key = MacSecurity.pidAndVersion(token);
            boolean needSeal = !Boolean.TRUE.equals(sealCache.get(key));
            switch (sec.checkPeer(token, requirement, needSeal)) {
                case OK -> {
                }
                case NO_GUEST -> {
                    return Verdict.no("peer_code_unavailable");
                }
                case BAD_REQUIREMENT -> {
                    return Verdict.no("requirement_invalid");
                }
                case REQUIREMENT_FAILED -> {
                    return Verdict.no("peer_requirement_failed");
                }
                case BUNDLE_MODIFIED -> {
                    return Verdict.no("peer_bundle_modified");
                }
            }
            List<String> env = sec.launchEnvironment((int) (key >>> 32));
            if (env == null) {
                return Verdict.no("peer_env_unreadable");
            }
            for (String e : env) {
                for (String banned : BANNED_ENV_PREFIXES) {
                    if (e.startsWith(banned)) {
                        return Verdict.no("peer_env_unsafe");
                    }
                }
            }
            if (needSeal) {
                if (sealCache.size() > 256) {
                    sealCache.clear();
                }
                sealCache.put(key, Boolean.TRUE);
            }
            return Verdict.ok();
        } catch (RuntimeException | LinkageError e) {
            Log.event("peer_verify_error", e.getClass().getSimpleName());
            return Verdict.no("peer_verify_error");
        }
    }

    /**
     * Descritor do socket. O JDK não o expõe; a leitura por reflexão exige --add-opens java.base/sun.nio.ch e java.base/java.io, que só o
     * lançador empacotado do serviço define. Sem eles (IDE) devolve -1 e o peer fica NÃO verificado (falha fechada).
     */
    static int fd(SocketChannel ch) {
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
