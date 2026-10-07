package panel.txview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.motion.MotionService;

/** O Lab é só apresentação: pede ao serviço (dublê), exibe a cotação exata, bloqueia clique duplo e confirmação com cotação vencida/alterada. */
class TransactionLabTest {
    private static final JsonMapper JSON = new JsonMapper();

    private static class FakeService implements TxLabService {
        final List<String> calls = new ArrayList<>();
        volatile String prepareCode; // null = ok
        volatile String state = "AWAITING_CONFIRMATION";
        volatile String lastAmount;
        volatile String lastMode;

        private static JsonNode quote(String state, String amount, String mode) throws Exception {
            return JSON.readTree("{\"state\":\"" + state + "\",\"quote\":{\"quoteId\":\"" + "c".repeat(32) + "\",\"feeMode\":\"" + mode + "\",\"chainId\":\"byx-fake-1\",\"chainGeneration\":7,"
                    + "\"sender\":\"byx1sender\",\"recipient\":\"byx1recipient\",\"amountUbyx\":\"" + amount + "\",\"memoDigest\":\"" + "d".repeat(64) + "\",\"accountNumber\":12,\"sequence\":5,"
                    + "\"simulatedGas\":\"100000\",\"adjustedGas\":\"110000\",\"gasLimit\":110000,\"gasPrice\":\"0.025\",\"feeUbyx\":\"2750\",\"maximumFeeUbyx\":\"50000\","
                    + "\"totalDebitUbyx\":\"1002750\",\"policy\":\"DRAFT_TESTNET_POLICY\",\"policyVersion\":\"V1\",\"createdAtMs\":0,\"expiresAtMs\":60000}}");
        }

        @Override
        public synchronized Reply prepare(String op, String sender, String recipient, String amountUbyx, String memo, String feeMode) {
            calls.add("prepare");
            lastAmount = amountUbyx;
            lastMode = feeMode;
            if (prepareCode != null) {
                return new Reply(false, prepareCode, null);
            }
            try {
                return new Reply(true, "OK", quote("AWAITING_CONFIRMATION", amountUbyx, feeMode));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized Reply confirm(String op, String quote) {
            calls.add("confirm");
            try {
                return new Reply(true, "OK", quote("SUBMITTED", lastAmount, lastMode));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public synchronized Reply status(String op) { calls.add("status"); return confirm(op, ""); }

        @Override
        public synchronized Reply cancel(String op) {
            calls.add("cancel");
            try {
                return new Reply(true, "OK", quote("EXPIRED", lastAmount, lastMode));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final class Fixture {
        final FakeService service = new FakeService();
        final AtomicLong now = new AtomicLong(1_000);
        final List<Runnable> queued = new ArrayList<>();
        TransactionLab lab;
        Stage stage;

        Fixture() {
            // executor de trabalho e de UI em linha (determinístico): o código real usa threads separadas, a lógica é a mesma
            lab = new TransactionLab(new MotionService(), service, Runnable::run, Runnable::run, now::get);
            stage = new Stage();
            Scene s = new Scene((javafx.scene.Parent) lab.node(), 1200, 900);
            ByxTheme.apply(s);
            stage.setScene(s);
            stage.show();
        }

        void fill(String to, String byx, String memo) {
            lab.recipientForTest().input().setText(to);
            lab.amountForTest().input().setText(byx);
            lab.memoForTest().input().setText(memo);
        }
    }

    @Test
    void amountHelperShowsExactUbyxAndTheServiceReceivesIntegerUbyxOnly() throws Exception {
        String[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.fill("byx1recipient", "1.5", "hi");
            String helper = f.lab.ubyxHelperForTest();
            f.lab.requestQuote();
            String[] out = {helper, f.service.lastAmount, f.service.lastMode, f.lab.currentStepLabel(), String.join("|", f.lab.previewForTest())};
            f.stage.close();
            return out;
        });
        assertEquals("= 1500000 ubyx", r[0]);
        assertEquals("1500000", r[1], "the wire carries integer ubyx, never a BYX decimal");
        assertEquals("STANDARD", r[2]);
        assertEquals("Awaiting confirmation", r[3]);
        assertTrue(r[4].contains("Total debit=1.002750 BYX (1002750 ubyx)"), r[4]);
        assertTrue(r[4].contains("Estimated fee=0.002750 BYX (2750 ubyx)") && r[4].contains("Gas price (ubyx/gas)=0.025") && r[4].contains("Maximum fee=0.050000 BYX (50000 ubyx)"), r[4]);
    }

    @Test
    void invalidAmountsNeverCallTheService() throws Exception {
        int calls = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            for (String bad : new String[] {"0", "-1", "1.1234567", "1e3", ""}) {
                f.fill("byx1recipient", bad, "");
                f.lab.requestQuote();
            }
            int n = f.service.calls.size();
            f.stage.close();
            return n;
        });
        assertEquals(0, calls);
    }

    @Test
    void confirmShowsEveryFieldAndFakeLabelsAndExecutesOnce() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.fill("byx1recipient", "1", "memo!");
            f.lab.requestQuote();
            boolean enabled = !f.lab.confirmButtonForTest().isDisabled();
            String confirmation = String.join("|", f.lab.confirmationForTest());
            f.lab.confirm();
            f.lab.confirm(); // segundo clique: a cotação já executou, nada é enviado de novo
            f.lab.confirm();
            long confirms = f.service.calls.stream().filter("confirm"::equals).count();
            Object[] out = {enabled, confirmation, confirms, f.lab.currentStepLabel(), f.lab.confirmButtonForTest().isDisabled(), f.lab.quoteButtonForTest().isDisabled()};
            f.stage.close();
            return out;
        });
        assertTrue((Boolean) r[0]);
        String c = (String) r[1];
        for (String key : new String[] {"From=byx1sender", "To=byx1recipient", "Amount=1.000000 BYX", "Fee=0.002750 BYX", "Total=1.002750 BYX", "Network=byx-fake-1 · generation 7 (FAKE)", "Memo=memo! · digest dddddddddddd"}) {
            assertTrue(c.contains(key), key + " in " + c);
        }
        assertEquals(1L, r[2], "exactly one confirm reaches the service");
        assertEquals("Submitted (fake)", r[3]);
        assertTrue((Boolean) r[4] && (Boolean) r[5]);
    }

