package panel.design;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import panel.motion.MotionService;

/**
 * OtpInput V2 (handoff §6): N caixas 40x50, mono 20. Só dígitos; avança sozinho; Backspace volta; setas
 * movem; colar distribui os dígitos. Os dígitos aparecem mascarados (•) e ficam num char[] zerável: o código
 * nunca vai para log nem para o modelo de UI. Inválido: borda vermelha + um tremor (errorFeedback, só FULL).
 */
public class ByxOtpInput extends HBox {
    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final MotionService motion;
    private final List<TextField> boxes = new ArrayList<>();
    private final char[] digits;
    private Runnable onComplete = () -> { };
    private Timeline shake;

    public ByxOtpInput(int length, MotionService motion) {
        this.motion = motion;
        digits = new char[length];
        getStyleClass().add("byx-otp");
        setAccessibleText(length + "-digit code");
        for (int i = 0; i < length; i++) {
            TextField box = new TextField();
            box.getStyleClass().add("byx-otp-box");
            box.setAccessibleText("Digit " + (i + 1) + " of " + length);
            box.setEditable(false); // a entrada é tratada aqui: nada de texto real na caixa
            final int index = i;
            box.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                String ch = e.getCharacter();
                if (ch != null && ch.length() == 1 && Character.isDigit(ch.charAt(0))) {
                    put(index, ch.charAt(0));
                    focus(index + 1);
                }
                e.consume();
            });
            box.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.BACK_SPACE) {
                    if (digits[index] != 0) {
                        put(index, (char) 0);
                    } else if (index > 0) {
                        put(index - 1, (char) 0);
                        focus(index - 1);
                    }
                    e.consume();
                } else if (e.getCode() == KeyCode.LEFT) {
                    focus(index - 1);
                    e.consume();
                } else if (e.getCode() == KeyCode.RIGHT) {
                    focus(index + 1);
                    e.consume();
                } else if (e.getCode() == KeyCode.V && e.isShortcutDown()) {
                    String text = Clipboard.getSystemClipboard().getString();
                    if (text != null) {
                        paste(index, text);
                    }
                    e.consume();
                }
            });
            boxes.add(box);
            getChildren().add(box);
        }
    }

    public int length() {
        return digits.length;
    }

    public void setOnComplete(Runnable r) {
        onComplete = r == null ? () -> { } : r;
    }

    /** Distribui os dígitos colados a partir da caixa atual (ignora não-dígitos). */
    public void paste(int from, String text) {
        int i = from;
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) && i < digits.length) {
                put(i++, c);
            }
        }
        focus(Math.min(i, digits.length - 1));
    }

    /** Teclado programático (testes): mesmo caminho do KEY_TYPED. */
    public void type(String text) {
        int i = firstEmpty();
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c) && i >= 0 && i < digits.length) {
                put(i, c);
                i = firstEmpty();
            }
        }
    }

    private int firstEmpty() {
        for (int i = 0; i < digits.length; i++) {
            if (digits[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private void put(int i, char c) {
        if (i < 0 || i >= digits.length) {
            return;
        }
        digits[i] = c;
        boxes.get(i).setText(c == 0 ? "" : "•");
        setInvalid(false);
        if (isComplete() && c != 0) {
            onComplete.run();
        }
    }

    private void focus(int i) {
        if (i >= 0 && i < boxes.size()) {
            boxes.get(i).requestFocus();
        }
    }

    public void focusFirstEmpty() {
        int i = firstEmpty();
        focus(i < 0 ? digits.length - 1 : i);
    }

    public boolean isComplete() {
        return firstEmpty() < 0;
    }

    /** Cópia do código; o chamador deve zerá-la depois de usar. */
    public char[] value() {
        return Arrays.copyOf(digits, digits.length);
    }

    public int filled() {
        int n = 0;
        for (char d : digits) {
            if (d != 0) {
                n++;
            }
        }
        return n;
    }

    /** Zera os dígitos (depois de enviar, ou ao trocar de passo). */
    public void clear() {
        Arrays.fill(digits, (char) 0);
        boxes.forEach(b -> b.setText(""));
    }

    public void setInvalid(boolean on) {
        pseudoClassStateChanged(ERROR, on);
        if (on) {
            playShake();
        }
    }

    public boolean shaking() {
        return shake != null;
    }

    private void playShake() {
        if (shake != null) {
            shake.stop();
            shake = null;
            setTranslateX(0);
        }
        // errorFeedback: 320 ms em FULL; reducedMs = 0 e OFF = 0 → sem tremor (a borda vermelha e o texto bastam)
        Duration d = motion == null || !motion.translateAllowed() ? Duration.ZERO : motion.duration("errorFeedback");
        if (d.equals(Duration.ZERO)) {
            return;
        }
        shake = new Timeline(
                new KeyFrame(d.multiply(0.2), new KeyValue(translateXProperty(), -4)),
                new KeyFrame(d.multiply(0.4), new KeyValue(translateXProperty(), 4)),
                new KeyFrame(d.multiply(0.6), new KeyValue(translateXProperty(), -3)),
                new KeyFrame(d.multiply(0.8), new KeyValue(translateXProperty(), 2)),
                new KeyFrame(d, new KeyValue(translateXProperty(), 0)));
        shake.setOnFinished(e -> shake = null);
        shake.play();
    }

    public List<TextField> boxes() {
        return List.copyOf(boxes);
    }
}
