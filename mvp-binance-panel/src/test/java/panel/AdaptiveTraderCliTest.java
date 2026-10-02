package panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import panel.adapter.AdaptiveTraderCli;
import panel.adapter.CommandSpec;

class AdaptiveTraderCliTest {
    private final AdaptiveTraderCli cli = new AdaptiveTraderCli(() -> "/x/adaptive-trader");

    @Test
    void buildsWhitelistedCommand() {
        assertEquals(List.of("/x/adaptive-trader", "research", "microstructure", "label-status"), cli.build(CommandSpec.LABEL_STATUS, null));
    }

    @Test
    void sessionCommandAddsValidatedSession() {
        String id = "microstructure-20260814T011521Z-usd_m_futures";
        assertEquals(List.of("/x/adaptive-trader", "research", "microstructure", "label-run-session", "--session", id), cli.build(CommandSpec.LABEL_RUN_SESSION, id));
    }

    @Test
    void rejectsInvalidOrProtectedSessions() {
        assertThrows(IllegalArgumentException.class, () -> cli.build(CommandSpec.LABEL_RUN_SESSION, null));
        assertThrows(IllegalArgumentException.class, () -> cli.build(CommandSpec.LABEL_RUN_SESSION, "x; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> cli.build(CommandSpec.LABEL_RUN_SESSION, "microstructure-20260814T011521Z-validation"));
        assertThrows(IllegalArgumentException.class, () -> new AdaptiveTraderCli(() -> "/x/FINAL_HOLDOUT/cli").build(CommandSpec.LABEL_STATUS, null));
    }
}
