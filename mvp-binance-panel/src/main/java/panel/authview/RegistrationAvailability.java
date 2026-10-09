package panel.authview;

/**
 * What the Service says about creating accounts (B02). The Service has NO registration operation in this build
 * (wire ops are auth.password / beginSecondFactor / verifySecondFactor / … only), so production answers {@link #UNAVAILABLE}.
 *
 * <p>{@code OPEN} is reserved for the day the Service publishes a registration contract. Until then the login screen renders it exactly like
 * UNAVAILABLE: the client never shows a sign-up form, never creates a local account, never asks for or sends a role, and never invents
 * an invitation channel. Roles always come from the Service.
 * Dependency: REGISTRATION_BACKEND_DEPENDENCY (D-01/D-02/D-03).
 */
public enum RegistrationAvailability {
    /** Still asking. Signing in is never blocked by this. */
    UNKNOWN,
    UNAVAILABLE,
    /** Reserved; see above. */
    OPEN,
    /** Registration exists only by invitation; no form and no invite-code field (the mechanism does not exist). */
    INVITE;

    /** True when the login may show a sign-up affordance. Always false in this build. */
    public boolean offersSignUp() {
        return false;
    }
}
