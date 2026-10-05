package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.auth.AuthService;
import panel.authview.AuthScreens;
import panel.authview.LoginController;
import panel.design.ByxField;
import panel.design.ByxTheme;
import panel.motion.MotionService;
import panel.user.User;

/** Teclado no login V2: ordem de Tab = ordem visual, Enter envia, Esc limpa o erro, Show/Hide não perde o valor. */
class AuthKeyboardTest {
    private static final class Fixture {
        final List<String> routes = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();
        final AuthScreens screens;
        final Stage stage = new Stage();

        Fixture() {
            screens = new AuthScreens(new MotionService(), new AuthScreens.Services() {
                @Override public User login(String id, char[] pw) {
                    attempts.add(id);
                    throw new AuthService.LoginException(AuthService.Failure.INVALID_CREDENTIALS, null);
                }
                @Override public void createInitialAdmin(String u, String e, char[] p, String ph) { }
                @Override public void changeOwnPassword(long id, char[] c, char[] n) { }
                @Override public void endSession() { }
            }, routes::add, u -> { }, () -> { }, m -> { }, () -> { }, null);
            Scene s = new Scene((javafx.scene.Parent) screens.node(), 1440, 900);
            ByxTheme.apply(s);
            stage.setScene(s);
            stage.show();
            screens.show(AuthScreens.LOGIN, null, null);
            screens.node().applyCss();
        }

        ByxField field(String label) {
            return screens.node().lookupAll(".byx-field").stream().map(n -> (ByxField) n)
                    .filter(f -> f.labelText().equalsIgnoreCase(label)).findFirst().orElseThrow();
        }

        Node focus() {
            return stage.getScene().getFocusOwner();
        }

        void press(Node target, KeyCode k, boolean shift) {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", k, shift, false, false, false));
        }
    }

    private static String describe(Node n) {
        return n instanceof Button b ? "button:" + b.getText() : n instanceof TextField t ? "field:" + t.getAccessibleText() : String.valueOf(n);
    }

    @Test
    void tabOrderFollowsTheVisualOrder() throws Exception {
        List<String> order = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.field("Email or username").input().requestFocus();
            List<String> out = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                out.add(describe(f.focus()));
                f.press(f.focus(), KeyCode.TAB, false); // Tab real, tratado pelo dispatcher da cena
            }
            f.stage.close();
            return out;
        });
        assertEquals(List.of("field:Email or username", "button:Forgot password?", "field:Password", "button:Show", "button:Sign in"), order);
    }

    @Test
    void enterSubmitsAndEscClearsTheError() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            f.field("Email or username").input().setText("qa");
            f.field("Password").input().setText("wrong-pass-1");
            f.field("Password").input().requestFocus();
            f.press(f.focus(), KeyCode.ENTER, false); // botão padrão
            LoginController.State afterEnter = f.screens.loginController().state();
            Object[] out = {afterEnter, f.field("Password").input().getText()};
            f.stage.close();
            return out;
        });
        assertEquals(LoginController.State.LOADING, r[0], "Enter submits through the default button");
        assertEquals("", r[1], "the password leaves the UI as soon as it is sent");
        // resultado do serviço chega depois (thread de login): espera e verifica Esc
        Thread.sleep(500);
        Object[] r2 = FxSupport.<Object[]>fx(() -> {
            Fixture f = new Fixture();
            f.field("Email or username").input().setText("qa");
            f.field("Password").input().setText("wrong-pass-1");
            f.screens.node().lookupAll(".byx-btn").stream().map(n -> (Button) n).filter(b -> b.getText().equals("Sign in"))
                    .findFirst().orElseThrow().fire();
            return new Object[] {f};
        });
        Thread.sleep(500);
        Object[] r3 = FxSupport.fx(() -> {
            Fixture f = (Fixture) r2[0];
            LoginController.State invalid = f.screens.loginController().state();
            f.press(f.focus() != null ? f.focus() : f.screens.node(), KeyCode.ESCAPE, false);
            LoginController.State afterEsc = f.screens.loginController().state();
            f.stage.close();
            return new Object[] {invalid, afterEsc};
        });
        assertEquals(LoginController.State.INVALID, r3[0]);
        assertEquals(LoginController.State.DEFAULT, r3[1], "Esc returns from the error to default");
    }

    @Test
    void showHideKeepsTheValueWithoutLeavingTheField() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            Fixture f = new Fixture();
            ByxField pw = f.field("Password");
            pw.input().setText("secret-pass-1");
            Button show = (Button) pw.lookup(".byx-reveal");
            show.fire();
            boolean revealed = pw.isRevealed() && "secret-pass-1".equals(pw.revealedInput().getText());
            show.fire();
            boolean masked = !pw.isRevealed() && "secret-pass-1".equals(pw.input().getText());
            f.stage.close();
            return new boolean[] {revealed, masked};
        });
        assertTrue(r[0]);
        assertTrue(r[1]);
    }
}
