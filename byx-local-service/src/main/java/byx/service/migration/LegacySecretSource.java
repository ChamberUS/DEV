package byx.service.migration;

import java.util.Optional;

/**
 * Origem dos segredos LEGADOS (o keychain de arquivo do painel). Só nomes fixos; nunca valor em toString/log. {@link #describe} NÃO lê o segredo (sem
 * aviso de acesso); {@link #readOnce} lê uma vez, e quem chama zera a cópia.
 */
interface LegacySecretSource {
    enum Item { RESEND, TWILIO, TRUSTED_DEVICE }

    enum Access { PRESENT, ABSENT, DENIED, ERROR }

    /** Presença e atributos (service/account) sem ler o segredo. */
    Access describe(Item item);

    /** Lê o segredo legado UMA vez. DENIED/ERROR = o item fica bloqueado (sem contornos: nada de arquivo, env, área de transferência, argv ou log). */
    Result readOnce(Item item);

    record Result(Access access, byte[] value) {
        @Override
        public String toString() {
            return "Result[" + access + "]";
        }

        static Result none(Access a) {
            return new Result(a, null);
        }
    }

    /** Nomes exibíveis (não secretos) do item: service/account do keychain legado. */
    String serviceOf(Item item);

    String account();

    static Optional<Item> parse(String s) {
        for (Item i : Item.values()) {
            if (i.name().equalsIgnoreCase(s)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }
}
