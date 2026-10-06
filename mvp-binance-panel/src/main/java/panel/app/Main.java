package panel.app;

import javafx.application.Application;

/** Ponto de entrada sem herdar de Application (necessário para jpackage / classpath). */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--probe-service")) {
            System.exit(panel.localservice.ServiceProbe.runToStdout(args)); // sonda sem JavaFX, mesma identidade de código do app
        }
        if (java.util.Arrays.asList(args).contains("--gallery")) {
            Application.launch(GalleryApp.class, args); // DEV ONLY: galeria de controles V2
            return;
        }
        Application.launch(PanelApp.class, args);
    }
}
