package panel;

/** Acesso público ao início do toolkit JavaFX para testes de outros pacotes (FxSupport é do pacote panel). */
public final class FxSupportAccess {
    private FxSupportAccess() { }

    public static void start() {
        FxSupport.start();
    }
}
