package byx.service.auth;

import byx.service.PrivateCapabilityGate;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * AUTORIDADE de autenticação e sessão. O painel não decide nada: userId, username, role, admin, mfa e estado de sessão que a UI mostra são só
 * exibição. Tudo é decidido AQUI a cada uso: sessão (token opaco, ligada ao peer, com validade absoluta e por inatividade) → conta na
 * autoridade (existe, habilitada, versão de credencial igual à da sessão, papel ATUAL) → segundo fator recente / elevação (temporários) →
 * posse → capacidade privada (gate). Qualquer falha de integridade da autoridade revoga todas as sessões e responde AUTHORITY_UNAVAILABLE.
 */
public final class AuthService {
    public enum Code {
        OK, INVALID_CREDENTIALS, RATE_LIMITED, AUTH_REQUIRED, DENIED, SECOND_FACTOR_NOT_CONFIGURED, CHALLENGE_INVALID, CHALLENGE_EXPIRED, TOO_MANY_ATTEMPTS,
        COOLDOWN, TOO_MANY_SENDS, AUTHORITY_UNAVAILABLE, WEAK_PASSWORD, ELEVATION_REQUIRES_MFA, FROZEN
    }

    /** data NUNCA carrega senha, OTP, hash ou token além do campo "session" entregue no login. */
    public record Result(Code code, Map<String, Object> data) {
        static Result of(Code c) {
            return new Result(c, Map.of());
        }

        public boolean ok() {
            return code == Code.OK;
        }
    }

    public record Guarded<T>(Code code, String detail, T value) {
    }

    private record Valid(Session session, Account account) {
    }

    private final AuthorityStore authority;
    private final AuthorityAdmin admin;
    private final PasswordVerifier passwords;
    private final AuthRateLimiter limiter;
    private final SessionStore sessions;
    private final SecondFactorProvider second;
    private final AuthPolicy policy;
    private final AuthAudit audit;
    private final Clock clock;
    private final OtpChallenges otp;

    public AuthService(AuthorityStore authority, AuthorityAdmin admin, PasswordVerifier passwords, AuthRateLimiter limiter, SecondFactorProvider second, AuthPolicy policy,
            AuthAudit audit, Clock clock) {
        this.authority = authority;
        this.admin = admin;
        this.passwords = passwords;
        this.limiter = limiter;
        this.second = second;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
        this.sessions = new SessionStore(clock);
        this.otp = new OtpChallenges(clock);
    }

    public AuthAudit audit() {
        return audit;
    }

    public AuthPolicy policy() {
        return policy;
    }

    public int activeSessions() {
        return sessions.size();
    }

    // ---- login ---------------------------------------------------------------------------------------------------------------------

