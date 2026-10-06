package byx.service.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Transporte HTTP dos provedores de 2º fator. A implementação de produção só fala HTTPS com um conjunto FIXO de hosts (nada vem da UI, de IPC ou de
 * configuração), sem redirecionamento, com prazos e teto de resposta; testes injetam um dublê (nenhum e-mail/SMS real em teste).
 */
public interface HttpTransport {
    record Response(int status, String body) {
    }

    Response post(String url, Map<String, String> headers, String contentType, byte[] body) throws IOException;

    /** Hosts permitidos em produção. */
    Set<String> ALLOWED_HOSTS = Set.of("api.resend.com", "verify.twilio.com");

    static HttpTransport jdk() {
        return (url, headers, contentType, body) -> {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !ALLOWED_HOSTS.contains(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1) {
                throw new IOException("host_not_allowed");
            }
            try (HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach(b::header);
                HttpResponse<java.io.InputStream> r = c.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (java.io.InputStream in = r.body()) {
                    byte[] cap = in.readNBytes(64 * 1024 + 1);
                    if (cap.length > 64 * 1024) {
                        throw new IOException("response_too_large");
                    }
                    return new Response(r.statusCode(), new String(cap, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        };
    }
}
