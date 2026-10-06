package panel.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import panel.localservice.AuthorityGateway;
import panel.security.AccessDeniedException;

/**
 * Segundo fator de DOIS estágios (e-mail e depois SMS) conduzido pelo SERVIÇO: quem gera, entrega (Resend/Twilio, com segredos só do serviço) e confere os códigos
 * é o serviço. O painel só pede begin/verify e repassa o que o usuário digitou; o código de e-mail NUNCA passa por aqui. Concluir o segundo fator não concede
 * privilégio no painel: a elevação é pedida ao serviço, que a concede ou nega.
 */
public class TwoFactorFlow {
    public enum State { PENDING_EMAIL, EMAIL_SENT, EMAIL_VERIFIED, PENDING_SMS, SMS_SENT, SMS_VERIFIED, COMPLETE, EXPIRED, FAILED }

    private final AuthorityGateway gateway;
    private final AdminAccessService access;
    private final Clock clock;
    private final Instant createdAt;
    private volatile State state = State.PENDING_EMAIL;
    private volatile boolean cancelled;
    private boolean finished;
    private String challenge;
    private Instant lastEmailSend;
    private Instant lastSmsSend;
    private long emailResend = 30;
    private long smsResend = 30;

    TwoFactorFlow(AuthorityGateway gateway, AdminAccessService access, Clock clock) {
        this.gateway = gateway;
        this.access = access;
        this.clock = clock;
        this.createdAt = clock.instant();
    }

    public State state() {
        return state;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private void requireAlive() {
        if (cancelled || !clock.instant().isBefore(createdAt.plusSeconds(600))) {
            state = State.EXPIRED;
            throw new AccessDeniedException("Verification expired. Start again.");
        }
    }

    private RuntimeException failure(AuthorityGateway.Reply r, boolean sms) {
        switch (r.code()) {
            case "COOLDOWN", "TOO_MANY_SENDS", "RATE_LIMITED" -> {
                return new CooldownException(Duration.ofSeconds(Math.max(1, r.result() == null ? 30 : r.result().path("retryAfterSec").asLong(30))));
            }
            case "SECOND_FACTOR_NOT_CONFIGURED" -> {
                return new TwoFactorNotConfiguredException();
            }
            case "AUTH_REQUIRED", "AUTHORITY_UNAVAILABLE" -> {
                access.sessionLost();
                return new AccessDeniedException("Verification expired. Start again.");
            }
            case "CHALLENGE_EXPIRED" -> {
                state = State.EXPIRED;
                return new AccessDeniedException("Verification expired. Start again.");
            }
            default -> {
                return new IllegalStateException(sms ? "SMS verification failed. Check provider configuration." : "Email delivery failed. Check provider configuration.");
            }
        }
    }

    public synchronized void sendEmailCode() {
        requireAlive();
        if (emailVerified() || complete()) {
            throw new IllegalStateException("Email already verified");
        }
        AuthorityGateway.Reply r = gateway.beginSecondFactor();
        if (!r.ok()) {
            if (!r.code().equals("COOLDOWN") && !r.code().equals("TOO_MANY_SENDS") && !r.code().equals("RATE_LIMITED")) {
                state = State.FAILED;
            }
            throw failure(r, false);
        }
        challenge = r.result().path("challenge").asText();
        emailResend = Math.max(1, r.result().path("resendAfterSec").asLong(30));
        lastEmailSend = clock.instant();
        state = State.EMAIL_SENT;
    }

    private static TwoFactorResult map(AuthorityGateway.Reply r) {
        return switch (r.code()) {
            case "OK" -> TwoFactorResult.OK;
            case "CHALLENGE_EXPIRED" -> TwoFactorResult.EXPIRED;
            case "TOO_MANY_ATTEMPTS", "RATE_LIMITED" -> TwoFactorResult.TOO_MANY_ATTEMPTS;
            case "CHALLENGE_INVALID" -> TwoFactorResult.INVALID;
            default -> TwoFactorResult.EXPIRED; // sessão perdida, serviço indisponível…: fecha
        };
    }

    public synchronized TwoFactorResult verifyEmail(String code) {
        if (cancelled || !clock.instant().isBefore(createdAt.plusSeconds(600))) {
            state = State.EXPIRED;
            return TwoFactorResult.EXPIRED;
        }
        if (state != State.EMAIL_SENT) {
            return TwoFactorResult.NO_CHALLENGE;
        }
        AuthorityGateway.Reply r = gateway.verifySecondFactor(challenge, code == null ? "" : code);
        if (r.ok()) {
            state = State.EMAIL_VERIFIED;
            return TwoFactorResult.OK;
        }
        if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) {
            access.sessionLost();
        }
        TwoFactorResult t = map(r);
        if (t == TwoFactorResult.EXPIRED) {
            state = State.EXPIRED;
        } else if (t == TwoFactorResult.TOO_MANY_ATTEMPTS) {
            state = State.FAILED;
        }
        return t;
    }

