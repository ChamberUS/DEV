package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Test;
import panel.model.TraderSnapshot;

/**
 * Step 7 não toca no live trading: o Desk não habilita execução, não envia ordem, não altera a flag, não cria
 * endpoint de execução e não altera salvaguardas. Controles visuais relacionados ficam desabilitados/gated.
 */
class DeskGuardTest {
    private static final Path MAIN = Path.of("src/main/java");

    @Test
    void theDeskHasNoOrderEntryAndNoExecutionControl() throws Exception {
        DeskHarness.fx(() -> {
            for (int[] size : new int[][] {{1440, 900}, {1600, 1000}, {1920, 1080}}) {
                DeskHarness d = DeskHarness.open(size[0], size[1], DeskFixtures.liveWithAccount(1));
                assertTrue(DeskNodes.all(d.desk, TextInputControl.class).isEmpty(), "no input to type an order into");
                assertTrue(DeskNodes.all(d.desk, javafx.scene.control.Slider.class).isEmpty(), "no leverage/size slider");
                assertTrue(DeskNodes.all(d.desk, javafx.scene.control.Spinner.class).isEmpty());
                assertTrue(DeskNodes.all(d.desk, javafx.scene.control.CheckBox.class).isEmpty());
                assertTrue(DeskNodes.all(d.desk, javafx.scene.control.MenuButton.class).isEmpty());
                Pattern forbidden = Pattern.compile("(?i)\\b(buy|sell|long|short|place|submit|execute|send order|start bot|stop bot|enable|confirm|close position|cancel)\\b");
                for (ButtonBase b : DeskNodes.all(d.desk, ButtonBase.class)) {
                    assertFalse(forbidden.matcher(String.valueOf(b.getText())).find(), "no trading verb on a control: " + b.getText());
                }
                // os únicos controles são navegação: timeframe, abas de contexto, abas do blotter
                List<String> allowed = List.of("1m", "5m", "15m", "1h", "4h", "Market", "Bot", "Risk", "Positions 1", "Orders 1",
                        "Trades", "Signals", "Activity");
                for (ButtonBase b : DeskNodes.all(d.desk, ButtonBase.class)) {
                    assertTrue(allowed.contains(b.getText()), "unexpected control: " + b.getText());
                }
                // o único timeframe real é 1m: os demais ficam desabilitados com motivo
                var tf = d.desk.header().timeframe().buttons();
                assertFalse(tf.get(0).isDisabled());
                for (int i = 1; i < tf.size(); i++) {
                    assertTrue(tf.get(i).isDisabled(), tf.get(i).getText());
                    assertTrue(tf.get(i).getTooltip().getText().contains("1m"));
                }
                d.close();
            }
        });
    }

    @Test
    void renderingNeverWritesTheLiveTradingFlagOrAnyOtherSnapshotField() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.liveWithAccount(3);
            t.trading = "DISABLED";
            t.mode = "RESEARCH";
            int before = DeskModel.fingerprint(t);
            DeskHarness d = DeskHarness.open(1440, 900, t);
            for (int[] s : new int[][] {{1600, 1000}, {1920, 1080}, {1280, 760}, {1440, 900}}) {
                d.resize(s[0], s[1]);
                d.show(t);
            }
            for (var tab : BlotterPanel.Tab.values()) {
                d.desk.blotter().select(tab);
            }
            d.desk.contextTabs().select(1);
            assertEquals("DISABLED", t.trading);
            assertEquals("RESEARCH", t.mode);
            assertEquals(before, DeskModel.fingerprint(t), "the snapshot is exactly what it was");
            assertEquals("LIVE OFF", ((Labeled) d.desk.lookup("#desk-live-badge")).getText());
            assertEquals("Off", d.desk.botRows().liveRow().valueLabel().getText());
            d.close();
        });
    }

    @Test
    void liveTradingIsOnlyEverShownAsTextNeverAsAControl() throws Exception {
        DeskHarness.fx(() -> {
            TraderSnapshot t = DeskFixtures.noFeed();
            t.trading = "ENABLED"; // mesmo que um provider futuro diga ENABLED, a UI só mostra; não oferece controle
            DeskHarness d = DeskHarness.open(1920, 1080, t);
            for (Control c : DeskNodes.all(d.desk, Control.class)) {
                if (c instanceof ButtonBase b) {
                    assertFalse(b.getText().toLowerCase().contains("live"), b.getText());
                }
            }
            assertEquals("LIVE ENABLED", ((Labeled) d.desk.lookup("#desk-live-badge")).getText());
            d.close();
        });
    }

    @Test
    void thereIsNoExecutionCodeOrEndpointInTheTradingSources() throws IOException {
        Pattern forbidden = Pattern.compile("(?i)(HttpClient|HttpURLConnection|WebSocket|api\\.binance|fapi\\.binance|newOrder|placeOrder|"
                + "order/test|/fapi/|listenKey|apiKey|api_key|secretKey|signature=|HmacSHA256|\\.trading\\s*=[^=])");
        try (Stream<Path> files = Stream.concat(Files.walk(MAIN.resolve("panel/tradeview")), Files.walk(MAIN.resolve("panel/ui/trader")))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                assertFalse(forbidden.matcher(src).find(), p + " must not contain execution, exchange or key code");
            }
        }
    }

    @Test
    void nothingInMainCreatesAnOrderEndpointOrWritesTheTradingFlag() throws IOException {
        Pattern endpoint = Pattern.compile("(?i)(newOrder|placeOrder|order/test|/fapi/v1/order|api\\.binance\\.com|fapi\\.binance\\.com)");
        Pattern flagWrite = Pattern.compile("\\.trading\\s*=[^=]");
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                assertFalse(endpoint.matcher(src).find(), p + " must not reference an order endpoint");
                assertFalse(flagWrite.matcher(src).find(), p + " must not write the live trading flag");
            }
        }
    }

    @Test
    void theRealProviderStillReportsExecutionDisabledWithNoFeedAndNoAccount() {
        TraderSnapshot t = new panel.adapter.ResearchModeTradingProvider().load(new panel.model.Snapshot());
        assertEquals("DISABLED", t.trading);
        assertEquals("NOT_CONFIGURED", t.feed);
        assertEquals(DeskModel.Feed.NO_FEED, DeskModel.feed(t, DeskFixtures.NOW));
    }
}
