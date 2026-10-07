package byx.service.chain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** HTTP GET em loopback: sem redirecionamento, sem proxy, prazo curto e leitura LIMITADA do corpo (nunca confia no nó só por estar em localhost). */
public final class LoopbackHttp implements ChainTransport {
    private final HttpClient client;
    private final Duration requestTimeout;

    public LoopbackHttp(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build();
        this.requestTimeout = requestTimeout;
    }

    public static LoopbackHttp production() {
        return new LoopbackHttp(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Override
    public byte[] get(ChainEndpoint endpoint, String pathAndQuery, int maxBytes) throws ChainException {
        if (pathAndQuery == null || !pathAndQuery.startsWith("/") || pathAndQuery.contains("..") || pathAndQuery.contains("#")) {
            throw new ChainException(ChainReason.SCHEMA_INVALID);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.base() + pathAndQuery)).timeout(requestTimeout).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    throw new ChainException(ChainReason.REDIRECT_REFUSED);
                }
                if (status != 200) {
                    byte[] err = status >= 400 && status < 500 ? in.readNBytes(1024) : null; // corpo de erro 4xx, limitado, só para o mapeamento interno (não encontrado)
                    throw new ChainException(ChainReason.HTTP_STATUS, status, err);
                }
                long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
                if (declared > maxBytes) {
                    throw new ChainException(ChainReason.RESPONSE_TOO_LARGE);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > maxBytes) {
                        throw new ChainException(ChainReason.RESPONSE_TOO_LARGE);
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (ChainException e) {
            throw e; // razão já tipada: não vira UNREACHABLE
        } catch (HttpTimeoutException e) {
            throw new ChainException(ChainReason.TIMEOUT);
        } catch (java.net.ConnectException e) {
            throw new ChainException(ChainReason.UNREACHABLE);
        } catch (IOException e) {
            if (e instanceof java.net.SocketTimeoutException) {
                throw new ChainException(ChainReason.TIMEOUT);
            }
            throw new ChainException(ChainReason.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChainException(ChainReason.UNREACHABLE);
        }
    }
}
