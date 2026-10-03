package panel.auth;

import java.time.Instant;
import panel.user.User;

/** Usuário autenticado no aplicativo. Não concede acesso administrativo por si só. */
public record UserSession(User user, Instant loggedInAt, java.util.UUID id) {
    public UserSession(User user, Instant loggedInAt) { this(user, loggedInAt, java.util.UUID.randomUUID()); }
}
