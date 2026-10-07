package panel.txview;

/** Ponte para o suporte FX do pacote de testes principal (mesmo toolkit, iniciado uma vez). */
final class FxSupport {
    private FxSupport() { }

    static <T> T fx(java.util.function.Supplier<T> s) throws Exception {
        return panel.FxBridge.fx(s);
    }
}
