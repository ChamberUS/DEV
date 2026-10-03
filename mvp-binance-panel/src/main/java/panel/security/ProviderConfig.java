package panel.security;

import java.nio.file.*;
import java.util.Properties;

/** IDs and sender only. API secrets have no properties representation. */
public record ProviderConfig(String accountSid, String apiKeySid, String verifyServiceSid, String fromAddress, boolean readable) {
    public static final Path FILE = Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "providers.properties");
    public static ProviderConfig load() {
        Properties p = new Properties();
        try (var in = Files.newInputStream(FILE)) { p.load(in); }
        catch (NoSuchFileException e) { return new ProviderConfig(null, null, null, null, true); }
        catch (java.io.IOException e) { return new ProviderConfig(null, null, null, null, false); }
        return new ProviderConfig(p.getProperty("twilio.accountSid"), p.getProperty("twilio.apiKeySid"),
                p.getProperty("twilio.verifyServiceSid"), p.getProperty("resend.fromAddress"), true);
    }
    public static boolean sid(String value, String prefix) { return value != null && value.matches(prefix + "[0-9a-fA-F]{32}"); }
    @Override public String toString() { return "ProviderConfig[redacted]"; }
}
