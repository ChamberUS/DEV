package byx.service;

/** Closed, read-only inspection of sealed runtime constants. Never composes auth, custody, feed or storage. */
final class RuntimeDiagnostics {
    private RuntimeDiagnostics() { }
    static void print() {
        // Read-only before constructing any auth profile, secret store, feed, chain connector or runtime directory.
        System.out.println("service.diagnostics.schema=1");
        System.out.println("service.diagnostics.javaFeature=" + Runtime.version().feature());
        System.out.println("service.diagnostics.authModule=" + ModuleLayer.boot().findModule("jdk.security.auth").isPresent());
        System.out.println("service.diagnostics.txMutationsAllowed=" + byx.service.tx.TxGate.TX_MUTATIONS_ALLOWED);
        System.out.println("service.diagnostics.walletCapability=" + byx.service.wallet.WalletView.disabled().capability());
        System.out.println("service.diagnostics.keychainCalls=0");
        System.out.println("service.diagnostics.networkStarted=false");
    }
}
