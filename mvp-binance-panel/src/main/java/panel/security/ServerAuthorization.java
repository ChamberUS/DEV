package panel.security;

/** Operations without an authority service endpoint remain unavailable, regardless of presentation state. */
public final class ServerAuthorization {
    public static final String REQUIRED = "SERVER_AUTHORIZATION_REQUIRED";

    /** Produção: nega TUDO (inclusive operação nula). Não há estado, configuração nem variante permissiva. */
    public static final ServerAuthorizer DENY_ALL = new DenyAll();

    private ServerAuthorization() { }

    private static final class DenyAll implements ServerAuthorizer {
        @Override public void require(ServerOperation operation) {
            throw new AccessDeniedException(REQUIRED + ": " + (operation == null ? "unknown" : operation.wireName()));
        }
    }

    public static void require(ServerOperation operation) {
        DENY_ALL.require(operation);
    }

    public static void requirePersistence(ServerOperation operation) throws java.io.IOException {
        DENY_ALL.requirePersistence(operation);
    }
}
