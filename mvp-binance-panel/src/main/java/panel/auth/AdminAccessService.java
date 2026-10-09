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
 * serviço (expirada, ociosa, revogada, serviço reiniciado) encerra a representação local. Esconder um botão não é autorização: operações sensíveis sem endpoint
 * do serviço ficam indisponíveis por ServerAuthorization, independentemente deste cache.
 */
public class AdminAccessService implements AdminGate {
    private final SessionManager sessions;
    private final AuthorityGateway gateway;
    private final AuthService auth;
    private final TrustedDeviceService devices;
    private final Clock clock;
    private volatile TwoFactorFlow active;
    private volatile long lastActivityMs = System.currentTimeMillis();
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
        devices.onChanged = scope -> { try { refresh(scope); } catch (RuntimeException ignored) { } };
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
    void sessionLost(AuthService.SessionScope scope) { scope.invalidate(); }

    /** Relê o estado no serviço e sincroniza a representação. Vazio = sem sessão válida (a representação já foi encerrada). */
    public Optional<JsonNode> refresh() { return refresh(auth.captureSession()); }

    private Optional<JsonNode> refresh(AuthService.SessionScope scope) {
        AuthorityGateway.Reply r = scope.call(AuthorityGateway::sessionStatus);
        if (!r.ok()) {
            if (!r.code().equals("STALE_AUTH_OPERATION")) sessionLost(scope);
            return Optional.empty();
        }
        boolean applied = scope.present(() -> {
            apply(r.result(), AuthMethod.TWO_FACTOR);
            secondFactorConfigured = r.result().path("secondFactorConfigured").asBoolean(false);
        });
        return applied ? Optional.of(r.result()) : Optional.empty();
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

    public AccessDecision evaluate() { return evaluate(auth.captureSession()); }

    public AccessDecision evaluate(AuthService.SessionScope scope) {
        if (sessions.user().isEmpty()) {
            return AccessDecision.SESSION_EXPIRED;
        }
        Optional<JsonNode> st = refresh(scope);
        if (st.isEmpty()) {
            return AccessDecision.SESSION_EXPIRED;
        }
        if (!"ADMIN".equals(st.get().path("role").asText())) {
            return AccessDecision.FORBIDDEN_NOT_ADMIN;
        }
        return st.get().path("elevated").asBoolean(false) ? AccessDecision.ALREADY_AUTHORIZED : AccessDecision.REQUIRES_2FA;
    }

    /** Compatibilidade da UI: confiança do dispositivo nunca substitui MFA recente na elevação. */
    public boolean tryTrustedDevice() { return tryTrustedDevice(auth.captureSession()); }

    public boolean tryTrustedDevice(AuthService.SessionScope scope) {
        if (sessions.user().isEmpty()) {
            return false;
        }
        Optional<JsonNode> st = refresh(scope);
        if (st.isEmpty() || !"ADMIN".equals(st.get().path("role").asText()) || st.get().path("elevated").asBoolean(false)) {
            return false;
        }
        // contato verificado e dispositivo confiável NÃO são MFA: sem MFA recente o serviço negaria (e auditaria) à toa; a verificação de 2º fator é o único caminho
        if (!st.get().path("mfaRecent").asBoolean(false)) {
            return false;
        }
        AuthorityGateway.Reply r = scope.call(AuthorityGateway::adminElevation);
        if (!r.ok()) {
            if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) {
                sessionLost(scope);
            }
            return false;
        }
        return scope.present(() -> { sessions.revokeAdmin(); apply(r.result(), AuthMethod.TWO_FACTOR); }) && hasValidAdminSession();
    }

    public TwoFactorFlow startTwoFactor() { return startTwoFactor(auth.captureSession()); }

    public TwoFactorFlow startTwoFactor(AuthService.SessionScope scope) {
        if (evaluate(scope) != AccessDecision.REQUIRES_2FA) throw new AccessDeniedException("Two-factor is not applicable");
        TwoFactorFlow flow = new TwoFactorFlow(scope, this, clock);
        if (!scope.present(() -> { cancelChallenge(); active = flow; })) {
            flow.cancel();
            throw new AccessDeniedException("Session changed");
        }
        return flow;
    }

    /** Elevation is always decided by the Service; an old flow cannot publish it for a newer session. */
    boolean completed(AuthService.SessionScope scope) {
        AuthorityGateway.Reply r = scope.call(AuthorityGateway::adminElevation);
        if (r.ok()) return scope.present(() -> { sessions.revokeAdmin(); apply(r.result(), AuthMethod.TWO_FACTOR); });
        if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) sessionLost(scope);
        return false;
    }

    void trustCurrent(AuthService.SessionScope scope) { devices.trustCurrent(scope); }

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

    /** Atividade renova somente o idle da sessão comum. Nunca solicita nem estende elevação. */
    public void touch() {
        lastActivityMs = System.currentTimeMillis();
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

    public void close() {
        cancelChallenge();
        keepalive.shutdownNow();
    }

    @Override
    public User requireAdmin() { return requireAdmin(auth.captureSession()); }

    public User requireAdmin(AuthService.SessionScope scope) {
        var status = refresh(scope);
        if (status.isEmpty() || !"ADMIN".equals(status.get().path("role").asText())
                || !status.get().path("elevated").asBoolean(false)
                || !status.get().path("mfaRecent").asBoolean(false)) {
            throw new AccessDeniedException("Administrator session required");
        }
        java.util.concurrent.atomic.AtomicReference<User> user = new java.util.concurrent.atomic.AtomicReference<>();
        if (!scope.present(() -> user.set(sessions.user().orElseThrow().user()))) throw new AccessDeniedException("Session changed");
        return user.get();
    }
}
