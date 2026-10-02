package panel.security;

import panel.user.User;

/** Barreira de serviço para operações administrativas: exige usuário ADMIN logado e AdminSession válida. */
public interface AdminGate {
    /** Devolve o administrador atuante ou lança AccessDeniedException. */
    User requireAdmin();
}
