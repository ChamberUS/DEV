package panel.security;

import java.io.IOException;

/**
 * Decisão de autorização do SERVIÇO para uma operação sensível. A única implementação de produção é {@link ServerAuthorization#DENY_ALL}; nenhuma
 * implementação permissiva existe em src/main. Um construtor que recebe um ServerAuthorizer só é usado por testes; a composição de produção nunca o usa.
 * Sessão, papel e elevação do painel são apresentação e não entram aqui.
 */
public interface ServerAuthorizer {
    /** Retorna somente se a operação estiver autorizada; caso contrário lança {@link AccessDeniedException} ANTES de qualquer efeito. */
    void require(ServerOperation operation);

    default void requirePersistence(ServerOperation operation) throws IOException {
        try {
            require(operation);
        } catch (AccessDeniedException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
