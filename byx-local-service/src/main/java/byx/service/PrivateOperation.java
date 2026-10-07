package byx.service;

import byx.service.auth.Role;
import java.util.Optional;

/**
 * Operações privadas TIPADAS (contrato futuro; NENHUMA está no protocolo de IPC hoje). Cada uma pertence a exatamente UMA capacidade e declara o que exige além do
 * piso da capacidade. Não existe operação genérica: nada de http.request, binance.request, proxy, rawQuery, signedRequest, execute, dumpSecret nem notification.send.
 * Configurar/remover credencial exige mais que ler (segundo fator recente), e leitura exige sessão + posse.
 */
public enum PrivateOperation {
    NOTIFICATIONS_STATUS("notifications.status", PrivateCapability.NOTIFICATIONS, Role.USER, false, false, true),
    NOTIFICATIONS_SUBSCRIBE("notifications.subscribe", PrivateCapability.NOTIFICATIONS, Role.USER, false, false, true),
    NOTIFICATIONS_UNSUBSCRIBE("notifications.unsubscribe", PrivateCapability.NOTIFICATIONS, Role.USER, false, false, true),
    ACCOUNT_STATUS("account.status", PrivateCapability.BINANCE_ACCOUNT_READ, Role.USER, false, false, true),
    ACCOUNT_BALANCES("account.balances", PrivateCapability.BINANCE_ACCOUNT_READ, Role.USER, false, false, true),
    ACCOUNT_POSITIONS("account.positions", PrivateCapability.BINANCE_ACCOUNT_READ, Role.USER, false, false, true),
    /** Conectar/trocar a credencial: segundo fator recente + posse + ação explícita do usuário (sem elevação de admin: a credencial é do próprio usuário). */
    ACCOUNT_CREDENTIAL_CONFIGURE("account.credential.configure", PrivateCapability.BINANCE_ACCOUNT_READ, Role.USER, true, false, true),
    ACCOUNT_CREDENTIAL_REMOVE("account.credential.remove", PrivateCapability.BINANCE_ACCOUNT_READ, Role.USER, true, false, true),
    ADMIN_OPERATION("admin.operation", PrivateCapability.ADMIN_OPERATIONS, Role.ADMIN, true, true, false);

    private final String wire;
    private final PrivateCapability capability;
    private final Role minRole;
    private final boolean recentMfa;
    private final boolean elevation;
    private final boolean ownership;

    PrivateOperation(String wire, PrivateCapability capability, Role minRole, boolean recentMfa, boolean elevation, boolean ownership) {
        // uma operação nunca é mais frouxa que o piso da capacidade
        if (!minRole.atLeast(capability.minRole()) || capability.recentMfa() && !recentMfa || capability.elevation() && !elevation || capability.ownership() && !ownership) {
            throw new IllegalStateException("operation weaker than its capability: " + wire);
        }
        this.wire = wire;
        this.capability = capability;
        this.minRole = minRole;
        this.recentMfa = recentMfa;
        this.elevation = elevation;
        this.ownership = ownership;
    }

    public String wire() { return wire; }

    public PrivateCapability capability() { return capability; }

    public Role minRole() { return minRole; }

    public boolean recentMfa() { return recentMfa; }

    public boolean elevation() { return elevation; }

    public boolean ownership() { return ownership; }

    public static Optional<PrivateOperation> fromWire(String wire) {
        for (PrivateOperation o : values()) {
            if (o.wire.equals(wire)) {
                return Optional.of(o);
            }
        }
        return Optional.empty();
    }
}
