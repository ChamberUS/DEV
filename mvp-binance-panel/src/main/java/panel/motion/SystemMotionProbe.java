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
        return () -> read(true) || read(false);
    }

    /** Valor de "1"/"true" (saída do defaults); chave ausente ou qualquer outra coisa = falso. */
    static boolean isOn(String output) {
        String t = output == null ? "" : output.trim();
        return t.equals("1") || t.equalsIgnoreCase("true");
    }

    /** Argumentos SEMPRE literais (nenhum dado externo chega ao comando): chave moderna ou legada do macOS. */
    private static boolean read(boolean modern) {
        try {
            Process p = (modern ? new ProcessBuilder("/usr/bin/defaults", "read", "com.apple.Accessibility", "ReduceMotionEnabled")
                    : new ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceMotion")).redirectErrorStream(true).start();
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
