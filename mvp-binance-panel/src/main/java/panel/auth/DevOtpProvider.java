package panel.auth;

import java.time.Clock;
import java.util.*;

/** Test/development transport. AppContext enables it only for explicit security.dev.mode=true. */
public class DevOtpProvider implements EmailOtpProvider, SmsOtpProvider {
    public static final String LABEL="DEVELOPMENT AUTH PROVIDER";
    private volatile String lastCode;
    private final OtpService sms=new OtpService(Clock.systemUTC());
    private final Map<String,String> destinations=new HashMap<>();
    public boolean configured() { return true; }
    public ProviderStatus status() { return ProviderStatus.configured(); }
    public String name() { return LABEL; }
    public void send(String destination, String code) { lastCode=code; }
    public synchronized String startVerification(String phone) {
        String id=UUID.randomUUID().toString(); destinations.put(id,phone); lastCode=sms.issue(id,OtpService.Channel.SMS); return id;
    }
    public synchronized boolean checkVerification(String phone,String id,String code) {
        return Objects.equals(phone,destinations.get(id)) && sms.verify(id,OtpService.Channel.SMS,code)==OtpService.Result.OK;
    }
    public String lastCode() { return lastCode; }
    @Override public String toString() { return LABEL; }
}
