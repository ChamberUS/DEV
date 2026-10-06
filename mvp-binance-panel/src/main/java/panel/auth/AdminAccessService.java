package panel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import panel.localservice.AuthorityGateway;
import panel.security.AccessDeniedException;
import panel.security.AdminGate;
import panel.user.User;

/**
 * Acesso administrativo pelo SERVIÇO: elevação, segundo fator e dispositivo confiável são decididos e guardados lá. Este objeto só mantém a REPRESENTAÇÃO
 * (cache de apresentação) do que o serviço respondeu e a mostra à interface; um cache velho nunca concede nada que o serviço negue, e perder a sessão no
 * serviço (expirada, ociosa, revogada, serviço reiniciado) encerra a representação local. Esconder um botão não é autorização: toda operação protegida é
 * decidida pelo serviço (as que ainda vivem no painel seguem barradas pela interface até migrarem para o serviço).
 */
public class AdminAccessService implements AdminGate {
    private final SessionManager sessions;
    private final AuthorityGateway gateway;
    private final AuthService auth;
    private final TrustedDeviceService devices;
    private final Clock clock;
    private volatile TwoFactorFlow active;
    private volatile long lastActivityMs = System.currentTimeMillis();
    private volatile long lastPingMs;
    private volatile boolean secondFactorConfigured;
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "authority-keepalive");
        t.setDaemon(true);
        return t;
    });

    public AdminAccessService(SessionManager sessions, AuthorityGateway gateway, AuthService auth, TrustedDeviceService devices, Clock clock) {
        this.sessions = sessions;
        this.gateway = gateway;
        this.auth = auth;
        this.devices = devices;
        this.clock = clock;
        devices.onChanged = this::refreshQuietly;
        sessions.onLogout(this::cancelChallenge);
        // com o usuário ATIVO a sessão no serviço é mantida viva e a representação sincronizada; ocioso (sem entrada por 60 s) deixa o prazo ocioso do serviço correr
        keepalive.scheduleWithFixedDelay(this::keepaliveTick, 20, 20, TimeUnit.SECONDS);
    }

    private void keepaliveTick() {
        try {
            if (sessions.user().isPresent() && System.currentTimeMillis() - lastActivityMs < 60_000) {
                refresh();
            }
        } catch (RuntimeException ignored) {
            // próxima volta
        }
    }

    /** O serviço não reconhece mais a sessão (ou ficou indisponível): a representação local acaba e a interface volta ao login. */
    void sessionLost() {
        gateway.forgetSession();
        sessions.logout();
    }

    /** Relê o estado no serviço e sincroniza a representação. Vazio = sem sessão válida (a representação já foi encerrada). */
    public Optional<JsonNode> refresh() {
        if (sessions.user().isEmpty()) {
            return Optional.empty();
        }
        AuthorityGateway.Reply r = gateway.sessionStatus();
        if (!r.ok()) {
            sessionLost(); // fecha: sem o serviço não há sessão
            return Optional.empty();
        }
        apply(r.result(), AuthMethod.TWO_FACTOR);
        secondFactorConfigured = r.result().path("secondFactorConfigured").asBoolean(false);
        return Optional.of(r.result());
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException ignored) {
            // nada
        }
    }

    private void apply(JsonNode st, AuthMethod method) {
        sessions.updateUser(AuthService.toUser(st));
        long left = st.path("elevatedForSec").asLong(0);
        if (st.path("elevated").asBoolean(false) && left > 0) {
            var cur = sessions.admin();
            Instant now = clock.instant();
            AuthMethod m = cur.map(AdminSession::method).orElse(method);
            sessions.grantAdmin(new AdminSession(now, m, Duration.ofSeconds(left))); // expira quando o SERVIÇO diz que expira
        } else {
            sessions.revokeAdmin();
        }
    }

    /** Se o serviço declarou os provedores de 2º fator configurados (a configuração e os segredos vivem no serviço). */
    public boolean twoFactorConfigured() {
        return secondFactorConfigured;
    }

    public AccessDecision evaluate() {
        if (sessions.user().isEmpty()) {
            return AccessDecision.SESSION_EXPIRED;
        }
        Optional<JsonNode> st = refresh();
        if (st.isEmpty()) {
            return AccessDecision.SESSION_EXPIRED;
        }
        if (!"ADMIN".equals(st.get().path("role").asText())) {
            return AccessDecision.FORBIDDEN_NOT_ADMIN;
        }
        return st.get().path("elevated").asBoolean(false) ? AccessDecision.ALREADY_AUTHORIZED : AccessDecision.REQUIRES_2FA;
    }

    /** Pede ao SERVIÇO a elevação por dispositivo confiável (o serviço decide se há dispositivo válido; sem ele exige o segundo fator). */
    public boolean tryTrustedDevice() {
        if (evaluate() != AccessDecision.REQUIRES_2FA) {
            return false;
        }
        AuthorityGateway.Reply r = gateway.adminElevation();
        if (!r.ok()) {
            if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) {
                sessionLost();
            }
            return false;
        }
        sessions.revokeAdmin();
        apply(r.result(), AuthMethod.TRUSTED_DEVICE);
        return hasValidAdminSession();
    }

    public TwoFactorFlow startTwoFactor() {
        if (evaluate() != AccessDecision.REQUIRES_2FA) {
            throw new AccessDeniedException("Two-factor is not applicable");
        }
        cancelChallenge();
        TwoFactorFlow flow = new TwoFactorFlow(gateway, this, clock);
        active = flow;
        return flow;
    }

    /** O 2º fator foi concluído NO SERVIÇO: pede a elevação (que ele concede ou nega). */
    void completed() {
        AuthorityGateway.Reply r = gateway.adminElevation();
        if (r.ok()) {
            sessions.revokeAdmin();
            apply(r.result(), AuthMethod.TWO_FACTOR);
        } else if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) {
            sessionLost();
        }
    }

    void trustCurrent() {
        devices.trustCurrent();
    }

    public boolean hasValidAdminSession() {
        return sessions.user().filter(s -> s.user().admin() && s.user().active()).isPresent() && sessions.admin().filter(s -> s.validAt(clock.instant())).isPresent();
    }

    public boolean expireIfNeeded() {
        if (sessions.admin().filter(s -> !s.validAt(clock.instant())).isPresent()) {
            sessions.revokeAdmin();
            return true;
        }
        return false;
    }

    /** Atividade do usuário (mouse/teclado): mantém a sessão e a elevação vivas no serviço (a elevação só desliza se ainda válida; lapsada exige o 2º fator de novo). */
    public void touch() {
        long now = System.currentTimeMillis();
        lastActivityMs = now;
        if (hasValidAdminSession() && now - lastPingMs > 60_000) {
            lastPingMs = now;
            keepalive.execute(() -> {
                try {
                    AuthorityGateway.Reply r = gateway.adminElevation();
                    if (r.ok()) {
                        apply(r.result(), AuthMethod.TWO_FACTOR);
                    } else if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) {
                        sessionLost();
                    }
                } catch (RuntimeException ignored) {
                    // nada
                }
            });
        }
    }

    public Optional<AdminSession> adminSession() {
        return sessions.admin().filter(s -> s.validAt(clock.instant()));
    }

    /** Troca/reset de credencial: o serviço já encerrou a elevação; aqui só a representação e o desafio em curso. */
    public void credentialsChanged(long userId) {
        sessions.revokeAdminFor(userId);
        cancelChallenge();
    }

    public void cancelChallenge() {
        TwoFactorFlow f = active;
        if (f != null) {
            f.cancel();
            active = null;
        }
    }

    /** O painel não fabrica evento de segurança: a auditoria confiável é a do serviço. */
    public void noteDenied(String detail) {
        // intencionalmente vazio
    }

    @Override
    public User requireAdmin() {
        if (expireIfNeeded() || !hasValidAdminSession()) {
            throw new AccessDeniedException("Administrator session required");
        }
        return sessions.user().orElseThrow().user();
    }
}
