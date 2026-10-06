package panel.auth;

/** Resultado de uma verificação de código do segundo fator (decidido pelo SERVIÇO; o painel só o apresenta). */
public enum TwoFactorResult { OK, INVALID, EXPIRED, TOO_MANY_ATTEMPTS, NO_CHALLENGE }
