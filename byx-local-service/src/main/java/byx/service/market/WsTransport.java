package byx.service.market;

import java.net.URI;

/** Abertura de WebSocket (somente URIs da allowlist). As chamadas do ouvinte chegam em ordem, em uma thread do transporte. */
public interface WsTransport {
    interface Listener {
        void onOpen();

        void onText(String message);

        /** Fechamento limpo ou não; code é o código de fechamento do protocolo (ou -1). */
        void onClosed(int code);

        /** statusCode = status HTTP do handshake recusado (429/418 são tratados como limite de taxa), ou -1. */
        void onError(String code, int statusCode);
    }

    interface Handle {
        /** Aborta a conexão (idempotente, não bloqueia). */
        void close();
    }

    Handle connect(URI uri, Listener listener) throws MarketException;
}
