package panel.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import panel.adapter.ByxChainGateway;
import panel.model.*;

/** Localnet-only tier evaluation; never grants security roles, execution or financial discounts. */
public final class ByxBenefitsService implements AutoCloseable {
    public record PaymentMethod(String name, boolean requiresByx, boolean connected) { }
    public record UsageLimits(boolean normalAccess, boolean byxBenefitGranted, OptionalLong extraResourceUnits) { }
    public interface PaymentMethods { List<PaymentMethod> methods(); }
    public interface AppDiscountPolicy { Optional<BigDecimal> rate(); }
    public interface UsagePolicy { UsageLimits limits(); }
    public interface NetworkFeePolicy { Optional<BigInteger> subsidyUbyx(); }
    public record Assessment(String mode, String controlProof, boolean benefitGranted,
                             boolean normalAccess, boolean adminGranted, boolean researchUnlocked,
                             boolean strategyExecutionEnabled) { }

    private final ByxWalletIdentityService identity;
    private final ByxChainGateway gateway;
    private final Clock clock;
    private final java.util.Map<String, BigInteger> thresholds = new java.util.LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "byx-wallet-benefits"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private record Cached(VerifiedWallet wallet, ByxSnapshot chain) { }
    private volatile Cached cached;
    public ByxBenefitsService() { this(null, null, Clock.systemUTC(), defaults()); }
    public ByxBenefitsService(ByxWalletIdentityService identity, ByxChainGateway gateway, Clock clock, Properties policy) {
        this.identity = identity; this.gateway = gateway; this.clock = clock;
        if (!"LOCALNET_TEST_ONLY".equals(policy.getProperty("environment"))) throw new IllegalArgumentException("Test policy required");
        BigInteger previous = BigInteger.ZERO;
        thresholds.put("FREE", previous);
        for (String tier : List.of("HOLDER", "PLUS", "PRO")) {
            String value = policy.getProperty(tier);
            if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException("Integer ubyx thresholds required");
            BigInteger amount = new BigInteger(value);
            if (amount.compareTo(previous) <= 0) throw new IllegalArgumentException("Increasing thresholds required");
            thresholds.put(tier, amount); previous = amount;
        }
    }
    public static Properties defaults() {
        Properties p = new Properties();
        try (var input = ByxBenefitsService.class.getResourceAsStream("/panel/byx-localnet-benefits.properties")) {
            if (input == null) throw new IllegalStateException("Localnet policy absent"); p.load(input); return p;
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid policy", e); }
    }
    private BenefitsSnapshot disabled(String status, String address, ByxSnapshot chain) {
        return new BenefitsSnapshot(status, address, chain == null ? null : chain.balance(), "FREE", false,
                chain == null ? null : chain.updatedAt(), chain == null ? "UNKNOWN" : chain.connection() + "/" + chain.freshness(),
                List.of("Acesso normal sem carteira"), "HOLDER: " + thresholds.get("HOLDER") + " ubyx (TEST)");
    }
    public BenefitsSnapshot snapshot(String address) {
        if (identity == null || address == null || address.isBlank()) return disabled("NO WALLET", address, null);
        var wallet = identity.wallets().stream().filter(w -> w.address().equals(address)).findFirst();
        if (wallet.isEmpty()) return disabled("WATCH-ONLY", address, null);
        var w = wallet.get();
        if (!w.validAt(clock.instant())) return disabled("EXPIRED/REVOKED", address, null);
        Cached c = cached;
        if (c == null || !c.wallet().equals(w)) return disabled("VERIFIED", address, null);
        ByxSnapshot chain = c.chain(); Instant now = clock.instant();
        boolean trusted = "LIVE_NODE".equals(chain.source()) && "LOCALNET".equals(chain.environment())
                && "VERIFIED".equals(chain.identity()) && w.chainId().equals(chain.chainId()) && address.equals(chain.address())
                && "ONLINE".equals(chain.connection()) && "FRESH".equals(chain.freshness()) && Boolean.FALSE.equals(chain.syncing())
                && chain.updatedAt() != null && !chain.updatedAt().isAfter(now) && chain.updatedAt().isAfter(now.minusSeconds(60))
                && chain.blockTime() != null && !chain.blockTime().isAfter(now.plusSeconds(30)) && chain.blockTime().isAfter(now.minusSeconds(60))
                && chain.balance() != null && chain.balance().signum() >= 0 && "BYX".equals(chain.denom()) && chain.decimals() == 6;
        if (!trusted) return disabled("CHAIN OFFLINE", address, chain);
        String tier = "FREE", next = "Maximum experimental tier";
        for (var entry : thresholds.entrySet()) {
            if (chain.balance().compareTo(entry.getValue()) >= 0) tier = entry.getKey();
            else { next = entry.getKey() + ": " + entry.getValue() + " ubyx (TEST)"; break; }
        }
        boolean enabled = !tier.equals("FREE");
        return new BenefitsSnapshot("VERIFIED", address, chain.balance(), tier, enabled, chain.updatedAt(), "ONLINE/FRESH",
                enabled ? List.of("Selo " + tier + " LOCALNET (simulação)", "Prévia dos benefícios experimentais; sem efeito financeiro")
                        : List.of("Acesso normal sem carteira"), next);
    }
    public CompletableFuture<BenefitsSnapshot> refresh(String address) {
        if (identity == null || identity.verified(address).isEmpty()) return CompletableFuture.completedFuture(snapshot(address));
        var wallet = identity.verified(address).orElseThrow(); var c = identity.network();
        if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(snapshot(address));
        var query = new ByxConfig(c.endpoint(), c.rpcEndpoint(), c.environment(), c.expectedChainId(), c.genesisFingerprint(),
                c.baseDenom(), c.displayDenom(), c.decimals(), c.decimalsSource(), address);
        return CompletableFuture.supplyAsync(() -> {
            try {
                ByxSnapshot result;
                try { result = gateway.read(query); }
                catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    Cached previous = cached;
                    result = previous != null && previous.wallet().equals(wallet)
                            ? previous.chain().stale("OFFLINE", "Cached balance; node unavailable")
                            : ByxSnapshot.unknown(gateway.source(), "LOCALNET", "OFFLINE", "Balance unavailable");
                }
                cached = new Cached(wallet, result);
                return snapshot(address);
            } finally { busy.set(false); }
        }, worker);
    }
    public BigInteger thresholdUbyx(String tier) {
        var value = thresholds.get(tier);
        if (value == null) throw new IllegalArgumentException("Unknown experimental tier");
        return value;
    }
    public void close() { cached = null; worker.shutdownNow(); }

    public PaymentMethods payments() {
        return () -> List.of(new PaymentMethod("Método independente de BYX — a definir", false, false),
                new PaymentMethod("BYX opcional — a definir", true, false));
    }
    public AppDiscountPolicy appDiscounts() { return Optional::empty; }
    public UsagePolicy usage() { return () -> new UsageLimits(true, false, OptionalLong.empty()); }
    public NetworkFeePolicy networkFees() { return Optional::empty; }
    public Assessment assess(String observedAddress) {
        return new Assessment("SIMULAÇÃO / NÃO CONTRATADO", "VERIFICATION_REQUIRED", false, true, false, false, false);
    }
}
