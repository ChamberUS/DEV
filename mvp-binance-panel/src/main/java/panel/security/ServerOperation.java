package panel.security;

/** Lista FECHADA das operações sensíveis que ainda dependem de autorização do serviço. O nome de fio é o que aparece em SERVER_AUTHORIZATION_REQUIRED. */
public enum ServerOperation {
    WALLET_IDENTITY("wallet.identity"),
    WALLET_PAYMENT("wallet.payment"),
    WALLET_GAS_REQUEST("wallet.gas.request"),
    WALLET_GAS_REFRESH("wallet.gas.refresh"),
    WALLET_GAS_REVOKE("wallet.gas.revoke"),
    WALLET_GAS_JOURNAL("wallet.gas.journal"),
    WALLET_TREASURY_READ("wallet.treasury.read"),
    RESEARCH_JOB_SUBMIT("research.job.submit"),
    RESEARCH_JOB_CANCEL("research.job.cancel"),
    SETTINGS_PERSIST("settings.persist"),
    SETTINGS_PREFERENCES_PERSIST("settings.preferences.persist"),
    LEGACY_SECURITY_AUDIT_WRITE("legacy.security.audit.write");

    private final String wireName;

    ServerOperation(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
