package byx.service.identity;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;

/**
 * Ligações mínimas com as APIs REAIS do macOS usadas na identidade do peer (conferidas nos cabeçalhos do SDK 26):
 * {@code getsockopt(SOL_LOCAL=0, LOCAL_PEERTOKEN=0x006)} (token de auditoria do peer, dado pelo kernel),
 * {@code SecCodeCopyGuestWithAttributes(kSecGuestAttributeAudit)}, {@code SecRequirementCreateWithString},
 * {@code SecCodeCheckValidityWithErrors}, {@code SecStaticCodeCheckValidityWithErrors}, {@code SecCodeCopySelf},
 * {@code SecCodeCopySigningInformation} e {@code sysctl(KERN_PROCARGS2)}. Nada aqui lê PID, caminho ou nome vindos do cliente.
 */
final class MacSecurity {
    static final int SOL_LOCAL = 0;
    static final int LOCAL_PEERTOKEN = 0x006;
    static final int CF_UTF8 = 0x08000100;
    static final int ERR_SEC_CS_UNSIGNED = -67062;
    static final int SEC_CS_SIGNING_INFORMATION = 1 << 1;
    static final int SEC_CS_CHECK_NESTED_CODE = 1 << 3;
    static final int SEC_CS_STRICT_VALIDATE = 1 << 4;
    static final int CTL_KERN = 1;
    static final int KERN_PROCARGS2 = 49;
    private static final int MAX_PROCARGS = 512 * 1024;

    interface LibC extends Library {
        int task_name_for_pid(int self, int pid, IntByReference task);

        int task_info(int task, int flavor, byte[] info, IntByReference count);

        int mach_port_deallocate(int self, int port);

        int proc_listallpids(int[] buffer, int size);

        int proc_pidinfo(int pid, int flavor, long arg, byte[] buffer, int size);

        int proc_pidpath(int pid, byte[] buffer, int size);

        int proc_pidpath_audittoken(byte[] token, byte[] buffer, int size);

        int proc_signal_with_audittoken(byte[] token, int signal);

        int geteuid();

        long confstr(int name, byte[] buffer, long size);
        int getsockopt(int fd, int level, int name, byte[] value, IntByReference len);

        int sysctl(int[] name, int namelen, byte[] oldp, LongByReference oldlenp, Pointer newp, long newlen);
    }

    interface CF extends Library {
        Pointer CFDataCreate(Pointer alloc, byte[] bytes, long length);

        Pointer CFStringCreateWithCString(Pointer alloc, String s, int encoding);

        Pointer CFDictionaryCreate(Pointer alloc, Pointer[] keys, Pointer[] values, long count, Pointer keyCallbacks, Pointer valueCallbacks);

        Pointer CFDictionaryGetValue(Pointer dict, Pointer key);

        Pointer CFURLCreateWithFileSystemPath(Pointer alloc, Pointer path, long pathStyle, byte isDirectory);

        byte CFURLGetFileSystemRepresentation(Pointer url, byte resolveAgainstBase, byte[] buffer, long maxBufLen);

        byte CFStringGetCString(Pointer s, byte[] buffer, long size, int encoding);

        void CFRelease(Pointer p);
    }

    interface Sec extends Library {
        int SecCodeCopyGuestWithAttributes(Pointer host, Pointer attributes, int flags, PointerByReference guest);

        int SecCodeCopySelf(int flags, PointerByReference self);

        int SecCodeCopyStaticCode(Pointer code, int flags, PointerByReference staticCode);

        int SecRequirementCreateWithString(Pointer text, int flags, PointerByReference requirement);

        int SecCodeCheckValidityWithErrors(Pointer code, int flags, Pointer requirement, PointerByReference errors);

        int SecStaticCodeCheckValidityWithErrors(Pointer staticCode, int flags, Pointer requirement, PointerByReference errors);

        int SecCodeCopySigningInformation(Pointer code, int flags, PointerByReference information);

        int SecStaticCodeCreateWithPath(Pointer url, int flags, PointerByReference staticCode);

        int SecCodeCopyPath(Pointer staticCode, int flags, PointerByReference url);
    }

    private static final String SEC_PATH = "/System/Library/Frameworks/Security.framework/Security";
    private static final String CF_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

