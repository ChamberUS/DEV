package panel.security;

import java.nio.file.*;
import java.util.Properties;

/** Old network-trust properties are ignored; only timeout and explicit dev mode are loaded. */
public class SecurityConfig {
    public static final Path FILE=Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "security.properties");
    private final int sessionTimeoutMinutes; private final boolean devMode;
    public SecurityConfig(int timeout,boolean devMode) { this.sessionTimeoutMinutes=Math.max(1,timeout);this.devMode=devMode; }
    public static SecurityConfig load() { return load(FILE); }
    public static SecurityConfig load(Path file) {
        Properties p=new Properties();
        try(var in=Files.newInputStream(file)) { p.load(in); }
        catch(java.io.IOException e) { return new SecurityConfig(30,false); }
        int timeout=30;
        try { timeout=Integer.parseInt(p.getProperty("security.admin.sessionTimeoutMinutes","30")); }
        catch(NumberFormatException ignored) { }
        return new SecurityConfig(timeout,Boolean.parseBoolean(p.getProperty("security.dev.mode","false")));
    }
    public int sessionTimeoutMinutes(){return sessionTimeoutMinutes;}
    public boolean devMode(){return devMode;}
}