    public Result login(long peerKey, String username, char[] password) {
        try {
            String name = AuthorityAdmin.normalize(username); // usuário OU e-mail digitado (mesmo caminho para ambos)
            Optional<java.time.Duration> blocked = limiter.blockedFor("login", name);
            if (blocked.isPresent()) {
                audit.record("LOGIN_RATE_LIMITED", "-");
                return new Result(Code.RATE_LIMITED, Map.of("retryAfterSec", blocked.get().toSeconds() + 1));
            }
            AuthorityState st;
            try {
                st = authority.current();
            } catch (AuthorityException e) {
                failClosed();
                return Result.of(Code.AUTHORITY_UNAVAILABLE);
            }
            if (password == null || password.length == 0 || new String(password).getBytes(StandardCharsets.UTF_8).length > AuthLimits.PASSWORD_MAX_BYTES) {
                passwords.verifyDummy(new char[] {'x'});
                limiter.recordFailure("login", name);
                audit.record("LOGIN_FAILED", "-");
                return Result.of(Code.INVALID_CREDENTIALS);
            }
            Optional<Account> found = name.matches("[a-z0-9._@+-]{1,254}") ? st.byIdentifier(name) : Optional.empty();
            // conta inexistente, senha errada e conta desabilitada seguem o MESMO caminho (uma verificação Argon2 e a mesma resposta)
            boolean verified = found.isPresent() ? passwords.verify(password, found.get().passwordHash()) : passwords.verifyDummy(password);
            if (!verified || !found.get().enabled()) {
                limiter.recordFailure("login", name);
                audit.record("LOGIN_FAILED", found.map(Account::id).orElse("-"));
                return Result.of(Code.INVALID_CREDENTIALS);
            }
            Account a = found.get();
            limiter.recordSuccess("login", name);
            try {
                admin.recordLogin(a.id(), clock.millis());
            } catch (AuthorityException e) {
                failClosed();
                return Result.of(Code.AUTHORITY_UNAVAILABLE);
            }
            String[] created = sessions.create(a.id(), a.credentialVersion(), peerKey);
            if (created == null) {
                audit.record("LOGIN_DENIED_SESSION_LIMIT", a.id());
                return Result.of(Code.DENIED);
            }
            audit.record("LOGIN_OK", a.id());
            Session s = sessions.lookup(created[0], peerKey).session();
            Map<String, Object> d = new LinkedHashMap<>(view(a, s));
            d.put("session", created[0]);
            return new Result(Code.OK, d);
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    // ---- validação a cada uso (revalidação contra a AUTORIDADE) ------------------------------------------------------------------

    private void failClosed() {
        sessions.revokeAll();
        audit.record("AUTHORITY_UNTRUSTED", "-");
    }

    private Object validate(long peerKey, String token) {
        AuthorityState st;
        try {
            st = authority.current();
        } catch (AuthorityException e) {
            failClosed();
            return Result.of(Code.AUTHORITY_UNAVAILABLE);
        }
        SessionStore.Found f = sessions.lookup(token, peerKey);
        switch (f.result()) {
            case NOT_FOUND -> {
                return Result.of(Code.AUTH_REQUIRED);
            }
            case EXPIRED -> {
                audit.record("SESSION_EXPIRED", "-");
                return Result.of(Code.AUTH_REQUIRED);
            }
            case PEER_MISMATCH -> {
                audit.record("SESSION_PEER_MISMATCH", "-"); // mesma resposta de "token desconhecido": nada a aprender
                return Result.of(Code.AUTH_REQUIRED);
            }
            default -> {
            }
        }
        Session s = f.session();
        Optional<Account> acc = st.byId(s.accountId);
        if (acc.isEmpty() || !acc.get().enabled() || acc.get().credentialVersion() != s.credentialVersion) {
            audit.record("SESSION_REVOKED_AUTHORITY_CHANGED", s.accountId);
            otp.cancelSession(s.id);
            sessions.revoke(s);
            return Result.of(Code.AUTH_REQUIRED);
        }
        if (s.elevatedUntilMs > 0 && acc.get().role() != Role.ADMIN) {
            s.elevatedUntilMs = -1; // rebaixado: a elevação some na hora
        }
        s.lastSeenMs = clock.millis();
        return new Valid(s, acc.get());
    }

    private static Result error(Object v) {
        return (Result) v;
    }

    // ---- operações ---------------------------------------------------------------------------------------------------------------

    public Result sessionStatus(long peerKey, String token) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        return new Result(Code.OK, view(ok.account, ok.session));
    }

    public Result logout(long peerKey, String token) {
        SessionStore.Found f = sessions.lookup(token, peerKey);
        if (f.result() != SessionStore.Lookup.OK) {
            return Result.of(Code.AUTH_REQUIRED);
        }
        otp.cancelSession(f.session().id);
        sessions.revoke(f.session());
        audit.record("LOGOUT", f.session().accountId);
        return Result.of(Code.OK);
    }

    public Result beginSecondFactor(long peerKey, String token) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        if (!second.configured()) {
            return Result.of(Code.SECOND_FACTOR_NOT_CONFIGURED);
        }
        Optional<java.time.Duration> blocked = limiter.blockedFor("2fa", ok.account.id());
        if (blocked.isPresent()) {
            return new Result(Code.RATE_LIMITED, Map.of("retryAfterSec", blocked.get().toSeconds() + 1));
        }
        OtpChallenges.Issued issued;
        try {
            issued = otp.issue(ok.session.id, ok.account.id());
        } catch (OtpChallenges.Cooldown c) {
            return new Result("TOO_MANY_SENDS".equals(c.getMessage()) ? Code.TOO_MANY_SENDS : Code.COOLDOWN, Map.of("retryAfterSec", c.retryAfterMs / 1000 + 1));
        }
        try {
            second.deliver(ok.account, issued.code());
        } catch (SecondFactorProvider.DeliveryException e) {
            otp.cancelSession(ok.session.id);
            audit.record("OTP_DELIVERY_FAILED", ok.account.id());
            return Result.of(Code.DENIED);
        } finally {
            Arrays.fill(issued.code(), '\0');
        }
        audit.record("OTP_SENT", ok.account.id());
        long now = clock.millis();
        return new Result(Code.OK, Map.of("challenge", issued.challengeId(), "expiresInSec", Math.max(0, (issued.expiresAtMs() - now) / 1000),
                "resendAfterSec", AuthLimits.OTP_RESEND_COOLDOWN.toSeconds()));
    }

