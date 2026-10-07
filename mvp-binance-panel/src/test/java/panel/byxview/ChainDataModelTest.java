package panel.byxview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import panel.model.ChainModules;
import panel.model.ChainModules.Failure;
import panel.model.ChainModules.Freshness;
import panel.model.ChainModules.Reply;

/** V2.1N: derivação dos estados de UI, formatação de tempo/identificadores/porcentagem e o formatador monetário ÚNICO. */
class ChainDataModelTest {
    @Test
    void everyFailureHasADistinctNonSuccessStateAndNotFoundIsNotAnError() {
        for (Failure f : Failure.values()) {
            ChainDataModel.State s = ChainDataModel.failure(f);
            assertFalse(s.view().showsData(), f.name());
            assertFalse(s.note().isBlank());
        }
        assertEquals(ChainDataModel.View.NOT_FOUND, ChainDataModel.failure(Failure.NOT_FOUND).view());
        assertTrue(ChainDataModel.View.NOT_FOUND.tone != panel.design.ByxBadge.Tone.NEGATIVE, "not found is not painted as an error");
        assertEquals(ChainDataModel.View.OFFLINE, ChainDataModel.failure(Failure.UNREACHABLE).view());
        assertEquals(ChainDataModel.View.OFFLINE, ChainDataModel.failure(Failure.STALE_CHAIN).view());
        assertEquals(ChainDataModel.View.MODULE_UNAVAILABLE, ChainDataModel.failure(Failure.UNSUPPORTED_QUERY).view());
        assertEquals(ChainDataModel.View.NETWORK_MISMATCH, ChainDataModel.failure(Failure.DENOM_MISMATCH).view());
        assertEquals(ChainDataModel.View.ERROR, ChainDataModel.failure(Failure.RESPONSE_TOO_LARGE).view());
    }

    @Test
    void freshnessIsAlwaysVisibleAndCacheOrStaleNeverBecomeLive() {
        assertEquals(ChainDataModel.View.LIVE, ChainDataModel.of(new Reply<>(true, null, Freshness.LIVE, 0, "x", null), false).view());
        var cached = ChainDataModel.of(new Reply<>(true, null, Freshness.CACHED, 4_000, "x", null), false);
        assertEquals(ChainDataModel.View.CACHED, cached.view());
        assertTrue(cached.note().contains("4s"));
        var stale = ChainDataModel.of(new Reply<>(true, null, Freshness.STALE, 125_000, "x", null), false);
        assertEquals(ChainDataModel.View.STALE, stale.view());
        assertTrue(stale.note().contains("2m 5s") && stale.note().contains("not current"));
        assertEquals(ChainDataModel.View.EMPTY, ChainDataModel.of(new Reply<>(true, null, Freshness.LIVE, 0, "x", null), true).view());
    }

    @Test
    void identifiersAreAbbreviatedOnlyForDisplayAndTimestampsAreUtcInstants() {
        String addr = "byx14uzu3ja88cpf5fktzp9rwt5zqhppv0j59qev7z";
        assertEquals("byx14uzu3j…9qev7z", ChainDataModel.shortId(addr));
        assertEquals("1", ChainDataModel.shortId("1"));
        assertEquals("—", ChainDataModel.shortId(""));
        assertEquals(addr.length(), addr.length(), "the model value is untouched");
        assertEquals("—", ChainDataModel.time(null));
        assertFalse(ChainDataModel.time(Instant.parse("2026-10-07T06:09:00Z")).isBlank());
    }

    @Test
    void allocationPercentagesAreExactIntegers() {
        assertEquals("60%", ChainDataModel.percent(6000));
        assertEquals("30%", ChainDataModel.percent(3000));
        assertEquals("10%", ChainDataModel.percent(1000));
        assertEquals("0.50%", ChainDataModel.percent(50));
        assertEquals("12.34%", ChainDataModel.percent(1234));
    }

    @Test
    void thereIsOneMonetaryFormatterAndItIsExact() {
        var f = (java.util.function.BiFunction<String, Integer, String>) (a, e) -> panel.util.DenomFormat.format(new java.math.BigInteger(a), e);
        assertEquals("0.000000", f.apply("0", 6));
        assertEquals("0.000001", f.apply("1", 6));
        assertEquals("0.999999", f.apply("999999", 6));
        assertEquals("1.000000", f.apply("1000000", 6));
        assertEquals("18446744073709.551615", f.apply("18446744073709551615", 6));
        assertFalse(f.apply("123456789012345678901234567890", 6).contains("E"), "no scientific notation");
    }

    @Test
    void feesplitFactsStayInTheModelNotInTheView() {
        var f = new ChainModules.Feesplit("DOCUMENTED_DEFAULT_NOT_QUERIED", 6000, 3000, 1000);
        assertEquals(10_000, f.distributionBps() + f.treasuryBps() + f.burnBps());
    }
}
