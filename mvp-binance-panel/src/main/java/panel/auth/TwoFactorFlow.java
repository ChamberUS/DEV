package panel.auth;

import panel.auth.OtpService.Channel;
import panel.auth.OtpService.Result;
import panel.user.User;

/** Fluxo e-mail → SMS. O acesso só é concedido depois dos DOIS códigos validados. */
public class TwoFactorFlow {
    private final User user;
    private final OtpService otp;
    private final EmailOtpProvider email;
    private final SmsOtpProvider sms;
    private final AdminAccessService access;
    private boolean emailVerified;
    private boolean smsVerified;

    TwoFactorFlow(User user, OtpService otp, EmailOtpProvider email, SmsOtpProvider sms, AdminAccessService access) {
        this.user = user;
        this.otp = otp;
        this.email = email;
        this.sms = sms;
        this.access = access;
    }

    public void sendEmailCode() {
        email.send(user.email(), otp.issue(user.id(), Channel.EMAIL));
    }

    public Result verifyEmail(String code) {
        Result r = otp.verify(user.id(), Channel.EMAIL, code);
        if (r == Result.OK) {
            emailVerified = true;
        } else {
            access.twoFactorFailed(user, "email " + r);
        }
        return r;
    }

    public void sendSmsCode() {
        if (!emailVerified) {
            throw new IllegalStateException("Verify the email code first.");
        }
        sms.send(user.phone(), otp.issue(user.id(), Channel.SMS));
    }

    public Result verifySms(String code) {
        if (!emailVerified) {
            return Result.NO_CHALLENGE;
        }
        Result r = otp.verify(user.id(), Channel.SMS, code);
        if (r == Result.OK) {
            smsVerified = true;
            access.twoFactorCompleted(this, user);
        } else {
            access.twoFactorFailed(user, "sms " + r);
        }
        return r;
    }

    public boolean emailVerified() {
        return emailVerified;
    }

    public boolean complete() {
        return emailVerified && smsVerified;
    }

    boolean bothVerified() {
        return complete();
    }

    public void cancel() {
        otp.clear(user.id());
    }
}
