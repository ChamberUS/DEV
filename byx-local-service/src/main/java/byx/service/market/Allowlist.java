package byx.service.market;

import java.net.URI;
import java.util.Set;

/**
 * Lista fechada de URIs que o serviço pode abrir. É comparação EXATA de texto com um conjunto fixo (esquema, host, caminho e query):
 * nada de prefixo, padrão ou sufixo de domínio, então userinfo, porta, fragmento, outro host, outro caminho, outro símbolo e qualquer
 * rota privada/assinada (order, account, listenKey, /private) simplesmente não estão no conjunto. Nenhuma URL chega aqui vinda da
 * UI: o painel só pede operações tipadas, e o símbolo é uma constante do serviço.
 */
public final class Allowlist {
    public static final String SYMBOL = "ETHUSDT";
    public static final String WS_HOST = "fstream.binance.com";
    public static final String REST_HOST = "fapi.binance.com";
    public static final int KLINE_LIMIT = 120;
    public static final int DEPTH_SNAPSHOT_LIMIT = 1000;

    /** WebSocket PÚBLICO (alta frequência): só o depth diferencial de 100 ms. */
    public static final URI WS_PUBLIC = URI.create("wss://" + WS_HOST + "/public/stream?streams=ethusdt@depth@100ms");
    /** WebSocket MARKET (regular): negociações agregadas, mark price, ticker 24h e kline 1m. */
    public static final URI WS_MARKET = URI.create("wss://" + WS_HOST + "/market/stream?streams=ethusdt@aggTrade/ethusdt@markPrice@1s/ethusdt@ticker/ethusdt@kline_1m");
    public static final URI REST_DEPTH = URI.create("https://" + REST_HOST + "/fapi/v1/depth?symbol=" + SYMBOL + "&limit=" + DEPTH_SNAPSHOT_LIMIT);
    public static final URI REST_KLINES = URI.create("https://" + REST_HOST + "/fapi/v1/klines?symbol=" + SYMBOL + "&interval=1m&limit=" + KLINE_LIMIT);

    private final Set<String> allowed;

    private Allowlist(Set<String> allowed) {
        this.allowed = allowed;
    }

    public static Allowlist production() {
        return new Allowlist(Set.of(WS_PUBLIC.toString(), WS_MARKET.toString(), REST_DEPTH.toString(), REST_KLINES.toString()));
    }

    /** Só para testes de transporte contra um servidor local (o conjunto de produção não é afetado). */
    static Allowlist onlyForTests(URI... uris) {
        Set<String> s = new java.util.HashSet<>();
        for (URI u : uris) {
            s.add(u.toString());
        }
        return new Allowlist(Set.copyOf(s));
    }

    public boolean permits(URI uri) {
        return uri != null && allowed.contains(uri.toString());
    }

    public void require(URI uri) throws MarketException {
        if (!permits(uri)) {
            throw new MarketException("not_allowlisted");
        }
    }

    public Set<String> entries() {
        return allowed;
    }
}
