package byx.service.auth;

import java.util.Optional;

/**
 * Cofre da chave AEAD do snapshot da autoridade (256 bits, SecureRandom), SEPARADO da chave MAC da {@link Anchor}. Em produção/QA é o
 * keychain de proteção de dados só do serviço ({@link SecretStoreKeyVault}); nunca env, propriedade, configuração, argv, arquivo ou log.
 * O painel nunca recebe esta chave.
 */
public interface EncryptionKeyVault {
    final class VaultException extends Exception {
        public VaultException(String code) {
            super(code, null, false, false);
        }
    }

    /** Cópia da chave (quem chama zera) ou vazio se nunca criada. */
    Optional<byte[]> read() throws VaultException;

    /** Grava (substitui) a chave. */
    void write(byte[] key) throws VaultException;
}
