package byx.service.market;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * GET HTTPS com tudo limitado: allowlist antes de abrir o socket, redirecionamento DESLIGADO (qualquer 3xx é recusado), corpo lido até
 * um teto fixo (também por Content-Length declarado), prazo total. TLS é o padrão da JVM: nenhum contexto TLS, gerenciador de confiança ou verificador
 * de hostname próprio, e nenhum cabeçalho de credencial é enviado (as rotas são públicas e sem assinatura).
 */
public final class BoundedHttp implements HttpGet {
    public static final int MAX_BODY = 256 * 1024;

    private final Allowlist allowlist;
    private final HttpClient client;
    private final Duration timeout;
    private final int maxBody;

    public BoundedHttp(Allowlist allowlist) {
        this(allowlist, Duration.ofSeconds(8), MAX_BODY);
    }

    BoundedHttp(Allowlist allowlist, Duration timeout, int maxBody) {
        this.allowlist = allowlist;
        this.timeout = timeout;
        this.maxBody = maxBody;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    @Override
    public Response get(URI uri) throws IOException {
        allowlist.require(uri);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MarketException("interrupted");
        } catch (IOException e) {
            throw new MarketException("transport_error");
        }
        try (InputStream in = resp.body()) {
            int status = resp.statusCode();
            int weight = (int) resp.headers().firstValueAsLong("x-mbx-used-weight-1m").orElse(-1);
            long retry = resp.headers().firstValueAsLong("retry-after").orElse(-1);
            if (status >= 300 && status < 400) {
                throw new MarketException("redirect_refused");
            }
            if (status != 200) {
                return new Response(status, new byte[0], weight, retry); // o corpo de um erro nunca é lido nem repassado
            }
            long declared = resp.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBody) {
                throw new MarketException("response_too_large");
            }
            byte[] body = in.readNBytes(maxBody + 1);
            if (body.length > maxBody) {
                throw new MarketException("response_too_large");
            }
            return new Response(status, body, weight, retry);
        }
    }
}
