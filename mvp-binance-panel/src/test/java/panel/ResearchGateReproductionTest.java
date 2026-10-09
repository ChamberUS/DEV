package panel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import panel.app.AppContext;
import panel.app.PanelApp;
import panel.security.Role;
import panel.shell.ShellRouter;
import panel.user.User;
import panel.user.UserStatus;

/**
 * Reprodução SINTÉTICA (autoridade falsa, home temporário, nenhum perfil real, nenhum provedor) do gate do Research no PanelApp real: sessão válida de ADMIN
 * sem MFA recente e sem elevação -> clique em Research -> o painel precisa abrir a verificação de 2º fator. Contato verificado NÃO é MFA e nunca concede elevação.
 */
class ResearchGateReproductionTest {
    private static String oldHome;
    private static Path home;

    @BeforeAll
    static void sandbox() throws Exception {
        FxSupport.start();
        oldHome = System.getProperty("user.home");
        home = Files.createTempDirectory("byx-gate-home-");
        Path settings = Files.createDirectories(home.resolve(".mvp-binance-panel"));
        Files.writeString(settings.resolve("security.properties"), "security.dev.mode=true\n");
        Files.writeString(settings.resolve("settings.properties"), "dataSource=REAL\nprojectPath=" + home.resolve("empty-project")
                + "\ncliPath=/usr/bin/false\nmotion=OFF\ndensity=COMPACT\nonboardingCompleted=true\n");
        System.setProperty("user.home", home.toString());
    }

    @AfterAll
    static void restore() {
        System.setProperty("user.home", oldHome);
    }

    static final class App extends PanelApp {
        final FakeAuthority authority = new FakeAuthority(Clock.systemUTC());

        @Override
        protected AppContext createContext() {
            return AppContext.create(null, new AppContext.Providers(authority, "FAKE AUTHORITY (test only)"));
        }

