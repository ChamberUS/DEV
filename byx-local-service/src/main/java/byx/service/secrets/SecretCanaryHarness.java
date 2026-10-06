package byx.service.secrets;

/**
 * Harness INTERNO de canário (não é IPC, não aceita argumento, não aceita nome de item): cria, lê, atualiza e apaga {@link SecretId#TEST_CANARY}
 * com um valor aleatório e imprime só estados e comprimentos, nunca o valor. Apaga sempre ao fim (limpeza) e informa se algo ficou.
 * Não faz parte de nenhum fluxo do produto; só roda se um lançador de TESTE apontar para esta classe.
 */
public final class SecretCanaryHarness {
    private SecretCanaryHarness() {
    }

    public static void main(String[] args) {
        System.exit(run(SecretStores.system()));
    }

    static int run(SecretStore store) {
        int code = 0;
        boolean[] wrote = {false};
        System.out.println("canary.backend=SecItem(dataProtection)");
        System.out.println("canary.status=" + store.status());
        try (SecretBytes v1 = SecretBytes.random(32); SecretBytes v2 = SecretBytes.random(48)) {
            code = step("write", () -> {
                store.write(SecretId.TEST_CANARY, v1);
                wrote[0] = true;
            });
            if (code == 0) {
                code = step("read", () -> {
                    try (SecretBytes got = store.read(SecretId.TEST_CANARY).orElseThrow(() -> new SecretStoreException(SecretStatus.ERROR))) {
                        if (!got.contentEquals(v1)) {
                            throw new SecretStoreException(SecretStatus.ERROR);
                        }
                    }
                });
            }
            if (code == 0) {
                code = step("update", () -> {
                    if (!store.update(SecretId.TEST_CANARY, v2)) {
                        throw new SecretStoreException(SecretStatus.ERROR);
                    }
                });
            }
            if (code == 0) {
                code = step("readAfterUpdate", () -> {
                    try (SecretBytes got = store.read(SecretId.TEST_CANARY).orElseThrow(() -> new SecretStoreException(SecretStatus.ERROR))) {
                        if (!got.contentEquals(v2)) {
                            throw new SecretStoreException(SecretStatus.ERROR);
                        }
                    }
                });
            }
        }
        // limpeza SEMPRE (mesmo após falha): e relata se o item de teste ficou
        int cleanup = step("cleanup.delete", () -> store.delete(SecretId.TEST_CANARY));
        int absent = step("cleanup.absentCheck", () -> {
            if (store.read(SecretId.TEST_CANARY).isPresent()) {
                throw new SecretStoreException(SecretStatus.ERROR);
            }
        });
        if (wrote[0] && (cleanup != 0 || absent != 0)) { // só pode ter sobrado algo se a escrita chegou a acontecer
            System.out.println("canary.leftover=possible (item invalid.byx-canary-test/test-canary; value never printed)");
        }
        System.out.println("canary.result=" + (code == 0 && absent == 0 ? "OK" : "BLOCKED"));
        return code != 0 ? 2 : absent != 0 ? 3 : 0;
    }

    private interface Action {
        void run() throws SecretStoreException;
    }

    private static int step(String name, Action a) {
        try {
            a.run();
            System.out.println("canary." + name + "=OK");
            return 0;
        } catch (SecretStoreException e) {
            System.out.println("canary." + name + "=" + e.status() + (e.osStatus() == 0 ? "" : " (os=" + e.osStatus() + ")"));
            return 1;
        } catch (RuntimeException e) {
            System.out.println("canary." + name + "=ERROR");
            return 1;
        }
    }
}
