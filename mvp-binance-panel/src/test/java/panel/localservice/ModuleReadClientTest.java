package panel.localservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import panel.model.ChainModules.Failure;
import panel.model.ChainModules.Freshness;

/** V2.1N: o cliente tipado de módulos: pedidos mínimos validados ANTES de qualquer chamada, DTOs revalidados, contrato violado = CONTRACT_VIOLATION sem dado parcial. Sem serviço real. */
class ModuleReadClientTest {
    static final String ADDR = "byx14uzu3ja88cpf5fktzp9rwt5zqhppv0j59qev7z";
    private static final ObjectMapper M = new ObjectMapper();
    private final List<String> calls = new ArrayList<>();
    private String reply;
    private IOException failure;

    private ModuleReadClient client() {
        return new ModuleReadClient((op, args) -> {
            calls.add(op + " " + args);
            if (failure != null) {
                throw failure;
            }
            return M.readTree(reply);
        });
    }

    private static String ok(String data, String extra) {
        return "{\"status\":\"OK\",\"freshness\":\"LIVE\",\"ageMs\":0,\"generation\":1,\"chainId\":\"byx\",\"data\":" + data + (extra == null ? "" : "," + extra) + "}";
    }

    private static String merchant(String id) {
        return "{\"id\":\"" + id + "\",\"name\":\"Loja\",\"address\":\"Rua 1\",\"creator\":\"" + ADDR + "\",\"operator\":\"" + ADDR + "\",\"kycStatus\":\"approved\"}";
    }

    private static String payment(String amount, String display, String status) {
        return "{\"id\":\"9\",\"storeId\":\"2\",\"amountUbyx\":\"" + amount + "\",\"amountDisplay\":\"" + display + "\",\"memo\":\"\",\"status\":\"" + status + "\",\"createdAtUnix\":1791353334,\"expiresAtUnix\":1791356934}";
    }

    @Test
    void validRequestsAreMinimalAndRepliesBecomeTypedDtos() {
        reply = ok(merchant("1"), null);
        var r = client().merchant("1");
        assertTrue(r.ok());
        assertEquals("Loja", r.data().name());
        assertEquals(Freshness.LIVE, r.freshness());
        assertEquals("byx.lojas.getMerchant {\"id\":\"1\"}", calls.get(0));
        calls.clear();
        reply = ok("{\"items\":[" + merchant("1") + "]}", "\"nextCursor\":\"v1.b.AAAAAAAAAAM\"");
        var page = client().merchants(5, null);
        assertEquals("byx.lojas.listMerchants {\"limit\":5}", calls.get(0));
        assertEquals(1, page.data().items().size());
        assertEquals("v1.b.AAAAAAAAAAM", page.nextCursor());
        calls.clear();
        client().paymentsByStore("3", 5, "v1.d.AAAAAAAAAAM");
        assertEquals("byx.payments.listByStore {\"id\":\"3\",\"limit\":5,\"cursor\":\"v1.d.AAAAAAAAAAM\"}", calls.get(0));
    }

    @Test
    void invalidInputFailsLocallyWithNoCallToTheService() {
        for (String id : new String[] {null, "", "0", "01", "-1", "../1", "1/2", "1?x", "1#", "http://x", "%2f", "9".repeat(19), "1 ", "x"}) {
            assertEquals(Failure.INVALID_REQUEST, client().merchant(id).failure(), String.valueOf(id));
            assertEquals(Failure.INVALID_REQUEST, client().payment(id).failure());
            assertEquals(Failure.INVALID_REQUEST, client().certificate(id).failure());
        }
        for (String a : new String[] {null, "", ADDR.toUpperCase(), ADDR.substring(0, ADDR.length() - 1) + "q", "cosmos1" + ADDR.substring(4), ADDR + "x", "../" + ADDR, ADDR + "?x=1"}) {
            assertEquals(Failure.INVALID_REQUEST, client().balance(a).failure(), String.valueOf(a));
        }
        for (int lim : new int[] {0, -1, 11, 100}) {
            assertEquals(Failure.INVALID_REQUEST, client().merchants(lim, null).failure());
        }
        assertEquals(Failure.INVALID_REQUEST, client().merchants(5, "bad").failure());
        assertEquals(Failure.INVALID_REQUEST, client().paymentsByStore(null, 5, null).failure());
        assertTrue(calls.isEmpty(), "no request left the panel: " + calls);
    }

