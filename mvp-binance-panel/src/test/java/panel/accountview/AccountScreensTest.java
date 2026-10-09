package panel.accountview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Test;
import panel.auth.TrustedDeviceService;
import panel.design.ByxTheme;
import panel.design.RegionState;
import panel.motion.MotionService;
import panel.security.Role;
import panel.security.SecurityAuditService;
import panel.tradeview.DeskHarness;
import panel.user.User;
import panel.user.UserStatus;

/** Passo 10: dados reais ou UNAVAILABLE, nunca fake-save; edição suja, descarte e preferências reais. */
class AccountScreensTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final class Stub implements AccountData {
        volatile User user = new User(1, "alex", "alex@example.com", "hash", Role.ADMIN, UserStatus.ACTIVE, "+5511999991234", true, false,
                false, NOW.minusSeconds(86400 * 30), NOW, NOW.minusSeconds(3600));
        volatile boolean contactFails;
        volatile boolean saveFails;
        volatile boolean devicesAvailable = true;
        final List<String> contactCalls = new ArrayList<>();
        final List<Prefs> saves = new ArrayList<>();
        volatile Prefs prefs = new Prefs("FULL", "COMPACT", true, "TRADING", true);
        volatile List<SecurityAuditService.Entry> audit = List.of();
        volatile boolean auditFails;

