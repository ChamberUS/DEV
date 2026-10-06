package byx.service;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contrato do protocolo local (versão 1). Quadros de 4 bytes (tamanho, big-endian) + JSON UTF-8 estrito.
 * Tudo que não está aqui é recusado: operações são uma lista fechada e os DTOs não aceitam campos desconhecidos.
 */
public final class Protocol {
    public static final int VERSION = 1;
    /** Maior quadro aceito (bytes). Cabeçalho acima disso fecha a conexão sem ler nem alocar o corpo. */
    public static final int MAX_FRAME = 8 * 1024;
    /** Operações permitidas (allowlist). Nenhuma delas recebe argumentos nem devolve dado de conta. */
    public static final Set<String> OPERATIONS = Set.of("health", "version", "capabilities");
    public static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    public static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{22,64}"); // base64url de 16 a 48 bytes
    public static final Pattern PROOF = Pattern.compile("[A-Za-z0-9_-]{43}"); // base64url de HMAC-SHA256 (32 bytes)

    private Protocol() {
    }

    // ---- DTOs de entrada (estritos) --------------------------------------------------------------------------------

    public record Hello(int v, String type, String clientNonce) {
    }

    public record Auth(int v, String type, String clientProof) {
    }

    public record Request(int v, String id, String op) {
    }

    /** Mapper estrito: sem campos desconhecidos, sem lixo depois do JSON, sem chaves duplicadas, profundidade e strings limitadas. */
    public static JsonMapper mapper() {
        com.fasterxml.jackson.core.JsonFactory factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(1024).maxNumberLength(16).build()).build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS) // "1" nunca vira 1
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }
}
