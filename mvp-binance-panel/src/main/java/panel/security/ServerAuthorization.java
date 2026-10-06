package panel.security;

/** Operations without an authority service endpoint remain unavailable, regardless of presentation state. */
public final class ServerAuthorization {
    public static final String REQUIRED = "SERVER_AUTHORIZATION_REQUIRED";
    private ServerAuthorization() { }
    public static void require(String operation) {
        throw new AccessDeniedException(REQUIRED + ": " + operation);
    }
    public static void requirePersistence(String operation) throws java.io.IOException {
        throw new java.io.IOException(REQUIRED + ": " + operation);
    }
}
