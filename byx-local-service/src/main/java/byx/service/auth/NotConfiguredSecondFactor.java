package byx.service.auth;

/** Provedor de PRODUÇÃO desta fase: não configurado. */
public final class NotConfiguredSecondFactor implements SecondFactorProvider {
    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public void deliver(String accountId, char[] code) throws DeliveryException {
        throw new DeliveryException();
    }
}
