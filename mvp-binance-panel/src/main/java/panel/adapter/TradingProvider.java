package panel.adapter;

import panel.model.Snapshot;
import panel.model.TraderSnapshot;

/** Fonte do estado operacional. Hoje só pesquisa/mock; amanhã o engine de execução. */
public interface TradingProvider {
    TraderSnapshot load(Snapshot research);
}
