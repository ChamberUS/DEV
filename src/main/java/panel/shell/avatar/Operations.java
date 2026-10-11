package panel.shell.avatar;

/**
 * The only way anything tells the avatar that work is pending (dependency D-13). Callers report REAL operations they own: begin before the
 * real request starts, end when its real result arrives. The avatar never infers, simulates or times operations by itself, and ending a token
 * never touches any other operation or the operation itself.
 */
public interface Operations {
    /** Opaque identity of one operation. A token is only valid for the registry (and session) that issued it. */
    interface Token {
    }

    Token begin(String name);

    /** ok=false (failed or did not finish) raises the error look for a few seconds. Unknown, stale or repeated tokens are ignored. */
    void end(Token token, boolean ok, String why);

    Operations NONE = new Operations() {
        private final Token t = new Token() { };

        @Override
        public Token begin(String name) {
            return t;
        }

        @Override
        public void end(Token token, boolean ok, String why) {
        }
    };
}
