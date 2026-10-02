package panel.auth;

public class UnconfiguredEmailOtpProvider implements EmailOtpProvider {
    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String name() {
        return "Not configured";
    }

    @Override
    public void send(String email, String code) {
        throw new TwoFactorNotConfiguredException();
    }
}
