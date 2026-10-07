package panel.adapter;

import panel.model.ByxConfig;
import panel.model.ByxSnapshot;

public interface ByxChainGateway {
    String source();

    /** true = o ENDPOINT pertence a outro componente (o serviço local): o painel lê sem configuração própria e nunca envia host/porta. */
    default boolean ownsEndpoint() {
        return false;
    }
    ByxSnapshot read(ByxConfig config) throws Exception;
}
