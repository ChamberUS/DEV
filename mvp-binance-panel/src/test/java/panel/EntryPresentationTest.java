package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.authview.AuthScreens;
import panel.authview.RegistrationAvailability;
import panel.design.ByxOverlayHost;
import panel.design.ByxTheme;
import panel.homeview.LandingPreference;
import panel.homeview.WelcomeDialog;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** B02: registration is only ever presented as text, never as a form or a role choice; the welcome is one honest step. */
class EntryPresentationTest {
    private static AuthScreens screens(RegistrationAvailability a, Stage stage) {
        AuthScreens s = new AuthScreens(new MotionService(), new AuthScreens.Services() {
            @Override public panel.auth.AuthenticationRequest beginLogin() { return panel.TestAuthenticationRequests.create((id, pw) -> { throw new IllegalStateException(); }); }
            @Override public void createInitialAdmin(String u, String e, char[] p, String ph) { throw new AssertionError("no account may be created from the login"); }
            @Override public panel.auth.SessionOperation preparePasswordChange(long id, char[] c, char[] n) { return panel.TestAuthenticationRequests.operation(() -> { }); }
            @Override public panel.auth.SessionOperation prepareLogout() { return panel.TestAuthenticationRequests.operation(() -> { }); }
            @Override public RegistrationAvailability registrationAvailability() { return a; }
        }, r -> { }, u -> { }, () -> { }, m -> { }, r -> { }, null);
        Scene sc = new Scene((Parent) s.node(), 1440, 900);
        ByxTheme.apply(sc);
        stage.setScene(sc);
        stage.show();
        s.show(AuthScreens.LOGIN, null, null);
        s.node().applyCss();
        return s;
    }

