package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.secrets.SecretBytes;
import byx.service.secrets.SecretId;
import byx.service.secrets.SecretStatus;
import byx.service.secrets.SecretStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Adaptadores reais contra um transporte de MENTIRA: nenhum e-mail/SMS real é enviado em teste; segredos só do cofre; respostas hostis recusadas. */
class RealSecondFactorTest {
    private static final String ACC = "AC" + "1".repeat(32);
    private static final String SK = "SK" + "2".repeat(32);
    private static final String VA = "VA" + "3".repeat(32);
    private static final String VE = "VE" + "4".repeat(32);
    private final Map<SecretId, byte[]> vault = new HashMap<>();
    private final List<String[]> calls = new ArrayList<>();
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private HttpTransport.Response next = new HttpTransport.Response(200, "{}");
    private final SecretStore store = new SecretStore() {
        public SecretStatus status() {
            return SecretStatus.SECURE_STORAGE_AVAILABLE;
        }

        public void write(SecretId id, SecretBytes v) {
            vault.put(id, v.copyBytes());
        }

        public boolean update(SecretId id, SecretBytes v) {
            return vault.replace(id, v.copyBytes()) != null;
        }

        public Optional<SecretBytes> read(SecretId id) {
            return Optional.ofNullable(vault.get(id)).map(SecretBytes::copyOf);
        }

        public boolean delete(SecretId id) {
            return vault.remove(id) != null;
        }
    };
    private final HttpTransport http = (url, headers, ct, body) -> {
        calls.add(new String[] {url, headers.toString(), ct, new String(body, StandardCharsets.UTF_8)});
        return next;
    };
    private ProviderSettings settings = new ProviderSettings("BYX <noreply@example.test>", ACC, SK, VA);
    private final RealSecondFactor rsf = new RealSecondFactor(() -> settings, store, http, SecretId.RESEND_API_KEY, SecretId.TWILIO_API_SECRET);
    private final Account acc = new Account("a".repeat(32), "someone", Role.ADMIN, true, 1, "$argon2id$x", 1, 1, "someone@example.test", "+5511999990000", true, true, false, 0);

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        vault.put(SecretId.RESEND_API_KEY, "re_FAKE_CANARY_KEY".getBytes(StandardCharsets.UTF_8));
        vault.put(SecretId.TWILIO_API_SECRET, "twilio-FAKE-CANARY-SECRET".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void down() {
        Log.redirect(null);
    }

    @Test
    void configuredNeedsBothProvidersAndBothSecretsInTheVault() {
        assertTrue(rsf.configured());
        assertTrue(rsf.smsRequired());
        vault.remove(SecretId.TWILIO_API_SECRET);
        assertFalse(rsf.configured(), "missing Twilio secret");
        vault.put(SecretId.TWILIO_API_SECRET, new byte[] {1});
        settings = new ProviderSettings(null, ACC, SK, VA);
        assertFalse(rsf.configured(), "missing sender");
        settings = new ProviderSettings("a@example.test", "bad", SK, VA);
        assertFalse(rsf.configured(), "malformed SID");
    }

    @Test
    void emailGoesToTheFixedEndpointWithTheVaultKeyAndNoSecretElsewhere() throws Exception {
        rsf.deliver(acc, "123456".toCharArray());
        String[] c = calls.get(0);
        assertEquals("https://api.resend.com/emails", c[0]);
        assertEquals("{Authorization=Bearer re_FAKE_CANARY_KEY}", c[1]);
        assertTrue(c[3].contains("someone@example.test") && c[3].contains("123456") && c[3].contains("noreply@example.test"));
        assertFalse(String.join("\n", logs).contains("123456") || String.join("\n", logs).contains("re_FAKE"), "nothing in the logs");
        assertThrows(SecondFactorProvider.DeliveryException.class, () -> rsf.deliver(acc.id(), "123456".toCharArray()), "no delivery without the account destination");
        next = new HttpTransport.Response(401, "{\"error\":\"re_FAKE_CANARY_KEY\"}");
        var e = assertThrows(SecondFactorProvider.DeliveryException.class, () -> rsf.deliver(acc, "123456".toCharArray()));
        assertFalse(String.valueOf(e.getMessage()).contains("re_FAKE") || e.getCause() != null, "failure carries no body, no secret");
        vault.remove(SecretId.RESEND_API_KEY);
        assertThrows(SecondFactorProvider.DeliveryException.class, () -> rsf.deliver(acc, "123456".toCharArray()));
    }

    @Test
    void smsStartAndCheckUseTheVerifyServiceWithBasicAuthAndRejectForeignAccounts() throws Exception {
        next = new HttpTransport.Response(201, "{\"sid\":\"" + VE + "\",\"status\":\"pending\",\"account_sid\":\"" + ACC + "\",\"service_sid\":\"" + VA + "\"}");
        assertEquals(VE, rsf.startSms(acc));
        String[] c = calls.get(0);
        assertEquals("https://verify.twilio.com/v2/Services/" + VA + "/Verifications", c[0]);
        String basic = Base64.getEncoder().encodeToString((SK + ":twilio-FAKE-CANARY-SECRET").getBytes(StandardCharsets.UTF_8));
        assertEquals("{Authorization=Basic " + basic + "}", c[1]);
        assertEquals("application/x-www-form-urlencoded", c[2]);
        assertTrue(c[3].contains("To=%2B5511999990000") && c[3].contains("Channel=sms"));
        next = new HttpTransport.Response(200, "{\"sid\":\"" + VE + "\",\"status\":\"approved\",\"account_sid\":\"" + ACC + "\",\"service_sid\":\"" + VA + "\"}");
        assertTrue(rsf.checkSms(acc, VE, "654321"));
        assertEquals("https://verify.twilio.com/v2/Services/" + VA + "/VerificationCheck", calls.get(1)[0]);
        next = new HttpTransport.Response(200, "{\"sid\":\"" + VE + "\",\"status\":\"pending\",\"account_sid\":\"" + ACC + "\",\"service_sid\":\"" + VA + "\"}");
        assertFalse(rsf.checkSms(acc, VE, "654321"), "not approved");
        next = new HttpTransport.Response(200, "{\"sid\":\"" + VE + "\",\"status\":\"approved\",\"account_sid\":\"AC" + "9".repeat(32) + "\",\"service_sid\":\"" + VA + "\"}");
        assertThrows(SecondFactorProvider.DeliveryException.class, () -> rsf.checkSms(acc, VE, "654321"), "an approval from another account is refused");
        assertFalse(rsf.checkSms(acc, "VE-bad", "654321"));
        assertFalse(rsf.checkSms(acc, VE, "12ab56"));
        assertFalse(String.join("\n", logs).contains("twilio-FAKE") || String.join("\n", logs).contains("654321"));
        next = new HttpTransport.Response(200, "{\"sid\":\"not-a-sid\",\"status\":\"pending\",\"account_sid\":\"" + ACC + "\",\"service_sid\":\"" + VA + "\"}");
        assertThrows(SecondFactorProvider.DeliveryException.class, () -> rsf.startSms(acc), "malformed verification id");
    }

    @Test
    void theProductionTransportOnlyTalksHttpsToTheTwoFixedHosts() {
        HttpTransport t = HttpTransport.jdk();
        for (String bad : new String[] {"http://api.resend.com/emails", "https://evil.example/emails", "https://api.resend.com:8443/emails", "https://user:pw@api.resend.com/emails",
            "https://api.resend.com.evil.example/x", "file:///etc/passwd", "https://127.0.0.1/x"}) {
            var e = assertThrows(IOException.class, () -> t.post(bad, Map.of(), "text/plain", new byte[0]), bad);
            assertEquals("host_not_allowed", e.getMessage());
        }
        assertEquals(java.util.Set.of("api.resend.com", "verify.twilio.com"), HttpTransport.ALLOWED_HOSTS);
    }
}
