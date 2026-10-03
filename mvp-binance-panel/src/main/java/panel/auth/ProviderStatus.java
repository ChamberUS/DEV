package panel.auth;

public record ProviderStatus(State state, String detail) {
    public enum State { CONFIGURED, NOT_CONFIGURED, ERROR }
    public static ProviderStatus configured() { return new ProviderStatus(State.CONFIGURED, "Ready"); }
    public static ProviderStatus missing(String detail) { return new ProviderStatus(State.NOT_CONFIGURED, detail); }
    public static ProviderStatus error() { return new ProviderStatus(State.ERROR, "Provider or Keychain unavailable"); }
}
