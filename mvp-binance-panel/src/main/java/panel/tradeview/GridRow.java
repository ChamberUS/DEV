package panel.tradeview;

import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * Linha de três colunas iguais (Price | Size | Total/Time) do Order Book e do Recent Trades, 22 px (referência
 * .or). Os nós são criados uma vez; um tick só troca texto, classe de tom e a largura da barra de profundidade.
 * Em esqueleto a linha mostra três barras cinzas e NENHUMA barra de profundidade: o esqueleto mostra estrutura,
 * nunca dado.
 */
final class GridRow extends Pane {
    static final double HEIGHT = 22;
    private static final double PAD = 8;
    private static final double GAP = 14;
    private static final double[] SKELETON = {.70, .55, .65};

    private final Region depth = new Region();
    private final Label[] cells = new Label[3];
    private final Region[] bars = {Fx.skeleton(), Fx.skeleton(), Fx.skeleton()};
    private double depthFraction;
    private boolean skeleton;

    GridRow(String... styleClasses) {
        getStyleClass().addAll("byx-desk-grid-row");
        getStyleClass().addAll(styleClasses);
        depth.getStyleClass().add("byx-desk-depth");
        depth.setMouseTransparent(true);
        getChildren().add(depth);
        for (int i = 0; i < 3; i++) {
            cells[i] = new Label();
            cells[i].getStyleClass().add("byx-desk-cell");
            cells[i].setMinWidth(0);
            getChildren().add(cells[i]);
        }
        getChildren().addAll(bars);
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        showSkeleton(false);
        showDepth(0, null);
    }

    /** Cabeçalho (rótulos). */
    GridRow header(String a, String b, String c) {
        getStyleClass().add("header");
        for (int i = 0; i < 3; i++) {
            cells[i].getStyleClass().add("byx-label");
        }
        text(a, b, c);
        return this;
    }

    void text(String a, String b, String c) {
        Fx.text(cells[0], a);
        Fx.text(cells[1], b);
        Fx.text(cells[2], c);
    }

    Label cell(int i) {
        return cells[i];
    }

    /** "ask", "bid", "buy", "sell" ou null para a coluna do preço. */
    void priceTone(String tone) {
        Fx.tone(cells[0], tone == null ? null : "tone-" + tone, "tone-ask", "tone-bid", "tone-buy", "tone-sell");
    }

    /** side: "ask" (barra vermelha), "bid" (verde) ou null (sem barra). fraction 0..1. */
    void showDepth(double fraction, String side) {
        boolean on = side != null && fraction > 0 && !skeleton;
        double f = on ? Math.min(1, fraction) : 0;
        Fx.shown(depth, on);
        Fx.cls(depth, "ask", "ask".equals(side));
        Fx.cls(depth, "bid", "bid".equals(side));
        if (f != depthFraction) {
            depthFraction = f;
            requestLayout();
        }
    }

    double depthFraction() {
        return depthFraction;
    }

    boolean skeleton() {
        return skeleton;
    }

    void showSkeleton(boolean on) {
        skeleton = on;
        for (int i = 0; i < 3; i++) {
            Fx.shown(bars[i], on);
            Fx.visible(cells[i], !on);
        }
        if (on) {
            showDepth(0, null);
        }
    }

    @Override
    protected double computePrefWidth(double height) {
        return 180;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        double col = Math.max(0, (w - 2 * PAD - 2 * GAP) / 3);
        depth.resizeRelocate(w * (1 - depthFraction), 0, w * depthFraction, h);
        for (int i = 0; i < 3; i++) {
            double x = PAD + i * (col + GAP);
            cells[i].resizeRelocate(x, 0, col, h);
            bars[i].resizeRelocate(x, (h - 8) / 2, col * SKELETON[i], 8);
        }
    }
}
