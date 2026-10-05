package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.motion.MotionSpec;
import panel.motion.MotionSpec.Kind;

/** BYX_MOTION_TOKENS.json resolvido por modo; FULL/REDUCED/OFF seguem o JSON sem tempos inventados. */
class MotionSpecTest {
    private static Duration ms(double v) {
        return Duration.millis(v);
    }

    @Test
    void loadsEveryToken() {
        MotionSpec s = MotionSpec.get();
        assertEquals("3.0.0-final", s.version());
        assertEquals(79, s.tokens().size());
        for (MotionPreference m : MotionPreference.values()) {
            s.tokens().values().forEach(t -> t.resolve(m)); // nenhum valor desconhecido
        }
    }

    @Test
    void cssVariableTableMatchesTokens() throws Exception {
        JsonNode css;
        try (InputStream in = MotionSpec.class.getResourceAsStream(MotionSpec.RESOURCE)) {
            css = new ObjectMapper().readTree(in).path("cssVariables");
        }
        Map<String, String> varToToken = Map.of("--m-hover", "hover", "--m-press", "press", "--m-enter", "pageEnter",
                "--m-exit", "overlayClose", "--m-menu", "menuOpen", "--m-menu-out", "menuClose", "--m-toast", "toastEnter",
                "--m-base", "workspaceChange", "--m-acc", "accordionExpand", "--m-row", "rowRemove");
        MotionPreference[] order = {MotionPreference.FULL, MotionPreference.REDUCED, MotionPreference.OFF};
        varToToken.forEach((var, token) -> {
            for (int i = 0; i < 3; i++) {
                MotionSpec.Resolved r = MotionSpec.get().token(token).resolve(order[i]);
                double expected = css.path(var).get(i).asDouble();
                assertEquals(expected, r.runs() ? r.duration().toMillis() : 0, 0.001, var + " " + order[i]);
            }
        });
    }

    @Test
    void numericValuesPerMode() {
        var hover = MotionSpec.get().token("hover");
        assertEquals(ms(120), hover.resolve(MotionPreference.FULL).duration());
        assertEquals(ms(60), hover.resolve(MotionPreference.REDUCED).duration());
        assertEquals(Kind.INSTANT, hover.resolve(MotionPreference.OFF).kind());
        assertEquals(MotionSpec.ACCELERATE, MotionSpec.get().token("overlayClose").interpolator());
        assertEquals(MotionSpec.STANDARD, MotionSpec.get().token("overlayOpen").interpolator());
    }

    @Test
    void loopsFollowTheModeRules() {
        MotionSpec s = MotionSpec.get();
        // spinner é feedback essencial: continua em REDUCED, vira glifo parado em OFF
        assertTrue(s.token("loading").resolve(MotionPreference.REDUCED).runs());
        assertEquals(Kind.STATIC, s.token("loading").resolve(MotionPreference.OFF).kind());
        for (String loop : new String[] {"shimmer", "breathing", "statePulse"}) {
            assertEquals(Kind.NONE, s.token(loop).resolve(MotionPreference.REDUCED).kind(), loop);
            assertEquals(Kind.NONE, s.token(loop).resolve(MotionPreference.OFF).kind(), loop);
        }
        assertEquals(Kind.STATIC, s.token("regionLoading").resolve(MotionPreference.REDUCED).kind());
        assertEquals(Kind.INSTANT, s.token("progressFill").resolve(MotionPreference.OFF).kind());
    }

    @Test
    void logicalTimersAreModeIndependent() {
        MotionSpec s = MotionSpec.get();
        for (MotionPreference m : MotionPreference.values()) {
            assertEquals(ms(3000), s.token("restoredChipHold").resolve(m).duration(), m.name());
            assertEquals(ms(300), s.token("tooltip").resolve(m).delay(), m.name());
            assertEquals(ms(700), s.token("rowRemove").resolve(m).delay(), m.name());
        }
    }

    @Test
    void navigationTokensNeverAnimate() {
        for (String t : new String[] {"returnToExistingView", "navigationInterrupt", "dataUpdateResizeRefresh", "pageExit",
                "badgeCountChange", "searchResultsUpdate"}) {
            for (MotionPreference m : MotionPreference.values()) {
                assertFalse(MotionSpec.get().token(t).resolve(m).runs(), t + " " + m);
            }
        }
    }

    @Test
    void rangeAndFallbackAreExplicit() {
        var drift = MotionSpec.get().token("brandFieldDrift");
        assertEquals(ms(18000), drift.full());
        assertEquals(ms(40000), drift.fullMax());
        // profileSaved não declara reducedMs: teto documentado
        assertEquals(MotionSpec.REDUCED_FALLBACK_CAP, MotionSpec.get().token("profileSaved").resolve(MotionPreference.REDUCED).duration());
    }

    @Test
    void motionServiceResolvesInCurrentMode() {
        MotionService m = new MotionService();
        assertEquals(ms(320), m.duration("pageEnter"));
        assertTrue(m.translateAllowed());
        m.preference.set(MotionPreference.REDUCED);
        assertEquals(ms(120), m.duration("pageEnter"));
        assertFalse(m.translateAllowed());
        assertFalse(m.scaleAllowed());
        m.preference.set(MotionPreference.OFF);
        assertEquals(Duration.ZERO, m.duration("pageEnter"));
        assertEquals(Duration.ZERO, m.duration("shimmer"));
    }
}
