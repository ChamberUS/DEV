package panel.auth;

import java.time.*;
import panel.auth.OtpService.*;
import panel.security.*;

/** All results are rebound to the initiating login after external I/O returns. */
public class TwoFactorFlow {
    public enum State { PENDING_EMAIL, EMAIL_SENT, EMAIL_VERIFIED, PENDING_SMS, SMS_SENT, SMS_VERIFIED, COMPLETE, EXPIRED, FAILED }
    private final UserSession session; private final OtpService otp; private final EmailOtpProvider email;
    private final SmsOtpProvider sms; private final AdminAccessService access; private final Clock clock;
    private final Instant createdAt; private volatile State state=State.PENDING_EMAIL;
    private volatile boolean cancelled; private boolean finished; private int smsAttempts;
    private String verificationId; private Instant smsSentAt; private Instant lastEmailSend; private Instant lastSmsSend;
    TwoFactorFlow(UserSession session,OtpService otp,EmailOtpProvider email,SmsOtpProvider sms,AdminAccessService access,Clock clock){
        this.session=session;this.otp=otp;this.email=email;this.sms=sms;this.access=access;this.clock=clock;createdAt=clock.instant();
    }
    public State state(){return state;}
    public Instant createdAt(){return createdAt;}
    private boolean alive(){
        if(cancelled||!access.valid(this,session))return false;
        if(!clock.instant().isBefore(createdAt.plusSeconds(600))){state=State.EXPIRED;return false;}return true;
    }
    private void requireAlive(){if(!alive())throw new AccessDeniedException("Verification expired. Start again.");}
    public synchronized void sendEmailCode(){
        requireAlive();if(emailVerified()||complete())throw new IllegalStateException("Email already verified");
        access.sent(session,false);String code=otp.issue(session.id().toString(),Channel.EMAIL);lastEmailSend=clock.instant();
        try{email.send(session.user().email(),code);requireAlive();state=State.EMAIL_SENT;access.event(AuditEvent.EMAIL_OTP_SENT,session);}
        catch(RuntimeException e){otp.clear(session.id().toString());state=State.FAILED;access.event(AuditEvent.EMAIL_OTP_FAILED,session);throw e;}
    }
    public synchronized Result verifyEmail(String code){
        if(!alive())return Result.EXPIRED;
        if(state!=State.EMAIL_SENT)return Result.NO_CHALLENGE;
        Result result=otp.verify(session.id().toString(),Channel.EMAIL,code);
        if(result==Result.OK){state=State.EMAIL_VERIFIED;access.event(AuditEvent.EMAIL_OTP_VERIFIED,session);}
        else {if(result==Result.EXPIRED)state=State.EXPIRED;else if(result==Result.TOO_MANY_ATTEMPTS)state=State.FAILED;access.event(AuditEvent.EMAIL_OTP_FAILED,session);}
        return result;
    }
    public synchronized void sendSmsCode(){
        requireAlive();if(!emailVerified()||complete())throw new IllegalStateException("Verify email first");
        access.sent(session,true);state=State.PENDING_SMS;lastSmsSend=clock.instant();
        try{String id=sms.startVerification(session.user().phone());requireAlive();verificationId=id;smsSentAt=clock.instant();smsAttempts=0;state=State.SMS_SENT;access.event(AuditEvent.SMS_VERIFY_SENT,session);}
        catch(RuntimeException e){state=State.EMAIL_VERIFIED;access.event(AuditEvent.SMS_VERIFY_FAILED,session);throw e;}
    }
    public synchronized Result verifySms(String code){
        if(!alive())return Result.EXPIRED;
        if(state!=State.SMS_SENT)return Result.NO_CHALLENGE;
        if(!clock.instant().isBefore(smsSentAt.plusSeconds(300))){state=State.EXPIRED;return Result.EXPIRED;}
        if(smsAttempts>=5){state=State.FAILED;return Result.TOO_MANY_ATTEMPTS;}smsAttempts++;
        boolean ok;
        try{ok=sms.checkVerification(session.user().phone(),verificationId,code);}
        catch(RuntimeException e){access.event(AuditEvent.SMS_VERIFY_FAILED,session);throw e;}
        if(!alive())return Result.EXPIRED;
        if(!clock.instant().isBefore(smsSentAt.plusSeconds(300))){state=State.EXPIRED;return Result.EXPIRED;}
        if(!ok){access.event(AuditEvent.SMS_VERIFY_FAILED,session);return Result.INVALID;}
        state=State.SMS_VERIFIED;access.event(AuditEvent.SMS_VERIFY_APPROVED,session);state=State.COMPLETE;
        access.complete(this,session);return Result.OK;
    }
    public synchronized void finish(boolean trust){requireAlive();if(!complete()||finished)throw new AccessDeniedException("Verification is not complete");if(trust)access.trust(this,session);finished=true;}
    public boolean emailVerified(){return state==State.EMAIL_VERIFIED||state==State.PENDING_SMS||state==State.SMS_SENT||state==State.SMS_VERIFIED||state==State.COMPLETE;}
    public boolean complete(){return state==State.COMPLETE&&!cancelled;}
    public long resendSeconds(boolean phone){Instant last=phone?lastSmsSend:lastEmailSend;return last==null?0:Math.max(0,Duration.between(clock.instant(),last.plusSeconds(30)).toSeconds()+1);}
    public void cancel(){cancelled=true;otp.clear(session.id().toString());}
}
