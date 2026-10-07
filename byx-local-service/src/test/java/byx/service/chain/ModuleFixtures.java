package byx.service.chain;

/** Fixtures sintéticas na FORMA do gateway REST real (sem copiar payloads reais). */
public final class ModuleFixtures {
    private ModuleFixtures() { }

    public static String payment(String id, String loja, String amount, String status) {
        return ChainReaderTest.payment(id, loja, amount, status);
    }
}
