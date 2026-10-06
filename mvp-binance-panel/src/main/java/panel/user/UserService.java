package panel.user;

import java.util.List;
import panel.auth.AuthService;
import panel.localservice.AuthorityGateway;
import panel.security.AccessDeniedException;
import panel.security.Role;

/**
 * Regras de usuário na interface. A ÚNICA operação de conta que existe depois do cutover é a troca da PRÓPRIA senha, feita pelo SERVIÇO (exige a senha atual,
 * sobe a versão de credencial e revoga as outras sessões). Criar, listar, desabilitar, mudar papel, redefinir senha e mudar contato exigem operações da autoridade que
 * ainda não existem no serviço (e ficam bloqueadas durante a janela de segurança do cutover): aqui são recusadas com uma mensagem clara; nada toca o banco legado.
 */
public class UserService {
    public static final String UNAVAILABLE = "Account administration is unavailable after the authority cutover (planned for a later phase).";
    private final AuthorityGateway gateway;
    private final AuthService auth;
    private final panel.auth.SessionManager sessions;

    public UserService(AuthorityGateway gateway, AuthService auth, panel.auth.SessionManager sessions) {
        this.gateway = gateway;
        this.auth = auth;
        this.sessions = sessions;
    }

    /** Chamado depois de trocar a senha da conta (a representação da elevação é encerrada). */
    public java.util.function.Consumer<Long> onCredentialsChanged = id -> { };
    public Runnable onContactsChanged = () -> { };

    public User createInitialAdmin(String username, String email, char[] password, String phone) {
        throw new AccessDeniedException("Initial setup is no longer available.");
    }

    public User createUser(String username, String email, char[] temporaryPassword, String phone, Role role) {
        throw new AccessDeniedException(UNAVAILABLE);
    }

    public List<User> listUsers() {
        throw new AccessDeniedException(UNAVAILABLE);
    }

    public void setStatus(long id, UserStatus status) {
        throw new AccessDeniedException(UNAVAILABLE);
    }

    public void changeRole(long id, Role role) {
        throw new AccessDeniedException(UNAVAILABLE);
    }

    public void resetPassword(long id, char[] temporaryPassword) {
        throw new AccessDeniedException(UNAVAILABLE);
    }

    public void changeOwnContact(long id, char[] password, String email, String phone) {
        throw new AccessDeniedException(UNAVAILABLE);
    }

    /** O próprio usuário troca a senha pelo serviço. Mensagens equivalentes às do fluxo anterior. */
    public void changeOwnPassword(long id, char[] current, char[] next) {
        String username = sessions.user().map(s -> s.user().username()).orElse(null);
        String policy = PasswordPolicy.check(next, username);
        if (policy != null) {
            java.util.Arrays.fill(current, '\0');
            java.util.Arrays.fill(next, '\0');
            throw new IllegalArgumentException(policy);
        }
        AuthorityGateway.Reply r = gateway.changePassword(current, next);
        switch (r.code()) {
            case "OK" -> {
                auth.refreshUser();
                onCredentialsChanged.accept(id);
            }
            case "INVALID_CREDENTIALS" -> throw new IllegalArgumentException("Current password is incorrect.");
            case "WEAK_PASSWORD" -> throw new IllegalArgumentException("New password must differ from the current one and meet the password policy.");
            case "RATE_LIMITED" -> throw new IllegalArgumentException("Wait before retrying current password.");
            case "FROZEN" -> throw new AccessDeniedException("Password changes are temporarily unavailable until the security cutover validation is finished.");
            case "AUTH_REQUIRED" -> throw new AccessDeniedException("Session changed");
            default -> throw new IllegalStateException("Authority unavailable.");
        }
    }
}
