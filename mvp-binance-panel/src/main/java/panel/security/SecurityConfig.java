package panel.security;

import java.nio.file.*;
import java.util.Properties;

/**
 * Só o tempo limite da sessão administrativa é lido do arquivo. Propriedades antigas (confiança de rede, a flag legada de modo de desenvolvimento) são
 * IGNORADAS: nenhuma configuração liga código de desenvolvimento (OTP de desenvolvimento e signer de teste foram removidos do artefato).
 */
public class SecurityConfig {
    public static final Path FILE=Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "security.properties");
    private final int sessionTimeoutMinutes;
    public SecurityConfig(int timeout) { this.sessionTimeoutMinutes=Math.max(1,timeout); }
    public static SecurityConfig load() { return load(FILE); }
    public static SecurityConfig load(Path file) {
        Properties p=new Properties();
        try(var in=Files.newInputStream(file)) { p.load(in); }
        catch(java.io.IOException e) { return new SecurityConfig(30); }
        int timeout=30;
        try { timeout=Integer.parseInt(p.getProperty("security.admin.sessionTimeoutMinutes","30")); }
        catch(NumberFormatException ignored) { }
        return new SecurityConfig(timeout);
    }
    public int sessionTimeoutMinutes(){return sessionTimeoutMinutes;}
}
