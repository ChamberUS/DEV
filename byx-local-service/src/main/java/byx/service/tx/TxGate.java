package byx.service.tx;

/**
 * Gate MESTRE de mutações de transação, independente do gate privado ({@code PrivateCapabilityGate}). Em produção é uma constante FALSA:
 * não lê ambiente, propriedade, arquivo nem pedido de IPC. Só código de teste constrói um gate ligado (e passa por injeção de construtor).
 */
public interface TxGate {
    /** Constante de compilação: o artefato de produção nunca permite mutações de transação nesta fase. */
    boolean TX_MUTATIONS_ALLOWED = false;

    boolean mutationsAllowed();

    TxGate PRODUCTION = () -> TX_MUTATIONS_ALLOWED;
}
