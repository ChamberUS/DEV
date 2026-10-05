package panel.tradeview;

import com.fasterxml.jackson.databind.JsonNode;
import panel.design.DesignTokens;

/**
 * Contrato responsivo do Trading Desk (handoff §3, BYX_DESIGN_TOKENS.json#breakpoints). Não há escala
 * proporcional: a informação é reorganizada. Os números vêm do JSON; nada é copiado aqui.
 * <ul>
 * <li>COMPACT (1440): coluna de contexto 330 com abas, 3 níveis por lado, 3 trades, Bot em faixa, blotter 7 colunas / 214.</li>
 * <li>STANDARD (1600): coluna 350 empilhada, 6 níveis, 4 trades, Bot com 4 linhas, blotter 8 colunas / 240.</li>
 * <li>EXPANDED (1920): duas colunas (330 Market, 350 Context), 10 níveis, 9 trades, Bot completo, blotter 10 colunas / 300.</li>
 * </ul>
 */
public enum DeskMode {
    COMPACT("compact"), STANDARD("standard"), EXPANDED("expanded");

    /** Largura de conteúdo (janela − rail 68) a partir da qual cada modo vale; 1440→1372, 1600→1532, 1920→1852. */
    public static final double STANDARD_FROM = 1480;
    public static final double EXPANDED_FROM = 1700;

    public final String key;
    /** Coluna de mercado (book + trades). Em COMPACT e STANDARD é a única coluna lateral. */
    public final double marketColumn;
    /** Coluna de contexto (Bot, Risk, Freshness, Activity); só em EXPANDED, senão 0. */
    public final double contextColumn;
    public final int bookLevels;
    public final int tradeRows;
    public final double blotterHeight;
    public final int blotterColumns;

    DeskMode(String key) {
        this.key = key;
        DesignTokens t = DesignTokens.get();
        String base = "breakpoints." + key;
        JsonNode right = t.node(base).path("rightColumns");
        if (right.isArray()) {
            marketColumn = right.get(0).asDouble();
            contextColumn = right.get(1).asDouble();
        } else {
            marketColumn = t.number(base + ".rightColumn");
            contextColumn = 0;
        }
        bookLevels = (int) t.number(base + ".orderBookLevels");
        tradeRows = (int) t.number(base + ".tradesRows");
        blotterHeight = t.number(base + ".blotterHeight");
        blotterColumns = (int) t.number(base + ".blotterColumns");
    }

    public static DeskMode of(double contentWidth) {
        return contentWidth >= EXPANDED_FROM ? EXPANDED : contentWidth >= STANDARD_FROM ? STANDARD : COMPACT;
    }

    /** COMPACT agrupa Market / Bot / Risk em abas dentro de uma única coluna. */
    public boolean tabbedContext() {
        return this == COMPACT;
    }

    public boolean twoColumns() {
        return this == EXPANDED;
    }
}