        @SuppressWarnings("unchecked")
        <T> T field(String name) {
            try {
                Field f = PanelApp.class.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(this);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        void invoke(String name, Class<?> type, Object arg) {
            try {
                Method m = PanelApp.class.getDeclaredMethod(name, type);
                m.setAccessible(true);
                m.invoke(this, arg);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        AppContext ctx() { return field("ctx"); }
        ShellRouter router() { return field("router"); }
        Object overlay() { return field("tfOverlay"); }
        boolean checking() { return field("checkingTrustedDevice"); }
        void show(String id) { invoke("show", String.class, id); }
    }

    private App app;
    private PrintStream realErr;
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @AfterEach
    void close() throws Exception {
        if (realErr != null) System.setErr(realErr);
        if (app != null) {
            App a = app;
            FxSupport.fx(() -> {
                try { a.stop(); } catch (RuntimeException ignored) { }
                Stage stage = a.field("stage");
                stage.close();
                return null;
            });
        }
    }

    private App open(Boolean emailVerified, Boolean phoneVerified) throws Exception {
        App created = FxSupport.fx(App::new);
        created.init(); // launcher thread in production; never initialize persistence on FX
        app = FxSupport.fx(() -> {
            App a = created;
            a.authority.emailVerifiedOverride = emailVerified;
            a.authority.phoneVerifiedOverride = phoneVerified;
            a.start(new Stage());
            a.authority.add("gate-admin", "gate-admin@example.invalid", "+5511999991234", "gate-qa-pass-1", Role.ADMIN, false);
            User admin = a.ctx().auth.login("gate-admin", "gate-qa-pass-1".toCharArray());
            a.invoke("afterLogin", User.class, admin);
            return a;
        });
        await(() -> (boolean) app.field("mainActive"));
        assertFalse(app.authority.calls.contains("begin"), "no provider/challenge call yet");
        return app;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxSupport.fx(() -> condition.getAsBoolean())) return;
            Thread.sleep(20);
        }
        fail("condition not reached");
    }

    /** Espera o fim do check de dispositivo em outra thread e processa o que ele enfileirou na thread FX. */
    private void awaitCheckFinished() throws Exception {
        await(() -> !app.checking());
        FxSupport.fx(() -> null);
    }

    private void captureErr() {
        realErr = System.err;
        System.setErr(new PrintStream(errBytes, true));
    }

    // ---- 1/3/7: contato verificado não é MFA; sem MFA a verificação de 2º fator abre ---------------------------------------------------

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void researchWithoutRecentMfaOpensTwoFactorForEveryVerifiedFlagCombination(boolean emailVerified, boolean phoneVerified) throws Exception {
        open(emailVerified, phoneVerified);
        FxSupport.fx(() -> { app.show("overview"); return null; });
        await(() -> app.overlay() != null);
        assertNotEquals("overview", app.router().route(), "Research did not open");
        assertFalse(app.ctx().adminAccess.hasValidAdminSession(), "contact verification never grants elevation");
        assertTrue(app.ctx().sessions.admin().isEmpty());
        assertFalse(app.authority.calls.contains("begin") || app.authority.calls.contains("sms"), "no provider call before the user starts 2FA");
        var status = app.ctx().adminAccess.refresh().orElseThrow();
        assertFalse(status.path("mfaRecent").asBoolean(true));
        assertFalse(status.path("elevated").asBoolean(true));
        assertEquals(emailVerified, status.path("emailVerified").asBoolean());
        assertEquals(phoneVerified, status.path("phoneVerified").asBoolean());
    }

    // ---- 8: um pedido = no máximo um pedido de elevação (e nenhum sem MFA recente) -----------------------------------------------------

    @Test
    void oneResearchRequestWithoutRecentMfaMakesNoElevationRequestAndRepeatedClicksNeverMultiplyThem() throws Exception {
        open(true, true);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdDeviceCheck(reached, release);
        FxSupport.fx(() -> {
            for (int i = 0; i < 6; i++) app.show("overview"); // cliques repetidos com o check em andamento
            return null;
        });
        await(reached);
        FxSupport.fx(() -> { for (int i = 0; i < 6; i++) app.show("overview"); return null; }); // e mais cliques enquanto segura
        release.countDown();
        await(() -> app.overlay() != null);
        assertTrue(app.authority.elevationCalls <= 1, "bounded: " + app.authority.elevationCalls);
        assertEquals(0, app.authority.elevationCalls, "without recent MFA the service is not even asked for an elevation");
        assertFalse(app.ctx().adminAccess.hasValidAdminSession(), "no automatic elevation");
    }

    /** Segura a thread do check de dispositivo (e só ela) dentro de sessionStatus até o teste liberar; sinaliza quando chegou lá. */
    private void holdDeviceCheck(CountDownLatch reached, CountDownLatch release) {
        app.authority.beforeStatus = () -> {
            if ("trusted-device-check".equals(Thread.currentThread().getName()) && reached.getCount() > 0) {
                reached.countDown();
                await(release);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    // ---- 4: ciclo de vida do ticket pendente (corrida determinística) -------------------------------------------------------------------

    @Test
    void theSessionRefreshInsideTheDeviceCheckKeepsTheTicketAndTheSessionIdentityAndOpensTheOverlay() throws Exception {
        open(true, true);
        var before = app.ctx().sessions.user().orElseThrow().id();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdDeviceCheck(reached, release);
        FxSupport.fx(() -> { app.show("overview"); return null; });
        await(reached);
        assertNotNull(app.router().pending(), "ticket created and still pending while the async check runs");
        assertTrue(app.checking());
        release.countDown(); // o refresh do check (apply -> updateUser) acontece AGORA, antes do callback na thread FX
        awaitCheckFinished();
        assertEquals(before, app.ctx().sessions.user().orElseThrow().id(), "a status refresh keeps the panel session identity (it never changes without a login/logout)");
        assertNotNull(app.overlay(), "showTwoFactor received a valid ticket and a valid session: the overlay is open");
        assertNotNull(app.router().pending());
    }

    @Test
    void theStableSessionIdentityStillChangesOnNewLoginAndLogout() throws Exception {
        open(true, true);
        var first = app.ctx().sessions.user().orElseThrow().id();
        app.ctx().adminAccess.refresh();
        assertEquals(first, app.ctx().sessions.user().orElseThrow().id());
        FxSupport.fx(() -> { app.ctx().sessions.logout(); return null; });
        assertTrue(app.ctx().sessions.user().isEmpty());
        var second = FxSupport.fx(() -> {
            app.authority.add("other-admin", "other@example.invalid", "+5511999990000", "gate-qa-pass-2", Role.ADMIN, false);
            return app.ctx().auth.login("other-admin", "gate-qa-pass-2".toCharArray());
        });
        assertNotEquals(first, app.ctx().sessions.user().orElseThrow().id(), "a new login is a new session identity");
        assertNotNull(second);
    }

    @Test
    void navigatingAwayWhileTheCheckRunsShowsNoOverlay() throws Exception {
        open(true, true);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdDeviceCheck(reached, release);
        FxSupport.fx(() -> { app.show("overview"); return null; });
        await(reached);
        FxSupport.fx(() -> { app.show("t-desk"); return null; }); // o usuário sai enquanto o check roda
        release.countDown();
        awaitCheckFinished();
        assertNull(app.overlay(), "the late callback of a cancelled request shows nothing");
        assertEquals("t-desk", app.router().route());
    }

    // ---- 5/6: exceção ao abrir o overlay: fail-closed, sem silêncio e sem dados pessoais ------------------------------------------------

    @Test
    void anOverlayFailureFailsClosedWithAFixedRedactedCodeAndKeepsResearchBlocked() throws Exception {
        open(true, true);
        captureErr();
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdDeviceCheck(reached, release);
        FxSupport.fx(() -> { app.show("overview"); return null; }); // pedido pendente
        await(reached);
        assertNotNull(app.router().pending());
        // a falha chega com dados pessoais na mensagem: nada disso pode ir ao log
        FxSupport.fx(() -> {
            app.invoke("researchGateFailed", Throwable.class, new IllegalStateException("boom gate-admin@example.invalid +5511999991234 123456"));
            return null;
        });
        String err = errBytes.toString();
        assertTrue(err.contains("BYX_RESEARCH_GATE_ERROR code=overlay_open_failed"), err);
        assertTrue(err.contains("IllegalStateException"));
        assertFalse(err.contains("gate-admin@example.invalid") || err.contains("+5511999991234") || err.contains("123456"), "no contact data or code in the log");
        assertNull(app.router().pending(), "the pending Research request is cancelled");
        assertNull(app.overlay());
        assertNotEquals("overview", app.router().route(), "Research stays blocked");
        assertFalse(app.ctx().adminAccess.hasValidAdminSession());
        release.countDown();
        awaitCheckFinished();
        assertNull(app.overlay(), "the late callback of the cancelled request shows nothing");
    }

    @Test
    void maskingNeverThrowsForAnyContactShape() {
        for (String email : new String[] {null, "", "x", "@x", "ab@example.invalid", "gate-admin@example.invalid"}) {
            for (String phone : new String[] {null, "", "123", "+5511999991234"}) {
                var u = new User(1, "u", email, "", Role.ADMIN, UserStatus.ACTIVE, phone, true, true, false, java.time.Instant.EPOCH, java.time.Instant.EPOCH, null);
                assertDoesNotThrow(u::maskedEmail);
                assertDoesNotThrow(u::maskedPhone);
                assertFalse(u.maskedEmail().contains("gate-admin"));
                assertFalse(u.maskedPhone().contains("5511999"));
            }
        }
    }

    // ---- 7: só OTP real recente concede a elevação ---------------------------------------------------------------------------------------

    @Test
    void verifiedContactsAndNoMfaMeansElevationDeniedAndOnlyARecentSecondFactorGrantsIt() throws Exception {
        open(true, true);
        var denied = app.authority.adminElevation();
        assertFalse(denied.ok());
        assertEquals("ELEVATION_REQUIRES_MFA", denied.code());
        FxSupport.fx(() -> { app.show("overview"); return null; });
        await(() -> app.overlay() != null);
        FxSupport.fx(() -> {
            var flow = app.ctx().adminAccess.startTwoFactor();
            flow.sendEmailCode();
            assertEquals(panel.auth.TwoFactorResult.OK, flow.verifyEmail(app.authority.lastEmailCode()));
            flow.sendSmsCode();
            assertEquals(panel.auth.TwoFactorResult.OK, flow.verifySms(app.authority.lastSmsCode()));
            return null;
        });
        assertTrue(app.ctx().adminAccess.hasValidAdminSession(), "granted only after the recent second factor");
        assertEquals(List.of(), app.authority.calls.stream().filter("enroll"::equals).toList(), "no trusted device enrolled");
    }
}
