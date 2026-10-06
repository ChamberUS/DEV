package byx.service.auth;

/**

 * Configuração NÃO secreta da autoridade: provedores de 2º fator (remetente do e-mail, SIDs do Twilio) e a janela da elevação administrativa (migrada do legado: 30 min). Vive no snapshot cifrado (integridade e
 * confidencialidade); os SEGREDOS (chave Resend, segredo Twilio) ficam só no cofre do serviço.
 */
public record ProviderSettings(String resendFromAddress, String twilioAccountSid, String twilioApiKeySid, String twilioVerifyServiceSid, int adminElevationMinutes) {
    public static final ProviderSettings NONE = new ProviderSettings(null, null, null, null, 0);
    public static final int MAX_ELEVATION_MINUTES = 60;

    public ProviderSettings(String resendFromAddress, String twilioAccountSid, String twilioApiKeySid, String twilioVerifyServiceSid) {
        this(resendFromAddress, twilioAccountSid, twilioApiKeySid, twilioVerifyServiceSid, 0);
    }

    /** Janela (ms) da elevação administrativa: o valor migrado do painel legado (1..60 min) ou, se ausente, o padrão de {@link AuthLimits}. */
    public long elevationMs() {
        return adminElevationMinutes >= 1 && adminElevationMinutes <= MAX_ELEVATION_MINUTES ? adminElevationMinutes * 60_000L : AuthLimits.ADMIN_ELEVATION_TIMEOUT.toMillis();
    }

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
