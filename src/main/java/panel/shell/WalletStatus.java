package panel.shell;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Estado da lista de carteiras LEGADA para o dock e o diagnóstico. O adaptador de carteira é DENY_ALL desde o cutover: a negação (ou qualquer falha) vira "indisponível", NUNCA uma exceção no timer
 * de 1 s da thread FX. Regressão do V2.1N-1A: com a chain pública LIVE a identidade passa a VERIFIED e o dock passou a ler essa lista, lançando AccessDeniedException a cada segundo logo após o login.
 */
public final class WalletStatus {
    private WalletStatus() { }

    /** vazio = indisponível; true = nenhuma carteira vinculada; false = há carteira(s). */
    public static Optional<Boolean> empty(Supplier<List<?>> wallets) {
        try {
            return Optional.of(wallets.get().isEmpty());
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    /** Texto do dock: só lê a lista quando a identidade da rede é VERIFIED; qualquer outra coisa é "Wallet unavailable". */
    public static String dock(String identity, Supplier<List<?>> wallets) {
        if (!"VERIFIED".equals(identity)) {
            return "Wallet unavailable";
        }
        return empty(wallets).map(e -> e ? "Wallet not linked" : "Wallet linked").orElse("Wallet unavailable");
    }

    /** Texto do diagnóstico (mesma regra). */
    public static String diagnostics(String identity, Supplier<List<?>> wallets) {
        if (!"VERIFIED".equals(identity)) {
            return "Unavailable";
        }
        return empty(wallets).map(e -> e ? "Not linked" : "Linked").orElse("Unavailable");
    }
}
