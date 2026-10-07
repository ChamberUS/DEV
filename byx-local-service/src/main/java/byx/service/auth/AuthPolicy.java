package byx.service.auth;

import byx.service.PrivateCapability;
import byx.service.PrivateOperation;
import java.util.HashMap;
import java.util.Map;

/**
 * Tabela FECHADA de autorização: operação → (papel mínimo, segundo fator recente, elevação administrativa, posse do recurso, capacidade privada).
 * Negar por padrão: operação não registrada = DENIED. A elevação é propriedade TEMPORÁRIA da sessão, nunca promoção do papel. Operações que
 * dependem de capacidade privada ficam negadas enquanto o gate estático ({@code PrivateCapabilityGate}) estiver fechado.
 */
public final class AuthPolicy {
    /** capability: a capacidade privada de que a operação depende (null = operação não privada). Cada capacidade tem o próprio gate; nenhuma libera outra. */
    public record Rule(Role minRole, boolean recentMfa, boolean elevation, boolean ownership, PrivateCapability capability) {
    }

    private static final Rule USER = new Rule(Role.USER, false, false, false, null);
    private final Map<String, Rule> rules;

    private AuthPolicy(Map<String, Rule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public Rule rule(String op) {
        return op == null ? null : rules.get(op);
    }

    public java.util.Set<String> operations() {
        return rules.keySet();
    }

    /** A tabela do serviço. As operações "qa.*" existem só para provar o motor de autorização (não são operações de IPC de produção). */
    public static AuthPolicy standard() {
        Map<String, Rule> all = new HashMap<>(base());
        // operações privadas TIPADAS (contrato futuro): derivadas de PrivateOperation, a fonte única; nenhuma está no IPC e todas ficam negadas pelo gate
        for (PrivateOperation op : PrivateOperation.values()) {
            all.put(op.wire(), new Rule(op.minRole(), op.recentMfa(), op.elevation(), op.ownership(), op.capability()));
        }
        return new AuthPolicy(all);
    }

    private static Map<String, Rule> base() {
        return Map.ofEntries(
                Map.entry("auth.sessionStatus", USER),
                Map.entry("auth.logout", USER),
                Map.entry("auth.beginSecondFactor", USER),
                Map.entry("auth.verifySecondFactor", USER),
                Map.entry("auth.changePassword", USER),
                Map.entry("auth.adminElevation", new Rule(Role.ADMIN, true, false, false, null)),
                // nomes legados (V2.1F), mantidos e agora ligados à capacidade correta; negados pelo gate
                Map.entry("account.read", new Rule(Role.USER, true, false, true, PrivateCapability.BINANCE_ACCOUNT_READ)),
                Map.entry("notifications.read", new Rule(Role.USER, false, false, true, PrivateCapability.NOTIFICATIONS)),
                // motor de autorização (testes)
                Map.entry("qa.userOp", USER),
                Map.entry("qa.mfaOp", new Rule(Role.USER, true, false, false, null)),
                Map.entry("qa.adminOp", new Rule(Role.ADMIN, true, true, false, null)),
                Map.entry("qa.ownedOp", new Rule(Role.USER, false, false, true, null)),
                Map.entry("qa.privateOp", new Rule(Role.USER, false, false, false, PrivateCapability.NOTIFICATIONS)));
    }
}
