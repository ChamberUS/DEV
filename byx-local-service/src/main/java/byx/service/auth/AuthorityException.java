package byx.service.auth;

/** Falha de autoridade: só um código fixo (sem caminho, conta, hash ou texto de rede). */
public final class AuthorityException extends Exception {
    public final String code;

    public AuthorityException(String code) {
        super(code, null, false, false);
        this.code = code;
    }
}
