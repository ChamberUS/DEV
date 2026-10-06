package panel.identity;

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
    static final int SEC_CS_SIGNING_INFORMATION = 1 << 1;
    static final int SEC_CS_CHECK_NESTED_CODE = 1 << 3;
    static final int SEC_CS_STRICT_VALIDATE = 1 << 4;
    static final int CTL_KERN = 1;
    static final int KERN_PROCARGS2 = 49;
    private static final int MAX_PROCARGS = 512 * 1024;

    interface LibC extends Library {
        int getsockopt(int fd, int level, int name, byte[] value, IntByReference len);

        int sysctl(int[] name, int namelen, byte[] oldp, LongByReference oldlenp, Pointer newp, long newlen);
    }

    interface CF extends Library {
        Pointer CFDataCreate(Pointer alloc, byte[] bytes, long length);

        Pointer CFStringCreateWithCString(Pointer alloc, String s, int encoding);

        Pointer CFDictionaryCreate(Pointer alloc, Pointer[] keys, Pointer[] values, long count, Pointer keyCallbacks, Pointer valueCallbacks);

        Pointer CFDictionaryGetValue(Pointer dict, Pointer key);

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
            return Check.OK;
        } finally {
            release(staticCode.getValue());
            release(req);
            release(guest.getValue());
            release(attrs);
            release(data);
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