        @Override public Optional<User> user() { return Optional.ofNullable(user); }
        @Override public Instant signedInAt() { return NOW.minusSeconds(600); }
        @Override public boolean adminSession() { return false; }
        @Override public void changePassword(char[] current, char[] next) { }
        @Override public void changeContact(char[] password, String email, String phone) {
            contactCalls.add(email);
            if (contactFails) {
                throw new IllegalArgumentException("Current password is incorrect.");
            }
        }
        @Override public List<TrustedDeviceService.Device> trustedDevices() {
            if (!devicesAvailable) {
                throw new IllegalStateException("admin only");
            }
            return List.of();
        }
        @Override public void revokeDevice(String id) { }
        @Override public List<ProviderLine> providers() { return List.of(new ProviderLine("Email", "NOT_CONFIGURED", "x")); }
        @Override public List<SecurityAuditService.Entry> activity(int limit) {
            if (auditFails) {
                throw new IllegalStateException("db");
            }
            return audit;
        }
        @Override public Prefs prefs() { return prefs; }
        @Override public String effectiveMotion() { return prefs.motion(); }
        @Override public boolean preferencesEditable() { return true; }
        @Override public void savePrefs(Prefs p) {
            if (saveFails) {
                throw new IllegalStateException("disk");
            }
            saves.add(p);
            prefs = p;
        }
    }

    private static void show(Node n) {
        Scene s = new Scene((Parent) n, 1440, 900);
        ByxTheme.apply(s);
        n.applyCss();
        ((Parent) n).layout();
    }

    private static String texts(Node n) {
        StringBuilder b = new StringBuilder();
        collect(n, b);
        return b.toString();
    }

    private static void collect(Node n, StringBuilder b) {
        if (n instanceof Labeled l && l.getText() != null) {
            b.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), b);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, b));
        }
    }

    @Test
    void auditResultIsOnlyClaimedWhenTheEventSaysSo() {
        assertEquals("Failed", AccountModel.result("LOGIN_FAILED"));
        assertEquals("Failed", AccountModel.result("ADMIN_ACCESS_DENIED"));
        assertEquals("Success", AccountModel.result("LOGIN_SUCCESS"));
        assertEquals("Recorded", AccountModel.result("EMAIL_OTP_SENT"));
        assertEquals("Sign in", AccountModel.eventLabel("SIGN_IN").replace("Sign in", "Sign in"));
        var groups = AccountModel.group(List.of(new SecurityAuditService.Entry(NOW.toString(), "LOGIN_SUCCESS", "alex", ""),
                new SecurityAuditService.Entry(NOW.minusSeconds(86400).toString(), "LOGOUT", "alex", ""),
                new SecurityAuditService.Entry(NOW.minusSeconds(86400 * 5).toString(), "LOGIN_FAILED", "alex", "")), CLOCK, ZoneOffset.UTC);
        assertEquals(List.of("Today", "Yesterday", "2026-09-30"), groups.stream().map(AccountModel.Group::day).toList());
    }

    @Test
    void profileShowsRealDataAndNeverFakesASave() throws Exception {
        DeskHarness.fx(() -> {
            Stub data = new Stub();
            List<String> nav = new ArrayList<>();
            ProfileScreen p = new ProfileScreen(new MotionService(), data, nav::add, () -> { });
            show(p.node());
            String t = texts(p.node());
            assertTrue(t.contains("@alex") && t.contains("Administrator") && t.contains("EMAIL VERIFIED"));
            assertTrue(t.contains("Not provided by the API"), "no display name exists in the account API");
            assertFalse(t.contains("alex@example.com"), "contacts stay masked");
            assertFalse(p.hasUnsavedChanges());
            p.startEdit();
            assertTrue(p.editing());
            assertFalse(p.hasUnsavedChanges(), "opening the editor is not a change");
            p.emailField().input().setText("new@example.com");
            assertTrue(p.hasUnsavedChanges());
            p.discardChanges();
            assertFalse(p.editing());
            assertFalse(p.hasUnsavedChanges());
            assertTrue(data.contactCalls.isEmpty(), "discarding saves nothing");
        });
    }

    @Test
    void profileSaveFailureKeepsTheEditedTextAndShowsABanner() throws Exception {
        Stub data = new Stub();
        data.contactFails = true;
        ProfileScreen[] holder = new ProfileScreen[1];
        DeskHarness.fx(() -> {
            holder[0] = new ProfileScreen(new MotionService(), data, id -> { }, () -> { });
            show(holder[0].node());
            holder[0].startEdit();
            holder[0].emailField().input().setText("typed@example.com");
            holder[0].passwordField().input().setText("wrong");
            holder[0].node().lookupAll(".byx-btn").stream().filter(n -> n instanceof javafx.scene.control.Button b && "Save changes".equals(b.getText()))
                    .map(n -> (javafx.scene.control.Button) n).findFirst().orElseThrow().fire();
        });
        Thread.sleep(500);
        DeskHarness.fx(() -> {
            assertEquals(List.of("typed@example.com"), data.contactCalls);
            assertNotNull(holder[0].banner(), "backend error is shown inline");
            assertEquals("typed@example.com", holder[0].emailField().input().getText(), "edited text is kept");
            assertTrue(holder[0].editing());
            assertEquals("", holder[0].passwordField().input().getText(), "the password never stays in the form");
        });
    }

    @Test
    void notificationsAreUnavailableNotEmpty() throws Exception {
        DeskHarness.fx(() -> {
            NotificationsScreen n = new NotificationsScreen(new MotionService());
            show(n.node());
            assertEquals(RegionState.UNAVAILABLE, n.state());
            assertTrue(texts(n.node()).contains("Notifications unavailable"));
            assertFalse(texts(n.node()).toLowerCase().contains("demo"));
            n.dispose();
        });
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            boolean[] ready = new boolean[1];
            DeskHarness.fx(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assertions.fail("account read did not finish");
    }

    @Test
    void sessionsNeverListFictionalDevices() throws Exception {
        Stub data = new Stub();
        SessionsScreen[] holder = new SessionsScreen[1];
        DeskHarness.fx(() -> {
            SessionsScreen screen = holder[0] = new SessionsScreen(new MotionService(), CLOCK, data, () -> null);
            show(screen.node());
            assertTrue(texts(screen.node()).contains("CURRENT") && texts(screen.node()).contains("Other sessions unavailable"));
            assertFalse(texts(screen.node()).contains("Sign out other"));
            screen.tabs().select("Trusted devices");
            screen.onShow();
            assertTrue(texts(screen.node()).contains("Loading trusted devices"));
        });
        await(() -> texts(holder[0].node()).contains("No trusted devices"));
        data.devicesAvailable = false;
        DeskHarness.fx(() -> holder[0].onShow());
        await(() -> texts(holder[0].node()).contains("Trusted devices unavailable"));
        DeskHarness.fx(() -> holder[0].dispose());
    }

    @Test
    void activityShowsOnlyTheRealAuditLog() throws Exception {
        Stub data = new Stub();
        ActivityScreen[] holder = new ActivityScreen[1];
        DeskHarness.fx(() -> {
            holder[0] = new ActivityScreen(CLOCK, data);
            show(holder[0].node());
            holder[0].onShow();
            assertEquals("LOADING", holder[0].stateText());
        });
        await(() -> "EMPTY".equals(holder[0].stateText()));
        data.audit = List.of(new SecurityAuditService.Entry(NOW.toString(), "LOGIN_FAILED", "alex", "x"));
        DeskHarness.fx(() -> holder[0].onShow());
        await(() -> texts(holder[0].node()).contains("Login failed") && texts(holder[0].node()).contains("FAILED")
                && texts(holder[0].node()).contains("Today"));
        data.auditFails = true;
        DeskHarness.fx(() -> holder[0].onShow());
        await(() -> "UNAVAILABLE".equals(holder[0].stateText()));
        DeskHarness.fx(() -> holder[0].dispose());
    }

    @Test
    void settingsKeepADraftAndOnlyPersistRealPreferences() throws Exception {
        DeskHarness.fx(() -> {
            Stub data = new Stub();
            SettingsScreen s = new SettingsScreen(new MotionService(), data, id -> { }, () -> null);
            show(s.node());
            assertFalse(s.hasUnsavedChanges());
            s.select("Appearance");
            var seg = s.node().lookupAll(".byx-desk-seg-btn").stream().filter(n -> n instanceof javafx.scene.control.ToggleButton b && "REDUCED".equals(b.getText()))
                    .findFirst().orElseThrow();
            ((javafx.scene.control.ToggleButton) seg).setSelected(true);
            assertTrue(s.hasUnsavedChanges());
            assertEquals("REDUCED", s.draft().motion());
            assertEquals("FULL", s.saved().motion());
            assertTrue(data.saves.isEmpty(), "nothing is written before Save");
            s.discardChanges();
            assertFalse(s.hasUnsavedChanges());
            assertEquals("FULL", s.draft().motion());
            ((javafx.scene.control.ToggleButton) s.node().lookupAll(".byx-desk-seg-btn").stream()
                    .filter(n -> n instanceof javafx.scene.control.ToggleButton b && "OFF".equals(b.getText())).findFirst().orElseThrow()).setSelected(true);
            data.saveFails = true;
            s.save();
            assertTrue(s.hasUnsavedChanges(), "a failed save keeps the draft");
            data.saveFails = false;
            s.save();
            assertEquals(List.of(new AccountData.Prefs("OFF", "COMPACT", true, "TRADING", true)), data.saves);
            assertFalse(s.hasUnsavedChanges());
        });
    }

    @Test
    void settingsHaveNoLiveTradingSwitchAndEveryCategoryExists() throws Exception {
        DeskHarness.fx(() -> {
            SettingsScreen s = new SettingsScreen(new MotionService(), new Stub(), id -> { }, () -> null);
            show(s.node());
            assertEquals(List.of("General", "Appearance", "Trading", "Research", "BYX", "Notifications", "Security", "Accessibility", "About"),
                    SettingsScreen.SECTIONS);
            for (String sec : SettingsScreen.SECTIONS) {
                s.select(sec);
                assertEquals(sec, s.section());
                String t = texts(s.node().lookup("#settings-" + sec.toLowerCase()));
                assertFalse(t.toLowerCase().contains("enable live"));
                assertTrue(s.node().lookupAll(".toggle-button").stream().noneMatch(n -> n instanceof Labeled l && l.getText() != null
                        && l.getText().toLowerCase().contains("live")), "no live trading control in " + sec);
            }
            s.select("Trading");
            String t = texts(s.node());
            assertTrue(t.contains("OFF · LOCKED"));
            s.select("Research");
            assertTrue(texts(s.node()).contains("LOCKED · SEALED"));
            s.select("BYX");
            assertTrue(texts(s.node()).contains("DEVNET UNAVAILABLE"));
        });
    }

    @Test
    void securityNeverInventsASecondFactor() throws Exception {
        DeskHarness.fx(() -> {
            SecurityScreen s = new SecurityScreen(new MotionService(), CLOCK, new Stub(), id -> { }, () -> null);
            show(s.node());
            String t = texts(s.node());
            assertTrue(t.contains("Authenticator app") && t.contains("Not configured"));
            assertFalse(t.contains("Set up") || t.contains("Turn off"));
            assertTrue(t.contains("Change password"));
            s.dispose();
        });
    }
}
