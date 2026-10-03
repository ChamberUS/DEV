package panel.auth;

public interface SmsOtpProvider {
    boolean configured();
    String name();
    String startVerification(String phone);
    boolean checkVerification(String phone, String verificationId, String code);
    default ProviderStatus status() { return configured() ? ProviderStatus.configured() : ProviderStatus.missing("SMS provider is not configured"); }
}
