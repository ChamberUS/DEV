package panel.auth;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import java.util.Arrays;
import panel.security.*;

public final class ResendEmailOtpProvider implements EmailOtpProvider {
    @FunctionalInterface public interface Sender { void send(char[] key, String from, String to, String code) throws Exception; }
    private final SecretStore secrets; private final ProviderConfig config; private final Sender sender;
    private volatile boolean failed;
    public ResendEmailOtpProvider(SecretStore secrets, ProviderConfig config) {
        this(secrets, config, (key, from, to, code) -> new Resend(new String(key)).emails().send(CreateEmailOptions.builder()
                .from(from).to(to).subject("MVP Binance — Administrator Verification")
                .text("MVP Binance\n\nAdministrator verification code\n\n" + code + "\n\nThis code expires in 5 minutes.\nNever share this code.")
                .build()));
    }
    public ResendEmailOtpProvider(SecretStore secrets, ProviderConfig config, Sender sender) { this.secrets=secrets; this.config=config; this.sender=sender; }
    public ProviderStatus status() {
        var status=configurationStatus();
        return failed && status.state()==ProviderStatus.State.CONFIGURED ? ProviderStatus.error() : status;
    }
    public String name() { return "Resend"; }
    public boolean configured() { return configurationStatus().state() == ProviderStatus.State.CONFIGURED; }
    private ProviderStatus configurationStatus() {
        if (!config.readable()) return ProviderStatus.error();
        if (config.fromAddress() == null || config.fromAddress().isBlank()) return ProviderStatus.missing("Missing Resend From Address");
        try { var key = secrets.read(SecretStore.RESEND); if (key.isEmpty()) return ProviderStatus.missing("Missing Resend API key in Keychain");
            boolean present = key.get().length > 0; Arrays.fill(key.get(), '\0');
            return !present ? ProviderStatus.missing("Missing Resend API key in Keychain") : ProviderStatus.configured();
        } catch (SecretStore.Unavailable e) { return ProviderStatus.error(); }
    }
    public void send(String email, String code) {
        if (code == null || !code.matches("[0-9]{6}")) throw new IllegalArgumentException("Invalid email challenge");
        if (!config.readable() || config.fromAddress() == null || config.fromAddress().isBlank()) throw new TwoFactorNotConfiguredException();
        char[] key = secrets.read(SecretStore.RESEND).orElseThrow(TwoFactorNotConfiguredException::new);
        try { sender.send(key, config.fromAddress(), email, code); failed=false; }
        catch (Exception e) { failed=true; throw new IllegalStateException("Email delivery failed. Check provider configuration."); }
        finally { Arrays.fill(key, '\0'); }
    }
}
