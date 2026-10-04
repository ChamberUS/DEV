package panel.service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import panel.model.BenefitsSnapshot;
import panel.model.Entitlement;

/** Application previews only; not an authority for roles, research data or execution. */
public final class EntitlementService {
    private static final List<String> TIERS = List.of("FREE", "HOLDER", "PLUS", "PRO");
    private record Feature(String id, String name, String tier) { }
    private static final List<Feature> FEATURES = List.of(
            new Feature("extended_history", "Extended History", "HOLDER"),
            new Feature("advanced_analytics", "Advanced Analytics", "PLUS"),
            new Feature("advanced_bot_controls", "Advanced Bot Controls — interface preview", "PLUS"),
            new Feature("premium_research_tools", "Premium Research Tools — synthetic preview", "PRO"));
    public record HistoryEntry(Instant refreshedAt, BigInteger balanceUbyx, String tier) { }
    public record Progress(String nextTier, BigInteger requiredUbyx, BigInteger currentUbyx,
            BigInteger remainingUbyx) { }
    private final ByxBenefitsService benefits;
    private final ByxPaymentService payments;
    private final Map<String, Deque<HistoryEntry>> history = new HashMap<>();
    public EntitlementService(ByxBenefitsService benefits) { this(benefits,null); }
    public EntitlementService(ByxBenefitsService benefits,ByxPaymentService payments) {
        this.benefits=Objects.requireNonNull(benefits);this.payments=payments;
    }

    public List<Entitlement> snapshot(String address) {
        var tiers=resolve(benefits.snapshot(address));
        if(payments==null)return tiers;
        var paid=payments.active(address);
        if(paid.isEmpty())return tiers;
        var receipt=paid.get();
        return tiers.stream().map(e -> e.id().equals("advanced_analytics")&&!e.enabled()
                ?new Entitlement(e.id(),e.displayName(),e.requiredTier(),true,"BYX_PAYMENT",receipt.expiresAt(),
                    Entitlement.Status.UNLOCKED,receipt.startsAt(),receipt.paymentIntentId(),receipt.txHash()):e).toList();
    }
    private List<Entitlement> resolve(BenefitsSnapshot s) {
        boolean fresh = "VERIFIED".equals(s.walletStatus()) && "ONLINE/FRESH".equals(s.chainState())
                && s.balanceUbyx() != null && TIERS.contains(s.tier());
        int level = fresh && s.benefitsEnabled() ? TIERS.indexOf(s.tier()) : 0;
        boolean unavailable = "CHAIN OFFLINE".equals(s.walletStatus())
                || ("VERIFIED".equals(s.walletStatus()) && !fresh);
        return FEATURES.stream().map(f -> {
            boolean enabled = level >= TIERS.indexOf(f.tier());
            return new Entitlement(f.id(), f.name(), f.tier(), enabled, "LOCALNET_TEST/HOLD_TO_UNLOCK",
                    fresh && s.lastChainUpdate() != null ? s.lastChainUpdate().plusSeconds(60) : null,
                    enabled ? Entitlement.Status.UNLOCKED : unavailable ? Entitlement.Status.UNAVAILABLE : Entitlement.Status.LOCKED);
        }).toList();
    }
    public boolean allows(String address, String id) {
        return snapshot(address).stream().anyMatch(e -> e.id().equals(id) && e.enabled());
    }
    public synchronized List<HistoryEntry> extendedHistory(String address) {
        var s = benefits.snapshot(address);
        if (resolve(s).stream().noneMatch(e -> e.id().equals("extended_history") && e.enabled()))
            throw new panel.security.AccessDeniedException("Extended History entitlement required");
        var entries = history.computeIfAbsent(address, a -> new ArrayDeque<>());
        var entry = new HistoryEntry(s.lastChainUpdate(), s.balanceUbyx(), s.tier());
        if (!entry.equals(entries.peekLast())) {
            entries.addLast(entry);
            if (entries.size() > 20) entries.removeFirst();
        }
        return List.copyOf(entries);
    }
    public List<String> analyticsPreview(String address) {
        if(!allows(address,"advanced_analytics"))throw new panel.security.AccessDeniedException("Analytics entitlement required");
        return List.of("SYNTHETIC DEMO / NO RESEARCH DATA", "Sample observations: 20", "Sample processing time: 12 ms");
    }
    public Progress progress(String address) {
        var s = benefits.snapshot(address);
        int index = TIERS.indexOf(s.tier());
        if (index < 0 || index == TIERS.size() - 1)
            return new Progress("MAXIMUM", null, s.balanceUbyx(), null);
        String next = TIERS.get(index + 1);
        BigInteger required = benefits.thresholdUbyx(next);
        BigInteger current = s.balanceUbyx();
        return new Progress(next, required, current, current == null ? null : required.subtract(current).max(BigInteger.ZERO));
    }
}
