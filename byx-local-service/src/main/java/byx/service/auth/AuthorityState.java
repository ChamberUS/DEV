package byx.service.auth;

import java.util.List;
import java.util.Optional;

/**
 * Estado crítico e VERSIONADO da autoridade: contas, papéis, habilitação, versão de credencial, verificadores de senha, configuração não secreta dos
 * provedores, dispositivos confiáveis e a trava de migração (migrationFreeze). Imutável.
 */
public record AuthorityState(long version, List<Account> accounts, ProviderSettings providers, List<TrustedDevice> devices, boolean migrationFreeze,
        byx.service.signer.WalletCatalog walletCatalog) {
    public AuthorityState {
        accounts = List.copyOf(accounts);
        devices = List.copyOf(devices);
        providers = providers == null ? ProviderSettings.NONE : providers;
    }

    public AuthorityState(long version, List<Account> accounts) {
        this(version, accounts, ProviderSettings.NONE, List.of(), false);
    }

    public AuthorityState(long version, List<Account> accounts, ProviderSettings providers, List<TrustedDevice> devices, boolean migrationFreeze) {
        this(version,accounts,providers,devices,migrationFreeze,null);
    }

    public AuthorityState withAccounts(List<Account> a) {
        return new AuthorityState(version, a, providers, devices, migrationFreeze,walletCatalog);
    }

    public AuthorityState withWalletCatalog(byx.service.signer.WalletCatalog catalog) {
        return new AuthorityState(version,accounts,providers,devices,migrationFreeze,catalog);
    }

    public Optional<Account> byId(String id) {
        return accounts.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public Optional<Account> byUsername(String normalized) {
        return accounts.stream().filter(a -> a.username().equals(normalized)).findFirst();
    }

    /** Identificador digitado no login: usuário OU e-mail (comparação sem diferença de caixa), como no fluxo legado. */
    public Optional<Account> byIdentifier(String typed) {
        String n = AuthorityAdmin.normalize(typed);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        return accounts.stream().filter(a -> a.username().equals(n) || a.email() != null && a.email().equalsIgnoreCase(n)).findFirst();
    }
}
