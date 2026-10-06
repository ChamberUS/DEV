package byx.service.auth;

import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStore;
import byx.service.secrets.SecretStoreException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Adaptadores REAIS do segundo fator, DENTRO do serviço: e-mail (Resend, código de 6 dígitos gerado pelo serviço) e SMS (Twilio Verify, código
 * gerado e conferido pelo provedor). Os segredos (chave Resend, segredo Twilio) vêm só do cofre do serviço (escopo PRODUCTION) a cada uso, nunca de
 * propriedade, ambiente, arquivo ou argv, e NUNCA chegam ao painel; só a configuração não secreta (remetente, SIDs) vem do snapshot cifrado. O painel só
 * pede begin/verify. Falha do provedor ⇒ {@link DeliveryException} fixa (sem corpo, sem segredo). O OTP de e-mail nunca é logado.
 */
public final class RealSecondFactor implements SecondFactorProvider {
    private static final JsonMapper JSON = new JsonMapper();
    private final Supplier<ProviderSettings> settings;
    private final SecretStore secrets;
    private final HttpTransport http;
    private final SecretId resendKey;
    private final SecretId twilioSecret;

    public RealSecondFactor(Supplier<ProviderSettings> settings, SecretStore secrets, HttpTransport http, SecretId resendKey, SecretId twilioSecret) {
        this.settings = settings;
        this.secrets = secrets;
        this.http = http;
        this.resendKey = resendKey;
        this.twilioSecret = twilioSecret;
    }

    @Override
    public boolean configured() {
        ProviderSettings p = settings.get();
        return p.emailConfigured() && p.smsConfigured() && present(resendKey) && present(twilioSecret);
    }

    @Override
    public boolean smsRequired() {
        return true;
    }

    private boolean present(SecretId id) {
        try (SecretBytes v = secrets.read(id).orElse(null)) {
            return v != null;
        } catch (SecretStoreException e) {
            return false;
        }
    }

    private String secret(SecretId id) throws DeliveryException {
        try (SecretBytes v = secrets.read(id).orElseThrow(DeliveryException::new)) {
            byte[] b = v.copyBytes();
            try {
                return new String(b, StandardCharsets.UTF_8);
            } finally {
                java.util.Arrays.fill(b, (byte) 0);
            }
        } catch (SecretStoreException e) {
            throw new DeliveryException();
        }
    }

    @Override
    public void deliver(String accountId, char[] code) throws DeliveryException {
        throw new DeliveryException(); // o adaptador real precisa da conta (destino): usa deliver(Account, …)
    }

    @Override
    public void deliver(Account a, char[] code) throws DeliveryException {
        ProviderSettings p = settings.get();
        if (!p.emailConfigured() || a.email() == null || code.length != 6) {
            throw new DeliveryException();
        }
        String key = secret(resendKey);
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("from", p.resendFromAddress());
            body.putArray("to").add(a.email());
            body.put("subject", "MVP Binance — Administrator Verification");
            body.put("text", "MVP Binance\n\nAdministrator verification code\n\n" + new String(code) + "\n\nThis code expires in 5 minutes.\nNever share this code.");
            HttpTransport.Response r = http.post("https://api.resend.com/emails", Map.of("Authorization", "Bearer " + key), "application/json", JSON.writeValueAsBytes(body));
            if (r.status() < 200 || r.status() >= 300) {
                throw new DeliveryException();
            }
        } catch (IOException e) {
            throw new DeliveryException();
        }
    }

    private JsonNode twilio(String action, Map<String, String> fields) throws DeliveryException {
        ProviderSettings p = settings.get();
        if (!p.smsConfigured()) {
            throw new DeliveryException();
        }
        String secret = secret(twilioSecret);
        try {
            StringBuilder form = new StringBuilder();
            for (Map.Entry<String, String> e : fields.entrySet()) {
                form.append(form.length() == 0 ? "" : "&").append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            String basic = Base64.getEncoder().encodeToString((p.twilioApiKeySid() + ":" + secret).getBytes(StandardCharsets.UTF_8));
            HttpTransport.Response r = http.post("https://verify.twilio.com/v2/Services/" + p.twilioVerifyServiceSid() + "/" + action, Map.of("Authorization", "Basic " + basic),
                    "application/x-www-form-urlencoded", form.toString().getBytes(StandardCharsets.UTF_8));
            if (r.status() < 200 || r.status() >= 300) {
                throw new DeliveryException();
            }
            JsonNode v = JSON.readTree(r.body());
            if (!p.twilioAccountSid().equals(v.path("account_sid").asText()) || !p.twilioVerifyServiceSid().equals(v.path("service_sid").asText())) {
                throw new DeliveryException(); // resposta de outra conta/serviço: recusada
            }
            return v;
        } catch (IOException e) {
            throw new DeliveryException();
        }
    }

    @Override
    public String startSms(Account a) throws DeliveryException {
        if (a.phone() == null) {
            throw new DeliveryException();
        }
        JsonNode v = twilio("Verifications", Map.of("To", a.phone(), "Channel", "sms"));
        String sid = v.path("sid").asText();
        if (!sid.matches("VE[0-9a-fA-F]{32}") || !"pending".equals(v.path("status").asText())) {
            throw new DeliveryException();
        }
        return sid;
    }

    @Override
    public boolean checkSms(Account a, String verificationId, String code) throws DeliveryException {
        if (!verificationId.matches("VE[0-9a-fA-F]{32}") || !code.matches("[0-9]{4,10}")) {
            return false;
        }
        JsonNode v = twilio("VerificationCheck", Map.of("VerificationSid", verificationId, "Code", code));
        return verificationId.equals(v.path("sid").asText()) && "approved".equals(v.path("status").asText());
    }
}
