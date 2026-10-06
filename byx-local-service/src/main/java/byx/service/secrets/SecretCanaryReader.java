package byx.service.secrets;

/**
 * Leitor de TESTE (sem argumento, sem nome de item): tenta ler o canário do serviço e imprime só o estado (nunca o valor) e se o item
 * apareceu. Serve às tentativas negativas (outro executável, outro Java, app irmão): sem o grupo de acesso do serviço o resultado nunca é
 * FOUND. Só roda se um lançador de TESTE apontar para esta classe.
 */
public final class SecretCanaryReader {
    private SecretCanaryReader() {
    }

    public static void main(String[] args) {
        SecretStore store = SecretStores.test();
        System.out.println("reader.status=" + store.status());
        try (var found = store.read(SecretId.TEST_CANARY).orElse(null)) {
            System.out.println("reader.result=" + (found != null ? "FOUND(length=" + found.length() + ")" : "NOT_FOUND"));
            System.exit(found != null ? 0 : 1);
        } catch (SecretStoreException e) {
            System.out.println("reader.result=" + e.status());
            System.exit(1);
        }
    }
}
