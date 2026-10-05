package panel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.design.ByxField;
import panel.design.ByxOtpInput;
import panel.design.ByxTheme;
import panel.motion.MotionPreference;
import panel.motion.MotionService;

/** OtpInput V2: só dígitos, avanço, Backspace, colar, máscara, zerar, tremor só em FULL. */
class ByxOtpInputTest {
    private static ByxOtpInput otp(MotionPreference p, Stage[] keep) {
        MotionService m = new MotionService();
        m.preference.set(p);
        ByxOtpInput o = new ByxOtpInput(6, m);
        Scene s = new Scene(new VBox(o), 600, 200);
        ByxTheme.apply(s);
        Stage st = new Stage();
        st.setScene(s);
        st.show();
        keep[0] = st;
        o.boxes().get(0).requestFocus();
        return o;
    }

    private static void typed(javafx.scene.Node n, String ch) {
        Event.fireEvent(n, new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, false, false));
    }

    private static void pressed(javafx.scene.Node n, KeyCode k) {
        Event.fireEvent(n, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", k, false, false, false, false));
    }

    @Test
    void digitsOnlyAdvanceAndMask() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Stage[] st = new Stage[1];
            ByxOtpInput o = otp(MotionPreference.OFF, st);
            typed(o.boxes().get(0), "4");
            typed(o.boxes().get(1), "x"); // ignorado
            typed(o.boxes().get(1), "2");
            Object[] out = {o.filled(), o.boxes().get(0).getText(), o.getScene().getFocusOwner() == o.boxes().get(2), new String(o.value()).replace('\0', '_')};
            st[0].close();
            return out;
        });
        assertEquals(2, r[0]);
        assertEquals("•", r[1], "digits are masked");
        assertTrue((boolean) r[2], "focus advanced to box 3");
        assertEquals("42____", r[3]);
    }

    @Test
    void backspaceGoesBackAndPasteDistributes() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Stage[] st = new Stage[1];
            ByxOtpInput o = otp(MotionPreference.OFF, st);
            o.paste(0, "12 34-56");
            boolean complete = o.isComplete();
            pressed(o.boxes().get(5), KeyCode.BACK_SPACE); // apaga o 6º
            pressed(o.boxes().get(5), KeyCode.BACK_SPACE); // vazio: volta e apaga o 5º
            Object[] out = {complete, new String(o.value()).replace('\0', '_'), o.getScene().getFocusOwner() == o.boxes().get(4)};
            st[0].close();
            return out;
        });
        assertTrue((boolean) r[0]);
        assertEquals("1234__", r[1]);
        assertTrue((boolean) r[2]);
    }

    @Test
    void completionCallbackAndClearZeroes() throws Exception {
        Object[] r = FxSupport.fx(() -> {
            Stage[] st = new Stage[1];
            ByxOtpInput o = otp(MotionPreference.OFF, st);
            AtomicInteger done = new AtomicInteger();
            o.setOnComplete(done::incrementAndGet);
            o.type("654321");
            char[] v = o.value();
            o.clear();
            Object[] out = {done.get(), new String(v), o.filled(), o.boxes().get(0).getText()};
            st[0].close();
            return out;
        });
        assertEquals(1, r[0]);
        assertEquals("654321", r[1]);
        assertEquals(0, r[2]);
        assertEquals("", r[3]);
    }

    @Test
    void invalidShakesOnlyInFull() throws Exception {
        for (MotionPreference p : MotionPreference.values()) {
            boolean[] r = FxSupport.fx(() -> {
                Stage[] st = new Stage[1];
                ByxOtpInput o = otp(p, st);
                o.setInvalid(true);
                boolean shaking = o.shaking();
                boolean error = o.getPseudoClassStates().stream().anyMatch(c -> c.getPseudoClassName().equals("error"));
                o.type("1");
                boolean cleared = o.getPseudoClassStates().stream().noneMatch(c -> c.getPseudoClassName().equals("error"));
                st[0].close();
                return new boolean[] {shaking, error, cleared};
            });
            assertEquals(p == MotionPreference.FULL, r[0], p + " errorFeedback shake");
            assertTrue(r[1], p + " red border (not color only: the screen also shows text)");
            assertTrue(r[2], p + " typing clears the error");
        }
    }

    @Test
    void fieldAccessorySitsOnTheLabelRow() throws Exception {
        boolean[] r = FxSupport.fx(() -> {
            ByxField f = ByxField.password("Password");
            Button forgot = new Button("Forgot password?");
            f.setAccessory(forgot);
            f.setAccessory(forgot); // idempotente
            return new boolean[] {forgot.getParent() != null && forgot.getParent().getStyleClass().contains("byx-field-label-row"),
                    f.lookupAll(".button").stream().filter(n -> n == forgot).count() == 1};
        });
        assertArrayEquals(new boolean[] {true, true}, r, "accessory on the label row, added once");
    }
}