    @Test
    void serviceFailuresAreTypedAndAnythingUnknownIsAContractViolation() {
        reply = "{\"status\":\"FAILED\",\"failure\":\"NOT_FOUND\"}";
        assertEquals(Failure.NOT_FOUND, client().merchant("1").failure());
        reply = "{\"status\":\"FAILED\",\"failure\":\"SOMETHING_NEW\"}";
        assertEquals(Failure.CONTRACT_VIOLATION, client().merchant("1").failure());
        reply = "{\"status\":\"MAYBE\"}";
        assertEquals(Failure.CONTRACT_VIOLATION, client().merchant("1").failure());
        reply = ok(merchant("1"), null).replace("\"LIVE\"", "\"FRESHISH\"");
        assertEquals(Failure.CONTRACT_VIOLATION, client().merchant("1").failure());
        reply = ok(merchant("1"), null).replace("\"ageMs\":0", "\"ageMs\":-5");
        assertEquals(Failure.CONTRACT_VIOLATION, client().merchant("1").failure());
        reply = ok(merchant("1"), null).replace("\"chainId\":\"byx\"", "\"chainId\":\"b y x\"");
        assertEquals(Failure.CONTRACT_VIOLATION, client().merchant("1").failure());
        reply = ok(merchant("1"), "\"nextCursor\":\"v1.a.AAAA\"");
        assertEquals(Failure.CONTRACT_VIOLATION, client().merchant("1").failure(), "a single record has no cursor");
        failure = new IOException("boom");
        assertEquals(Failure.SERVICE_UNAVAILABLE, client().merchant("1").failure());
    }

    @Test
    void cachedAndStaleFreshnessArePreservedAndNeverUpgradedToLive() {
        reply = ok(merchant("1"), null).replace("\"LIVE\"", "\"STALE\"").replace("\"ageMs\":0", "\"ageMs\":42000");
        var r = client().merchant("1");
        assertEquals(Freshness.STALE, r.freshness());
        assertEquals(42000, r.ageMs());
        reply = ok(merchant("1"), null).replace("\"LIVE\"", "\"CACHED\"");
        assertEquals(Freshness.CACHED, client().merchant("1").freshness());
    }

    @Test
    void paymentAmountsAreExactAndTheDisplayMustMatchTheBaseUnits() {
        reply = ok(payment("1500001", "1.500001 BYX", "EXPIRED"), null);
        var p = client().payment("9");
        assertTrue(p.ok());
        assertEquals("1500001", p.data().amountUbyx().toString());
        assertEquals("EXPIRED", p.data().status().name());
        assertNull(p.data().paidAt());
        reply = ok(payment("18446744073709551615", "18446744073709.551615 BYX", "PAID"), null);
        assertTrue(client().payment("9").ok());
        for (String[] bad : new String[][] {
                {"1500001", "1.500002 BYX", "PENDING"}, {"1500001", "1500001 BYX", "PENDING"}, {"-1", "0 BYX", "PENDING"}, {"18446744073709551616", "18446744073709.551616 BYX", "PENDING"},
                {"1.5", "1.5 BYX", "PENDING"}, {"1e6", "1 BYX", "PENDING"}, {"100", "0.000100 BYX", "NEWSTATE"}, {"100", "0.000100 BYX", "pending"}, {"100", "0.0001 by x", "PENDING"}}) {
            reply = ok(payment(bad[0], bad[1], bad[2]), null);
            assertEquals(Failure.CONTRACT_VIOLATION, client().payment("9").failure(), String.join("|", bad));
        }
        reply = ok(payment("100", "0.000100 BYX", "PENDING").replace("\"createdAtUnix\":1791353334,", ""), null);
        assertEquals(Failure.CONTRACT_VIOLATION, client().payment("9").failure(), "missing field");
        reply = ok(payment("100", "0.000100 BYX", "PENDING").replace("1791353334", "\"1791353334\""), null);
        assertEquals(Failure.CONTRACT_VIOLATION, client().payment("9").failure(), "wrong type");
    }

