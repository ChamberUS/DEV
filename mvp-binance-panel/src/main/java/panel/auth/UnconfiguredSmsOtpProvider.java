package panel.auth;
public class UnconfiguredSmsOtpProvider implements SmsOtpProvider {
    public boolean configured() { return false; }
    public String name() { return "Not configured"; }
    public String startVerification(String phone) { throw new TwoFactorNotConfiguredException(); }
    public boolean checkVerification(String phone, String id, String code) { throw new TwoFactorNotConfiguredException(); }
}
