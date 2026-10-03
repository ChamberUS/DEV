package panel.auth;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import panel.security.*;

public final class TwilioVerifySmsProvider implements SmsOtpProvider {
    @FunctionalInterface public interface Transport { JsonNode post(String action, Map<String,String> fields, char[] secret) throws Exception; }
    private final ProviderConfig config; private final SecretStore secrets; private final Transport transport;
    private volatile boolean failed;
    public TwilioVerifySmsProvider(SecretStore secrets, ProviderConfig config) {
        this(secrets, config, (action, fields, secret) -> {
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                String body = fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
                byte[] credentials = (config.apiKeySid() + ":" + new String(secret)).getBytes(StandardCharsets.UTF_8);
                String basic = Base64.getEncoder().encodeToString(credentials); Arrays.fill(credentials, (byte)0);
                var request = HttpRequest.newBuilder(URI.create("https://verify.twilio.com/v2/Services/" + config.verifyServiceSid() + "/" + action))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Basic " + basic)
                        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Verify request failed");
                return new ObjectMapper().readTree(response.body());
            }
        });
    }
    public TwilioVerifySmsProvider(SecretStore secrets, ProviderConfig config, Transport transport) { this.secrets=secrets; this.config=config; this.transport=transport; }
    public ProviderStatus status() {
        var status=configurationStatus();
        return failed && status.state()==ProviderStatus.State.CONFIGURED ? ProviderStatus.error() : status;
    }
    public String name() { return "Twilio Verify"; }
    private ProviderStatus configStatus() {
        if (!config.readable()) return ProviderStatus.error();
        if (!ProviderConfig.sid(config.verifyServiceSid(), "VA")) return ProviderStatus.missing("Missing Verify Service SID");
        if (!ProviderConfig.sid(config.accountSid(), "AC") || !ProviderConfig.sid(config.apiKeySid(), "SK")) return ProviderStatus.missing("Missing Account SID or API Key SID");
        return ProviderStatus.configured();
    }
    private ProviderStatus configurationStatus() {
        var status = configStatus(); if (status.state() != ProviderStatus.State.CONFIGURED) return status;
        try { var key=secrets.read(SecretStore.TWILIO); if (key.isEmpty()) return ProviderStatus.missing("Missing API secret in Keychain");
            boolean present=key.get().length>0; Arrays.fill(key.get(), '\0');
            return !present ? ProviderStatus.missing("Missing API secret in Keychain") : ProviderStatus.configured();
        } catch (SecretStore.Unavailable e) { return ProviderStatus.error(); }
    }
    public boolean configured() { return configurationStatus().state()==ProviderStatus.State.CONFIGURED; }
    private JsonNode request(String action, Map<String,String> fields) {
        if (configStatus().state()!=ProviderStatus.State.CONFIGURED) throw new TwoFactorNotConfiguredException();
        char[] key=secrets.read(SecretStore.TWILIO).orElseThrow(TwoFactorNotConfiguredException::new);
        try { JsonNode value=transport.post(action, fields, key);
            if (!config.accountSid().equals(value.path("account_sid").asText()) || !config.verifyServiceSid().equals(value.path("service_sid").asText())) throw new IllegalStateException();
            failed=false; return value;
        } catch (Exception e) { failed=true; if(e instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IllegalStateException("SMS verification failed. Check provider configuration."); }
        finally { Arrays.fill(key, '\0'); }
    }
    public String startVerification(String phone) {
        if (phone==null || !phone.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Phone must use E.164 format");
        JsonNode value=request("Verifications", Map.of("To", phone, "Channel", "sms"));
        String sid=value.path("sid").asText();
        if (!ProviderConfig.sid(sid,"VE") || !"pending".equals(value.path("status").asText())) throw new IllegalStateException("SMS verification was not started");
        return sid;
    }
    public boolean checkVerification(String phone, String verificationId, String code) {
        if (!ProviderConfig.sid(verificationId,"VE") || code==null || !code.matches("[0-9]{4,10}")) return false;
        JsonNode value=request("VerificationCheck", Map.of("VerificationSid",verificationId,"Code",code));
        return verificationId.equals(value.path("sid").asText()) && "approved".equals(value.path("status").asText());
    }
}
