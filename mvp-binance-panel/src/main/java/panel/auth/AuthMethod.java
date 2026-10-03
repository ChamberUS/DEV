package panel.auth;
public enum AuthMethod {
    TWO_FACTOR("2FA"), TRUSTED_DEVICE("Trusted Device"), PASSKEY("Passkey (not implemented)");
    public final String label;
    AuthMethod(String label) { this.label=label; }
}
