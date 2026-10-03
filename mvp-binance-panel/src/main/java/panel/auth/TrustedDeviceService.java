package panel.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import panel.security.*;
import panel.user.User;

public final class TrustedDeviceService {
    public record Device(String id, String displayName, Instant createdAt, Instant lastUsedAt, Instant expiresAt, Instant revokedAt) {
        public String status(Instant now) { return !now.isBefore(expiresAt) ? "EXPIRED" : revokedAt != null ? "REVOKED" : "ACTIVE"; }
    }
    private final Database db; private final SecretStore secrets; private final SessionManager sessions;
    private final SecurityAuditService audit; private final Clock clock;
    public TrustedDeviceService(Database db, SecretStore secrets, SessionManager sessions, SecurityAuditService audit, Clock clock) {
        this.db=db;this.secrets=secrets;this.sessions=sessions;this.audit=audit;this.clock=clock;
    }
    private User admin() {
        var user=sessions.user().orElseThrow(()->new AccessDeniedException("Login required")).user();
        if (!user.admin() || !user.active() || sessions.admin().filter(s->s.validAt(clock.instant())).isEmpty()) throw new AccessDeniedException("AdminSession required");
        return user;
    }
    static String hash(char[] token) {
        byte[] bytes = new String(token).getBytes(StandardCharsets.UTF_8);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
        finally { Arrays.fill(bytes,(byte)0); }
    }
    public void trustCurrent() {
        User u=admin(); AdminSession session=sessions.admin().orElseThrow();
        if(session.method()!=AuthMethod.TWO_FACTOR || clock.instant().isAfter(session.authorizedAt().plusSeconds(300))) throw new AccessDeniedException("Fresh two-factor authentication required");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        char[] token=Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();Arrays.fill(random,(byte)0);
        String hash=hash(token), id=UUID.randomUUID().toString(); Instant now=clock.instant();
        try {
            secrets.write(SecretStore.DEVICE,token);
            synchronized(sessions) {
                if(!sessions.admin().filter(s->s==session && s.validAt(clock.instant())).isPresent()) throw new AccessDeniedException("Session changed");
                db.with(c->{try(var p=c.prepareStatement("INSERT INTO trusted_devices VALUES(?,?,?,?,?,?,?,NULL)")) {
                    p.setString(1,id);p.setLong(2,u.id());p.setString(3,"This Mac");p.setString(4,hash);
                    p.setString(5,now.toString());p.setString(6,now.toString());p.setString(7,now.plus(Duration.ofDays(30)).toString());return p.executeUpdate();
                }});
                audit.record(AuditEvent.TRUSTED_DEVICE_CREATED,"user:"+u.id(),"");
            }
        } finally { Arrays.fill(token,'\0'); }
    }
    boolean use(UserSession expected) {
        char[] token;
        try { token=secrets.read(SecretStore.DEVICE).orElse(null); } catch(SecretStore.Unavailable e) { return false; }
        if(token==null) return false;
        String hash;try { hash=hash(token); } finally { Arrays.fill(token,'\0'); }
        synchronized(sessions) {
            if(sessions.user().filter(s->s.id().equals(expected.id())).isEmpty()) return false;
            return db.with(c->{try(var p=c.prepareStatement("SELECT device_id,expires_at,revoked_at FROM trusted_devices WHERE user_id=? AND token_hash=?")) {
                p.setLong(1,expected.user().id());p.setString(2,hash);
                try(var rows=p.executeQuery()) {
                    if(!rows.next() || rows.getString(3)!=null) return false;
                    String id=rows.getString(1); boolean expired=!clock.instant().isBefore(Instant.parse(rows.getString(2)));
                    try(var update=c.prepareStatement(expired ? "UPDATE trusted_devices SET revoked_at=? WHERE device_id=?" : "UPDATE trusted_devices SET last_used_at=? WHERE device_id=?")) {
                        update.setString(1,clock.instant().toString());update.setString(2,id);update.executeUpdate();
                    }
                    audit.record(expired?AuditEvent.TRUSTED_DEVICE_EXPIRED:AuditEvent.TRUSTED_DEVICE_USED,"user:"+expected.user().id(),"");
                    return !expired;
                }
            }});
        }
    }
    public List<Device> list() {
        User user=admin();
        return db.with(c->{var out=new ArrayList<Device>();try(var p=c.prepareStatement("SELECT device_id,display_name,created_at,last_used_at,expires_at,revoked_at FROM trusted_devices WHERE user_id=? ORDER BY created_at DESC")) {
            p.setLong(1,user.id());try(var rows=p.executeQuery()){while(rows.next())out.add(new Device(rows.getString(1),rows.getString(2),Instant.parse(rows.getString(3)),Instant.parse(rows.getString(4)),Instant.parse(rows.getString(5)),rows.getString(6)==null?null:Instant.parse(rows.getString(6))));}
        }return List.copyOf(out);});
    }
    public void revoke(String id) {
        User user=admin();
        db.with(c->{try(var p=c.prepareStatement("UPDATE trusted_devices SET revoked_at=? WHERE device_id=? AND user_id=? AND revoked_at IS NULL")) {
            p.setString(1,clock.instant().toString());p.setString(2,id);p.setLong(3,user.id());p.executeUpdate();return null;
        }});
        sessions.revokeAdmin();
        audit.record(AuditEvent.TRUSTED_DEVICE_REVOKED,"user:"+user.id(),"");
    }
    public void revokeAllForCurrentUser() {
        long userId=sessions.user().orElseThrow().user().id();
        db.with(c->{try(var p=c.prepareStatement("UPDATE trusted_devices SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL")){
            p.setString(1,clock.instant().toString());p.setLong(2,userId);p.executeUpdate();return null;
        }});sessions.revokeAdmin();
    }
}