    private final LibC libc;
    private final CF cf;
    private final Sec sec;
    private final Pointer auditKey;
    private final Pointer teamKey;
    private final Pointer keyCallbacks;
    private final Pointer valueCallbacks;

    private MacSecurity() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            throw new IllegalStateException("not macOS");
        }
        libc = Native.load("c", LibC.class);
        cf = Native.load(CF_PATH, CF.class);
        sec = Native.load(SEC_PATH, Sec.class);
        NativeLibrary secLib = NativeLibrary.getInstance(SEC_PATH);
        NativeLibrary cfLib = NativeLibrary.getInstance(CF_PATH);
        auditKey = secLib.getGlobalVariableAddress("kSecGuestAttributeAudit").getPointer(0);
        teamKey = secLib.getGlobalVariableAddress("kSecCodeInfoTeamIdentifier").getPointer(0);
        keyCallbacks = cfLib.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks");
        valueCallbacks = cfLib.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks");
    }

    static MacSecurity load() {
        return new MacSecurity();
    }

    byte[] processToken(int pid) {
        int self = NativeLibrary.getInstance("c").getGlobalVariableAddress("mach_task_self_").getInt(0);
        IntByReference port = new IntByReference();
        if (libc.task_name_for_pid(self, pid, port) != 0) {
            return null;
        }
        try {
            byte[] token = new byte[32];
            IntByReference count = new IntByReference(8);
            return libc.task_info(port.getValue(), 15, token, count) == 0 && count.getValue() == 8 ? token : null;
        } finally {
            libc.mach_port_deallocate(self, port.getValue());
        }
    }

    int[] processIds() {
        int[] ids = new int[65536];
        int count = libc.proc_listallpids(ids, ids.length * 4);
        if (count <= 0 || count >= ids.length) {
            throw new IllegalStateException("PROCESS_ENUMERATION_UNPROVEN");
        }
        return java.util.Arrays.copyOf(ids, count);
    }

    // -1: absent/zombie, -2: unreadable; otherwise effective UID. No process name is an identity.
    int processUid(int pid) {
        byte[] b = new byte[64];
        int n = libc.proc_pidinfo(pid, 13, 0, b, b.length);
        int error = Native.getLastError();
        if (n != b.length) {
            return n == 0 && error == 3 ? -1 : -2;
        }
        var data = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.nativeOrder());
        return data.getInt(12) == 5 ? -1 : data.getInt(36);
    }

    String processPath(int pid) {
        byte[] b = new byte[4096];
        return libc.proc_pidpath(pid, b, b.length) > 0 ? Native.toString(b) : null;
    }

    // null is proved ESRCH only; every other native error remains an uncertainty.
    String instancePath(byte[] token) {
        byte[] b = new byte[4096];
        int n = libc.proc_pidpath_audittoken(token, b, b.length);
        int error = Native.getLastError();
        if (n > 0) {
            return Native.toString(b);
        }
        if (error == 3) {
            return null;
        }
        throw new IllegalStateException("INSTANCE_STATE_UNPROVEN");
    }

    int signalInstance(byte[] token, int signal) {
        return libc.proc_signal_with_audittoken(token, signal);
    }

    String custodyTempRoot() {
        byte[] b = new byte[4096];
        long n = libc.confstr(65537, b, b.length); // _CS_DARWIN_USER_TEMP_DIR
        if (n <= 0 || n >= b.length) {
            throw new IllegalStateException("PRIVATE_ROOT_UNAVAILABLE");
        }
        return Native.toString(b);
    }

    int effectiveUid() {
        return libc.geteuid();
    }

    // ---- o próprio processo -----------------------------------------------------------------------------------------------------

    /** Team ID da assinatura do próprio processo (null se não assinado por um time: ad-hoc, JDK de outro time não conta como "nosso"). */
    String selfTeamId() {
        PointerByReference self = new PointerByReference();
        if (sec.SecCodeCopySelf(0, self) != 0) {
            return null;
        }
        PointerByReference info = new PointerByReference();
        try {
            if (sec.SecCodeCopySigningInformation(self.getValue(), SEC_CS_SIGNING_INFORMATION, info) != 0) {
                return null;
            }
            Pointer team = cf.CFDictionaryGetValue(info.getValue(), teamKey);
            return team == null ? null : javaString(team);
        } finally {
            release(info.getValue());
            release(self.getValue());
        }
    }

    boolean selfSatisfies(String requirement) {
        PointerByReference self = new PointerByReference();
        if (sec.SecCodeCopySelf(0, self) != 0) {
            return false;
        }
        Pointer req = requirement(requirement);
        try {
            return req != null && sec.SecCodeCheckValidityWithErrors(self.getValue(), 0, req, null) == 0;
        } finally {
            release(req);
            release(self.getValue());
        }
    }

    // ---- o peer da conexão ----------------------------------------------------------------------------------------------------------

    /** Token de auditoria (32 bytes) do peer, entregue pelo kernel para o socket; null se indisponível. */
    byte[] peerToken(int fd) {
        byte[] token = new byte[32];
        IntByReference len = new IntByReference(32);
        return libc.getsockopt(fd, SOL_LOCAL, LOCAL_PEERTOKEN, token, len) == 0 && len.getValue() == 32 ? token : null;
    }

    /** pid e pidversion do token de auditoria (audit_token_t.val[5] e val[7], ordem do host). */
    static long pidAndVersion(byte[] token) {
        long pid = Integer.toUnsignedLong(le(token, 20));
        long version = Integer.toUnsignedLong(le(token, 28));
        return (pid << 32) | version;
    }

    private static int le(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24;
    }

    /** Resultado da checagem de código do guest identificado PELO TOKEN do kernel. */
    enum Check { OK, NO_GUEST, BAD_REQUIREMENT, REQUIREMENT_FAILED, BUNDLE_MODIFIED }

    Check checkPeer(byte[] token, String requirement, boolean sealedBundle) {
        return checkPeer(token, requirement, sealedBundle, null);
    }

    /** Igual a {@link #checkPeer(byte[], String, boolean)}; se {@code pathOut} != null recebe o caminho do código do guest (bundle) quando a checagem passa. */
    Check checkPeer(byte[] token, String requirement, boolean sealedBundle, String[] pathOut) {
        Pointer data = cf.CFDataCreate(null, token, token.length);
        Pointer attrs = cf.CFDictionaryCreate(null, new Pointer[] {auditKey}, new Pointer[] {data}, 1, keyCallbacks, valueCallbacks);
        PointerByReference guest = new PointerByReference();
        Pointer req = null;
        PointerByReference staticCode = new PointerByReference();
        try {
            if (data == null || attrs == null || sec.SecCodeCopyGuestWithAttributes(null, attrs, 0, guest) != 0 || guest.getValue() == null) {
                return Check.NO_GUEST;
            }
            req = requirement(requirement);
            if (req == null) {
                return Check.BAD_REQUIREMENT;
            }
            if (sec.SecCodeCheckValidityWithErrors(guest.getValue(), 0, req, null) != 0) {
                return Check.REQUIREMENT_FAILED;
            }
            if (sealedBundle) {
                // selo dos recursos (jars, .cfg, bibliotecas): detecta bundle adulterado em disco; estrito e com código aninhado
                if (sec.SecCodeCopyStaticCode(guest.getValue(), 0, staticCode) != 0
                        || sec.SecStaticCodeCheckValidityWithErrors(staticCode.getValue(), SEC_CS_CHECK_NESTED_CODE | SEC_CS_STRICT_VALIDATE, req, null) != 0) {
                    return Check.BUNDLE_MODIFIED;
                }
            }
            if (pathOut != null) {
                if (staticCode.getValue() == null && sec.SecCodeCopyStaticCode(guest.getValue(), 0, staticCode) != 0) {
                    return Check.BUNDLE_MODIFIED;
                }
                pathOut[0] = pathOf(staticCode.getValue());
                if (pathOut[0] == null) {
                    return Check.NO_GUEST;
                }
            }
            return Check.OK;
        } finally {
            release(staticCode.getValue());
            release(req);
            release(guest.getValue());
            release(attrs);
            release(data);
        }
    }

    /** Caminho (no disco) do código identificado por um SecStaticCode; null se indisponível. */
    private String pathOf(Pointer staticCode) {
        PointerByReference url = new PointerByReference();
        if (sec.SecCodeCopyPath(staticCode, 0, url) != 0 || url.getValue() == null) {
            return null;
        }
        try {
            byte[] buf = new byte[4096];
            if (cf.CFURLGetFileSystemRepresentation(url.getValue(), (byte) 1, buf, buf.length) == 0) {
                return null;
            }
            int n = 0;
            while (n < buf.length && buf[n] != 0) {
                n++;
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } finally {
            release(url.getValue());
        }
    }

    /** Valida o código ESTÁTICO em um caminho (bundle) contra um requisito, com selo estrito e código aninhado. Antes de executar qualquer coisa. */
    Check checkStaticPath(String path, String requirement) {
        Pointer ps = cf.CFStringCreateWithCString(null, path, CF_UTF8);
        Pointer url = ps == null ? null : cf.CFURLCreateWithFileSystemPath(null, ps, 0, (byte) 1);
        PointerByReference sc = new PointerByReference();
        Pointer req = null;
        try {
            if (url == null || sec.SecStaticCodeCreateWithPath(url, 0, sc) != 0 || sc.getValue() == null) {
                return Check.NO_GUEST;
            }
            req = requirement(requirement);
            if (req == null) {
                return Check.BAD_REQUIREMENT;
            }
            // two steps, so each defect has its own verdict: (1) is there a valid, sealed signature at all (strict, nested)? (2) does it satisfy the requirement?
            int seal = sec.SecStaticCodeCheckValidityWithErrors(sc.getValue(), SEC_CS_CHECK_NESTED_CODE | SEC_CS_STRICT_VALIDATE, null, null);
            if (seal == ERR_SEC_CS_UNSIGNED) {
                return Check.NO_GUEST; // unsigned
            }
            if (seal != 0) {
                return Check.BUNDLE_MODIFIED; // broken seal / modified resources / invalid signature
            }
            return sec.SecStaticCodeCheckValidityWithErrors(sc.getValue(), SEC_CS_CHECK_NESTED_CODE | SEC_CS_STRICT_VALIDATE, req, null) == 0 ? Check.OK : Check.REQUIREMENT_FAILED;
        } finally {
            release(sc.getValue());
            release(req);
            release(url);
            release(ps);
        }
    }

    /** Variáveis de ambiente do processo (do kernel, KERN_PROCARGS2: o bloco recebido no exec); null se não puder ler (falha fechada). */
    java.util.List<String> launchEnvironment(int pid) {
        byte[] buf = new byte[MAX_PROCARGS];
        LongByReference len = new LongByReference(buf.length);
        if (libc.sysctl(new int[] {CTL_KERN, KERN_PROCARGS2, pid}, 3, buf, len, null, 0) != 0) {
            return null;
        }
        int n = (int) Math.min(len.getValue(), buf.length);
        if (n < 8) {
            return null;
        }
        int argc = le(buf, 0);
        int i = 4;
        while (i < n && buf[i] != 0) { // caminho do executável
            i++;
        }
        while (i < n && buf[i] == 0) { // preenchimento
            i++;
        }
        int seen = 0;
        while (i < n && seen < argc) { // argumentos
            while (i < n && buf[i] != 0) {
                i++;
            }
            i++;
            seen++;
        }
        java.util.List<String> env = new java.util.ArrayList<>();
        while (i < n) {
            int start = i;
            while (i < n && buf[i] != 0) {
                i++;
            }
            if (i > start) {
                env.add(new String(buf, start, i - start, StandardCharsets.UTF_8));
            }
            i++;
        }
        return env;
    }

    // ---- CoreFoundation ---------------------------------------------------------------------------------------------------------------

    private Pointer requirement(String text) {
        Pointer s = cf.CFStringCreateWithCString(null, text, CF_UTF8);
        if (s == null) {
            return null;
        }
        try {
            PointerByReference req = new PointerByReference();
            return sec.SecRequirementCreateWithString(s, 0, req) == 0 ? req.getValue() : null;
        } finally {
            release(s);
        }
    }

    private String javaString(Pointer cfString) {
        byte[] buf = new byte[256];
        if (cf.CFStringGetCString(cfString, buf, buf.length, CF_UTF8) == 0) {
            return null;
        }
        int n = 0;
        while (n < buf.length && buf[n] != 0) {
            n++;
        }
        return new String(buf, 0, n, StandardCharsets.UTF_8);
    }

    private void release(Pointer p) {
        if (p != null) {
            cf.CFRelease(p);
        }
    }
}
