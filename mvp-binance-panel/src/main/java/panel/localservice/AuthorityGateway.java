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

    boolean hasSession();

    /** O painel esquece o token (a sessão já não vale no serviço ou o painel saiu). */
    void forgetSession();
}
