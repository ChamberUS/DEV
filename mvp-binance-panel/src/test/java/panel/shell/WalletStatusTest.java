package panel.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import panel.security.AccessDeniedException;

/** V2.1N-1A: com a chain LIVE (identidade VERIFIED) o dock NUNCA pode lançar por causa do adaptador de carteira DENY_ALL. */
class WalletStatusTest {
    @Test
    void aDeniedWalletAdapterIsUnavailableNotAnException() {
        for (String identity : new String[] {"VERIFIED", "UNVERIFIED", "UNKNOWN", null}) {
            assertEquals("Wallet unavailable", WalletStatus.dock(identity, () -> { throw new AccessDeniedException("SERVER_AUTHORIZATION_REQUIRED: wallet.identity"); }), String.valueOf(identity));
            assertEquals("Unavailable", WalletStatus.diagnostics(identity, () -> { throw new AccessDeniedException("x"); }), String.valueOf(identity));
        }
        assertTrue(WalletStatus.empty(() -> { throw new IllegalStateException("anything"); }).isEmpty());
    }

    @Test
    void theListIsOnlyReadWhenTheNetworkIdentityIsVerified() {
        int[] reads = {0};
        assertEquals("Wallet unavailable", WalletStatus.dock("UNVERIFIED", () -> { reads[0]++; return List.of(); }));
        assertEquals(0, reads[0]);
        assertEquals("Wallet not linked", WalletStatus.dock("VERIFIED", () -> List.of()));
        assertEquals("Wallet linked", WalletStatus.dock("VERIFIED", () -> List.of("w")));
        assertEquals("Not linked", WalletStatus.diagnostics("VERIFIED", () -> List.of()));
        assertEquals("Linked", WalletStatus.diagnostics("VERIFIED", () -> List.of("w")));
        assertFalse(WalletStatus.empty(() -> List.of("w")).orElseThrow());
    }

    @Test
    void panelAppNeverReadsTheLegacyWalletListOutsideAGuard() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/panel/app/PanelApp.java"));
        int unguarded = 0;
        int from = 0;
        while ((from = src.indexOf("byxWallets.wallets()", from)) >= 0) {
            String before = src.substring(Math.max(0, from - 160), from);
            if (!before.contains("WalletStatus.") && !before.contains("try {")) {
                unguarded++;
            }
            from++;
        }
        assertEquals(0, unguarded, "every PanelApp read of the legacy wallet list goes through WalletStatus or a try/catch");
    }
}
