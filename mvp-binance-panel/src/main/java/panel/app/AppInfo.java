package panel.app;

/** Versão e ambiente de execução (só leitura, sem segredo): usados por About, Settings e Diagnostics. */
public final class AppInfo {
    public static final String VERSION = "0.1.0";
    public static final String ENVIRONMENT = "LOCALNET";

    private AppInfo() {
    }

    /** Build do manifesto quando empacotado; "development" ao rodar das classes. */
    public static String build() {
        String v = AppInfo.class.getPackage() == null ? null : AppInfo.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "development" : v;
    }

    public static String java() {
        return System.getProperty("java.version", "unknown");
    }

    public static String javafx() {
        return System.getProperty("javafx.version", "unknown");
    }

    public static String os() {
        return System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "");
    }

    public static String versionLine() {
        return AppBranding.NAME + " " + VERSION + " · build " + build();
    }
}
