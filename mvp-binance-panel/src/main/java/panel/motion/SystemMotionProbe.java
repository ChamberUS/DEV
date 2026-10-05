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
        return () -> {
            try {
                Process p = new ProcessBuilder("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceMotion").redirectErrorStream(true).start();
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim().equals("1");
            } catch (java.io.IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }
}
