package panel.security;

import java.util.Optional;

public interface SecretStore {
    String RESEND = "mvp-binance-panel/resend-api-key";
    String TWILIO = "mvp-binance-panel/twilio-api-secret";
    String DEVICE = "mvp-binance-panel/trusted-device-token";
    Optional<char[]> read(String name);
    void write(String name, char[] secret);
    void delete(String name);
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("macOS Keychain unavailable or access denied"); }
    }
}