    @Test
    void expiredOrChangedQuoteDisablesConfirm() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.fill("byx1recipient", "1", "");
            f.lab.requestQuote();
            boolean live = !f.lab.confirmButtonForTest().isDisabled();
            f.lab.amountForTest().input().setText("2"); // campo mudou depois da cotação
            boolean changed = f.lab.confirmButtonForTest().isDisabled();
            f.lab.amountForTest().input().setText("1");
            boolean back = !f.lab.confirmButtonForTest().isDisabled();
            f.now.set(70_000); // venceu
            f.lab.onShow();
            f.lab.onHide();
            f.lab.cancel(); // sem efeito visual; força render pelo caminho do serviço
            f.now.set(70_000);
            boolean expired = f.lab.currentStepLabel().equals("Expired") || f.lab.confirmButtonForTest().isDisabled();
            f.stage.close();
            return new boolean[] {live, changed, back, expired};
        });
        assertTrue(r[0]);
        assertTrue(r[1], "changed fields disable confirm");
        assertTrue(r[2]);
        assertTrue(r[3], "expired quote disables confirm");
    }

    @Test
    void productionStyleDisabledServiceShowsTxDisabledAndNeverEnablesConfirm() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.service.prepareCode = "TX_DISABLED";
            f.fill("byx1recipient", "1", "");
            f.lab.requestQuote();
            Object[] out = {f.lab.messageForTest(), f.lab.confirmButtonForTest().isDisabled(), f.lab.viewForTest() == null};
            f.stage.close();
            return out;
        });
        assertTrue(((String) r[0]).contains("TX_DISABLED"));
        assertTrue((Boolean) r[1]);
        assertTrue((Boolean) r[2]);
    }

    @Test
    void theScreenIsLabelledFakeAndNeverCallsTheServiceOnTheFxThread() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            AtomicLong fxCalls = new AtomicLong();
            TxLabService probe = new FakeService() {
                @Override
                public synchronized Reply prepare(String op, String s, String rcp, String a, String m, String mode) {
                    if (Platform.isFxApplicationThread()) {
                        fxCalls.incrementAndGet();
                    }
                    return super.prepare(op, s, rcp, a, m, mode);
                }
            };
            // com executor de trabalho REAL (outra thread) e UI de volta pela FX
            java.util.concurrent.Executor worker = rr -> {
                Thread t = new Thread(rr);
                t.setDaemon(true);
                t.start();
            };
            TransactionLab lab = new TransactionLab(new MotionService(), probe, worker, Platform::runLater, () -> 1_000);
            lab.recipientForTest().input().setText("byx1recipient");
            lab.amountForTest().input().setText("1");
            lab.requestQuote();
            boolean fakeBanner = lab.bannerForTest().contains("SIMULATION / FAKE TX");
            return new Object[] {fxCalls, fakeBanner};
        });
        Thread.sleep(300);
        assertEquals(0L, ((AtomicLong) r[0]).get(), "service calls never run on the FX thread");
        assertTrue((Boolean) r[1], "the whole screen says SIMULATION / FAKE TX");
    }
}
