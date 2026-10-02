package panel.app;

import javafx.application.Application;

/** Ponto de entrada sem herdar de Application (necessário para jpackage / classpath). */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(PanelApp.class, args);
    }
}
