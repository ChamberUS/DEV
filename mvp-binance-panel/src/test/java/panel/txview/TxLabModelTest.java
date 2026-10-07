package panel.txview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Conversão exata BYX <-> ubyx, leitura tipada da cotação e rótulos: sem float e sem texto livre do serviço. */
class TxLabModelTest {
    @Test
    void byxToUbyxIsExactAndStrict() {
        assertEquals(Optional.of(BigInteger.valueOf(1_500_000)), TxLabModel.parseByx("1.5"));
        assertEquals(Optional.of(BigInteger.ONE), TxLabModel.parseByx("0.000001"));
        assertEquals(Optional.of(new BigInteger("123456789000000")), TxLabModel.parseByx("123456789"));
        assertEquals(Optional.of(new BigInteger("10000000000000000000000000")), TxLabModel.parseByx("10000000000000000000"));
        assertTrue(TxLabModel.parseByx("100000000000000000000").isEmpty(), "21 integer digits is out of range");
        for (String bad : new String[] {null, "", "0", "0.0", "0.000000", "-1", "1.1234567", "1e3", "1,5", " 1", "1 ", "+1", ".5", "1.", "abc", "١٢٣", "1_000"}) {
            assertTrue(TxLabModel.parseByx(bad).isEmpty(), String.valueOf(bad));
        }
        // 0.1 + 0.2 do mundo float: aqui é exato
        assertEquals(Optional.of(BigInteger.valueOf(100_000)), TxLabModel.parseByx("0.1"));
        assertEquals(Optional.of(BigInteger.valueOf(200_000)), TxLabModel.parseByx("0.2"));
    }

    @Test
    void ubyxFormatsWithSixExactDecimals() {
        assertEquals("1.500000", TxLabModel.formatByx(BigInteger.valueOf(1_500_000)));
        assertEquals("0.000001", TxLabModel.formatByx(BigInteger.ONE));
        assertEquals("0.002750", TxLabModel.formatByx(BigInteger.valueOf(2750)));
        assertEquals("12345678901234567890.123456", TxLabModel.formatByx(new BigInteger("12345678901234567890123456")));
    }

    @Test
    void parsesTheServiceQuoteAndRejectsGarbage() throws Exception {
        var m = new JsonMapper();
        String ok = "{\"state\":\"AWAITING_CONFIRMATION\",\"quote\":{\"quoteId\":\"" + "a".repeat(32) + "\",\"feeMode\":\"LOW\",\"chainId\":\"c\",\"chainGeneration\":3,\"sender\":\"s\",\"recipient\":\"r\","
                + "\"amountUbyx\":\"1000\",\"memoDigest\":\"" + "b".repeat(64) + "\",\"accountNumber\":1,\"sequence\":2,\"simulatedGas\":\"100\",\"adjustedGas\":\"110\",\"gasLimit\":110,"
                + "\"gasPrice\":\"0.02\",\"feeUbyx\":\"3\",\"maximumFeeUbyx\":\"50000\",\"totalDebitUbyx\":\"1003\",\"policy\":\"P\",\"policyVersion\":\"1\",\"createdAtMs\":10,\"expiresAtMs\":70000}}";
        var v = TxLabModel.parse(m.readTree(ok)).orElseThrow();
        assertEquals("AWAITING_CONFIRMATION", v.state());
        assertEquals(BigInteger.valueOf(1003), v.quote().totalDebit());
        assertTrue(v.quote().expiredAt(70_000));
        assertFalse(v.quote().expiredAt(69_999));
        assertTrue(TxLabModel.parse(m.readTree("{\"state\":\"X\",\"quote\":{\"amountUbyx\":\"1.5\"}}")).isEmpty(), "a malformed quote is never shown");
        assertTrue(TxLabModel.parse(m.readTree("{\"quote\":{}}")).isEmpty());
        assertTrue(TxLabModel.parse(null).isEmpty());
    }

    @Test
    void everythingThatIsFakeIsLabelledAndErrorsAreFixedTexts() {
        for (String s : new String[] {"SIGNING", "BROADCASTING", "SUBMITTED", "CONFIRMED_ON_CHAIN"}) {
            assertTrue(TxLabModel.stateLabel(s).contains("fake"), s);
        }
        assertEquals("Unknown outcome", TxLabModel.stateLabel("UNKNOWN_OUTCOME"));
        assertTrue(TxLabModel.errorText("TX_DISABLED").contains("disabled"));
        assertEquals(TxLabModel.errorText(null), TxLabModel.errorText("<script>alert(1)</script>"), "an unknown code never echoes the service text");
        assertTrue(TxLabModel.errorText("MAX_FEE_EXCEEDED").contains("maximum fee"));
    }

    @Test
    void confirmIsPossibleOnlyForALiveUnchangedQuoteWhenIdle() {
        var q = new TxLabModel.QuoteView("q", "LOW", "c", 1, "s", "r", BigInteger.ONE, "d", 1, 1, BigInteger.ONE, BigInteger.ONE, 1, "0.02", BigInteger.ONE, BigInteger.TEN, BigInteger.TWO, "P", "1", 0, 1000);
        var awaiting = new TxLabModel.View("AWAITING_CONFIRMATION", q, null, null);
        assertTrue(TxLabModel.canConfirm(awaiting, true, 999, false));
        assertFalse(TxLabModel.canConfirm(awaiting, true, 1000, false), "expired");
        assertFalse(TxLabModel.canConfirm(awaiting, false, 10, false), "fields changed");
        assertFalse(TxLabModel.canConfirm(awaiting, true, 10, true), "busy (double click)");
        assertFalse(TxLabModel.canConfirm(new TxLabModel.View("SUBMITTED", q, "H", null), true, 10, false));
        assertFalse(TxLabModel.canConfirm(null, true, 10, false));
    }
}
