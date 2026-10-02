package panel.auth;

public enum AuthMethod {
    TRUSTED_IPV6("Trusted Network"), TWO_FACTOR("2FA");

    public final String label;

    AuthMethod(String label) {
        this.label = label;
    }
}
