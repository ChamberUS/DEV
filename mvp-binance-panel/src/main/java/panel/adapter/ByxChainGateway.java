package panel.adapter;

import panel.model.ByxConfig;
import panel.model.ByxSnapshot;

public interface ByxChainGateway {
    String source();
    ByxSnapshot read(ByxConfig config) throws Exception;
}
