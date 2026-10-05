package panel.app;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import panel.byxview.ByxData;
import panel.model.BenefitsSnapshot;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;
import panel.model.Entitlement;
import panel.model.TreasurySnapshot;
import panel.model.VerifiedWallet;
import panel.repository.GasGrantRepository;
import panel.service.EntitlementService;

/** Liga as telas BYX V2 aos serviços existentes. Só leituras: nada assina, transmite ou move fundos. */
final class ByxDataAdapter implements ByxData {
    private final AppContext ctx;

    ByxDataAdapter(AppContext ctx) {
        this.ctx = ctx;
    }

    @Override public ByxSnapshot network() { return ctx.byx.snapshot(); }

    @Override public boolean sessionActive() { return ctx.sessions.user().isPresent(); }

    @Override public boolean admin() { return ctx.adminAccess.hasValidAdminSession(); }

    @Override
    public ByxConfig config() {
        try {
            return ctx.byx.details();
        } catch (panel.security.AccessDeniedException denied) {
            return null;
        }
    }

    @Override public void configure(ByxConfig config) { ctx.byx.configure(config); }

    @Override public void refreshNetwork() { ctx.byx.refresh(); }

    @Override public List<VerifiedWallet> wallets() { return ctx.byxWallets.wallets(); }

    @Override public BenefitsSnapshot benefits(String address) { return ctx.byxBenefits.snapshot(address); }

    @Override public List<Entitlement> entitlements(String address) { return ctx.byxEntitlements.snapshot(address); }

    @Override public EntitlementService.Progress progress(String address) { return ctx.byxEntitlements.progress(address); }

    @Override public CompletableFuture<?> refreshBenefits(String address) { return ctx.byxBenefits.refresh(address); }

    @Override
    public Optional<GasGrantRepository.Entry> gasGrant(String address) {
        try {
            return ctx.byxGas.journal().stream().filter(e -> e.address().equals(address)).findFirst();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override public TreasurySnapshot refreshTreasury() throws Exception { return ctx.byxTreasury.refresh(); }
}
