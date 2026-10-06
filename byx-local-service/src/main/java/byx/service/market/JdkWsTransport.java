package byx.service.market;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket sobre o cliente padrão da JVM (TLS padrão, sem redirecionamento). Cada mensagem é remontada com teto fixo; passar do teto
 * aborta a conexão. Ping do servidor é respondido pelo próprio JDK (pong). O serviço não envia mensagens além de pong, então o limite
 * de 10 mensagens/s de entrada da Binance não é tocado.
 */
public final class JdkWsTransport implements WsTransport {
    public static final int MAX_MESSAGE = 128 * 1024;

    private final Allowlist allowlist;
    private final HttpClient client;
    private final int maxMessage;

    public JdkWsTransport(Allowlist allowlist) {
        this(allowlist, MAX_MESSAGE);
    }

    JdkWsTransport(Allowlist allowlist, int maxMessage) {
        this.allowlist = allowlist;
        this.maxMessage = maxMessage;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Handle connect(URI uri, Listener listener) throws MarketException {
        allowlist.require(uri);
        AtomicBoolean ended = new AtomicBoolean();
        WebSocket[] ref = new WebSocket[1];
        var future = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, new WebSocket.Listener() {
            private final StringBuilder buf = new StringBuilder();
            private boolean overflow;

            @Override
            public void onOpen(WebSocket ws) {
                ref[0] = ws;
                listener.onOpen();
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                if (!overflow) {
                    if (buf.length() + data.length() > maxMessage) {
                        overflow = true;
                        buf.setLength(0);
                        if (ended.compareAndSet(false, true)) {
                            ws.abort();
                            listener.onError("message_too_large", -1);
                        }
                    } else {
                        buf.append(data);
                        if (last) {
                            String m = buf.toString();
                            buf.setLength(0);
                            listener.onText(m);
                        }
                    }
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                if (ended.compareAndSet(false, true)) {
                    listener.onClosed(statusCode);
                }
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                if (ended.compareAndSet(false, true)) {
                    listener.onError("transport_error", -1);
                }
            }
        });
        future.whenComplete((ws, err) -> {
            if (err != null && ended.compareAndSet(false, true)) {
                Throwable t = err.getCause() != null ? err.getCause() : err;
                int status = t instanceof WebSocketHandshakeException h ? h.getResponse().statusCode() : -1;
                listener.onError(status > 0 ? "handshake_refused" : "connect_failed", status);
            }
        });
        return () -> {
            ended.set(true);
            WebSocket ws = ref[0];
            if (ws != null) {
                ws.abort();
            }
            future.cancel(true);
        };
    }
}
