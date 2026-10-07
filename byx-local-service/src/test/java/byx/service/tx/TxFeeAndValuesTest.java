package byx.service.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import byx.service.tx.TxIntent.BankSendIntent;
import byx.service.tx.TxValues.AccountNumber;
import byx.service.tx.TxValues.BankAddress;
import byx.service.tx.TxValues.GasAmount;
import byx.service.tx.TxValues.GasPrice;
import byx.service.tx.TxValues.KeyRef;
import byx.service.tx.TxValues.Memo;
import byx.service.tx.TxValues.Sequence;
import byx.service.tx.TxValues.UbyxAmount;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** Aritmética exata de gas/taxa, tipos de valor estritos e resumo canônico da intenção. */
class TxFeeAndValuesTest {
    private final TxFx.DraftTestnetPolicy policy = new TxFx.DraftTestnetPolicy();

    private static TxError error(Runnable r) {
        return assertThrows(TxException.class, r::run).error();
    }

    private static GasAmount gas(long g) {
        return new GasAmount(BigInteger.valueOf(g));
    }

    @Test
    void feeIsCeilOfCeilWithEveryNumberKeptSeparate() {
        FeeEngine.Fee f = FeeEngine.compute(policy, FeeMode.STANDARD, gas(21_000));
        assertEquals(BigInteger.valueOf(21_000), f.simulatedGas().value());
        assertEquals(BigInteger.valueOf(23_100), f.adjustedGas().value()); // 21000 * 1.10
        assertEquals(23_100, f.gasLimit().value());
        assertEquals("0.025", f.gasPrice().canonical());
        assertEquals(BigInteger.valueOf(578), f.estimatedFee().value()); // ceil(23100 * 0.025 = 577.5)
        assertEquals(BigInteger.valueOf(50_000), f.maximumFee().value());
        // ceil também no ajuste: 100001 * 1.1 = 110001.1 -> 110002
        assertEquals(BigInteger.valueOf(110_002), FeeEngine.compute(policy, FeeMode.LOW, gas(100_001)).adjustedGas().value());
        // preço LOW: ceil(110002 * 0.020 = 2200.04) = 2201
        assertEquals(BigInteger.valueOf(2201), FeeEngine.compute(policy, FeeMode.LOW, gas(100_001)).estimatedFee().value());
    }

    @Test
    void highModeWithHugeGasViolatesTheBudgetBeforeAnythingIsSigned() {
        // 1 800 000 * 1.10 = 1 980 000 <= 2 000 000 (teto global), mas 1 980 000 * 0.05 = 99 000 > 50 000 (orçamento 0.05 BYX)
        assertEquals(TxError.MAX_FEE_EXCEEDED, error(() -> FeeEngine.compute(policy, FeeMode.HIGH, gas(1_800_000))));
        // o orçamento domina: HIGH a 0.05 admite no máximo 1 000 000 de gas
        FeeEngine.Fee atBudget = FeeEngine.compute(policy, FeeMode.HIGH, gas(909_090)); // 909090*1.1 = 999 999 -> 1 000 000? ceil(999999.0)=999999
        assertTrue(atBudget.gasLimit().value() <= 1_000_000 && atBudget.estimatedFee().value().compareTo(BigInteger.valueOf(50_000)) <= 0);
        assertEquals(BigInteger.valueOf(1_000_000), atBudget.gasAllowedByBudget());
        // 2 000 000 simulados de gas -> ajustado 2 200 000 > teto global
        assertEquals(TxError.MAX_GAS_EXCEEDED, error(() -> FeeEngine.compute(policy, FeeMode.LOW, gas(2_000_000))));
    }

    @Test
    void gasIsNeverSilentlyReducedToFitTheFee() {
        // o mesmo gas que cabe em LOW não cabe em HIGH: a resposta é erro, nunca um gas menor
        assertEquals(TxError.MAX_FEE_EXCEEDED, error(() -> FeeEngine.compute(policy, FeeMode.HIGH, gas(1_000_000))));
        FeeEngine.Fee low = FeeEngine.compute(policy, FeeMode.LOW, gas(1_000_000));
        assertEquals(BigInteger.valueOf(1_100_000), low.adjustedGas().value());
        assertEquals(BigInteger.valueOf(22_000), low.estimatedFee().value());
    }

    @Test
    void gasEstimatesThatAreNotEstimatesAreRejected() {
        for (String bad : new String[] {null, "", "0", "-1", "-0", "1.5", "1e9", " 1", "+5", "00", "0x10", "99999999999999999999999999", "1000000000001", "٣"}) {
            assertEquals(TxError.GAS_ESTIMATE_INVALID, error(() -> GasAmount.parse(bad)), String.valueOf(bad));
        }
        assertEquals(BigInteger.TEN.pow(12), GasAmount.parse("1000000000000").value());
    }

