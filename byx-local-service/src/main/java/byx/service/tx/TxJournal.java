package byx.service.tx;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Diário de transação (semente). Cada transição relevante é registrada ANTES do efeito seguinte; em particular o hash da transação é gravado
 * antes de transmitir, para que uma resposta perdida possa ser resolvida consultando o hash (nunca reenviando). Nesta fase o diário é só em
 * memória; o esquema durável (dono, local, campos) está definido em docs/TX_ARCHITECTURE.md. Nunca contém chave, semente ou bytes assinados.
 */
public interface TxJournal {
    record Entry(String operationId, String sessionId, TxState state, String quoteId, String intentDigest, String txHash, String error, long atMs) { }

    void save(Entry entry);

    static InMemory memory() {
        return new InMemory();
    }

    final class InMemory implements TxJournal {
        private final List<Entry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void save(Entry entry) {
            entries.add(entry);
        }

        public List<Entry> entries() {
            return List.copyOf(entries);
        }
    }
}
