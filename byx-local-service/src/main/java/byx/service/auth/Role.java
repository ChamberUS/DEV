package byx.service.auth;

public enum Role {
    USER, ADMIN;

    /** Ordem de privilégio: ADMIN inclui USER. */
    public boolean atLeast(Role min) {
        return ordinal() >= min.ordinal();
    }
}
