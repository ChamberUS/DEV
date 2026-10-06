package byx.service.auth;

import byx.service.secrets.SecretStore;
import java.time.Clock;
import java.util.function.Function;

/** Monta a autoridade de um perfil (store cifrado + âncora + limitador persistente + auditoria encadeada + segundo fator). Sem inicialização implícita. */
public final class AuthComposition {
    /** Resultado: serviço de autenticação e o store (para o migrador/diagnóstico). */
    public record Composed(AuthService auth, AuthorityStore store, AuthAudit audit) {
    }

    private AuthComposition() {
    }

    /**
     * secondFactor: recebe o store (para ler a configuração dos provedores do snapshot cifrado) e devolve o provedor. Produção: {@link RealSecondFactor};
     * QA: provedor de teste. Estado não confiável sobe mesmo assim, fechado (toda autenticação responde AUTHORITY_UNAVAILABLE).
     */
    public static Composed compose(AuthProfile profile, SecretStore secrets, Clock clock, Function<AuthorityStore, SecondFactorProvider> secondFactor) throws AuthorityException {
        AuthorityStore store = AuthorityStore.open(profile.snapshot(), new SecretStoreAnchor(secrets, profile.anchorId()), new SecretStoreKeyVault(secrets, profile.encryptionKeyId()));
        PasswordVerifier pw = new PasswordVerifier();
        boolean trusted = store.status() == AuthorityStore.Status.TRUSTED;
        AuthRateLimiter limiter = trusted ? new AuthRateLimiter(profile.rateLimitFile(), store.derivedKey("ratelimit"), store.derivedKey("ratelimit-subject"), clock)
                : new AuthRateLimiter(null, new byte[32], new byte[32], clock);
        AuthAudit audit = trusted ? new AuthAudit(clock, profile.auditFile(), store.derivedKey("audit")) : new AuthAudit(clock);
        SecondFactorProvider sf = secondFactor.apply(store);
        AuthService auth = new AuthService(store, new AuthorityAdmin(store, pw, clock), pw, limiter, sf, AuthPolicy.standard(), audit, clock);
        return new Composed(auth, store, audit);
    }

    /** Provedor de produção: adaptadores reais lendo a configuração do snapshot e os segredos do cofre de PRODUÇÃO. */
    public static Function<AuthorityStore, SecondFactorProvider> realProviders(AuthProfile profile, SecretStore secrets, HttpTransport http) {
        return store -> new RealSecondFactor(() -> {
            try {
                return store.current().providers();
            } catch (AuthorityException e) {
                return ProviderSettings.NONE;
            }
        }, secrets, http, profile.resendId(), profile.twilioId());
    }
}
