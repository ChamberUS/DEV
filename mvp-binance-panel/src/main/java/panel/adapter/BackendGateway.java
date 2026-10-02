package panel.adapter;

import panel.model.Settings;
import panel.model.Snapshot;

/** Transport boundary; future remote implementations expose the same snapshot. */
public interface BackendGateway extends ResearchBackend {
    Snapshot load(Settings settings);
    default void invalidate() { }
}
