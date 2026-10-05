package panel.accountview;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import panel.auth.TrustedDeviceService;
import panel.security.SecurityAuditService;
import panel.user.User;

/**
 * Fonte das telas Account V2. Só expõe o que o app realmente tem (conta local, senha, contatos, dispositivos confiáveis,
 * auditoria local, preferências de UX). Nada de sessões remotas, notificações ou histórico fictício.
 */
public interface AccountData {
    record Prefs(String motion, String density, boolean animatedIcons) {
    }

    record ProviderLine(String name, String state, String detail) {
    }

    Optional<User> user();

    /** Início da sessão local atual; null sem sessão. */
    Instant signedInAt();

    boolean adminSession();

    /** Lança IllegalArgumentException com a mensagem da política quando recusa. */
    void changePassword(char[] current, char[] next);

    /** Contatos da própria conta; exige a senha atual e revoga a confiança de dispositivos. */
    void changeContact(char[] password, String email, String phone);

    /** Dispositivos confiáveis (só admin); lança RuntimeException quando indisponível. */
    List<TrustedDeviceService.Device> trustedDevices();

    void revokeDevice(String id);

    /** Estado real dos provedores de verificação (bloqueante: fora da thread FX). */
    List<ProviderLine> providers();

    List<SecurityAuditService.Entry> activity(int limit);

    Prefs prefs();

    void savePrefs(Prefs prefs);
}
