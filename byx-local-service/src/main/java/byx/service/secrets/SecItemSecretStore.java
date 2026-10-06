package byx.service.secrets;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Backend do Keychain com as APIs MODERNAS ({@code SecItemAdd/CopyMatching/Update/Delete}) e SOMENTE no keychain de proteção de dados
 * ({@code kSecUseDataProtectionKeychain = true}). Não há as APIs legadas de arquivo de chaveiro (nem as de ACL por aplicativo confiável), nem
 * fallback para arquivo ou texto puro: sem o entitlement o resultado é {@code NOT_CONFIGURED}, nunca "grava em outro lugar".
 * <ul>
 *   <li>Acessibilidade: {@code kSecAttrAccessibleWhenUnlockedThisDeviceOnly} (a mais restritiva compatível: o serviço não opera antes do
 *       login nem em segundo plano privilegiado; "ThisDeviceOnly" exclui backup/migração). Nunca "Always".</li>
 *   <li>{@code kSecAttrSynchronizable = false} explícito em TODA consulta (nada de iCloud; e a consulta nunca casa item sincronizável).</li>
 *   <li>Sem {@code kSecAttrAccessGroup}: vale o primeiro grupo do entitlement do serviço. A decisão do grupo depende do ID final e do
 *       provisioning (ver docs/SECURE_SECRET_STORE.md).</li>
 * </ul>
 * Mapeamento de erros: {@link #map(int)}. Nenhuma consulta, valor ou ACL sai desta classe em erro ou log.
 */
public final class SecItemSecretStore implements SecretStore {
    static final int ERR_SUCCESS = 0;
    static final int ERR_NOT_FOUND = -25300;
    static final int ERR_DUPLICATE = -25299;
    static final int ERR_MISSING_ENTITLEMENT = -34018;
    static final int ERR_INTERACTION_NOT_ALLOWED = -25308;
    static final int ERR_INTERACTION_REQUIRED = -25315;
    static final int ERR_AUTH_FAILED = -25293;
    static final int ERR_NO_ACCESS_FOR_ITEM = -25243;
    static final int ERR_USER_CANCELED = -128;
    static final int ERR_NOT_AVAILABLE = -25291;

    interface CF extends Library {
        Pointer CFDataCreate(Pointer alloc, byte[] bytes, long length);

        Pointer CFStringCreateWithCString(Pointer alloc, String s, int encoding);

        Pointer CFDictionaryCreate(Pointer alloc, Pointer[] keys, Pointer[] values, long count, Pointer keyCallbacks, Pointer valueCallbacks);

        long CFDataGetLength(Pointer data);

        Pointer CFDataGetBytePtr(Pointer data);

        void CFRelease(Pointer p);

        Pointer CFDictionaryGetValue(Pointer dict, Pointer key);

        byte CFStringGetCString(Pointer s, byte[] buffer, long size, int encoding);

        byte CFBooleanGetValue(Pointer b);

        byte CFEqual(Pointer a, Pointer b);
    }

    interface Sec extends Library {
        int SecItemAdd(Pointer attributes, PointerByReference result);

        int SecItemCopyMatching(Pointer query, PointerByReference result);

        int SecItemUpdate(Pointer query, Pointer attributesToUpdate);

        int SecItemDelete(Pointer query);

        Pointer SecTaskCreateFromSelf(Pointer allocator);

        Pointer SecTaskCopyValueForEntitlement(Pointer task, Pointer entitlement, PointerByReference error);
    }

    private static final String SEC_PATH = "/System/Library/Frameworks/Security.framework/Security";
    private static final String CF_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private static final int CF_UTF8 = 0x08000100;

    private final CF cf;
    private final Sec sec;
    private final Pointer kClass;
    private final Pointer kClassGeneric;
    private final Pointer kService;
    private final Pointer kAccount;
    private final Pointer kValueData;
    private final Pointer kAccessible;
    private final Pointer kAccessibleWhenUnlockedThisDeviceOnly;
    private final Pointer kDataProtection;
    private final Pointer kSynchronizable;
    private final Pointer kReturnData;
    private final Pointer kReturnAttributes;
    private final Pointer kAccessGroup;
    private final Pointer kSynchronizableAny;
    private final Pointer kMatchLimit;
    private final Pointer kMatchLimitOne;
    private final Pointer kTrue;
    private final Pointer kFalse;
    private final Pointer keyCallbacks;
    private final Pointer valueCallbacks;

    public SecItemSecretStore() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            throw new IllegalStateException("not macOS");
        }
        cf = Native.load(CF_PATH, CF.class);
        sec = Native.load(SEC_PATH, Sec.class);
        NativeLibrary s = NativeLibrary.getInstance(SEC_PATH);
        NativeLibrary c = NativeLibrary.getInstance(CF_PATH);
        kClass = global(s, "kSecClass");
        kClassGeneric = global(s, "kSecClassGenericPassword");
        kService = global(s, "kSecAttrService");
        kAccount = global(s, "kSecAttrAccount");
        kValueData = global(s, "kSecValueData");
        kAccessible = global(s, "kSecAttrAccessible");
        kAccessibleWhenUnlockedThisDeviceOnly = global(s, "kSecAttrAccessibleWhenUnlockedThisDeviceOnly");
        kDataProtection = global(s, "kSecUseDataProtectionKeychain");
        kSynchronizable = global(s, "kSecAttrSynchronizable");
        kReturnData = global(s, "kSecReturnData");
        kReturnAttributes = global(s, "kSecReturnAttributes");
        kAccessGroup = global(s, "kSecAttrAccessGroup");
        kSynchronizableAny = global(s, "kSecAttrSynchronizableAny");
        kMatchLimit = global(s, "kSecMatchLimit");
        kMatchLimitOne = global(s, "kSecMatchLimitOne");
        kTrue = global(c, "kCFBooleanTrue");
        kFalse = global(c, "kCFBooleanFalse");
        keyCallbacks = c.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks");
        valueCallbacks = c.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks");
    }

    private static Pointer global(NativeLibrary lib, String name) {
        return lib.getGlobalVariableAddress(name).getPointer(0);
    }

    /** Códigos do Keychain → estados que a UI pode conhecer. */
    static SecretStatus map(int os) {
        return switch (os) {
            case ERR_SUCCESS -> SecretStatus.SECURE_STORAGE_AVAILABLE;
            case ERR_MISSING_ENTITLEMENT, ERR_NOT_AVAILABLE -> SecretStatus.NOT_CONFIGURED;
            case ERR_INTERACTION_NOT_ALLOWED, ERR_INTERACTION_REQUIRED -> SecretStatus.LOCKED;
            case ERR_AUTH_FAILED, ERR_NO_ACCESS_FOR_ITEM, ERR_USER_CANCELED -> SecretStatus.DENIED;
            default -> SecretStatus.ERROR;
        };
    }

    /** Atributos (sem valor secreto) de uma consulta, para inspeção em teste: prova proteção de dados, sem iCloud e acessibilidade. */
    static Map<String, String> describe(SecretId id) throws SecretStoreException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("class", "genericPassword");
        m.put("service", SecretNamespace.service(id));
        m.put("account", SecretNamespace.ACCOUNT);
        m.put("dataProtectionKeychain", "true");
        m.put("synchronizable", "false");
        m.put("accessible", "WhenUnlockedThisDeviceOnly");
        return m;
    }

    private Pointer cfString(String s) {
        return cf.CFStringCreateWithCString(null, s, CF_UTF8);
    }

    private Pointer dict(Pointer[] keys, Pointer[] values) {
        return cf.CFDictionaryCreate(null, keys, values, keys.length, keyCallbacks, valueCallbacks);
    }

    /** Consulta base (SEM valor): classe, serviço, conta, proteção de dados e sincronizável=false. */
    private Pointer[][] base(SecretId id, Pointer service, Pointer account) {
        return new Pointer[][] {{kClass, kService, kAccount, kDataProtection, kSynchronizable}, {kClassGeneric, service, account, kTrue, kFalse}};
    }

    private static Pointer[] concat(Pointer[] a, Pointer... b) {
        Pointer[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private void release(Pointer p) {
        if (p != null) {
            cf.CFRelease(p);
        }
    }

    private int run(SecretId id, Op op) throws SecretStoreException {
        Pointer service = cfString(SecretNamespace.service(id));
        Pointer account = cfString(SecretNamespace.ACCOUNT);
        try {
            return op.apply(base(id, service, account));
        } finally {
            release(service);
            release(account);
        }
    }

    private interface Op {
        int apply(Pointer[][] base);
    }

    /**
     * Sonda de disponibilidade. ATENÇÃO (medido): sem o entitlement, {@code SecItemCopyMatching} devolve "não encontrado" (-25300) como se
     * o item simplesmente não existisse; só {@code SecItemAdd}/{@code SecItemDelete} devolvem -34018. Por isso a sonda é um DELETE de um item
     * de SONDA dedicado e inexistente (nunca o canário, nunca segredo): 0 ou "não encontrado" = o keychain de proteção de dados está acessível.
     */
    private int probe() {
        Pointer service = cfString(SecretNamespace.PROBE_SERVICE);
        Pointer account = cfString(SecretNamespace.ACCOUNT);
        try {
            Pointer[][] b = base(null, service, account);
            Pointer q = dict(b[0], b[1]);
            try {
                return sec.SecItemDelete(q); // item de sonda inexistente: nunca toca o canário nem segredo algum
            } finally {
                release(q);
            }
        } catch (RuntimeException | LinkageError e) {
            return Integer.MIN_VALUE;
        } finally {
            release(service);
            release(account);
        }
    }

    private volatile boolean confirmedAvailable;

    /** Leitura/atualização só depois de provar o entitlement: um "não encontrado" sem entitlement não pode virar "sem segredo". */
    private void ensureAvailable() throws SecretStoreException {
        if (confirmedAvailable) {
            return;
        }
        int os = probe();
        if (os == ERR_SUCCESS || os == ERR_NOT_FOUND) {
            confirmedAvailable = true;
            return;
        }
        SecretStatus s = os == Integer.MIN_VALUE ? SecretStatus.ERROR : map(os);
        throw new SecretStoreException(s == SecretStatus.SECURE_STORAGE_AVAILABLE ? SecretStatus.ERROR : s, os == Integer.MIN_VALUE ? 0 : os);
    }


    /** Atributos NÃO secretos de um item (nunca o valor): grupo de acesso efetivo, sincronizável e se a acessibilidade é a esperada. */
    public record Inspection(String accessGroup, Boolean synchronizable, boolean accessibleWhenUnlockedThisDeviceOnly) {
    }

    /** Valor do entitlement com.apple.application-identifier do PRÓPRIO processo (lido da assinatura), ou null. */
    public String selfApplicationIdentifier() {
        Pointer task = sec.SecTaskCreateFromSelf(null);
        if (task == null) {
            return null;
        }
        Pointer key = cfString("com.apple.application-identifier");
        try {
            Pointer v = sec.SecTaskCopyValueForEntitlement(task, key, null);
            try {
                return v == null ? null : javaString(v);
            } finally {
                release(v);
            }
        } finally {
            release(key);
            release(task);
        }
    }

    private String javaString(Pointer cfString) {
        byte[] buf = new byte[512];
        if (cf.CFStringGetCString(cfString, buf, buf.length, CF_UTF8) == 0) {
            return null;
        }
        int n = 0;
        while (n < buf.length && buf[n] != 0) {
            n++;
        }
        return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Consulta SÓ de atributos (sem kSecReturnData) com sincronizável "qualquer" para enxergar o valor real do atributo. Vazio se o item não existe. */
    public Optional<Inspection> inspect(SecretId id) throws SecretStoreException {
        ensureAvailable();
        PointerByReference out = new PointerByReference();
        int os = run(id, b -> {
            Pointer[] keys = new Pointer[] {kClass, kService, kAccount, kDataProtection, kSynchronizable, kReturnAttributes, kMatchLimit};
            Pointer[] vals = new Pointer[] {kClassGeneric, null, null, kTrue, kSynchronizableAny, kTrue, kMatchLimitOne};
            vals[1] = b[1][1];
            vals[2] = b[1][2];
            Pointer q = dict(keys, vals);
            try {
                return sec.SecItemCopyMatching(q, out);
            } finally {
                release(q);
            }
        });
        if (os == ERR_NOT_FOUND) {
            return Optional.empty();
        }
        check(os);
        Pointer attrs = out.getValue();
        try {
            Pointer group = cf.CFDictionaryGetValue(attrs, kAccessGroup);
            Pointer sync = cf.CFDictionaryGetValue(attrs, kSynchronizable);
            Pointer acc = cf.CFDictionaryGetValue(attrs, kAccessible);
            return Optional.of(new Inspection(group == null ? null : javaString(group), sync == null ? null : cf.CFBooleanGetValue(sync) != 0,
                    acc != null && cf.CFEqual(acc, kAccessibleWhenUnlockedThisDeviceOnly) != 0));
        } finally {
            release(attrs);
        }
    }

    @Override
    public SecretStatus status() {
        int os = probe();
        if (os == ERR_SUCCESS || os == ERR_NOT_FOUND) {
            return SecretStatus.SECURE_STORAGE_AVAILABLE;
        }
        return os == Integer.MIN_VALUE ? SecretStatus.ERROR : map(os);
    }

    @Override
    public void write(SecretId id, SecretBytes value) throws SecretStoreException {
        byte[] bytes = value.bytes();
        Pointer data = cf.CFDataCreate(null, bytes, bytes.length);
        int os = run(id, b -> {
            Pointer attrs = dict(concat(b[0], kValueData, kAccessible), concat(b[1], data, kAccessibleWhenUnlockedThisDeviceOnly));
            try {
                return sec.SecItemAdd(attrs, null);
            } finally {
                release(attrs);
            }
        });
        try {
            if (os == ERR_DUPLICATE) {
                if (!update(id, value)) {
                    throw new SecretStoreException(SecretStatus.ERROR, os);
                }
                return;
            }
            check(os);
        } finally {
            release(data);
        }
    }

    @Override
    public boolean update(SecretId id, SecretBytes value) throws SecretStoreException {
        ensureAvailable();
        byte[] bytes = value.bytes();
        Pointer data = cf.CFDataCreate(null, bytes, bytes.length);
        try {
            int os = run(id, b -> {
                Pointer q = dict(b[0], b[1]);
                Pointer change = dict(new Pointer[] {kValueData}, new Pointer[] {data});
                try {
                    return sec.SecItemUpdate(q, change);
                } finally {
                    release(change);
                    release(q);
                }
            });
            if (os == ERR_NOT_FOUND) {
                return false;
            }
            check(os);
            return true;
        } finally {
            release(data);
        }
    }

    @Override
    public Optional<SecretBytes> read(SecretId id) throws SecretStoreException {
        ensureAvailable();
        PointerByReference out = new PointerByReference();
        int os = run(id, b -> {
            Pointer q = dict(concat(b[0], kReturnData, kMatchLimit), concat(b[1], kTrue, kMatchLimitOne));
            try {
                return sec.SecItemCopyMatching(q, out);
            } finally {
                release(q);
            }
        });
        if (os == ERR_NOT_FOUND) {
            return Optional.empty();
        }
        check(os);
        Pointer data = out.getValue();
        try {
            long n = cf.CFDataGetLength(data);
            if (n < 1 || n > SecretBytes.MAX_BYTES) {
                throw new SecretStoreException(SecretStatus.ERROR);
            }
            byte[] copy = cf.CFDataGetBytePtr(data).getByteArray(0, (int) n);
            try {
                return Optional.of(SecretBytes.copyOf(copy));
            } finally {
                java.util.Arrays.fill(copy, (byte) 0);
            }
        } finally {
            release(data);
        }
    }

    @Override
    public boolean delete(SecretId id) throws SecretStoreException {
        int os = run(id, b -> {
            Pointer q = dict(b[0], b[1]);
            try {
                return sec.SecItemDelete(q);
            } finally {
                release(q);
            }
        });
        if (os == ERR_NOT_FOUND) {
            return false;
        }
        check(os);
        return true;
    }

    private static void check(int os) throws SecretStoreException {
        if (os != ERR_SUCCESS) {
            SecretStatus s = map(os);
            throw new SecretStoreException(s == SecretStatus.SECURE_STORAGE_AVAILABLE ? SecretStatus.ERROR : s, os);
        }
    }
}
