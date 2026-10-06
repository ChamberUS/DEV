package byx.service.auth;

import java.util.List;
import java.util.Optional;

/** Estado crítico e VERSIONADO da autoridade (contas, papéis, habilitação, versão de credencial, verificadores de senha). Imutável. */
public record AuthorityState(long version, List<Account> accounts) {
    public AuthorityState {
        accounts = List.copyOf(accounts);
    }

    public Optional<Account> byId(String id) {
        return accounts.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public Optional<Account> byUsername(String normalized) {
        return accounts.stream().filter(a -> a.username().equals(normalized)).findFirst();
    }
}
