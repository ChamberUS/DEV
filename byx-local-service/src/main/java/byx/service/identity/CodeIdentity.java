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

    public static final class Instance {
        private final byte[] token;

        private Instance(byte[] token) {
            this.token = token.clone();
        }

        public long pid() { return MacSecurity.pidAndVersion(token) >>> 32; }
        public long pidVersion() { return MacSecurity.pidAndVersion(token) & 0xFFFFFFFFL; }
    }

    private final MacSecurity sec;

    private CodeIdentity(MacSecurity sec) {
        this.sec = sec;
    }

    /** null se não houver macOS/JNA (nenhuma confiança é concedida nesse caso). */
    public static CodeIdentity load() {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.load")) {
        try {
            return new CodeIdentity(MacSecurity.load());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }



        }
    }

    public static String requirement(String identifier, String teamId) {
        return AppIdentity.requirement(identifier, teamId);
    }

    public String selfTeamId() {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.selfTeamId")) {
        return sec.selfTeamId();



        }
    }

    public boolean selfSatisfies(String requirement) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.selfSatisfies")) { return sec.selfSatisfies(requirement);


        }
    }

    public Instance instance(long pid) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.instance")) {
        byte[] token = sec.processToken(Math.toIntExact(pid));
        return token == null ? null : new Instance(token);



        }
    }

    public Verdict checkInstance(Instance instance, String requirement, String expectedBundle) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.checkInstance")) {
        String[] path = new String[1];
        Verdict v = map(sec.checkPeer(instance.token, requirement, true, path));
        return v == Verdict.OK && !expectedBundle.equals(path[0]) ? Verdict.REQUIREMENT_FAILED : v;



        }
    }

    public String instancePath(Instance instance) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.instancePath")) { return sec.instancePath(instance.token);


        }
    }
    public int signalInstance(Instance instance, int signal) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.signalInstance")) { return sec.signalInstance(instance.token, signal);


        }
    }
    public int[] processIds() {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.processIds")) { return sec.processIds();


        }
    }
    public int processUid(int pid) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.processUid")) { return sec.processUid(pid);


        }
    }
    public int effectiveUid() {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.effectiveUid")) { return sec.effectiveUid();


        }
    }
    public String processPath(int pid) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.processPath")) { return sec.processPath(pid);


        }
    }
    public String custodyTempRoot() {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.custodyTempRoot")) { return sec.custodyTempRoot();


        }
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
        try (var timing = byx.service.identity.FencingTiming.phase("identity.checkBundle")) {
        return map(sec.checkStaticPath(bundlePath, requirement));



        }
    }

    /** Peer VIVO da conexão: o token vem do kernel; o código é validado contra o requisito e o selo; devolve também o caminho do bundle. */
    public Peer checkPeer(SocketChannel connection, String requirement) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.checkPeer")) {
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
    }

    /** Ambiente de lançamento do processo, lido do kernel (null se ilegível). */
    public List<String> launchEnvironment(long pid) {
        try (var timing = byx.service.identity.FencingTiming.phase("identity.launchEnvironment")) {
        return sec.launchEnvironment((int) pid);



        }
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
