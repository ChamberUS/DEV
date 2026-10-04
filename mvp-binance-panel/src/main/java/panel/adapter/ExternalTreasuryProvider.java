package panel.adapter;

import java.util.List;
import panel.model.TreasuryAsset;

/** Future externally held USDC/USDT/cash: custody, network, units, time and evidence are explicit.
 * No exchange connection, key API, price feed or provider is implemented in V1. */
public interface ExternalTreasuryProvider {
    List<TreasuryAsset> assets() throws Exception;
}
