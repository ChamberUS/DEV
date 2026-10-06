package byx.service.auth;

/**
 * Entrega do segundo fator. O fluxo real tem DOIS estágios (como o painel legado): (1) e-mail com código de 6 dígitos gerado pelo serviço
 * ({@link #deliver}) e (2) SMS verificado pelo provedor ({@link #startSms}/{@link #checkSms}, Twilio Verify). Produção sem segredos usa
 * {@link NotConfiguredSecondFactor}; os testes injetam um dublê; os adaptadores reais ({@code RealSecondFactor}) leem os segredos só do cofre do
 * serviço. O código de e-mail nunca volta por IPC. Nenhum provedor de desenvolvimento no artefato de produção.
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

    /** Estágio e-mail com o destino real (adaptadores reais usam {@code a.email()}); o padrão cai no dublê por id. */
    default void deliver(Account a, char[] code) throws DeliveryException {
        deliver(a.id(), code);
    }

    /** Verdadeiro se, depois do e-mail, o SMS também é obrigatório para concluir o segundo fator. */
    default boolean smsRequired() {
        return false;
    }

    /** Inicia a verificação por SMS; devolve o id da verificação (opaco). */
    default String startSms(Account a) throws DeliveryException {
        throw new DeliveryException();
    }

    /** Confere o código de SMS contra o id da verificação. */
    default boolean checkSms(Account a, String verificationId, String code) throws DeliveryException {
        throw new DeliveryException();
    }
}
