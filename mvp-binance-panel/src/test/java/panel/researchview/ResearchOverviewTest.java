package panel.researchview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.model.Snapshot;
import panel.model.StageState;
import panel.motion.MotionPreference;
import panel.researchview.ResearchModel.GuardKind;
import panel.researchview.ResearchModel.SegState;
import panel.tradeview.DeskHarness;
import panel.tradeview.DeskMode;
import panel.tradeview.DeskNodes;

/** Research Overview V2 no shell real: geometria da referência, estados reais, gates, incremental, movimento. */
class ResearchOverviewTest {
    private static void rect(String what, Bounds b, double x, double y, double w, double h) {
        assertEquals(x, b.getMinX(), 1, what + " x");
        assertEquals(y, b.getMinY(), 1, what + " y");
        assertEquals(w, b.getWidth(), 1, what + " w");
        if (h > 0) {
            assertEquals(h, b.getHeight(), 1, what + " h");
        }
    }

    private static Set<Node> identities(Node root) {
        Set<Node> out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        DeskNodes.walk(root, out::add);
        return out;
    }

    @Test
    void geometryAt1440MatchesTheReferenceMeasurements() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(34, true));
            assertEquals(DeskMode.COMPACT, h.overview.mode());
            Node root = h.overview;
            rect("header", h.rect(root.lookup("#research-header")), 88, 70, 1332, 82);
            rect("guard", h.rect(root.lookup("#research-guard")), 88, 166, 1332, 92);
            Bounds pipeline = h.rect(root.lookup("#research-pipeline"));
            rect("pipeline", pipeline, 88, 272, 1332, 108);
            Bounds kpi = h.rect(root.lookup("#research-kpis"));
            rect("main left", kpi, 88, 394, 958, 0);
            rect("right column", h.rect(root.lookup("#research-right")), 1060, 394, 360, 0);
            assertEquals(8, h.overview.tiles().size());
            assertTrue(h.overview.tiles().stream().noneMatch(t -> t.summaryLabel().isManaged()), "1440 shows the stage names only");
            h.close();
            return null;
        });
    }

    @Test
    void geometryAt1920AddsStageSummariesAndAWiderSideColumn() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1920, 1080, ResearchFixtures.trainReady(34, true));
            assertEquals(DeskMode.EXPANDED, h.overview.mode());
            Node root = h.overview;
            double mainW = 1920 - 68 - 40;
            rect("header", h.rect(root.lookup("#research-header")), 88, 70, mainW, 82);
            rect("guard", h.rect(root.lookup("#research-guard")), 88, 166, mainW, 92);
            Bounds pipe = h.rect(root.lookup("#research-pipeline"));
            rect("right column", h.rect(root.lookup("#research-right")), 88 + mainW - 420, pipe.getMaxY() + 14, 420, 0);
            assertTrue(h.overview.tiles().stream().allMatch(t -> t.summaryLabel().isManaged()), "1920 shows the real one-line summary");
            assertEquals("34 / 34", h.overview.tiles().get(2).summaryLabel().getText());
            assertEquals("34 sessions", h.overview.tiles().get(1).summaryLabel().getText());
            assertEquals(44, h.rect(h.overview.segments().getFirst()).getHeight(), 0.5);
            h.resize(1440, 900);
            h.show(h.source.snapshot);
            assertEquals(30, h.rect(h.overview.segments().getFirst()).getHeight(), 0.5, "the strip is lower again at 1440");
            h.close();
            return null;
        });
    }

    @Test
    void emptyEnvironmentUsesSemanticStatesAndInventsNothing() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.empty());
            String text = String.join(" | ", DeskNodes.visibleTexts(h.overview));
            assertFalse(text.contains("34"), text);
            assertFalse(text.contains("209,478"), text);
            assertFalse(text.contains("1 WARNING"), text);
            assertTrue(text.contains("Session states unavailable"), text);
            assertEquals("N/A", h.overview.kpis().get(1).value().getText());
            assertEquals("No warnings", h.overview.warningBadge().getText());
            assertEquals("0 failed jobs. Details in Logs.", h.overview.failedJobsLabel().getText());
            assertTrue(h.overview.segments().isEmpty());
            assertEquals(0, h.motion.runningLoops(), "nothing is running, nothing animates");
            assertEquals("STAGE · NO DATASET · IDLE", h.overview.stageBadge().getText());
            h.close();
            return null;
        });
    }

    @Test
    void thePipelineShowsRealStatesAndCapturingBreathesOnce() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1600, 1000, ResearchFixtures.trainReady(34, true));
            var tiles = h.overview.tiles();
            assertTrue(tiles.get(0).bar().getStyleClass().contains("current") && tiles.get(0).bar().getStyleClass().contains("live"));
            assertTrue(tiles.get(1).bar().getStyleClass().contains("complete"));
            assertTrue(tiles.get(4).bar().getStyleClass().contains("pending"));
            assertTrue(tiles.get(5).bar().getStyleClass().contains("locked"));
            assertTrue(tiles.get(5).hatched(), "validation is hatched");
            assertTrue(tiles.get(7).hatched(), "live is hatched");
            assertFalse(tiles.get(4).hatched(), "pending is not locked");
            assertEquals("STAGE · HYPOTHESIS RESEARCH · IDLE", h.overview.stageBadge().getText());
            // um loop para a barra do recorder + um para a sessão ativa; ciclos RUNNING/IDLE nunca acumulam
            assertEquals(2, h.motion.runningLoops());
            for (int i = 0; i < 20; i++) {
                h.show(ResearchFixtures.trainReady(34, false));
                assertEquals(0, h.motion.runningLoops(), "leaving RUNNING stops every animation");
                h.show(ResearchFixtures.trainReady(34, true));
                assertEquals(2, h.motion.runningLoops(), "returning to RUNNING creates exactly one instance each");
            }
            h.close();
            return null;
        });
    }

    @Test
    void sessionsComeFromRealDataAndOnlyTheActiveOneMoves() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(34, true));
            assertEquals(35, h.overview.segments().size());
            assertEquals("34 ready · 1 capturing", h.overview.sessionSummary().getText());
            assertSame(h.overview.segments().getLast(), h.overview.liveSegment());
            assertTrue(h.overview.segments().getLast().getStyleClass().contains("capturing"));
            assertTrue(h.overview.segments().get(0).getStyleClass().contains("complete"));
            Snapshot failed = ResearchFixtures.trainReady(12, false);
            failed.sessions.set(3, ResearchFixtures.session("s003", StageState.FAILED));
            h.show(failed);
            assertEquals(12, h.overview.segments().size());
            assertNull(h.overview.liveSegment());
            assertTrue(h.overview.segments().get(3).getStyleClass().contains("failed"));
            assertEquals(0, h.motion.runningLoops());
            h.close();
            return null;
        });
    }

    @Test
    void theGuardShowsThreeDifferentTreatmentsAndOffersNoControl() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(34, true));
            var tiles = h.overview.guardTiles();
            assertEquals(GuardKind.OPEN, tiles.get(0).kind());
            assertEquals(GuardKind.LOCKED, tiles.get(1).kind());
            assertEquals(GuardKind.SEALED, tiles.get(2).kind());
            assertEquals(List.of("Open", "Locked", "Sealed"), tiles.stream().map(t -> t.stateLabel().getText()).toList());
            // tratamentos visuais diferentes (cor/hachura), não três botões desabilitados
            assertEquals(3, new java.util.HashSet<>(tiles.stream().map(t -> t.getStyleClass().toString()).toList()).size());
            assertTrue(DeskNodes.all(tiles.get(2), Hatch.class).size() == 1, "the sealed tile is hatched");
            assertTrue(DeskNodes.all(tiles.get(1), Hatch.class).isEmpty());
            for (var tile : tiles) {
                assertTrue(DeskNodes.all(tile, ButtonBase.class).isEmpty(), "a gate is never a button");
            }
            h.show(ResearchFixtures.empty());
            assertEquals(GuardKind.LOCKED, tiles.get(1).kind());
            assertEquals(GuardKind.SEALED, tiles.get(2).kind());
            h.close();
            return null;
        });
    }

    @Test
    void theOnlyControlsNavigateAndNextStepReflectsTheRealRule() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(34, false));
            assertEquals("Open hypotheses", h.overview.nextButton().getText());
            h.overview.nextButton().fire();
            assertEquals(List.of("hypotheses"), h.navigations);
            Snapshot labels = ResearchFixtures.trainReady(34, false);
            labels.labelState = StageState.PARTIAL;
            labels.labelDone = 20;
            h.show(labels);
            assertEquals("Open labels", h.overview.nextButton().getText());
            h.overview.nextButton().fire();
            assertEquals("labels", h.navigations.getLast());
            List<ButtonBase> controls = DeskNodes.all(h.overview, ButtonBase.class);
            assertEquals(1, controls.size(), "no other button: only 'Open …' (navigation)");
            h.close();
            return null;
        });
    }

    @Test
    void stageTilesAndAttentionNavigateOnlyThroughTheCallback() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(5, false));
            h.overview.tiles().get(3).getOnMouseClicked().handle(null);
            h.overview.tiles().get(5).getOnMouseClicked().handle(null); // validation tile: opens the read-only locked page, nothing else
            assertEquals(List.of("labels", "validation"), h.navigations);
            h.overview.lookup("#research-attention").getOnMouseClicked().handle(null);
            assertEquals("logs", h.navigations.getLast());
            h.close();
            return null;
        });
    }

    @Test
    void attentionComesFromRealCounts() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(5, false));
            assertEquals("No warnings", h.overview.warningBadge().getText());
            Snapshot w = ResearchFixtures.trainReady(5, false);
            w.warnings.add("Backend refresh unavailable (IOException)");
            h.source.failed = 2;
            h.show(w);
            assertEquals("1 WARNING", h.overview.warningBadge().getText());
            assertTrue(h.overview.warningBadge().getStyleClass().contains("tone-wrn"));
            assertEquals("2 failed jobs. Details in Logs.", h.overview.failedJobsLabel().getText());
            h.close();
            return null;
        });
    }

    @Test
    void aHundredAndFiftyUpdatesReuseEveryNodeAndUnchangedPollsTouchNothing() throws Exception {
        DeskHarness.fx(() -> {
            for (int[] size : new int[][] {{1440, 900}, {1920, 1080}}) {
                ResearchHarness h = ResearchHarness.open(size[0], size[1], ResearchFixtures.trainReady(34, true));
                int nodes = h.overview.nodeCount();
                Set<Node> before = identities(h.overview);
                int loops = h.motion.runningLoops();
                for (int i = 0; i < 150; i++) {
                    Snapshot s = ResearchFixtures.trainReady(34, true);
                    s.anchorCount = 209_478L + i;
                    s.capture = new panel.model.CaptureInfo("RUNNING", "CONNECTED", "Binance", "USD-M-FUTURES", "ETHUSDT", "s-active", "21:" + (10 + i % 40), null, null, null, null, null, null, null, null, null);
                    h.show(s);
                }
                assertEquals(nodes, h.overview.nodeCount(), size[0] + ": constant node count");
                assertEquals(before, identities(h.overview), size[0] + ": same instances (pipeline, strip, panels are not rebuilt)");
                assertEquals(loops, h.motion.runningLoops(), "no animation restarted or accumulated");
                assertEquals("209,627", h.overview.kpis().get(2).value().getText());
                int applied = h.overview.applied();
                for (int i = 0; i < 100; i++) {
                    Snapshot same = h.source.snapshot.copy();
                    h.show(same);
                }
                assertEquals(applied, h.overview.applied(), "an unchanged poll is skipped");
                assertEquals(100, h.overview.skipped());
                h.close();
            }
            return null;
        });
    }

    @Test
    void switchingBreakpointKeepsDataAndNodes() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(34, true));
            var tile = h.overview.tiles().get(2);
            var seg = h.overview.segments().get(3);
            h.resize(1920, 1080);
            h.show(h.source.snapshot);
            assertSame(tile, h.overview.tiles().get(2));
            assertSame(seg, h.overview.segments().get(3));
            assertEquals(35, h.overview.segments().size());
            assertEquals(2, h.motion.runningLoops());
            h.close();
            return null;
        });
    }

    @Test
    void everyStateEndsIdenticallyInFullReducedAndOff() throws Exception {
        List<Snapshot> states = List.of(ResearchFixtures.empty(), ResearchFixtures.trainReady(34, true), ResearchFixtures.trainReady(34, false),
                ResearchFixtures.trainReady(8, true));
        List<List<String>> perMode = new ArrayList<>();
        for (MotionPreference mode : MotionPreference.values()) {
            List<String> digests = new ArrayList<>();
            DeskHarness.fx(() -> {
                ResearchHarness h = ResearchHarness.open(1920, 1080, ResearchFixtures.empty(), mode);
                for (Snapshot s : states) {
                    h.show(s);
                    digests.add(String.join("\n", DeskNodes.digest(h.overview)));
                }
                if (mode != MotionPreference.FULL) {
                    assertEquals(0, h.motion.runningLoops(), mode + ": no loop outside FULL");
                }
                h.close();
                return null;
            });
            perMode.add(digests);
        }
        for (int m = 1; m < perMode.size(); m++) {
            assertEquals(perMode.get(0), perMode.get(m), MotionPreference.values()[m] + " ends in the FULL state");
        }
    }

    @Test
    void theOverviewIsV2OnlyWithNoLegacyStyle() throws Exception {
        DeskHarness.fx(() -> {
            ResearchHarness h = ResearchHarness.open(1440, 900, ResearchFixtures.trainReady(34, true));
            assertEquals(ByxTheme.urls(), h.scene.getStylesheets());
            assertTrue(h.shell.content().getChildren().isEmpty(), "nothing of Research lives in the legacy host");
            for (String legacy : List.of("card", "card-title", "badge", "badge-ok", "muted", "kv-value", "metric", "pipeline-line", "session-segment", "guard-strip")) {
                DeskNodes.walk(h.overview, n -> assertFalse(n.getStyleClass().contains(legacy), legacy));
            }
            var value = h.overview.kpis().get(1).value();
            assertEquals("JetBrains Mono SemiBold", value.getFont().getFamily());
            assertEquals(30, value.getFont().getSize(), 0.01);
            assertEquals(javafx.scene.paint.Color.web("#EEF1F8"), value.getTextFill());
            assertNotNull(h.overview.lookup("#research-sessions"));
            h.close();
            return null;
        });
    }
}
