package byx.service.secrets;

import java.io.IOException;

/**
 * Harness de TESTE que ESCREVE o canário com a identidade do serviço e o MANTÉM vivo (até o stdin fechar ou 90 s) para que outros
 * processos tentem lê-lo; ao fim apaga e confirma. Imprime só estados; nunca o valor. Não tem argumento nem IPC.
 */
public final class SecretCanaryHolder {
    private SecretCanaryHolder() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        SecretStore store = SecretStores.test();
        System.out.println("holder.status=" + store.status());
        boolean wrote = false;
        try (SecretBytes v = SecretBytes.random(32)) {
            store.write(SecretId.TEST_CANARY, v);
            wrote = true;
            try (SecretBytes got = store.read(SecretId.TEST_CANARY).orElseThrow(() -> new SecretStoreException(SecretStatus.ERROR))) {
                if (!got.contentEquals(v)) {
                    throw new SecretStoreException(SecretStatus.ERROR);
                }
            }
            System.out.println("holder.ready=OK");
            System.out.flush();
            long end = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < end && System.in.available() >= 0) {
                if (System.in.available() > 0 && System.in.read() < 0) {
                    break;
                }
                Thread.sleep(100);
            }
        } catch (SecretStoreException e) {
            System.out.println("holder.ready=" + e.status() + (e.osStatus() == 0 ? "" : " (os=" + e.osStatus() + ")"));
        } finally {
            try {
                boolean deleted = store.delete(SecretId.TEST_CANARY);
                System.out.println("holder.cleanup=" + (deleted ? "DELETED" : "NOTHING_TO_DELETE"));
            } catch (SecretStoreException e) {
                System.out.println("holder.cleanup=" + e.status() + (wrote ? " LEFTOVER_POSSIBLE(invalid.byx-canary-test/test-canary)" : ""));
            }
        }
    }
}
