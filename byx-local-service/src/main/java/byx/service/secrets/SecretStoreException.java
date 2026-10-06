package byx.service.secrets;

/** Falha do armazenamento seguro. A mensagem é SÓ o código fixo; o OSStatus numérico (não secreto) é só diagnóstico interno. */
public final class SecretStoreException extends Exception {
    private final SecretStatus status;
    private final int osStatus;

    public SecretStoreException(SecretStatus status) {
        this(status, 0);
    }

    SecretStoreException(SecretStatus status, int osStatus) {
        super(status.name(), null, false, false); // sem causa e sem pilha: nada de dado do chamador pode vazar por aqui
        this.status = status;
        this.osStatus = osStatus;
    }

    public SecretStatus status() {
        return status;
    }

    int osStatus() {
        return osStatus;
    }
}
