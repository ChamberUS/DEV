package byx.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import byx.service.market.MarketFeed;
import byx.service.market.MarketView;
import java.time.Instant;

/**
 * Operações permitidas, sem argumentos e sem dado privado: nada de conta, saldo, administrador, caminho local, variável de ambiente
 * ou versão do sistema. As capacidades privadas aparecem declaradas como BLOQUEADAS, com o motivo.
 */
final class Operations {
    static final String SERVICE = "byx-local-service";
    static final String VERSION = "0.1.0";

    private final String instanceId;
    private final Instant startedAt;
    private final MarketFeed market;

    Operations(String instanceId, Instant startedAt, MarketFeed market) {
        this.instanceId = instanceId;
        this.startedAt = startedAt;
        this.market = market;
    }

    boolean supports(String op) {
        return Protocol.OPERATIONS.contains(op) || market != null && Protocol.MARKET_OPERATIONS.contains(op);
    }

    ObjectNode run(String op, ObjectNode out) {
        switch (op) {
            case "health" -> {
                out.put("status", "ok");
                out.put("uptimeSeconds", Math.max(0, Duration.between(startedAt, Instant.now()).getSeconds()));
                out.put("instanceId", instanceId);
            }
            case "version" -> {
                out.put("service", SERVICE);
                out.put("version", VERSION);
                out.put("protocolMin", Protocol.VERSION);
                out.put("protocolMax", Protocol.VERSION);
            }
            case "capabilities" -> {
                var ops = out.putArray("operations");
                java.util.stream.Stream.concat(Protocol.OPERATIONS.stream(), market == null ? java.util.stream.Stream.<String>empty() : Protocol.MARKET_OPERATIONS.stream())
                        .sorted().forEach(ops::add);
                var features = out.putObject("features");
                // CAPACIDADE suportada (feed montado), distinta do ESTADO do feed (market.status): marketData=true com feed=DISCONNECTED é válido
                features.put("marketData", market != null);
                features.put("notifications", false);
                features.put("accountData", false);
                features.put("adminOperations", false);
                var identity = out.putObject("identity");
                identity.put("peer", "pairing_secret_same_user");
                identity.put("userAuthentication", "not_implemented");
                identity.put("authorization", market != null ? "service_status_and_public_market_data" : "service_status_only");
                out.put("mode", "development_local_same_user");
                out.put("privateCapabilities", "blocked_until_user_identity_and_authorization_are_verified_by_this_service");
            }
            case "market.status" -> {
                MarketView v = market.view();
                out.put("symbol", byx.service.market.Allowlist.SYMBOL);
                out.put("market", "USD-M");
                out.put("feed", v.feed().name());
                out.put("reason", v.reason());
                out.put("book", v.book().name());
                out.put("updatedAtMs", v.updatedAtMs());
                out.put("nowMs", System.currentTimeMillis());
                out.put("subscribers", market.subscribers());
                out.put("reconnects", v.reconnects());
                out.put("rotations", v.rotations());
                out.put("resyncs", v.resyncs());
                out.put("rejected", v.rejected());
            }
            default -> throw new IllegalArgumentException("not allowlisted");
        }
        return out;
    }
}
