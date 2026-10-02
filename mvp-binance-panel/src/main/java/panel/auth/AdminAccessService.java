package panel.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import panel.security.AccessDeniedException;
import panel.security.AdminGate;
import panel.security.AuditEvent;
import panel.security.SecurityAuditService;
import panel.security.SecurityConfig;
import panel.user.User;

/**
 * Política da área Research/Admin: usuário autenticado + role ADMIN + (IPv6 confiável OU e-mail+SMS).
 * IPv6 é apenas sinal de confiança; nunca concede role nem substitui login.
 */
public class AdminAccessService implements AdminGate {
    private final SessionManager sessions;
    private final SecurityConfig config;
    private final NetworkIdentityProvider network;
    private final OtpService otp;
    private final EmailOtpProvider emailProvider;
    private final SmsOtpProvider smsProvider;
    private final SecurityAuditService audit;
    private final Clock clock;

    public AdminAccessService(SessionManager sessions, SecurityConfig config, NetworkIdentityProvider network, OtpService otp,
                              EmailOtpProvider emailProvider, SmsOtpProvider smsProvider, SecurityAuditService audit, Clock clock) {
        this.sessions = sessions;
        this.config = config;
        this.network = network;
        this.otp = otp;
        this.emailProvider = emailProvider;
        this.smsProvider = smsProvider;
        this.audit = audit;
        this.clock = clock;
    }

    public boolean twoFactorConfigured() {
        return emailProvider.configured() && smsProvider.configured();
    }

    public boolean trustedNetwork() {
        String trusted = config.trustedIpv6();
        if (trusted == null) {
            return false;
        }
        Optional<String> want = Ipv6.normalize(trusted);
        if (want.isEmpty()) {
            return false;
        }
        Set<String> local = network.globalIpv6Addresses();
        return local.contains(want.get());
    }

    public AccessDecision evaluate() {
        Optional<UserSession> us = sessions.user();
        if (us.isEmpty()) {
            return AccessDecision.SESSION_EXPIRED;
        }
        if (!us.get().user().admin() || !us.get().user().active()) {
            return AccessDecision.FORBIDDEN_NOT_ADMIN;
        }
        if (hasValidAdminSession()) {
            return AccessDecision.ALREADY_AUTHORIZED;
        }
        return trustedNetwork() ? AccessDecision.AUTHORIZED_TRUSTED_NETWORK : AccessDecision.REQUIRES_2FA;
    }

    /** Concede AdminSession por rede confiável. Reavalia tudo; falha se não for admin logado em rede confiável. */
    public AdminSession grantTrustedNetwork() {
        AccessDecision d = evaluate();
        if (d == AccessDecision.ALREADY_AUTHORIZED) {
            return sessions.admin().orElseThrow();
        }
        if (d != AccessDecision.AUTHORIZED_TRUSTED_NETWORK) {
            throw new AccessDeniedException("Trusted network access is not available.");
        }
        AdminSession s = newSession(AuthMethod.TRUSTED_IPV6);
        sessions.grantAdmin(s);
        audit.record(AuditEvent.ADMIN_ACCESS_TRUSTED_IPV6, actor(), "");
        return s;
    }

    public TwoFactorFlow startTwoFactor() {
        AccessDecision d = evaluate();
        if (d != AccessDecision.REQUIRES_2FA && d != AccessDecision.AUTHORIZED_TRUSTED_NETWORK) {
            throw new AccessDeniedException("Two-factor is not applicable.");
        }
        if (!twoFactorConfigured()) {
            throw new TwoFactorNotConfiguredException();
        }
        User u = sessions.user().orElseThrow().user();
        if (u.email() == null || u.phone() == null) {
            throw new IllegalStateException("Admin email and phone are required for two-factor.");
        }
        audit.record(AuditEvent.ADMIN_2FA_STARTED, u.username(), "");
        return new TwoFactorFlow(u, otp, emailProvider, smsProvider, this);
    }

    void twoFactorCompleted(TwoFactorFlow flow, User user) {
        if (!flow.bothVerified()) {
            return;
        }
        sessions.grantAdmin(newSession(AuthMethod.TWO_FACTOR));
        audit.record(AuditEvent.ADMIN_2FA_SUCCESS, user.username(), "");
    }

    void twoFactorFailed(User user, String detail) {
        audit.record(AuditEvent.ADMIN_2FA_FAILED, user.username(), detail);
    }

    public boolean hasValidAdminSession() {
        Optional<UserSession> us = sessions.user();
        Optional<AdminSession> as = sessions.admin();
        return us.isPresent() && us.get().user().admin() && us.get().user().active() && as.isPresent() && as.get().validAt(clock.instant());
    }

    /** Verifica e, se expirada, revoga e audita. Devolve true se a sessão administrativa expirou agora. */
    public boolean expireIfNeeded() {
        Optional<AdminSession> as = sessions.admin();
        if (as.isPresent() && !as.get().validAt(clock.instant())) {
            sessions.revokeAdmin();
            audit.record(AuditEvent.ADMIN_SESSION_EXPIRED, actor(), "");
            return true;
        }
        return false;
    }

    public void touch() {
        sessions.admin().ifPresent(s -> {
            if (s.validAt(clock.instant())) {
                s.touch(clock.instant());
            }
        });
    }

    public Optional<AdminSession> adminSession() {
        return sessions.admin().filter(s -> s.validAt(clock.instant()));
    }

    public void noteDenied(String detail) {
        audit.record(AuditEvent.ADMIN_ACCESS_DENIED, actor(), detail);
    }

    @Override
    public User requireAdmin() {
        Optional<UserSession> us = sessions.user();
        if (us.isEmpty()) {
            throw new AccessDeniedException("Session expired. Please sign in again.");
        }
        if (!us.get().user().admin() || !us.get().user().active()) {
            noteDenied("service layer: not admin");
            throw new AccessDeniedException("Access restricted to administrators.");
        }
        if (expireIfNeeded() || !hasValidAdminSession()) {
            throw new AccessDeniedException("Administrator session required.");
        }
        return us.get().user();
    }

    private AdminSession newSession(AuthMethod m) {
        return new AdminSession(clock.instant(), m, Duration.ofMinutes(config.sessionTimeoutMinutes()));
    }

    private String actor() {
        return sessions.user().map(s -> s.user().username()).orElse("-");
    }
}
