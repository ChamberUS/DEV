package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.authview.AuthScreens;
import panel.design.ByxTheme;
import panel.motion.MotionService;
import panel.user.User;

/** O login diz a verdade sobre o serviço local: "Starting local service…" sem enviar, "Retry" quando indisponível, "Sign in" quando pronto. */
class LoginServiceReadinessTest {
    private static final class Fixture {
        final List<String> attempts = new ArrayList<>();
        final int[] retries = {0};
        final AuthScreens screens;
        final Stage stage = new Stage();

        Fixture() {
            screens = new AuthScreens(new MotionService(), new AuthScreens.Services() {
                @Override public panel.auth.AuthenticationRequest beginLogin() { return panel.TestAuthenticationRequests.create((id, pw) -> {
                    attempts.add(id);
                    throw new AuthService.LoginException(AuthService.Failure.INVALID_CREDENTIALS, null);
                }); }
                @Override public void createInitialAdmin(String u, String e, char[] p, String ph) { }
                @Override public panel.auth.SessionOperation preparePasswordChange(long id, char[] c, char[] n) { return panel.TestAuthenticationRequests.operation(() -> { }); }
                @Override public panel.auth.SessionOperation prepareLogout() { return panel.TestAuthenticationRequests.operation(() -> { }); }
            }, r -> { }, u -> { }, () -> { }, m -> { }, r -> { }, null);
            Scene s = new Scene((javafx.scene.Parent) screens.node(), 1440, 900);
            ByxTheme.apply(s);
            stage.setScene(s);
            stage.show();
            screens.show(AuthScreens.LOGIN, null, null);
            screens.node().applyCss();
        }

        Button signIn() {
            return screens.node().lookupAll(".button").stream().map(n -> (Button) n)
                    .filter(b -> b.getText().startsWith("Sign in") || b.getText().startsWith("Starting") || b.getText().equals("Retry"))
                    .findFirst().orElseThrow();
        }

        String allText() {
            StringBuilder sb = new StringBuilder();
            screens.node().lookupAll(".label").forEach(n -> sb.append(((javafx.scene.control.Label) n).getText()).append('|'));
            return sb.toString();
        }
    }

    @Test
    void startingDisablesSubmitAndSaysSo() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.screens.setServiceReadiness(AuthScreens.ServiceReadiness.STARTING, () -> f.retries[0]++);
            f.screens.node().applyCss();
            f.screens.node().lookupAll(".byx-field").stream().map(n -> (panel.design.ByxField) n)
                    .filter(x -> x.labelText().equalsIgnoreCase("Email or username")).findFirst().orElseThrow().input().setText("qa");
            f.signIn().fire(); // desabilitado: não faz nada
            Object[] out = {f.signIn().getText(), f.signIn().isDisabled(), f.attempts.size(), f.allText().contains("Starting the local service")};
            f.stage.close();
            return out;
        });
        assertEquals("Starting local service…", r[0]);
        assertTrue((Boolean) r[1], "sem serviço pronto o botão não aceita envio");
        assertEquals(0, r[2], "nenhuma tentativa de login antes do serviço");
        assertTrue((Boolean) r[3], "mensagem curta e explícita");
    }

    @Test
    void readyEnablesSignInAndUnavailableOffersRetryWithoutSendingCredentials() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.screens.setServiceReadiness(AuthScreens.ServiceReadiness.STARTING, () -> f.retries[0]++);
            f.screens.setServiceReadiness(AuthScreens.ServiceReadiness.READY, () -> f.retries[0]++);
            String ready = f.signIn().getText();
            boolean readyDisabled = f.signIn().isDisabled();
            f.screens.setServiceReadiness(AuthScreens.ServiceReadiness.UNAVAILABLE, () -> f.retries[0]++);
            f.screens.node().applyCss();
            String down = f.signIn().getText();
            boolean banner = f.allText().contains("Local service unavailable");
            f.signIn().fire(); // Retry: sobe o serviço de novo, NÃO envia credenciais
            Object[] out = {ready, readyDisabled, down, banner, f.retries[0], f.attempts.size()};
            f.stage.close();
            return out;
        });
        assertEquals("Sign in", r[0]);
        assertFalse((Boolean) r[1]);
        assertEquals("Retry", r[2]);
        assertTrue((Boolean) r[3], "aviso seguro e honesto");
        assertEquals(1, r[4], "Retry chama a nova tentativa de iniciar o serviço");
        assertEquals(0, r[5], "Retry nunca envia credenciais");
    }
}
