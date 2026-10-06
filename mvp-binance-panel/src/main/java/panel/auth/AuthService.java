package panel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import panel.localservice.AuthorityGateway;
import panel.security.Role;
import panel.user.User;
import panel.user.UserStatus;

/**
 * Login pelo SERVIÇO local (a autoridade). O painel NÃO verifica senha, não lê conta, não aplica limite de tentativas e não decide papel: envia o que o usuário
 * digitou e apresenta o que o serviço respondeu. {@link SessionManager} guarda só a REPRESENTAÇÃO da sessão (usuário/papel para renderizar a interface); esconder
 * um botão não é autorização. Não existe caminho para autenticar contra o banco legado nem flag que o reabra.
 */
public class AuthService {
    public enum Failure { INVALID_CREDENTIALS, ACCOUNT_DISABLED, RATE_LIMITED }

    public static class LoginException extends RuntimeException {
        public final Failure failure;
        public final Duration retryAfter;

        public LoginException(Failure failure, Duration retryAfter) {
            super(failure.name());
            this.failure = failure;
            this.retryAfter = retryAfter;
        }
    }

    private final AuthorityGateway gateway;
    private final SessionManager sessions;
    private final Clock clock;

    public AuthService(AuthorityGateway gateway, SessionManager sessions, Clock clock) {
        this.gateway = gateway;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** O painel não cria o primeiro administrador: contas vêm da autoridade (migrada). Sem autoridade preparada o login responde indisponível. */
    public boolean firstRun() {
        return false;
    }

    public User login(String identifier, char[] password) {
        char[] pw = password.clone();
        AuthorityGateway.Reply r = gateway.login(identifier == null ? "" : identifier.trim(), password);
        if (r.serviceUnavailable() && gateway.ensureService()) { // o serviço do próprio bundle ainda não estava de pé: inicia e tenta UMA vez
            r = gateway.login(identifier == null ? "" : identifier.trim(), pw);
        }
        java.util.Arrays.fill(pw, '\0');
        if (r.ok()) {
            User u = toUser(r.result());
            sessions.login(u, clock.instant());
            return u;
        }
        switch (r.code()) {
            case "INVALID_CREDENTIALS" -> throw new LoginException(Failure.INVALID_CREDENTIALS, null);
            case "RATE_LIMITED" -> throw new LoginException(Failure.RATE_LIMITED, Duration.ofSeconds(Math.max(1, r.result() == null ? 30 : r.result().path("retryAfterSec").asLong(30))));
            default -> throw new IllegalStateException("authority unavailable"); // não iniciado, sem autoridade, não confiável, identidade não verificada…: nunca "senha errada"
        }
    }

    /** Encerra a sessão no serviço (revoga lá) e esquece a representação local. */
    public void logout() {
        try {
            gateway.logout();
        } finally {
            gateway.forgetSession();
            sessions.logout();
        }
    }

    /** Relê a sessão no serviço (p. ex. depois de trocar a senha obrigatória); vazio se o serviço já não a reconhece. */
    public Optional<User> refreshUser() {
        AuthorityGateway.Reply r = gateway.sessionStatus();
        if (!r.ok()) {
            return Optional.empty();
        }
        User u = toUser(r.result());
        sessions.updateUser(u);
        return Optional.of(u);
    }

    /** Apresentação: o que o serviço devolveu. O verificador de senha nunca existe aqui. */
    public static User toUser(JsonNode r) {
        String phone = r.path("phone").asText("");
        return new User(r.path("userId").asLong(0), r.path("username").asText(""), r.path("email").asText(""), "", "ADMIN".equals(r.path("role").asText()) ? Role.ADMIN : Role.USER,
                UserStatus.ACTIVE, phone.isEmpty() ? null : phone, r.path("emailVerified").asBoolean(false), r.path("phoneVerified").asBoolean(false), r.path("mustChangePassword").asBoolean(false),
                Instant.ofEpochMilli(r.path("createdAtMs").asLong(0)), Instant.ofEpochMilli(r.path("createdAtMs").asLong(0)),
                r.path("lastLoginAtMs").asLong(0) == 0 ? null : Instant.ofEpochMilli(r.path("lastLoginAtMs").asLong()));
    }
}
