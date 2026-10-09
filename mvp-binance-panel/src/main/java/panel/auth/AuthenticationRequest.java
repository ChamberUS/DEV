package panel.auth;

import panel.user.User;

/** Owned login operation. No session token or authority decision is exposed to a view. */
public interface AuthenticationRequest {
    User authenticate(String identifier, char[] password);
    boolean isCurrent();
    /** Deliver a completion atomically with its generation check. Callback contains presentation only. */
    boolean deliver(Runnable callback);
    /** Non-blocking cancellation. Cleans only resources owned by this request. */
    void cancel();
}
