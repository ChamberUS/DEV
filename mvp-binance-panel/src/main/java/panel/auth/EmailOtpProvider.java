package panel.auth;

public interface EmailOtpProvider {
    boolean configured();
    String name();
    void send(String email, String code);
    default ProviderStatus status() { return configured() ? ProviderStatus.configured() : ProviderStatus.missing("Email provider is not configured"); }
}
