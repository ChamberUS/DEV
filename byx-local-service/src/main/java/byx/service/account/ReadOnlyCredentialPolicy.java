package byx.service.account;

import byx.service.account.AccountDtos.CredentialPermissions;

/**
 * Política da credencial somente leitura: a chave precisa ter leitura e NENHUM poder de movimentar ou negociar. Função pura (testável sem chave real). Saque, transferência, margem, negociação à
 * vista, opções, margem de portfólio e transferência universal: todos OFF. {@link #FUTURES_PERMISSION_UNRESOLVED}: não está provado que a leitura de futuros USDⓈ-M funciona sem a permissão
 * "Enable Futures" (que também habilita negociar). Até que uma chave somente leitura real prove o contrário, a política RECUSA chave com futuros ligado; se a prova mostrar que a leitura de futuros
 * EXIGE essa permissão, é um BLOQUEADOR a decidir pelo dono (aceitar chave com futuros, restringida por IP) — nunca aceito em silêncio.
 */
public final class ReadOnlyCredentialPolicy {
    public enum Verdict { ACCEPTABLE, REJECTED_NO_READING, REJECTED_WITHDRAWALS, REJECTED_TRANSFER, REJECTED_MARGIN, REJECTED_TRADING, REJECTED_FUTURES, REJECTED_OTHER }

    /** Verdade enquanto não houver prova com chave real. */
    public static final boolean FUTURES_PERMISSION_UNRESOLVED = true;

    private ReadOnlyCredentialPolicy() { }

    public static Verdict evaluate(CredentialPermissions p) {
        if (p == null || !p.reading()) {
            return Verdict.REJECTED_NO_READING;
        }
        if (p.withdrawals()) {
            return Verdict.REJECTED_WITHDRAWALS;
        }
        if (p.internalTransfer() || p.universalTransfer()) {
            return Verdict.REJECTED_TRANSFER;
        }
        if (p.margin() || p.portfolioMargin()) {
            return Verdict.REJECTED_MARGIN;
        }
        if (p.spotAndMarginTrading()) {
            return Verdict.REJECTED_TRADING;
        }
        if (p.futures() && FUTURES_PERMISSION_UNRESOLVED) {
            return Verdict.REJECTED_FUTURES;
        }
        if (p.vanillaOptions()) {
            return Verdict.REJECTED_OTHER;
        }
        return Verdict.ACCEPTABLE;
    }
}
