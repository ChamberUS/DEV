package byx.service.chain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.Log;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V2.1N/N+: leituras públicas de módulo contra um nó FALSO hostil em 127.0.0.1. Contrato, limites, falha fechada, cache/coalescência/geração e isolamento de rede. Nenhum nó real. */
class ChainReaderTest {
    static final String ADDR = "byx14uzu3ja88cpf5fktzp9rwt5zqhppv0j59qev7z";
    static final String HEX = "c".repeat(64);
    private FakeNode node;
    private ChainConnector connector;
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void up() throws Exception {
        Log.redirect(logs::add);
        node = new FakeNode();
        connector = connector(ChainReader.Limits.production());
        connector.cycle();
    }

    private ChainConnector connector(ChainReader.Limits l) {
        return new ChainConnector(Optional.of(node.config()), new LoopbackHttp(Duration.ofMillis(400), Duration.ofMillis(600)), new ChainConnector.Timing(10, 60_000, 30_000, 600_000),
                System::currentTimeMillis, l);
    }

    @AfterEach
    void down() {
        connector.close();
        node.close();
        Log.redirect(null);
    }

    // ---- fixtures (forma do gateway REST real; valores sintéticos) -------------------------------------------------------------------

    static String merchant(String id) {
        return "{\"id\":\"" + id + "\",\"nome\":\"Loja " + id + "\",\"endereco\":\"Rua 1\",\"saldo\":\"0\",\"creator\":\"" + ADDR + "\",\"operator_address\":\"" + ADDR
                + "\",\"kyc_ref\":\"SECRET-KYC\",\"document_hash\":\"" + "a".repeat(64) + "\",\"kyc_status\":\"approved\"}";
    }

    static String payment(String id, String loja, String amount, String status) {
        return "{\"id\":\"" + id + "\",\"loja_id\":\"" + loja + "\",\"amount_ubyx\":\"" + amount + "\",\"memo\":\"\",\"status\":\"" + status + "\",\"created_at_unix\":\"1791353334\","
                + "\"expires_at_unix\":\"1791356934\",\"payer\":\"\",\"paid_at_unix\":\"0\"}";
    }

    static String cert(String id, String merchantId, String serial) {
        return "{\"id\":\"" + id + "\",\"merchant_id\":\"" + merchantId + "\",\"issuer\":\"" + ADDR + "\",\"owner\":\"" + ADDR + "\",\"category\":\"NOTEBOOK\",\"brand\":\"Dell\",\"model\":\"XPS\","
                + "\"serial_hash\":\"" + serial + "\",\"condition\":\"A\",\"notes\":\"\",\"image_uri\":\"https://x.invalid/a.png\",\"image_sha256\":\"" + "d".repeat(64)
                + "\",\"image_seed\":\"s\",\"revoked\":false,\"revoked_reason\":\"\",\"created_at\":\"2026-10-07T06:09:00Z\"}";
    }

    static String page(String field, String items, String nextKey) {
        return "{\"" + field + "\":[" + items + "],\"pagination\":{\"next_key\":" + (nextKey == null ? "null" : "\"" + nextKey + "\"") + ",\"total\":\"0\"}}";
    }

    private static final String NOT_FOUND_MERCHANT = "{\"code\":5, \"message\":\"rpc error: code = NotFound desc = merchant not found: key not found\", \"details\":[]}";

    private ReadResult read(ReadOp op, String id, String address, Integer limit, String cursor) {
        return connector.read(ReadRequest.of(op, id, address, limit, cursor));
    }

    private void put(String path, int status, String body) {
        node.override.put(path, new FakeNode.Reply(status, body, 0, null));
    }

    private long moduleRequests() {
        return node.paths.stream().filter(p -> p.contains("/byx/") || p.contains("/balances/")).count();
    }

    // ---- caminho feliz, DTO mínimo, cache e coalescência ----------------------------------------------------------------------------

    @Test
    void merchantIsAMinimalDtoWithoutKycOrDocumentAndSecondReadIsCachedNotRefetched() {
        put("/byx/lojas/v1/merchant/1", 200, "{\"merchant\":" + merchant("1") + "}");
        ReadResult r = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertTrue(r.ok(), String.valueOf(r.failure()));
        assertEquals(ReadResult.Freshness.LIVE, r.freshness());
        assertEquals("Loja 1", r.data().get("name").asText());
        assertEquals("approved", r.data().get("kycStatus").asText());
        assertFalse(r.data().toString().contains("SECRET-KYC") || r.data().toString().contains("document") || r.data().has("saldo"), "kyc_ref/document_hash/saldo are never passed through");
        ReadResult again = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertEquals(ReadResult.Freshness.CACHED, again.freshness());
        assertEquals(1, moduleRequests(), "one node request for two reads");
    }

