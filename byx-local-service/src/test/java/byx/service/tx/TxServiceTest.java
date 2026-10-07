package byx.service.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.tx.TxFx.Broadcast;
import byx.service.tx.TxPorts.Decision;
import byx.service.tx.TxPorts.RawTxStatus;
import byx.service.tx.TxValues.TxQuoteId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Pipeline completo SINTÉTICO: assinante, transporte, sessões e política são dublês; nenhuma rede, nenhuma chave real. */
class TxServiceTest {
    private final TxFx fx = new TxFx();

    private static TxError error(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(TxException.class, e).error();
    }

    private TxService.Status confirm(int op, TxQuote q) {
        return fx.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(op), q.id());
    }

    // ---- preparar --------------------------------------------------------------------------------------------------------------------

    @Test
    void validPrepareBindsEveryFieldInTheQuote() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        assertEquals(TxFx.intent("hello", 1_000_000).digest(), q.intentDigest());
        assertEquals(TxFx.CHAIN, q.chainId());
        assertEquals(7, q.chainGeneration().value());
        assertEquals(TxFx.SENDER, q.sender().value());
        assertEquals(TxFx.RECIPIENT, q.recipient().value());
        assertEquals("1000000", q.amount().value().toString());
        assertEquals(TxFx.intent("hello", 1).memo().digest(), q.memoDigest());
        assertEquals(12, q.accountNumber().value());
        assertEquals(5, q.sequence().value());
        assertEquals("100000", q.simulatedGas().value().toString());
        assertEquals("110000", q.adjustedGas().value().toString());
        assertEquals(110_000, q.gasLimit().value());
        assertEquals("0.025", q.gasPrice().canonical());
        assertEquals("2750", q.fee().value().toString()); // ceil(110000 * 0.025)
        assertEquals("50000", q.maximumFee().value().toString());
        assertEquals("1002750", q.totalDebit().toString());
        assertEquals("DRAFT_TESTNET_POLICY", q.policyName());
        assertEquals("DRAFT_TESTNET_V1", q.policyVersion());
        assertEquals(TxFx.DraftTestnetPolicy.TTL.toMillis(), q.expiresAtMs() - q.createdAtMs());
        assertEquals(TxState.AWAITING_CONFIRMATION, fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
    }

    @Test
    void invalidInputsNeverReachTheTransport() {
        assertEquals(TxError.INVALID_ADDRESS, error(() -> new TxValues.BankAddress("byx1nope")));
        assertEquals(TxError.INVALID_AMOUNT, error(() -> TxValues.UbyxAmount.parse("0")));
        assertEquals(TxError.INVALID_AMOUNT, error(() -> TxValues.UbyxAmount.parse("-1")));
        assertEquals(TxError.MEMO_TOO_LARGE, error(() -> new TxValues.Memo("m".repeat(300))));
        assertEquals(TxError.INVALID_FEE_MODE, error(() -> FeeMode.parse("TURBO")));
        assertEquals(0, fx.transport.accountCalls.get());
    }

    @Test
    void gasZeroNegativeHugeAndMalformedFailClosedWithNoQuote() {
        for (String bad : new String[] {"0", "-5", "99999999999999999999", "abc", "", null}) {
            TxFx f = new TxFx();
            f.transport.gasUsed = bad;
            assertEquals(TxError.GAS_ESTIMATE_INVALID, error(() -> f.prepare(1, FeeMode.LOW)), String.valueOf(bad));
            assertEquals(TxState.FAILED, f.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
        }
        fx.transport.simulateNull = true;
        assertEquals(TxError.SIMULATION_FAILED, error(() -> fx.prepare(2, FeeMode.LOW)));
        TxFx f2 = new TxFx();
        f2.transport.simulateFails = true;
        assertEquals(TxError.SIMULATION_FAILED, error(() -> f2.prepare(3, FeeMode.LOW)));
    }

    @Test
    void maliciousAccountResultsFailClosed() {
        String[][] cases = {{"sequence", "-1"}, {"sequence", "99999999999999999999"}, {"accountNumber", "-3"}, {"accountNumber", "9999999999999999999999"},
                {"sequence", "1e3"}, {"spendable", "-1"}, {"spendable", "abc"}};
        for (String[] c : cases) {
            TxFx f = new TxFx();
            switch (c[0]) {
                case "sequence" -> f.transport.sequence = c[1];
                case "accountNumber" -> f.transport.accountNumber = c[1];
                default -> f.transport.spendable = c[1];
            }
            assertEquals(TxError.ACCOUNT_INVALID, error(() -> f.prepare(1, FeeMode.LOW)), c[0] + "=" + c[1]);
        }
        TxFx wrongAddress = new TxFx();
        wrongAddress.transport.address = TxFx.address(55);
        assertEquals(TxError.ACCOUNT_INVALID, error(() -> wrongAddress.prepare(1, FeeMode.LOW)));
        TxFx wrongChain = new TxFx();
        wrongChain.transport.chainId = "another-chain";
        assertEquals(TxError.CHAIN_CHANGED, error(() -> wrongChain.prepare(1, FeeMode.LOW)));
        TxFx malformed = new TxFx();
        malformed.transport.accountNull = true;
        assertEquals(TxError.ACCOUNT_INVALID, error(() -> malformed.prepare(1, FeeMode.LOW)));
        TxFx down = new TxFx();
        down.transport.accountFails = true;
        assertEquals(TxError.ACCOUNT_INVALID, error(() -> down.prepare(1, FeeMode.LOW)));
    }

    @Test
    void feeBudgetAndGasCeilingAreEnforcedBeforeSigning() {
        fx.transport.gasUsed = "1800000";
        assertEquals(TxError.MAX_FEE_EXCEEDED, error(() -> fx.prepare(1, FeeMode.HIGH)));
        fx.transport.gasUsed = "2000000";
        assertEquals(TxError.MAX_GAS_EXCEEDED, error(() -> fx.prepare(2, FeeMode.LOW)));
        assertEquals(0, fx.signer.signed.get());
        assertEquals(0, fx.transport.broadcasts.size());
    }

    @Test
    void insufficientFundsIsReportedAtQuoteTime() {
        fx.transport.spendable = "1000000"; // cobre a quantia, não a quantia + taxa
        assertEquals(TxError.INSUFFICIENT_FUNDS, error(() -> fx.prepare(1, FeeMode.STANDARD)));
    }

    @Test
    void signerOrKeyUnavailableStopsAtPrepare() {
        fx.signer.available = false;
        assertEquals(TxError.SIGNER_UNAVAILABLE, error(() -> fx.prepare(1, FeeMode.LOW)));
        TxFx noKey = new TxFx();
        noKey.keys.present = false;
        assertEquals(TxError.SIGNER_UNAVAILABLE, error(() -> noKey.prepare(1, FeeMode.LOW)));
    }

    @Test
    void repeatPrepareIsIdempotentAndModeChangeReplacesTheQuote() {
        TxQuote a = fx.prepare(1, FeeMode.STANDARD);
        int sims = fx.transport.simulateCalls.get();
        TxQuote again = fx.prepare(1, FeeMode.STANDARD);
        assertSame(a, again, "same operation, same request: the same quote, no new simulation");
        assertEquals(sims, fx.transport.simulateCalls.get());
        TxQuote high = fx.prepare(1, FeeMode.HIGH);
        assertNotEquals(a.id(), high.id());
        assertEquals("0.05", high.gasPrice().canonical());
        // a cotação anterior não pode ser confirmada
        assertEquals(TxError.QUOTE_NOT_FOUND, error(() -> confirm(1, a)));
        assertEquals(TxState.AWAITING_CONFIRMATION, confirmable(1, high));
        assertTrue(fx.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), high.id()).state().executionStarted());
    }

    private TxState confirmable(int op, TxQuote q) {
        return fx.svc.quote(TxFx.PEER, TxFx.TOKEN, TxFx.op(op), q.id()).state();
    }

    @Test
    void memoChangeProducesANewQuoteWithAnotherDigest() {
        TxQuote a = fx.prepare(1, FeeMode.STANDARD);
        TxQuote b = fx.svc.prepareBankSend(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), TxFx.intent("changed", 1_000_000), FeeMode.STANDARD);
        assertNotEquals(a.intentDigest(), b.intentDigest());
        assertNotEquals(a.memoDigest(), b.memoDigest());
        assertEquals(TxError.QUOTE_NOT_FOUND, error(() -> confirm(1, a)));
    }

    // ---- confirmar -------------------------------------------------------------------------------------------------------------------

    @Test
    void fullFakePipelineSucceedsAndRecordsTheHashBeforeBroadcasting() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        fx.transport.onBroadcast = tx -> assertTrue(fx.journal.entries().stream().anyMatch(e -> tx.txHash().equals(e.txHash()) && e.state() == TxState.BROADCASTING),
                "the tx hash must be in the journal BEFORE the broadcast is attempted");
        TxService.Status st = confirm(1, q);
        assertEquals(TxState.SUBMITTED, st.state());
        assertNotNull(st.txHash());
        assertEquals(1, fx.signer.signed.get());
        assertEquals(1, fx.transport.broadcasts.size());
        assertEquals(List.of(TxState.NEW, TxState.VALIDATED, TxState.SIMULATING, TxState.QUOTED, TxState.AWAITING_CONFIRMATION, TxState.CONFIRMED, TxState.SIGNING,
                TxState.BROADCASTING, TxState.SUBMITTED), fx.svc.historyOf("sess-a", TxFx.op(1).value()));
        // o sign request usa os valores da cotação guardada (nunca algo vindo do painel na confirmação)
        assertEquals(q, fx.signer.requests.get(0).quote());
        fx.transport.statuses.put(st.txHash(), new RawTxStatus(true, "0", st.txHash()));
        assertEquals(TxState.CONFIRMED_ON_CHAIN, fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
    }

    @Test
    void doubleConfirmAndRapidClicksExecuteExactlyOnce() throws Exception {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        TxService.Status first = confirm(1, q);
        TxService.Status second = confirm(1, q);
        assertEquals(first.txHash(), second.txHash());
        assertEquals(1, fx.signer.signed.get());
        assertEquals(1, fx.transport.broadcasts.size());
        // concorrência: 16 confirmações simultâneas de outra operação -> uma execução
        TxQuote q2 = fx.prepare(2, FeeMode.STANDARD);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TxService.Status>> all = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            all.add(pool.submit(() -> {
                go.await();
                return confirm(2, q2);
            }));
        }
        go.countDown();
        for (Future<TxService.Status> f : all) {
            assertTrue(f.get(5, TimeUnit.SECONDS).state().executionStarted());
        }
        pool.shutdownNow();
        assertEquals(2, fx.signer.signed.get(), "one signature per operation, never two");
        assertEquals(2, fx.transport.broadcasts.size());
    }

    @Test
    void expiredQuoteRequiresAReQuoteAndIsNeverSigned() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        fx.clock.advance(TxFx.DraftTestnetPolicy.TTL.plusSeconds(1));
        assertEquals(TxError.QUOTE_EXPIRED, error(() -> confirm(1, q)));
        assertEquals(0, fx.signer.signed.get());
        assertEquals(TxError.QUOTE_EXPIRED, error(() -> confirm(1, q)), "stays expired");
        TxQuote fresh = fx.prepare(1, FeeMode.STANDARD);
        assertNotEquals(q.id(), fresh.id());
        assertTrue(confirm(1, fresh).state().executionStarted());
    }

    @Test
    void sequenceChangeBeforeExecutionIsStaleAndNeverResigned() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        fx.transport.sequence = "6";
        assertEquals(TxError.STALE_SEQUENCE, error(() -> confirm(1, q)));
        assertEquals(0, fx.signer.signed.get());
        TxQuote fresh = fx.prepare(1, FeeMode.STANDARD);
        assertEquals(6, fresh.sequence().value());
        assertTrue(confirm(1, fresh).state().executionStarted());
    }

    @Test
    void chainGenerationOrIdentityChangeInvalidatesTheQuote() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        fx.chain.generation = 8;
        assertEquals(TxError.CHAIN_CHANGED, error(() -> confirm(1, q)));
        TxQuote q2 = fx.prepare(1, FeeMode.STANDARD);
        fx.chain.live = false;
        assertEquals(TxError.CHAIN_CHANGED, error(() -> confirm(1, q2)));
        fx.chain.live = true;
        TxQuote q3 = fx.prepare(1, FeeMode.STANDARD);
        fx.chain.chainId = "other-net";
        assertEquals(TxError.CHAIN_CHANGED, error(() -> confirm(1, q3)));
        assertEquals(0, fx.signer.signed.get());
    }

    @Test
    void policyVersionChangeInvalidatesTheQuote() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        fx.policy.version = "DRAFT_TESTNET_V2";
        assertEquals(TxError.QUOTE_MISMATCH, error(() -> confirm(1, q)));
        assertEquals(0, fx.signer.signed.get());
    }

    @Test
    void cancelBeforeConfirmMakesTheQuoteUnusable() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        assertEquals(TxState.EXPIRED, fx.svc.cancel(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
        assertEquals(TxError.QUOTE_EXPIRED, error(() -> confirm(1, q)));
        assertEquals(0, fx.signer.signed.get());
    }

    @Test
    void signerFailureAndFakeBroadcastFailuresAreClassified() {
        TxFx s = new TxFx();
        TxQuote q = s.prepare(1, FeeMode.STANDARD);
        s.signer.failSigning = true;
        TxService.Status st = s.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), q.id());
        assertEquals(TxState.FAILED, st.state());
        assertEquals(TxError.SIGNING_FAILED, st.error());
        assertEquals(0, s.transport.broadcasts.size());

        for (String[] c : new String[][] {{"13", "FEE_TOO_LOW"}, {"5", "INSUFFICIENT_FUNDS"}, {"11", "OUT_OF_GAS"}, {"32", "STALE_SEQUENCE"}, {"99", "BROADCAST_FAILED"}}) {
            TxFx b = new TxFx();
            TxQuote bq = b.prepare(1, FeeMode.STANDARD);
            b.transport.broadcastMode = Broadcast.REJECT;
            b.transport.rejectCode = c[0];
            TxService.Status r = b.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), bq.id());
            assertEquals(TxState.FAILED, r.state());
            assertEquals(TxError.valueOf(c[1]), r.error(), c[0]);
            assertEquals(1, b.transport.broadcasts.size());
        }
    }

    @Test
    void unknownBroadcastOutcomeIsNeverResubmittedAndIsResolvedByHash() {
        for (Broadcast mode : new Broadcast[] {Broadcast.UNKNOWN, Broadcast.MALFORMED, Broadcast.WRONG_HASH, Broadcast.UNEXPECTED_EXCEPTION}) {
            TxFx u = new TxFx();
            TxQuote q = u.prepare(1, FeeMode.STANDARD);
            u.transport.broadcastMode = mode;
            TxService.Status st = u.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), q.id());
            assertEquals(TxState.UNKNOWN_OUTCOME, st.state(), mode.name());
            assertEquals(TxError.UNKNOWN_OUTCOME, st.error());
            assertNotNull(st.txHash(), "the hash was computed and persisted before broadcasting");
            // repetir confirm e consultar status NÃO retransmite
            u.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), q.id());
            u.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1));
            assertEquals(1, u.transport.broadcasts.size(), "no blind retry");
            assertEquals(1, u.signer.signed.get());
            // consulta pelo hash: ainda não encontrada -> segue desconhecido
            assertEquals(TxState.UNKNOWN_OUTCOME, u.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
            u.transport.statusUnavailable = true;
            assertEquals(TxState.UNKNOWN_OUTCOME, u.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
            u.transport.statusUnavailable = false;
            u.transport.statuses.put(st.txHash(), new RawTxStatus(true, "0", st.txHash()));
            assertEquals(TxState.CONFIRMED_ON_CHAIN, u.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state());
            assertEquals(1, u.transport.broadcasts.size());
        }
    }

    @Test
    void anOnChainFailureFoundByHashBecomesFailed() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        TxService.Status st = confirm(1, q);
        fx.transport.statuses.put(st.txHash(), new RawTxStatus(true, "11", st.txHash()));
        TxService.Status after = fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1));
        assertEquals(TxState.FAILED, after.state());
        assertEquals(TxError.OUT_OF_GAS, after.error());
    }

    // ---- sessão, propriedade, peer, autorização ---------------------------------------------------------------------------------------

    @Test
    void logoutOrRevocationMakesUnexecutedQuotesUnusable() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        fx.sessions.logout(TxFx.TOKEN);
        assertEquals(TxError.UNAUTHORIZED, error(() -> confirm(1, q)));
        assertEquals(TxError.UNAUTHORIZED, error(() -> fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1))));
        fx.svc.sessionEnded("sess-a");
        assertEquals(0, fx.signer.signed.get());
    }

    @Test
    void anotherSessionOrPeerCannotSeeOrExecuteTheQuote() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        assertEquals(TxError.QUOTE_NOT_FOUND, error(() -> fx.svc.confirm(TxFx.OTHER_PEER, TxFx.TOKEN_B, TxFx.op(1), q.id())));
        assertEquals(TxError.QUOTE_NOT_FOUND, error(() -> fx.svc.quote(TxFx.OTHER_PEER, TxFx.TOKEN_B, TxFx.op(1), q.id())));
        assertEquals(TxError.UNAUTHORIZED, error(() -> fx.svc.confirm(TxFx.OTHER_PEER, TxFx.TOKEN, TxFx.op(1), q.id())), "right token, wrong peer: the session does not resolve");
        assertEquals(TxError.QUOTE_NOT_FOUND, error(() -> fx.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), new TxQuoteId("f".repeat(32)))));
        assertEquals(0, fx.signer.signed.get());
    }

    @Test
    void authorizationPolicyIsConsultedAtPrepareAndAtConfirm() {
        fx.decision = Decision.DENY;
        assertEquals(TxError.UNAUTHORIZED, error(() -> fx.prepare(1, FeeMode.LOW)));
        fx.decision = Decision.REQUIRE_MFA;
        assertEquals(TxError.MFA_REQUIRED, error(() -> fx.prepare(1, FeeMode.LOW)));
        fx.decision = Decision.ALLOW;
        TxQuote q = fx.prepare(1, FeeMode.LOW);
        fx.decision = Decision.REQUIRE_ELEVATION; // mudou entre cotar e confirmar
        assertEquals(TxError.ELEVATION_REQUIRED, error(() -> confirm(1, q)));
        fx.decision = Decision.ALLOW;
        assertTrue(confirm(1, q).state().executionStarted(), "MFA/elevation requirements do not burn the quote");
    }

    @Test
    void masterGateAndPolicyDisableEverythingEvenWithAValidSession() {
        TxQuote q = fx.prepare(1, FeeMode.LOW);
        fx.gateOn = false;
        assertEquals(TxError.TX_DISABLED, error(() -> fx.prepare(2, FeeMode.LOW)));
        assertEquals(TxError.TX_DISABLED, error(() -> confirm(1, q)));
        assertEquals(TxError.TX_DISABLED, error(() -> fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1))));
        TxService prod = TxService.disabled(fx.sessions, fx.chain, fx.clock);
        assertFalse(prod.enabled());
        assertEquals(TxError.TX_DISABLED, error(() -> prod.prepareBankSend(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), TxFx.intent("x", 5), FeeMode.LOW)));
        assertEquals(TxError.TX_DISABLED, error(() -> prod.cancel(TxFx.PEER, TxFx.TOKEN, TxFx.op(1))));
        assertFalse(prod.signerAvailable());
        assertEquals("ABSENT", prod.transportName());
        assertEquals("TX_DISABLED", prod.policyName());
        assertFalse(prod.gate().mutationsAllowed());
    }

    @Test
    void stateMachineRejectsEveryIllegalTransition() {
        for (TxState from : TxState.values()) {
            for (TxState to : TxState.values()) {
                if (!from.canGoTo(to)) {
                    assertEquals(TxError.QUOTE_MISMATCH, assertThrows(TxException.class, () -> from.require(to)).error(), from + "->" + to);
                }
            }
        }
        assertFalse(TxState.CONFIRMED_ON_CHAIN.canGoTo(TxState.FAILED));
        assertFalse(TxState.SUBMITTED.canGoTo(TxState.AWAITING_CONFIRMATION));
        assertFalse(TxState.UNKNOWN_OUTCOME.canGoTo(TxState.BROADCASTING), "an unknown outcome is never re-broadcast");
        assertTrue(TxState.AWAITING_CONFIRMATION.canGoTo(TxState.CONFIRMED));
    }

    @Test
    void afterConfirmationTheOperationIsFinalAndCannotBeRequoted() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        confirm(1, q);
        assertEquals(TxError.QUOTE_MISMATCH, error(() -> fx.prepare(1, FeeMode.HIGH)));
        assertEquals(TxError.QUOTE_MISMATCH, error(() -> fx.svc.cancel(TxFx.PEER, TxFx.TOKEN, TxFx.op(1))));
    }

    @Test
    void liveOperationsPerSessionAreBounded() {
        for (int i = 0; i < TxService.MAX_LIVE_OPERATIONS_PER_SESSION; i++) {
            fx.prepare(100 + i, FeeMode.LOW);
        }
        assertEquals(TxError.TOO_MANY_OPERATIONS, error(() -> fx.prepare(999, FeeMode.LOW)));
        fx.svc.cancel(TxFx.PEER, TxFx.TOKEN, TxFx.op(100));
        fx.prepare(999, FeeMode.LOW);
    }

    @Test
    void auditNeverCarriesFinancialOrSecretData() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        TxService.Status st = confirm(1, q);
        String audit = fx.allAudit();
        assertFalse(audit.contains(TxFx.RECIPIENT) || audit.contains(TxFx.SENDER), "no addresses");
        assertFalse(audit.contains("1000000") || audit.contains("hello"), "no amount, no memo");
        assertFalse(audit.contains(st.txHash()), "no tx hash");
        assertFalse(audit.contains(TxFx.TOKEN), "no session token");
        for (String t : new String[] {"TX_PREPARE", "TX_QUOTED", "TX_CONFIRM", "TX_SIGN_REQUEST", "TX_BROADCAST_REQUEST", "TX_SUBMITTED"}) {
            assertTrue(audit.contains(t), t);
        }
        TxFx f = new TxFx();
        TxQuote fq = f.prepare(1, FeeMode.STANDARD);
        f.transport.broadcastMode = Broadcast.UNKNOWN;
        f.svc.confirm(TxFx.PEER, TxFx.TOKEN, TxFx.op(1), fq.id());
        assertTrue(f.allAudit().contains("TX_UNKNOWN_OUTCOME"));
        TxFx g = new TxFx();
        g.transport.gasUsed = "0";
        assertThrows(TxException.class, () -> g.prepare(1, FeeMode.LOW));
        assertTrue(g.allAudit().contains("TX_FAILED"));
    }

    @Test
    void journalNeverHoldsKeysOrSignedBytes() {
        TxQuote q = fx.prepare(1, FeeMode.STANDARD);
        confirm(1, q);
        String all = fx.journal.entries().toString();
        assertFalse(all.contains("SignedTx") || all.contains("BYX-TX-SIGNDOC"), all);
        assertNull(fx.journal.entries().get(0).txHash(), "no hash before signing");
        assertTrue(fx.journal.entries().stream().anyMatch(e -> e.txHash() != null));
    }

    @Test
    void signRequestsCanOnlyBeBuiltByTheServicePackageNotByCallers() throws Exception {
        // construtor package-private: nenhum chamador fora de byx.service.tx pode fabricar um pedido de assinatura
        var ctor = TxPorts.TxSignRequest.class.getDeclaredConstructors()[0];
        assertFalse(java.lang.reflect.Modifier.isPublic(ctor.getModifiers()));
        assertFalse(java.lang.reflect.Modifier.isProtected(ctor.getModifiers()));
    }

    @Test
    void quotesAreImmutableRecordsWithNoSetters() {
        assertTrue(TxQuote.class.isRecord());
        assertEquals(0, java.util.Arrays.stream(TxQuote.class.getMethods()).filter(m -> m.getName().startsWith("set")).count());
        assertEquals(Duration.ofSeconds(60), TxFx.DraftTestnetPolicy.TTL);
    }

    @Test
    void terminalOperationsArePrunedAfterRetentionAndLiveOnesAreNot() {
        TxQuote live = fx.prepare(1, FeeMode.LOW);
        fx.prepare(2, FeeMode.LOW);
        fx.svc.cancel(TxFx.PEER, TxFx.TOKEN, TxFx.op(2));
        fx.clock.advance(Duration.ofMinutes(61));
        fx.prepare(3, FeeMode.LOW); // dispara a poda
        assertEquals(TxError.QUOTE_NOT_FOUND, error(() -> fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(2))), "old terminal operation is gone");
        assertEquals(TxState.EXPIRED, fx.svc.status(TxFx.PEER, TxFx.TOKEN, TxFx.op(1)).state(), "the unconfirmed quote simply expired by TTL, still known");
        assertNotNull(live);
    }

    @Test
    void fakePrepareAndQuoteAreCheap() {
        long t0 = System.nanoTime();
        for (int i = 0; i < 25; i++) {
            fx.prepare(1000 + i, FeeMode.STANDARD);
            fx.svc.cancel(TxFx.PEER, TxFx.TOKEN, TxFx.op(1000 + i));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 1500, "25 fake prepares took " + ms + " ms: the pipeline does no heavy work (and it never runs on the FX thread)");
    }
}
