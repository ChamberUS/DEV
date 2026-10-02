package panel.auth;

public interface SmsOtpProvider {
    boolean configured();

    String name();

    void send(String phone, String code);
}
