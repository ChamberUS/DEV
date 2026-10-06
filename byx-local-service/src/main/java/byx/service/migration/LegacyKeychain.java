package byx.service.migration;

import com.sun.jna.NativeLibrary;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Caminho de migração para os itens LEGADOS do painel no keychain de arquivo (APIs {@code SecKeychain*}, depreciadas: usadas SÓ aqui, só para LER os
 * itens fixos e nunca para criar/alterar/apagar itens reais). Nomes FIXOS por conjunto ({@link Names}); sem nome livre. A leitura pode exigir a autorização
 * do usuário (o item legado confia no {@code java} do painel antigo, não no helper assinado): isso é esperado e manual; negado ⇒ o item fica bloqueado
 * (sem contornar copiando para arquivo, env, área de transferência, argv ou log). {@link #describe} só consulta atributos (não lê o segredo).
 */
final class LegacyKeychain implements LegacySecretSource {
    /** Conjunto de nomes: o REAL do painel legado ou o de TESTE (itens sintéticos do QA, em namespace reservado). */
    record Names(String resend, String twilio, String device, String account, boolean test) {
        static final Names REAL = new Names("mvp-binance-panel/resend-api-key", "mvp-binance-panel/twilio-api-secret", "mvp-binance-panel/trusted-device-token", "mvp-binance-panel", false);
        static final Names TEST = new Names("invalid.byx-legacy-test/resend-api-key", "invalid.byx-legacy-test/twilio-api-secret", "invalid.byx-legacy-test/trusted-device-token", "byx-legacy-test", true);
    }

    private static final String SEC = "/System/Library/Frameworks/Security.framework/Security";
    private static final String CF = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";
    private final Names names;
    private final boolean interactive;

    LegacyKeychain(Names names, boolean interactive) {
        this.names = names;
        this.interactive = interactive;
    }

    @Override
    public String serviceOf(Item item) {
        return switch (item) {
            case RESEND -> names.resend();
            case TWILIO -> names.twilio();
            case TRUSTED_DEVICE -> names.device();
        };
    }

    @Override
    public String account() {
        return names.account();
    }

    private static Access map(int code) {
        return switch (code) {
            case 0 -> Access.PRESENT;
            case -25300 -> Access.ABSENT;
            case -25293, -128, -25308, -25315, -25243 -> Access.DENIED; // auth failed, user canceled, interaction not allowed/required, no access
            default -> Access.ERROR;
        };
    }

    private static NativeLibrary lib(String path) {
        return NativeLibrary.getInstance(path);
    }

    @Override
    public synchronized Access describe(Item item) {
        try {
            NativeLibrary sec = lib(SEC);
            byte[] service = serviceOf(item).getBytes(StandardCharsets.UTF_8);
            byte[] account = names.account().getBytes(StandardCharsets.UTF_8);
            PointerByReference ref = new PointerByReference();
            sec.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[] {(byte) 0}); // consulta de atributos nunca abre diálogo
            int rc = sec.getFunction("SecKeychainFindGenericPassword").invokeInt(new Object[] {null, service.length, service, account.length, account, null, null, ref});
            if (ref.getValue() != null) {
                lib(CF).getFunction("CFRelease").invokeVoid(new Object[] {ref.getValue()});
            }
            return map(rc);
        } catch (RuntimeException | LinkageError e) {
            return Access.ERROR;
        }
    }

    @Override
    public synchronized Result readOnce(Item item) {
        PointerByReference data = new PointerByReference();
        IntByReference size = new IntByReference();
        NativeLibrary sec = null;
        try {
            sec = lib(SEC);
            byte[] service = serviceOf(item).getBytes(StandardCharsets.UTF_8);
            byte[] account = names.account().getBytes(StandardCharsets.UTF_8);
            sec.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[] {(byte) (interactive ? 1 : 0)});
            int rc = sec.getFunction("SecKeychainFindGenericPassword").invokeInt(new Object[] {null, service.length, service, account.length, account, size, data, null});
            Access a = map(rc);
            if (a != Access.PRESENT) {
                return Result.none(a);
            }
            byte[] bytes = data.getValue().getByteArray(0, size.getValue());
            return new Result(Access.PRESENT, bytes);
        } catch (RuntimeException | LinkageError e) {
            return Result.none(Access.ERROR);
        } finally {
            if (sec != null) {
                if (data.getValue() != null) {
                    data.getValue().clear(size.getValue());
                    sec.getFunction("SecKeychainItemFreeContent").invokeInt(new Object[] {null, data.getValue()});
                }
                sec.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[] {(byte) 1});
            }
        }
    }

    // ---- SÓ para o QA (itens sintéticos no namespace de TESTE): recusados no conjunto real --------------------------------------------------------------

    synchronized void addForTest(Item item, byte[] value) {
        requireTest();
        NativeLibrary sec = lib(SEC);
        byte[] service = serviceOf(item).getBytes(StandardCharsets.UTF_8);
        byte[] account = names.account().getBytes(StandardCharsets.UTF_8);
        sec.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[] {(byte) 0});
        int rc = sec.getFunction("SecKeychainAddGenericPassword").invokeInt(new Object[] {null, service.length, service, account.length, account, value.length, value, null});
        if (rc != 0 && rc != -25299) {
            throw new IllegalStateException("legacy_test_add");
        }
    }

    synchronized void deleteForTest(Item item) {
        requireTest();
        NativeLibrary sec = lib(SEC);
        byte[] service = serviceOf(item).getBytes(StandardCharsets.UTF_8);
        byte[] account = names.account().getBytes(StandardCharsets.UTF_8);
        PointerByReference ref = new PointerByReference();
        sec.getFunction("SecKeychainSetUserInteractionAllowed").invokeInt(new Object[] {(byte) 0});
        int rc = sec.getFunction("SecKeychainFindGenericPassword").invokeInt(new Object[] {null, service.length, service, account.length, account, null, null, ref});
        if (rc == 0 && ref.getValue() != null) {
            sec.getFunction("SecKeychainItemDelete").invokeInt(new Object[] {ref.getValue()});
            lib(CF).getFunction("CFRelease").invokeVoid(new Object[] {ref.getValue()});
        }
    }

    private void requireTest() {
        if (!names.test()) {
            throw new IllegalStateException("never_on_real_items");
        }
    }

    static void zero(byte[] b) {
        if (b != null) {
            Arrays.fill(b, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "LegacyKeychain[" + (names.test() ? "test" : "legacy") + "]";
    }

}
