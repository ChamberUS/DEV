package byx.service.migration;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FinalizePreflightTest {
    @Test void eachNegativeConditionFailsClosed() {
        var ready = new FinalizePreflight.Conditions(true, true, true, false, true, true, true);
        assertDoesNotThrow(() -> FinalizePreflight.require(ready));
        var cases = new FinalizePreflight.Conditions[] {
            new FinalizePreflight.Conditions(false, true, true, false, true, true, true),
            new FinalizePreflight.Conditions(true, false, true, false, true, true, true),
            new FinalizePreflight.Conditions(true, true, false, false, true, true, true),
            new FinalizePreflight.Conditions(true, true, true, true, true, true, true),
            new FinalizePreflight.Conditions(true, true, true, false, false, true, true),
            new FinalizePreflight.Conditions(true, true, true, false, true, false, true),
            new FinalizePreflight.Conditions(true, true, true, false, true, true, false)
        };
        String[] codes = {"authority_untrusted", "migration_verify_failed", "safety_window_inactive", "private_gate_open",
                         "provisioning_profile_expired_or_unavailable", "enabled_admin_required", "second_factor_unavailable"};
        for (int i = 0; i < cases.length; i++) {
            var candidate = cases[i];
            assertEquals(codes[i], assertThrows(MigrationException.class, () -> FinalizePreflight.require(candidate)).code);
        }
    }
    @Test void provisioningExpirationUsesSyntheticMetadataAndNoKeychain() {
        byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">"
                + "<plist><dict><key>ExpirationDate</key><date>2026-10-13T12:00:00Z</date></dict></plist>").getBytes(StandardCharsets.UTF_8);
        assertTrue(FinalizePreflight.profileCurrent(xml, Instant.parse("2026-10-06T12:00:00Z")));
        assertFalse(FinalizePreflight.profileCurrent(xml, Instant.parse("2026-10-13T12:00:00Z")));
        assertFalse(FinalizePreflight.profileCurrent("<plist/>".getBytes(StandardCharsets.UTF_8), Instant.now()));
    }
}
