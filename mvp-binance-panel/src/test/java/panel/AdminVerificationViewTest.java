package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import org.junit.jupiter.api.Test;
import panel.auth.TwoFactorResult;
import panel.authview.AdminVerificationView;
import panel.design.ByxOtpInput;
import panel.design.ByxTheme;
import panel.motion.MotionService;

/** Verificação de admin V2 sobre o fluxo real: e-mail → SMS → trust opcional; não configurado; descarte. */
class AdminVerificationViewTest {
    /** Fluxo falso com o mesmo contrato do TwoFactorFlow. */
    private static final class FakeFlow implements AdminVerificationView.Flow {
        final List<String> calls = new ArrayList<>();
        String emailCode = "123456";
        String smsCode = "9876";
        Boolean trusted;
        boolean cancelled;

        @Override public void sendEmailCode() { calls.add("sendEmail"); }
        @Override public TwoFactorResult verifyEmail(String c) { calls.add("verifyEmail"); return c.equals(emailCode) ? TwoFactorResult.OK : TwoFactorResult.INVALID; }
        @Override public void sendSmsCode() { calls.add("sendSms"); }
        @Override public TwoFactorResult verifySms(String c) { calls.add("verifySms"); return c.equals(smsCode) ? TwoFactorResult.OK : TwoFactorResult.INVALID; }
        @Override public void finish(boolean trust) { trusted = trust; }
        @Override public long resendSeconds(boolean phone) { return 0; }
        @Override public void cancel() { cancelled = true; }
    }

    private record Fixture(AdminVerificationView view, FakeFlow flow, AtomicInteger ok, AtomicInteger cancel) {
    }

    /** Executores imediatos: worker e FX na mesma thread (FX) para um teste determinístico. */
    private static Fixture fixture(AdminVerificationView.Starter starter, FakeFlow flow) {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger cancel = new AtomicInteger();
        AdminVerificationView v = new AdminVerificationView(new MotionService(), starter, Runnable::run, Runnable::run,
                "qa••••@example.invalid", "•• •••••-1234", false,
                ok::incrementAndGet, cancel::incrementAndGet);
        Scene s = new Scene(v, 1372, 806);
        ByxTheme.apply(s);
        v.applyCss();
        return new Fixture(v, flow, ok, cancel);
    }

    private static Button button(AdminVerificationView v, String startsWith) {
        return v.lookupAll(".byx-btn").stream().map(n -> (Button) n).filter(b -> b.getText().startsWith(startsWith)).findFirst().orElseThrow();
    }

    @Test
    void emailThenSmsThenOptionalTrust() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            FakeFlow flow = new FakeFlow();
            Fixture f = fixture(() -> flow, flow);
            String first = f.view().screen();
            button(f.view(), "Send code").fire();
            ((ByxOtpInput) f.view().lookup(".byx-otp")).type("123456");
            button(f.view(), "Verify email").fire();
            String second = f.view().screen();
            button(f.view(), "Send code").fire();
            ((javafx.scene.control.TextField) f.view().lookup(".byx-code-input")).setText("9876");
            button(f.view(), "Verify phone").fire();
            String third = f.view().screen();
            CheckBox trust = (CheckBox) f.view().lookup(".byx-check");
            boolean trustOffByDefault = !trust.isSelected();
            button(f.view(), "Continue to Research").fire();
            return new Object[] {first, second, third, trustOffByDefault, flow.trusted, f.ok().get(), List.copyOf(flow.calls)};
        });
        assertEquals("EMAIL", r[0]);
        assertEquals("SMS", r[1]);
        assertEquals("COMPLETE", r[2]);
        assertTrue((boolean) r[3], "Trust this device is off unless chosen");
        assertEquals(Boolean.FALSE, r[4]);
        assertEquals(1, r[5], "success hands over once");
        assertEquals(List.of("sendEmail", "verifyEmail", "sendSms", "verifySms"), r[6]);
    }

    @Test
    void invalidCodeStaysOnStepWithTextAndClearsInput() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            FakeFlow flow = new FakeFlow();
            Fixture f = fixture(() -> flow, flow);
            button(f.view(), "Send code").fire();
            ByxOtpInput otp = (ByxOtpInput) f.view().lookup(".byx-otp");
            otp.type("000000");
            button(f.view(), "Verify email").fire();
            return new Object[] {f.view().screen(), f.view().statusText(), otp.filled(), f.ok().get()};
        });
        assertEquals("EMAIL", r[0]);
        assertEquals("That code is not valid. Try again.", r[1], "error is text, not color only");
        assertEquals(0, r[2], "entered code is cleared");
        assertEquals(0, r[3]);
    }

    @Test
    void providersNotConfiguredShowRealStatusAndNoSuccess() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Fixture f = fixture(() -> {
                throw new AdminVerificationView.NotConfigured("Resend: NOT_CONFIGURED");
            }, null);
            return new Object[] {f.view().screen(), f.view().statusText(), f.ok().get()};
        });
        assertEquals("BLOCKED", r[0]);
        assertEquals("Verification providers not configured", r[1]);
        assertEquals(0, r[2]);
    }

    @Test
    void cancelAndDisposeCancelTheChallengeAndIgnoreLateResults() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            FakeFlow flow = new FakeFlow();
            Fixture f = fixture(() -> flow, flow);
            button(f.view(), "Cancel").fire();
            boolean cancelled = flow.cancelled;
            // depois de descartada, nenhum resultado conclui a verificação
            button(f.view(), "Verify email").fire();
            return new Object[] {cancelled, f.cancel().get(), f.ok().get(), f.view().closed()};
        });
        assertTrue((boolean) r[0], "challenge cancelled");
        assertEquals(1, r[1]);
        assertEquals(0, r[2], "a closed view never succeeds");
        assertTrue((boolean) r[3]);
    }

    @Test
    void lateStartAfterCloseCancelsTheNewChallenge() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            FakeFlow flow = new FakeFlow();
            List<Runnable> pending = new ArrayList<>();
            AdminVerificationView v = new AdminVerificationView(new MotionService(), () -> flow, pending::add, Runnable::run,
                    "e", "p", false, () -> { }, () -> { });
            v.dispose(); // a rota mudou antes dos providers responderem
            pending.forEach(Runnable::run);
            return new boolean[] {flow.cancelled, !"EMAIL".equals(v.screen())};
        });
        assertTrue(r[0], "challenge started after close is cancelled");
        assertTrue(r[1]);
    }
}
