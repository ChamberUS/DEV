package byx.service.auth;

import java.util.Map;

/**
 * Tabela FECHADA de autorização: operação → (papel mínimo, segundo fator recente, elevação administrativa, posse do recurso, capacidade privada).
 * Negar por padrão: operação não registrada = DENIED. A elevação é propriedade TEMPORÁRIA da sessão, nunca promoção do papel. Operações que
 * dependem de capacidade privada ficam negadas enquanto o gate estático ({@code PrivateCapabilityGate}) estiver fechado.
 */
public final class AuthPolicy {
    public record Rule(Role minRole, boolean recentMfa, boolean elevation, boolean ownership, boolean privateCapability) {
    }

    private static final Rule USER = new Rule(Role.USER, false, false, false, false);
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
        return new AuthPolicy(Map.ofEntries(
                Map.entry("auth.sessionStatus", USER),
                Map.entry("auth.logout", USER),
                Map.entry("auth.beginSecondFactor", USER),
                Map.entry("auth.verifySecondFactor", USER),
                Map.entry("auth.changePassword", USER),
                Map.entry("auth.adminElevation", new Rule(Role.ADMIN, true, false, false, false)),
                // futuras operações privadas: DECLARADAS, porém negadas pelo gate até a revisão explícita
                Map.entry("account.read", new Rule(Role.USER, true, false, true, true)),
                Map.entry("notifications.read", new Rule(Role.USER, false, false, true, true)),
                Map.entry("admin.operation", new Rule(Role.ADMIN, true, true, false, true)),
                // motor de autorização (testes)
                Map.entry("qa.userOp", USER),
                Map.entry("qa.mfaOp", new Rule(Role.USER, true, false, false, false)),
                Map.entry("qa.adminOp", new Rule(Role.ADMIN, true, true, false, false)),
                Map.entry("qa.ownedOp", new Rule(Role.USER, false, false, true, false)),
                Map.entry("qa.privateOp", new Rule(Role.USER, false, false, false, true))));
    }
}
