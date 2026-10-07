package panel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import panel.security.AccessDeniedException;
import panel.security.ServerAuthorization;
import panel.security.ServerAuthorizer;
import panel.security.ServerOperation;

/**
 * SÓ TESTE (src/test; não existe no artefato de produção). Autoriza o teste local para que a LÓGICA DE DOMÍNIO (carteira, pagamento, gás, benefícios) seja provada.
 * Não representa o comportamento de autorização do produto: este é {@link ServerAuthorization#DENY_ALL}, provado em {@link ServerAuthorizationBoundaryTest}.
 * Registra as consultas, para que um teste de domínio não passe sem que a autorização tenha sido de fato exigida.
 */
final class FakeServerAuthorizer implements ServerAuthorizer {
    private final List<ServerOperation> calls = Collections.synchronizedList(new ArrayList<>());
    private final Set<ServerOperation> denied = EnumSet.noneOf(ServerOperation.class);

    @Override public void require(ServerOperation operation) {
        calls.add(operation);
        if (operation == null || denied.contains(operation)) {
            throw new AccessDeniedException(ServerAuthorization.REQUIRED + ": " + (operation == null ? "unknown" : operation.wireName()));
        }
    }

    List<ServerOperation> calls() {
        synchronized (calls) { return List.copyOf(calls); }
    }

    FakeServerAuthorizer deny(ServerOperation operation) {
        denied.add(operation);
        return this;
    }
}
