package byx.service.chain;

/** Falhas PÚBLICAS FECHADAS das leituras de módulo. Nunca texto, exceção ou corpo vindo do nó. NOT_FOUND não é erro do sistema: a chain segue LIVE. */
public enum ReadFailure {
    NOT_CONFIGURED, UNREACHABLE, TIMEOUT, NETWORK_MISMATCH, DENOM_MISMATCH, NOT_FOUND, INVALID_REQUEST, MALFORMED_RESPONSE, RESPONSE_TOO_LARGE, UNSUPPORTED_QUERY, RATE_LIMITED, STALE_CHAIN,
    MODULE_UNAVAILABLE
}
