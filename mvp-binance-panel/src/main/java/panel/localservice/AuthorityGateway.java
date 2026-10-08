package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fronteira do painel com a AUTORIDADE de autenticação do serviço local. O painel só envia credenciais e o token opaco (guardado AQUI, na memória do
 * cliente); papel, admin, MFA e estado de sessão que ele exibe vêm sempre das respostas do serviço e nunca são enviados. Testes injetam um dublê.
 */
public interface AuthorityGateway {
    /** Resposta tipada: code é fixo ("OK" ou o código do serviço; "unavailable"/"connection_closed"/… para falhas locais); result só existe quando ok. */
    record Reply(boolean ok, String code, JsonNode result) {
        @Override
        public String toString() {
            return "Reply[" + code + "]";
        }

        /** O serviço não pôde ser usado (não iniciado, conexão caiu, identidade não verificada…): nunca é "credencial inválida". */
        public boolean serviceUnavailable() {
            return !ok && !code.matches("[A-Z_]{3,40}");
        }
    }

    Reply login(String identifier, char[] password);

    Reply sessionStatus();

    Reply beginSecondFactor();

    Reply verifySecondFactor(String challenge, String code);

    Reply sendSecondFactorSms();

    Reply verifySecondFactorSms(String code);

    Reply adminElevation();

    Reply changePassword(char[] current, char[] next);

    Reply enrollTrustedDevice();

    Reply listTrustedDevices();

    Reply revokeTrustedDevice(String deviceId);

    Reply logout();

    /** Garante que o serviço local esteja rodando (inicia o do próprio bundle se preciso). Dublês de teste devolvem false. */
    default boolean ensureService() {
        return false;
    }

    /** O gateway fala com o serviço do PRÓPRIO bundle (só o cliente real). Dublês de teste/QA: false, e o login não espera por serviço algum. */
    default boolean launchesBundledService() {
        return false;
    }

    // ---- transações (V2.1R): superfície FECHADA e tipada do serviço. O painel só PEDE; o token de sessão fica no cliente. Padrão (dublês): TX_DISABLED. ----

    /** Prepara uma cotação de bank send. amountUbyx é inteiro decimal em ubyx (nunca BYX decimal); operation = id de idempotência (32 hex) escolhido pelo painel. */
    default Reply txPrepareBankSend(String operation, String sender, String recipient, String amountUbyx, String memo, String feeMode) {
        return new Reply(false, "TX_DISABLED", null);
    }

    default Reply txGetQuote(String operation, String quote) {
        return new Reply(false, "TX_DISABLED", null);
    }

    /** Pede a confirmação: o serviço revalida tudo; o painel não autoriza nada. */
    default Reply txConfirm(String operation, String quote) {
        return new Reply(false, "TX_DISABLED", null);
    }

    default Reply txGetStatus(String operation) {
        return new Reply(false, "TX_DISABLED", null);
    }

    default Reply txCancel(String operation) {
        return new Reply(false, "TX_DISABLED", null);
    }

    default Reply walletList() { return new Reply(true, "OK", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(panel.wallet.WalletView.disabled())); }
    default Reply walletCreate(String key, boolean acknowledged) { return new Reply(false, "FEATURE_DISABLED", null); }
    default Reply walletDelete(String wallet, long version, String key, boolean acknowledged) { return new Reply(false, "FEATURE_DISABLED", null); }
    default Reply walletSyntheticSign(String wallet, long version, String key, boolean confirmed) { return new Reply(false, "FEATURE_DISABLED", null); }

    boolean hasSession();

    /** O painel esquece o token (a sessão já não vale no serviço ou o painel saiu). */
    void forgetSession();
}
