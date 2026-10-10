package panel;

import java.nio.file.Path;
import java.util.List;

/** Bounded synthetic subprocess fixture, independent of a Unix shell. Never bundled. */
public final class ProcessTestChild {
    private ProcessTestChild() { }
    static List<String> command(String action) {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        return List.of(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString(),
                "-cp", System.getProperty("java.class.path"), ProcessTestChild.class.getName(), action);
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "sleep" -> Thread.sleep(10000);
            case "fail" -> System.exit(7);
            case "json" -> System.out.print("{}");
            default -> throw new IllegalArgumentException("unknown synthetic fixture action");
        }
    }
}
