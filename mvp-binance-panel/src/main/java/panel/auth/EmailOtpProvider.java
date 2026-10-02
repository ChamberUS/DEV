package panel.auth;

public interface EmailOtpProvider {
    boolean configured();

    String name();

    void send(String email, String code);
}
