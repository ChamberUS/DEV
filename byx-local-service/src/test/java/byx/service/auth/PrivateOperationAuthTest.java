package byx.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import byx.service.PrivateOperation;
import byx.service.auth.AuthService.Code;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V2.1K: matriz negativa no motor de autorização REAL do serviço. Toda operação privada TIPADA continua negada para qualquer sessão, papel, MFA, elevação e posse, porque o gate
 * (mestre, capacidade habilitada, capacidade implementada) é independente de quem pede; sessão expirada, encerrada ou de outro peer nega antes disso (AUTH_REQUIRED).
 */
class PrivateOperationAuthTest {
    private static final long PEER = 7001;
    private static final long OTHER_PEER = 7002;
    private AuthFixture f;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        f = new AuthFixture();
    }

    @AfterEach
    void down() throws Exception {
        f.close();
        Log.redirect(null);
    }

    @Test
    void everyTypedPrivateOperationIsDeniedEvenForAnElevatedAdminWithMfaAndOwnership() {
        String admin = AuthFixture.token(f.loginAdmin(PEER));
        f.mfa(PEER, admin, f.adminId);
        assertEquals(Code.OK, f.auth.adminElevation(PEER, admin).code());
        for (PrivateOperation op : PrivateOperation.values()) {
            assertEquals(Code.DENIED, f.auth.authorize(PEER, admin, op.wire(), f.adminId).code(), op.wire() + ": ADMIN + MFA + elevation + owner");
        }
        for (String legacy : List.of("account.read", "notifications.read", "qa.privateOp")) {
            assertEquals(Code.DENIED, f.auth.authorize(PEER, admin, legacy, f.adminId).code(), legacy);
        }
        assertEquals(Code.OK, f.auth.authorize(PEER, admin, "qa.adminOp", null).code(), "control: the engine still authorizes a non-private admin operation");
    }

    @Test
    void aPlainUserSessionAndForgedPrivilegeHintsNeverReachAPrivateOperation() {
        String user = AuthFixture.token(f.loginUser(PEER));
        f.mfa(PEER, user, f.userId);
        for (PrivateOperation op : PrivateOperation.values()) {
            assertEquals(Code.DENIED, f.auth.authorize(PEER, user, op.wire(), f.userId).code(), op.wire());
        }
        // o "dono" e o papel vêm do serviço, não de quem pede: dono errado também nega
        assertEquals(Code.DENIED, f.auth.authorize(PEER, user, PrivateOperation.ACCOUNT_BALANCES.wire(), f.adminId).code());
        for (String forged : new String[] {"admin.grant", "capabilities.set", "private.enable", "secrets.get", "account.balances.raw", "ACCOUNT.BALANCES", " account.balances", "account.balances ", null, ""}) {
            assertEquals(Code.DENIED, f.auth.authorize(PEER, user, forged, f.userId).code(), String.valueOf(forged));
        }
    }

    @Test
    void anUnknownCapabilityOrOperationIsDenied() {
        String admin = AuthFixture.token(f.loginAdmin(PEER));
        f.mfa(PEER, admin, f.adminId);
        f.auth.adminElevation(PEER, admin);
        for (String op : List.of("wallet.read", "payment.create", "gas.request", "orders.create", "binance.request", "notification.send", "http.request")) {
            assertEquals(Code.DENIED, f.auth.authorize(PEER, admin, op, f.adminId).code(), op);
        }
    }

    @Test
    void expiredLoggedOutAndWrongPeerSessionsAreRefusedBeforeAnyPolicy() {
        String token = AuthFixture.token(f.loginAdmin(PEER));
        f.mfa(PEER, token, f.adminId);
        f.auth.adminElevation(PEER, token);
        for (PrivateOperation op : PrivateOperation.values()) {
            assertEquals(Code.AUTH_REQUIRED, f.auth.authorize(OTHER_PEER, token, op.wire(), f.adminId).code(), op.wire() + ": same token, different process");
        }
        f.clock.advance(AuthLimits.IDLE_TIMEOUT.plusSeconds(1));
        for (PrivateOperation op : PrivateOperation.values()) {
            assertEquals(Code.AUTH_REQUIRED, f.auth.authorize(PEER, token, op.wire(), f.adminId).code(), op.wire() + ": expired");
        }
        String again = AuthFixture.token(f.loginAdmin(PEER));
        assertEquals(Code.OK, f.auth.logout(PEER, again).code());
        for (PrivateOperation op : PrivateOperation.values()) {
            assertEquals(Code.AUTH_REQUIRED, f.auth.authorize(PEER, again, op.wire(), f.adminId).code(), op.wire() + ": after logout");
        }
    }

    @Test
    void theAdminStateCannotExposeASecretOrAPrivateCapabilityInTheSessionView() {
        String admin = AuthFixture.token(f.loginAdmin(PEER));
        f.mfa(PEER, admin, f.adminId);
        f.auth.adminElevation(PEER, admin);
        var view = f.auth.sessionStatus(PEER, admin).data();
        String dump = view.toString().toLowerCase();
        for (String forbidden : List.of("secret", "credential", "apikey", "api_key", "passwordhash", "verifier", "binance", "capab", "signature")) {
            assertTrue(!dump.contains(forbidden), "session view mentions " + forbidden + ": " + dump);
        }
        assertTrue(!dump.contains(admin.toLowerCase()), "the session token is never echoed back in the view");
        assertTrue(logs.stream().noneMatch(l -> l.toLowerCase().contains("secret") || l.toLowerCase().contains("credential")), "no secret or credential text in the service log");
    }
}
