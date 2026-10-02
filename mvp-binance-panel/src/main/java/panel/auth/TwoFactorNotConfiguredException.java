package panel.auth;

public class TwoFactorNotConfiguredException extends RuntimeException {
    public TwoFactorNotConfiguredException() {
        super("Two-factor authentication is not configured.");
    }
}
