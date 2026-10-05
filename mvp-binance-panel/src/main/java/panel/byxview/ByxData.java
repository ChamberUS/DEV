package panel.byxview;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import panel.model.BenefitsSnapshot;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;
import panel.model.Entitlement;
import panel.model.TreasurySnapshot;
import panel.model.VerifiedWallet;
import panel.repository.GasGrantRepository;
import panel.service.EntitlementService;

/**
 * Fonte somente leitura das telas BYX V2. Nada aqui assina, transmite, move fundos ou cria saldo: o adaptador do app
 * expõe leituras de cadeia e do repositório local. {@link #configure} só aponta o app para um nó LOCALNET de leitura.
 */
public interface ByxData {
    ByxSnapshot network();

    /** Há sessão de usuário (leituras que dependem dela falham sem). */
    boolean sessionActive();

    boolean admin();

    /** Configuração atual (só com sessão de admin); null quando não há. */
    ByxConfig config();

    void configure(ByxConfig config);

    void refreshNetwork();

    /** Carteiras verificadas do usuário; lança RuntimeException quando LOCALNET não está configurada ou não há sessão. */
    List<VerifiedWallet> wallets();

    BenefitsSnapshot benefits(String address);

    List<Entitlement> entitlements(String address);

    EntitlementService.Progress progress(String address);

    CompletableFuture<?> refreshBenefits(String address);

    Optional<GasGrantRepository.Entry> gasGrant(String address);

    /** Leitura de cadeia (bloqueante: chame fora da thread FX). Lança quando a tesouraria não pode ser verificada. */
    TreasurySnapshot refreshTreasury() throws Exception;
}
