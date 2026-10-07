package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cliente tipado da leitura PÚBLICA da chain: o painel NÃO fala com RPC/REST/gRPC/WebSocket da chain; pede ao serviço ({@code byx.status}) e valida a resposta. Sem argumentos: o serviço decide
 * endpoint, rotas e parsing. Qualquer resposta fora do contrato vira ERROR (nunca "saudável").
 */
public final class ChainStatusClient {
    public static final Set<String> STATES = Set.of("NOT_CONFIGURED", "CONNECTING", "OFFLINE", "SYNCING", "LIVE", "STALE", "NETWORK_MISMATCH", "ERROR");
    private static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern REASON = Pattern.compile("[A-Z_]{1,32}");

    /** Status mínimo recebido do serviço (sem JSON cru). */
    public record View(String state, boolean configured, boolean reachable, String chainId, Long latestHeight, Boolean catchingUp, Long blockTimeMs, boolean networkMatch, String reason) {
        /** Falha de comunicação ou contrato violado: nunca saudável. */
        public static View error(String reason) {
            return new View("ERROR", false, false, null, null, null, null, false, reason);
        }
    }

    private final LocalServiceClient client;

    public ChainStatusClient(LocalServiceClient client) {
        this.client = client;
    }

    /** Bloqueante (chame fora da thread FX). Nunca lança: falha vira {@link View#error}. */
    public View read() {
        try {
            return parse(client.chainCall("byx.status"));
        } catch (Exception e) {
            return View.error("SERVICE_UNAVAILABLE");
        }
    }

    static View parse(JsonNode r) {
        try {
            String state = r.path("state").asText("");
            if (!STATES.contains(state) || !r.path("configured").isBoolean() || !r.path("reachable").isBoolean() || !r.path("networkMatch").isBoolean()) {
                return View.error("CONTRACT_VIOLATION");
            }
            String reason = r.path("reason").asText("");
            if (!REASON.matcher(reason).matches()) {
                return View.error("CONTRACT_VIOLATION");
            }
            String chainId = null;
            if (r.has("chainId")) {
                chainId = r.path("chainId").asText("");
                if (!CHAIN_ID.matcher(chainId).matches()) {
                    return View.error("CONTRACT_VIOLATION");
                }
            }
            Long height = null;
            if (r.has("latestHeight")) {
                if (!r.path("latestHeight").isIntegralNumber() || r.path("latestHeight").asLong() < 1 || r.path("latestHeight").asLong() > 999_999_999_999_999L) {
                    return View.error("CONTRACT_VIOLATION");
                }
                height = r.path("latestHeight").asLong();
            }
            Long blockTime = null;
            if (r.has("blockTimeMs")) {
                if (!r.path("blockTimeMs").isIntegralNumber() || r.path("blockTimeMs").asLong() <= 0) {
                    return View.error("CONTRACT_VIOLATION");
                }
                blockTime = r.path("blockTimeMs").asLong();
            }
            Boolean catching = null;
            if (r.has("catchingUp")) {
                if (!r.path("catchingUp").isBoolean()) {
                    return View.error("CONTRACT_VIOLATION");
                }
                catching = r.path("catchingUp").asBoolean();
            }
            boolean match = r.path("networkMatch").asBoolean();
            // coerência: só LIVE/SYNCING/STALE carregam altura e exigem a rede esperada; qualquer outro estado com altura é recusado
            boolean carriesHeight = state.equals("LIVE") || state.equals("SYNCING") || state.equals("STALE");
            if (carriesHeight != (height != null && blockTime != null) || carriesHeight && !match || !carriesHeight && height != null) {
                return View.error("CONTRACT_VIOLATION");
            }
            return new View(state, r.path("configured").asBoolean(), r.path("reachable").asBoolean(), chainId, height, catching, blockTime, match, reason);
        } catch (RuntimeException e) {
            return View.error("CONTRACT_VIOLATION");
        }
    }
}
