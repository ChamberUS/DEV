package byx.service;

import byx.service.auth.Role;
import byx.service.secrets.SecretId;
import java.util.Optional;
import java.util.Set;

/**
 * Lista FECHADA das capacidades privadas do serviço e dos requisitos de cada uma. É o contrato, não a ativação: nenhuma está implementada nem habilitada, e a decisão final
 * sempre vem de {@link PrivateCapabilityGate} (master AND habilitada AND implementada AND pré-requisitos AND autorização). Desconhecida = negada. O nome de fio ({@link #wire()}) é o
 * que o painel já conhece em {@code capabilities.features}; o painel só apresenta, nunca decide.
 *
 * Notas de escopo: Resend/Twilio da AUTENTICAÇÃO não são capacidades privadas (são infraestrutura do login); SECRET_INTEGRATIONS é para integrações futuras NÃO-auth e nunca é exposta
 * à UI; wallet/payment/gas do painel são outra superfície (ServerAuthorizer do painel, DENY_ALL).
 */
public enum PrivateCapability {
    /** Notificações privadas derivadas de conta/atividade do usuário (não é o envio de OTP de segurança; não é notificação local de desktop). */
    NOTIFICATIONS("notifications", Role.USER, false, false, true, null, Set.of(), Status.CONTRACT_ONLY, true),
    /** Leitura SOMENTE LEITURA da conta Binance (saldos, posições, estado). Credencial só no serviço. */
    BINANCE_ACCOUNT_READ("accountData", Role.USER, false, false, true, SecretId.BINANCE_READONLY_CREDENTIAL, Set.of("fapi.binance.com", "api.binance.com"), Status.CONTRACT_ONLY, true),
    /** Operações administrativas reais (hoje inexistentes). */
    ADMIN_OPERATIONS("adminOperations", Role.ADMIN, true, true, false, null, Set.of(), Status.NOT_IMPLEMENTED, true),
    /** Integrações apoiadas em segredo (não-auth). NUNCA exposta diretamente à UI: nenhuma operação de IPC mapeia para ela. */
    SECRET_INTEGRATIONS("secretIntegrations", Role.ADMIN, true, true, false, null, Set.of(), Status.NOT_IMPLEMENTED, false);

    public enum Status { NOT_IMPLEMENTED, CONTRACT_ONLY, IMPLEMENTED }

    private final String wire;
    private final Role minRole;
    private final boolean recentMfa;
    private final boolean elevation;
    private final boolean ownership;
    private final SecretId requiredSecret;
    private final Set<String> hosts;
    private final Status status;
    private final boolean uiExposed;

    PrivateCapability(String wire, Role minRole, boolean recentMfa, boolean elevation, boolean ownership, SecretId requiredSecret, Set<String> hosts, Status status, boolean uiExposed) {
        this.wire = wire;
        this.minRole = minRole;
        this.recentMfa = recentMfa;
        this.elevation = elevation;
        this.ownership = ownership;
        this.requiredSecret = requiredSecret;
        this.hosts = Set.copyOf(hosts);
        this.status = status;
        this.uiExposed = uiExposed;
    }

    public String wire() { return wire; }

    /** Requisitos BASE da capacidade (as operações podem exigir mais, nunca menos). */
    public Role minRole() { return minRole; }

    public boolean recentMfa() { return recentMfa; }

    public boolean elevation() { return elevation; }

    public boolean ownership() { return ownership; }

    /** Identidade verificada do peer (app empacotado e assinado): sempre exigida. */
    public boolean requiresVerifiedPeer() { return true; }

    public Optional<SecretId> requiredSecret() { return Optional.ofNullable(requiredSecret); }

    /** Hosts de rede que a capacidade poderá contatar no futuro (lista fechada; nada vem da UI). */
    public Set<String> hosts() { return hosts; }

    public Status status() { return status; }

    public boolean implemented() { return status == Status.IMPLEMENTED; }

    public boolean uiExposed() { return uiExposed; }

    /** Nome de fio → capacidade; qualquer outro texto (inclusive null) = vazio = NEGADO. */
    public static Optional<PrivateCapability> fromWire(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        for (PrivateCapability c : values()) {
            if (c.wire.equals(wire)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
