package byx.service.auth;

/**
 * Configuração NÃO secreta dos provedores de 2º fator (remetente do e-mail, SIDs do Twilio). Vive no snapshot cifrado (integridade e
 * confidencialidade); os SEGREDOS (chave Resend, segredo Twilio) ficam só no cofre do serviço.
 */
public record ProviderSettings(String resendFromAddress, String twilioAccountSid, String twilioApiKeySid, String twilioVerifyServiceSid) {
    public static final ProviderSettings NONE = new ProviderSettings(null, null, null, null);

    public boolean emailConfigured() {
        return resendFromAddress != null && !resendFromAddress.isBlank();
    }

    public boolean smsConfigured() {
        return sid(twilioAccountSid, "AC") && sid(twilioApiKeySid, "SK") && sid(twilioVerifyServiceSid, "VA");
    }

    static boolean sid(String v, String prefix) {
        return v != null && v.matches(prefix + "[0-9a-fA-F]{32}");
    }

    @Override
    public String toString() {
        return "ProviderSettings[redacted]";
    }
}
