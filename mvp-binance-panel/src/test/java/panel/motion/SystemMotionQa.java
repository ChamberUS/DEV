package panel.motion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verificação manual contra o macOS REAL (não roda no surefire): mvn -o test -Dtest=SystemMotionQa.
 * Rode uma vez com "Reduzir movimento" desligado e outra ligado (Ajustes do Sistema › Acessibilidade › Tela). Imprime o que o app lê e
 * o motion efetivo de cada preferência; afirma que o sistema só reduz FULL e nunca afrouxa REDUCED/OFF.
 */
class SystemMotionQa {
    @Test
    void liveProbeAndEffectivePolicy() {
        boolean reduced = SystemMotionProbe.macOs().reduced();
        System.out.println("SYSTEM_REDUCE_MOTION=" + reduced);
        for (MotionPreference app : MotionPreference.values()) {
            for (boolean follow : new boolean[] {true, false}) {
                MotionPreference eff = MotionPolicy.effective(app, follow, reduced);
                System.out.println("app=" + app + " follow=" + follow + " -> effective=" + eff);
                if (app != MotionPreference.FULL) {
                    assertEquals(app, eff, "the system never changes an explicit REDUCED/OFF");
                } else {
                    assertEquals(follow && reduced ? MotionPreference.REDUCED : MotionPreference.FULL, eff);
                }
            }
        }
        assertTrue(SystemMotionProbe.isOn("1\n") && SystemMotionProbe.isOn("true") && !SystemMotionProbe.isOn("0") && !SystemMotionProbe.isOn(null)
                && !SystemMotionProbe.isOn("The domain/default pair of (x, y) does not exist"));
    }
}
