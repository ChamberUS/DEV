package panel.security;

import com.sun.jna.*;
import com.sun.jna.ptr.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native Keychain calls: secrets never travel through argv, shell or temporary files. */
public final class MacOsKeychainSecretStore implements SecretStore {
    private static final byte[] ACCOUNT = "mvp-binance-panel".getBytes(StandardCharsets.UTF_8);
    private final boolean interactive;
    public MacOsKeychainSecretStore() { this(false); }
    public MacOsKeychainSecretStore(boolean interactive) { this.interactive = interactive; }
    private NativeLibrary library() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) throw new Unavailable();
        try { return NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security"); }
        catch (LinkageError | RuntimeException e) { throw new Unavailable(); }
    }
    private static void check(int code) { if (code != 0) throw new Unavailable(); }
    private static byte[] service(String name) {
        if (!Set.of(RESEND, TWILIO, DEVICE).contains(name)) throw new IllegalArgumentException("Unknown secret name");
        return name.getBytes(StandardCharsets.UTF_8);
    }
    private int find(NativeLibrary lib, byte[] service, IntByReference size, PointerByReference data, PointerByReference item) {
        return lib.getFunction("SecKeychainFindGenericPassword").invokeInt(new Object[]{null, service.length, service,
                ACCOUNT.length, ACCOUNT, size, data, item});
    }
    private static void release(Pointer item) {
        if (item != null) NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
                .getFunction("CFRelease").invokeVoid(new Object[]{item});
    }
    @Override public Optional<char[]> read(String name) {
        synchronized (MacOsKeychainSecretStore.class) {
            NativeLibrary lib = library();
            check(lib.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[]{(byte)(interactive ? 1 : 0)}));
            var size = new IntByReference(); var data = new PointerByReference();
            try {
                int code = find(lib, service(name), size, data, null);
                if (code == -25300) return Optional.empty();
                check(code);
                byte[] bytes = data.getValue().getByteArray(0, size.getValue());
                try { var decoded = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes));
                    char[] out = new char[decoded.remaining()]; decoded.get(out); if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0'); return Optional.of(out);
                } finally { Arrays.fill(bytes, (byte)0); }
            } finally {
                if (data.getValue() != null) {
                    data.getValue().clear(size.getValue());
                    lib.getFunction("SecKeychainItemFreeContent").invokeInt(new Object[]{null, data.getValue()});
                }
                lib.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[]{(byte)1});
            }
        }
    }
    @Override public void write(String name, char[] secret) {
        var encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(secret));
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        if (encoded.hasArray()) Arrays.fill(encoded.array(), (byte)0);
        synchronized (MacOsKeychainSecretStore.class) {
            NativeLibrary lib = library(); var item = new PointerByReference(); byte[] service = service(name);
            check(lib.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[]{(byte)(interactive ? 1 : 0)}));
            try {
                int result = find(lib, service, null, null, item);
                if (result == -25300) check(lib.getFunction("SecKeychainAddGenericPassword").invokeInt(new Object[]{null,
                        service.length, service, ACCOUNT.length, ACCOUNT, bytes.length, bytes, null}));
                else { check(result); check(lib.getFunction("SecKeychainItemModifyAttributesAndData").invokeInt(new Object[]{item.getValue(), null, bytes.length, bytes})); }
            } finally { Arrays.fill(bytes, (byte)0); release(item.getValue());
                lib.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[]{(byte)1}); }
        }
    }
    @Override public void delete(String name) {
        synchronized (MacOsKeychainSecretStore.class) {
            NativeLibrary lib = library(); var item = new PointerByReference();
            check(lib.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[]{(byte)(interactive ? 1 : 0)}));
            try { int code = find(lib, service(name), null, null, item);
                if (code == -25300) return; check(code);
                check(lib.getFunction("SecKeychainItemDelete").invokeInt(new Object[]{item.getValue()}));
            } finally { release(item.getValue()); lib.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[]{(byte)1}); }
        }
    }
    @Override public String toString() { return "MacOsKeychainSecretStore"; }
}
