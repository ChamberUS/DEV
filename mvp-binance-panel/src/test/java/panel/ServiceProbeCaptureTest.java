package panel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import panel.localservice.ServiceProbe;

/** A sonda empacotada imprime o estado da captura científica pelo modelo do dock (somente leitura, sem login, sem mercado). */
class ServiceProbeCaptureTest {
    @Test
    void captureFlagPrintsTheDockModelStateWithoutAnyMarketOrLogin() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ServiceProbe.run(new String[] {"--capture"}, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("(?m)^probe\\.capture\\.status=(RUNNING|DEGRADED|STOPPED|UNKNOWN)").matcher(out).find(), out);
        assertTrue(Pattern.compile("(?m)^probe\\.capture\\.dock=(OPERATIONAL|DEGRADED|UNAVAILABLE|UNKNOWN)$").matcher(out).find(), out);
        assertTrue(out.contains("probe.capture.full.ms=") && out.contains("probe.capture.fast.ms="), out);
        assertTrue(!out.contains("probe.market."), "the capture probe never starts the market");
    }
}
