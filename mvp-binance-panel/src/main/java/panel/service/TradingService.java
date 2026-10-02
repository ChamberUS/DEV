package panel.service;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import panel.adapter.TradingProvider;
import panel.model.DataSource;
import panel.model.Settings;
import panel.model.Snapshot;
import panel.model.TraderSnapshot;

/** Deriva a visão do Trader a partir do mesmo Snapshot de pesquisa e do provider ativo. */
public class TradingService {
    public final ObjectProperty<TraderSnapshot> snapshot = new SimpleObjectProperty<>(new TraderSnapshot());

    private final Settings settings;
    private final TradingProvider real;
    private final TradingProvider mock;

    public TradingService(Settings settings, TradingProvider real, TradingProvider mock) {
        this.settings = settings;
        this.real = real;
        this.mock = mock;
    }

    public void update(Snapshot research) {
        snapshot.set((settings.dataSource == DataSource.MOCK ? mock : real).load(research));
    }
}
