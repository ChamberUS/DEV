package byx.service.auth;

/**
 * Entrega do segundo fator (e-mail/SMS). Nesta fase NÃO existe provedor real: produção usa {@link NotConfiguredSecondFactor} e responde
 * SECOND_FACTOR_NOT_CONFIGURED; os testes injetam um dublê. O código nunca volta por IPC. Nenhum provedor de desenvolvimento no artefato de produção.
 */
public interface SecondFactorProvider {
    final class DeliveryException extends Exception {
        public DeliveryException() {
            super("delivery_failed", null, false, false);
        }
    }

    boolean configured();

    /** accountId = identificador opaco da conta; code = 6 dígitos (o provedor deve zerar a cópia quando terminar). */
    void deliver(String accountId, char[] code) throws DeliveryException;
}