    private static List<String> texts(Node root) {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Node n, List<String> out) {
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, out));
        }
    }

    @Test
    void registrationNeverOffersAFormWhateverTheCapabilityIs() throws Exception {
        for (RegistrationAvailability a : RegistrationAvailability.values()) {
            Object[] r = FxSupport.fx(() -> {
                Stage st = new Stage();
                AuthScreens s = screens(a, st);
                List<String> t = texts(s.node());
                long fields = s.node().lookupAll(".byx-field").size();
                Object[] out = {t, fields, a.offersSignUp()};
                st.close();
                return out;
            });
            @SuppressWarnings("unchecked")
            List<String> t = (List<String>) r[0];
            assertFalse(t.stream().anyMatch(x -> x.toLowerCase().contains("create account")), a + ": no create-account control");
            assertEquals(2L, r[1], a + ": only email/username + password; no role, no invite code, no registration fields");
            assertFalse((boolean) r[2]);
        }
    }

    @Test
    void unavailableShowsTheHonestLineAndAnExpandableGuidance() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Stage st = new Stage();
            AuthScreens s = screens(RegistrationAvailability.UNAVAILABLE, st);
            List<String> before = texts(s.node());
            Button how = (Button) s.node().lookup("#registration-how");
            how.fire();
            s.node().applyCss();
            List<String> after = texts(s.node());
            st.close();
            return new Object[] {before, after};
        });
        @SuppressWarnings("unchecked")
        List<String> before = (List<String>) r[0];
        @SuppressWarnings("unchecked")
        List<String> after = (List<String>) r[1];
        assertTrue(before.contains("Public registration isn’t available in this beta. Accounts are created by an administrator."));
        assertTrue(before.contains("How do I get access?"));
        assertFalse(before.stream().anyMatch(x -> x.startsWith("Ask the administrator")));
        assertTrue(after.stream().anyMatch(x -> x.startsWith("Ask the administrator of your BYX-MVP installation")));
    }

    @Test
    void unknownCapabilityDoesNotBlockSigningIn() throws Exception {
        List<String> t = FxSupport.fx(() -> {
            Stage st = new Stage();
            AuthScreens s = screens(RegistrationAvailability.UNKNOWN, st);
            List<String> out = texts(s.node());
            boolean signInEnabled = !s.node().lookupAll(".button").stream().anyMatch(n -> n instanceof Button b && "Sign in".equals(b.getText()) && b.isDisabled());
            assertTrue(signInEnabled);
            st.close();
            return out;
        });
        assertTrue(t.contains("Checking whether registration is available… Signing in is not affected."));
    }

    // ---------------------------------------------------------------- welcome

    @Test
    void welcomeIsOneStepStatesRealityAndOffersOnlyHomeOrTerminal() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            MotionService motion = new MotionService();
            motion.preference.set(MotionPreference.OFF);
            ByxOverlayHost host = new ByxOverlayHost(new StackPane(), motion);
            Stage st = new Stage();
            st.setScene(new Scene(host, 900, 700));
            ByxTheme.apply(st.getScene());
            st.show();
            AtomicReference<WelcomeDialog.Result> result = new AtomicReference<>();
            var facts = new WelcomeDialog.Facts("demo.user", "Trader", true, "LOCALNET");
            var handle = WelcomeDialog.open(host, motion, facts, LandingPreference.Start.HOME, result::set, () -> { });
            host.applyCss();
            WelcomeDialog dlg = (WelcomeDialog) host.lookup("#welcome");
            List<String> t = texts(dlg);
            boolean anyInput = dlg.lookupAll("*").stream().anyMatch(n -> n instanceof TextInputControl);
            boolean ctaFocused = st.getScene().getFocusOwner() == dlg.goButton();
            dlg.choice().buttons().get(1).fire();
            String cta = dlg.goButton().getText();
            dlg.goButton().fire();
            Object[] out = {t, anyInput, ctaFocused, cta, result.get(), handle.isOpen(), host.openDialogs()};
            st.close();
            return out;
        });
        @SuppressWarnings("unchecked")
        List<String> t = (List<String>) r[0];
        assertTrue(t.contains("Welcome, demo.user"));
        assertTrue(t.stream().anyMatch(x -> x.contains("Trader") && x.contains("assigned by the service")), "the role is shown, never chosen");
        assertTrue(t.contains("Live trading OFF"));
        assertTrue(t.stream().anyMatch(x -> x.contains("LOCALNET · TEST")));
        assertTrue(t.stream().anyMatch(x -> x.startsWith("Saving isn’t available in this build")), "tells the truth about persistence");
        assertFalse((boolean) r[1], "no credential or free-text field");
        assertTrue((boolean) r[2]);
        assertEquals("Go to Terminal", r[3]);
        assertEquals(LandingPreference.Start.TERMINAL, ((WelcomeDialog.Result) r[4]).start());
        assertFalse((boolean) r[5]);
        assertEquals(0, r[6]);
    }

    @Test
    void escapeDismissesWithoutAnyChoice() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            MotionService motion = new MotionService();
            motion.preference.set(MotionPreference.OFF);
            ByxOverlayHost host = new ByxOverlayHost(new StackPane(), motion);
            Stage st = new Stage();
            st.setScene(new Scene(host, 900, 700));
            ByxTheme.apply(st.getScene());
            st.show();
            AtomicReference<WelcomeDialog.Result> result = new AtomicReference<>();
            int[] dismissed = {0};
            WelcomeDialog.open(host, motion, new WelcomeDialog.Facts("a", "Admin", true, null), LandingPreference.Start.HOME, result::set, () -> dismissed[0]++);
            Event.fireEvent(host.lookup("#welcome"), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            Object[] out = {result.get(), dismissed[0], host.openDialogs()};
            st.close();
            return out;
        });
        assertNull(r[0]);
        assertEquals(1, r[1]);
        assertEquals(0, r[2]);
    }

    @Test
    void entryChoicesCanNeverLeadToResearchOrChangeARole() {
        LandingPreference p = new LandingPreference();
        for (LandingPreference.Start s : LandingPreference.Start.values()) {
            p.choose("uid:1", s);
            String route = p.route("uid:1", id -> true);
            assertFalse(panel.shell.ShellRoutes.isResearch(route), s + " -> " + route);
        }
        assertNotNull(p.startFor("uid:1"));
        // the welcome result carries one field only: the entry page
        assertEquals(1, WelcomeDialog.Result.class.getRecordComponents().length);
    }
}
