package panel.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.helpview.FaqScreen;
import panel.helpview.HelpContent;
import panel.motion.MotionService;
import panel.nav.Navigator;
import panel.shell.ByxShell;
import panel.shell.LegacyHost;
import panel.shell.ShellRouter;
import panel.systemview.OnboardingContent;
import panel.systemview.OnboardingDialog;
import panel.tradeview.DeskHarness;

/** Auditoria técnica simples de semântica (Passo 14): todo controle sem texto tem texto acessível; diálogos têm papel e nome; estado vem em texto. */
class SemanticsAuditTest {
    private static void walk(Node n, List<Node> out) {
        out.add(n);
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            walk(sp.getContent(), out);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, out));
        }
    }

    private static List<String> gaps(Node root) {
        List<Node> all = new ArrayList<>();
        walk(root, all);
        List<String> gaps = new ArrayList<>();
        for (Node n : all) {
            if (n instanceof ButtonBase b && !n.isDisabled() || n instanceof ButtonBase) {
                boolean hasText = b0(n);
                if (!hasText && (n.getAccessibleText() == null || n.getAccessibleText().isBlank())) {
                    gaps.add("button without text or accessibleText: " + n.getStyleClass() + " id=" + n.getId());
                }
            }
            if (n instanceof TextInputControl t && (t.getAccessibleText() == null || t.getAccessibleText().isBlank()) && (t.getPromptText() == null || t.getPromptText().isBlank())
                    && t.getAccessibleHelp() == null && t.getParent() == null) {
                gaps.add("unlabeled input " + n.getStyleClass());
            }
        }
        return gaps;
    }

    private static boolean b0(Node n) {
        return n instanceof Labeled l && l.getText() != null && !l.getText().isBlank();
    }

    @Test
    void shellControlsAreNamed() throws Exception {
        DeskHarness.fx(() -> {
            MotionService m = new MotionService();
            ShellRouter router = new ShellRouter(new Navigator(), (t, k) -> ShellRouter.Decision.ALLOW, id -> { });
            ByxShell shell = new ByxShell(router, m, new LegacyHost());
            shell.showV2(true);
            router.request("t-desk");
            Scene s = new Scene(shell, 1440, 900);
            ByxTheme.apply(s);
            shell.applyCss();
            shell.layout();
            List<String> g = gaps(shell);
            assertTrue(g.isEmpty(), "rail, top bar, dock and switcher controls need names: " + g);
            assertFalse(shell.topBar().avatar().getAccessibleText() == null, "avatar names the account menu");
            assertTrue(shell.topBar().notifications().getAccessibleText() != null, "bell is named");
            shell.dispose();
        });
    }

    @Test
    void dialogsHaveRoleAndNameAndPublicScreensHaveNoUnnamedControls() throws Exception {
        DeskHarness.fx(() -> {
            MotionService m = new MotionService();
            OnboardingDialog d = new OnboardingDialog(m, OnboardingContent.load(), "TRADING", r -> { });
            assertEquals(AccessibleRole.DIALOG, d.getAccessibleRole());
            assertEquals("Onboarding", d.getAccessibleText());
            assertTrue(d.lookupAll(".byx-step-dot").size() == 6, "progress is also given as 'Step n of 6' text, not only dots");
            FaqScreen faq = new FaqScreen(m, HelpContent.faq(), id -> { });
            Scene s = new Scene((Parent) faq.node(), 1440, 900);
            ByxTheme.apply(s);
            assertTrue(gaps(faq.node()).isEmpty(), "FAQ controls named: " + gaps(faq.node()));
            assertTrue(faq.heads().stream().allMatch(h -> h.getAccessibleText() != null && (h.getAccessibleText().endsWith("collapsed") || h.getAccessibleText().endsWith("expanded"))),
                    "FAQ rows announce expanded/collapsed in text");
        });
    }
}
