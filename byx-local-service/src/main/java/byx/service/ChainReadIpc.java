package byx.service;

import byx.service.chain.ChainConnector;
import byx.service.chain.ModuleHealth;
import byx.service.chain.ReadFailure;
import byx.service.chain.ReadOp;
import byx.service.chain.ReadRequest;
import byx.service.chain.ReadResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Operações de leitura PÚBLICA de módulo pelo IPC verificado. Conjunto FECHADO (um nome por {@link ReadOp} + feesplit NOT_EXPOSED + saúde dos módulos). Pedido: só {@code args} tipado e mínimo
 * ({@code id} decimal, {@code address} bech32, {@code limit} 1..10, {@code cursor} opaco tipado); qualquer outro campo é INVALID_REQUEST local, sem rede. Resposta: DTO mínimo, sem JSON do nó,
 * limitado a {@link #MAX_RESULT_BYTES}. Nenhuma operação de escrita, proxy ou consulta genérica.
 */
final class ChainReadIpc {
    /** Teto do resultado serializado (o quadro de resposta do IPC é de 8 KiB). */
    static final int MAX_RESULT_BYTES = 7 * 1024;
    static final Set<String> OPERATIONS;
    private static final Set<String> TOP = Set.of("v", "id", "op", "args");

    static {
        List<String> all = new ArrayList<>();
        for (ReadOp o : ReadOp.values()) {
            all.add(o.wire());
        }
        all.add(ReadOp.FEESPLIT_NOT_EXPOSED);
        all.add(ReadOp.MODULE_HEALTH);
        OPERATIONS = Set.copyOf(all);
    }

    private ChainReadIpc() { }

    static boolean handles(String op) {
        return OPERATIONS.contains(op);
    }

    static void run(ChainConnector chain, String op, JsonNode tree, ObjectNode out) {
        try {
            for (var it = tree.fieldNames(); it.hasNext();) {
                if (!TOP.contains(it.next())) {
                    throw new IllegalArgumentException("field");
                }
            }
            JsonNode args = tree.get("args");
            if (args != null && !args.isObject()) {
                throw new IllegalArgumentException("args");
            }
            if (ReadOp.MODULE_HEALTH.equals(op)) {
                noArgs(args);
                health(chain, out);
            } else if (ReadOp.FEESPLIT_NOT_EXPOSED.equals(op)) {
                noArgs(args);
                feesplit(out);
            } else {
                ReadOp ro = ReadOp.ofWire(op).orElseThrow(() -> new IllegalArgumentException("op"));
                ReadResult r = chain.read(request(ro, args));
                result(r, out);
            }
        } catch (IllegalArgumentException e) {
            out.removeAll();
            out.put("status", "FAILED");
            out.put("failure", ReadFailure.INVALID_REQUEST.name());
        }
        if (out.toString().length() > MAX_RESULT_BYTES) { // string JSON ≈ bytes (campos ASCII/limitados); cheque barato e conservador
            out.removeAll();
            out.put("status", "FAILED");
            out.put("failure", ReadFailure.RESPONSE_TOO_LARGE.name());
        }
    }

    private static void noArgs(JsonNode args) {
        if (args != null && args.size() > 0) {
            throw new IllegalArgumentException("args");
        }
    }

    private static ReadRequest request(ReadOp op, JsonNode args) {
        Set<String> allowed = switch (op.arg()) {
            case NONE -> Set.of();
            case ID -> Set.of("id");
            case LIST -> Set.of("limit", "cursor");
            case ID_LIST -> Set.of("id", "limit", "cursor");
            case ADDRESS -> Set.of("address");
        };
        if (args != null) {
            for (var it = args.fieldNames(); it.hasNext();) {
                if (!allowed.contains(it.next())) {
                    throw new IllegalArgumentException("arg");
                }
            }
        }
        return ReadRequest.of(op, text(args, "id"), text(args, "address"), integer(args, "limit"), text(args, "cursor"));
    }

    private static String text(JsonNode args, String f) {
        JsonNode v = args == null ? null : args.get(f);
        if (v == null) {
            return null;
        }
        if (!v.isTextual()) {
            throw new IllegalArgumentException(f);
        }
        return v.asText();
    }

    private static Integer integer(JsonNode args, String f) {
        JsonNode v = args == null ? null : args.get(f);
        if (v == null) {
            return null;
        }
        if (!v.isInt()) {
            throw new IllegalArgumentException(f);
        }
        return v.asInt();
    }

    private static void result(ReadResult r, ObjectNode out) {
        if (!r.ok()) {
            out.put("status", "FAILED");
            out.put("failure", r.failure().name());
            return;
        }
        out.put("status", "OK");
        out.put("freshness", r.freshness().name()); // LIVE | CACHED | STALE: o cache nunca se passa por dado fresco
        out.put("ageMs", r.ageMs());
        out.put("generation", r.generation());
        out.put("chainId", r.chainId());
        out.set("data", r.data().deepCopy());
        if (r.nextCursor() != null) {
            out.put("nextCursor", r.nextCursor());
        }
    }

    private static void health(ChainConnector chain, ObjectNode out) {
        out.put("node", chain.status().state().name()); // estado do NÓ, separado do estado de cada módulo
        var arr = out.putArray("modules");
        for (ModuleHealth.Snapshot s : chain.moduleHealth()) {
            var o = arr.addObject();
            o.put("module", s.module().name());
            o.put("state", s.state().name());
            o.put("ok", s.ok());
            o.put("failed", s.failed());
            o.put("lastLatencyMs", s.lastLatencyMs());
            o.put("lastSuccessAgeMs", s.lastSuccessAgeMs());
            if (s.lastFailure() != null) {
                o.put("lastFailure", s.lastFailure().name());
            }
        }
        if (arr.isEmpty()) { // não configurado: o conjunto de módulos continua descrito, sem estado
            for (ReadOp.Module m : ReadOp.Module.values()) {
                arr.addObject().put("module", m.name()).put("state", m == ReadOp.Module.FEESPLIT ? "NOT_EXPOSED" : "UNKNOWN");
            }
        }
        long[] c = chain.readCounters();
        var rc = out.putObject("reads");
        rc.put("fetches", c[0]);
        rc.put("cacheHits", c[1]);
        rc.put("coalesced", c[2]);
        rc.put("rateLimited", c[3]);
        out.put("modulesSummary", chain.moduleHealth().stream().map(s -> s.module() + "=" + s.state()).collect(Collectors.joining(",")));
    }

    /** Feesplit: a chain NÃO expõe Query service nem rota (NOT_EXPOSED). A alocação abaixo é o PADRÃO DE PROJETO do módulo (fonte: código/genesis default da chain), NÃO lido do nó: a tela deve rotulá-la assim. */
    private static void feesplit(ObjectNode out) {
        out.put("status", "NOT_EXPOSED");
        out.put("exposed", false);
        out.put("source", "DOCUMENTED_DEFAULT_NOT_QUERIED");
        var a = out.putObject("allocationBps");
        a.put("distribution", 6000);
        a.put("treasury", 3000);
        a.put("burn", 1000);
    }
}
