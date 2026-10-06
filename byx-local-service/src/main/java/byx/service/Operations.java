package byx.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import byx.service.identity.IdentityPolicy;
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
    private final IdentityPolicy.Mode identityMode;
    private final boolean authentication;

    Operations(String instanceId, Instant startedAt, MarketFeed market, IdentityPolicy.Mode identityMode, boolean authentication) {
        this.authentication = authentication;
        this.identityMode = identityMode;
        this.instanceId = instanceId;
        this.startedAt = startedAt;
        this.market = market;
    }

    boolean supports(String op) {
        return Protocol.OPERATIONS.contains(op) || authentication && byx.service.auth.AuthIpc.OPERATIONS.contains(op) || market != null && Protocol.MARKET_OPERATIONS.contains(op);
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
                if (authentication) {
                    byx.service.auth.AuthIpc.OPERATIONS.stream().sorted().forEach(ops::add);
                }
                var features = out.putObject("features");
                // CAPACIDADE suportada (feed montado), distinta do ESTADO do feed (market.status): marketData=true com feed=DISCONNECTED é válido
                features.put("marketData", market != null);
                // autenticação: só a composição de QA da autoridade a monta (o produto não: o app normal segue o fluxo atual)
                features.put("authentication", authentication);
                // capacidades PRIVADAS: vêm da decisão estática PrivateCapabilityGate (false), nunca de configuração, ambiente ou pedido
                features.put("notifications", PrivateCapabilityGate.allowed("notifications"));
                features.put("accountData", PrivateCapabilityGate.allowed("accountData"));
                features.put("adminOperations", PrivateCapabilityGate.allowed("adminOperations"));
                features.put("secretIntegrations", PrivateCapabilityGate.allowed("secretIntegrations"));
                var gate = out.putObject("privateGate");
                gate.put("allowed", PrivateCapabilityGate.PRIVATE_CAPABILITIES_ALLOWED);
                gate.put("reviewRequired", PrivateCapabilityGate.EXPLICIT_REVIEW_REQUIRED);
                var unmet = gate.putArray("unmetPrerequisites");
                PrivateCapabilityGate.PREREQUISITES.forEach(unmet::add);
                var identity = out.putObject("identity");
                identity.put("peer", identityMode == IdentityPolicy.Mode.PACKAGED_VERIFIED ? "verified_app_code_identity_and_pairing_secret" : "pairing_secret_same_user");
                identity.put("userAuthentication", "not_implemented");
                identity.put("appIdentity", identityMode.wire); // packaged_verified | development_unverified (nunca habilita capacidade privada)
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
