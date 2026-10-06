package byx.service.auth;

import java.util.Optional;

/** Onde a âncora é guardada. Produção: keychain de proteção de dados do serviço. Testes/IDE: memória (nunca persiste: sem âncora o estado existente não é confiável). */
public interface Anchor {
    final class AnchorException extends Exception {
        public AnchorException(String code) {
            super(code, null, false, false);
        }
    }

    Optional<AnchorData> read() throws AnchorException;

    void write(AnchorData data) throws AnchorException;
}
