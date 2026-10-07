package byx.service.chain;

/** Transporte GET para o nó local aprovado. A implementação de produção só fala com {@link ChainEndpoint} (loopback), sem redirecionamento, com prazo e teto de bytes. */
public interface ChainTransport {
    /** Corpo de uma resposta 200; qualquer outra coisa lança {@link ChainException}. */
    byte[] get(ChainEndpoint endpoint, String pathAndQuery, int maxBytes) throws ChainException;
}
