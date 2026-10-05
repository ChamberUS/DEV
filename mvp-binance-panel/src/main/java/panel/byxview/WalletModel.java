package panel.byxview;

import panel.design.ByxBadge;

/** Estado da carteira: só o que o repositório e a cadeia confirmam. Nada de carteira, endereço ou saldo presumidos. */
public final class WalletModel {
    private WalletModel() {
    }

    public enum State {
        NOT_LINKED("NOT LINKED", ByxBadge.Tone.NEUTRAL), CONNECTING("CONNECTING", ByxBadge.Tone.INFO),
        LINKED("LINKED", ByxBadge.Tone.POSITIVE), LOADING("LOADING", ByxBadge.Tone.INFO),
        ERROR("ERROR", ByxBadge.Tone.NEGATIVE), UNAVAILABLE("UNAVAILABLE", ByxBadge.Tone.WARNING);

        public final String text;
        public final ByxBadge.Tone tone;

        State(String text, ByxBadge.Tone tone) {
            this.text = text;
            this.tone = tone;
        }
    }

    /**
     * @param readable   a lista de carteiras pôde ser lida (LOCALNET configurada e sessão ativa)
     * @param linked     há carteira verificada válida
     * @param inFlight   leitura de saldo em andamento
     * @param everLoaded já houve ao menos uma resposta
     * @param failed     a última atualização falhou
     * @param nodeAnswering a rede já respondeu (conexão conhecida)
     */
    public static State resolve(boolean readable, boolean linked, boolean inFlight, boolean everLoaded, boolean failed, boolean nodeAnswering) {
        if (!readable) {
            return State.UNAVAILABLE;
        }
        if (failed) {
            return State.ERROR;
        }
        if (inFlight && !everLoaded) {
            return State.LOADING;
        }
        if (!nodeAnswering && !linked) {
            return State.CONNECTING;
        }
        return linked ? State.LINKED : State.NOT_LINKED;
    }
}