    public Result verifySecondFactor(long peerKey, String token, String challengeId, String code) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        Optional<java.time.Duration> blocked = limiter.blockedFor("2fa", ok.account.id());
        if (blocked.isPresent()) {
            return new Result(Code.RATE_LIMITED, Map.of("retryAfterSec", blocked.get().toSeconds() + 1));
        }
        switch (otp.verify(ok.session.id, ok.account.id(), challengeId, code)) {
            case OK -> {
                limiter.recordSuccess("2fa", ok.account.id());
                audit.record("OTP_OK", ok.account.id());
                if (second.smsRequired()) { // dois estágios: o e-mail sozinho NÃO conclui o segundo fator
                    ok.session.clearSecondFactorStage();
                    ok.session.emailOkAtMs = clock.millis();
                    Map<String, Object> d = new LinkedHashMap<>(view(ok.account, ok.session));
                    d.put("next", "SMS");
                    return new Result(Code.OK, d);
                }
                ok.session.mfaAtMs = clock.millis();
                return new Result(Code.OK, view(ok.account, ok.session));
            }
            case EXPIRED -> {
                audit.record("OTP_EXPIRED", ok.account.id());
                return Result.of(Code.CHALLENGE_EXPIRED);
            }
            case TOO_MANY_ATTEMPTS -> {
                limiter.recordFailure("2fa", ok.account.id());
                return Result.of(Code.TOO_MANY_ATTEMPTS);
            }
            case INVALID -> {
                limiter.recordFailure("2fa", ok.account.id());
                audit.record("OTP_FAILED", ok.account.id());
                return Result.of(Code.CHALLENGE_INVALID);
            }
            default -> {
                audit.record("OTP_NO_CHALLENGE", ok.account.id());
                return Result.of(Code.CHALLENGE_INVALID); // outro usuário, outra sessão, desafio antigo, reutilizado: indistinguíveis
            }
        }
    }

    /** Estágio 2: envia o SMS (só depois do e-mail verificado e dentro do fluxo de 10 min; envios e intervalo limitados). */
    public Result sendSecondFactorSms(long peerKey, String token) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        Session s = ok.session;
        long now = clock.millis();
        if (!second.configured() || !second.smsRequired()) {
            return Result.of(Code.SECOND_FACTOR_NOT_CONFIGURED);
        }
        if (s.emailOkAtMs < 0 || now - s.emailOkAtMs > AuthLimits.SECOND_FACTOR_FLOW.toMillis()) {
            s.clearSecondFactorStage();
            return Result.of(Code.CHALLENGE_EXPIRED);
        }
        Optional<java.time.Duration> blocked = limiter.blockedFor("2fa", ok.account.id());
        if (blocked.isPresent()) {
            return new Result(Code.RATE_LIMITED, Map.of("retryAfterSec", blocked.get().toSeconds() + 1));
        }
        if (s.smsSends >= AuthLimits.SMS_MAX_SENDS) {
            return Result.of(Code.TOO_MANY_SENDS);
        }
        if (s.smsSentAtMs >= 0 && now - s.smsSentAtMs < AuthLimits.OTP_RESEND_COOLDOWN.toMillis()) {
            return new Result(Code.COOLDOWN, Map.of("retryAfterSec", (AuthLimits.OTP_RESEND_COOLDOWN.toMillis() - (now - s.smsSentAtMs)) / 1000 + 1));
        }
        String id;
        try {
            id = second.startSms(ok.account);
        } catch (SecondFactorProvider.DeliveryException e) {
            audit.record("SMS_DELIVERY_FAILED", ok.account.id());
            return Result.of(Code.DENIED);
        }
        s.smsVerificationId = id;
        s.smsSentAtMs = now;
        s.smsSends++;
        audit.record("SMS_SENT", ok.account.id());
        return new Result(Code.OK, Map.of("expiresInSec", AuthLimits.SMS_TTL.toSeconds(), "resendAfterSec", AuthLimits.OTP_RESEND_COOLDOWN.toSeconds()));
    }

    /** Estágio 2: confere o código de SMS; sucesso conclui o segundo fator (mfaAt). Tentativas acumulam entre reenvios. */
    public Result verifySecondFactorSms(long peerKey, String token, String code) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        Session s = ok.session;
        long now = clock.millis();
        if (!second.configured() || !second.smsRequired()) {
            return Result.of(Code.SECOND_FACTOR_NOT_CONFIGURED);
        }
        Optional<java.time.Duration> blocked = limiter.blockedFor("2fa", ok.account.id());
        if (blocked.isPresent()) {
            return new Result(Code.RATE_LIMITED, Map.of("retryAfterSec", blocked.get().toSeconds() + 1));
        }
        String vid = s.smsVerificationId;
        if (vid == null || s.emailOkAtMs < 0) {
            return Result.of(Code.CHALLENGE_INVALID);
        }
        if (now - s.emailOkAtMs > AuthLimits.SECOND_FACTOR_FLOW.toMillis() || now - s.smsSentAtMs > AuthLimits.SMS_TTL.toMillis()) {
            s.clearSecondFactorStage();
            audit.record("SMS_EXPIRED", ok.account.id());
            return Result.of(Code.CHALLENGE_EXPIRED);
        }
        if (s.smsAttempts >= AuthLimits.SMS_MAX_ATTEMPTS) {
            limiter.recordFailure("2fa", ok.account.id());
            return Result.of(Code.TOO_MANY_ATTEMPTS);
        }
        s.smsAttempts++;
        boolean good;
        try {
            good = code != null && code.matches("[0-9]{4,10}") && second.checkSms(ok.account, vid, code);
        } catch (SecondFactorProvider.DeliveryException e) {
            audit.record("SMS_CHECK_FAILED", ok.account.id());
            return Result.of(Code.DENIED);
        }
        // a sessão pode ter sido revogada enquanto o provedor respondia: revalida antes de conceder qualquer coisa
        Object again = validate(peerKey, token);
        if (again instanceof Result r2) {
            return r2;
        }
        if (!good) {
            limiter.recordFailure("2fa", ok.account.id());
            audit.record("SMS_FAILED", ok.account.id());
            return Result.of(Code.CHALLENGE_INVALID);
        }
        limiter.recordSuccess("2fa", ok.account.id());
        s.clearSecondFactorStage();
        s.mfaAtMs = clock.millis();
        audit.record("SMS_OK", ok.account.id());
        return new Result(Code.OK, view(ok.account, s));
    }

    public Result adminElevation(long peerKey, String token) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        long now = clock.millis();
        if (ok.account.role() != Role.ADMIN) {
            audit.record("ELEVATION_DENIED", ok.account.id());
            return Result.of(Code.DENIED);
        }
        long window = elevationMs();
        long absoluteCap = ok.session.createdAtMs + AuthLimits.ABSOLUTE_TIMEOUT.toMillis();
        if (ok.session.elevatedUntilMs > now) { // elevação AINDA válida: atividade a estende (deslizante), nunca além da sessão absoluta; se lapsou, exige 2º fator ou dispositivo de novo
            ok.session.elevatedUntilMs = Math.min(now + window, absoluteCap);
            return new Result(Code.OK, view(ok.account, ok.session));
        }
        boolean mfaFresh = ok.session.mfaAtMs >= 0 && now - ok.session.mfaAtMs <= AuthLimits.RECENT_MFA_WINDOW.toMillis();
        if (!mfaFresh) {
            Optional<TrustedDevice> td = activeDevice(ok.account.id(), now);
            if (td.isEmpty()) {
                audit.record("ELEVATION_DENIED", ok.account.id());
                return Result.of(Code.ELEVATION_REQUIRES_MFA);
            }
            try {
                admin.touchDevice(td.get().id());
            } catch (AuthorityException e) {
                failClosed();
                return Result.of(Code.AUTHORITY_UNAVAILABLE);
            }
            audit.record("ELEVATION_TRUSTED_DEVICE", ok.account.id());
        }
        ok.session.elevatedUntilMs = Math.min(now + window, absoluteCap);
        audit.record("ELEVATION_GRANTED", ok.account.id());
        return new Result(Code.OK, view(ok.account, ok.session));
    }

    private long elevationMs() {
        try {
            return authority.current().providers().elevationMs();
        } catch (AuthorityException e) {
            return AuthLimits.ADMIN_ELEVATION_TIMEOUT.toMillis();
        }
    }

    private Optional<TrustedDevice> activeDevice(String accountId, long now) {
        try {
            return authority.current().devices().stream().filter(d -> d.accountId().equals(accountId) && d.activeAt(now)).findFirst();
        } catch (AuthorityException e) {
            return Optional.empty();
        }
    }

    // ---- dispositivos confiáveis (esta instalação): inscrever exige ADMIN elevado + 2º fator fresco (≤ 5 min) e é recusado durante a trava -------------

    public Result enrollTrustedDevice(long peerKey, String token) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        long now = clock.millis();
        if (ok.account.role() != Role.ADMIN || ok.session.elevatedUntilMs <= now || ok.session.mfaAtMs < 0 || now - ok.session.mfaAtMs > AuthLimits.TRUSTED_DEVICE_ENROLL_MFA_WINDOW.toMillis()) {
            audit.record("DEVICE_ENROLL_DENIED", ok.account.id());
            return Result.of(Code.DENIED);
        }
        try {
            TrustedDevice d = admin.enrollDevice(ok.account.id(), AuthLimits.TRUSTED_DEVICE_VALIDITY.toMillis());
            audit.record("DEVICE_ENROLLED", ok.account.id());
            return new Result(Code.OK, Map.of("device", d.id(), "expiresAtMs", d.expiresAtMs()));
        } catch (AuthorityException e) {
            if ("frozen".equals(e.code)) {
                audit.record("DEVICE_ENROLL_FROZEN", ok.account.id());
                return Result.of(Code.FROZEN);
            }
            failClosed();
            return Result.of(Code.AUTHORITY_UNAVAILABLE);
        }
    }

    public Result listTrustedDevices(long peerKey, String token) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        long now = clock.millis();
        if (ok.account.role() != Role.ADMIN || ok.session.elevatedUntilMs <= now) {
            return Result.of(Code.DENIED);
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (TrustedDevice d : authority.current().devices()) {
                if (d.accountId().equals(ok.account.id())) {
                    sb.append(sb.length() == 0 ? "" : ";").append(d.id()).append(',').append(d.status(now)).append(',').append(d.createdAtMs()).append(',').append(d.lastUsedAtMs()).append(',')
                            .append(d.expiresAtMs());
                }
            }
        } catch (AuthorityException e) {
            failClosed();
            return Result.of(Code.AUTHORITY_UNAVAILABLE);
        }
        return new Result(Code.OK, Map.of("devices", sb.toString()));
    }

    public Result revokeTrustedDevice(long peerKey, String token, String deviceId) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return r;
        }
        Valid ok = (Valid) v;
        long now = clock.millis();
        if (ok.account.role() != Role.ADMIN || ok.session.elevatedUntilMs <= now) {
            return Result.of(Code.DENIED);
        }
        try {
            admin.revokeDevice(ok.account.id(), deviceId);
        } catch (AuthorityException e) {
            failClosed();
            return Result.of(Code.AUTHORITY_UNAVAILABLE);
        }
        ok.session.elevatedUntilMs = -1; // como no fluxo legado: revogar encerra a elevação
        audit.record("DEVICE_REVOKED", ok.account.id());
        return Result.of(Code.OK);
    }

    /**
     * Troca de senha. Política: exige sessão válida E a senha atual; sobe a credentialVersion; invalida a elevação e o segundo fator recente da sessão
     * atual, cancela desafios OTP da conta e REVOGA todas as OUTRAS sessões. A sessão atual (comum) PERMANECE, mas precisará refazer o segundo fator
     * para qualquer operação que o exija.
     */
    public Result changePassword(long peerKey, String token, char[] current, char[] next) {
        try {
            Object v = validate(peerKey, token);
            if (v instanceof Result r) {
                return r;
            }
            Valid ok = (Valid) v;
            Optional<java.time.Duration> blocked = limiter.blockedFor("pwchange", ok.account.id());
            if (blocked.isPresent()) {
                return new Result(Code.RATE_LIMITED, Map.of("retryAfterSec", blocked.get().toSeconds() + 1));
            }
            if (current == null || !passwords.verify(current, ok.account.passwordHash())) {
                limiter.recordFailure("pwchange", ok.account.id());
                audit.record("PASSWORD_CHANGE_FAILED", ok.account.id());
                return Result.of(Code.INVALID_CREDENTIALS);
            }
            if (next == null || next.length < AuthLimits.PASSWORD_MIN_CHARS || new String(next).getBytes(StandardCharsets.UTF_8).length > AuthLimits.PASSWORD_MAX_BYTES
                    || Arrays.equals(next, current)) {
                return Result.of(Code.WEAK_PASSWORD);
            }
            limiter.recordSuccess("pwchange", ok.account.id());
            try {
                admin.changePassword(ok.account.id(), next);
                ok.session.credentialVersion = authority.current().byId(ok.account.id()).orElseThrow().credentialVersion();
            } catch (AuthorityException e) {
                if ("frozen".equals(e.code)) { // janela de segurança do cutover: sem mudança de senha até a validação explícita
                    audit.record("PASSWORD_CHANGE_FROZEN", ok.account.id());
                    return Result.of(Code.FROZEN);
                }
                failClosed();
                return Result.of(Code.AUTHORITY_UNAVAILABLE);
            } catch (RuntimeException e) {
                failClosed();
                return Result.of(Code.AUTHORITY_UNAVAILABLE);
            }
            ok.session.mfaAtMs = -1;
            ok.session.elevatedUntilMs = -1;
            otp.cancelAccount(ok.account.id());
            int others = sessions.revokeAllFor(ok.account.id(), ok.session);
            audit.record("PASSWORD_CHANGED", ok.account.id());
            return new Result(Code.OK, Map.of("otherSessionsRevoked", others));
        } finally {
            if (current != null) {
                Arrays.fill(current, '\0');
            }
            if (next != null) {
                Arrays.fill(next, '\0');
            }
        }
    }

    // ---- autorização ---------------------------------------------------------------------------------------------------------------

    private Code decide(AuthPolicy.Rule rule, Valid v, String ownerAccountId) {
        long now = clock.millis();
        if (rule == null || !v.account.role().atLeast(rule.minRole())) {
            return Code.DENIED;
        }
        if (rule.recentMfa() && (v.session.mfaAtMs < 0 || now - v.session.mfaAtMs > AuthLimits.RECENT_MFA_WINDOW.toMillis())) {
            return Code.DENIED;
        }
        if (rule.elevation() && (v.account.role() != Role.ADMIN || v.session.elevatedUntilMs <= now)) {
            return Code.DENIED;
        }
        if (rule.ownership() && !v.account.id().equals(ownerAccountId)) {
            return Code.DENIED;
        }
        if (rule.privateCapability() && !PrivateCapabilityGate.PRIVATE_CAPABILITIES_ALLOWED) {
            return Code.DENIED;
        }
        return Code.OK;
    }

    /** Decisão de uma operação (revalida sessão e autoridade). op fora da tabela = DENIED. */
    public Guarded<Void> authorize(long peerKey, String token, String op, String ownerAccountId) {
        Object v = validate(peerKey, token);
        if (v instanceof Result r) {
            return new Guarded<>(r.code(), "session", null);
        }
        Code c = decide(policy.rule(op), (Valid) v, ownerAccountId);
        if (c != Code.OK) {
            audit.record("AUTH_DENIED", ((Valid) v).account.id());
        }
        return new Guarded<>(c, c == Code.OK ? "ok" : "policy", null);
    }

    /**
     * Executa trabalho protegido: autoriza ANTES e reautoriza DEPOIS. Se a sessão foi encerrada, rebaixada, desabilitada ou teve a credencial trocada
     * durante a execução, o resultado é DESCARTADO (nenhum privilégio "ressuscita" por resposta tardia).
     */
    public <T> Guarded<T> guarded(long peerKey, String token, String op, String ownerAccountId, Callable<T> work) {
        Guarded<Void> pre = authorize(peerKey, token, op, ownerAccountId);
        if (pre.code() != Code.OK) {
            return new Guarded<>(pre.code(), pre.detail(), null);
        }
        T value;
        try {
            value = work.call();
        } catch (Exception e) {
            return new Guarded<>(Code.DENIED, "work_failed", null);
        }
        Guarded<Void> post = authorize(peerKey, token, op, ownerAccountId);
        if (post.code() != Code.OK) {
            audit.record("LATE_RESULT_DISCARDED", "-");
            return new Guarded<>(post.code() == Code.OK ? Code.DENIED : post.code(), "revoked_during_execution", null);
        }
        return new Guarded<>(Code.OK, "ok", value);
    }

    /** Depois de uma mudança de autoridade (rebaixar, desabilitar, remover, trocar credencial): revoga o que deixou de valer, agora. */
    public void authorityChanged() {
        AuthorityState st;
        try {
            st = authority.current();
        } catch (AuthorityException e) {
            failClosed();
            return;
        }
        // varre as sessões vivas (sem expor o mapa): a revalidação por uso é a garantia; isto só antecipa a revogação
        sessions.revokeWhere(s -> {
            Optional<Account> a = st.byId(s.accountId);
            return a.isEmpty() || !a.get().enabled() || a.get().credentialVersion() != s.credentialVersion;
        });
    }

    private Map<String, Object> view(Account a, Session s) {
        long now = clock.millis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", a.username()); // exibição apenas
        m.put("role", a.role().name()); // exibição apenas: a decisão é sempre refeita aqui
        m.put("mfaRecent", s.mfaAtMs >= 0 && now - s.mfaAtMs <= AuthLimits.RECENT_MFA_WINDOW.toMillis());
        m.put("elevated", s.elevatedUntilMs > now && a.role() == Role.ADMIN);
        m.put("elevatedForSec", s.elevatedUntilMs > now && a.role() == Role.ADMIN ? (s.elevatedUntilMs - now) / 1000 : 0L);
        // apresentação para a UI (a decisão é sempre refeita aqui): identidade estável legada, contato, flags e conclusão do 1º estágio do 2º fator
        m.put("userId", a.legacyUserId());
        m.put("email", a.email() == null ? "" : a.email());
        m.put("phone", a.phone() == null ? "" : a.phone());
        m.put("emailVerified", a.emailVerified());
        m.put("phoneVerified", a.phoneVerified());
        m.put("mustChangePassword", a.mustChangePassword());
        m.put("lastLoginAtMs", a.lastLoginAtMs());
        m.put("createdAtMs", a.createdAtMs());
        m.put("secondFactorConfigured", second.configured());
        m.put("smsPending", s.emailOkAtMs >= 0 && now - s.emailOkAtMs <= AuthLimits.SECOND_FACTOR_FLOW.toMillis());
        m.put("trustedDevice", activeDevice(a.id(), now).isPresent());
        m.put("expiresInSec", Math.max(0, (s.createdAtMs + AuthLimits.ABSOLUTE_TIMEOUT.toMillis() - now) / 1000));
        return m;
    }
}
