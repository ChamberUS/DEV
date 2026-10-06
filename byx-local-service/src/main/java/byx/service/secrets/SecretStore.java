package byx.service.secrets;

import java.util.Optional;

/**
 * Armazenamento seguro INTERNO do serviço (o painel nunca recebe segredo e não existe operação de segredo no IPC). Sem nome arbitrário:
 * só {@link SecretId}. O chamador é dono dos {@link SecretBytes} que recebe e deve fechá-los.
 */
public interface SecretStore {
    /** Disponibilidade sem tocar em nenhum segredo. */
    SecretStatus status();

    /** Cria o item ou, se já existe, atualiza o valor. */
    void write(SecretId id, SecretBytes value) throws SecretStoreException;

    /** Atualiza um item EXISTENTE; false se não existe. */
    boolean update(SecretId id, SecretBytes value) throws SecretStoreException;

    Optional<SecretBytes> read(SecretId id) throws SecretStoreException;

    /** false se não existia. */
    boolean delete(SecretId id) throws SecretStoreException;
}
