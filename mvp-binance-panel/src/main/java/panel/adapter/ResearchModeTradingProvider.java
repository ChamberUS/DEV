package panel.adapter;

import panel.model.Snapshot;
import panel.model.StageState;
import panel.model.TraderSnapshot;

/** Estado real nesta fase: sem Binance, sem conta, trading desabilitado. Só deriva o status da estratégia da pesquisa. */
public class ResearchModeTradingProvider implements TradingProvider {
    @Override
    public TraderSnapshot load(Snapshot research) {
        TraderSnapshot t = new TraderSnapshot();
        t.loading = research.loading;
        t.backendOnline = research.backendOnline;
        t.recorder = research.capture.recorder();
        t.symbol = research.capture.symbol();
        t.market = research.capture.market();
        long approved = research.hypotheses.stream().filter(h -> h.state() == StageState.READY).count();
        t.strategy = "None approved";
        t.strategyStatus = "IN RESEARCH · " + approved + " / " + research.hypotheses.size() + " hypotheses completed";
        t.strategyRows.add(new String[] {"Microstructure Alpha", "v1", "IN RESEARCH", "Not approved"});
        return t;
    }
}
