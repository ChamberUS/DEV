package panel.security;

import java.util.List;

/**
 * Histórico de auditoria LEGADO (somente leitura). A fonte é o {@code panel.db} (rollback-only), aberto exclusivamente por {@link LegacyPanelDb}. Não há escrita
 * nesta interface: a auditoria nova é do serviço local.
 */
public interface LegacyAuditHistory {
    /** Linhas cruas mais recentes primeiro: ts, event, actor, detail. Indisponível = lista vazia (nunca recria o banco). */
    List<String[]> recent(int limit);

    List<String[]> recentFor(String actor, int limit);

    /** Só para mascarar linhas antigas na exibição: o nome existe na tabela de contas legada? Indisponível = false. */
    boolean legacyUsernameExists(String username);

    /** Nenhum histórico legado disponível (arquivo ausente/ilegível/sem a tabela): o produto segue sem ele e não tenta criar nada. */
    LegacyAuditHistory UNAVAILABLE = new LegacyAuditHistory() {
        @Override public List<String[]> recent(int limit) { return List.of(); }
        @Override public List<String[]> recentFor(String actor, int limit) { return List.of(); }
        @Override public boolean legacyUsernameExists(String username) { return false; }
    };
}