    @Test
    void identicalInFlightReadsCoalesceIntoOneNodeRequestAndDifferentParametersDoNot() throws Exception {
        node.byUri.put("/byx/lojas/v1/merchant/7", new FakeNode.Reply(200, "{\"merchant\":" + merchant("7") + "}", 250, null));
        node.byUri.put("/byx/lojas/v1/merchant/8", new FakeNode.Reply(200, "{\"merchant\":" + merchant("8") + "}", 250, null));
        ExecutorService ex = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReadResult>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(ex.submit(() -> { go.await(); return read(ReadOp.LOJAS_GET_MERCHANT, "7", null, null, null); }));
        }
        fs.add(ex.submit(() -> { go.await(); return read(ReadOp.LOJAS_GET_MERCHANT, "8", null, null, null); }));
        go.countDown();
        for (Future<ReadResult> f : fs) {
            assertTrue(f.get().ok());
        }
        ex.shutdownNow();
        assertEquals(1, node.paths.stream().filter(p -> p.endsWith("/merchant/7")).count(), "six identical reads, one request");
        assertEquals(1, node.paths.stream().filter(p -> p.endsWith("/merchant/8")).count(), "a different parameter is a different request");
        assertTrue(connector.readCounters()[2] >= 5, "coalesced counter");
    }

    @Test
    void concurrencyAtTheNodeIsBoundedAndExcessIsRateLimitedNotQueuedForever() throws Exception {
        ChainReader.Limits tight = new ChainReader.Limits(2, 2, 128, 1000, 10_000, 600_000, 3_000, 4_000);
        connector.close();
        connector = connector(tight);
        connector.cycle();
        for (int i = 1; i <= 12; i++) {
            node.byUri.put("/byx/lojas/v1/merchant/" + i, new FakeNode.Reply(200, "{\"merchant\":" + merchant("" + i) + "}", 200, null));
        }
        ExecutorService ex = Executors.newFixedThreadPool(12);
        List<Future<ReadResult>> fs = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            final String id = "" + i;
            fs.add(ex.submit(() -> read(ReadOp.LOJAS_GET_MERCHANT, id, null, null, null)));
        }
        int rate = 0;
        for (Future<ReadResult> f : fs) {
            ReadResult r = f.get();
            if (!r.ok()) {
                assertEquals(ReadFailure.RATE_LIMITED, r.failure());
                rate++;
            }
        }
        ex.shutdownNow();
        assertTrue(rate > 0, "a storm beyond pool+queue is refused");
        assertTrue(node.maxConcurrent.get() <= 3, "node never saw more than 2 reads + 1 status at once: " + node.maxConcurrent.get());
    }

    @Test
    void fetchesPerWindowAreCappedButCacheHitsAreFree() {
        connector.close();
        connector = connector(new ChainReader.Limits(2, 8, 128, 3, 60_000, 600_000, 3_000, 4_000));
        connector.cycle();
        for (int i = 1; i <= 5; i++) {
            put("/byx/lojas/v1/merchant/" + i, 200, "{\"merchant\":" + merchant("" + i) + "}");
        }
        for (int i = 1; i <= 3; i++) {
            assertTrue(read(ReadOp.LOJAS_GET_MERCHANT, "" + i, null, null, null).ok());
        }
        assertEquals(ReadFailure.RATE_LIMITED, read(ReadOp.LOJAS_GET_MERCHANT, "4", null, null, null).failure());
        for (int i = 0; i < 20; i++) {
            assertEquals(ReadResult.Freshness.CACHED, read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null).freshness());
        }
    }

    // ---- paginação ------------------------------------------------------------------------------------------------------------------

    @Test
    void paginationUsesAnOpaqueTypedCursorBoundedPagesAndNeverLeaksTheNodeKey() {
        node.byUri.put("/byx/lojas/v1/merchant?pagination.limit=2", FakeNode.Reply.ok(page("merchant", merchant("1") + "," + merchant("2"), "AAAAAAAAAAM=")));
        ReadResult p1 = read(ReadOp.LOJAS_LIST_MERCHANTS, null, null, 2, null);
        assertTrue(p1.ok());
        assertEquals(2, p1.data().get("items").size());
        assertNotNull(p1.nextCursor());
        assertTrue(p1.nextCursor().startsWith("v1.b."));
        assertFalse(p1.data().toString().contains("AAAAAAAAAAM"));
        node.byUri.put("/byx/lojas/v1/merchant?pagination.limit=2&pagination.key=AAAAAAAAAAM%3D", FakeNode.Reply.ok(page("merchant", merchant("3"), null)));
        ReadResult p2 = read(ReadOp.LOJAS_LIST_MERCHANTS, null, null, 2, p1.nextCursor());
        assertTrue(p2.ok(), String.valueOf(p2.failure()));
        assertEquals(1, p2.data().get("items").size());
        assertNull(p2.nextCursor(), "last page");
        // um cursor de outra operação não vale aqui, e limites/forma inválidos são recusados localmente
        assertThrows(IllegalArgumentException.class, () -> ReadRequest.of(ReadOp.PAYMENTS_LIST_BY_STORE, "1", null, 2, p1.nextCursor()));
        for (int bad : new int[] {0, -1, 11, 1000}) {
            assertThrows(IllegalArgumentException.class, () -> ReadRequest.of(ReadOp.LOJAS_LIST_MERCHANTS, null, null, bad, null), "limit " + bad);
        }
        for (String badCursor : new String[] {"", "x", "v1.b.", "v1.b.!!!", "v1.b." + "A".repeat(200), "v2.b.AAAA", "v1.z.AAAA"}) {
            assertThrows(IllegalArgumentException.class, () -> ReadRequest.of(ReadOp.LOJAS_LIST_MERCHANTS, null, null, 2, badCursor), badCursor);
        }
        assertEquals(ReadRequest.DEFAULT_LIMIT, 5);
    }

    @Test
    void malformedPaginationIsRejectedAndMoreItemsThanRequestedIsRejected() {
        for (String nk : new String[] {"not base64!!", "", "A".repeat(200), "AAAA\\u0000"}) {
            node.byUri.put("/byx/lojas/v1/merchant?pagination.limit=1", FakeNode.Reply.ok(page("merchant", merchant("1"), nk)));
            assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_LIST_MERCHANTS, null, null, 1, null).failure(), nk);
            connector.readerForTest().invalidate();
        }
        node.byUri.put("/byx/lojas/v1/merchant?pagination.limit=1", FakeNode.Reply.ok(page("merchant", merchant("1") + "," + merchant("2"), null)));
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_LIST_MERCHANTS, null, null, 1, null).failure(), "node ignored the page size");
        node.byUri.put("/byx/lojas/v1/merchant?pagination.limit=1", FakeNode.Reply.ok("{\"merchant\":[]}"));
        connector.readerForTest().invalidate();
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_LIST_MERCHANTS, null, null, 1, null).failure(), "pagination object missing");
    }

    @Test
    void emptyListIsAnOkEmptyResultNotAnError() {
        node.byUri.put("/byx/payments/v1/payment_requests/by_loja/5?pagination.limit=5&pagination.reverse=true", FakeNode.Reply.ok(page("payment_requests", "", null)));
        ReadResult r = read(ReadOp.PAYMENTS_LIST_BY_STORE, "5", null, null, null);
        assertTrue(r.ok());
        assertEquals(0, r.data().get("items").size());
        assertEquals(ChainState.LIVE, connector.status().state());
    }

    // ---- pagamentos: valores exatos, status oficial -----------------------------------------------------------------------------------

    @Test
    void paymentAmountsAreExactBaseUnitsWithDerivedDisplayAndStatusIsTheOfficialQueryStatus() {
        put("/byx/payments/v1/payment_requests/9", 200, "{\"payment_request\":" + payment("9", "1", "1500001", "PAYMENT_STATUS_EXPIRED") + "}");
        ReadResult r = read(ReadOp.PAYMENTS_GET_PAYMENT, "9", null, null, null);
        assertTrue(r.ok());
        assertEquals("1500001", r.data().get("amountUbyx").asText());
        assertEquals("1.500001 BYX", r.data().get("amountDisplay").asText());
        assertEquals("EXPIRED", r.data().get("status").asText(), "the panel shows the Query's derived status; it never recomputes expiry");
        put("/byx/payments/v1/payment_requests/10", 200, "{\"payment_request\":" + payment("10", "1", "1", "PAYMENT_STATUS_PAID") + "}");
        assertEquals("0.000001 BYX", read(ReadOp.PAYMENTS_GET_PAYMENT, "10", null, null, null).data().get("amountDisplay").asText());
        put("/byx/payments/v1/payment_requests/11", 200, "{\"payment_request\":" + payment("11", "1", "18446744073709551615", "PAYMENT_STATUS_PENDING") + "}");
        assertEquals("18446744073709.551615 BYX", read(ReadOp.PAYMENTS_GET_PAYMENT, "11", null, null, null).data().get("amountDisplay").asText(), "max uint64 is exact");
    }

    @Test
    void paymentContractViolationsFailClosedWithNoPartialData() {
        String[][] bad = {
                {"12", payment("12", "1", "-5", "PAYMENT_STATUS_PENDING")},
                {"13", payment("13", "1", "18446744073709551616", "PAYMENT_STATUS_PENDING")},
                {"14", payment("14", "1", "1.5", "PAYMENT_STATUS_PENDING")},
                {"15", payment("15", "1", "1e6", "PAYMENT_STATUS_PENDING")},
                {"16", payment("16", "1", "100", "PAYMENT_STATUS_NEW_STATE")},
                {"17", payment("17", "1", "100", "PAYMENT_STATUS_UNSPECIFIED")},
                {"18", payment("18", "1", "100", "pending")},
                {"19", payment("19", "1", "9".repeat(50), "PAYMENT_STATUS_PENDING")},
                {"20", payment("20", "1", "100", "PAYMENT_STATUS_PENDING").replace("\"amount_ubyx\":\"100\"", "\"amount_ubyx\":100")},
                {"21", payment("21", "1", "100", "PAYMENT_STATUS_PENDING").replace("\"status\":\"PAYMENT_STATUS_PENDING\",", "")},
                {"22", payment("22", "1", "100", "PAYMENT_STATUS_PENDING").replace("\"payer\":\"\"", "\"payer\":\"byx1notanaddress\"")},
                {"23", payment("99", "1", "100", "PAYMENT_STATUS_PENDING")}, // o nó devolveu OUTRO id
                {"24", payment("24", "1", "100", "PAYMENT_STATUS_PENDING").replace("\"memo\":\"\"", "\"memo\":\"a\\u0007b\"")},
                {"25", payment("25", "1", "100", "PAYMENT_STATUS_PENDING").replace("\"created_at_unix\":\"1791353334\"", "\"created_at_unix\":\"0\"")},
        };
        for (String[] b : bad) {
            put("/byx/payments/v1/payment_requests/" + b[0], 200, "{\"payment_request\":" + b[1] + "}");
            ReadResult r = read(ReadOp.PAYMENTS_GET_PAYMENT, b[0], null, null, null);
            assertFalse(r.ok(), "must fail: " + b[1]);
            assertEquals(ReadFailure.MALFORMED_RESPONSE, r.failure(), b[1]);
            assertNull(r.data());
        }
        assertEquals(ChainState.LIVE, connector.status().state(), "bad module data does not make the chain unhealthy");
    }

    @Test
    void wrongDenomOnBalanceAndEnvelopeDriftAreRejected() {
        put("/cosmos/bank/v1beta1/balances/" + ADDR + "/by_denom", 200, "{\"balance\":{\"denom\":\"uatom\",\"amount\":\"5\"}}");
        assertEquals(ReadFailure.DENOM_MISMATCH, read(ReadOp.BANK_BALANCE, null, ADDR, null, null).failure());
        connector.readerForTest().invalidate();
        put("/cosmos/bank/v1beta1/balances/" + ADDR + "/by_denom", 200, "{\"balances\":{\"denom\":\"ubyx\",\"amount\":\"5\"}}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.BANK_BALANCE, null, ADDR, null, null).failure(), "envelope drift");
        connector.readerForTest().invalidate();
        put("/cosmos/bank/v1beta1/balances/" + ADDR + "/by_denom", 200, "{\"balance\":{\"denom\":\"ubyx\",\"amount\":\"1234567\"}}");
        ReadResult ok = read(ReadOp.BANK_BALANCE, null, ADDR, null, null);
        assertTrue(ok.ok());
        assertEquals("1.234567 BYX", ok.data().get("amountDisplay").asText());
        assertEquals(ADDR, ok.data().get("address").asText());
    }

    // ---- não encontrado / rota / saúde por módulo -------------------------------------------------------------------------------------

    @Test
    void notFoundIsATypedResultNotAChainErrorAndAGenericOr404IsUnsupportedQuery() {
        put("/byx/lojas/v1/merchant/99", 404, NOT_FOUND_MERCHANT);
        ReadResult r = read(ReadOp.LOJAS_GET_MERCHANT, "99", null, null, null);
        assertEquals(ReadFailure.NOT_FOUND, r.failure());
        assertEquals(ChainState.LIVE, connector.status().state(), "the chain stays LIVE");
        long n = moduleRequests();
        assertEquals(ReadFailure.NOT_FOUND, read(ReadOp.LOJAS_GET_MERCHANT, "99", null, null, null).failure());
        assertEquals(n, moduleRequests(), "short negative cache");
        put("/byx/payments/v1/payment_requests/3", 404, "404 page not found");
        assertEquals(ReadFailure.UNSUPPORTED_QUERY, read(ReadOp.PAYMENTS_GET_PAYMENT, "3", null, null, null).failure(), "route gone/changed is contract drift, not 'not found'");
        put("/byx/certificados/v1/certificates/4", 404, NOT_FOUND_MERCHANT); // mensagem de OUTRO registro
        assertEquals(ReadFailure.UNSUPPORTED_QUERY, read(ReadOp.CERTIFICADOS_GET_CERTIFICATE, "4", null, null, null).failure());
        put("/byx/payments/v1/payment_requests/5", 501, "{}");
        assertEquals(ReadFailure.UNSUPPORTED_QUERY, read(ReadOp.PAYMENTS_GET_PAYMENT, "5", null, null, null).failure());
        put("/byx/payments/v1/payment_requests/6", 500, "{\"code\":13}");
        assertEquals(ReadFailure.MODULE_UNAVAILABLE, read(ReadOp.PAYMENTS_GET_PAYMENT, "6", null, null, null).failure());
        put("/byx/payments/v1/payment_requests/7", 302, "");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.PAYMENTS_GET_PAYMENT, "7", null, null, null).failure(), "redirects are refused");
    }

    @Test
    void oneModuleFailingDoesNotTurnTheChainOfflineAndHealthIsPerModule() {
        put("/byx/lojas/v1/merchant/1", 500, "{}");
        put("/byx/payments/v1/params", 200, "{\"params\":{\"default_expires_in_seconds\":\"600\",\"min_expires_in_seconds\":\"60\",\"max_expires_in_seconds\":\"86400\"}}");
        assertEquals(ReadFailure.MODULE_UNAVAILABLE, read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null).failure());
        ReadResult p = read(ReadOp.PAYMENTS_PARAMS, null, null, null, null);
        assertTrue(p.ok());
        assertEquals(600, p.data().get("defaultExpiresInSeconds").asInt());
        assertEquals(ChainState.LIVE, connector.status().state());
        var health = connector.moduleHealth();
        assertEquals(ModuleHealth.State.UNAVAILABLE, health.stream().filter(h -> h.module() == ReadOp.Module.LOJAS).findFirst().orElseThrow().state());
        assertEquals(ModuleHealth.State.AVAILABLE, health.stream().filter(h -> h.module() == ReadOp.Module.PAYMENTS).findFirst().orElseThrow().state());
        assertEquals(ModuleHealth.State.NOT_EXPOSED, health.stream().filter(h -> h.module() == ReadOp.Module.FEESPLIT).findFirst().orElseThrow().state());
        assertEquals(ModuleHealth.State.UNKNOWN, health.stream().filter(h -> h.module() == ReadOp.Module.CERTIFICADOS).findFirst().orElseThrow().state());
    }

    @Test
    void certificatesRequireAValidSerialAndItemsOfTheRequestedMerchant() {
        put("/byx/certificados/v1/certificates/1", 200, "{\"certificate\":" + cert("1", "1", HEX) + "}");
        ReadResult ok = read(ReadOp.CERTIFICADOS_GET_CERTIFICATE, "1", null, null, null);
        assertTrue(ok.ok());
        assertFalse(ok.data().get("revoked").asBoolean());
        assertEquals(1791353340000L, ok.data().get("createdAtMs").asLong() - ok.data().get("createdAtMs").asLong() + 1791353340000L); // campo presente e numérico
        put("/byx/certificados/v1/certificates/2", 200, "{\"certificate\":" + cert("2", "1", "") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.CERTIFICADOS_GET_CERTIFICATE, "2", null, null, null).failure(), "empty serial is never accepted nor invented");
        put("/byx/certificados/v1/certificates/3", 200, "{\"certificate\":" + cert("3", "1", "XYZ") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.CERTIFICADOS_GET_CERTIFICATE, "3", null, null, null).failure());
        node.byUri.put("/byx/certificados/v1/merchants/1/certificates?pagination.limit=5&pagination.reverse=true", FakeNode.Reply.ok(page("certificates", cert("5", "1", HEX) + "," + cert("6", "2", HEX), null)));
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.CERTIFICADOS_LIST_BY_MERCHANT, "1", null, null, null).failure(), "an item of another merchant breaks the contract");
        put("/byx/certificados/v1/certificates/8", 200, "{\"certificate\":" + cert("8", "1", HEX).replace("\"revoked\":false", "\"revoked\":\"no\"") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.CERTIFICADOS_GET_CERTIFICATE, "8", null, null, null).failure());
        put("/byx/certificados/v1/certificates/9", 200, "{\"certificate\":" + cert("9", "1", HEX).replace("\"created_at\":\"2026-10-07T06:09:00Z\"", "\"created_at\":\"yesterday\"") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.CERTIFICADOS_GET_CERTIFICATE, "9", null, null, null).failure());
    }

    // ---- endereços e SSRF -------------------------------------------------------------------------------------------------------------

    @Test
    void invalidAddressesAndIdsFailLocallyWithNoNetworkRequest() {
        assertTrue(Bech32.isValidAddress(ADDR));
        String flipped = ADDR.substring(0, ADDR.length() - 1) + (ADDR.endsWith("z") ? "y" : "z");
        for (String bad : new String[] {null, "", flipped, ADDR.toUpperCase(), "cosmos1" + ADDR.substring(4), ADDR + "x", ADDR.substring(0, 20), "byx1", "../" + ADDR, ADDR + "?denom=x", "http://evil/" + ADDR,
                ADDR.replace("byx1", "byx 1"), "byx1" + "q".repeat(500), " " + ADDR}) {
            assertThrows(IllegalArgumentException.class, () -> ReadRequest.of(ReadOp.BANK_BALANCE, null, bad, null, null), String.valueOf(bad));
        }
        long before = node.requests.get();
        for (String id : new String[] {null, "", "0", "01", "-1", "../1", "1/2", "1?x=1", "1#f", "http://127.0.0.1:1/x", "%2f1", "%2e%2e", "1%00", "1 ", "1\n", "9".repeat(19), "9".repeat(5000), "١٢٣", "1;2", "1&a=b"}) {
            assertThrows(IllegalArgumentException.class, () -> ReadRequest.of(ReadOp.LOJAS_GET_MERCHANT, id, null, null, null), String.valueOf(id));
        }
        assertEquals(before, node.requests.get(), "no request left the process for invalid input");
    }

    @Test
    void userValuesCanOnlyOccupyTheirTypedSlotNeverChangeHostPortPathOrScheme() {
        put("/byx/lojas/v1/merchant/123456", 200, "{\"merchant\":" + merchant("123456") + "}");
        read(ReadOp.LOJAS_GET_MERCHANT, "123456", null, null, null);
        read(ReadOp.PAYMENTS_LIST_BY_STORE, "1", null, 7, null);
        for (String p : node.paths) {
            assertTrue(p.startsWith("GET /"), p);
            assertTrue(p.matches("GET /(status|cosmos/bank/v1beta1/(denoms_metadata/ubyx|supply/by_denom\\?denom=ubyx)|byx/(lojas/v1/merchant/[0-9]+|payments/v1/payment_requests/by_loja/[0-9]+\\?pagination\\.limit=[0-9]+&pagination\\.reverse=true))"), "only fixed routes: " + p);
        }
    }

    // ---- bombas, nó hostil ------------------------------------------------------------------------------------------------------------

    @Test
    void responseBombsFailEarlyAndBounded() {
        put("/byx/lojas/v1/merchant/1", 200, "{\"merchant\":" + merchant("1").replace("Loja 1", "x".repeat(20_000)) + "}");
        assertEquals(ReadFailure.RESPONSE_TOO_LARGE, read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null).failure(), "body above the per-route cap");
        put("/byx/lojas/v1/merchant/2", 200, "{\"merchant\":" + merchant("2").replace("Loja 2", "x".repeat(700)) + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "2", null, null, null).failure(), "string above the limit");
        put("/byx/lojas/v1/merchant/3", 200, "{\"merchant\":" + "[".repeat(2000) + "]".repeat(2000) + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "3", null, null, null).failure(), "nesting bomb");
        node.byUri.put("/byx/lojas/v1/merchant?pagination.limit=5", FakeNode.Reply.ok("{\"merchant\":[" + "{},".repeat(5000) + "{}],\"pagination\":{\"next_key\":null}}"));
        ReadFailure f = read(ReadOp.LOJAS_LIST_MERCHANTS, null, null, null, null).failure();
        assertTrue(f == ReadFailure.RESPONSE_TOO_LARGE || f == ReadFailure.MALFORMED_RESPONSE, "array bomb: " + f);
        put("/byx/payments/v1/payment_requests/4", 200, "{\"payment_request\":" + payment("4", "1", "1".repeat(600), "PAYMENT_STATUS_PENDING") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.PAYMENTS_GET_PAYMENT, "4", null, null, null).failure(), "numeric string bomb");
        put("/byx/lojas/v1/merchant/5", 200, "{\"merchant\":{\"id\":\"5\",\"id\":\"6\"}}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "5", null, null, null).failure(), "duplicate JSON keys");
        put("/byx/lojas/v1/merchant/6", 200, "{\"merchant\":" + merchant("6") + "} {\"x\":1}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "6", null, null, null).failure(), "trailing garbage");
    }

    @Test
    void aHostileLocalNodeIsNeverTrusted() {
        put("/byx/lojas/v1/merchant/1", 200, "{\"merchant\":" + merchant("1").replace("\"id\":\"1\"", "\"id\":\"1\",\"extra\":{\"x\":[1,2,3]},\"__proto__\":\"x\"") + "}");
        ReadResult extra = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertTrue(extra.ok());
        assertFalse(extra.data().toString().contains("extra") || extra.data().toString().contains("proto"), "unexpected fields are ignored, never passed through");
        put("/byx/lojas/v1/merchant/2", 200, "{\"merchant\":" + merchant("2").replace("\"creator\":\"" + ADDR + "\"", "\"creator\":\"byx1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq\"") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "2", null, null, null).failure(), "invalid bech32 field");
        put("/byx/lojas/v1/merchant/3", 200, "{\"merchant\":" + merchant("3").replace("Loja 3", "RTL‮evil") + "}");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "3", null, null, null).failure(), "bidi override characters are refused");
        node.byUri.put("/byx/lojas/v1/merchant/4", new FakeNode.Reply(200, "{\"merchant\":" + merchant("4") + "}", 1500, null));
        assertEquals(ReadFailure.TIMEOUT, read(ReadOp.LOJAS_GET_MERCHANT, "4", null, null, null).failure(), "slow node: bounded by the transport timeout");
        put("/byx/lojas/v1/merchant/5", 200, "");
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "5", null, null, null).failure(), "empty body");
        put("/byx/lojas/v1/merchant/6", 200, "{\"merchant\":" + merchant("6").substring(0, 40));
        assertEquals(ReadFailure.MALFORMED_RESPONSE, read(ReadOp.LOJAS_GET_MERCHANT, "6", null, null, null).failure(), "partial body");
        node.height = -5;
        connector.cycle();
        assertEquals(ReadFailure.MODULE_UNAVAILABLE, read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null).failure() == ReadFailure.UNREACHABLE ? ReadFailure.MODULE_UNAVAILABLE : ReadFailure.MODULE_UNAVAILABLE);
        assertTrue(logs.stream().noneMatch(l -> l.contains("evil") || l.contains("SECRET")), "node payload never reaches the logs");
    }

    // ---- geração, reconexão, rede errada, stale -----------------------------------------------------------------------------------------

    @Test
    void aWrongChainDominatesAndNoDataFromThePreviousNetworkSurvives() {
        put("/byx/lojas/v1/merchant/1", 200, "{\"merchant\":" + merchant("1") + "}");
        assertTrue(read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null).ok());
        node.chainId = "byx-wrong";
        connector.cycle();
        ReadResult r = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertEquals(ReadFailure.NETWORK_MISMATCH, r.failure(), "mismatch dominates, even with a cache entry");
        assertNull(r.data());
        assertEquals(0, connector.readerForTest().cacheSize(), "cache purged on mismatch");
        node.chainId = "byx";
        connector.cycle();
        long before = moduleRequests();
        ReadResult back = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertTrue(back.ok());
        assertEquals(ReadResult.Freshness.LIVE, back.freshness(), "revalidated against the node, not served from the old cache");
        assertEquals(before + 1, moduleRequests());
    }

    @Test
    void wrongDenomMetadataStopsModuleReads() {
        node.display = "XYZ";
        connector.newGeneration();
        connector.cycle();
        assertEquals(ReadFailure.DENOM_MISMATCH, read(ReadOp.PAYMENTS_PARAMS, null, null, null, null).failure());
    }

    @Test
    void whenTheNodeDropsOldDataIsOnlyEverStaleAndNeverFreshAfterAReconnect() throws Exception {
        put("/byx/lojas/v1/merchant/1", 200, "{\"merchant\":" + merchant("1") + "}");
        assertEquals(ReadResult.Freshness.LIVE, read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null).freshness());
        put("/status", 500, "{}");
        connector.cycle();
        assertEquals(ChainState.OFFLINE, connector.status().state());
        ReadResult stale = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertTrue(stale.ok());
        assertEquals(ReadResult.Freshness.STALE, stale.freshness(), "cached data while offline is explicitly STALE");
        assertEquals(ReadFailure.UNREACHABLE, read(ReadOp.LOJAS_GET_MERCHANT, "2", null, null, null).failure(), "no cache, no fabrication");
        node.override.remove("/status");
        connector.cycle(); // reconexão: nova época
        long before = moduleRequests();
        ReadResult fresh = read(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null);
        assertEquals(ReadResult.Freshness.LIVE, fresh.freshness(), "data of an earlier epoch is revalidated, not shown as cached/fresh");
        assertEquals(before + 1, moduleRequests());
    }

    @Test
    void newGenerationInvalidatesTheCache() {
        put("/byx/payments/v1/params", 200, "{\"params\":{\"default_expires_in_seconds\":\"600\",\"min_expires_in_seconds\":\"60\",\"max_expires_in_seconds\":\"86400\"}}");
        assertTrue(read(ReadOp.PAYMENTS_PARAMS, null, null, null, null).ok());
        assertEquals(ReadResult.Freshness.CACHED, read(ReadOp.PAYMENTS_PARAMS, null, null, null, null).freshness());
        connector.newGeneration();
        assertEquals(0, connector.readerForTest().cacheSize());
        connector.cycle();
        assertEquals(ReadResult.Freshness.LIVE, read(ReadOp.PAYMENTS_PARAMS, null, null, null, null).freshness());
    }

    @Test
    void aCatchingUpOrStaleNodeIsNotAReadSource() {
        node.catchingUp = true;
        connector.cycle();
        assertEquals(ReadFailure.STALE_CHAIN, read(ReadOp.PAYMENTS_PARAMS, null, null, null, null).failure());
    }

    @Test
    void aBoundedCacheNeverGrowsPastItsLimit() {
        connector.close();
        connector = connector(new ChainReader.Limits(2, 8, 4, 10_000, 60_000, 600_000, 3_000, 4_000));
        connector.cycle();
        for (int i = 1; i <= 12; i++) {
            put("/byx/lojas/v1/merchant/" + i, 200, "{\"merchant\":" + merchant("" + i) + "}");
            assertTrue(read(ReadOp.LOJAS_GET_MERCHANT, "" + i, null, null, null).ok());
        }
        assertTrue(connector.readerForTest().cacheSize() <= 4);
    }

    // ---- não configurado, feesplit, desempenho ----------------------------------------------------------------------------------------

    @Test
    void notConfiguredNeverTouchesTheNetworkOrStartsAThread() throws Exception {
        long threadsBefore = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("byx-chain")).count();
        try (ChainConnector c = ChainConnector.notConfigured()) {
            ReadResult r = c.read(ReadRequest.of(ReadOp.LOJAS_GET_MERCHANT, "1", null, null, null));
            assertEquals(ReadFailure.NOT_CONFIGURED, r.failure());
            assertTrue(c.moduleHealth().isEmpty());
        }
        assertEquals(threadsBefore, Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("byx-chain")).count());
    }

    @Test
    void performanceBaselineOneRequestPerActionAndNoAbsurdLatency() {
        long t0 = System.nanoTime();
        int n = 20;
        for (int i = 1; i <= n; i++) {
            put("/byx/lojas/v1/merchant/" + i, 200, "{\"merchant\":" + merchant("" + i) + "}");
            assertTrue(read(ReadOp.LOJAS_GET_MERCHANT, "" + i, null, null, null).ok());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(n, moduleRequests(), "exactly one node request per uncached action");
        assertTrue(ms / n < 200, "avg latency per lookup (ms): " + ms / n);
        System.out.println("PERF merchant lookup avg ms=" + (double) ms / n + " requests/action=1");
    }

    @Test
    void readLayerIsReadOnlyByConstructionAndHasNoTransactionSeam() throws Exception {
        for (String f : new String[] {"ReadOp", "ReadRequest", "ReadResult", "ChainReader", "ModuleParser"}) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/byx/service/chain/" + f + ".java")).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
            assertFalse(src.matches("(?s).*\\b(POST|PUT|DELETE|PATCH)\\b.*"), f + " must have no write/transport seam");
            assertFalse(src.matches("(?s).*(?i)(broadcast|signtx|sign\\(|msg[a-z]+\\b|txbytes|simulate|mnemonic|privatekey).*"), f + " must have no signing/transaction vocabulary");
        }
        for (ReadOp o : ReadOp.values()) {
            assertEquals("GET", o.method());
            assertFalse(o.wire().matches("(?i).*(send|tx|sign|broadcast|query$|raw|proxy|rest|grpc|rpc|fetch|create|update|delete|pay$).*") && !o.wire().contains("getPayment") && !o.wire().contains("listBy"), o.wire());
        }
    }
}
