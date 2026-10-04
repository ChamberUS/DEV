package panel.service;

import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import panel.model.ByxConfig;

/** Experimental TEST limits, unrelated to paid passes or security permissions. */
public record GasSponsorshipPolicy(String granter, String chainId, String genesis, Map<String, BigInteger> quotas, int validitySeconds) {
    public GasSponsorshipPolicy {
        if (granter == null || !granter.matches("byx1[023456789acdefghjklmnpqrstuvwxyz]{38}")
                || chainId == null || !chainId.startsWith("byx-mvp-localnet-b-")
                || genesis == null || !genesis.matches("[a-f0-9]{64}") || validitySeconds < 30 || validitySeconds > 3600)
            throw new IllegalArgumentException("Bounded LOCALNET TEST policy required");
        quotas = Map.copyOf(quotas);
        if (!quotas.keySet().equals(Set.of("FREE", "HOLDER", "PLUS", "PRO"))
                || quotas.values().stream().anyMatch(v -> v.signum() < 0 || v.compareTo(BigInteger.valueOf(1000000)) > 0)
                || quotas.get("FREE").signum() != 0 || quotas.get("HOLDER").signum() != 0
                || quotas.get("PLUS").signum() <= 0 || quotas.get("PRO").compareTo(quotas.get("PLUS")) < 0)
            throw new IllegalArgumentException("Invalid TEST quotas");
    }
    public void matches(ByxConfig c) {
        if (!chainId.equals(c.expectedChainId()) || !genesis.equals(c.genesisFingerprint()) || !"LOCALNET".equals(c.environment()))
            throw new panel.security.AccessDeniedException("Wrong sponsorship chain");
    }
    public static GasSponsorshipPolicy load() {
        var p = new Properties();
        try (var in = Files.newInputStream(Path.of(System.getProperty("user.home"), ".mvp-binance-panel/byx-gas-test.properties"))) { p.load(in); }
        catch (java.io.IOException e) { throw new IllegalStateException("TEST gas sponsor not configured", e); }
        if (!"LOCALNET_TEST_ONLY".equals(p.getProperty("environment"))) throw new IllegalArgumentException("TEST policy required");
        var quotas = new HashMap<String, BigInteger>();
        for (String tier : List.of("FREE", "HOLDER", "PLUS", "PRO")) quotas.put(tier, new BigInteger(p.getProperty(tier)));
        return new GasSponsorshipPolicy(p.getProperty("granter"), p.getProperty("chainId"), p.getProperty("genesis"), quotas, Integer.parseInt(p.getProperty("validitySeconds")));
    }
}
