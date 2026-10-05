package panel.motion;

import java.util.concurrent.TimeUnit;

/** Lê "Reduzir movimento" do macOS. Nunca bloqueia a UI (o chamador usa thread própria) e qualquer falha vale "não reduzido". */
public interface SystemMotionProbe {
    boolean reduced();

    SystemMotionProbe NONE = () -> false;

    static SystemMotionProbe macOs() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            return NONE;
        }
        // macOS recente guarda a chave em com.apple.Accessibility; versões anteriores, em com.apple.universalaccess. Qualquer uma ligada = reduzido.
        return () -> read("com.apple.Accessibility", "ReduceMotionEnabled") || read("com.apple.universalaccess", "reduceMotion");
    }

    /** Valor de "1"/"true" (saída do defaults); chave ausente ou qualquer outra coisa = falso. */
    static boolean isOn(String output) {
        String t = output == null ? "" : output.trim();
        return t.equals("1") || t.equalsIgnoreCase("true");
    }

    private static boolean read(String domain, String key) {
        try {
            Process p = new ProcessBuilder("/usr/bin/defaults", "read", domain, key).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return isOn(new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
