package panel.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Settings {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".mvp-binance-panel", "settings.properties");

    public String projectPath = Path.of(System.getProperty("user.home"), "dev/mvp-binance").toString();
    public String cliPath = projectPath + "/.venv/bin/adaptive-trader";
    public String reportsPath = "reports/research";
    public int pollSeconds = 3;
    public int researchPollSeconds = 20;
    public DataSource dataSource = DataSource.REAL;
    public String motion = "FULL";
    public boolean animatedIcons = true;
    public String density = "COMPACT";
    /** Preferências de UX do onboarding (só isto é guardado): conclusão e workspace de abertura. */
    public boolean onboardingCompleted;
    public String primaryWorkspace = "TRADING";
    /** "Follow system setting": REDUCED quando o macOS pede redução de movimento (só reduz, nunca afrouxa). */
    public boolean followSystemMotion = true;

    public Path project() {
        return Path.of(projectPath);
    }

    public Path reports() {
        Path p = Path.of(reportsPath);
        return p.isAbsolute() ? p : project().resolve(p);
    }

    public static Settings load() {
        Settings s = new Settings();
        if (Files.exists(FILE)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
                s.projectPath = p.getProperty("projectPath", s.projectPath);
                s.cliPath = p.getProperty("cliPath", s.cliPath);
                s.reportsPath = p.getProperty("reportsPath", s.reportsPath);
                s.pollSeconds = Math.max(2, Integer.parseInt(p.getProperty("pollSeconds", "3")));
                s.researchPollSeconds = Math.max(15, Integer.parseInt(p.getProperty("researchPollSeconds", "20")));
                s.dataSource = DataSource.valueOf(p.getProperty("dataSource", "REAL"));
                s.motion = p.getProperty("motion", "FULL");
                s.animatedIcons = Boolean.parseBoolean(p.getProperty("animatedIcons", "true"));
                s.density = p.getProperty("density", "COMPACT");
                s.onboardingCompleted = Boolean.parseBoolean(p.getProperty("onboardingCompleted", "false"));
                s.followSystemMotion = Boolean.parseBoolean(p.getProperty("followSystemMotion", "true"));
                String pw = p.getProperty("primaryWorkspace", "TRADING");
                s.primaryWorkspace = java.util.Set.of("TRADING", "RESEARCH", "BYX").contains(pw) ? pw : "TRADING";
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("Settings ignoradas: " + e.getMessage());
            }
        }
        return s;
    }

    public void save() throws IOException {
        panel.security.ServerAuthorization.requirePersistence("settings.persist");
        Properties p = new Properties();
        p.setProperty("projectPath", projectPath);
        p.setProperty("cliPath", cliPath);
        p.setProperty("reportsPath", reportsPath);
        p.setProperty("pollSeconds", Integer.toString(pollSeconds));
        p.setProperty("researchPollSeconds", Integer.toString(researchPollSeconds));
        p.setProperty("dataSource", dataSource.name());
        p.setProperty("motion", motion);
        p.setProperty("animatedIcons", Boolean.toString(animatedIcons));
        p.setProperty("density", density);
        p.setProperty("onboardingCompleted", Boolean.toString(onboardingCompleted));
        p.setProperty("primaryWorkspace", primaryWorkspace);
        p.setProperty("followSystemMotion", Boolean.toString(followSystemMotion));
        Files.createDirectories(FILE.getParent());
        try (OutputStream out = Files.newOutputStream(FILE)) {
            p.store(out, "MVP Binance panel");
        }
    }
}
