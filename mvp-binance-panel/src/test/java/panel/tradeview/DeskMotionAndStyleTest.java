package panel.tradeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.model.TraderSnapshot;
import panel.motion.MotionPreference;

/** FULL, REDUCED e OFF terminam no mesmo estado; dados de alta frequência não animam nada; o Desk é só V2. */
class DeskMotionAndStyleTest {
    private static List<TraderSnapshot> states() {
        return List.of(DeskFixtures.noFeed(), DeskFixtures.waiting(), DeskFixtures.live(1), DeskFixtures.liveWithAccount(2),
                DeskFixtures.stale(3), DeskFixtures.degraded(4), DeskFixtures.disconnected(5), DeskFixtures.error(), DeskFixtures.mock());
    }

    @Test
    void everyStateEndsIdenticallyInFullReducedAndOff() throws Exception {
        for (int[] size : new int[][] {{1440, 900}, {1920, 1080}}) {
            List<List<String>> perMode = new ArrayList<>();
            for (MotionPreference mode : MotionPreference.values()) {
                List<String> digests = new ArrayList<>();
                DeskHarness.fx(() -> {
                    DeskHarness d = DeskHarness.open(size[0], size[1], DeskFixtures.noFeed(), mode);
                    for (TraderSnapshot t : states()) {
                        d.show(t);
                        digests.add(String.join("\n", DeskNodes.digest(d.desk)));
                    }
                    d.close();
                });
                perMode.add(digests);
            }
            for (int m = 1; m < perMode.size(); m++) {
                for (int i = 0; i < perMode.get(0).size(); i++) {
                    List<String> full = List.of(perMode.get(0).get(i).split("\n"));
                    List<String> other = List.of(perMode.get(m).get(i).split("\n"));
                    assertEquals(full.size(), other.size(), size[0] + " state " + i + ": node count in " + MotionPreference.values()[m]);
                    for (int k = 0; k < full.size(); k++) {
                        assertEquals(full.get(k), other.get(k), size[0] + " state " + i + " node " + k + ": " + MotionPreference.values()[m] + " ends in the FULL state");
                    }
                }
            }
        }
    }

    @Test
    void loopsExistOnlyInFullAndNeverGrowWithTicks() throws Exception {
        DeskHarness.fx(() -> {
            for (MotionPreference mode : MotionPreference.values()) {
                DeskHarness d = DeskHarness.open(1600, 1000, DeskFixtures.noFeed(), mode);
                for (int i = 0; i < 100; i++) {
                    d.show(DeskFixtures.liveWithAccount(i));
                }
                int loops = d.motion.runningLoops();
                for (int i = 100; i < 200; i++) {
                    d.show(DeskFixtures.liveWithAccount(i));
                }
                assertEquals(loops, d.motion.runningLoops(), mode + ": ticks add no loop");
                assertTrue(loops <= 2, mode + ": at most the bot dot and the waiting mark loop (" + loops + ")");
                if (mode != MotionPreference.FULL) {
                    assertEquals(0, loops, mode + ": loops stop outside FULL");
                }
                d.close();
            }
        });
    }

    @Test
    void aTickNeverMovesOrFadesAPanel() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.liveWithAccount(0));
            List<Node> panels = DeskNodes.all(d.desk, Node.class).stream().filter(n -> n.getStyleClass().contains("byx-panel")).toList();
            assertFalse(panels.isEmpty());
            for (int i = 1; i <= 60; i++) {
                d.show(DeskFixtures.liveWithAccount(i));
                for (Node p : panels) {
                    assertEquals(0, p.getTranslateY(), 1e-9, "no page enter on a tick");
                    assertEquals(1.0, p.getOpacity(), 1e-9, "no fade on a tick");
                }
            }
            d.close();
        });
    }

    @Test
    void theDeskUsesOnlyTheV2ThemeAndNoLegacyClass() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.liveWithAccount(1));
            assertEquals(ByxTheme.urls(), d.scene.getStylesheets(), "the scene carries only the V2 theme");
            for (Node n = d.desk; n != null; n = n.getParent()) {
                assertFalse(n.getStyleClass().contains("byx-legacy-host"), "the Desk is outside the LegacyHost");
                assertFalse(n instanceof panel.shell.LegacyHost, "no LegacyHost among the ancestors");
            }
            assertTrue(d.shell.content().getChildren().isEmpty(), "nothing of the Desk lives in the legacy host");
            Set<String> legacy = Set.of("card", "card-title", "desk-header", "desk-chart", "desk-bottom", "desk-tabs", "desk-tab", "desk-period",
                    "desk-symbol", "desk-value", "desk-empty", "desk-empty-title", "th-bar-ask", "th-bar-bid", "skeleton", "market-feed-dot",
                    "bot-monitor-dot", "reference-row", "kv-value", "metric", "badge", "badge-warn");
            DeskNodes.walk(d.desk, n -> {
                for (String c : n.getStyleClass()) {
                    assertFalse(legacy.contains(c), "legacy class " + c + " on " + n.getClass().getSimpleName());
                }
            });
            d.close();
        });
    }

    @Test
    void v2FontsAndColoursResolveFromTheV2SheetsAlone() throws Exception {
        DeskHarness.fx(() -> {
            DeskHarness d = DeskHarness.open(1920, 1080, DeskFixtures.liveWithAccount(1));
            Labeled value = d.desk.metrics().cells().get(0).value();
            assertEquals("JetBrains Mono Medium", value.getFont().getFamily());
            assertEquals(22, value.getFont().getSize(), 0.01);
            assertEquals("Schibsted Grotesk SemiBold", ((Labeled) d.desk.lookup(".byx-desk-symbol")).getFont().getFamily());
            assertEquals(20, ((Labeled) d.desk.lookup(".byx-desk-symbol")).getFont().getSize(), 0.01);
            assertEquals("JetBrains Mono Medium", d.desk.header().priceLabel().getFont().getFamily());
            assertEquals(26, d.desk.header().priceLabel().getFont().getSize(), 0.01);
            assertEquals("JetBrains Mono Medium", d.desk.book().askRows().getFirst().cell(0).getFont().getFamily());
            // a cor do texto principal vem do token text.primary
            assertEquals(javafx.scene.paint.Color.web("#EEF1F8"), value.getTextFill());
            assertEquals(javafx.scene.paint.Color.web("#8791A8"), d.desk.metrics().cells().get(4).reason().getTextFill());
            // painel = surface bg1 (+ realce de 1 px no topo)
            var bg = d.desk.metrics().getBackground();
            assertEquals(javafx.scene.paint.Color.web("#121723"), bg.getFills().getLast().getFill());
            assertEquals(14, bg.getFills().getLast().getRadii().getTopLeftHorizontalRadius(), 0.01);
            d.close();
        });
    }
}
