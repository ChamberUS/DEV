package byx.service.chain;

import java.net.URI;

/**
 * Origem de nó local APROVADA: só http em loopback LITERAL (127.0.0.1 ou [::1]), com porta, sem usuário, caminho, consulta nem fragmento. Recusa 0.0.0.0, IP de LAN/público, nome de host (sem DNS,
 * sem rebinding), file://, unix e qualquer outro esquema. O painel nunca fornece host/porta: a origem vem de configuração empacotada (hoje: nenhuma = NOT_CONFIGURED).
 */
public record ChainEndpoint(String host, int port) {
    public ChainEndpoint {
        if (!("127.0.0.1".equals(host) || "[::1]".equals(host)) || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("loopback origin required");
        }
    }

    public static ChainEndpoint parse(String origin) {
        if (origin == null || origin.length() > 64 || !origin.startsWith("http://")) {
            throw new IllegalArgumentException("loopback origin required");
        }
        URI u;
        try {
            u = URI.create(origin);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("loopback origin required");
        }
        if (!"http".equals(u.getScheme()) || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null || u.getPort() < 0
                || !(u.getRawPath() == null || u.getRawPath().isEmpty() || u.getRawPath().equals("/")) || u.getHost() == null) {
            throw new IllegalArgumentException("loopback origin required");
        }
        return new ChainEndpoint(u.getHost(), u.getPort());
    }

    String base() {
        return "http://" + host + ":" + port;
    }
}
