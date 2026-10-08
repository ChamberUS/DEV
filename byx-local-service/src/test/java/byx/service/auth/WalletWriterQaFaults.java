package byx.service.auth;

public final class WalletWriterQaFaults {
    private WalletWriterQaFaults() { }
    public static void attach(AuthorityStore store,java.util.function.Consumer<String> boundary) {
        store.walletQaFault(boundary,Integer.MAX_VALUE);
    }
}
