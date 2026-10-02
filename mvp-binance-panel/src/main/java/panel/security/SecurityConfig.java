package panel.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuração local de segurança. Fontes, em ordem: variáveis de ambiente, depois
 * ~/.mvp-binance-panel/security.properties (fora do repositório). Não guarda segredos.
 * Chaves: security.admin.trustedIpv6, security.admin.sessionTimeoutMinutes, security.dev.mode.
 */
public class SecurityConfig {
    public static final Path FILE = Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "security.properties");
    public static final String ENV_TRUSTED_IPV6 = "MVP_BINANCE_ADMIN_TRUSTED_IPV6";

    private final String trustedIpv6;
    private final int sessionTimeoutMinutes;
    private final boolean devMode;

    public SecurityConfig(String trustedIpv6, int sessionTimeoutMinutes, boolean devMode) {
        this.trustedIpv6 = trustedIpv6 == null || trustedIpv6.isBlank() ? null : trustedIpv6.trim();
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.devMode = devMode;
    }

    public static SecurityConfig load() {
        Properties p = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("security.properties ignored: " + e.getMessage());
            }
        }
        String env = System.getenv(ENV_TRUSTED_IPV6);
        String ip = env != null && !env.isBlank() ? env : p.getProperty("security.admin.trustedIpv6");
        int timeout = 30;
        try {
            timeout = Math.max(1, Integer.parseInt(p.getProperty("security.admin.sessionTimeoutMinutes", "30")));
        } catch (NumberFormatException e) {
            System.err.println("Invalid security.admin.sessionTimeoutMinutes; using 30");
        }
        return new SecurityConfig(ip, timeout, Boolean.parseBoolean(p.getProperty("security.dev.mode", "false")));
    }

    public String trustedIpv6() {
        return trustedIpv6;
    }

    public boolean trustedConfigured() {
        return trustedIpv6 != null;
    }

    public int sessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public boolean devMode() {
        return devMode;
    }
}
