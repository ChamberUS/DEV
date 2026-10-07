package byx.service.account;

import java.math.BigDecimal;

/** DTOs MÍNIMOS que o painel poderá receber (design). Campos desconhecidos do provedor NÃO passam; nenhum identificador externo completo, nenhuma chave, nenhuma assinatura. */
public final class AccountDtos {
    private AccountDtos() { }

    public record AccountBalance(String asset, BigDecimal balance, BigDecimal availableBalance, BigDecimal unrealizedPnl) { }

    public record OpenPosition(String symbol, String positionSide, BigDecimal quantity, BigDecimal entryPrice, BigDecimal markPrice, BigDecimal unrealizedPnl, BigDecimal liquidationPrice) { }

    /** Estado mínimo: nada da conta além de "alcançável" e quando foi lido. */
    public record AccountStatus(boolean reachable, long readAtMs) { }

    /** Permissões da chave, só para decisão local de aceitar/recusar (nunca exibidas). */
    public record CredentialPermissions(boolean reading, boolean withdrawals, boolean internalTransfer, boolean margin, boolean futures, boolean spotAndMarginTrading,
            boolean vanillaOptions, boolean portfolioMargin, boolean universalTransfer) { }
}
