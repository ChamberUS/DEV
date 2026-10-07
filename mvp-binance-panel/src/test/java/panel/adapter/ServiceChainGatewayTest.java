package panel.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import panel.byxview.NetworkModel;
import panel.localservice.ChainStatusClient;
import panel.localservice.LocalServiceClient;
import panel.model.ByxSnapshot;
import panel.service.ByxNetworkService;

/** V2.1L (painel): estado da chain vindo do serviço → snapshot da tela. Só LIVE é saudável; divergência de rede nunca mostra altura; o painel não conhece endpoint. */
class ServiceChainGatewayTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private static ChainStatusClient.View view(String state, boolean match, Long height, Long blockMs, String chainId) {
        return new ChainStatusClient.View(state, true, !"OFFLINE".equals(state) && !"CONNECTING".equals(state), chainId, height, height == null ? null : "SYNCING".equals(state), blockMs, match, "NONE");
    }

    @Test
    void everyServiceStateMapsToExactlyTheMatchingScreenStateAndOnlyLiveIsHealthy() {
        long block = NOW.minusSeconds(3).toEpochMilli();
        Map<String, NetworkModel.State> expected = Map.of("NOT_CONFIGURED", NetworkModel.State.NOT_CONFIGURED, "CONNECTING", NetworkModel.State.CONNECTING, "OFFLINE", NetworkModel.State.OFFLINE,
                "SYNCING", NetworkModel.State.SYNCING, "LIVE", NetworkModel.State.HEALTHY, "STALE", NetworkModel.State.STALE, "NETWORK_MISMATCH", NetworkModel.State.IDENTITY_MISMATCH,
                "ERROR", NetworkModel.State.ERROR);
        for (var e : expected.entrySet()) {
            boolean withBlock = Set3.has(e.getKey());
            ByxSnapshot s = ServiceChainGateway.toSnapshot(view(e.getKey(), withBlock, withBlock ? 100L : null, withBlock ? block : null, "byx"), NOW);
            assertEquals(e.getValue(), NetworkModel.state(s), e.getKey());
            assertEquals(e.getKey().equals("LIVE"), NetworkModel.state(s) == NetworkModel.State.HEALTHY, "only LIVE is healthy: " + e.getKey());
            assertNull(s.address());
            assertNull(s.balance(), "no account data in the network snapshot");
            assertEquals("SERVICE_NODE", s.source());
        }
        assertEquals(NetworkModel.State.ERROR, NetworkModel.state(ServiceChainGateway.toSnapshot(new ChainStatusClient.View("HEALTHY", true, true, null, null, null, null, true, "NONE"), NOW)),
                "an unknown state is an error, never healthy");
    }

    @Test
    void theLabelsAreTheReadOnlyOnesAndNothingSuggestsATransaction() {
        assertEquals("LIVE", NetworkModel.State.HEALTHY.text);
        assertEquals("NETWORK MISMATCH", NetworkModel.State.IDENTITY_MISMATCH.text);
        assertEquals("NOT CONFIGURED", NetworkModel.State.NOT_CONFIGURED.text);
        assertEquals("CONNECTING", NetworkModel.State.CONNECTING.text);
        assertEquals("ERROR", NetworkModel.State.ERROR.text);
        for (NetworkModel.State s : NetworkModel.State.values()) {
            assertFalse(s.text.toLowerCase().matches(".*(send|transfer|sign|wallet|mainnet|swap|buy|sell).*"), s.text);
        }
        assertTrue(NetworkModel.State.CONNECTING.active() && NetworkModel.State.SYNCING.active());
        assertFalse(NetworkModel.State.NOT_CONFIGURED.active() || NetworkModel.State.ERROR.active() || NetworkModel.State.HEALTHY.active() || NetworkModel.State.OFFLINE.active());
    }

    @Test
    void aMismatchedNetworkExposesNoHeightAndANotConfiguredNodeExposesNoChain() {
        ByxSnapshot mismatch = ServiceChainGateway.toSnapshot(view("NETWORK_MISMATCH", false, null, null, "other-chain"), NOW);
        assertNull(mismatch.height());
        assertNull(mismatch.blockTime());
        assertNull(mismatch.updatedAt());
        assertEquals("other-chain", mismatch.chainId(), "the observed id explains the mismatch");
        assertEquals("UNVERIFIED", mismatch.identity());
        ByxSnapshot none = ServiceChainGateway.toSnapshot(view("NOT_CONFIGURED", false, null, null, null), NOW);
        assertNull(none.chainId());
        assertNull(none.height());
    }

    @Test
    void theNetworkServiceReadsThroughTheServiceWithoutAnyPanelConfigurationAndNeverThrows() throws Exception {
        Path home = Files.createTempDirectory(Path.of("/tmp"), "sg");
        try (ByxNetworkService network = new ByxNetworkService(new ServiceChainGateway(new ChainStatusClient(new LocalServiceClient(home)), Clock.fixed(NOW, ZoneOffset.UTC)), () -> null,
                Clock.fixed(NOW, ZoneOffset.UTC))) {
            ByxSnapshot s = network.refresh().get();
            assertEquals(NetworkModel.State.ERROR, NetworkModel.state(s), "no service running: an error state, not healthy and not an exception");
            assertNull(s.height());
            assertFalse(NetworkModel.state(network.snapshot()) == NetworkModel.State.HEALTHY);
        } finally {
            Files.deleteIfExists(home);
        }
    }

    /** estados que carregam altura e bloco */
    private static final class Set3 {
        static boolean has(String state) {
            return state.equals("LIVE") || state.equals("SYNCING") || state.equals("STALE");
        }
    }

    @Test
    void exactSupplyFormattingNeverUsesFloatingPoint() {
        assertEquals("0.000000", panel.util.DenomFormat.format(java.math.BigInteger.ZERO, 6));
        assertEquals("0.000001", panel.util.DenomFormat.format(java.math.BigInteger.ONE, 6));
        assertEquals("123456789012345678901234567890.123456", panel.util.DenomFormat.format(new java.math.BigInteger("123456789012345678901234567890123456"), 6));
        assertEquals("7", panel.util.DenomFormat.format(java.math.BigInteger.valueOf(7), 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> panel.util.DenomFormat.format(java.math.BigInteger.valueOf(-1), 6));
        var facts = new panel.model.ChainFacts("ubyx", "BYX", 6, new java.math.BigInteger("1000239758"));
        ByxSnapshot live = ServiceChainGateway.toSnapshot(new ChainStatusClient.View("LIVE", true, true, "byx", 5L, false, NOW.minusSeconds(2).toEpochMilli(), true, "NONE", 1), facts, NOW);
        assertEquals("1000.239758 BYX", NetworkModel.supply(live));
        assertEquals("ubyx", NetworkModel.baseDenom(live));
        assertEquals("6", NetworkModel.exponent(live));
        ByxSnapshot mismatch = ServiceChainGateway.toSnapshot(new ChainStatusClient.View("NETWORK_MISMATCH", true, true, "x", null, null, null, false, "NETWORK_MISMATCH", 1), facts, NOW);
        assertEquals(NetworkModel.NONE, NetworkModel.supply(mismatch), "a mismatched network never shows facts");
    }
}
