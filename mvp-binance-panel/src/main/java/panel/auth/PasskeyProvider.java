package panel.auth;

/** Reserved boundary; no passkey can authorize a session in this release. */
public interface PasskeyProvider { boolean available(); }
