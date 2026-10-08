package byx.service.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Mudanças de autoridade feitas SÓ por código interno do serviço (provisionamento, importação da migração, mudança de senha autenticada, dispositivos
 * confiáveis). NÃO existe operação de IPC equivalente (nada de setRole/setMfa/impersonate). Toda mudança de segurança sobe a credentialVersion da conta,
 * o que invalida as sessões antigas.
 * <p>
 * JANELA DE SEGURANÇA DO CUTOVER: com {@code migrationFreeze} ligado, são recusadas (código "frozen") as mutações que complicariam o rollback: mudança de
 * senha, mudança de papel, desabilitar/habilitar, apagar e inscrever dispositivo confiável permanente. Login, logout e 2º fator seguem. A trava só é
 * ligada/desligada pelo migrador ({@link #setFreeze}), nunca por IPC.
 */
public final class AuthorityAdmin {
    private final AuthorityStore store;
    private final PasswordVerifier passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthorityAdmin(AuthorityStore store, PasswordVerifier passwords, Clock clock) {
        this.store = store;
        this.passwords = passwords;
        this.clock = clock;
    }

    public static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireNotFrozen(AuthorityState s) {
        if (s.migrationFreeze()) {
            throw new FrozenException();
        }
    }

    /** Sinaliza mudança recusada pela trava de migração (convertida em AuthorityException("frozen")). */
    static final class FrozenException extends RuntimeException {
        FrozenException() {
            super("frozen", null, false, false);
        }
    }

    private AuthorityState mutate(UnaryOperator<AuthorityState> f) throws AuthorityException {
        try {
            return store.mutateRaw(f);
        } catch (FrozenException e) {
            throw new AuthorityException("frozen");
        }
    }

    public Account createAccount(String username, char[] password, Role role) throws AuthorityException {
        String name = normalize(username);
        if (!name.matches("[a-z0-9._-]{1,64}") || password == null || password.length < AuthLimits.PASSWORD_MIN_CHARS) {
            throw new AuthorityException("invalid_account");
        }
        byte[] id = new byte[16];
        random.nextBytes(id);
        Account a = new Account(HexFormat.of().formatHex(id), name, role, true, 1, passwords.hash(password), clock.millis());
        mutate(s -> {
            requireNotFrozen(s);
            if (s.byUsername(name).isPresent()) {
                throw new IllegalStateException("duplicate");
            }
            List<Account> next = new ArrayList<>(s.accounts());
            next.add(a);
            return s.withAccounts(next);
        });
        return a;
    }

    /**
     * IMPORTAÇÃO da migração: preserva o verificador, o papel, o estado e a identidade legada; nunca promove, nunca reativa, nunca normaliza em silêncio
     * (a conta já chega normalizada; se não for, é recusada). Isenta da trava (é o próprio ato de migrar, antes do cutover).
     */
    public void importAccount(Account a) throws AuthorityException {
        try {
            store.mutateRaw(s -> {
                if (s.byId(a.id()).isPresent() || s.byUsername(a.username()).isPresent() || a.legacyUserId() != 0 && s.accounts().stream().anyMatch(x -> x.legacyUserId() == a.legacyUserId())) {
                    throw new IllegalStateException("duplicate");
                }
                List<Account> next = new ArrayList<>(s.accounts());
                next.add(a);
                return s.withAccounts(next);
            });
        } catch (AuthorityException e) {
            throw e;
        }
    }

    public void setProviders(ProviderSettings p) throws AuthorityException {
        store.mutateRaw(s -> new AuthorityState(s.version(), s.accounts(), p, s.devices(), s.migrationFreeze(),s.walletCatalog()));
    }

    /** Liga/desliga a trava da janela de segurança do cutover (só o migrador). */
    public void setFreeze(boolean on) throws AuthorityException {
        store.mutateRaw(s -> new AuthorityState(s.version(), s.accounts(), s.providers(), s.devices(), on,s.walletCatalog()));
    }

    private void update(String id, UnaryOperator<Account> f) throws AuthorityException {
        mutate(s -> {
            requireNotFrozen(s);
            return replace(s, id, f);
        });
    }

    private static AuthorityState replace(AuthorityState s, String id, UnaryOperator<Account> f) {
        List<Account> next = new ArrayList<>();
        boolean found = false;
        for (Account a : s.accounts()) {
            if (a.id().equals(id)) {
                next.add(f.apply(a));
                found = true;
            } else {
                next.add(a);
            }
        }
        if (!found) {
            throw new IllegalStateException("not_found");
        }
        return s.withAccounts(next);
    }

    /** Papel, habilitação e credencial mudam ⇒ os dispositivos confiáveis da conta são revogados (fronteira de autoridade). */
    private AuthorityState revokeDevices(AuthorityState s, String accountId) {
        long now = clock.millis();
        List<TrustedDevice> ds = new ArrayList<>();
        for (TrustedDevice d : s.devices()) {
            ds.add(d.accountId().equals(accountId) && d.revokedAtMs() == 0 ? new TrustedDevice(d.id(), d.accountId(), d.createdAtMs(), d.lastUsedAtMs(), d.expiresAtMs(), now) : d);
        }
        return new AuthorityState(s.version(), s.accounts(), s.providers(), ds, s.migrationFreeze(),s.walletCatalog());
    }

    public void setRole(String id, Role role) throws AuthorityException {
        mutate(s -> {
            requireNotFrozen(s);
            return revokeDevices(replace(s, id, a -> a.withRole(role)), id);
        });
    }

    public void setEnabled(String id, boolean enabled) throws AuthorityException {
        mutate(s -> {
            requireNotFrozen(s);
            return revokeDevices(replace(s, id, a -> a.withEnabled(enabled)), id);
        });
    }

    /** Troca o verificador de senha E sobe a versão de credencial; revoga os dispositivos confiáveis da conta. */
    public void changePassword(String id, char[] newPassword) throws AuthorityException {
        if (newPassword == null || newPassword.length < AuthLimits.PASSWORD_MIN_CHARS) {
            throw new AuthorityException("weak_password");
        }
        String hash = passwords.hash(newPassword);
        mutate(s -> {
            requireNotFrozen(s);
            return revokeDevices(replace(s, id, a -> a.withPassword(hash)), id);
        });
    }

    /** Teste/operação interna: só sobe a versão de credencial. */
    public void bumpCredentialVersion(String id) throws AuthorityException {
        update(id, Account::withCredentialBump);
    }

    /** Registra o último login bem-sucedido (não é mudança de segurança: não sobe a versão de credencial nem é bloqueada pela trava). */
    public void recordLogin(String id, long ms) throws AuthorityException {
        store.mutateRaw(s -> replace(s, id, a -> a.withLastLogin(ms)));
    }

    public void delete(String id) throws AuthorityException {
        mutate(s -> {
            requireNotFrozen(s);
            List<Account> next = new ArrayList<>(s.accounts());
            if (!next.removeIf(a -> a.id().equals(id))) {
                throw new IllegalStateException("not_found");
            }
            List<TrustedDevice> ds = new ArrayList<>(s.devices());
            ds.removeIf(d -> d.accountId().equals(id));
            return new AuthorityState(s.version(), next, s.providers(), ds, s.migrationFreeze(),s.walletCatalog());
        });
    }

    // ---- dispositivos confiáveis ------------------------------------------------------------------------------------------------------------

    /** Inscreve UM dispositivo (esta instalação) para a conta, substituindo registro anterior ativo; recusado durante a trava. */
    public TrustedDevice enrollDevice(String accountId, long validityMs) throws AuthorityException {
        byte[] id = new byte[16];
        random.nextBytes(id);
        long now = clock.millis();
        TrustedDevice d = new TrustedDevice(HexFormat.of().formatHex(id), accountId, now, now, now + validityMs, 0);
        mutate(s -> {
            requireNotFrozen(s);
            if (s.byId(accountId).isEmpty()) {
                throw new IllegalStateException("not_found");
            }
            List<TrustedDevice> ds = new ArrayList<>();
            for (TrustedDevice x : s.devices()) {
                ds.add(x.accountId().equals(accountId) && x.revokedAtMs() == 0 ? new TrustedDevice(x.id(), x.accountId(), x.createdAtMs(), x.lastUsedAtMs(), x.expiresAtMs(), now) : x);
            }
            ds.add(d);
            return new AuthorityState(s.version(), s.accounts(), s.providers(), ds, s.migrationFreeze(),s.walletCatalog());
        });
        return d;
    }

    /** Revogação NÃO é bloqueada pela trava: revogar só reduz confiança. */
    public void revokeDevice(String accountId, String deviceId) throws AuthorityException {
        long now = clock.millis();
        store.mutateRaw(s -> {
            List<TrustedDevice> ds = new ArrayList<>();
            for (TrustedDevice x : s.devices()) {
                ds.add(x.id().equals(deviceId) && x.accountId().equals(accountId) && x.revokedAtMs() == 0
                        ? new TrustedDevice(x.id(), x.accountId(), x.createdAtMs(), x.lastUsedAtMs(), x.expiresAtMs(), now) : x);
            }
            return new AuthorityState(s.version(), s.accounts(), s.providers(), ds, s.migrationFreeze(),s.walletCatalog());
        });
    }

    public void touchDevice(String deviceId) throws AuthorityException {
        long now = clock.millis();
        store.mutateRaw(s -> {
            List<TrustedDevice> ds = new ArrayList<>();
            for (TrustedDevice x : s.devices()) {
                ds.add(x.id().equals(deviceId) ? new TrustedDevice(x.id(), x.accountId(), x.createdAtMs(), now, x.expiresAtMs(), x.revokedAtMs()) : x);
            }
            return new AuthorityState(s.version(), s.accounts(), s.providers(), ds, s.migrationFreeze(),s.walletCatalog());
        });
    }
}
