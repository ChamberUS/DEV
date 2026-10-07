package byx.service.chain;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Resultado tipado de uma leitura de módulo. {@code freshness}: LIVE (buscado agora), CACHED (cache válido da geração/época atual) ou STALE (nó indisponível: dado ANTERIOR, nunca apresentado como
 * fresco). Em falha não há dado algum (nada parcial). {@code data} é imutável por convenção (compartilhado entre consumidores coalescidos).
 */
public record ReadResult(boolean ok, ReadFailure failure, Freshness freshness, ObjectNode data, String nextCursor, long ageMs, int generation, String chainId) {
    public enum Freshness { LIVE, CACHED, STALE }

    static ReadResult failure(ReadFailure f, int generation) {
        return new ReadResult(false, f, null, null, null, 0, generation, null);
    }
}