    @Test
    void feesplitIsDocumentedNotQueriedAndMustSumToTenThousandBps() {
        reply = "{\"status\":\"NOT_EXPOSED\",\"exposed\":false,\"source\":\"DOCUMENTED_DEFAULT_NOT_QUERIED\",\"allocationBps\":{\"distribution\":6000,\"treasury\":3000,\"burn\":1000}}";
        var f = client().feesplit();
        assertTrue(f.ok());
        assertEquals(6000, f.data().distributionBps());
        assertEquals(3000, f.data().treasuryBps());
        assertEquals(1000, f.data().burnBps());
        assertEquals("DOCUMENTED_DEFAULT_NOT_QUERIED", f.data().source());
        reply = reply.replace("1000}", "900}");
        assertEquals(Failure.CONTRACT_VIOLATION, client().feesplit().failure());
        reply = "{\"status\":\"NOT_EXPOSED\",\"exposed\":true,\"source\":\"DOCUMENTED_DEFAULT_NOT_QUERIED\",\"allocationBps\":{\"distribution\":6000,\"treasury\":3000,\"burn\":1000}}";
        assertEquals(Failure.CONTRACT_VIOLATION, client().feesplit().failure(), "claims to be exposed");
        reply = "{\"status\":\"NOT_EXPOSED\",\"exposed\":false,\"source\":\"CHAIN\",\"allocationBps\":{\"distribution\":6000,\"treasury\":3000,\"burn\":1000}}";
        assertEquals(Failure.CONTRACT_VIOLATION, client().feesplit().failure(), "must not pretend to be chain data");
    }

    @Test
    void healthKeepsNodeStateSeparateFromModuleStateAndRejectsUnknownStates() {
        reply = "{\"node\":\"OFFLINE\",\"modules\":[{\"module\":\"LOJAS\",\"state\":\"UNAVAILABLE\",\"lastFailure\":\"MODULE_UNAVAILABLE\"},{\"module\":\"FEESPLIT\",\"state\":\"NOT_EXPOSED\"}],\"reads\":{\"fetches\":3,\"cacheHits\":2,\"coalesced\":1,\"rateLimited\":0}}";
        var h = client().health();
        assertTrue(h.ok());
        assertEquals(2, h.data().modules().size());
        assertEquals(3, h.data().fetches());
        assertEquals("OFFLINE", h.data().node(), "node state is separate from module state");
        reply = reply.replace("UNAVAILABLE\"", "MELTING\"");
        assertEquals(Failure.CONTRACT_VIOLATION, client().health().failure());
    }

    @Test
    void certificatesRequireValidSerialsAndAddresses() {
        String c = "{\"id\":\"1\",\"merchantId\":\"1\",\"issuer\":\"" + ADDR + "\",\"owner\":\"" + ADDR + "\",\"category\":\"NOTEBOOK\",\"brand\":\"Dell\",\"model\":\"XPS\",\"serialHash\":\"" + "c".repeat(64)
                + "\",\"condition\":\"A\",\"notes\":\"\",\"imageSha256\":\"\",\"revoked\":true,\"revokedReason\":\"lost\",\"createdAtMs\":1791353340000}";
        reply = ok(c, null);
        var r = client().certificate("1");
        assertTrue(r.ok());
        assertTrue(r.data().revoked());
        assertEquals(ADDR, r.data().owner(), "the model keeps the FULL value");
        reply = ok(c.replace("c".repeat(64), ""), null);
        assertEquals(Failure.CONTRACT_VIOLATION, client().certificate("1").failure(), "empty serial");
        reply = ok(c.replace("\"owner\":\"" + ADDR + "\"", "\"owner\":\"byx1bad\""), null);
        assertEquals(Failure.CONTRACT_VIOLATION, client().certificate("1").failure());
        assertFalse(calls.isEmpty());
    }
}