    public synchronized void sendSmsCode() {
        requireAlive();
        if (!emailVerified() || complete()) {
            throw new IllegalStateException("Verify email first");
        }
        state = State.PENDING_SMS;
        AuthorityGateway.Reply r = gateway.sendSecondFactorSms();
        if (!r.ok()) {
            state = State.EMAIL_VERIFIED;
            throw failure(r, true);
        }
        smsResend = Math.max(1, r.result().path("resendAfterSec").asLong(30));
        lastSmsSend = clock.instant();
        state = State.SMS_SENT;
    }

    public synchronized TwoFactorResult verifySms(String code) {
        if (cancelled || !clock.instant().isBefore(createdAt.plusSeconds(600))) {
            state = State.EXPIRED;
            return TwoFactorResult.EXPIRED;
        }
        if (state != State.SMS_SENT) {
            return TwoFactorResult.NO_CHALLENGE;
        }
        AuthorityGateway.Reply r = gateway.verifySecondFactorSms(code == null ? "" : code);
        if (r.ok()) {
            state = State.SMS_VERIFIED;
            state = State.COMPLETE;
            access.completed(); // pede a elevação AO SERVIÇO (que decide); falhar aqui não concede nada
            return TwoFactorResult.OK;
        }
        if (r.code().equals("DENIED")) {
            throw failure(r, true);
        }
        if (r.code().equals("AUTH_REQUIRED") || r.code().equals("AUTHORITY_UNAVAILABLE")) {
            access.sessionLost();
        }
        TwoFactorResult t = map(r);
        if (t == TwoFactorResult.EXPIRED) {
            state = State.EXPIRED;
        } else if (t == TwoFactorResult.TOO_MANY_ATTEMPTS) {
            state = State.FAILED;
        }
        return t;
    }

    /** trust = o usuário pede para lembrar este Mac (o serviço inscreve o dispositivo confiável; recusado durante a janela de segurança do cutover). */
    public synchronized void finish(boolean trust) {
        requireAlive();
        if (!complete() || finished) {
            throw new AccessDeniedException("Verification is not complete");
        }
        if (trust) {
            access.trustCurrent();
        }
        finished = true;
    }

    public boolean emailVerified() {
        return state == State.EMAIL_VERIFIED || state == State.PENDING_SMS || state == State.SMS_SENT || state == State.SMS_VERIFIED || state == State.COMPLETE;
    }

    public boolean complete() {
        return state == State.COMPLETE && !cancelled;
    }

    public long resendSeconds(boolean phone) {
        Instant last = phone ? lastSmsSend : lastEmailSend;
        long wait = phone ? smsResend : emailResend;
        return last == null ? 0 : Math.max(0, Duration.between(clock.instant(), last.plusSeconds(wait)).toSeconds() + 1);
    }

    public void cancel() {
        cancelled = true;
    }
}
