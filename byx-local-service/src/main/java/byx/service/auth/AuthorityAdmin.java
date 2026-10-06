package byx.service.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Mudanças de autoridade feitas SÓ por código interno do serviço (provisionamento de teste, mudança de senha autenticada). NÃO existe operação
 * de IPC equivalente (nada de setRole/setMfa/impersonate). Toda mudança de segurança sobe a credentialVersion da conta, o que invalida as
 * sessões antigas (nenhuma sessão "ressuscita" privilégio depois de rebaixar, desabilitar ou trocar credencial).
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

    public Account createAccount(String username, char[] password, Role role) throws AuthorityException {
        String name = normalize(username);
        if (!name.matches("[a-z0-9._-]{1,64}") || password == null || password.length < AuthLimits.PASSWORD_MIN_CHARS) {
            throw new AuthorityException("invalid_account");
        }
        byte[] id = new byte[16];
        random.nextBytes(id);
        Account a = new Account(HexFormat.of().formatHex(id), name, role, true, 1, passwords.hash(password), clock.millis());
        store.mutate(s -> {
            if (s.byUsername(name).isPresent()) {
                throw new IllegalStateException("duplicate");
            }
            List<Account> next = new ArrayList<>(s.accounts());
            next.add(a);
            return new AuthorityState(s.version(), next);
        });
        return a;
    }

    private void update(String id, java.util.function.UnaryOperator<Account> f) throws AuthorityException {
        store.mutate(s -> {
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
            return new AuthorityState(s.version(), next);
        });
    }

    public void setRole(String id, Role role) throws AuthorityException {
        update(id, a -> new Account(a.id(), a.username(), role, a.enabled(), a.credentialVersion() + 1, a.passwordHash(), a.createdAtMs()));
    }

    public void setEnabled(String id, boolean enabled) throws AuthorityException {
        update(id, a -> new Account(a.id(), a.username(), a.role(), enabled, a.credentialVersion() + 1, a.passwordHash(), a.createdAtMs()));
    }

    /** Troca o verificador de senha E sobe a versão de credencial. */
    public void changePassword(String id, char[] newPassword) throws AuthorityException {
        if (newPassword == null || newPassword.length < AuthLimits.PASSWORD_MIN_CHARS) {
            throw new AuthorityException("weak_password");
        }
        String hash = passwords.hash(newPassword);
        update(id, a -> new Account(a.id(), a.username(), a.role(), a.enabled(), a.credentialVersion() + 1, hash, a.createdAtMs()));
    }

    /** Teste/operação interna: só sobe a versão de credencial. */
    public void bumpCredentialVersion(String id) throws AuthorityException {
        update(id, a -> new Account(a.id(), a.username(), a.role(), a.enabled(), a.credentialVersion() + 1, a.passwordHash(), a.createdAtMs()));
    }

    public void delete(String id) throws AuthorityException {
        store.mutate(s -> {
            List<Account> next = new ArrayList<>(s.accounts());
            if (!next.removeIf(a -> a.id().equals(id))) {
                throw new IllegalStateException("not_found");
            }
            return new AuthorityState(s.version(), next);
        });
    }
}