    @Test
    void amountsAreExactPositiveIntegerUbyxOnly() {
        for (String bad : new String[] {null, "", "0", "-5", "1.5", "1,5", "1e3", "0.000001", " 5", "5 ", "+5", "05", "170141183460469231731687303715884105728" /* 2^127 */}) {
            assertEquals(TxError.INVALID_AMOUNT, error(() -> UbyxAmount.parse(bad)), String.valueOf(bad));
        }
        assertEquals(BigInteger.ONE, UbyxAmount.parse("1").value());
        assertEquals(TxValues.MAX_UBYX, UbyxAmount.parse(TxValues.MAX_UBYX.toString()).value());
    }

    @Test
    void accountAndSequenceRejectNegativeHugeAndMalformed() {
        for (String bad : new String[] {null, "-1", "9223372036854775808", "18446744073709551615", "1.0", "abc", ""}) {
            assertEquals(TxError.ACCOUNT_INVALID, error(() -> AccountNumber.parse(bad)), String.valueOf(bad));
            assertEquals(TxError.ACCOUNT_INVALID, error(() -> Sequence.parse(bad)), String.valueOf(bad));
        }
        assertEquals(0, Sequence.parse("0").value());
        assertEquals(Long.MAX_VALUE / 2, Sequence.parse(Long.toString(Long.MAX_VALUE / 2)).value());
    }

    @Test
    void gasPriceIsExactDecimalAndCanonical() {
        assertEquals("0.02", GasPrice.of("0.020").canonical());
        assertEquals("0", GasPrice.of("0.000").canonical());
        assertEquals(TxError.BAD_REQUEST, error(() -> GasPrice.of("-0.1")));
        assertEquals(TxError.BAD_REQUEST, error(() -> GasPrice.of("abc")));
        assertEquals(TxError.BAD_REQUEST, error(() -> GasPrice.of("0.1234567890123456789")));
    }

    @Test
    void addressesMemoAndKeyRefsAreValidated() {
        assertEquals(TxError.INVALID_ADDRESS, error(() -> new BankAddress("cosmos1abc")));
        assertEquals(TxError.INVALID_ADDRESS, error(() -> new BankAddress(TxFx.RECIPIENT.toUpperCase())));
        assertEquals(TxError.INVALID_ADDRESS, error(() -> new BankAddress(TxFx.RECIPIENT.substring(0, TxFx.RECIPIENT.length() - 1) + "q")));
        assertEquals(TxError.INVALID_ADDRESS, error(() -> new BankAddress(null)));
        new BankAddress(TxFx.RECIPIENT); // o gerador de teste produz bech32 válido
        assertEquals(TxError.MEMO_TOO_LARGE, error(() -> new Memo("x".repeat(257))));
        assertEquals(TxError.MEMO_TOO_LARGE, error(() -> new Memo("é".repeat(129)))); // 258 bytes UTF-8
        new Memo("é".repeat(128)); // 256 bytes
        assertEquals(TxError.BAD_REQUEST, error(() -> new Memo("a\nb")));
        assertEquals(TxError.BAD_REQUEST, error(() -> new KeyRef("Bad Ref")));
        assertEquals(TxError.INVALID_FEE_MODE, error(() -> FeeMode.parse("TURBO")));
        assertEquals(TxError.INVALID_FEE_MODE, error(() -> FeeMode.parse("high")));
    }

    @Test
    void intentDigestIsDeterministicAndChangesWithEveryField() {
        BankSendIntent base = TxFx.intent("memo", 1000);
        assertEquals(base.digest(), TxFx.intent("memo", 1000).digest());
        assertEquals(64, base.digest().value().length());
        assertNotEquals(base.digest(), TxFx.intent("memo2", 1000).digest());
        assertNotEquals(base.digest(), TxFx.intent("memo", 1001).digest());
        assertNotEquals(base.digest(), new BankSendIntent(base.sender(), new BankAddress(TxFx.address(3)), base.amount(), base.memo()).digest());
        assertNotEquals(base.digest(), new BankSendIntent(new KeyRef("other"), base.recipient(), base.amount(), base.memo()).digest());
        // sem ambiguidade de concatenação: ("ab","c") != ("a","bc")
        assertNotEquals(TxFx.intent("ab", 1).digest(), new BankSendIntent(new KeyRef("primary"), base.recipient(), new UbyxAmount(BigInteger.ONE), new Memo("a")).digest());
        assertFalse(new String(base.canonical(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("@")); // não é serialização Java
    }
}
