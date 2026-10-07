package panel;

/** Exposição pública do FxSupport (package-private) para testes de outros pacotes. */
public final class FxBridge {
    private FxBridge() { }

    public static <T> T fx(java.util.function.Supplier<T> s) throws Exception {
        return FxSupport.fx(s);
    }
}
