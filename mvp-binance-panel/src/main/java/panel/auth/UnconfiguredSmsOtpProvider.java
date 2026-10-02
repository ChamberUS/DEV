package panel.auth;

public class UnconfiguredSmsOtpProvider implements SmsOtpProvider {
    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String name() {
        return "Not configured";
    }

    @Override
    public void send(String phone, String code) {
        throw new TwoFactorNotConfiguredException();
    }
}
