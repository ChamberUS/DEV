package byx.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
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

    Operations(String instanceId, Instant startedAt) {
        this.instanceId = instanceId;
        this.startedAt = startedAt;
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
                Protocol.OPERATIONS.stream().sorted().forEach(ops::add);
                var features = out.putObject("features");
                features.put("marketData", false);
                features.put("notifications", false);
                features.put("accountData", false);
                features.put("adminOperations", false);
                var identity = out.putObject("identity");
                identity.put("peer", "pairing_secret_same_user");
                identity.put("userAuthentication", "not_implemented");
                identity.put("authorization", "service_status_only");
                out.put("mode", "development_local_same_user");
                out.put("privateCapabilities", "blocked_until_user_identity_and_authorization_are_verified_by_this_service");
            }
            default -> throw new IllegalArgumentException("not allowlisted");
        }
        return out;
    }
}
