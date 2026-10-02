package panel.auth;

/**
 * DEVELOPMENT AUTH PROVIDER: não envia nada; guarda o último código em memória.
 * Só é ligado com security.dev.mode=true. Nunca registra o código em log.
 */
public class DevOtpProvider implements EmailOtpProvider, SmsOtpProvider {
    public static final String LABEL = "DEVELOPMENT AUTH PROVIDER";

    private volatile String lastCode;
    private volatile String lastDestination;

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String name() {
        return LABEL;
    }

    @Override
    public void send(String destination, String code) {
        lastDestination = destination;
        lastCode = code;
    }

    public String lastCode() {
        return lastCode;
    }

    public String lastDestination() {
        return lastDestination;
    }
}
