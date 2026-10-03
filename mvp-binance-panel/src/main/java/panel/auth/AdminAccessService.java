package panel.auth;

import java.time.*;
import java.util.*;
import panel.security.*;
import panel.user.User;

/** Login and ADMIN are mandatory; only two factors or a valid Keychain-backed device grant a session. */
public class AdminAccessService implements AdminGate {
    private final SessionManager sessions; private final SecurityConfig config; private final OtpService otp;
    private final EmailOtpProvider email; private final SmsOtpProvider sms; private final TrustedDeviceService devices;
    private final SecurityAuditService audit; private final Clock clock;
    private volatile TwoFactorFlow active;
    private final Map<String,Instant> sends=new HashMap<>();
    public AdminAccessService(SessionManager sessions, SecurityConfig config, OtpService otp, EmailOtpProvider email,
            SmsOtpProvider sms, TrustedDeviceService devices, SecurityAuditService audit, Clock clock) {
        this.sessions=sessions;this.config=config;this.otp=otp;this.email=email;this.sms=sms;this.devices=devices;this.audit=audit;this.clock=clock;
        sessions.onLogout(this::cancelChallenge);
    }
    public boolean twoFactorConfigured(){return email.configured() && sms.configured();}
    public AccessDecision evaluate() {
        var user=sessions.user();if(user.isEmpty())return AccessDecision.SESSION_EXPIRED;
        if(!user.get().user().admin() || !user.get().user().active())return AccessDecision.FORBIDDEN_NOT_ADMIN;
        return hasValidAdminSession()?AccessDecision.ALREADY_AUTHORIZED:AccessDecision.REQUIRES_2FA;
    }
    public boolean tryTrustedDevice() {
        if(evaluate()!=AccessDecision.REQUIRES_2FA)return false;
        UserSession expected=sessions.user().orElseThrow();
        if(!devices.use(expected))return false;
        synchronized(sessions) {
            if(!current(expected))return false;
            sessions.grantAdmin(newSession(AuthMethod.TRUSTED_DEVICE));
            audit.record(AuditEvent.ADMIN_ACCESS_TRUSTED_DEVICE,actor(),"");return true;
        }
    }
    public TwoFactorFlow startTwoFactor() {
        if(evaluate()!=AccessDecision.REQUIRES_2FA)throw new AccessDeniedException("Two-factor is not applicable");
        UserSession expected=sessions.user().orElseThrow();
        if(!twoFactorConfigured())throw new TwoFactorNotConfiguredException();
        synchronized(sessions) {
            if(!current(expected))throw new AccessDeniedException("Session changed");
            User u=expected.user();
            if(u.email()==null || u.phone()==null || !u.phone().matches("\\+[1-9][0-9]{7,14}"))throw new IllegalStateException("Set an email and E.164 phone in Profile first.");
            cancelChallenge();
            active=new TwoFactorFlow(expected,otp,email,sms,this,clock);
            audit.record(AuditEvent.ADMIN_2FA_STARTED,actor(),"");return active;
        }
    }
    boolean current(UserSession expected) {
        return sessions.user().filter(s->s.id().equals(expected.id()) && s.user().id()==expected.user().id() && s.user().admin() && s.user().active()).isPresent();
    }
    boolean valid(TwoFactorFlow flow,UserSession expected){return flow==active && current(expected);}
    void sent(UserSession expected,boolean phone) {
        synchronized(sends) {
            String key=expected.user().id()+":"+phone; Instant now=clock.instant(),last=sends.get(key);
            if(last!=null && now.isBefore(last.plusSeconds(30)))throw new OtpService.CooldownException(Duration.between(now,last.plusSeconds(30)));
            sends.put(key,now);
        }
    }
    public void cancelChallenge() { if(active!=null){active.cancel();active=null;} }
    void event(AuditEvent event,UserSession expected){audit.record(event,"user:"+expected.user().id(),"");}
    void complete(TwoFactorFlow flow,UserSession expected) {
        synchronized(sessions) {
            if(!valid(flow,expected)||!flow.complete())throw new AccessDeniedException("Challenge expired or session changed");
            sessions.grantAdmin(newSession(AuthMethod.TWO_FACTOR));event(AuditEvent.ADMIN_ACCESS_2FA,expected);
        }
    }
    void trust(TwoFactorFlow flow,UserSession expected) {
        if(!valid(flow,expected)||!flow.complete())throw new AccessDeniedException("Complete two-factor first");
        devices.trustCurrent();
    }
    public boolean hasValidAdminSession() {
        return sessions.user().filter(s->s.user().admin()&&s.user().active()).isPresent()
                && sessions.admin().filter(s->s.validAt(clock.instant())).isPresent();
    }
    public boolean expireIfNeeded() {
        if(sessions.admin().filter(s->!s.validAt(clock.instant())).isPresent()){
            sessions.revokeAdmin();audit.record(AuditEvent.ADMIN_SESSION_EXPIRED,actor(),"");return true;
        }return false;
    }
    public void touch(){sessions.admin().filter(s->s.validAt(clock.instant())).ifPresent(s->s.touch(clock.instant()));}
    public Optional<AdminSession> adminSession(){return sessions.admin().filter(s->s.validAt(clock.instant()));}
    public void noteDenied(String detail){audit.record(AuditEvent.ADMIN_ACCESS_DENIED,actor(),"Access denied");}
    @Override public User requireAdmin() {
        if(expireIfNeeded()||!hasValidAdminSession())throw new AccessDeniedException("Administrator session required");
        return sessions.user().orElseThrow().user();
    }
    private AdminSession newSession(AuthMethod method){return new AdminSession(clock.instant(),method,Duration.ofMinutes(config.sessionTimeoutMinutes()));}
    private String actor(){return sessions.user().map(s->"user:"+s.user().id()).orElse("-");}
}
