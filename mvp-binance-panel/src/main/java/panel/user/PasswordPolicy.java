package panel.user;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 10;

    private PasswordPolicy() {
    }

    /** Devolve a mensagem de erro, ou null se a senha é aceitável. */
    public static String check(char[] password, String username) {
        if (password == null || password.length < MIN_LENGTH) {
            return "Password must have at least " + MIN_LENGTH + " characters.";
        }
        if (username != null && new String(password).equalsIgnoreCase(username)) {
            return "Password must not equal the username.";
        }
        return null;
    }
}
